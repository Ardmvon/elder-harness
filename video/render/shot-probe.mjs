// Render a few moments of one shot and report any page error.
//
// frames.mjs needs a full range to be useful; when a shot throws, the frames come out black
// and the run still "succeeds". This is the fast loop for finding that.

import { launch } from './cdp.mjs'
import { serve } from './serve.mjs'

const times = (process.argv[2] || '6.2,6.6,7.2').split(',').map(Number)
const shot = process.argv[3] || 'home'

const server = await serve()
const browser = await launch({ width: 1920, height: 1080, port: 9444 })
const { session } = browser

async function evalStr(expr) {
  const res = await session.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
  if (res.exceptionDetails) return { error: res.exceptionDetails.text + ' ' + (res.exceptionDetails.exception?.description ?? '') }
  return { value: res.result.value }
}

await session.send('Page.enable')
await session.send('Runtime.enable')
await session.send('Page.navigate', { url: server.origin + `/index.html?shot=${shot}` })

for (let i = 0; i < 100; i++) {
  const r = await evalStr('window.__ready === true')
  if (r.value) break
  await new Promise((r) => setTimeout(r, 100))
}
const boot = await evalStr('window.__error || ""')
if (boot.value) console.log('  boot error:', boot.value)

for (const t of times) {
  await evalStr(`window.__error = ""; window.__renderAt(${t})`)
  const err = await evalStr('window.__error || ""')
  console.log(`  t=${t}: ${err.value ? '✗ ' + err.value.replace(/\n/g, ' ').slice(0, 200) : 'ok'}`)
}
await browser.close()
process.exit(0)
