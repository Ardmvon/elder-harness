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
export function makePhone({ height = 2.0, corner = null } = {}) {
  const width = height * DEVICE_ASPECT
  const group = new THREE.Group()

  // ---- the slab: a real rounded-rect prism with a chamfered edge ----
  //
  // It used to be two flat planes (a body plate and a screen plate). At this camera distance
  // a flat plate can only ever be a picture of a phone: no thickness to catch a highlight, no
  // chamfer to draw a line down the side, nothing for the key light to do. So the body is an
  // extruded rounded rectangle with a 0.6 mm bevel, in brushed dark metal.
  const T = 0.052                     // body thickness, world units
  const bevel = 0.006
  const r = corner ?? width * 0.150   // corner radius
  const outline = roundedRectShape(width - bevel * 2, height - bevel * 2, Math.max(0.02, r - bevel))
  const frameGeo = new THREE.ExtrudeGeometry(outline, {
    depth: Math.max(0.004, T - bevel * 2),
    bevelEnabled: true,
    bevelThickness: bevel,
    bevelSize: bevel,
    bevelSegments: 2,
    curveSegments: 24,
  })
  frameGeo.center()
  const frameMat = new THREE.MeshStandardMaterial({
    color: 0x2C3237, metalness: 0.92, roughness: 0.30,
  })
  frameMat.transparent = true
  const frame = new THREE.Mesh(frameGeo, frameMat)
  group.add(frame)

  // ---- the glass: a rounded-rect face, glossy, with a clearcoat ----
  const glassGeo = new THREE.ShapeGeometry(
    roundedRectShape(width - 0.016, height - 0.016, Math.max(0.02, r - 0.008)), 24)
  const glassMat = new THREE.MeshPhysicalMaterial({
    color: 0x0A0E12, metalness: 0.0, roughness: 0.045,
    clearcoat: 1.0, clearcoatRoughness: 0.03,
  })
  glassMat.transparent = true
  const glass = new THREE.Mesh(glassGeo, glassMat)
  glass.position.z = T / 2 + 0.0008
  group.add(glass)

  // ---- the screen: the baked UI canvas, on the interface layer ----
  const screen = document.createElement('canvas')
  screen.width = SCREEN_W
  screen.height = SCREEN_H
  const screenCtx = screen.getContext('2d')
  const screenMap = new THREE.CanvasTexture(screen)
  screenMap.colorSpace = THREE.SRGBColorSpace
  screenMap.anisotropy = 8

  const surfaceMat = new THREE.MeshBasicMaterial({ map: screenMap, transparent: true, depthWrite: false })
  surfaceMat.toneMapped = false
  const screenW = width * 0.955
  const screenH = height * 0.972
  const surface = new THREE.Mesh(new THREE.PlaneGeometry(screenW, screenH), surfaceMat)
  surface.layers.set(LAYER_UI)   // interface: after post, unbloomed, exact colours
  surface.position.z = T / 2 + 0.0022
  group.add(surface)

  // ---- the glass reflection: additive, on the interface layer, drawn over the screen ----
  //
  // It was on the world layer and therefore *behind* the screen, which is drawn after post —
  // so the reflection never appeared. A phone screen with no reflection on it is the single
  // biggest reason the device read as a sticker.
  const sheenCanvas = document.createElement('canvas')
  sheenCanvas.width = 64
  sheenCanvas.height = 128
  const sctx = sheenCanvas.getContext('2d')
  const sgrd = sctx.createLinearGradient(0, 0, 64, 128)
  sgrd.addColorStop(0.00, 'rgba(255,255,255,0.20)')
  sgrd.addColorStop(0.30, 'rgba(255,255,255,0.05)')
  sgrd.addColorStop(0.55, 'rgba(255,255,255,0.00)')
  sgrd.addColorStop(0.80, 'rgba(255,255,255,0.035)')
  sgrd.addColorStop(1.00, 'rgba(255,255,255,0.10)')
  sctx.fillStyle = sgrd
  sctx.fillRect(0, 0, 64, 128)
  const sheenMap = new THREE.CanvasTexture(sheenCanvas)
  sheenMap.colorSpace = THREE.SRGBColorSpace
  const sheenMat = new THREE.MeshBasicMaterial({
    map: sheenMap, transparent: true, depthWrite: false,
    blending: THREE.AdditiveBlending, opacity: 0.55,
  })
  sheenMat.toneMapped = false
  const sheen = new THREE.Mesh(new THREE.PlaneGeometry(screenW, screenH), sheenMat)
  sheen.layers.set(LAYER_UI)
  sheen.renderOrder = 4
  sheen.position.z = T / 2 + 0.0030
  group.add(sheen)

  // ---- punch-hole camera, and the side buttons ----
  const punchMat = new THREE.MeshBasicMaterial({ color: 0x05070A })
  punchMat.toneMapped = false
  const punch = new THREE.Mesh(new THREE.CircleGeometry(0.017, 24), punchMat)
  punch.layers.set(LAYER_UI)
  punch.renderOrder = 5
  punch.position.set(0, height * 0.452, T / 2 + 0.0034)
  group.add(punch)

  const buttonMat = new THREE.MeshStandardMaterial({ color: 0x3A4147, metalness: 0.95, roughness: 0.25 })
  buttonMat.transparent = true
  const buttons = new THREE.Group()
  for (const [y, h] of [[0.42, 0.055], [0.30, 0.11]]) {
    const b = new THREE.Mesh(new THREE.BoxGeometry(0.007, h * height * 0.5, 0.020), buttonMat)
    b.position.set(width / 2 + 0.0022, y * height * 0.5, 0)
    buttons.add(b)
  }
  group.add(buttons)

  // ---- rim light: a tight glow behind the device ----
  const rim = new THREE.Mesh(
    new THREE.PlaneGeometry(width * 1.45, height * 1.22),
    new THREE.MeshBasicMaterial({
      map: glowTexture(), transparent: true, depthWrite: false,
      blending: THREE.AdditiveBlending, color: BRAND, opacity: 0.07,
    }),
  )
  rim.position.z = -T / 2 - 0.006
  group.add(rim)

  group.userData = {
    width,
    height,
    body: frame,
    glass,
    surface,
    sheen,
    rim,
    punch,
    buttons,
    screenCtx,
    screenMap,
    /** Fade the physical device (frame, glass, buttons, punch-hole) as one thing. */
    setFrameOpacity(k) {
      frameMat.opacity = k
      glassMat.opacity = k
      buttonMat.opacity = k
      punchMat.opacity = k
    },
    /**
     * Redraw the screen canvas and flag the texture; call from update(), not build().
     *
     * The clip is the important part: painting the UI into a square-cornered rectangle put a
     * rectangular image inside a rounded phone. Every paint is clipped to the device's own
     * corner radius, so the two can never disagree.
     */
    paint(draw) {
      screenCtx.clearRect(0, 0, SCREEN_W, SCREEN_H)
      screenCtx.save()
      roundRect(screenCtx, 0, 0, SCREEN_W, SCREEN_H, SCREEN_W * (r / (width * 0.955)))
      screenCtx.clip()
      draw(screenCtx, SCREEN_W, SCREEN_H)
      screenCtx.restore()
      screenMap.needsUpdate = true
    },
  }
  return group
}

/** A rounded-rectangle THREE.Shape, centred on the origin, in the XY plane. */
export function roundedRectShape(w, h, r) {
  const s = new THREE.Shape()
  const x = -w / 2
  const y = -h / 2
  const rr = Math.min(r, w / 2, h / 2)
  s.moveTo(x + rr, y)
  s.lineTo(x + w - rr, y)
  s.quadraticCurveTo(x + w, y, x + w, y + rr)
  s.lineTo(x + w, y + h - rr)
  s.quadraticCurveTo(x + w, y + h, x + w - rr, y + h)
  s.lineTo(x + rr, y + h)
  s.quadraticCurveTo(x, y + h, x, y + h - rr)
  s.lineTo(x, y + rr)
  s.quadraticCurveTo(x, y, x + rr, y)
  return s
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

/**
 * A technical grid: thin lines on transparent, with a radial fade so it never shows an edge.
 * Used for the back wall and the ground so the set has structure instead of being a void.
 */
export function gridTexture({ size = 512, cells = 16, color = '150,190,205', line = 1, vignette = 0.75 } = {}) {
  const c = document.createElement('canvas')
  c.width = c.height = size
  const ctx = c.getContext('2d')
  const step = size / cells
  ctx.strokeStyle = `rgba(${color},0.85)`
  ctx.lineWidth = line
  ctx.beginPath()
  for (let i = 0; i <= cells; i++) {
    const p = Math.round(i * step) + 0.5
    ctx.moveTo(p, 0); ctx.lineTo(p, size)
    ctx.moveTo(0, p); ctx.lineTo(size, p)
  }
  ctx.stroke()
  // every 4th line a little brighter, so the grid has a rhythm instead of being uniform
  ctx.strokeStyle = `rgba(${color},1.0)`
  ctx.beginPath()
  for (let i = 0; i <= cells; i += 4) {
    const p = Math.round(i * step) + 0.5
    ctx.moveTo(p, 0); ctx.lineTo(p, size)
    ctx.moveTo(0, p); ctx.lineTo(size, p)
  }
  ctx.stroke()
  const g = ctx.createRadialGradient(size / 2, size / 2, size * 0.05, size / 2, size / 2, size * 0.5)
  g.addColorStop(0, 'rgba(0,0,0,0)')
  g.addColorStop(vignette, 'rgba(0,0,0,0.55)')
  g.addColorStop(1, 'rgba(0,0,0,1)')
  ctx.globalCompositeOperation = 'destination-out'
  ctx.fillStyle = g
  ctx.fillRect(0, 0, size, size)
  const tex = new THREE.CanvasTexture(c)
  tex.colorSpace = THREE.SRGBColorSpace
  tex.wrapS = tex.wrapT = THREE.ClampToEdgeWrapping
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
export function makeLabel(text, { px = 40, color = '#FFFFFF', weight = 500, worldHeight = null, align = 'center', layer = LAYER_UI, family = undefined, italic = false, letterSpacing = 0, shadowColor = null, shadowBlur = 0 } = {}) {
  const tex = makeTextTexture({ text, size: px, weight, color, align, family, italic, letterSpacing, shadowColor, shadowBlur })
  const labelMat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, depthWrite: false })
  labelMat.toneMapped = false
  // The plate carries its world size in the geometry, and the mesh is never rescaled after
  // this. The previous version built a PlaneGeometry(1, 1/aspect) — which already has the
  // texture's aspect — and then scaled it by (widthUnits, heightUnits): a non-uniform scale
  // that divided the height by the aspect a second time. Every label in the film was
  // squashed vertically (the Chinese subtitle measured 16 px tall instead of 42).
  const heightUnits = worldHeight ?? (tex.userData.height * (2 / 1080))
  const widthUnits = heightUnits * tex.userData.aspect
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(widthUnits, heightUnits), labelMat)
  // Overlay subtitles ask for layer 0 (the orthographic camera's own layer); in-scene
  // labels stay on the UI layer so they render after post, crisp and unbloomed.
  mesh.layers.set(layer)
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
  // Layer 0: these live on the overlay scene, whose camera sees that layer. (They were
  // authored on LAYER_UI, which the overlay camera never looks at.)
  const main = makeLabel(text, { px: 56, weight: 600, color: '#FFFFFF', layer: 0 })
  group.add(main)
  group.userData.main = main
  if (sub) {
    const s = makeLabel(sub, { px: 30, weight: 400, color: TEXT_DIM, layer: 0 })
    // The secondary line sits a fixed 0.125 units below the main one; the shots animate the
    // group, never this offset, so the two lines can never collide.
    s.position.y = -0.125
    group.add(s)
    group.userData.sub = s
  }
  group.position.set(0, y, 0)
  group.userData.baseY = y
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
  hint = '点一下开始说话',
  pressed = 0,
  depth = null,
  pulse = 0,
  wake = 1,
} = {}) {
  // `wake` is the screen turning on: elements arrive in reading order, not as one flash.
  const at = (start, dur) => smooth01(wake, start, dur)

  ctx.clearRect(0, 0, w, h)

  // A screen is never one value. A top-lit gradient plus a slight edge falloff is what
  // stops the panel reading as a sheet of paper.
  const face = ctx.createLinearGradient(0, 0, 0, h)
  face.addColorStop(0.00, '#FBFCFD')
  face.addColorStop(0.45, '#F5F7F9')
  face.addColorStop(1.00, '#E9EDF1')
  ctx.fillStyle = face
  ctx.fillRect(0, 0, w, h)
  const edge = ctx.createRadialGradient(w / 2, h * 0.42, w * 0.25, w / 2, h * 0.42, w * 1.05)
  edge.addColorStop(0, 'rgba(0,0,0,0)')
  edge.addColorStop(1, 'rgba(18,28,33,0.06)')
  ctx.fillStyle = edge
  ctx.fillRect(0, 0, w, h)

  const pad = w * 0.075
  const cx = w / 2
  const cy = h * 0.42
  const r = w * 0.29
  const d = depth ?? Math.max(0, pressed)
  const rr = r * (1 - 0.055 * Math.max(-0.22, d))

  // ---- status line: the smallest element arrives first.
  ctx.save()
  ctx.globalAlpha = at(0.00, 0.30)
  ctx.beginPath()
  ctx.arc(pad + 9, h * 0.075, 9, 0, Math.PI * 2)
  ctx.fillStyle = tone
  ctx.fill()
  ctx.fillStyle = '#17202A'
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  ctx.font = `600 ${Math.round(w * 0.082)}px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif`
  ctx.fillText(status, pad + 28, h * 0.076)
  ctx.restore()

  // ---- the one thing to press.
  ctx.save()
  ctx.globalAlpha = at(0.12, 0.42)

  // A listening halo that breathes; slower than a UI blink, so it reads as calm attention.
  if (pulse > 0) {
    ctx.beginPath()
    ctx.arc(cx, cy, rr * (1.09 + 0.13 * pulse), 0, Math.PI * 2)
    ctx.fillStyle = `rgba(196,106,20,${0.07 + 0.13 * pulse})`
    ctx.fill()
  }

  // Contact shadow first, so the disc sits on the glass instead of floating above it.
  ctx.save()
  ctx.shadowColor = 'rgba(12,28,24,0.30)'
  ctx.shadowBlur = r * 0.42
  ctx.shadowOffsetY = r * 0.13
  const g = ctx.createRadialGradient(cx - rr * 0.34, cy - rr * 0.48, rr * 0.05, cx, cy, rr * 1.30)
  g.addColorStop(0.00, mixHex('#3FC0A2', '#F0A047', pressed))
  g.addColorStop(0.48, mixHex('#1A7F6B', '#C46A14', pressed))
  g.addColorStop(1.00, mixHex('#0E5849', '#9C520C', pressed))
  ctx.beginPath()
  ctx.arc(cx, cy, rr, 0, Math.PI * 2)
  ctx.fillStyle = g
  ctx.fill()
  ctx.restore()

  // Specular sheen across the top third: the single cue that says "glass".
  ctx.beginPath()
  ctx.ellipse(cx, cy - rr * 0.44, rr * 0.74, rr * 0.30, 0, 0, Math.PI * 2)
  ctx.fillStyle = 'rgba(255,255,255,0.13)'
  ctx.fill()

  drawMic(ctx, cx, cy - rr * 0.33, r * 0.40, 'rgba(255,255,255,0.97)')

  // The caption is the longest string on the screen ("说给接线员听"), so it is fitted to
  // the disc instead of overflowing it. The real app wraps it at 21sp; on film we want the
  // largest size that still fits, because the phone is the smallest type in the frame.
  ctx.fillStyle = 'rgba(255,255,255,0.98)'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  const capPx = fitFont(ctx, circle, rr * 1.52, w * 0.098, 500)
  ctx.font = `500 ${capPx}px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif`
  ctx.fillText(circle, cx, cy + rr * 0.34)
  ctx.restore()

  // ---- the hint under the button.
  ctx.save()
  ctx.globalAlpha = at(0.44, 0.40)
  ctx.fillStyle = '#5D6D7E'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.font = `400 ${Math.round(w * 0.058)}px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif`
  ctx.fillText(hint, cx, cy + rr + h * 0.050)
  ctx.restore()

  // ---- the quiet row of safety affordances at the bottom.
  // Laid out by measured width: "请家人帮忙" and "给家人打电话" are 5 and 6 glyphs, so
  // equal slots ran them into each other.
  ctx.save()
  ctx.globalAlpha = at(0.56, 0.40)
  ctx.fillStyle = '#7C8B98'
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  ctx.font = `400 ${Math.round(w * 0.046)}px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif`
  const labels = ['打字', '请家人帮忙', '给家人打电话']
  const slots = [0.17, 0.50, 0.83]
  labels.forEach((label, i) => {
    const x = w * slots[i]
    const half = ctx.measureText(label).width / 2
    const clamped = Math.max(half + pad * 0.5, Math.min(w - half - pad * 0.5, x))
    ctx.fillText(label, clamped, h * 0.955)
  })
  ctx.restore()
}

/** Largest font size (up to `startPx`) at which `text` fits `maxWidth`. */
function fitFont(ctx, text, maxWidth, startPx, weight = 500) {
  let px = startPx
  const family = '"Noto Sans CJK SC", "Microsoft YaHei", sans-serif'
  while (px > 12) {
    ctx.font = `${weight} ${Math.round(px)}px ${family}`
    if (ctx.measureText(text).width <= maxWidth) break
    px *= 0.95
  }
  return Math.round(px)
}

/** A minimal microphone glyph, drawn as paths so it never depends on an emoji font. */
function drawMic(ctx, cx, cy, s, color) {
  ctx.save()
  ctx.strokeStyle = color
  ctx.fillStyle = color
  ctx.lineCap = 'round'
  ctx.lineWidth = s * 0.15
  roundRect(ctx, cx - s * 0.24, cy - s * 0.58, s * 0.48, s * 0.80, s * 0.24)
  ctx.fill()
  ctx.beginPath()
  ctx.arc(cx, cy - s * 0.06, s * 0.46, Math.PI * 0.12, Math.PI * 0.88)
  ctx.stroke()
  ctx.beginPath()
  ctx.moveTo(cx, cy + s * 0.44)
  ctx.lineTo(cx, cy + s * 0.74)
  ctx.stroke()
  ctx.restore()
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
      blending: THREE.AdditiveBlending, color: tint, opacity: 0.17,
    }),
  )
  horizon.renderOrder = -8
  horizon.position.set(0, -0.80, -2.4)
  group.add(horizon)

  const horizonWide = new THREE.Mesh(
    new THREE.PlaneGeometry(16, 0.5),
    new THREE.MeshBasicMaterial({
      map: bandTexture(), transparent: true, depthWrite: false,
      blending: THREE.AdditiveBlending, color: 0x2E5F7A, opacity: 0.09,
    }),
  )
  horizonWide.renderOrder = -8
  horizonWide.position.set(0, -1.06, -3.6)
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
  // The floor is transparent + a negative renderOrder so it is guaranteed to be drawn
  // before any subject. As an ordinary opaque mesh it came *after* the additive particle
  // fields in three's render order and painted over them: the lower half of shot 2's
  // spark field was hidden at the cut. (Diagnosed by toggling colorWrite — the pixels came
  // straight back, which ruled out depth culling.)
  const floorMat = new THREE.MeshBasicMaterial({ map: floorMap, blending: THREE.AdditiveBlending })
  floorMat.transparent = true
  floorMat.depthWrite = false
  // Additive on purpose. Every other combination was tried: as an opaque (or merely
  // transparent) surface the floor painted over the additive particle field behind it —
  // shot 2's hand-off lost the lower half of its sparks (1.83M -> 1.04M bright pixels at
  // the cut). Additive geometry cannot hide anything, and the floor's gradient is a near
  // black wash anyway, so it reads the same.
  floorMat.depthTest = false
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(40, 26), floorMat)
  floor.renderOrder = -10
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
  spill.renderOrder = -7
  spill.position.set(0, floorY + 0.75, 0.9)
  group.add(spill)

  // Dust. This is the single cheapest thing that makes an empty frame read as a *place*:
  // a few hundred motes catching the light, drifting slowly. Shot 1 has 36k particles in flight;
  // shots 2-3 had nothing moving except the subject, which is why they felt like mockups.
  const DUST = 1500
  const dpos = new Float32Array(DUST * 3)
  const dseed = new Float32Array(DUST)
  const rnd = mulberry32(4242)
  for (let i = 0; i < DUST; i++) {
    dpos[i * 3 + 0] = (rnd() - 0.5) * 7.0
    dpos[i * 3 + 1] = -1.2 + rnd() * 3.4
    dpos[i * 3 + 2] = -3.6 + rnd() * 5.4
    dseed[i] = rnd()
  }
  const dgeo = new THREE.BufferGeometry()
  dgeo.setAttribute('position', new THREE.BufferAttribute(dpos, 3))
  dgeo.setAttribute('aSeed', new THREE.BufferAttribute(dseed, 1))
  const dust = new THREE.Points(dgeo, new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    uniforms: { uTime: { value: 0 }, uColor: { value: new THREE.Color(0x9FD8C8) } },
    vertexShader: /* glsl */`
      attribute float aSeed;
      uniform float uTime;
      varying float vFade;
      void main() {
        vec3 p = position;
        // Slow, incommensurate drifts: never repeats visibly, never needs a random number.
        p.x += 0.22 * sin(uTime * 0.16 + aSeed * 31.0);
        p.y += 0.30 * sin(uTime * 0.11 + aSeed * 17.0);
        p.z += 0.20 * sin(uTime * 0.09 + aSeed * 47.0);
        vec4 mv = modelViewMatrix * vec4(p, 1.0);
        gl_Position = projectionMatrix * mv;
        gl_PointSize = (1.0 + aSeed * 1.6) * (5.0 / -mv.z);
        vFade = 0.35 + 0.65 * aSeed;
      }
    `,
    fragmentShader: /* glsl */`
      uniform vec3 uColor;
      varying float vFade;
      void main() {
        float d = length(gl_PointCoord - 0.5) * 2.0;
        float a = (1.0 - smoothstep(0.0, 1.0, d)) * 0.5 * vFade;
        if (a < 0.004) discard;
        gl_FragColor = vec4(uColor, a);
      }
    `,
  }))
  group.add(dust)

  // Back wall: a faint technical grid far behind everything. It is what stops the frame
  // reading as "an object floating in a void" — there is now a room, and the phone sits in it.
  const wall = new THREE.Mesh(
    new THREE.PlaneGeometry(26, 15),
    new THREE.MeshBasicMaterial({
      map: gridTexture({ cells: 18, color: '150,195,215', line: 2, vignette: 0.62 }),
      transparent: true, depthWrite: false,
      blending: THREE.AdditiveBlending, color: 0x3C5F73, opacity: 0.85,
    }),
  )
  wall.position.set(0, 0.55, -7.4)
  wall.renderOrder = -12
  group.add(wall)

  // A second, closer wall layer, dimmer and larger: two grids at different depths give the
  // camera move somewhere to move *through*.
  const wallNear = new THREE.Mesh(
    new THREE.PlaneGeometry(20, 12),
    new THREE.MeshBasicMaterial({
      map: gridTexture({ cells: 9, color: '130,180,200', line: 2, vignette: 0.7 }),
      transparent: true, depthWrite: false,
      blending: THREE.AdditiveBlending, color: 0x2A4A5A, opacity: 0.55,
    }),
  )
  wallNear.position.set(0, 0.25, -5.2)
  wallNear.renderOrder = -11
  group.add(wallNear)

  // Ground grid: the same idea laid flat, fading with distance. It gives the floor a scale.
  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(44, 30),
    new THREE.MeshBasicMaterial({
      map: gridTexture({ cells: 22, color: '120,170,195', line: 2, vignette: 0.5 }),
      transparent: true, depthWrite: false,
      blending: THREE.AdditiveBlending, color: 0x2E5566, opacity: 0.42,
    }),
  )
  ground.rotation.x = -Math.PI / 2
  ground.position.set(0, floorY + 0.004, 0)
  ground.renderOrder = -8
  group.add(ground)

  // Contact shadow: without it the phone floats. A soft dark ellipse on the floor is enough.
  const shadowCanvas = document.createElement('canvas')
  shadowCanvas.width = shadowCanvas.height = 128
  const shctx = shadowCanvas.getContext('2d')
  const shgrd = shctx.createRadialGradient(64, 64, 2, 64, 64, 62)
  shgrd.addColorStop(0, 'rgba(0,0,0,0.75)')
  shgrd.addColorStop(0.55, 'rgba(0,0,0,0.32)')
  shgrd.addColorStop(1, 'rgba(0,0,0,0)')
  shctx.fillStyle = shgrd
  shctx.fillRect(0, 0, 128, 128)
  const shadowMap = new THREE.CanvasTexture(shadowCanvas)
  const shadow = new THREE.Mesh(
    new THREE.PlaneGeometry(2.0, 1.0),
    new THREE.MeshBasicMaterial({ map: shadowMap, transparent: true, depthWrite: false }),
  )
  shadow.renderOrder = -9
  shadow.rotation.x = -Math.PI / 2
  shadow.position.set(0, floorY + 0.002, 0.06)
  group.add(shadow)

  group.userData = {
    haze: [horizon, horizonWide], floor, spill, dust, shadow,
    /** Animate the environment. Every shot calls this: nothing in the frame may be static. */
    update(t) {
      dust.material.uniforms.uTime.value = t
      // The light in the air breathes, very slowly.
      const breathe = 0.92 + 0.08 * Math.sin(t * 0.43)
      horizon.material.opacity = 0.17 * breathe
      horizonWide.material.opacity = 0.09 * breathe
      spill.material.opacity = 0.04 * breathe
    },
  }
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
export function makeSparks({
  count, from, to, seed = 7, spread = 0.05, size = 3.4,
  /** Endpoint colours. Two shots that hand a field to each other must pass the same pair. */
  brand = 0x9FF3DC, deep = BRAND,
} = {}) {
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
      uMorph: { value: 0 }, uDisperse: { value: 0 }, uOpacity: { value: 1 }, uTime: { value: 0 },
      uSize: { value: size },
      uBrand: { value: new THREE.Color(brand) },
      uDeep: { value: new THREE.Color(deep) },
    },
    vertexShader: /* glsl */`
      attribute vec3 aTarget;
      attribute float aSeed;
      uniform float uMorph, uDisperse, uTime, uSize;
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
        // Third stage: once the shape is formed the sparks must leave it. Holding them on the
        // outline left a string of bright beads around the phone — a match cut that never ended.
        vec3 dir = normalize(vec3(
          snoise(vec3(aSeed * 7.0)),
          snoise(vec3(aSeed * 13.0 + 3.0)) + 0.35,
          snoise(vec3(aSeed * 19.0 + 7.0))
        ) + 0.0001);
        pos += dir * uDisperse * (0.30 + aSeed * 0.9);

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


/**
 * A frosted pill: the shape the assistant's step narration arrives in.
 *
 * Background, border, dot and baseline are baked into one canvas, so the corner radius,
 * the light edge and the text can never drift apart. Three separate meshes is exactly how
 * a clean interface starts to look handmade.
 */
export function makePill(text, {
  px = 40, color = '#EAF6F2', weight = 500,
  bg = 'rgba(17,26,31,0.72)', border = 'rgba(127,227,200,0.28)',
  dotColor = null, worldHeight = null, layer = LAYER_UI,
} = {}) {
  const SS = 2
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')
  const font = `${weight} ${px * SS}px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif`
  ctx.font = font
  const tw = ctx.measureText(text).width
  const padX = px * SS * 0.60
  const padY = px * SS * 0.44
  const dotW = dotColor ? px * SS * 1.15 : 0
  canvas.width = Math.ceil(tw + padX * 2 + dotW)
  canvas.height = Math.ceil(px * SS + padY * 2)

  roundRect(ctx, 1.5, 1.5, canvas.width - 3, canvas.height - 3, (canvas.height - 3) / 2)
  ctx.fillStyle = bg
  ctx.fill()
  ctx.lineWidth = 2
  ctx.strokeStyle = border
  ctx.stroke()

  ctx.font = font
  ctx.fillStyle = color
  ctx.textBaseline = 'middle'
  ctx.textAlign = 'left'
  let tx = padX
  if (dotColor) {
    ctx.beginPath()
    ctx.arc(padX + px * SS * 0.16, canvas.height / 2, px * SS * 0.19, 0, Math.PI * 2)
    ctx.fillStyle = dotColor
    ctx.fill()
    tx = padX + dotW * 0.92
  }
  ctx.fillText(text, tx, canvas.height / 2 + px * SS * 0.03)

  const tex = new THREE.CanvasTexture(canvas)
  tex.colorSpace = THREE.SRGBColorSpace
  tex.anisotropy = 8
  const mat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, depthWrite: false })
  mat.toneMapped = false
  const aspect = canvas.width / canvas.height
  const heightUnits = worldHeight ?? (canvas.height / SS) * (2 / 1080)
  const widthUnits = heightUnits * aspect
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(widthUnits, heightUnits), mat)
  mesh.layers.set(layer)
  mesh.userData = { widthUnits, heightUnits, aspect, baseY: 0, baseX: 0 }
  return mesh
}
