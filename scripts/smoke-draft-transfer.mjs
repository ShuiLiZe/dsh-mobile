/** Packed plugin, real DSH input, two authenticated Origins; no prompts or user profile. */
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { createServer, request } from 'node:http'
import { connect } from 'node:net'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { packBundle, installPackedBundle } from './packed-profile.mjs'
import { createMobileProfile, launchDsh, removeTemporaryRoot, openPairing, pairMobilePage,
  dismissOnboarding, CLIENT_TIMEOUT_MS, sanitized } from './mobile-boot-fixture.mjs'

const root = await mkdtemp(join(tmpdir(), 'dsh-mobile-transfer-smoke-'))
const dshBin = process.env.DSH_BOOT_SMOKE_BIN ?? fileURLToPath(new URL('../node_modules/@deepseek-ai/dsh/lib/bin.js', import.meta.url))
const previousCache = process.env.npm_config_cache
process.env.npm_config_cache = join(root, 'npm-cache')
let browser, dsh, proxy
const sockets = new Set()
const requests = new Set()
const errors = [], prompts = []
let failure
let sequence = 0
const rememberSocket = socket => {
  if (sockets.has(socket)) return
  sockets.add(socket)
  socket.once('close', () => sockets.delete(socket))
}
async function rpc(page, method, data) {
  const response = await page.evaluate(async ({ method, data, id }) => {
    const result = await fetch(`/api/${method}`, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ type: 'client-request', rpcId: id, method, payload: { args: { request: data } } }) })
    return { status: result.status, body: await result.json() }
  }, { method, data, id: `transfer-${++sequence}` })
  assert.equal(response.status, 200)
  assert.equal(response.body.result?.ok, true, sanitized(JSON.stringify(response)))
  return response.body.result.value
}
async function alternateOrigin(origin) {
  const upstream = new URL(origin)
  // Owned canonicalizing proxy: this proves browser Origin separation and input
  // migration, not production proxy admission or native pairing/TLS behavior.
  const canonicalHeaders = req => {
    const from = `http://${req.headers.host}`
    const headers = { ...req.headers, host: upstream.host }
    if (headers.origin === from) headers.origin = upstream.origin
    if (typeof headers.referer === 'string' && headers.referer.startsWith(from + '/')) headers.referer = upstream.origin + headers.referer.slice(from.length)
    return headers
  }
  proxy = createServer((req, res) => {
    const forwarded = request({ hostname: upstream.hostname, port: upstream.port, method: req.method,
      path: req.url, headers: canonicalHeaders(req) }, incoming => {
        const headers = { ...incoming.headers }
        if (typeof headers.location === 'string' && headers.location.startsWith(upstream.origin + '/')) headers.location = `http://${req.headers.host}` + headers.location.slice(upstream.origin.length)
        res.writeHead(incoming.statusCode, headers); incoming.pipe(res)
      })
    requests.add(forwarded)
    forwarded.once('close', () => requests.delete(forwarded))
    forwarded.on('socket', rememberSocket)
    forwarded.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end() })
    req.pipe(forwarded)
  })
  proxy.on('connection', rememberSocket)
  proxy.on('upgrade', (req, socket, head) => {
    const upstreamSocket = connect(Number(upstream.port), upstream.hostname, () => {
      upstreamSocket.write(`${req.method} ${req.url} HTTP/${req.httpVersion}\r\n${Object.entries(canonicalHeaders(req)).map(([key, value]) => `${key}: ${value}\r\n`).join('')}\r\n`)
      if (head.length) upstreamSocket.write(head)
      upstreamSocket.pipe(socket); socket.pipe(upstreamSocket)
    })
    rememberSocket(upstreamSocket)
    socket.on('error', () => upstreamSocket.destroy()); upstreamSocket.on('error', () => socket.destroy())
    socket.on('close', () => upstreamSocket.destroy())
  })
  await new Promise((resolve, reject) => { proxy.once('error', reject); proxy.listen(0, '127.0.0.1', resolve) })
  return `http://127.0.0.1:${proxy.address().port}`
}
async function capture(page) {
  await page.waitForFunction(() => { const value = window.__dshMobileDraftTransfer?.capture();
    if (value == null) return false; window.__smokeCaptured = value; return true }, undefined, { timeout: 12_000 })
  return page.evaluate(() => window.__smokeCaptured)
}
async function release(page, snapshot) { await page.evaluate(value => window.__dshMobileDraftTransfer.release(value), snapshot) }
try {
  await mkdir(join(root, 'workspace'))
  const tarball = process.env.DSH_BOOT_SMOKE_MOBILE_TARBALL === undefined
    ? await packBundle(fileURLToPath(new URL('..', import.meta.url)), root)
    : resolve(process.env.DSH_BOOT_SMOKE_MOBILE_TARBALL)
  console.log(JSON.stringify({ artifactSha256: createHash('sha256').update(await readFile(tarball)).digest('hex') }))
  const home = await createMobileProfile(root, { tarball, dshBin, packageStoreDirectory: join(root, 'store') })
  const fixture = join(root, 'draft-state-fixture')
  await mkdir(join(fixture, 'lib'), { recursive: true })
  await writeFile(join(fixture, 'package.json'), JSON.stringify({ name: 'draft-state-fixture', version: '1.0.0', type: 'module', main: './lib/index.mjs', exports: { '.': './lib/index.mjs', './client': './lib/client.js', './package.json': './package.json' }, dsh: { bundle: { patch: './cordis.patch.yml' }, client: { platform: 'web', inject: ['@deepseek-ai/dsh-client-ui-conversation', '@deepseek-ai/dsh-api-session-controller'], immediately: true } } }))
  await writeFile(join(fixture, 'cordis.patch.yml'), JSON.stringify([{ insert: [{ id: 'draft-state-fixture', name: 'draft-state-fixture' }] }]))
  await writeFile(join(fixture, 'lib/index.mjs'), 'export function apply() {}')
  await writeFile(join(fixture, 'lib/client.js'), `window.__ModuleLoader__.load({id:'draft-state-fixture',factory:require=>({inject:['conversation','sessions'],apply:ctx=>ctx.effect(()=>{window.__smokeInput=id=>{const scope=ctx.sessions.scope(id);return scope===undefined?null:ctx.conversation.input.for(scope).state.getSnapshot()};return()=>delete window.__smokeInput})})});`)
  await installPackedBundle(await packBundle(fixture, root), join(home, 'profiles', 'web'), { dshBin })
  dsh = launchDsh(root, home, dshBin)
  browser = await chromium.launch({ headless: true })
  const desktop = await browser.newPage({ locale: 'en-US' })
  const pairUrl = await openPairing(desktop, await dsh.ready(), dsh.logs)
  const workspace = await rpc(desktop, 'workspace/create', { path: join(root, 'workspace') })
  const mobile = async () => {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, locale: 'en-US', isMobile: true, hasTouch: true })
    page.on('pageerror', error => errors.push(error.message))
    await page.route('**/api/session/prompt', async route => { prompts.push('unexpected prompt'); await route.abort() })
    return page
  }
  const source = await mobile()
  await pairMobilePage(source, pairUrl, dsh.logs); await dismissOnboarding(source)
  const drawer = source.locator('.dshm-drawer')
  if (await drawer.getAttribute('data-open') !== 'true') await drawer.locator('button[data-dsh-mobile-toggle]').click()
  const created = source.waitForResponse(response => new URL(response.url()).pathname === '/api/session/create' &&
    response.request().postDataJSON()?.payload?.args?.request?.workspaceId === workspace.workspace.workspaceId,
  { timeout: CLIENT_TIMEOUT_MS })
  try { await drawer.locator(`[role="treeitem"][data-row-key="workspace:${workspace.workspace.workspaceId}"]`).getByRole('button').last().click() }
  catch (error) { void created.catch(() => {}); throw error }
  const createdBody = await (await created).json()
  assert.equal(createdBody.result?.ok, true)
  const sessionId = createdBody.result.value.sessionId
  const editor = source.locator('[data-composer-card] [data-composer-input][contenteditable="true"]').first()
  await editor.waitFor({ state: 'visible', timeout: CLIENT_TIMEOUT_MS })
  if (await drawer.getAttribute('data-open') === 'true') await drawer.locator('button[data-dsh-mobile-toggle]').click()
  const text = '跨地址迁移 🐳\nquotes " backslash \\ @literal /literal\u2028尾部\n'
  await editor.fill(text)
  await editor.evaluate(element => element.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true })))
  assert.equal(await source.evaluate(() => window.__dshMobileDraftTransfer?.capture()), null, 'Active IME composition must block transfer')
  await editor.evaluate(element => element.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true })))
  let snapshot
  try { snapshot = await capture(source) } catch (error) {
    const diagnostic = await source.evaluate(id => { const s=window.__smokeInput?.(id); return { api: typeof window.__dshMobileDraftTransfer, frontend: window.__DSH_MOBILE_FRONTEND__, state: s == null ? null : { phase: s.phase, draftRev:s.draftRev, claim:s.claim, attachmentCount:s.attachmentIds?.length, occurrenceCount:s.occurrences?.length, queueCount:s.queue?.length, draftLength:s.draft?.length }, slots:document.querySelectorAll('[data-slot="conversation.composer.dock"]').length, observerCount:document.querySelectorAll('[data-dsh-mobile-draft-observer]').length, observerSessions:[...document.querySelectorAll('[data-dsh-mobile-draft-observer]')].map(x=>x.getAttribute('data-dsh-mobile-draft-observer')) } }, sessionId)
    throw new Error('Capture failed: '+JSON.stringify(diagnostic), { cause: error })
  }
  assert.equal(snapshot.sessionId, sessionId); assert.equal(snapshot.text, text)
  assert.equal(await source.evaluate(value => window.__dshMobileDraftTransfer.validate(value), snapshot), true)
  const other = await alternateOrigin(new URL(source.url()).origin)
  const target = await mobile()
  const targetPair = new URL(await openPairing(desktop, undefined, dsh.logs)); const nextOrigin = new URL(other)
  targetPair.host = nextOrigin.host
  const targetEntry = await target.goto(targetPair.href, { waitUntil: 'domcontentloaded' })
  if (await target.locator('#pair-form button').count() === 0) throw new Error('Alternate Origin entry did not offer pairing: '+sanitized(JSON.stringify({ status:targetEntry?.status(), path:new URL(target.url()).pathname, body:(await target.locator('body').innerText()).slice(0,600) })))
  await pairMobilePage(target, targetPair.href, dsh.logs); await dismissOnboarding(target)
  assert.notEqual(new URL(target.url()).origin, new URL(source.url()).origin)
  await target.waitForFunction(value => window.__dshMobileDraftTransfer?.restore(value) === true, snapshot, { timeout: CLIENT_TIMEOUT_MS })
  await target.waitForFunction(({ sessionId, text }) => document.querySelector('[data-conversation-session]')?.getAttribute('data-conversation-session') === sessionId &&
    window.__smokeInput?.(sessionId)?.draft === text &&
    document.querySelector('[data-composer-card] [data-composer-input]')?.innerText.includes('跨地址迁移'), { sessionId, text }, { timeout: CLIENT_TIMEOUT_MS })
  assert.equal(await source.evaluate(value => window.__dshMobileDraftTransfer.validate(value), snapshot), true)
  await editor.fill(text + 'source changed')
  assert.equal(await source.evaluate(value => window.__dshMobileDraftTransfer.validate(value), snapshot), false)
  await release(source, snapshot)
  const targetEditor = target.locator('[data-composer-card] [data-composer-input][contenteditable="true"]').first()
  await targetEditor.fill('Do not overwrite target conflict')
  assert.equal(await target.evaluate(value => window.__dshMobileDraftTransfer.restore(value), snapshot), false)
  assert.equal(await targetEditor.textContent(), 'Do not overwrite target conflict')
  await targetEditor.fill('')
  assert.equal(await target.evaluate(value => window.__dshMobileDraftTransfer.verify(value), snapshot), false)
  assert.equal(await target.evaluate(value => window.__dshMobileDraftTransfer.restore(value), snapshot), false)
  assert.equal(await targetEditor.textContent(), '', 'A repeated import must not restore text over a cleared target')

  // A fresh owned target import is conditionally rolled back after source change.
  const rollbackSnapshot = await capture(source)
  const rollbackTarget = await mobile()
  const rollbackPair = new URL(await openPairing(desktop, undefined, dsh.logs)); rollbackPair.host = nextOrigin.host
  await pairMobilePage(rollbackTarget, rollbackPair.href, dsh.logs); await dismissOnboarding(rollbackTarget)
  await rollbackTarget.waitForFunction(value => window.__dshMobileDraftTransfer?.restore(value) === true, rollbackSnapshot, { timeout: CLIENT_TIMEOUT_MS })
  assert.equal(await rollbackTarget.evaluate(value => window.__dshMobileDraftTransfer.verify(value), rollbackSnapshot), true)
  await editor.fill('new source must survive abort')
  assert.equal(await source.evaluate(value => window.__dshMobileDraftTransfer.validate(value), rollbackSnapshot), false)
  assert.equal(await rollbackTarget.evaluate(value => window.__dshMobileDraftTransfer.rollback(value), rollbackSnapshot), true)
  await rollbackTarget.waitForFunction(id => window.__smokeInput?.(id)?.draft === '', sessionId)
  assert.equal(await source.evaluate(id => window.__smokeInput(id).draft, sessionId), 'new source must survive abort')
  await release(source, rollbackSnapshot)
  await rollbackTarget.close()
  await editor.fill('attachment protection')
  const input = source.locator('[data-composer-card] input[type="file"][multiple]').first()
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC', 'base64')
  await input.setInputFiles([{ name: 'transfer-guard.png', mimeType: 'image/png', buffer: png }])
  await source.getByRole('button', { name: 'Remove image transfer-guard.png', exact: true }).waitFor({ timeout: CLIENT_TIMEOUT_MS })
  assert.equal(await source.evaluate(() => window.__dshMobileDraftTransfer.capture()), null)
  assert.deepEqual(errors, [], 'Packed plugin or real input raised browser errors')
  assert.deepEqual(prompts, [], 'Migration must never submit a message')
  console.log('Packed two-Origin real-input migration passed: Unicode text visible in target, source preserved/change invalidation, target conflict/cleared-target rejection, conditional owned-import rollback, attachments protected, no prompts.')
} catch (error) {
  failure = new Error(`${sanitized(String(error?.stack ?? error))}\n${dsh === undefined ? '' : sanitized(dsh.logs())}`)
} finally {
  const failures = failure === undefined ? [] : [failure]
  const cleanup = { browserClosed: browser === undefined, proxyClosed: proxy === undefined,
    proxySocketsRemaining: undefined, proxyRequestsRemaining: undefined, dshExit: undefined, temporaryRootRemoved: false }
  if (browser !== undefined) await browser.close().then(() => { cleanup.browserClosed = true }, error => failures.push(error))
  if (proxy !== undefined) {
    try {
      const closed = proxy.listening ? new Promise((done, reject) => proxy.close(error => error ? reject(error) : done())) : Promise.resolve()
      await Promise.all([...requests].map(outgoing => new Promise(done => { outgoing.once('close', done); outgoing.destroy() })))
      await Promise.all([...sockets].map(socket => new Promise(done => {
        if (socket.closed) { done(); return }
        socket.once('close', done)
        socket.destroy()
      })))
      await closed
      cleanup.proxyClosed = true
    } catch (error) { failures.push(error) }
  }
  cleanup.proxySocketsRemaining = sockets.size
  cleanup.proxyRequestsRemaining = requests.size
  if (sockets.size !== 0 || requests.size !== 0) failures.push(new Error('Draft transfer proxy still owns live requests or sockets'))
  if (dsh !== undefined) {
    try {
      cleanup.dshExit = await dsh.close()
      if (cleanup.dshExit.forced || (cleanup.dshExit.code !== 0 && cleanup.dshExit.signal !== 'SIGTERM')) {
        failures.push(new Error(`Draft transfer DSH fixture did not stop quiescently: ${sanitized(JSON.stringify(cleanup.dshExit))}`))
      }
    } catch (error) { failures.push(error) }
  }
  await removeTemporaryRoot(root, 'dsh-mobile-transfer-smoke-').then(() => { cleanup.temporaryRootRemoved = true }, error => failures.push(error))
  if (previousCache === undefined) delete process.env.npm_config_cache; else process.env.npm_config_cache = previousCache
  console.log(`Draft transfer fixture cleanup ${JSON.stringify(cleanup)}`)
  if (failures.length === 1) throw failures[0]
  if (failures.length > 1) throw new AggregateError(failures, 'Draft transfer acceptance and cleanup failed')
}
