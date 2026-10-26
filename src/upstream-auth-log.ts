/** Fixed outcomes of the Gateway's private DSH launch-token exchange. */
export type UpstreamAuthOutcome = 'accepted' | 'invalid-target' | 'unexpected-status' | 'invalid-cookie' | 'timeout' | 'transport-error'

/** Exchange metadata only: no URL, header, Cookie, Token or raw error is retained. */
export interface UpstreamAuthEntry {
  readonly sequence: number
  readonly outcome: UpstreamAuthOutcome
  readonly stage: 'target' | 'request' | 'response' | 'cookie'
  readonly durationMs: number
  /** Upstream HTTP status after headers arrive, not the phone-facing failure status. */
  readonly status?: number
  /** Earlier exchanges with the same success/failure category omitted by sampling. */
  readonly suppressed?: number
}

/** Gateway-local correlation and monotonic start time for one physical exchange. */
export interface UpstreamAuthAttempt {
  readonly sequence: number
  readonly startedAt: number
}

interface LogWindow { nextLogAt: number; suppressed: number }
const LOG_INTERVAL_MS = 30_000

/** Two fixed sampling slots keep a first failure visible even after a successful exchange. */
export class UpstreamAuthRecorder {
  private sequence = 0
  private readonly windows: Record<'success' | 'failure', LogWindow> = {
    success: { nextLogAt: 0, suppressed: 0 },
    failure: { nextLogAt: 0, suppressed: 0 },
  }
  private observerFailureReported = false

  constructor(
    private readonly observe: ((entry: UpstreamAuthEntry) => void) | undefined,
    private readonly now: () => number = () => performance.now(),
    private readonly reportObserverFailure: () => void = () => {
      process.emitWarning('Mobile access upstream authentication diagnostics could not be recorded', { code: 'DSH_MOBILE_UPSTREAM_AUTH_LOG_FAILED' })
    },
  ) {}

  /** Begin only a physical exchange, not every consumer sharing its promise or cached Cookie. */
  begin(): UpstreamAuthAttempt | undefined {
    if (this.observe === undefined) return undefined
    this.sequence = this.sequence >= Number.MAX_SAFE_INTEGER ? 1 : this.sequence + 1
    return { sequence: this.sequence, startedAt: this.now() }
  }

  /** Record the completed exchange without changing authentication, retrying it or retaining its inputs. */
  record(attempt: UpstreamAuthAttempt | undefined, result: Pick<UpstreamAuthEntry, 'outcome' | 'stage' | 'status'>): void {
    if (attempt === undefined || this.observe === undefined) return
    const now = this.now()
    const window = this.windows[result.outcome === 'accepted' ? 'success' : 'failure']
    if (now < window.nextLogAt) {
      window.suppressed = Math.min(Number.MAX_SAFE_INTEGER, window.suppressed + 1)
      return
    }
    const suppressed = window.suppressed
    window.nextLogAt = now + LOG_INTERVAL_MS
    window.suppressed = 0
    try {
      this.observe({
        sequence: attempt.sequence,
        outcome: result.outcome,
        stage: result.stage,
        durationMs: Math.max(0, Math.floor(now - attempt.startedAt)),
        ...(result.status === undefined ? {} : { status: result.status }),
        ...(suppressed === 0 ? {} : { suppressed }),
      })
    } catch (observerError) {
      void observerError // Diagnostic failures cannot replace the exchange's owning result.
      if (this.observerFailureReported) return
      this.observerFailureReported = true
      try { this.reportObserverFailure() }
      catch (reporterError) { void reporterError /* Authentication remains independent of its log sink. */ }
    }
  }
}
