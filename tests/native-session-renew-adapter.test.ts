import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import vm from 'node:vm'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MobileRecoveryBridge } from '../src/client-recovery.js'

const source = readFileSync(new URL('../apps/mobile/android/app/src/main/java/io/github/sayach/dshmobile/NativeBridge.kt', import.meta.url), 'utf8')
const adapter = source.match(/private fun pageAdapterScript\(\): String = """([\s\S]*?)const previousChromeSync/u)?.[1]
if (adapter === undefined) throw new Error('Native renewal adapter source was not found')
// The activity-picker branch is not executed here; these tests own auth.renew only.
const script = `${adapter.replaceAll('$JS_OBJECT_NAME', 'dshMobileNative')
  .replaceAll('$MAX_MESSAGE_BYTES', String(1024 * 1024))
  .replaceAll('${NativeBridgePolicy.PAGE_ACTIVITY_TIMEOUT_MS}', '0')}
})();`

interface NativeMessage {
  readonly version: number
  readonly requestId: string
  readonly action?: string
  readonly event?: string
}

interface AdapterPage extends EventTarget {
  readonly dshMobileNative: {
    postMessage(raw: string): void
    onmessage?: (event: { readonly data: string }) => void
  }
  __DSH_MOBILE_NATIVE__?: MobileRecoveryBridge
  __DSH_MOBILE_NATIVE_STATE__?: { readonly pending: Map<string, object> }
}

function fixture() {
  vi.useFakeTimers()
  const messages: NativeMessage[] = []
  const page: AdapterPage = Object.assign(new EventTarget(), {
    dshMobileNative: { postMessage(raw: string) { messages.push(JSON.parse(raw) as NativeMessage) } },
  })
  vm.runInNewContext(script, {
    window: page, Event, TextEncoder, crypto: { randomUUID }, setTimeout, clearTimeout,
  })
  const bridge = page.__DSH_MOBILE_NATIVE__
  if (bridge === undefined) throw new Error('Native renewal adapter was not installed')
  return {
    bridge, messages,
    pending: () => page.__DSH_MOBILE_NATIVE_STATE__?.pending.size,
    reply(requestId: string, value: object, ok = true) {
      page.dshMobileNative.onmessage?.({ data: JSON.stringify({ requestId, ok, value }) })
    },
  }
}

afterEach(() => {
  expect(vi.getTimerCount()).toBe(0)
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('actual Android renewal page adapter', () => {
  it('advertises renewal but never posts a request whose signal was already cancelled', async () => {
    const mounted = fixture()
    expect(await mounted.bridge.capabilities()).toContain('auth.renew')
    const cancellation = new AbortController()
    cancellation.abort()
    await expect(mounted.bridge.invoke('auth.renew', {}, { signal: cancellation.signal }))
      .rejects.toMatchObject({ name: 'AbortError', code: 'cancelled' })
    expect(mounted.messages).toEqual([])
    expect(mounted.pending()).toBe(0)
  })

  it('cancels only the exact active request and ignores its late success', async () => {
    const mounted = fixture()
    const cancellation = new AbortController()
    const removeListener = vi.spyOn(cancellation.signal, 'removeEventListener')
    const first = mounted.bridge.invoke('auth.renew', {}, { signal: cancellation.signal })
    const rejected = expect(first).rejects.toMatchObject({ name: 'AbortError', code: 'cancelled' })
    const firstId = mounted.messages[0]?.requestId
    if (firstId === undefined) throw new Error('First native renewal was not posted')
    const second = mounted.bridge.invoke('auth.renew', {})
    const secondId = mounted.messages[1]?.requestId
    if (secondId === undefined) throw new Error('Second native renewal was not posted')
    cancellation.abort()
    await rejected
    expect(mounted.messages.at(-1)).toEqual({ version: 1, event: 'auth.renew.cancel', requestId: firstId })
    expect(mounted.pending()).toBe(1)
    expect(removeListener).toHaveBeenCalledTimes(1)
    mounted.reply(firstId, { renewed: true })
    expect(mounted.pending()).toBe(1)
    mounted.reply(secondId, { renewed: true })
    await expect(second).resolves.toEqual({ renewed: true })
    expect(mounted.pending()).toBe(0)
  })

  it('sends a targeted native cancellation when the page deadline expires', async () => {
    const mounted = fixture()
    const cancellation = new AbortController()
    const removeListener = vi.spyOn(cancellation.signal, 'removeEventListener')
    const response = mounted.bridge.invoke('auth.renew', {}, { signal: cancellation.signal })
    const rejected = expect(response).rejects.toMatchObject({ code: 'timeout' })
    const requestId = mounted.messages[0]?.requestId
    if (requestId === undefined) throw new Error('Native renewal was not posted')
    await vi.advanceTimersByTimeAsync(60_000)
    await rejected
    expect(mounted.messages.at(-1)).toEqual({ version: 1, event: 'auth.renew.cancel', requestId })
    expect(removeListener).toHaveBeenCalledTimes(1)
    expect(mounted.pending()).toBe(0)
    cancellation.abort()
    mounted.reply(requestId, { renewed: true })
    expect(mounted.messages).toHaveLength(2)
  })

  it('removes the abort listener and deadline after the committed native response', async () => {
    const mounted = fixture()
    const cancellation = new AbortController()
    const removeListener = vi.spyOn(cancellation.signal, 'removeEventListener')
    const response = mounted.bridge.invoke('auth.renew', {}, { signal: cancellation.signal })
    const requestId = mounted.messages[0]?.requestId
    if (requestId === undefined) throw new Error('Native renewal was not posted')
    mounted.reply(requestId, { renewed: true })
    await expect(response).resolves.toEqual({ renewed: true })
    expect(removeListener).toHaveBeenCalledTimes(1)
    expect(mounted.pending()).toBe(0)
    cancellation.abort()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(mounted.messages).toHaveLength(1)
  })
})
