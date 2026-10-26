/** Bounded SSE framing for the dedicated Mobile client graph stream. */
import { createParser, type EventSourceMessage } from 'eventsource-parser'
import { Transform, type TransformCallback } from 'node:stream'

/** Maximum retained UTF-8 frame/output bytes, with CRLF normalized to LF. */
export const MOBILE_GRAPH_EVENT_MAX_BYTES = 4 * 1024 * 1024
const LINE_FEED = Buffer.from('\n')

type GraphEventFailure = 'invalid_utf8' | 'invalid_sse' | 'invalid_json' | 'unsupported_event'
  | 'frame_too_large' | 'incomplete_frame' | 'projection_failed' | 'invalid_projection' | 'stream_closed'

/** Fixed failures contain neither an upstream payload nor a projection exception. */
class MobileGraphEventError extends Error {
  constructor(readonly code: GraphEventFailure) {
    super(`Mobile graph event stream: ${code}`)
    this.name = 'MobileGraphEventError'
  }
}

class MobileGraphEventTransform extends Transform {
  private frame = Buffer.allocUnsafe(16 * 1024)
  private used = 0
  private lineBytes = 0
  private skipLf = false
  private firstFrame = true
  private disposed = false
  private readWait: { resolve: () => void; reject: (error: Error) => void } | undefined

  constructor(private readonly project: (value: unknown) => Promise<unknown | undefined>) {
    super()
  }

  override _transform(chunk: Buffer, _encoding: BufferEncoding, callback: TransformCallback): void {
    void this.consume(chunk).then(() => callback(), error => callback(error))
  }

  override _read(size: number): void {
    super._read(size)
    const waiting = this.readWait
    this.readWait = undefined
    waiting?.resolve()
  }

  override _flush(callback: TransformCallback): void {
    if (this.used !== 0) {
      try { this.decodeFrame() } catch { callback(new MobileGraphEventError('invalid_utf8')); return }
      callback(new MobileGraphEventError('incomplete_frame'))
      return
    }
    callback()
  }

  override _destroy(error: Error | null, callback: (error?: Error | null) => void): void {
    this.disposed = true
    const waiting = this.readWait
    this.readWait = undefined
    waiting?.reject(error ?? new MobileGraphEventError('stream_closed'))
    this.frame = Buffer.alloc(0)
    this.used = 0
    callback(error)
  }

  private requireOpen(): void {
    if (this.disposed || this.destroyed) throw new MobileGraphEventError('stream_closed')
  }

  private append(bytes: Buffer): void {
    const required = this.used + bytes.length
    if (required > MOBILE_GRAPH_EVENT_MAX_BYTES) throw new MobileGraphEventError('frame_too_large')
    if (required > this.frame.length) {
      const next = Buffer.allocUnsafe(Math.min(MOBILE_GRAPH_EVENT_MAX_BYTES, Math.max(required, this.frame.length * 2)))
      this.frame.copy(next, 0, 0, this.used)
      this.frame = next
    }
    bytes.copy(this.frame, this.used)
    this.used = required
  }

  private async consume(chunk: Buffer): Promise<void> {
    let offset = 0
    while (offset < chunk.length) {
      this.requireOpen()
      if (this.skipLf) {
        this.skipLf = false
        if (chunk[offset] === 10) { offset += 1; continue }
      }
      let contentEnd = offset
      while (contentEnd < chunk.length && chunk[contentEnd] !== 13 && chunk[contentEnd] !== 10) contentEnd += 1
      const content = chunk.subarray(offset, contentEnd)
      this.append(content)
      this.lineBytes += content.length
      if (contentEnd === chunk.length) return
      this.append(LINE_FEED)
      this.skipLf = chunk[contentEnd] === 13
      const emptyLine = this.lineBytes === 0
      this.lineBytes = 0
      offset = contentEnd + 1
      if (emptyLine) await this.completeFrame()
    }
  }

  private decodeFrame(): string {
    try {
      return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(this.frame.subarray(0, this.used))
    } catch {
      throw new MobileGraphEventError('invalid_utf8')
    }
  }

  private async completeFrame(): Promise<void> {
    let text = this.decodeFrame()
    this.used = 0
    if (this.firstFrame) {
      this.firstFrame = false
      if (text.startsWith('\uFEFF')) text = text.slice(1)
    }
    let event: EventSourceMessage | undefined
    const parser = createParser({
      maxBufferSize: MOBILE_GRAPH_EVENT_MAX_BYTES,
      onEvent: value => { event = value },
      onError: () => { throw new MobileGraphEventError('invalid_sse') },
    })
    parser.feed(text)
    const lines = text.split('\n')
    for (const line of lines) {
      if (!line.startsWith('event:')) continue
      const type = line.slice(line[6] === ' ' ? 7 : 6)
      if (type !== '' && type !== 'message') throw new MobileGraphEventError('unsupported_event')
    }
    if (event === undefined) {
      await this.forward(Buffer.from(text, 'utf8'))
      return
    }
    if (event.event !== undefined && event.event !== 'message') throw new MobileGraphEventError('unsupported_event')
    let value: unknown
    try { value = JSON.parse(event.data) } catch { throw new MobileGraphEventError('invalid_json') }
    let projected: unknown
    try { projected = await this.project(value) } catch { throw new MobileGraphEventError('projection_failed') }
    this.requireOpen()
    if (projected === undefined) return
    let encoded: string | undefined
    try { encoded = JSON.stringify(projected) } catch { throw new MobileGraphEventError('invalid_projection') }
    if (encoded === undefined) throw new MobileGraphEventError('invalid_projection')
    const metadata = lines.filter(line => line !== '' && line !== 'data' && !line.startsWith('data:'))
    const result = `${metadata.length === 0 ? '' : `${metadata.join('\n')}\n`}data: ${encoded}\n\n`
    if (Buffer.byteLength(result) > MOBILE_GRAPH_EVENT_MAX_BYTES) throw new MobileGraphEventError('frame_too_large')
    await this.forward(Buffer.from(result, 'utf8'))
  }

  private async forward(frame: Buffer): Promise<void> {
    this.requireOpen()
    if (frame.length === 0 || this.push(frame)) return
    await new Promise<void>((resolve, reject) => {
      this.readWait = { resolve, reject }
      if (this.destroyed || this.disposed) {
        this.readWait = undefined
        reject(new MobileGraphEventError('stream_closed'))
      } else if (this.readableLength < this.readableHighWaterMark) {
        this.readWait = undefined
        resolve()
      }
    })
  }
}

/**
 * Project each complete JSON message serially without forwarding unprojected data.
 * Comments and valid SSE metadata are retained; only the default message event is supported.
 * Undefined suppresses that message. Malformed, oversized or truncated input terminates the stream.
 * @param project - Projects one message; caller-owned cancellation must stop any external work.
 * @returns A byte-mode Transform that honors downstream backpressure and fences late results after destroy.
 */
export function createMobileGraphEventTransform(project: (value: unknown) => Promise<unknown | undefined>): Transform {
  return new MobileGraphEventTransform(project)
}
