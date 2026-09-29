// The four-layer visual vocabulary the storyboard asks for, shared by every shot.
//
//   页面层  the phone slab and whatever is on its screen (a real 2D canvas, so the
//           layout matches the Android app and can be compared against a screenshot)
//   抽象层  the accessibility tree: nodes and links drawn as billboards and lines
//   推理层  the vortex of cloud model + tool calls
//   约束层  gratings, gates, badges — the film's visual identity, because every other
//           AI demo films how strong the inference is and this one films where the
//           constraints bite
//
// Everything is a pure function of the time handed in: no clock reads, no state carried
// between frames, so offline extraction and live preview cannot diverge.

import * as THREE from 'three'
import { makeTextTexture } from './text.js'
import { NOISE } from './glsl.js'
import { smooth01 } from './ease.js'

export const INK_BG = 0x0e1418

/** Layer 1: anything the audience is meant to read. Rendered after post (see Stage). */
export const LAYER_UI = 1
export const BRAND = 0x1a7f6b
export const BRAND_DEEP = 0x12604f
export const ATTENTION = 0xc46a14
export const PROBLEM = 0xc0392b
export const GOOD = 0x1e8449
export const CARD = 0xffffff
export const TEXT_DIM = '#8FA3AD'

/** Device aspect, straight from the OnePlus this was built on: 1272 x 2800. */
export const DEVICE_ASPECT = 1272 / 2800

/** Half of native: enough for on-screen text to survive being filmed at 1080p. */
export const SCREEN_W = 636
export const SCREEN_H = 1400

/**
 * The phone: a body plate, the screen surface, and a rim light.
 *
 * A real mesh with real thickness was tried and rejected: at this camera distance the
 * rounded edge reads as a black smear, while a flat plate with a baked rounded outline and
 * a rim glow reads as a device and keeps the screen the brightest thing in frame.
 */
export function makePhone({ height = 2.0 } = {}) {
  const width = height * DEVICE_ASPECT
  const group = new THREE.Group()

  const bodyTex = makeTextTexture({ text: ' ', size: 8, padding: 2 })
  const canvas = document.createElement('canvas')
  canvas.width = 256
  canvas.height = 512
  const ctx = canvas.getContext('2d')
  roundRect(ctx, 6, 6, canvas.width - 12, canvas.height - 12, 34)
  ctx.fillStyle = '#0b1114'
  ctx.fill()
  ctx.lineWidth = 3
  ctx.strokeStyle = 'rgba(150,190,185,0.30)'
  ctx.stroke()
  const bodyMap = new THREE.CanvasTexture(canvas)
  bodyMap.colorSpace = THREE.SRGBColorSpace
  bodyTex.dispose()

  const bodyMat = new THREE.MeshBasicMaterial({ map: bodyMap, transparent: true, depthWrite: false })
  // UI plates are authored in sRGB and must reach the screen unchanged. ACES filmic tone
  // mapping (right for the glow layers, which should bloom into white) turned the phone's
  // white screen into a grey rectangle and the subtitles into faint smudges.
  bodyMat.toneMapped = false
  const body = new THREE.Mesh(new THREE.PlaneGeometry(width * 1.075, height * 1.03), bodyMat)
  group.add(body)

  // The screen: its own canvas, redrawn by whichever shot owns it.
  const screen = document.createElement('canvas')
  screen.width = SCREEN_W
  screen.height = SCREEN_H
  const screenCtx = screen.getContext('2d')
  const screenMap = new THREE.CanvasTexture(screen)
  screenMap.colorSpace = THREE.SRGBColorSpace
  screenMap.anisotropy = 8

  const surfaceMat = new THREE.MeshBasicMaterial({ map: screenMap, transparent: true, depthWrite: false })
  surfaceMat.toneMapped = false
  const surface = new THREE.Mesh(new THREE.PlaneGeometry(width, height), surfaceMat)
  // Layer 1: interface. See Stage.renderAt — the screen must not go through bloom.
  surface.layers.set(LAYER_UI)
  surface.position.z = 0.001
  group.add(surface)

  // Rim glow: the phone should sit in its own light rather than in the void.
  const rim = new THREE.Mesh(
    new THREE.PlaneGeometry(width * 2.4, height * 1.9),
    new THREE.MeshBasicMaterial({
      map: glowTexture(),
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
      color: BRAND,
      opacity: 0.20,
    }),
  )
  rim.position.z = -0.02
  group.add(rim)

  group.userData = {
    width,
    height,
    body,
    surface,
    rim,
    screenCtx,
    screenMap,
    /** Redraw the screen canvas and flag the texture; call from update(), not build(). */
    paint(draw) {
      draw(screenCtx, SCREEN_W, SCREEN_H)
      screenMap.needsUpdate = true
    },
  }
  return group
}

/** A soft radial glow, used for halos, rim light and hit flashes. */
export function glowTexture() {
  const size = 256
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = size
  const ctx = canvas.getContext('2d')
  const grd = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2)
  grd.addColorStop(0.0, 'rgba(255,255,255,0.95)')
  grd.addColorStop(0.35, 'rgba(255,255,255,0.35)')
  grd.addColorStop(1.0, 'rgba(255,255,255,0)')
  ctx.fillStyle = grd
  ctx.fillRect(0, 0, size, size)
  const tex = new THREE.CanvasTexture(canvas)
  tex.colorSpace = THREE.SRGBColorSpace
  return tex
}

/** A horizontal band: bright in the middle, gone at both ends. A horizon light, not a blob. */
export function bandTexture() {
  const w = 512
  const h = 64
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d')
  const grd = ctx.createLinearGradient(0, 0, w, 0)
  grd.addColorStop(0.0, 'rgba(255,255,255,0)')
  grd.addColorStop(0.35, 'rgba(255,255,255,0.55)')
  grd.addColorStop(0.5, 'rgba(255,255,255,1)')
  grd.addColorStop(0.65, 'rgba(255,255,255,0.55)')
  grd.addColorStop(1.0, 'rgba(255,255,255,0)')
  ctx.fillStyle = grd
  ctx.fillRect(0, 0, w, h)
  // Fade vertically too, so the band has no hard top or bottom edge.
  const vgrd = ctx.createLinearGradient(0, 0, 0, h)
  vgrd.addColorStop(0.0, 'rgba(0,0,0,1)')
  vgrd.addColorStop(0.5, 'rgba(0,0,0,0)')
  vgrd.addColorStop(1.0, 'rgba(0,0,0,1)')
  ctx.globalCompositeOperation = 'destination-out'
  ctx.fillStyle = vgrd
  ctx.fillRect(0, 0, w, h)
  const tex = new THREE.CanvasTexture(canvas)
  tex.colorSpace = THREE.SRGBColorSpace
  return tex
}

/** Deterministic RNG: two shots that must match across a cut have to agree on every number. */
export function mulberry32(seed) {
  let a = seed >>> 0
  return function () {
    a |= 0
    a = (a + 0x6D2B79F5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** The film's coil. Shot 1 and shot 2 must both use this exact formula, or the cut will show. */
export const COIL = { turns: 1.5, height: 3.6, r0: 0.50, r1: 1.65 }
export function coilPoint(u, out = new THREE.Vector3()) {
  const ang = u * Math.PI * 2 * COIL.turns
  const rad = COIL.r0 + u * (COIL.r1 - COIL.r0)
  return out.set(Math.cos(ang) * rad, (u - 0.5) * COIL.height, Math.sin(ang) * rad)
}

/** Points along a rounded rectangle's outline, in that rectangle's own plane. */
export function phoneOutlinePoints(n, { width, height, radius = 0.10 } = {}) {
  const pts = []
  const hw = width / 2
  const hh = height / 2
  const r = Math.min(radius, hw * 0.9, hh * 0.9)
  const straightV = 2 * (hh - r)
  const straightH = 2 * (hw - r)
  const arc = (Math.PI / 2) * r
  const total = 2 * straightV + 2 * straightH + 4 * arc
  for (let i = 0; i < n; i++) {
    let d = (i / n) * total
    let x = 0
    let y = 0
    if (d < straightV) { x = hw; y = -hh + r + d }
    else if ((d -= straightV) < arc) { const a = -Math.PI / 2 + (d / arc) * (Math.PI / 2); x = hw - r + Math.cos(a) * r; y = hh - r + Math.sin(a) * r }
    else if ((d -= arc) < straightH) { x = hw - r - d; y = hh }
    else if ((d -= straightH) < arc) { const a = (d / arc) * (Math.PI / 2); x = -hw + r + Math.cos(a) * r; y = hh - r + Math.sin(a) * r }
    else if ((d -= arc) < straightV) { x = -hw; y = hh - r - d }
    else if ((d -= straightV) < arc) { const a = Math.PI / 2 + (d / arc) * (Math.PI / 2); x = -hw + r + Math.cos(a) * r; y = -hh + r + Math.sin(a) * r }
    else if ((d -= arc) < straightH) { x = -hw + r + d; y = -hh }
    else { d -= straightH; const a = Math.PI + (d / arc) * (Math.PI / 2); x = hw - r + Math.cos(a) * r; y = -hh + r + Math.sin(a) * r }
    pts.push(new THREE.Vector3(x, y, 0))
  }
  return pts
}

/**
 * A camera move, as keys rather than as a slider.
 *
 * Every key is eased by default. A camera that interpolates linearly is the most common
 * reason an otherwise decent render reads as a slideshow.
 */
export function camKey(t, keys) {
  if (t <= keys[0].at) return { x: keys[0].x ?? 0, y: keys[0].y ?? 0, z: keys[0].z }
  for (let i = 0; i < keys.length - 1; i++) {
    const a = keys[i]
    const b = keys[i + 1]
    if (t >= a.at && t <= b.at) {
      const raw = (t - a.at) / Math.max(1e-6, b.at - a.at)
      const e = (a.ease ?? ((x) => x * x * (3 - 2 * x)))(raw)
      return {
        x: (a.x ?? 0) + ((b.x ?? 0) - (a.x ?? 0)) * e,
        y: (a.y ?? 0) + ((b.y ?? 0) - (a.y ?? 0)) * e,
        z: a.z + (b.z - a.z) * e,
      }
    }
  }
  const last = keys[keys.length - 1]
  return { x: last.x ?? 0, y: last.y ?? 0, z: last.z }
}

/**
 * A flat billboard carrying Chinese text.
 *
 * Sizing is LITERAL: `px` is the font size on a 1080-tall frame, and the plane is sized so
 * the glyphs actually measure that many pixels. Scaling by texture *width* instead (the
 * first attempt) made a "56px" subtitle render at 160px and swallow the phone behind it.
 *
 * The overlay camera is 2 units tall for the whole frame, so 1 px = 2/1080 units. For text
 * that lives in the 3D scene, pass `worldHeight` instead.
 */
export function makeLabel(text, { px = 40, color = '#FFFFFF', weight = 500, worldHeight = null, align = 'center' } = {}) {
  const tex = makeTextTexture({ text, size: px, weight, color, align })
  const labelMat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, depthWrite: false })
  labelMat.toneMapped = false
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(1, 1 / tex.userData.aspect), labelMat)
  mesh.layers.set(LAYER_UI)
  // userData.height is the plate height in CSS px (text + padding), so this maps the
  // measured plate onto the frame at exactly the requested scale.
  const heightUnits = worldHeight ?? (tex.userData.height * (2 / 1080))
  const widthUnits = heightUnits * tex.userData.aspect
  mesh.scale.set(widthUnits, heightUnits, 1)
  mesh.userData.baseScale = widthUnits
  mesh.userData.heightUnits = heightUnits
  return mesh
}

/** A white translucent card, the surface every claim and conclusion lands on. */
export function makeCard(width = 1.5, height = 0.62, opacity = 0.93) {
  const group = new THREE.Group()
  const canvas = document.createElement('canvas')
  canvas.width = 512
  canvas.height = Math.round(512 * (height / width))
  const ctx = canvas.getContext('2d')
  roundRect(ctx, 4, 4, canvas.width - 8, canvas.height - 8, 26)
  ctx.fillStyle = `rgba(255,255,255,${opacity})`
  ctx.fill()
  const map = new THREE.CanvasTexture(canvas)
  map.colorSpace = THREE.SRGBColorSpace
  const plateMat = new THREE.MeshBasicMaterial({ map, transparent: true, depthWrite: false })
  plateMat.toneMapped = false
  const plate = new THREE.Mesh(new THREE.PlaneGeometry(width, height), plateMat)
  plate.layers.set(LAYER_UI)
  group.add(plate)
  group.userData = { width, height, plate }
  return group
}

/**
 * Sound: rings that leave the screen.
 *
 * `phase` < 0 is the interrupted state — the ring collapses inward with reversed
 * phase and the colour steps brand -> attention, which is the whole point of shot 3.
 */
export function makeSoundRing({ radius = 0.5, width = 0.05, color = BRAND } = {}) {
  const mesh = new THREE.Mesh(
    new THREE.RingGeometry(radius - width, radius + width, 96),
    new THREE.MeshBasicMaterial({
      color,
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
      side: THREE.DoubleSide,
    }),
  )
  mesh.userData.ring = { radius, width }
  return mesh
}

/**
 * A subtitle plate on the orthographic overlay: 56px main, 30px secondary, centred, and
 * pinned below the subject so it never reads through the phone.
 */
export function makeSubtitle({ text, sub = null, y = -0.74 }) {
  const group = new THREE.Group()
  const main = makeLabel(text, { px: 56, weight: 600, color: '#FFFFFF' })
  group.add(main)
  group.userData.main = main
  if (sub) {
    const s = makeLabel(sub, { px: 30, weight: 400, color: TEXT_DIM })
    s.position.y = -0.125
    group.add(s)
    group.userData.sub = s
  }
  group.position.set(0, y, 0)
  return group
}

export function setGroupOpacity(group, value, skip = null) {
  group.traverse((o) => {
    if (!o.material || o === skip) return
    if (Array.isArray(o.material)) o.material.forEach((m) => { m.transparent = true; m.opacity = value })
    else { o.material.transparent = true; o.material.opacity = value }
  })
}

export function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}

/**
 * The phone's screen at rest: one status line, one big circle, one hint.
 *
 * Drawn to match the Android home screen (Ui.kt tokens, same wording), because the film's
 * claim is that these screens are the product, not an artist's impression of it.
 */
export function paintHomeScreen(ctx, w, h, {
  status = '我在',
  tone = '#1E8449',
  circle = '说给接线员听',
  circleColor = '#1A7F6B',
  hint = '点一下开始说话',
  pressed = 0,
} = {}) {
  ctx.clearRect(0, 0, w, h)
  ctx.fillStyle = '#F6F7F8'
  ctx.fillRect(0, 0, w, h)

  const pad = w * 0.075

  // status line
  ctx.beginPath()
  ctx.arc(pad + 9, h * 0.075, 9, 0, Math.PI * 2)
  ctx.fillStyle = tone
  ctx.fill()
  ctx.fillStyle = '#17202A'
  ctx.font = `600 ${Math.round(w * 0.085)}px "Noto Sans CJK SC", sans-serif`
  ctx.textBaseline = 'middle'
  ctx.fillText(status, pad + 28, h * 0.076)

  // the big circle
  const cx = w / 2
  const cy = h * 0.42
  const r = w * 0.30
  ctx.beginPath()
  ctx.arc(cx, cy, r, 0, Math.PI * 2)
  ctx.fillStyle = mixHex('#1A7F6B', '#C46A14', pressed)
  ctx.fill()

  ctx.fillStyle = 'rgba(255,255,255,0.97)'
  ctx.font = `500 ${Math.round(w * 0.115)}px "Noto Sans CJK SC", sans-serif`
  ctx.textAlign = 'center'
  ctx.fillText(circle, cx, cy + r * 0.02)
  ctx.font = `400 ${Math.round(w * 0.085)}px "Noto Sans CJK SC", sans-serif`
  ctx.fillText('🎤', cx, cy - r * 0.34)
  ctx.textAlign = 'left'

  // hint under the circle
  ctx.fillStyle = '#5D6D7E'
  ctx.font = `400 ${Math.round(w * 0.062)}px "Noto Sans CJK SC", sans-serif`
  ctx.textAlign = 'center'
  ctx.fillText(hint, cx, cy + r + h * 0.045)

  // the quiet row of safety buttons at the bottom.
  // Laid out by measured width, not by evenly spaced guesses: "请家人帮忙" and
  // "给家人打电话" are 5 and 6 glyphs, so equal spacing ran them into each other.
  ctx.fillStyle = '#5D6D7E'
  ctx.font = `400 ${Math.round(w * 0.046)}px "Noto Sans CJK SC", sans-serif`
  const labels = ['打字', '请家人帮忙', '给家人打电话']
  const slots = [0.17, 0.50, 0.83]
  labels.forEach((label, i) => {
    const x = w * slots[i]
    const half = ctx.measureText(label).width / 2
    // Nudge the outer two inward if they would leave the safe area.
    const clamped = Math.max(half + pad * 0.5, Math.min(w - half - pad * 0.5, x))
    ctx.fillText(label, clamped, h * 0.955)
  })
  ctx.textAlign = 'left'
}

export function mixHex(a, b, t) {
  const ca = hexToRgb(a)
  const cb = hexToRgb(b)
  const k = Math.max(0, Math.min(1, t))
  return `rgb(${Math.round(ca[0] + (cb[0] - ca[0]) * k)},${Math.round(ca[1] + (cb[1] - ca[1]) * k)},${Math.round(ca[2] + (cb[2] - ca[2]) * k)})`
}

function hexToRgb(hex) {
  const v = parseInt(hex.replace('#', ''), 16)
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255]
}

/** Camera distance that makes a plane of the given height fill `fill` of the frame. */
export function distanceFor(stage, height, fill = 0.8) {
  const fov = THREE.MathUtils.degToRad(stage.camera.fov)
  return (height / fill) / (2 * Math.tan(fov / 2))
}

/** Fade a shot's group in at its start and out at its end, without state. */
export function windowFade(local, duration, inDur = 0.5, outDur = 0.5) {
  return smooth01(local, 0, inDur) * (1 - smooth01(local, duration - outDur, outDur))
}

/**
 * The set: a space for the phone to exist in.
 *
 * Shots 2-3 first looked like a phone pasted on black — no floor, no haze, no light in the
 * air. Cinematographically the missing thing was not decoration: without a space there is
 * nothing for the camera move to be *against*, so a dolly reads as nothing happening.
 *
 * Everything here is additive or very dark, so it never competes with the screen.
 */
export function makeSet({ tint = BRAND, floorY = -1.28 } = {}) {
  const group = new THREE.Group()
  const params = new URLSearchParams(globalThis.location?.search || '')
  if (params.get('haze') === '0') return group

  // Haze: three planes at different depths. Parallax between them is what sells the depth.
  // A horizon band instead of haze blobs. Three round glows behind a near-black frame added up
  // to a grey wall — diagnosed by rendering the same frame with ?haze=0, which came out black.
  // A wide, short band separates the phone from the background without lifting the black level.
  const horizon = new THREE.Mesh(
    new THREE.PlaneGeometry(9.5, 1.5),
    new THREE.MeshBasicMaterial({
      map: bandTexture(), transparent: true, depthWrite: false,
      blending: THREE.AdditiveBlending, color: tint, opacity: 0.26,
    }),
  )
  horizon.position.set(0, -0.62, -2.4)
  group.add(horizon)

  const horizonWide = new THREE.Mesh(
    new THREE.PlaneGeometry(16, 0.5),
    new THREE.MeshBasicMaterial({
      map: bandTexture(), transparent: true, depthWrite: false,
      blending: THREE.AdditiveBlending, color: 0x2E5F7A, opacity: 0.13,
    }),
  )
  horizonWide.position.set(0, -1.02, -3.6)
  group.add(horizonWide)

  // Floor: a gradient that is darkest under the phone, so the eye goes to the screen.
  const floorCanvas = document.createElement('canvas')
  floorCanvas.width = 512
  floorCanvas.height = 256
  const fctx = floorCanvas.getContext('2d')
  // The far edge blends into the background rather than ending in a visible line: a hard
  // horizon at mid-frame reads as a wall, and there is no wall in this scene.
  const grd = fctx.createLinearGradient(0, 0, 0, 256)
  grd.addColorStop(0, 'rgba(11,16,19,1)')
  grd.addColorStop(0.35, 'rgba(14,21,25,1)')
  grd.addColorStop(0.75, 'rgba(10,15,18,1)')
  grd.addColorStop(1, 'rgba(8,11,14,1)')
  fctx.fillStyle = grd
  fctx.fillRect(0, 0, 512, 256)
  const floorMap = new THREE.CanvasTexture(floorCanvas)
  floorMap.colorSpace = THREE.SRGBColorSpace
  const floor = new THREE.Mesh(
    new THREE.PlaneGeometry(40, 26),
    new THREE.MeshBasicMaterial({ map: floorMap }),
  )
  floor.rotation.x = -Math.PI / 2
  floor.position.y = floorY
  group.add(floor)

  // Screen spill: the phone's light falling on the space in front of it. Without this the
  // device looks emissive in a vacuum, which is exactly how a mockup looks.
  const spill = new THREE.Mesh(
    new THREE.PlaneGeometry(3.4, 2.0),
    new THREE.MeshBasicMaterial({
      map: glowTexture(), transparent: true, depthWrite: false,
      blending: THREE.AdditiveBlending, color: tint, opacity: 0.04,
    }),
  )
  spill.position.set(0, floorY + 0.75, 0.9)
  group.add(spill)

  group.userData = { haze: [horizon, horizonWide], floor, spill }
  return group
}

/** A mirrored, dimmed copy below the floor: enough for the eye to read a surface. */
export function makeMirror(object, { floorY = -1.28, opacity = 0.16 } = {}) {
  const clone = object.clone(true)
  clone.traverse((o) => {
    if (!o.material) return
    // Clone the materials: sharing them would make the reflection's opacity edits change
    // the phone itself.
    const src = Array.isArray(o.material) ? o.material : [o.material]
    const copy = src.map((m) => {
      const c = m.clone()
      c.opacity = (m.opacity ?? 1) * opacity
      c.transparent = true
      c.depthWrite = false
      return c
    })
    o.material = Array.isArray(o.material) ? copy : copy[0]
  })
  clone.scale.y *= -1
  clone.position.y = 2 * floorY - object.position.y
  return clone
}

/**
 * Sample a text texture into points, so a title can come apart into sparks.
 *
 * The alternative — animating the plane's opacity — reads as a dissolve, which is a different
 * (and much cheaper-looking) idea. Breaking the glyphs themselves is what makes the title
 * *become* the next image instead of being replaced by it.
 */
export function sampleGlyphPoints(texture, { count = 4200, threshold = 0.55, seed = 7 } = {}) {
  const canvas = texture.image
  const ctx = canvas.getContext('2d')
  const { width, height } = canvas
  const data = ctx.getImageData(0, 0, width, height).data

  const hits = []
  const step = 2
  for (let y = 0; y < height; y += step) {
    for (let x = 0; x < width; x += step) {
      if (data[(y * width + x) * 4 + 3] / 255 > threshold) hits.push([x, y])
    }
  }

  const rnd = mulberry32(seed)
  const points = []
  for (let i = 0; i < count; i++) {
    const [sx, sy] = hits.length ? hits[Math.floor(rnd() * hits.length)] : [width / 2, height / 2]
    points.push([sx / width, sy / height])
  }
  return points
}

/**
 * A field of sparks that can be morphed between two shapes.
 *
 * Used for the two halves of the film's one true match cut: the title's glyphs collapse onto
 * the coil at the end of shot 1, and the same field (same seed, same formula) gathers the coil
 * into the phone's outline at the start of shot 2. Both halves are additive points with the
 * same noise curl, so the cut lands mid-motion and the eye reads it as one continuous move.
 */
export function makeSparks({ count, from, to, seed = 7, spread = 0.05, size = 3.4 } = {}) {
  const rnd = mulberry32(seed)
  const positions = new Float32Array(count * 3)
  const targets = new Float32Array(count * 3)
  const seeds = new Float32Array(count)
  for (let i = 0; i < count; i++) {
    const f = from(i, rnd)
    const g = to(i, rnd)
    positions[i * 3 + 0] = f[0] + (rnd() - 0.5) * spread
    positions[i * 3 + 1] = f[1] + (rnd() - 0.5) * spread
    positions[i * 3 + 2] = f[2] + (rnd() - 0.5) * spread
    targets[i * 3 + 0] = g[0] + (rnd() - 0.5) * spread * 0.7
    targets[i * 3 + 1] = g[1] + (rnd() - 0.5) * spread * 0.7
    targets[i * 3 + 2] = g[2] + (rnd() - 0.5) * spread * 0.7
    seeds[i] = rnd()
  }

  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geo.setAttribute('aTarget', new THREE.BufferAttribute(targets, 3))
  geo.setAttribute('aSeed', new THREE.BufferAttribute(seeds, 1))

  const mat = new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    uniforms: {
      uMorph: { value: 0 }, uOpacity: { value: 1 }, uTime: { value: 0 },
      uSize: { value: size },
      uBrand: { value: new THREE.Color(0x9FF3DC) },
      uDeep: { value: new THREE.Color(BRAND) },
    },
    vertexShader: /* glsl */`
      attribute vec3 aTarget;
      attribute float aSeed;
      uniform float uMorph, uTime, uSize;
      varying float vGlow;
      varying float vSeed;
      ${NOISE}
      void main() {
        // Per-particle stagger so the shape does not slide as one rigid block.
        float m = clamp((uMorph - aSeed * 0.25) / 0.75, 0.0, 1.0);
        float e = m * m * (3.0 - 2.0 * m);
        vec3 pos = mix(position, aTarget, e);
        float curl = (1.0 - e) * 0.22;
        pos += vec3(
          snoise(pos * 0.7 + uTime * 0.3 + aSeed * 9.0),
          snoise(pos * 0.7 + uTime * 0.3 + aSeed * 9.0 + 21.7),
          snoise(pos * 0.7 + uTime * 0.3 + aSeed * 9.0 + 51.3)
        ) * curl;
        vec4 mv = modelViewMatrix * vec4(pos, 1.0);
        gl_Position = projectionMatrix * mv;
        gl_PointSize = uSize * (1.0 + aSeed) * (6.0 / -mv.z);
        vGlow = mix(0.5, 1.0, smoothstep(-16.0, -4.0, mv.z));
        vSeed = aSeed;
      }
    `,
    fragmentShader: /* glsl */`
      uniform float uOpacity;
      uniform vec3 uBrand, uDeep;
      varying float vGlow;
      varying float vSeed;
      void main() {
        float d = length(gl_PointCoord - 0.5) * 2.0;
        float core = 1.0 - smoothstep(0.0, 1.0, d);
        float halo = pow(core, 3.0);
        vec3 col = mix(uDeep, uBrand, vSeed) * (0.55 + 1.3 * vGlow);
        col += vec3(0.35, 0.85, 0.75) * halo * 0.5;
        float alpha = (core * 0.5 + halo * 0.9) * vGlow * uOpacity;
        if (alpha < 0.002) discard;
        gl_FragColor = vec4(col, alpha);
      }
    `,
  })

  const points = new THREE.Points(geo, mat)
  points.userData = { mat, geo }
  return points
}
