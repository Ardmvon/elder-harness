// Easing. Shared because the smooth01 bug (see below) cost an hour and would
// cost it again in every shot that grew its own copy.

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
