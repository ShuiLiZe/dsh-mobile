import assert from 'node:assert/strict'
import { once } from 'node:events'
import { Readable, Writable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { describe, expect, it } from 'vitest'
import { createMobileGraphEventTransform, MOBILE_GRAPH_EVENT_MAX_BYTES } from '../src/mobile-graph-events.js'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

async function readAll(source: Readable): Promise<string> {
  const chunks: Buffer[] = []
  for await (const chunk of source) chunks.push(Buffer.from(chunk as Uint8Array))
  return Buffer.concat(chunks).toString('utf8')
}

async function transform(chunks: readonly (Buffer | string)[], project: (value: unknown) => Promise<unknown | undefined> = async value => value): Promise<string> {
  const stream = createMobileGraphEventTransform(project)
  const reading = readAll(stream)
  const forwarding = pipeline(Readable.from(chunks), stream)
  const [, output] = await Promise.all([forwarding, reading])
  return output
}

function frame(value: unknown): string { return `data: ${JSON.stringify(value)}\n\n` }

describe('dedicated Mobile graph SSE framing', () => {
  it('projects split UTF-8 and CRLF, multi-data JSON, and preserves supported metadata', async () => {
    const input = Buffer.from('\uFEFF: heartbeat\r\nid: revision-1\r\nretry: 1000\r\nevent: message\r\ndata: {"name":\r\ndata: "鲸鱼"}\r\n\r\n')
    const chunks = [...input].map(byte => Buffer.from([byte]))
    const seen: unknown[] = []
    const result = await transform(chunks, async value => {
      seen.push(value)
      return { graph: value }
    })
    expect(seen).toEqual([{ name: '鲸鱼' }])
    expect(result).toBe(': heartbeat\nid: revision-1\nretry: 1000\nevent: message\ndata: {"graph":{"name":"鲸鱼"}}\n\n')
  })

  it('retains comment and control-only frames without calling the graph projector', async () => {
    let called = false
    const input = ': heartbeat\n\nid: revision-2\nretry: 4000\n\n'
    expect(await transform([input], async () => { called = true })).toBe(input)
    expect(called).toBe(false)
    expect(await transform([])).toBe('')
  })

  it('leaves JSON protocol classification to the projector and does not drop falsy results', async () => {
    const values: unknown[] = [null, false, 0, [], { future: 'protocol' }]
    const seen: unknown[] = []
    const input = values.map(frame).join('')
    expect(await transform([input], async value => { seen.push(value); return value })).toBe(input)
    expect(seen).toEqual(values)
  })

  it.each(['\n', '\r\n', '\r'])('handles adjacent complete frames with %j line endings', async newline => {
    const input = `${frame({ n: 1 })}${frame({ n: 2 })}`.replaceAll('\n', newline)
    expect(await transform([input])).toBe(frame({ n: 1 }) + frame({ n: 2 }))
  })

  it('omits only the selected message without emitting its original payload or metadata', async () => {
    const seen: unknown[] = []
    const result = await transform([frame({ n: 1 }) + 'id: dropped\ndata: {"n":2}\n\n' + frame({ n: 3 })], async value => {
      seen.push(value)
      return typeof value === 'object' && value !== null && 'n' in value && value.n === 2 ? undefined : value
    })
    expect(seen).toEqual([{ n: 1 }, { n: 2 }, { n: 3 }])
    expect(result).toBe(frame({ n: 1 }) + frame({ n: 3 }))
  })

  it('serializes projections across multiple frames from one incoming chunk', async () => {
    const entered = deferred<void>()
    const release = deferred<void>()
    const calls: unknown[] = []
    let active = 0
    let maximum = 0
    const completion = transform([frame({ n: 1 }) + frame({ n: 2 })], async value => {
      active += 1
      maximum = Math.max(maximum, active)
      calls.push(value)
      try {
        if (calls.length === 1) { entered.resolve(); await release.promise }
        return value
      } finally { active -= 1 }
    })
    try {
      await entered.promise
      expect(calls).toEqual([{ n: 1 }])
      release.resolve()
      expect(await completion).toBe(frame({ n: 1 }) + frame({ n: 2 }))
      expect(maximum).toBe(1)
    } finally { release.resolve(); await completion.catch(() => {}) }
  })

  it.each([
    ['data: not-json\n\n', 'invalid_json'],
    ['data:\n\n', 'invalid_json'],
    ['event: other\ndata: {}\n\n', 'unsupported_event'],
    ['event: other\n\n', 'unsupported_event'],
    ['retry: later\n\n', 'invalid_sse'],
    ['unknown: value\ndata: {}\n\n', 'invalid_sse'],
    ['data: {}\n', 'incomplete_frame'],
    ['data: {}', 'incomplete_frame'],
  ])('rejects malformed or truncated input without raw fallback: %j', async (input, code) => {
    await expect(transform([input])).rejects.toMatchObject({ name: 'MobileGraphEventError', code })
  })

  it.each([true, false])('rejects malformed UTF-8, including unfinished EOF (complete=%s)', async complete => {
    const bytes = Buffer.concat([Buffer.from('data: "'), Buffer.from([0xc3]), ...(complete ? [Buffer.from('"\n\n')] : [])])
    await expect(transform([bytes])).rejects.toMatchObject({ code: 'invalid_utf8' })
  })

  it('rejects a single oversized line before invoking the projector', async () => {
    let called = false
    const input = `data: {"value":"${'鲸'.repeat(Math.ceil(MOBILE_GRAPH_EVENT_MAX_BYTES / 3))}"}\n\n`
    await expect(transform([input], async () => { called = true })).rejects.toMatchObject({ code: 'frame_too_large' })
    expect(called).toBe(false)
  })

  it('bounds a never-dispatched frame made from many data lines', async () => {
    await expect(transform(['data: 1\n'.repeat(Math.ceil(MOBILE_GRAPH_EVENT_MAX_BYTES / 8) + 1)])).rejects.toMatchObject({ code: 'frame_too_large' })
  })

  it('bounds projected output and rejects non-JSON results', async () => {
    await expect(transform([frame({})], async () => ({ value: 'x'.repeat(MOBILE_GRAPH_EVENT_MAX_BYTES) }))).rejects.toMatchObject({ code: 'frame_too_large' })
    await expect(transform([frame({})], async () => 1n)).rejects.toMatchObject({ code: 'invalid_projection' })
    await expect(transform([frame({})], async () => () => {})).rejects.toMatchObject({ code: 'invalid_projection' })
  })

  it('contains projector rejection without exposing its message or forwarding the input', async () => {
    await expect(transform([frame({ private: 'owned-input' })], async () => {
      throw new Error('Owned sensitive detail')
    })).rejects.toMatchObject({ code: 'projection_failed', message: 'Mobile graph event stream: projection_failed' })
  })

  it.each(['resolve', 'reject'] as const)('never pushes a late projection after destroy (%s)', async outcome => {
    const entered = deferred<void>()
    const held = deferred<unknown>()
    const stream = createMobileGraphEventTransform(async () => { entered.resolve(); return held.promise })
    const output: Buffer[] = []
    stream.on('data', chunk => { output.push(Buffer.from(chunk)) })
    stream.on('error', () => {})
    const write = new Promise<Error | undefined>(resolve => { stream.write(frame({ raw: true }), error => resolve(error ?? undefined)) })
    try {
      await entered.promise
      const closed = once(stream, 'close')
      stream.destroy()
      await closed
      if (outcome === 'resolve') held.resolve({ late: true }); else held.reject(new Error('Owned late failure'))
      await write
      expect(output).toEqual([])
      expect(stream.destroyed).toBe(true)
    } finally {
      held.resolve(undefined)
      stream.destroy()
      await write
    }
  })

  it('stops before projecting the next frame while downstream is backpressured', async () => {
    let calls = 0
    let stream = createMobileGraphEventTransform(async () => {
      calls += 1
      return { value: 'x'.repeat(stream.readableHighWaterMark) }
    })
    const readable = once(stream, 'readable')
    const wrote = new Promise<void>((resolve, reject) => stream.write(frame({ n: 1 }) + frame({ n: 2 }) + frame({ n: 3 }), error => error ? reject(error) : resolve()))
    const reading = readable.then(async () => {
      expect(calls).toBe(1)
      expect(stream.readableLength).toBeLessThan(MOBILE_GRAPH_EVENT_MAX_BYTES)
      return readAll(stream)
    })
    stream.end()
    try {
      const [, output] = await Promise.all([wrote, reading])
      expect(calls).toBe(3)
      expect(output.match(/data:/gu)).toHaveLength(3)
    } finally { stream.destroy(); await Promise.allSettled([wrote, reading]) }
  })

  it('destroy releases a pending backpressure wait and does not project queued frames', async () => {
    let calls = 0
    let stream = createMobileGraphEventTransform(async () => {
      calls += 1
      return { value: 'x'.repeat(stream.readableHighWaterMark) }
    })
    stream.on('error', () => {})
    const readable = once(stream, 'readable')
    const wrote = new Promise<void>(resolve => stream.write(frame({ n: 1 }) + frame({ n: 2 }), () => resolve()))
    try {
      await readable
      const closed = once(stream, 'close')
      stream.destroy()
      await Promise.all([closed, wrote])
      expect(calls).toBe(1)
    } finally { stream.destroy(); await wrote }
  })

  it('pipeline failure closes the upstream and downstream owners', async () => {
    let sourceClosed = false
    let sinkClosed = false
    const source = new Readable({ read() { this.push('data: not-json\n\n') }, destroy(error, done) { sourceClosed = true; done(error) } })
    const sink = new Writable({ write(_chunk, _encoding, done) { done() }, destroy(error, done) { sinkClosed = true; done(error) } })
    await assert.rejects(pipeline(source, createMobileGraphEventTransform(async value => value), sink), { code: 'invalid_json' })
    expect(sourceClosed).toBe(true)
    expect(sinkClosed).toBe(true)
    expect(source.destroyed).toBe(true)
    expect(sink.destroyed).toBe(true)
  })
})
