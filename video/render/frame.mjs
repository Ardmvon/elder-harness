// Render ONE moment to ONE named file.
//
// Added after a stale file from a previous range render was mistaken for the
// current output and sent me chasing a bug that did not exist. Iterating on a
// shot should never involve guessing which frame on disk is the new one.
//
//   node render/frame.mjs 3.5               -> /tmp/frame-3.5.png
//   node render/frame.mjs 3.5 --out a.png --boost 1.4

import { launch } from './cdp.mjs'
import { serve } from './serve.mjs'
import { captureExpr, decodeDataURL } from './capture.mjs'
import { writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const args = parseArgs(process.argv.slice(2))
const t = Number(args._[0] ?? 0)
const out = args.out ?? join(tmpdir(), `frame-${t}.png`)
const boost = args.boost ? Number(args.boost) : 0

const server = await serve()
const browser = await launch({ width: 1920, height: 1080, port: args.port ?? 9222 })
const { session } = browser
try {
  await session.send('Page.enable')
  await session.send('Runtime.enable')
  await session.send('Page.navigate', { url: server.origin + '/index.html' })
  const ok = await poll(session, 'window.__ready === true', 60000)
  if (!ok) throw new Error('page not ready: ' + await q(session, 'window.__error || "no error"'))

  // renderAt twice: the first call enters the shot, the second reflects the
  // settled state. A shot that looks different on repeat is a bug of its own.
  await q(session, `window.__renderAt(${t})`)
  await q(session, `window.__renderAt(${t})`)

  const raw = decodeDataURL(await q(session, captureExpr(t, 'png')))
  if (boost) {
    const tmp = out.replace(/\.png$/, '.raw.png')
    writeFileSync(tmp, raw)
    const r = spawnSync('ffmpeg', ['-v', 'error', '-y', '-i', tmp,
      '-vf', `eq=brightness=${(boost - 1) * 0.12}:contrast=${boost}`, out], { stdio: 'inherit' })
    if (r.status !== 0) throw new Error('ffmpeg boost failed')
  } else {
    writeFileSync(out, raw)
  }
  console.log(`t=${t}s -> ${out}`)
} finally {
  await browser.close()
  await server.close()
}

function parseArgs(argv) {
  const out = { _: [] }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (!a.startsWith('--')) { out._.push(a); continue }
    const key = a.slice(2).replace(/-([a-z])/g, (_, c) => c.toUpperCase())
    const next = argv[i + 1]
    if (next === undefined || next.startsWith('--')) out[key] = true
    else { out[key] = next; i++ }
  }
  return out
}
async function q(session, expr) {
  const r = await session.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text)
  return r.result.value
}
async function poll(session, expr, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    try { if (await q(session, expr)) return true } catch { }
    await new Promise(r => setTimeout(r, 50))
  }
  return false
}
