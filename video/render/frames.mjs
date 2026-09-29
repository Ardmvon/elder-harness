// Offline frame extraction.
//
// Drives the same index.html the preview uses, calling __renderAt(t) once per
// frame. Deterministic by construction: the page never reads a clock, only the
// frame index we hand it.
//
//   node render/frames.mjs                      # whole film
//   node render/frames.mjs --range 26,28        # seconds
//   node render/frames.mjs --only spiral        # one shot
//   node render/frames.mjs --fps 30 --quality 95

import { launch } from './cdp.mjs'
import { serve } from './serve.mjs'
import { captureExpr, decodeDataURL, extFor } from './capture.mjs'
import { mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..')

const args = parseArgs(process.argv.slice(2))
const FPS = args.fps ?? 60
const ONLY = args.only ?? null
const FORMAT = args.format ?? 'jpeg'
const QUALITY = args.quality ?? 92
const OUT = join(ROOT, args.out ?? 'out/frames')

const server = await serve()
const browser = await launch({ width: 1920, height: 1080, port: args.port ?? 9222 })
const { session } = browser

try {
  await session.send('Page.enable')
  await session.send('Runtime.enable')
  // ?shot= selects one shot; --query passes render-time switches through to the page
  // (currently bloom and haze) so a frame can be diagnosed one layer at a time.
  const query = new URLSearchParams(args.query ?? '')
  if (ONLY) query.set('shot', ONLY)
  const url = server.origin + '/index.html' + (query.toString() ? `?${query}` : '')
  await session.send('Page.navigate', { url })

  let ready = false
  for (let i = 0; i < 600 && !ready; i++) {
    try { ready = await evalStr(session, 'window.__ready === true') } catch { }
    if (!ready) await sleep(100)
  }
  if (!ready) {
    const why = await evalStr(session, 'window.__error || "no error reported"').catch(() => 'unknown')
    throw new Error(`page never became ready: ${why}`)
  }

  const info = JSON.parse(await evalStr(session, 'JSON.stringify(window.__film)'))
  let t0 = 0, t1 = info.duration
  if (args.range) [t0, t1] = String(args.range).split(',').map(Number)

  const first = Math.round(t0 * FPS)
  const last = Math.round(t1 * FPS)
  const total = last - first

  console.log(`film "${info.title}" ${info.duration}s @ ${FPS}fps`)
  console.log(`frames ${first}..${last - 1} (${total})`)
  console.log(`shots: ${info.shots.map(s => `${s.name}@${s.start}s`).join('  ')}`)

  if (args.clean && existsSync(OUT)) rmSync(OUT, { recursive: true, force: true })
  mkdirSync(OUT, { recursive: true })

  const started = Date.now()
  const ext = extFor(FORMAT)
  for (let f = first; f < last; f++) {
    const t = f / FPS
    const dataURL = await evalStr(session, captureExpr(t, FORMAT, QUALITY))
    writeFileSync(join(OUT, `f${String(f).padStart(6, '0')}.${ext}`), decodeDataURL(dataURL))
    // A shot that throws keeps producing frames — black ones — and the run still "succeeds".
    // index.html records the error; surfacing it here is the difference between a finished
    // render and a very fast way to produce 3600 black frames.
    const pageError = await evalStr(session, 'window.__error || ""').catch(() => '')
    if (pageError) throw new Error(`shot threw at t=${t.toFixed(2)}: ${pageError.split('\n')[0]}`)

    const done = f - first + 1
    if (done % 20 === 0 || f === last - 1) {
      const eta = (Date.now() - started) / done * (total - done) / 1000
      process.stdout.write(`\r  ${done}/${total}  ${(done / total * 100).toFixed(1)}%  eta ${eta.toFixed(0)}s    `)
    }
  }
  process.stdout.write('\n')
  console.log(`wrote ${total} frames to ${OUT}`)
} finally {
  await browser.close()
  await server.close()
}

function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (!a.startsWith('--')) continue
    const key = a.slice(2)
    const next = argv[i + 1]
    if (next === undefined || next.startsWith('--')) out[key] = true
    else { out[key] = isNaN(Number(next)) ? next : Number(next); i++ }
  }
  return out
}
// A function declaration, not a const arrow: the readiness loop above calls
// this before a const initialiser would have run (temporal dead zone).
function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }
async function evalStr(session, expr) {
  const r = await session.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error('page error: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text))
  return r.result.value
}
