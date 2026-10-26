import { createServer, request as requestHttp, type Server } from 'node:http'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { parseGatewayConfig } from '../src/config.js'
import { MobileAccessGateway, type UpstreamAuthEntry } from '../src/gateway.js'
import { SESSION_COOKIE } from '../src/http-security.js'
import { MemoryDeviceStore } from '../src/storage.js'

type ExchangeMode = 'accepted' | 'html' | 'invalid-cookie' | 'transport-error' | 'headers-timeout' | 'body-abort' | 'body-timeout'
const cleanups: Array<() => Promise<void>> = []
afterEach(async () => {
  const errors: unknown[] = []
  for (const cleanup of cleanups.splice(0).reverse()) {
    try { await cleanup() } catch (error) { errors.push(error) }
  }
  vi.restoreAllMocks()
  if (errors.length > 0) throw new AggregateError(errors, 'Upstream authentication fixture cleanup failed')
})

async function listen(server: Server): Promise<number> {
  await new Promise<void>((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  const address = server.address()
  if (address === null || typeof address === 'string') throw new Error('Owned fixture has no TCP listener')
  return address.port
}

async function fixture(mode: ExchangeMode, observe?: (entry: UpstreamAuthEntry) => void, invalidTarget = false) {
  const root = await mkdtemp(join(tmpdir(), 'dsh-mobile-upstream-auth-log-'))
  cleanups.push(() => rm(root, { recursive: true, force: true }))
  let exchanges = 0
  let forwarded = 0
  const upstream = createServer((request, response) => {
    request.on('error', () => undefined)
    if (request.url?.startsWith('/?token=') === true) {
      exchanges++
      if (mode === 'headers-timeout') return
      if (mode === 'transport-error') { request.socket.destroy(); return }
      response.writeHead(mode === 'html' ? 200 : 303, {
        'content-type': 'text/html', location: '/?private-location=secret',
        'set-cookie': 'dsh-owned-auth=private-cookie; HttpOnly; Path=/; ' + (mode === 'invalid-cookie' ? 'Max-Age=invalid' : 'Max-Age=1800'),
      })
      if (mode === 'body-abort' || mode === 'body-timeout') {
        response.flushHeaders()
        response.write('private-response-body')
        if (mode === 'body-abort') setImmediate(() => response.destroy())
        return
      }
      response.end(mode === 'html' ? '<!doctype html>private-response-body' : undefined)
      return
    }
    forwarded++
    expect(request.headers.cookie).toBe('dsh-owned-auth=private-cookie')
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end('{"ok":true}')
  })
  cleanups.push(async () => {
    upstream.closeAllConnections()
    await new Promise<void>(resolve => { upstream.close(() => resolve()) })
  })
  const upstreamPort = await listen(upstream)
  const config = parseGatewayConfig({
    listenHost: '127.0.0.1', listenPort: 0, upstreamOrigin: `http://127.0.0.1:${upstreamPort}`,
    publicAuthorities: ['127.0.0.1'], allowedCidrs: ['127.0.0.0/8'],
    stateFile: join(root, 'devices.json'), tls: { mode: 'disabled' }, upstreamTimeoutMs: 1000,
  })
  const entries: UpstreamAuthEntry[] = []
  const authenticatedUrl = invalidTarget ? 'http://private.invalid/?token=private-token' : `${config.upstreamOrigin.origin}/?token=private-token`
  const gateway = new MobileAccessGateway({ ...config, discovery: false }, new MemoryDeviceStore(),
    undefined, authenticatedUrl, undefined, undefined, undefined, undefined, undefined, undefined,
    observe ?? (entry => { entries.push(entry) }))
  cleanups.push(() => gateway.close())
  await gateway.start()
  const opened = await gateway.access.openPairing()
  const paired = await gateway.access.pair('owned-fixture', opened.token, 'Owned diagnostics')
  const headers = { host: new URL(gateway.address().origin).host, origin: gateway.address().origin,
    'sec-fetch-site': 'same-origin', cookie: `${SESSION_COOKIE}=${paired.sessionToken}` }
  const request = (authenticated = true): Promise<{ status: number; body: string }> => new Promise((resolve, reject) => {
    const outgoing = requestHttp({ hostname: '127.0.0.1', port: gateway.address().port, method: 'GET', path: '/api/owned-diagnostic', agent: false,
      headers: authenticated ? headers : { ...headers, cookie: '' } }, response => {
      const chunks: Buffer[] = []
      response.on('data', chunk => { chunks.push(Buffer.from(chunk)) })
      response.once('error', reject)
      response.once('end', () => resolve({ status: response.statusCode ?? 0, body: Buffer.concat(chunks).toString('utf8') }))
    })
    outgoing.once('error', reject)
    outgoing.setTimeout(4000, () => outgoing.destroy(new Error('Owned diagnostic request deadline')))
    outgoing.end()
  })
  return { entries, request, exchanges: () => exchanges, forwarded: () => forwarded }
}

function expectSafe(entries: readonly UpstreamAuthEntry[]) {
  expect(JSON.stringify(entries)).not.toMatch(/private|token|location|owned-auth|cookie=/iu)
  expect(entries.every(entry => Number.isInteger(entry.durationMs) && entry.durationMs >= 0)).toBe(true)
}

describe('Gateway private upstream exchange diagnostics', () => {
  it('records one physical exchange for parallel consumers and none for a cached Cookie', async () => {
    const f = await fixture('accepted')
    const replies = await Promise.all(Array.from({ length: 6 }, () => f.request()))
    expect(replies.every(reply => reply.status === 200)).toBe(true)
    expect(f.exchanges()).toBe(1)
    expect(f.forwarded()).toBe(6)
    expect(f.entries).toEqual([{ sequence: 1, outcome: 'accepted', stage: 'cookie', status: 303, durationMs: expect.any(Number) }])
    expect((await f.request()).status).toBe(200)
    expect(f.entries).toHaveLength(1)
    expectSafe(f.entries)
  })

  it.each([
    { mode: 'html' as const, outcome: 'unexpected-status', stage: 'response', status: 200 },
    { mode: 'invalid-cookie' as const, outcome: 'invalid-cookie', stage: 'cookie', status: 303 },
    { mode: 'body-abort' as const, outcome: 'transport-error', stage: 'response', status: 303 },
  ])('distinguishes $mode without exposing its response or changing 502', async ({ mode, outcome, stage, status }) => {
    const f = await fixture(mode)
    expect((await f.request()).status).toBe(502)
    expect(f.entries).toEqual([{ sequence: 1, outcome, stage, status, durationMs: expect.any(Number) }])
    expect(f.forwarded()).toBe(0)
    expectSafe(f.entries)
  })

  it.each(['transport-error', 'headers-timeout'] as const)('records $mode before response headers without a fabricated status', async mode => {
    const f = await fixture(mode)
    expect((await f.request()).status).toBe(mode === 'headers-timeout' ? 504 : 502)
    expect(f.entries).toEqual([{ sequence: 1, outcome: mode === 'headers-timeout' ? 'timeout' : 'transport-error', stage: 'request', durationMs: expect.any(Number) }])
    expect(f.forwarded()).toBe(0)
    expectSafe(f.entries)
  })

  it('retains the observed upstream status when its response body times out', async () => {
    const f = await fixture('body-timeout')
    expect((await f.request()).status).toBe(502)
    expect(f.entries).toEqual([{ sequence: 1, outcome: 'timeout', stage: 'response', status: 303, durationMs: expect.any(Number) }])
    expect(f.forwarded()).toBe(0)
    expectSafe(f.entries)
  })

  it('records an invalid internal target without connecting to it or logging it', async () => {
    const f = await fixture('accepted', undefined, true)
    expect((await f.request()).status).toBe(502)
    expect(f.entries).toEqual([{ sequence: 1, outcome: 'invalid-target', stage: 'target', durationMs: expect.any(Number) }])
    expect(f.exchanges()).toBe(0)
    expectSafe(f.entries)
  })

  it('does not start or log an upstream exchange for an unauthenticated phone request', async () => {
    const f = await fixture('accepted')
    expect((await f.request(false)).status).toBe(401)
    expect(f.entries).toHaveLength(0)
    expect(f.exchanges()).toBe(0)
  })

  it.each(['accepted', 'html'] as const)('does not replace $mode HTTP handling when its sink throws', async mode => {
    const warnings = vi.spyOn(process, 'emitWarning').mockImplementation(() => undefined)
    const sink = vi.fn(() => { throw new Error('private-sink-error') })
    const f = await fixture(mode, sink)
    expect((await f.request()).status).toBe(mode === 'accepted' ? 200 : 502)
    expect(sink).toHaveBeenCalledTimes(1)
    expect(warnings).toHaveBeenCalledWith('Mobile access upstream authentication diagnostics could not be recorded', { code: 'DSH_MOBILE_UPSTREAM_AUTH_LOG_FAILED' })
  })
})
