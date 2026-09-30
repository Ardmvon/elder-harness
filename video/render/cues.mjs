// Dump the film's cue sheet, exactly like the reference film's `export.mjs --cues`.
//
//   node render/cues.mjs            -> video/cues.json
//
// The cue sheet is generated *by the page*, not by hand: every shot declares its own
// sound cues next to the code that draws it, so the music can never drift from the
// picture. `music.py` reads this file and synthesizes the score and effects.

import { writeFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { launch } from './cdp.mjs'
import { serve } from './serve.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const OUT = join(HERE, '..', 'cues.json')

const server = await serve()
const browser = await launch({ width: 1920, height: 1080, port: 9225 })
const { session } = browser
const ev = async (expression) => {
  const r = await session.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text)
  return r.result.value
}
try {
  await session.send('Page.enable'); await session.send('Runtime.enable')
  await session.send('Page.navigate', { url: server.origin + '/index.html' })
  for (let i = 0; i < 400; i++) { if (await ev('window.__ready === true').catch(() => false)) break; await new Promise(r => setTimeout(r, 100)) }
  const cues = JSON.parse(await ev('JSON.stringify(window.__cues())'))
  const dur = await ev('window.__duration')
  writeFileSync(OUT, JSON.stringify({ duration: dur, cues }, null, 1))
  console.log(`wrote ${OUT}  (${cues.length} cues, film ${dur.toFixed(1)}s)`)
} finally {
  await browser.close()
  await server.close()
}
