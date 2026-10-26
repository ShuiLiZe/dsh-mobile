/** Connection state exposed by DSH's public client connection handle. */
export type MobileConnectionState = 'connected' | 'connecting' | 'disconnected' | undefined

/** Public connection operations used without replacing DSH's transport. */
export interface MobileRecoveryConnection {
  readonly state?: {
    getSnapshot(): MobileConnectionState
    subscribe(listener: () => void): () => void
  }
  reconnect?: () => void
}

/** A supporting App settles renewal only after installing the current cookies. */
export interface MobileRecoveryBridge {
  capabilities(): Promise<readonly string[]> | readonly string[]
  invoke(action: string, input?: unknown, options?: { readonly signal?: AbortSignal }): Promise<unknown>
}

/** Only a visible, disconnected public transport needs an immediate resume nudge. */
export function shouldNudgeReconnect(visibility: string, connection: MobileConnectionState): boolean {
  return visibility === 'visible' && connection !== 'connected'
}

/** A persistent, known disconnection can request one native renewal. */
export function shouldRenewSession(connection: MobileConnectionState, downForMs: number): boolean {
  return isDisconnected(connection) && downForMs >= 5000
}

function isDisconnected(connection: MobileConnectionState): boolean {
  return connection === 'connecting' || connection === 'disconnected'
}

interface RecoveryPage extends EventTarget {
  readonly location: { readonly origin: string }
  readonly document: Document
  readonly __DSH_MOBILE_NATIVE__?: MobileRecoveryBridge
}

/**
 * Own resume nudges and one native renewal per continuous disconnection.
 * @param getConnection - Resolve the current service to reject a replaced owner.
 * @param page - The document's window and advertised native adapter.
 * @param document - The mounted document; page replacement withdraws its work.
 * @returns An awaited disposer, or nothing when no public connection is present.
 */
export function installMobileConnectionRecovery(
  getConnection: () => MobileRecoveryConnection | undefined,
  page: RecoveryPage,
  document: Document,
): (() => Promise<void>) | undefined {
  const connection = getConnection()
  const state = connection?.state
  if (connection === undefined || state === undefined || typeof connection.reconnect !== 'function') return
  const origin = page.location.origin
  let disposed = false
  let generation = 0
  let currentState = state.getSnapshot()
  let downSince = isDisconnected(currentState) ? Date.now() : undefined
  let attempted = false
  let bridge = page.__DSH_MOBILE_NATIVE__
  let pending: Promise<void> | undefined
  let pendingAbort: AbortController | undefined
  let cleanup: Promise<void> | undefined

  const current = (): boolean => !disposed && page.document === document
    && page.location.origin === origin && getConnection() === connection && connection.state === state
  const cancelPending = (): void => {
    generation += 1
    pendingAbort?.abort(new DOMException('mobile recovery owner ended', 'AbortError'))
  }
  const updateState = (): void => {
    const next = state.getSnapshot()
    if (!isDisconnected(next)) {
      downSince = undefined
      attempted = false
      if (currentState !== next) cancelPending()
    } else if (downSince === undefined) {
      downSince = Date.now()
      attempted = false
    }
    currentState = next
  }
  const updateBridge = (): void => {
    const next = page.__DSH_MOBILE_NATIVE__
    if (bridge === next) return
    cancelPending()
    bridge = next
    attempted = false
  }
  const renew = (): void => {
    if (!current()) { void dispose(); return }
    updateState()
    updateBridge()
    const adapter = bridge
    if (document.visibilityState !== 'visible' || adapter === undefined || pending !== undefined || attempted
      || downSince === undefined || !shouldRenewSession(currentState, Date.now() - downSince)) return
    attempted = true
    const owner = generation
    const abort = new AbortController()
    pendingAbort = abort
    const owns = (): boolean => current() && !abort.signal.aborted && generation === owner
      && page.__DSH_MOBILE_NATIVE__ === adapter && isDisconnected(state.getSnapshot())
    const task = Promise.resolve().then(async () => {
      if (!owns()) return
      const capabilities = await adapter.capabilities()
      if (!owns() || !capabilities.includes('auth.renew')) return
      const result = await adapter.invoke('auth.renew', {}, { signal: abort.signal })
      if (!owns() || typeof result !== 'object' || result === null
        || !('renewed' in result) || result.renewed !== true) return
      connection.reconnect?.()
    }).catch((_error: unknown) => {
      // Native recovery reports its own failure; do not restart a stopped attempt.
    }).finally(() => {
      if (pending !== task) return
      pending = undefined
      pendingAbort = undefined
    })
    pending = task
  }
  const resume = (): void => {
    if (!current()) { void dispose(); return }
    updateState()
    updateBridge()
    if (shouldNudgeReconnect(document.visibilityState, currentState)) connection.reconnect?.()
    renew()
  }
  const stopState = state.subscribe(updateState)
  const timer = setInterval(renew, 2000)
  const pagehide = (): void => { void dispose() }
  const dispose = (): Promise<void> => {
    if (cleanup !== undefined) return cleanup
    disposed = true
    cancelPending()
    clearInterval(timer)
    stopState()
    document.removeEventListener('visibilitychange', resume)
    page.removeEventListener('dsh-mobile-native-ready', updateBridge)
    page.removeEventListener('pagehide', pagehide)
    cleanup = Promise.resolve(pending)
    return cleanup
  }
  document.addEventListener('visibilitychange', resume)
  page.addEventListener('dsh-mobile-native-ready', updateBridge)
  page.addEventListener('pagehide', pagehide)
  return dispose
}
