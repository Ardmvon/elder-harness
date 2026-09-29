// The single source of truth for the cut. Both the renderer and the storyboard
// doc read from here, so the film and its documentation cannot drift apart.

export const FPS = 60
export const WIDTH = 1920
export const HEIGHT = 1080

import { shotSpiral }    from './shots/01-spiral.js'
import { shotHome }      from './shots/02-home.js'
import { shotVoice }     from './shots/03-voice.js'
import { shotLoop }      from './shots/04-loop.js'
import { shotDevice }    from './shots/04b-device.js'
import { shotClaim }     from './shots/05-claim.js'
import { shotStairs }    from './shots/06-stairs.js'
import { shotFamily }    from './shots/07-family.js'
import { shotEvolve }    from './shots/08-evolve.js'
import { shotClose }     from './shots/09-close.js'

export const SHOTS = [
  shotSpiral, shotHome, shotVoice, shotLoop, shotDevice,
  shotClaim, shotStairs, shotFamily, shotEvolve, shotClose,
]

export const DURATION = Math.max(...SHOTS.map(s => s.end))

export function shotNamed(name) {
  const s = SHOTS.find(x => x.name === name)
  if (!s) throw new Error(`unknown shot: ${name} (have: ${SHOTS.map(x => x.name).join(', ')})`)
  return s
}

export function frameCount() {
  return Math.round(DURATION * FPS)
}
