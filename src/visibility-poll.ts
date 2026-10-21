/**
 * One repeating read tied to page visibility.
 *
 * A poll that keeps ticking while the page is hidden spends LAN requests on a
 * document nobody can see: the phone keeps the tab alive in the background for
 * as long as its session lasts. Pausing the timer on `document.hidden` and
 * catching up once on the next visible tick keeps the panel fresh at the moment
 * the user looks at it while sending nothing at all in between.
 */

export interface VisibilityPollTimer {
  readonly setInterval: (handler: () => void, ms: number) => unknown
  readonly clearInterval: (handle: unknown) => void
  readonly setTimeout: (handler: () => void, ms: number) => unknown
  readonly clearTimeout: (handle: unknown) => void
}

/** One visibility transition of the document, as far as the poll needs it. */
export interface VisibilitySource {
  /** Whether the document is currently hidden. */
  readonly hidden: () => boolean
  /** Observe later transitions; returns the disposer. */
  readonly subscribe: (listener: (hidden: boolean) => void) => () => void
}

const browserTimers: VisibilityPollTimer = {
  setInterval: (handler, ms) => globalThis.setInterval(handler, ms),
  clearInterval: handle => { globalThis.clearInterval(handle as ReturnType<typeof setInterval>) },
  setTimeout: (handler, ms) => globalThis.setTimeout(handler, ms),
  clearTimeout: handle => { globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>) },
}

/**
 * Start `run` every `intervalMs` while the document is visible, plus once
 * immediately after each hidden-to-visible transition that outlasted it.
 *
 * @param run - the read; the caller keeps its own in-flight guard.
 * @param intervalMs - the visible-period cadence.
 * @param source - visibility source; defaults to `document.visibilityState`.
 * @param timers - timer host; defaults to the global timers.
 * @returns disposer that stops the timer and drops the listener.
 */
export function startVisibilityPoll(
  run: () => void,
  intervalMs: number,
  source?: VisibilitySource,
  timers: VisibilityPollTimer = browserTimers,
): () => void {
  const visibility = source ?? documentVisibility()
  if (visibility === undefined) {
    // No document (SSR, worker): keep the previous always-on cadence.
    const handle = timers.setInterval(run, intervalMs)
    return () => { timers.clearInterval(handle) }
  }
  let handle: unknown
  const stop = (): void => {
    if (handle === undefined) return
    timers.clearInterval(handle)
    handle = undefined
  }
  const start = (): void => {
    if (handle !== undefined) return
    handle = timers.setInterval(run, intervalMs)
  }
  if (!visibility.hidden()) start()
  const unsubscribe = visibility.subscribe(hidden => {
    if (hidden) {
      stop()
      return
    }
    start()
    run()
  })
  return () => {
    unsubscribe()
    stop()
  }
}

function documentVisibility(): VisibilitySource | undefined {
  if (typeof document === 'undefined' || typeof document.addEventListener !== 'function') return undefined
  return Object.freeze({
    hidden: () => document.hidden === true,
    subscribe: (listener: (hidden: boolean) => void) => {
      const onVisibility = (): void => { listener(document.hidden === true) }
      document.addEventListener('visibilitychange', onVisibility)
      return () => { document.removeEventListener('visibilitychange', onVisibility) }
    },
  })
}
