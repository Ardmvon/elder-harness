// The single source of truth for the cut. Both the renderer and the storyboard
// doc read from here, so the film and its documentation cannot drift apart.
//
// PARTIAL CUT: shots 1-3 are implemented so far (the rest are being written
// against the storyboard). Restore the full cut with:
//   cp src/timeline.full.js src/timeline.js   (once every shot module exists)

export const FPS = 60
export const WIDTH = 1920
export const HEIGHT = 1080

import { shotSpiral } from './shots/01-spiral.js'
import { shotHome } from './shots/02-home.js'
import { shotVoice } from './shots/03-voice.js'

export const SHOTS = [shotSpiral, shotHome, shotVoice]

export const DURATION = Math.max(...SHOTS.map(s => s.end))

export function shotNamed(name) {
  const s = SHOTS.find(x => x.name === name)
  if (!s) throw new Error(`unknown shot: ${name} (have: ${SHOTS.map(x => x.name).join(', ')})`)
  return s
}

export function frameCount() { return Math.round(DURATION * FPS) }
