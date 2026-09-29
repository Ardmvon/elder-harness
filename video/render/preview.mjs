// Live preview: requestAnimationFrame drives the same __renderAt the offline
// renderer uses, so what you watch here is what gets extracted.

import { launch } from './cdp.mjs'
import { serve } from './serve.mjs'

const SPEED = Number(process.env.PREVIEW_SPEED || 1)

const server = await serve({ port: Number(process.env.PORT || 8123) })
console.log(`serving ${server.origin}`)
console.log(`open ${server.origin}/index.html — press Ctrl-C to stop`)

const browser = await launch({ width: 1920, height: 1080, port: 9333 })
try {
  await browser.session.send('Page.enable')
  await browser.session.send('Runtime.enable')
  await browser.session.send('Page.navigate', { url: server.origin + '/index.html' })
  // Keep the process alive; the page animates itself once ready.
} catch (e) {
  console.error(e)
}
process.on('SIGINT', async () => { await browser.close(); await server.close(); process.exit(0) })
