import { createElement, useEffect } from 'react'

/** Limited, plain-text-only migration. Not an atomic view of detached drafts,
 * attachment flights, other sessions, media or third-party editor state. */
export interface DraftTransferSnapshot {
  readonly version: 1
  readonly token: string
  readonly href: string
  readonly sessionId: string
  readonly text: string
  readonly revision: number
}
export interface DraftTransferApi {
  capture(): DraftTransferSnapshot | null
  validate(snapshot: unknown): boolean
  restore(snapshot: unknown): boolean
  verify(snapshot: unknown): boolean
  rollback(snapshot: unknown): boolean
  release(snapshot: unknown): void
}
interface InputState {
  readonly draft: string
  readonly draftRev: number
  readonly phase: string
  readonly attachmentIds: readonly unknown[]
  readonly occurrences: readonly unknown[]
  readonly queue: readonly unknown[]
  readonly claim?: unknown
}
export interface DraftTransferInput {
  readonly state: { getSnapshot(): InputState; subscribe(listener: () => void): () => void }
  setDraft(text: string): void
}
interface Context {
  get(name: string): unknown
  slots: {
    inject(key: string, callback: () => (() => void)): () => void
    register<Props>(options: { name: string; id: string }, component: (props: Props) => unknown): () => void
  }
}
declare global { interface Window { __dshMobileDraftTransfer?: DraftTransferApi } }
export const DRAFT_TRANSFER_MAX_BYTES = 256 * 1024
const LEASE_MS = 30_000

function transferableText(text: string): boolean {
  // DSH's public editor strips its chip placeholders; rejecting is lossless,
  // whereas silently normalizing them would corrupt a supposedly exact copy.
  return !/[\uE100-\uE11D\uFFFC]/u.test(text) && new TextEncoder().encode(text).length <= DRAFT_TRANSFER_MAX_BYTES
}
function snapshotShape(value: unknown): value is DraftTransferSnapshot {
  if (value === null || typeof value !== 'object') return false
  const s = value as Partial<DraftTransferSnapshot>
  return s.version === 1 && typeof s.token === 'string' && /^[a-f0-9]{48}$/u.test(s.token)
    && typeof s.href === 'string' && s.href.length <= 8192 && typeof s.sessionId === 'string'
    && s.sessionId.length > 0 && s.sessionId.length <= 1024 && typeof s.text === 'string'
    && transferableText(s.text)
    && Number.isSafeInteger(s.revision) && (s.revision ?? -1) >= 0
}
function plain(s: InputState): boolean {
  return typeof s.draft === 'string' && Number.isSafeInteger(s.draftRev) && s.draftRev >= 0
    && s.phase === 'plain' && s.claim === undefined
    && Array.isArray(s.attachmentIds) && s.attachmentIds.length === 0
    && Array.isArray(s.occurrences) && s.occurrences.length === 0
    && Array.isArray(s.queue) && s.queue.length === 0
}

/** Public state subscriptions are kept for the lifetime of each observed composer,
 * not just the lease: attachment/submit uncertainty stays sticky until reload. */
export function createDraftTransfer(view: Window, page: Document): {
  api: DraftTransferApi
  observe(sessionId: string, input: DraftTransferInput): () => void
  dispose(): void
} {
  const observed = new Map<object, { sessionId: string; input: DraftTransferInput }>()
  const subscriptions = new Set<() => void>()
  let epoch = 0, risky = false, disposed = false, composing = false
  let lease: DraftTransferSnapshot | undefined
  let timer: number | undefined
  let imported: { snapshot: DraftTransferSnapshot; entry: { sessionId: string; input: DraftTransferInput }; epoch: number; href: string; wrote: boolean; actualText: string; successful: boolean } | undefined
  let importTimer: number | undefined
  let importClosed = false
  const clearImport = (): void => { if (imported !== undefined) importClosed = true; imported = undefined; if (importTimer !== undefined) view.clearTimeout(importTimer); importTimer = undefined }
  const invalidate = (): void => { epoch += 1 }
  const clearLease = (): void => { lease = undefined; if (timer !== undefined) view.clearTimeout(timer); timer = undefined }
  const risk = (): void => { risky = true; invalidate() }
  // Events are risk observation only: never inspect editor text, DOM internals or patch core.
  const onActivity = (event: Event): void => {
    const inComposer = Boolean((event.target as Element | null)?.closest?.('[data-composer-card]'))
    if (['input', 'beforeinput', 'paste', 'compositionstart', 'compositionend'].includes(event.type) && !inComposer) {
      risk()
      return
    }
    if (event.type === 'compositionstart' || event.type === 'compositionend') {
      composing = event.type === 'compositionstart'
      invalidate()
      return
    }
    if (event.type === 'change' && (event.target as HTMLInputElement | null)?.type !== 'file') return
    if (event.type === 'paste') {
      const clipboard = (event as ClipboardEvent).clipboardData
      if (clipboard !== null && clipboard !== undefined && clipboard.files.length === 0
        && clipboard.types.length === 1 && clipboard.types[0] === 'text/plain'
        && Array.from(clipboard.items).every(item => item.kind === 'string' && item.type === 'text/plain')) {
        invalidate()
        return
      }
    }
    if (event.type === 'input' || event.type === 'beforeinput') invalidate()
    else risk()
  }
  const onClick = (event: Event): void => {
    const target = event.target as Element | null
    if (target?.closest?.('[data-composer-card] button, [data-dsh-mobile-media-action], [data-voice-activity]')) risk()
  }
  // These published activity markers are observed solely to reject voice/media
  // uncertainty, never to extract draft content or infer session identity.
  const checkVoice = (): void => {
    if (page.querySelector('[data-voice-activity="requesting"], [data-voice-activity="recording"], [data-voice-activity="transcribing"]') !== null) risk()
  }
  const voiceObserver = new MutationObserver(checkVoice)
  voiceObserver.observe(page.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ['data-voice-activity'] })
  checkVoice()
  page.addEventListener('click', onClick, true)
  const events = ['compositionstart', 'compositionend', 'drop', 'paste', 'change', 'submit', 'input', 'beforeinput',
    'dsh-mobile:voice-start', 'dsh-mobile:voice-stop']
  for (const name of events) page.addEventListener(name, onActivity, true)
  const current = (): { sessionId: string; input: DraftTransferInput } | undefined => {
    if (disposed || risky || composing || observed.size !== 1) return undefined
    for (const modal of page.querySelectorAll<HTMLElement>('[role="dialog"][aria-modal="true"]')) {
      if (!modal.hidden && !modal.closest('[hidden], [inert], [aria-hidden="true"]') && modal.getClientRects().length > 0) {
        const style = view.getComputedStyle(modal)
        if (style.display !== 'none' && style.visibility !== 'hidden') return undefined
      }
    }
    const entry = observed.values().next().value
    if (entry === undefined || !plain(entry.input.state.getSnapshot())) return undefined
    return entry
  }
  const api: DraftTransferApi = {
    capture() {
      if (lease !== undefined) return null
      const entry = current()
      if (entry === undefined) return null
      const state = entry.input.state.getSnapshot()
      if (!transferableText(state.draft)) return null
      const bytes = new Uint8Array(24)
      try { view.crypto.getRandomValues(bytes) } catch { return null }
      lease = { version: 1, token: Array.from(bytes, n => n.toString(16).padStart(2, '0')).join(''),
        href: view.location.href, sessionId: entry.sessionId, text: state.draft, revision: epoch }
      timer = view.setTimeout(clearLease, LEASE_MS)
      return { ...lease }
    },
    validate(value) {
      if (!snapshotShape(value) || lease === undefined) return false
      const entry = current()
      return entry !== undefined && value.token === lease.token && value.href === lease.href
        && value.href === view.location.href && value.sessionId === lease.sessionId && value.sessionId === entry.sessionId
        && value.revision === lease.revision && value.revision === epoch && value.text === lease.text
        && value.text === entry.input.state.getSnapshot().draft
    },
    restore(value) {
      if (!snapshotShape(value)) return false
      if (imported !== undefined) return api.verify(value)
      if (importClosed) return false
      const entry = current()
      if (entry === undefined || entry.sessionId !== value.sessionId) return false
      const state = entry.input.state.getSnapshot()
      if (state.draft !== '' && state.draft !== value.text) return false
      const wrote = state.draft !== value.text
      // Establish ownership before the public writer can mutate or throw. Only a
      // proven synchronous plain readback enables rollback of a partial write.
      imported = { snapshot: { ...value }, entry, epoch, href: view.location.href, wrote, actualText: state.draft, successful: false }
      importTimer = view.setTimeout(clearImport, LEASE_MS)
      let writeFailed = false
      if (wrote) { try { entry.input.setDraft(value.text) } catch { writeFailed = true } }
      const actual = entry.input.state.getSnapshot()
      if (current() !== entry || !plain(actual)) return false
      imported.epoch = epoch
      imported.actualText = actual.draft
      imported.successful = !writeFailed && actual.draft === value.text
      return imported.successful
    },
    verify(value) {
      if (!snapshotShape(value) || imported === undefined) return false
      const saved = imported.snapshot
      return imported.successful && value.token === saved.token && value.href === saved.href && value.sessionId === saved.sessionId
        && value.text === saved.text && value.revision === saved.revision && current() === imported.entry
        && epoch === imported.epoch && view.location.href === imported.href
        && imported.entry.input.state.getSnapshot().draft === saved.text
    },
    rollback(value) {
      if (!snapshotShape(value) || imported === undefined) return false
      const record = imported
      const saved = record.snapshot
      if (value.token !== saved.token || value.href !== saved.href || value.sessionId !== saved.sessionId
        || value.text !== saved.text || value.revision !== saved.revision || current() !== record.entry
        || epoch !== record.epoch || view.location.href !== record.href
        || record.entry.input.state.getSnapshot().draft !== record.actualText) return false
      if (record.wrote) record.entry.input.setDraft('')
      const restored = !record.wrote || record.entry.input.state.getSnapshot().draft === ''
      clearImport()
      return restored
    },
    release(value) {
      if (!snapshotShape(value)) return
      if (value.token === lease?.token) clearLease()
      if (value.token === imported?.snapshot.token) clearImport()
    },
  }
  const safeApi: DraftTransferApi = {
    capture() { try { return api.capture() } catch { risk(); clearLease(); return null } },
    validate(value) { try { return api.validate(value) } catch { risk(); return false } },
    restore(value) { try { return api.restore(value) } catch { risk(); return false } },
    verify(value) { try { return api.verify(value) } catch { risk(); return false } },
    rollback(value) { try { return api.rollback(value) } catch { risk(); return false } },
    release(value) { try { api.release(value) } catch { /* malformed native input: no effect */ } },
  }
  return {
    api: safeApi,
    observe(sessionId, input) {
      if (disposed) return () => undefined
      const key = {}, entry = { sessionId, input }
      observed.set(key, entry); invalidate()
      const changed = (): void => {
        invalidate()
        try { if (!plain(input.state.getSnapshot())) risky = true } catch { risky = true }
      }
      changed()
      const unsubscribe = input.state.subscribe(changed)
      const remove = (): void => {
        if (!subscriptions.delete(remove)) return
        unsubscribe(); observed.delete(key); invalidate()
      }
      subscriptions.add(remove)
      return remove
    },
    dispose() { if (disposed) return; disposed = true; voiceObserver.disconnect(); page.removeEventListener('click', onClick, true); clearLease(); clearImport(); for (const remove of subscriptions) remove(); subscriptions.clear(); observed.clear(); for (const name of events) page.removeEventListener(name, onActivity, true) },
  }
}

/** Install only on the authenticated Mobile frontend; identity comes from the
 * real public session-scoped composer slot, state/writes from the public facade. */
export function installDraftTransfer(ctx: Context, view: Window, page: Document): () => void {
  const controller = createDraftTransfer(view, page)
  const previous = Object.getOwnPropertyDescriptor(view, '__dshMobileDraftTransfer')
  const mounted = new Map<object, string>()
  let active = true
  let requestedSession: string | undefined
  let importedToken: string | undefined
  const api: DraftTransferApi = {
    ...controller.api,
    restore(value) {
      if (!active || !snapshotShape(value)) return false
      if (importedToken !== undefined) return controller.api.restore(value)
      // Selection is a public UI navigation action, never storage copying. The
      // native staging loop retries after the real scoped composer mounts.
      if (mounted.size === 0 || mounted.size === 1 && ![...mounted.values()].includes(value.sessionId)) {
        const sessions = ctx.get('sessions') as { list: { getSnapshot(): { byId: Readonly<Record<string, unknown>> } } }
        if (sessions?.list?.getSnapshot?.().byId[value.sessionId] === undefined) return false
        if (requestedSession !== value.sessionId) {
          const navigation = ctx.get('uiWorkspace') as { openSession(id: string): void }
          if (typeof navigation?.openSession !== 'function') return false
          try { navigation.openSession(value.sessionId) } catch { return false }
          requestedSession = value.sessionId
        }
        return false
      }
      const restored = controller.api.restore(value)
      if (restored) importedToken = value.token
      return restored
    },
    release(value) {
      controller.api.release(value)
      if (snapshotShape(value) && value.token === importedToken) importedToken = undefined
    },
  }
  Object.defineProperty(view, '__dshMobileDraftTransfer', { configurable: true, value: api })
  function Observer({ sessionId }: { sessionId: string }): ReturnType<typeof createElement> {
    useEffect(() => {
      if (!active) return
      const sessions = ctx.get('sessions') as { scope(id: string): unknown }
      const conversation = ctx.get('conversation') as { input: { for(scope: unknown): DraftTransferInput } }
      if (typeof sessions?.scope !== 'function' || typeof conversation?.input?.for !== 'function') return
      const scope = sessions.scope(sessionId)
      if (scope === undefined) return
      const key = {}
      mounted.set(key, sessionId)
      const remove = controller.observe(sessionId, conversation.input.for(scope))
      return () => { mounted.delete(key); remove() }
    }, [sessionId])
    return createElement('span', { hidden: true, 'data-dsh-mobile-draft-observer': sessionId })
  }
  const removeSlot = ctx.slots.inject('conversation.input.overlay', () => ctx.slots.register({
    name: 'conversation.input.overlay', id: 'dsh-mobile-draft-transfer',
  }, Observer))
  return () => {
    if (!active) return
    active = false; removeSlot(); mounted.clear(); controller.dispose()
    if (view.__dshMobileDraftTransfer === api) {
      if (previous === undefined) delete view.__dshMobileDraftTransfer
      else Object.defineProperty(view, '__dshMobileDraftTransfer', previous)
    }
  }
}
