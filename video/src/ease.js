// Easing and timing. Shared because the smooth01 bug (see below) cost an hour and
// would cost it again in every shot that grew its own copy.
//
// Two layers live here:
//   1. the original ramps (smooth01 / easeInOut / easeOut / spring), kept because
//      half the film is written against them, and
//   2. the motion vocabulary shots 2-3 are now built on: a real cubic-bezier solver
//      (so the film can use the same curves as CSS/After Effects), an "arrive and
//      settle" spring, and a decaying impulse for reaction beats.
//
// The rule from stage.js still holds: every function here is pure in t. Nothing
// accumulates, so the same second of the film is the same frame on every machine.

/**
 * Ramp 0 -> 1 over `dur` seconds from `start`. Negative `dur` runs backwards.
 *
 * The clamp must happen BEFORE the cubic. `x*x*(3-2x)` is only a smoothstep on
 * [0,1]; past 1 it dives sharply negative, and clamping the *result* turns
 * "past the end of the ramp" into 0 instead of 1. That is exactly how shot 1
 * first rendered as a bare background.
 */
export function smooth01(t, start, dur) {
  if (dur === 0) return t >= start ? 1 : 0
  const raw = dur > 0 ? (t - start) / dur : 1 - (t - start) / -dur
  const x = Math.max(0, Math.min(1, raw))
  return x * x * (3 - 2 * x)
}

/** Smoothstep on an already-normalised value. */
export function smoothstep01(x) {
  const c = Math.max(0, Math.min(1, x))
  return c * c * (3 - 2 * c)
}

export function easeInOut(x) {
  const c = Math.max(0, Math.min(1, x))
  return c < 0.5 ? 4 * c * c * c : 1 - Math.pow(-2 * c + 2, 3) / 2
}

export function easeOut(x) {
  const c = Math.max(0, Math.min(1, x))
  return 1 - Math.pow(1 - c, 3)
}

/** Overdamped spring, for things that should settle rather than stop. */
export function spring(t, damping = 6, freq = 12) {
  if (t <= 0) return 0
  return 1 - Math.exp(-damping * t) * Math.cos(freq * t)
}

// ---------------------------------------------------------------------------
// Apple-style motion vocabulary
// ---------------------------------------------------------------------------

/**
 * A CSS cubic-bezier, as a JS function. Solved with Newton-Raphson plus a
 * bisection fallback, so it is exact for the handful of curves we use and never
 * produces the NaN that a naive polynomial solve does at the flat ends.
 *
 * These curves are the whole difference between "an object moved" and "an object
 * arrived": a long decelerating tail reads as weight, a linear ramp reads as a
 * slideshow.
 */
export function cubicBezier(x1, y1, x2, y2) {
  const cx = 3 * x1
  const bx = 3 * (x2 - x1) - cx
  const ax = 1 - cx - bx
  const cy = 3 * y1
  const by = 3 * (y2 - y1) - cy
  const ay = 1 - cy - by

  const fx = (t) => ((ax * t + bx) * t + cx) * t
  const fy = (t) => ((ay * t + by) * t + cy) * t
  const dfx = (t) => (3 * ax * t + 2 * bx) * t + cx

  return (x) => {
    const target = Math.max(0, Math.min(1, x))
    if (target === 0 || target === 1) return target
    // Newton first: it converges in 2-3 steps for a well-formed curve.
    let t = target
    for (let i = 0; i < 6; i++) {
      const err = fx(t) - target
      if (Math.abs(err) < 1e-6) break
      const d = dfx(t)
      if (Math.abs(d) < 1e-6) break
      t -= err / d
    }
    // Bisection fallback if Newton wandered off.
    if (t < 0 || t > 1) {
      let lo = 0, hi = 1
      t = target
      for (let i = 0; i < 24; i++) {
        t = (lo + hi) / 2
        if (fx(t) < target) lo = t; else hi = t
      }
    }
    return fy(t)
  }
}

export const EASE = {
  /** Fast, confident start; long soft landing. The default for entrances. */
  out: cubicBezier(0.16, 1.0, 0.30, 1.0),
  /** Symmetric, calm. For camera moves and anything that must feel weightless. */
  inOut: cubicBezier(0.65, 0.0, 0.35, 1.0),
  /** Material-ish standard curve. For state changes that carry information. */
  standard: cubicBezier(0.40, 0.0, 0.20, 1.0),
  /** A gentle overshoot on the way out. For things that arrive with energy. */
  back: cubicBezier(0.34, 1.56, 0.64, 1.0),
  /** Accelerating. Only for exits. */
  in: cubicBezier(0.55, 0.0, 1.0, 0.45),
}

/** Eased 0 -> 1 ramp from `start` over `dur`. */
export function ramp(t, start, dur, ease = EASE.out) {
  if (dur === 0) return t >= start ? 1 : 0
  return ease(Math.max(0, Math.min(1, (t - start) / dur)))
}

/**
 * Arrive-and-settle: 0 at t<=0, then overshoots 1 and rings down to it.
 * Use for a device landing, a card dropping in, a button releasing.
 */
export function settle(t, { decay = 8.5, freq = 15 } = {}) {
  if (t <= 0) return 0
  return 1 - Math.exp(-decay * t) * Math.cos(freq * t)
}

/**
 * A decaying oscillation that starts at 0. Use for reaction beats: a flinch at
 * the interruption, a knock as something lands. Unlike a random shake it is
 * deterministic and has a definite end.
 */
export function impulse(t, { decay = 7, freq = 13 } = {}) {
  if (t <= 0) return 0
  return Math.exp(-decay * t) * Math.sin(freq * t)
}

/** Delayed visibility for a staggered list: the i-th of n items, `each` apart. */
export function stagger(i, each, from = 0) {
  return from + i * each
}
