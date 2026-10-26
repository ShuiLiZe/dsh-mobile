import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { chromium } from 'playwright'

const client = await readFile(new URL('../lib/client.js', import.meta.url), 'utf8')
const browser = await chromium.launch({ headless: true })
let cases = 0
try {
  for (const [lang, add, camera, gallery] of [['en', 'Add', 'Take photo', 'Gallery'], ['zh', '添加', '拍照', '相册'], ['it', 'Aggiungi', 'Scatta foto', 'Galleria']]) {
    const context = await browser.newContext({ viewport: { width: 393, height: 844 } })
    try {
      const page = await context.newPage()
      await page.route('https://gallery.test/**', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="${lang}"><head><meta charset="utf-8"><style>button{min-height:40px}svg{width:16px;height:16px}</style></head><body><main class="dshm-main"><section data-conversation-session="one"><div data-conversation-scroll></div><div data-composer-card><div data-input-scroll><div contenteditable="true" data-composer-input>draft</div></div><div><div><button id="add" aria-haspopup="listbox" aria-expanded="true">+</button><div data-trigger-menu><div role="listbox"><div role="presentation">${add}</div><button role="option" class="Menu_item"><span class="Menu_itemIcon">F</span><span class="Menu_itemName">File</span></button><button role="option">Goal</button><button role="option">Plan</button><button role="option">Feedback</button><div role="presentation">Other</div><button role="option">Compact</button><button role="option">Model</button></div></div></div><div></div></div></div></section></main></body></html>` }))
      await page.goto('https://gallery.test/')
      await page.evaluate(() => {
        window.drops = []
        window.imageDropOwner = event => { event.preventDefault(); event.dataTransfer.dropEffect = 'copy' }
        document.addEventListener('dragover', window.imageDropOwner)
        document.addEventListener('drop', event => { window.drops.push({ files: [...event.dataTransfer.files].map(file => file.name), items: [...event.dataTransfer.items].map(item => item.getAsFile().name) }) })
        window.__ModuleLoader__ = { load: ({ factory }) => { window.mobile = factory(name => { if (name === 'react') return {}; throw new Error(name) }) } }
      })
      await page.addScriptTag({ content: client })
      await page.evaluate(() => {
        const cleanup = []
        window.mobile.apply({ effect: effect => { const dispose = effect(); if (typeof dispose === 'function') cleanup.push(dispose) }, get: () => undefined, slots: { inject: () => () => {}, register: () => () => {} } })
        window.disposeMobile = () => cleanup.reverse().forEach(dispose => dispose())
      })
      const button = page.locator('[data-dsh-mobile-media-action="gallery"]')
      await button.waitFor({ timeout: 5000 }).catch(async error => { throw new Error(`${lang}: ${await page.locator('body').innerHTML()}`, { cause: error }) })
      assert.equal(await button.getAttribute('aria-label'), gallery)
      assert.equal(await page.locator('[data-dsh-mobile-media-action="camera"]').getAttribute('aria-label'), camera)
      assert.deepEqual(await page.locator('[role="listbox"]').evaluate(list => [...list.children].map(child => child.textContent)), [add, gallery, camera, 'FFile', 'Goal', 'Plan', 'Feedback', 'Other', 'Compact', 'Model'])
      assert.equal(await button.getAttribute('class'), 'Menu_item')
      assert.ok(await button.evaluate(element => { const rect = element.getBoundingClientRect(); return rect.height >= 40 && element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)) }))
      // Observer passes retain the same nodes and do not repeatedly mutate their placement/classes.
      await page.evaluate(() => { window.galleryNode = document.querySelector('[data-dsh-mobile-media-action="gallery"]'); window.menuMutations = 0; window.menuObserver = new MutationObserver(records => { window.menuMutations += records.length }); window.menuObserver.observe(document.querySelector('[role="listbox"]'), { childList: true, subtree: true, attributes: true }); document.body.classList.add('gallery-observer-wake') })
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
      assert.equal(await page.evaluate(() => window.menuMutations), 0)
      await page.evaluate(() => { window.menuObserver.disconnect(); const menu = document.querySelector('[data-trigger-menu]'); menu.innerHTML = menu.innerHTML.replace(/<button[^>]*data-dsh-mobile-media-action[\s\S]*?<\/button>/g, ''); })
      await button.waitFor()
      assert.equal(await button.count(), 1)
      assert.equal(await button.evaluate(element => element === window.galleryNode), true)
      assert.deepEqual(await page.locator('[role="listbox"]').evaluate(list => [...list.children].map(child => child.textContent)), [add, gallery, camera, 'FFile', 'Goal', 'Plan', 'Feedback', 'Other', 'Compact', 'Model'])
      // Keep a valid stock frame/composer mounted: absence of .dshm-main must
      // suppress Gallery without merely losing all composer/menu detection.
      await page.evaluate(() => {
        const main = document.querySelector('main')
        const frame = document.createElement('div')
        frame.id = 'stock-frame'
        frame.className = 'App_fixture_frame'
        const sidebar = document.createElement('aside')
        sidebar.className = 'App_fixture_sidebarCol'
        main.before(frame)
        main.className = 'App_fixture_centerCol'
        frame.append(sidebar, main)
      })
      await page.waitForFunction(() => document.querySelector('[data-dsh-mobile-frame]') !== null
        && document.querySelector('[data-dsh-mobile-media-action="gallery"]') === null)
      assert.equal(await page.locator('.dshm-main').count(), 0)
      assert.equal(await page.locator('[data-dsh-mobile-media-action="camera"]').count(), 1)
      assert.equal(await page.locator('[data-composer-card] [data-trigger-menu] [role="listbox"]').count(), 1)
      assert.deepEqual(await page.locator('[role="listbox"]').evaluate(list => [...list.children].map(child => child.textContent)), [add, 'FFile', 'Goal', 'Plan', 'Feedback', camera, 'Other', 'Compact', 'Model'])
      await page.evaluate(() => {
        const frame = document.querySelector('#stock-frame')
        const main = document.querySelector('main')
        frame.before(main)
        frame.remove()
        main.className = 'dshm-main'
      })
      await button.waitFor()
      // Without a document dragover owner, image preflight cannot authorize
      // intake. A synthetic click must not bypass the disabled control guard.
      await page.evaluate(() => {
        document.removeEventListener('dragover', window.imageDropOwner)
        window.blockedPickerOpens = 0
        window.normalInputClick = HTMLInputElement.prototype.click
        HTMLInputElement.prototype.click = function () { window.blockedPickerOpens++ }
        document.querySelector('[data-composer-card]').classList.add('wake-no-drop-owner')
      })
      await page.waitForFunction(() => document.querySelector('[data-dsh-mobile-media-action="gallery"]').disabled)
      await button.evaluate(element => element.dispatchEvent(new MouseEvent('click', { bubbles: true })))
      assert.equal(await page.evaluate(() => window.blockedPickerOpens), 0)
      assert.equal(await page.locator('input[type="file"]').count(), 0)
      assert.deepEqual(await page.evaluate(() => window.drops), [])
      await page.evaluate(() => {
        HTMLInputElement.prototype.click = window.normalInputClick
        document.addEventListener('dragover', window.imageDropOwner)
        document.querySelector('[data-composer-card]').classList.remove('wake-no-drop-owner')
      })
      await page.waitForFunction(() => !document.querySelector('[data-dsh-mobile-media-action="gallery"]').disabled)
      const choose = async () => {
        const pending = page.waitForEvent('filechooser')
        await button.click()
        const chooser = await pending
        const input = chooser.element()
        assert.equal(await input.getAttribute('accept'), 'image/*')
        assert.equal(await input.getAttribute('capture'), null)
        assert.equal(await chooser.isMultiple(), true)
        return { chooser, input }
      }
      let selection = await choose()
      await selection.chooser.setFiles([{ name: 'first.png', mimeType: 'image/png', buffer: Buffer.from('image') }, { name: 'second.jpg', mimeType: 'image/jpeg', buffer: Buffer.from('image') }])
      await page.waitForFunction(() => window.drops.length === 1)
      assert.deepEqual(await page.evaluate(() => window.drops), [{ files: ['first.png', 'second.jpg'], items: ['first.png', 'second.jpg'] }])
      await page.waitForFunction(() => !document.querySelector('[data-dsh-mobile-media-action="gallery"]').disabled)
      selection = await choose()
      await selection.input.evaluate(input => input.dispatchEvent(new Event('cancel')))
      await page.waitForFunction(() => document.querySelectorAll('input[type="file"]').length === 0)
      assert.equal(await page.evaluate(() => window.drops.length), 1)
      await page.waitForFunction(() => !document.querySelector('[data-dsh-mobile-media-action="gallery"]').disabled)
      selection = await choose()
      await page.evaluate(() => { document.querySelector('[data-conversation-session]').setAttribute('data-conversation-session', 'two') })
      await selection.chooser.setFiles({ name: 'stale.png', mimeType: 'image/png', buffer: Buffer.from('image') })
      await page.waitForFunction(() => document.querySelectorAll('input[type="file"]').length === 0)
      assert.equal(await page.evaluate(() => window.drops.length), 1)
      await page.waitForFunction(() => !document.querySelector('[data-dsh-mobile-media-action="gallery"]').disabled)
      // A picker-open rejection cleans up and unlocks a later explicit retry.
      await page.evaluate(() => { window.realInputClick = HTMLInputElement.prototype.click; HTMLInputElement.prototype.click = function () { throw new Error('owned picker rejection') } })
      await button.click()
      await page.waitForFunction(() => document.querySelector('.dsh-mobile-media-toast').dataset.visible === 'true')
      assert.equal(await page.locator('input[type="file"]').count(), 0)
      await page.evaluate(() => { HTMLInputElement.prototype.click = window.realInputClick })
      await page.waitForFunction(() => !document.querySelector('[data-dsh-mobile-media-action="gallery"]').disabled)
      selection = await choose()
      await page.evaluate(() => { history.pushState({}, '', '/other') })
      await selection.chooser.setFiles({ name: 'old-route.png', mimeType: 'image/png', buffer: Buffer.from('image') })
      await page.waitForFunction(() => document.querySelectorAll('input[type="file"]').length === 0)
      assert.equal(await page.evaluate(() => window.drops.length), 1)
      await page.waitForFunction(() => !document.querySelector('[data-dsh-mobile-media-action="gallery"]').disabled)
      selection = await choose()
      // Double taps while busy cannot create another hidden input.
      await button.evaluate(element => element.dispatchEvent(new MouseEvent('click', { bubbles: true })))
      assert.equal(await page.locator('input[type="file"]').count(), 1)
      await page.evaluate(() => { window.lateInput = document.querySelector('input[type="file"]'); Object.defineProperty(window.lateInput, 'files', { value: [new File(['image'], 'after-dispose.png', { type: 'image/png' })] }); window.disposeMobile() })
      await page.waitForFunction(() => document.querySelectorAll('input[type="file"]').length === 0)
      await page.evaluate(() => { window.lateInput.dispatchEvent(new Event('change')) })
      assert.equal(await page.evaluate(() => window.drops.length), 1)
      assert.equal(await button.count(), 0)
      cases++
    } finally { await context.close() }
  }
  console.log(`Gallery mounted-browser smoke passed (${cases} locales; stock Gallery absence, disabled intake without dragover owner, chooser MIME/capture, multiple file+item handoff, idempotence, rerender, cancellation, stale session, disposal). Controlled DSH-markup fixture; not physical Android or actual DSH draft persistence.`)
} finally { await browser.close() }
