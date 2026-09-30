// The presentation layer: the things the film says in type, drawn on the overlay
// camera above everything. Modelled on the reference explainer's grammar —
//
//   * a chapter heading (numeral + Chinese + letterspaced English) that slides in
//     at the top-left and withdraws after a few seconds;
//   * bilingual subtitles at the foot (Chinese bold, English italic under it) over a
//     soft dark gradient, never over the picture's own brightness;
//   * letterbox bars that close the film at the head and tail;
//   * global HITS: a hit table that flashes the frame and kicks the camera, so big
//     changes land on the edit's beats rather than drifting in.
//
// All of it is driven by one time value, so the subtitle at 2:41 is the same frame
// on every machine. Nothing here is per-shot: the shots only declare the data.

import * as THREE from 'three'
import { makeLabel } from './props.js'
import { smooth01 } from './ease.js'

const PX = 2 / 1080
const SERIF = '"Noto Serif CJK SC", "Noto Serif", serif'
const SERIF_IT = '"Liberation Serif", "Noto Serif", serif'
const GOLD = '#E2BD6B'
const GOLD_DIM = '#CDB88E'

/** Window with fades at both ends, like the reference film's `win()`. */
function win(x, a, b, fadeIn = 0.5, fadeOut = 0.5) {
  return smooth01(x, a, fadeIn) * (1 - smooth01(x, b - fadeOut, fadeOut))
}

export class Presentation {
  constructor(stage) {
    this.stage = stage
    this.film = null
    this.root = new THREE.Group()
    stage.overlay.add(this.root)

    const hw = stage.overlayHalfW
    const hh = stage.overlayHalfH

    // Letterbox: two bars that close the head and tail of the film.
    this.bars = [1, -1].map((sign) => {
      const bar = new THREE.Mesh(
        new THREE.PlaneGeometry(hw * 2, 1),
        new THREE.MeshBasicMaterial({ color: 0x000000, transparent: true, depthWrite: false }),
      )
      bar.material.toneMapped = false
      // Explicit draw order: the bars are the frame, so they go behind the type. Same-z
      // transparent meshes have no reliable order, and the tail bars were covering the
      // last subtitle.
      bar.renderOrder = -3
      bar.position.set(0, sign * hh, -0.5)
      bar.scale.y = 0
      bar.visible = false
      this.root.add(bar)
      return bar
    })

    // The hit flash: one additive plate over the whole frame.
    this.flash = new THREE.Mesh(
      new THREE.PlaneGeometry(hw * 2, hh * 2),
      new THREE.MeshBasicMaterial({
        color: 0xFFF8EB, transparent: true, opacity: 0, depthWrite: false,
        blending: THREE.AdditiveBlending,
      }),
    )
    this.flash.material.toneMapped = false
    this.flash.renderOrder = 3
    this.flash.position.z = 0.5
    this.root.add(this.flash)

    this.chapters = []
    this.subs = []
    this.flashAmount = 0
    this.shake = [0, 0]
  }

  /** Install the edit's text and hit data. Called once, after the timeline is known. */
  setFilm(film) {
    this.film = film
    for (const g of [...this.chapters.map((c) => c.group), ...this.subs.map((s) => s.group)]) {
      this.root.remove(g)
    }
    const hw = this.stage.overlayHalfW
    this.chapters = (film.chapters ?? []).map((c) => ({ ...c, group: makeChapter(c, hw) }))
    for (const c of this.chapters) { c.group.visible = false; this.root.add(c.group) }
    this.subs = (film.subs ?? []).map((s) => ({ ...s, group: makeSubtitle(s, hw) }))
    for (const s of this.subs) { s.group.visible = false; this.root.add(s.group) }
  }

  /**
   * Advance the presentation. Returns nothing; the stage reads `flashAmount` and
   * `shake` afterwards. `shake` is in world units at the film's camera distance.
   */
  update(t) {
    const f = this.film
    this.flashAmount = 0
    this.shake[0] = this.shake[1] = 0
    if (!f) return

    // ---- letterbox: closed at the head, opens on the first beat, closes at the tail.
    // A fixed 3 s tail window, not a fraction of the runtime: on a short cut a
    // percentage-based window would start closing the bars over the last third.
    const closed = Math.max(1 - smooth01(t, 0.15, 1.0), smooth01(t, (f.duration ?? 60) - 3.0, 1.5))
    const barH = 0.12 * closed
    for (let i = 0; i < 2; i++) {
      const bar = this.bars[i]
      bar.visible = barH > 0.001
      bar.scale.y = barH
      bar.position.y = (i === 0 ? 1 : -1) * (1 - barH / 2)
    }

    // ---- chapters: the heading is up for the first ~5 s of its chapter, then withdrawn.
    for (const c of this.chapters) {
      // A chapter may span several shots; `span` (default 6 s) is how long its heading stays up.
      const span = c.span ?? 6.0
      const a = t >= c.start && t < c.start + span ? win(t - c.start, 0.5, span, 0.6, 0.8) : 0
      c.group.visible = a > 0.002
      if (c.group.visible) {
        setOpacity(c.group, a)
        // a 30 px slide-in, like a plate being laid on the page
        const slide = (1 - smooth01(t - c.start, 0.5, 0.8)) * -30 * PX
        c.group.position.x = slide
      }
    }

    // ---- subtitles: in at the cue, out at its end; the English line lags a beat.
    for (const s of this.subs) {
      const a = win(t, s.a, s.b, 0.25, 0.30)
      s.group.visible = a > 0.002
      if (!s.group.visible) continue
      s.group.userData.zh.material.opacity = a
      s.group.userData.en.material.opacity = a * smooth01(t, s.a + 0.08, 0.3)
      s.group.userData.band.material.opacity = a * 0.55
      // a small rise so a line never simply appears
      s.group.position.y = (1 - smooth01(t, s.a, 0.35)) * -0.02
    }

    // ---- hits: light only. No camera kick — emphasis is a flash and a beat, and a shaking
    // frame reads as a mistake. `shake` stays zero on purpose.
    let flash = 0
    for (const h of f.hits ?? []) {
      if (t < h.t || t > h.t + 1.5) continue
      flash += 0.75 * h.k * Math.exp(-(t - h.t) * 5)
    }
    this.flashAmount = Math.min(0.9, flash)
    this.flash.visible = this.flashAmount > 0.002
    this.flash.material.opacity = this.flashAmount
    this.shake[0] = 0
    this.shake[1] = 0
  }
}

function makeChapter({ num, zh, en }, hw) {
  const g = new THREE.Group()
  const x0 = -hw + 0.075
  const y0 = 1 - 0.105
  let cursor = x0

  const numL = makeLabel(num, { px: 46, weight: 700, color: GOLD, family: SERIF, align: 'left', layer: 0 })
  numL.position.set(cursor + numL.userData.baseScale / 2, y0, 0)
  g.add(numL)
  cursor += numL.userData.baseScale + 0.020

  const rule = new THREE.Mesh(
    new THREE.PlaneGeometry(0.0035, 52 * PX),
    new THREE.MeshBasicMaterial({ color: GOLD, transparent: true, depthWrite: false }),
  )
  rule.material.toneMapped = false
  rule.position.set(cursor, y0, 0)
  g.add(rule)
  cursor += 0.024

  const zhL = makeLabel(zh, { px: 34, weight: 700, color: '#FFFFFF', family: SERIF, align: 'left', layer: 0 })
  zhL.position.set(cursor + zhL.userData.baseScale / 2, y0, 0)
  g.add(zhL)

  const enL = makeLabel(en, { px: 15, weight: 500, color: GOLD_DIM, family: SERIF, align: 'left', letterSpacing: 5, layer: 0 })
  enL.position.set(cursor + enL.userData.baseScale / 2, y0 - 0.078, 0)
  g.add(enL)

  return g
}

function makeSubtitle({ zh, en }, hw) {
  const g = new THREE.Group()

  // The dark gradient: subtitles must not fight the picture's own brightness.
  const band = new THREE.Mesh(
    new THREE.PlaneGeometry(hw * 2, 0.42),
    new THREE.MeshBasicMaterial({ map: verticalFade(), transparent: true, depthWrite: false }),
  )
  band.material.toneMapped = false
  band.renderOrder = -1
  band.position.set(0, -0.79, -0.01)
  g.add(band)

  const zhL = makeLabel(zh, { px: 42, weight: 700, color: '#FFFFFF', family: SERIF, align: 'center', layer: 0, shadowColor: 'rgba(0,0,0,0.92)', shadowBlur: 12 })
  zhL.renderOrder = 1
  zhL.position.set(0, -0.78, 0)
  g.add(zhL)

  const enL = makeLabel(en, { px: 30, weight: 400, color: '#E6DECC', family: SERIF_IT, italic: true, align: 'center', layer: 0, shadowColor: 'rgba(0,0,0,0.9)', shadowBlur: 8 })
  enL.renderOrder = 1
  enL.position.set(0, -0.855, 0)
  g.add(enL)

  g.userData = { band, zh: zhL, en: enL }
  return g
}

let _fade = null
function verticalFade() {
  if (_fade) return _fade
  const c = document.createElement('canvas')
  c.width = 4
  c.height = 256
  const ctx = c.getContext('2d')
  const grd = ctx.createLinearGradient(0, 0, 0, 256)
  grd.addColorStop(0, 'rgba(0,0,0,0)')
  grd.addColorStop(0.55, 'rgba(0,0,0,0.55)')
  grd.addColorStop(1, 'rgba(0,0,0,0.85)')
  ctx.fillStyle = grd
  ctx.fillRect(0, 0, 4, 256)
  _fade = new THREE.CanvasTexture(c)
  _fade.colorSpace = THREE.SRGBColorSpace
  return _fade
}

/** Set opacity on a whole tree that was built from transparent type plates. */
function setOpacity(root, a) {
  root.traverse((o) => {
    if (!o.material) return
    const mats = Array.isArray(o.material) ? o.material : [o.material]
    for (const m of mats) { m.transparent = true; m.opacity = a }
  })
}
