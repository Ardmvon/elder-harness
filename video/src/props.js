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
import { smooth01 } from './ease.js'

export const INK_BG = 0x0e1418
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
