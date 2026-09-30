// The single source of truth for the cut. Both the renderer and the storyboard
// doc read from here, so the film and its documentation cannot drift apart.
//
// PARTIAL CUT: the first five chapters (序 + Ⅰ–Ⅳ) are implemented, 0:00–2:05 = 125.0s.
// Chapters Ⅴ–Ⅹ are still to be written; the full 100-bar cut is 250.0s.

export const FPS = 60
export const WIDTH = 1920
export const HEIGHT = 1080

import { shotSpiral } from './shots/01-spiral.js'   // 序  0:00–0:15
import { shotHome } from './shots/02-home.js'       // Ⅰ  0:15–0:27
import { shotVoice } from './shots/03-voice.js'     // Ⅰ  0:27–0:40
import { shotTree } from './shots/04-tree.js'       // Ⅱ  0:40–1:05
import { shotVortex } from './shots/05-vortex.js'   // Ⅲ  1:05–1:35
import { shotClaim } from './shots/06-claim.js'     // Ⅳ  1:35–2:05

// Chapters 1–5 of the 250s cut: 序 15 + Ⅰ 25 + Ⅱ 25 + Ⅲ 30 + Ⅳ 30 = 125s.
export const SHOTS = [shotSpiral, shotHome, shotVoice, shotTree, shotVortex, shotClaim]

export const DURATION = Math.max(...SHOTS.map(s => s.end))

/**
 * The edit's voice, aggregated from what each shot declares about itself.
 *
 * A shot carries `chapter`, `subs` (local seconds), `sfx`, `hits` and `mb` next to the
 * code that draws it, so picture and sound cannot drift apart. The page renders chapters
 * and subtitles from this; the music script is generated from the same cue list.
 */
export const FILM = (() => {
  const film = { duration: DURATION, chapters: [], subs: [], sfx: [], hits: [] }
  for (const s of SHOTS) {
    if (s.chapter) film.chapters.push({ ...s.chapter, start: s.start, end: s.end })
    for (const [a, b, zh, en] of s.subs ?? []) {
      film.subs.push({ a: s.start + a, b: s.start + b, zh, en })
    }
    for (const [t, name, o] of s.sfx ?? []) {
      film.sfx.push({ t: +(s.start + t).toFixed(4), name, ...(o ?? {}) })
    }
    for (const [t, k] of s.hits ?? []) film.hits.push({ t: s.start + t, k })
  }
  film.sfx.sort((a, b) => a.t - b.t)
  film.hits.sort((a, b) => a.t - b.t)
  return film
})()

/** The cue sheet the music script reads, exactly like the reference film's cues.json. */
export function cueSheet() {
  return FILM.sfx.map(c => ({ ...c }))
}

export function shotNamed(name) {
  const s = SHOTS.find(x => x.name === name)
  if (!s) throw new Error(`unknown shot: ${name} (have: ${SHOTS.map(x => x.name).join(', ')})`)
  return s
}

export function frameCount() { return Math.round(DURATION * FPS) }
