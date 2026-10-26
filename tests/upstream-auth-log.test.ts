import { describe, expect, it, vi } from 'vitest'
import { UpstreamAuthRecorder, type UpstreamAuthEntry } from '../src/upstream-auth-log.js'

describe('private upstream exchange diagnostics', () => {
  it('records safe fields, elapsed monotonic time and physical exchange sequence only', () => {
    let now = 10
    const entries: UpstreamAuthEntry[] = []
    const recorder = new UpstreamAuthRecorder(entry => { entries.push(entry) }, () => now)
    const attempt = recorder.begin()
    now = 17.8
    const supplied = { outcome: 'accepted' as const, stage: 'cookie' as const, status: 303,
      url: 'http://private/?token=private-token', cookie: 'private-cookie', error: new Error('private-error') }
    recorder.record(attempt, supplied)
    expect(entries).toEqual([{ sequence: 1, outcome: 'accepted', stage: 'cookie', status: 303, durationMs: 7 }])
    expect(JSON.stringify(entries)).not.toMatch(/private|token|cookie=/iu)
  })

  it('keeps first failure visible after success and aggregates fixed success/failure windows independently', () => {
    let now = 0
    const entries: UpstreamAuthEntry[] = []
    const recorder = new UpstreamAuthRecorder(entry => { entries.push(entry) }, () => now)
    const success = { outcome: 'accepted' as const, stage: 'cookie' as const, status: 303 }
    const failure = { outcome: 'unexpected-status' as const, stage: 'response' as const, status: 200 }
    recorder.record(recorder.begin(), success)
    recorder.record(recorder.begin(), failure)
    for (let count = 0; count < 1000; count++) recorder.record(recorder.begin(), count % 2 === 0 ? success : failure)
    expect(entries).toHaveLength(2)
    now = 30_000
    recorder.record(recorder.begin(), failure)
    recorder.record(recorder.begin(), success)
    expect(entries[2]).toMatchObject({ sequence: 1003, outcome: 'unexpected-status', suppressed: 500 })
    expect(entries[3]).toMatchObject({ sequence: 1004, outcome: 'accepted', suppressed: 500 })
  })

  it('contains sink and reporter failures without changing the exchange caller', () => {
    let now = 0
    const observe = vi.fn(() => { throw new Error('private-sink-error') })
    const report = vi.fn(() => { throw new Error('private-report-error') })
    const recorder = new UpstreamAuthRecorder(observe, () => now, report)
    const result = { outcome: 'timeout' as const, stage: 'request' as const }
    expect(() => recorder.record(recorder.begin(), result)).not.toThrow()
    now = 30_000
    expect(() => recorder.record(recorder.begin(), result)).not.toThrow()
    expect(observe).toHaveBeenCalledTimes(2)
    expect(report).toHaveBeenCalledTimes(1)
  })

  it('does no clock or recording work without an observer', () => {
    const now = vi.fn(() => 10)
    const recorder = new UpstreamAuthRecorder(undefined, now)
    expect(recorder.begin()).toBeUndefined()
    recorder.record(undefined, { outcome: 'accepted', stage: 'cookie' })
    expect(now).not.toHaveBeenCalled()
  })
})
