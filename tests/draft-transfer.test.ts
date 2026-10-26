import { afterEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { createDraftTransfer, DRAFT_TRANSFER_MAX_BYTES, installDraftTransfer, type DraftTransferInput } from '../src/draft-transfer.js'

const pendingEffects = vi.hoisted(() => [] as Array<() => void | (() => void)>)
vi.mock('react', async importOriginal => ({
  ...await importOriginal<typeof import('react')>(),
  useEffect: (effect: () => void | (() => void)) => { pendingEffects.push(effect) },
}))

function fixture(text = '') {
  vi.stubGlobal('MutationObserver', class { observe() {} disconnect() {} })
  const page = Object.assign(new EventTarget(), { documentElement: {}, querySelector: () => null, querySelectorAll: () => [], closest: () => ({}) }) as unknown as Document
  const view = { location: { href: 'https://source.test/' }, crypto: webcrypto,
    setTimeout: globalThis.setTimeout, clearTimeout: globalThis.clearTimeout } as unknown as Window
  let state = { draft: text, draftRev: 0, phase: 'plain', attachmentIds: [] as unknown[], occurrences: [] as unknown[], queue: [] as unknown[] }
  const listeners = new Set<() => void>()
  const update = (patch: Partial<typeof state>) => { state = { ...state, ...patch }; for (const callback of listeners) callback() }
  const input: DraftTransferInput = { state: { getSnapshot: () => state, subscribe: fn => { listeners.add(fn); return () => { listeners.delete(fn) } } },
    setDraft: value => { update({ draft: value, draftRev: state.draftRev + 1 }) } }
  const controller = createDraftTransfer(view, page)
  const unmount = controller.observe('session-1', input)
  return { ...controller, page, view, input, update, unmount, listeners }
}
afterEach(() => { pendingEffects.length = 0; vi.unstubAllGlobals(); vi.useRealTimers() })
describe('limited public input draft transfer', () => {
  it('rejects late composer observation and releases each subscription only once', () => {
    const f = fixture('retained')
    const listeners = new Set<() => void>()
    const unsubscribe = vi.fn(() => { listeners.clear() })
    const subscribe = vi.fn((listener: () => void) => { listeners.add(listener); return unsubscribe })
    const input: DraftTransferInput = {
      state: { getSnapshot: f.input.state.getSnapshot, subscribe }, setDraft: f.input.setDraft,
    }
    const remove = f.observe('session-2', input)
    expect(subscribe).toHaveBeenCalledOnce()
    f.dispose(); f.dispose(); remove(); remove()
    expect(unsubscribe).toHaveBeenCalledOnce()
    expect(listeners.size).toBe(0)
    const lateRemove = f.observe('session-2', input)
    lateRemove(); lateRemove()
    expect(subscribe).toHaveBeenCalledOnce()
    expect(f.api.capture()).toBeNull()
  })
  it('does not reacquire services or subscribe when a queued passive effect runs after disposal', () => {
    const f = fixture('retained')
    let renderObserver: (() => void) | undefined
    const subscribe = vi.spyOn(f.input.state, 'subscribe')
    const removeSlot = vi.fn()
    const get = vi.fn((name: string) => name === 'sessions'
      ? { scope: () => ({}) } : { input: { for: () => f.input } })
    const ctx: Parameters<typeof installDraftTransfer>[0] = {
      get,
      slots: {
        inject: (_name, registration) => registration(),
        register: (_options, component) => {
          renderObserver = () => { Reflect.apply(component, undefined, [{ sessionId: 'session-1' }]) }
          return removeSlot
        },
      },
    }
    const dispose = installDraftTransfer(ctx, f.view, f.page)
    renderObserver!()
    expect(pendingEffects).toHaveLength(1)
    dispose(); dispose()
    expect(removeSlot).toHaveBeenCalledOnce()
    pendingEffects.shift()!()
    expect(get).not.toHaveBeenCalled()
    expect(subscribe).not.toHaveBeenCalled()
    expect(f.view.__dshMobileDraftTransfer).toBeUndefined()
    f.dispose()
  })
  it('captures/validates plain text and restores exact text without submission', () => {
    const source = fixture('  a\n\n"\\😀\u2028\n')
    const snapshot = source.api.capture()!
    expect(snapshot).toMatchObject({ version: 1, sessionId: 'session-1', text: '  a\n\n"\\😀\u2028\n' })
    expect(source.api.validate(JSON.parse(JSON.stringify(snapshot)))).toBe(true)
    const target = fixture()
    expect(target.api.restore(snapshot)).toBe(true)
    expect(target.input.state.getSnapshot().draft).toBe(snapshot.text)
    expect(target.api.restore(snapshot)).toBe(true)
    target.input.setDraft('conflict')
    expect(target.api.restore(snapshot)).toBe(false)
    expect(target.input.state.getSnapshot().draft).toBe('conflict')
    source.dispose(); target.dispose()
  })
  it('rejects reserved editor placeholders before writing', () => {
    const source = fixture('ok'); const s = source.api.capture()!
    const target = fixture(); const writer = vi.spyOn(target.input, 'setDraft')
    for (const char of ['\uE100', '\uE11D', '\uFFFC']) {
      expect(target.api.restore({ ...s, text: char })).toBe(false)
      const invalid = fixture(char); expect(invalid.api.capture()).toBeNull(); invalid.dispose()
    }
    expect(writer).not.toHaveBeenCalled(); source.dispose(); target.dispose()
  })
  it('can undo only its own proven partial normalized write', () => {
    const source = fixture('A\rB'); const s = source.api.capture()!
    const target = fixture()
    target.input.setDraft = value => { target.update({ draft: value.replaceAll('\r', ''), draftRev: 1 }) }
    expect(target.api.restore(s)).toBe(false)
    expect(target.input.state.getSnapshot().draft).toBe('AB')
    expect(target.api.rollback(s)).toBe(true)
    expect(target.input.state.getSnapshot().draft).toBe('')
    source.dispose(); target.dispose()
  })
  it('never rewrites a changed imported target, including empty and ABA', () => {
    const source = fixture('A'); const s = source.api.capture()!
    const target = fixture(); expect(target.api.restore(s)).toBe(true)
    expect(target.api.verify(s)).toBe(true)
    target.input.setDraft(''); expect(target.api.restore(s)).toBe(false)
    expect(target.input.state.getSnapshot().draft).toBe('')
    target.input.setDraft('A'); expect(target.api.verify(s)).toBe(false)
    expect(target.api.rollback(s)).toBe(false)
    expect(target.input.state.getSnapshot().draft).toBe('A')
    source.dispose(); target.dispose()
  })
  it('rolls back only its own unchanged import, never preexisting matching text', () => {
    const source = fixture('A'); const s = source.api.capture()!
    const target = fixture(); expect(target.api.restore(s)).toBe(true)
    expect(target.api.rollback(s)).toBe(true)
    expect(target.input.state.getSnapshot().draft).toBe('')
    expect(target.api.restore(s)).toBe(false)
    const existing = fixture('A'); expect(existing.api.restore(s)).toBe(true)
    expect(existing.api.rollback(s)).toBe(true)
    expect(existing.input.state.getSnapshot().draft).toBe('A')
    source.dispose(); target.dispose(); existing.dispose()
  })
  it('detects ABA, remount, href and forged token changes', () => {
    const f = fixture('A'); const s = f.api.capture()!
    f.input.setDraft('B'); f.input.setDraft('A')
    expect(f.api.validate(s)).toBe(false)
    f.api.release(s)
    const fresh = f.api.capture()!
    expect(f.api.validate({ ...fresh, token: 'f'.repeat(48) })).toBe(false)
    f.view.location.href = 'https://source.test/other'
    expect(f.api.validate(fresh)).toBe(false)
    f.unmount(); f.observe('session-1', f.input)
    expect(f.api.validate(fresh)).toBe(false)
    f.dispose()
  })
  it.each(['attachmentIds', 'occurrences', 'queue'] as const)('rejects %s permanently even after removal', field => {
    const f = fixture('text'); const s = f.api.capture()!
    f.update({ [field]: ['risk'] }); f.update({ [field]: [] })
    expect(f.api.validate(s)).toBe(false); expect(f.api.capture()).toBeNull(); f.dispose()
  })
  it.each(['adjudicating', 'claimed', 'submitting'])('rejects observed %s phase', phase => {
    const f = fixture(); f.update({ phase }); f.update({ phase: 'plain' })
    expect(f.api.capture()).toBeNull(); f.dispose()
  })
  it('allows known text-only paste but invalidates the active source lease', () => {
    const f = fixture('text'); const s = f.api.capture()!
    const paste = new Event('paste')
    Object.defineProperty(paste, 'clipboardData', { value: { files: [], types: ['text/plain'], items: [{ kind: 'string', type: 'text/plain' }] } })
    f.page.dispatchEvent(paste); expect(f.api.validate(s)).toBe(false)
    f.api.release(s); expect(f.api.capture()).not.toBeNull(); f.dispose()
  })
  it('allows composer input but blocks unsaved external form activity', () => {
    const f = fixture('composer'); const old = f.api.capture()!
    f.page.dispatchEvent(new Event('input')); expect(f.api.validate(old)).toBe(false)
    f.api.release(old); const next = f.api.capture()!; expect(next).not.toBeNull(); f.api.release(next)
    Object.assign(f.page, { closest: () => null })
    f.page.dispatchEvent(new Event('beforeinput')); expect(f.api.capture()).toBeNull(); f.dispose()
  })
  it('rejects a visible public aria-modal dialog without reading its text', () => {
    const f = fixture('text')
    const modal = { hidden: false, closest: () => null, getClientRects: () => [{}] }
    Object.assign(f.page, { querySelectorAll: () => [modal] })
    Object.assign(f.view, { getComputedStyle: () => ({ display: 'block', visibility: 'visible' }) })
    expect(f.api.capture()).toBeNull()
    modal.hidden = true; expect(f.api.capture()).not.toBeNull(); f.dispose()
  })
  it('ignores ordinary change controls but rejects file-input change', () => {
    const f = fixture('text')
    f.page.dispatchEvent(new Event('change')); const s = f.api.capture()!
    expect(s).not.toBeNull(); f.api.release(s)
    Object.defineProperty(f.page, 'type', { value: 'file' })
    f.page.dispatchEvent(new Event('change')); expect(f.api.capture()).toBeNull(); f.dispose()
  })
  it('rejects active IME and invalidates its old lease but permits settled Chinese text', () => {
    const f = fixture('中文'); const old = f.api.capture()!
    f.page.dispatchEvent(new Event('compositionstart'))
    expect(f.api.validate(old)).toBe(false); f.api.release(old)
    expect(f.api.capture()).toBeNull()
    f.input.setDraft('中文输入')
    f.page.dispatchEvent(new Event('compositionend'))
    expect(f.api.validate(old)).toBe(false)
    const settled = f.api.capture()!
    expect(settled.text).toBe('中文输入'); expect(f.api.validate(settled)).toBe(true)
    f.dispose()
  })
  it.each(['drop', 'paste', 'submit'])('rejects observed %s', name => {
    const f = fixture(); f.page.dispatchEvent(new Event(name)); expect(f.api.capture()).toBeNull(); f.dispose()
  })
  it('bounds UTF8 bytes, only one active composer, timeout and exact release', () => {
    vi.useFakeTimers()
    const f = fixture('😀'.repeat(DRAFT_TRANSFER_MAX_BYTES / 4 + 1)); expect(f.api.capture()).toBeNull()
    f.input.setDraft('ok'); const old = f.api.capture()!; expect(f.api.capture()).toBeNull()
    f.api.release(old); const fresh = f.api.capture()!
    f.api.release(old); expect(f.api.validate(fresh)).toBe(true)
    const removeSecond = f.observe('session-2', f.input); expect(f.api.capture()).toBeNull(); removeSecond()
    f.api.release(fresh)
    const last = f.api.capture()!; vi.advanceTimersByTime(30_000); expect(f.api.validate(last)).toBe(false)
    f.dispose(); expect(f.listeners.size).toBe(0); expect(f.api.capture()).toBeNull()
  })
})
