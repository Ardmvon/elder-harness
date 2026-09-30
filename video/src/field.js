// The coil particle field, shared by the shots that hand off to each other.
//
// Shot 1 assembles this field into the coil and shot 2 picks the *same points* up and
// gathers them into the phone. That is the match cut: same count, same positions, same
// seeds, so the cut lands mid-motion instead of on a density drop.
//
// It used to be generated with Math.random() inside shot 1, which is two bugs at once:
// the layout differed between page loads (so a sharded or repeated render would not match),
// and shot 2 had to invent its own, much sparser field. Seeded here, once.

import { mulberry32, COIL } from './props.js'

/** Both shots must seed the field identically, so the seed lives with the field. */
export const FIELD_SEED = 20260930

/**
 * @returns escape: Float32Array(count*3)  where the particles start (a loose sphere)
 *          coil:   Float32Array(count*3)  where they rest (around the coil spine)
 *          seeds:  Float32Array(count)    per-particle hash, 0..1
 *          us:     Float32Array(count)    position along the coil, 0..1
 */
export function makeCoilField({ count = 36000, seed = 20260930, jitter = 0.30 } = {}) {
  const rnd = mulberry32(seed)
  const escape = new Float32Array(count * 3)
  const coil = new Float32Array(count * 3)
  const seeds = new Float32Array(count)
  const us = new Float32Array(count)

  const turns = COIL.turns
  const height = COIL.height
  const r0 = COIL.r0
  const r1 = COIL.r1

  for (let i = 0; i < count; i++) {
    // Inner turns land first, so u is biased toward 0 (the original distribution).
    const u = Math.pow(rnd(), 0.65)
    const ang = u * Math.PI * 2 * turns
    const rad = r0 + u * (r1 - r0)
    const px = Math.cos(ang) * rad
    const py = (u - 0.5) * height
    const pz = Math.sin(ang) * rad

    // Scatter around the spine: this number is the shot. Too small and the helix becomes a
    // one-pixel filament; too large and the spine disappears inside the cloud.
    const j = jitter * (1 - u * 0.45)
    coil[i * 3 + 0] = px + (rnd() - 0.5) * j
    coil[i * 3 + 1] = py + (rnd() - 0.5) * j
    coil[i * 3 + 2] = pz + (rnd() - 0.5) * j

    const theta = rnd() * Math.PI * 2
    const phi = Math.acos(2 * rnd() - 1)
    const r = 5.5 + rnd() * 3.5
    escape[i * 3 + 0] = r * Math.sin(phi) * Math.cos(theta)
    escape[i * 3 + 1] = r * Math.cos(phi) * 0.6
    escape[i * 3 + 2] = r * Math.sin(phi) * Math.sin(theta)

    seeds[i] = rnd()
    us[i] = u
  }
  return { escape, coil, seeds, us }
}

/**
 * The coil's pose as shot 1 places it (its group is scaled and lifted). Both shots must
 * agree on this, so it lives here rather than being copied into each shot.
 */
export const COIL_POSE = { scale: 0.85, y: 0.10 }
