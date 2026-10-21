import { describe, expect, it } from 'vitest'
import { startVisibilityPoll, type VisibilityPollTimer, type VisibilitySource } from '../src/visibility-poll.js'

/** A timer host that runs intervals only when the test ticks them. */
function fakeTimers() {
  let nextId = 1
  const intervals = new Map<number, { handler: () => void; ms: number }>()
  const timeouts = new Map<number, () => void>()
  const timers: VisibilityPollTimer = {
    setInterval: (handler, ms) => { const id = nextId++; intervals.set(id, { handler, ms }); return id },
    clearInterval: handle => { intervals.delete(handle as number) },
    setTimeout: (handler) => { const id = nextId++; timeouts.set(id, handler); return id },
    clearTimeout: handle => { timeouts.delete(handle as number) },
  }
  return {
    timers,
    /** Fire every armed interval once, as if `ms` elapsed. */
    tick: () => { for (const entry of [...intervals.values()]) entry.handler() },
    armed: () => [...intervals.values()].map(entry => entry.ms),
    timeouts: () => timeouts.size,
  }
}

function fakeVisibility(hiddenInitially = false) {
  let hidden = hiddenInitially
  const listeners = new Set<(hidden: boolean) => void>()
  const source: VisibilitySource = {
    hidden: () => hidden,
    subscribe: listener => { listeners.add(listener); return () => { listeners.delete(listener) } },
  }
  return {
    source,
    listeners,
    set: (next: boolean) => { hidden = next; for (const listener of [...listeners]) listener(next) },
  }
}

describe('visibility poll', () => {
  it('ticks on the visible cadence and stops while the document is hidden', () => {
    const clock = fakeTimers()
    const visibility = fakeVisibility()
    let runs = 0
    const dispose = startVisibilityPoll(() => { runs += 1 }, 1_500, visibility.source, clock.timers)
    expect(clock.armed()).toEqual([1_500])
    clock.tick()
    clock.tick()
    expect(runs).toBe(2)
    visibility.set(true)
    expect(clock.armed()).toEqual([])
    clock.tick()
    expect(runs).toBe(2)
    dispose()
  })

  it('catches up exactly once when the document becomes visible again', () => {
    const clock = fakeTimers()
    const visibility = fakeVisibility()
    let runs = 0
    const dispose = startVisibilityPoll(() => { runs += 1 }, 1_500, visibility.source, clock.timers)
    visibility.set(true)
    visibility.set(false)
    expect(runs).toBe(1)
    expect(clock.armed()).toEqual([1_500])
    clock.tick()
    expect(runs).toBe(2)
    // A repeated visible notification re-arms nothing but still catches up.
    const armed = clock.armed()
    visibility.set(false)
    expect(runs).toBe(3)
    expect(clock.armed()).toEqual(armed)
    dispose()
  })

  it('does not start at all when the document is already hidden', () => {
    const clock = fakeTimers()
    const visibility = fakeVisibility(true)
    let runs = 0
    const dispose = startVisibilityPoll(() => { runs += 1 }, 1_500, visibility.source, clock.timers)
    expect(clock.armed()).toEqual([])
    expect(runs).toBe(0)
    visibility.set(false)
    expect(runs).toBe(1)
    dispose()
  })

  it('releases the timer and the listener on dispose', () => {
    const clock = fakeTimers()
    const visibility = fakeVisibility()
    let runs = 0
    const dispose = startVisibilityPoll(() => { runs += 1 }, 1_500, visibility.source, clock.timers)
    dispose()
    expect(clock.armed()).toEqual([])
    expect(visibility.listeners.size).toBe(0)
    visibility.set(true)
    visibility.set(false)
    clock.tick()
    expect(runs).toBe(0)
  })

  it('keeps the always-on cadence where no document exists', () => {
    const clock = fakeTimers()
    let runs = 0
    const dispose = startVisibilityPoll(() => { runs += 1 }, 20_000, undefined, clock.timers)
    expect(clock.armed()).toEqual([20_000])
    clock.tick()
    expect(runs).toBe(1)
    dispose()
    expect(clock.armed()).toEqual([])
  })

  it('counts the LAN requests of the remote panel across one hidden gap', () => {
    // The control panel's remote section polls /remote/control every 1.5 s
    // while both the dialog and its remote sub-view are shown.
    const clock = fakeTimers()
    const visibility = fakeVisibility()
    let requests = 0
    const dispose = startVisibilityPoll(() => { requests += 1 }, 1_500, visibility.source, clock.timers)
    // Visible for one minute: 40 ticks.
    for (let i = 0; i < 40; i++) clock.tick()
    expect(requests).toBe(40)
    // Backgrounded for ten minutes: nothing.
    visibility.set(true)
    for (let i = 0; i < 400; i++) clock.tick()
    expect(requests).toBe(40)
    // Returned: one catch-up read, then the cadence resumes.
    visibility.set(false)
    expect(requests).toBe(41)
    for (let i = 0; i < 40; i++) clock.tick()
    expect(requests).toBe(81)
    dispose()
  })
})
