import { describe, expect, it } from 'vitest'
import { shouldNudgeReconnect } from '../src/client.js'

/**
 * Android drops the socket while backgrounded without firing offline/online, so
 * the client waits out its own backoff on resume (500 ms … 10 s). The nudge
 * skips that wait — but only when it cannot disturb a healthy stream.
 */

describe('resume reconnect nudge', () => {
  it('nudges a stale connection as soon as the page is visible', () => {
    expect(shouldNudgeReconnect('visible', 'disconnected')).toBe(true)
    expect(shouldNudgeReconnect('visible', 'connecting')).toBe(true)
    expect(shouldNudgeReconnect('visible', undefined)).toBe(true)
  })

  it('never tears down a connected stream', () => {
    expect(shouldNudgeReconnect('visible', 'connected')).toBe(false)
  })

  it('does nothing while the page is hidden', () => {
    expect(shouldNudgeReconnect('hidden', 'disconnected')).toBe(false)
    expect(shouldNudgeReconnect('hidden', 'connected')).toBe(false)
  })
})
