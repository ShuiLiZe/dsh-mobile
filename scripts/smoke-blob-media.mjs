/** Decode owned valid silent WebM under actual source CSP; never runs community-plugin code. */
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { once } from 'node:events'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { chromium } from 'playwright'
import { build } from 'tsdown'

const repository = fileURLToPath(new URL('..', import.meta.url))
const prefix = 'dsh-mobile-blob-media-'
const root = await mkdtemp(join(tmpdir(), prefix))
const output = process.env.DSH_BLOB_MEDIA_EVIDENCE === undefined ? undefined : resolve(process.env.DSH_BLOB_MEDIA_EVIDENCE)
let browser, server, other
let clip
let externalHttpRequests = 0
let externalHttpsRequests = 0
let externalConnectRequests = 0
let failure
const receipt = { scope: 'Owned silent VP8 WebM and current source setSecurityHeaders, not packed DSH/pet/native acceptance', cases: [] }

async function listen(server) {
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  return server.address().port
}
async function close(server) {
  if (!server?.listening) return
  server.closeAllConnections()
  await new Promise((done, reject) => server.close(error => error ? reject(error) : done()))
}
async function evaluate(cdp, expression) {
  let timer
  try {
    const result = await Promise.race([
      cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture: false }),
      new Promise((_done, reject) => { timer = setTimeout(() => reject(new Error('Owned media script exceeded its execution budget')), 10000) }),
    ])
    assert.equal(result.exceptionDetails, undefined, 'Owned browser check raised a script exception')
    return result.result.value
  } finally { clearTimeout(timer) }
}
async function until(cdp, expression, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const result = await evaluate(cdp, expression)
    if (result) return result
    await new Promise(done => setTimeout(done, 50))
  }
  throw new Error('Owned media observation did not reach its expected browser state')
}

const generatedClip = `(async()=>{
  if(!MediaRecorder.isTypeSupported('video/webm;codecs=vp8'))throw Error('VP8 MediaRecorder unavailable');
  const canvas=document.createElement('canvas');canvas.width=160;canvas.height=90;document.body.append(canvas);
  const context=canvas.getContext('2d'),stream=canvas.captureStream(30),chunks=[];
  const recorder=new MediaRecorder(stream,{mimeType:'video/webm;codecs=vp8'});
  const stopped=new Promise((done,fail)=>{recorder.onstop=done;recorder.onerror=()=>fail(Error('Owned recorder failed'))});
  recorder.ondataavailable=event=>{if(event.data.size)chunks.push(event.data)};recorder.start();
  await new Promise(done=>{let count=0;const draw=()=>{context.fillStyle=count%2?'#4c86df':'#55bfae';context.fillRect(0,0,160,90);if(++count===24){done();return}requestAnimationFrame(draw)};draw()});
  recorder.stop();await stopped;for(const track of stream.getTracks())track.stop();
  const bytes=new Uint8Array(await new Blob(chunks,{type:'video/webm'}).arrayBuffer());
  return {bytes:[...bytes],activation:navigator.userActivation.hasBeenActive,mime:recorder.mimeType};
})()`

function pageHtml(kind, mediaUrl, connectUrl) {
  return `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Owned Blob media policy</title><video id="video" muted autoplay playsinline loop></video><script>
    window.mediaState={done:false,kind:${JSON.stringify(kind)},activationBefore:navigator.userActivation.hasBeenActive,violations:[],maxTime:0,loaded:false,play:null,error:null};
    const state=window.mediaState,video=document.querySelector('#video');video.muted=true;video.autoplay=true;video.playsInline=true;video.loop=true;
    document.addEventListener('securitypolicyviolation',event=>state.violations.push({directive:event.effectiveDirective,blocked:event.blockedURI.startsWith('blob')?'blob':event.blockedURI}));
    video.addEventListener('loadeddata',()=>state.loaded=true);video.addEventListener('timeupdate',()=>state.maxTime=Math.max(state.maxTime,video.currentTime));
    video.addEventListener('error',()=>{state.error=video.error?.code??null;state.done=true});
    (async()=>{try{
      if(${JSON.stringify(kind)}==='connect'){try{await fetch(${JSON.stringify(connectUrl)});state.connect='unexpected-success'}catch{state.connect='blocked'}state.done=true;return}
      if(${JSON.stringify(kind)}==='blob'){const result=await fetch('/clip.webm');if(!result.ok)throw Error('Owned clip unavailable');video.src=URL.createObjectURL(await result.blob())}
      else video.src=${JSON.stringify(mediaUrl)};
      video.load();try{await video.play();state.play={ok:true}}catch(error){state.play={ok:false,name:error.name};state.done=true}
    }catch(error){state.setupError=error.name;state.done=true}})();
    addEventListener('pagehide',()=>{video.pause();if(video.src.startsWith('blob:'))URL.revokeObjectURL(video.src)},{once:true});
  </script>`
}

try {
  const bundled = join(root, 'source')
  await build({ config: false, entry: { security: join(repository, 'src/http-security.ts') }, outDir: bundled,
    format: 'esm', platform: 'node', target: 'node22', dts: false, clean: false, logLevel: 'silent',
    outputOptions: { entryFileNames: 'security.mjs' } })
  const { setSecurityHeaders } = await import(pathToFileURL(join(bundled, 'security.mjs')).href)
  browser = await chromium.launch({ headless: true })
  receipt.browserVersion = browser.version()
  const generation = await browser.newPage()
  const generationCdp = await generation.context().newCDPSession(generation)
  await generation.goto('about:blank')
  const generated = await evaluate(generationCdp, generatedClip)
  assert.equal(generated.activation, false)
  clip = Buffer.from(generated.bytes)
  assert(clip.length > 128 && clip.length < 64 * 1024, 'Owned recorder did not produce bounded nonempty media')
  assert.equal(clip.subarray(0, 4).toString('hex'), '1a45dfa3', 'Owned clip lacks an EBML/WebM header')
  receipt.clip = { bytes: clip.length, mime: generated.mime, sha256: createHash('sha256').update(clip).digest('hex'), generatedWithoutGesture: true }
  await generation.close()
  other = createServer((_request, response) => {
    externalHttpRequests += 1
    response.writeHead(200, { 'content-type': 'video/webm' }); response.end(clip)
  })
  const otherPort = await listen(other)
  server = createServer((request, response) => {
    const url = new URL(request.url, 'http://owned.invalid')
    const gateway = url.pathname === '/gateway'
    setSecurityHeaders(response, false, gateway ? 'gateway' : 'proxied')
    if (url.pathname === '/clip.webm') {
      response.writeHead(200, { 'content-type': 'video/webm', 'content-length': clip.length }); response.end(clip); return
    }
    const kind = url.searchParams.get('kind') ?? 'blob'
    const mediaUrl = kind === 'external-http' ? `http://127.0.0.1:${otherPort}/clip.webm`
      : kind === 'external-https' ? 'https://media.example.invalid/clip.webm' : '/clip.webm'
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
    response.end(pageHtml(kind, mediaUrl, 'https://connect.example.invalid/forbidden'))
  })
  const port = await listen(server)
  const context = await browser.newContext()
  await context.route('https://media.example.invalid/**', route => {
    externalHttpsRequests += 1
    return route.fulfill({ status: 200, contentType: 'video/webm', body: clip })
  })
  await context.route('https://connect.example.invalid/**', route => {
    externalConnectRequests += 1
    return route.fulfill({ status: 200, contentType: 'text/plain', body: 'must remain blocked' })
  })
  for (const test of [
    { policy: 'proxied', kind: 'same-origin', plays: true },
    { policy: 'proxied', kind: 'blob', plays: true },
    { policy: 'gateway', kind: 'blob', plays: false, blocked: 'blob' },
    { policy: 'proxied', kind: 'external-http', plays: false, blocked: `http://127.0.0.1:${otherPort}` },
    { policy: 'proxied', kind: 'external-https', plays: false, blocked: 'https://media.example.invalid' },
    { policy: 'proxied', kind: 'connect', plays: false },
  ]) {
    const page = await context.newPage()
    const cdp = await context.newCDPSession(page)
    const navigation = await page.goto(`http://127.0.0.1:${port}/${test.policy}?kind=${test.kind}`, { waitUntil: 'domcontentloaded' })
    const policy = navigation.headers()['content-security-policy']
    assert(policy)
    // No response interception or CSP replacement occurs: this is the source function's actual header.
    const state = await until(cdp, test.plays
      ? `window.mediaState?.play?.ok&&mediaState.loaded&&mediaState.maxTime>0.1?({...mediaState,time:video.currentTime,paused:video.paused,width:video.videoWidth,height:video.videoHeight,activationAfter:navigator.userActivation.hasBeenActive}):false`
      : `window.mediaState?.done&&mediaState.violations.length?({...mediaState,time:video.currentTime,paused:video.paused,activationAfter:navigator.userActivation.hasBeenActive}):false`)
    assert.equal(state.activationBefore, false)
    assert.equal(state.activationAfter, false)
    assert.equal(state.setupError, undefined)
    if (test.plays) {
      assert.deepEqual(state.play, { ok: true })
      assert.equal(state.error, null)
      assert.equal(state.paused, false)
      assert.equal(state.width, 160); assert.equal(state.height, 90)
      assert(state.maxTime > 0.1)
      assert.deepEqual(state.violations, [])
    } else if (test.kind === 'connect') {
      assert.equal(state.connect, 'blocked')
      assert(state.violations.some(item => item.directive === 'connect-src'))
    } else {
      assert.equal(state.play?.ok, false)
      assert.equal(state.time, 0)
      assert(state.violations.some(item => item.directive === 'media-src' && item.blocked.startsWith(test.blocked)))
    }
    receipt.cases.push({ policy: test.policy, kind: test.kind, actualCsp: policy, ...state })
    await page.close()
  }
  assert.equal(externalHttpRequests, 0)
  assert.equal(externalHttpsRequests, 0)
  assert.equal(externalConnectRequests, 0)
  receipt.externalRequests = { httpMedia: externalHttpRequests, httpsMedia: externalHttpsRequests, connect: externalConnectRequests }
  receipt.passed = true
} catch (error) {
  failure = error
} finally {
  const failures = failure === undefined ? [] : [failure]
  receipt.cleanup = { browserClosed: browser === undefined, serversClosed: false, temporaryRootRemoved: false }
  if (browser) await browser.close().then(() => { receipt.cleanup.browserClosed = true }, error => failures.push(error))
  await close(server).catch(error => failures.push(error))
  await close(other).catch(error => failures.push(error))
  receipt.cleanup.serversClosed = !server?.listening && !other?.listening
  try {
    assert.equal(dirname(resolve(root)), resolve(tmpdir()))
    assert(basename(root).startsWith(prefix))
    await rm(root, { recursive: true, force: true })
    receipt.cleanup.temporaryRootRemoved = true
  } catch (error) { failures.push(error) }
  if (failures.length === 1) failure = failures[0]
  else if (failures.length > 1) failure = new AggregateError(failures, 'Owned media check and cleanup failed')
}
if (failure !== undefined) throw failure
if (output !== undefined) {
  await mkdir(dirname(output), { recursive: true })
  await writeFile(output, JSON.stringify(receipt, null, 2) + '\n')
}
console.log(JSON.stringify(receipt, null, 2))
