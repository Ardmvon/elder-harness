// Shot 3 — 插话与步骤播报 (0:12–0:18)
//
// Two claims, one image each:
//   the person can interrupt the assistant mid-sentence, and they hear every step as it
//   happens rather than a summary at the end.
//
// Motion design (Apple grammar):
//   * three rings, each one a speaker: orange = the microphone is open (the person),
//     green = the assistant. Colour is the interface language the shots share.
//   * the interruption is not a fade. The assistant's ring collapses inward, its phase
//     reverses, its colour steps to orange, and a single very short optical pulse
//     (bloom + chromatic aberration) lands on the cut — a flinch, then stillness.
//   * the steps are frosted pills that slide in from the right, one at a time. The newest
//     is at full strength; the older ones step back to half, so attention has one home.
//   * the camera arcs once and reacts to the interruption with a damped impulse, not a
//     shake. A shake reads as a glitch; a settle reads as a decision.

import * as THREE from 'three'
import { defineShot } from '../stage.js'
import {
  makePhone, makeSoundRing, makePill, paintHomeScreen,
  makeSet, makeMirror, distanceFor, BRAND, ATTENTION, INK_BG,
} from '../props.js'
import { EASE, ramp } from '../ease.js'

export const BEATS = {
  ring1At: 0.45, ring1Life: 2.80,          // the person speaks: mic ring, orange
  assistantAt: 3.05, assistantGrow: 1.60,  // the assistant answers: brand ring
  cutAt: 4.95, cutLife: 1.20,              // the person talks over it
  ring3At: 5.55, ring3Life: 2.40,          // the mic takes over, orange
  stepAt: 6.40, stepStagger: 1.30, stepDur: 1.20,
}

const STEPS = ['正在打开微信', '正在找到文件传输助手', '正在把文字填进去']

export const shotVoice = defineShot({
  name: 'voice',
  start: 27,
  duration: 13,

  // ---- film metadata (chapter Ⅰ continues; the heading was set up by shot 2)
  subs: [[9.4, 12.8, '说得进去，也插得进去', 'You can talk over it — and it stops.']],
  sfx: [[0.45, 'whoosh', { dur: 1.8 }], [3.05, 'whoosh', { dur: 1.5, pitch: 0.9 }], [4.95, 'cut'], [5.55, 'whoosh', { dur: 1.7 }]],
  hits: [],
  mb: 3,

  build(stage) {
    const group = new THREE.Group()
    group.visible = false
    stage.scene.add(group)

    const set = makeSet()
    group.add(set)

    const height = 1.86
    const phone = makePhone({ height })
    phone.position.y = -0.10
    group.add(phone)

    const mirror = makeMirror(phone, { floorY: -1.28, opacity: 0.10 })
    group.add(mirror)

    // Three speakers, three rings. Two layers each: a crisp edge and a wide, dim halo —
    // one flat ring reads as a drawn circle, two read as light travelling through air.
    const rings = [ATTENTION, BRAND, ATTENTION].map((color) => {
      const pair = new THREE.Group()
      const core = makeSoundRing({ radius: 0.42, width: 0.028, color })
      const halo = makeSoundRing({ radius: 0.42, width: 0.130, color })
      core.material.opacity = 0
      halo.material.opacity = 0
      pair.add(core, halo)
      pair.position.copy(phone.position)
      pair.userData = { core, halo }
      group.add(pair)
      return pair
    })

    // The assistant speaking, as five bars. It appears only while the assistant holds the
    // turn, and it is the thing that snaps flat at the interruption.
    const BARS = 5
    const bars = []
    for (let i = 0; i < BARS; i++) {
      const bar = new THREE.Mesh(
        new THREE.PlaneGeometry(0.028, 1),
        new THREE.MeshBasicMaterial({
          color: BRAND, transparent: true, depthWrite: false,
          blending: THREE.AdditiveBlending, opacity: 0,
        }),
      )
      bar.position.set(0.67 + i * 0.075, 0.30, 0.35)
      group.add(bar)
      bars.push(bar)
    }

    // The steps, as frosted pills on the right. Baked text + chip in one texture, so the
    // corner radius and the baseline can never disagree.
    const pills = STEPS.map((text) => {
      const pill = makePill(text, {
        px: 40, color: '#EDF8F4', weight: 500,
        bg: 'rgba(16,25,30,0.74)', border: 'rgba(127,227,200,0.30)',
        dotColor: '#39C39B', worldHeight: 0.175,
      })
      pill.visible = false
      group.add(pill)
      return pill
    })

    group.userData = { set, phone, mirror, rings, bars, pills, lastPaint: null }
    stage.userData.voice = group
  },

  enter(stage) {
    const group = stage.userData.voice
    group.visible = true
    group.userData.lastPaint = null
    stage.renderer.setClearColor(INK_BG, 1)
    // Remember the post defaults so the interruption pulse cannot leak into the next shot.
    group.userData.bloomBase = stage.bloom.strength
    group.userData.abBase = stage.lens.uniforms.uAberration.value
  },

  update(stage, local, t) {
    const group = stage.userData.voice
    const { set, phone, rings, bars, pills } = group.userData
    const B = BEATS

    set.userData.update?.(t)

    // ---- the interruption pulse: one short optical event, then done.
    const pop = local >= B.cutAt ? Math.exp(-(local - B.cutAt) * 3.4) : 0
    stage.bloom.strength = group.userData.bloomBase + 0.28 * pop
    stage.lens.uniforms.uAberration.value = group.userData.abBase + 0.0075 * pop

    // ---- camera: one calm arc. No handheld layer, no shake on the interruption: the beat
    // is carried by the ring's collapse, the colour step and a single short optical pulse.
    const d = 3.53 - 0.20 * ramp(local, 0.6, 6.5, EASE.inOut)
    const a = 0.028 + 0.19 * Math.sin(local * 0.15)
    stage.camera.position.set(d * Math.sin(a), 0.015 + 0.035 * Math.cos(local * 0.19), d * Math.cos(a))
    stage.camera.lookAt(0, -0.02, 0)

    // ---- the phone stays listening: repaint only when the breath would differ.
    const pulse = 0.5 + 0.5 * Math.sin(t * 1.7)
    const pq = Math.round(pulse * 20) / 20
    if (group.userData.lastPaint !== pq) {
      group.userData.lastPaint = pq
      phone.userData.paint((ctx, w, h) => {
        paintHomeScreen(ctx, w, h, {
          status: '正在听…', tone: '#C46A14', circle: '我在听', hint: '说完就好',
          pressed: 1, depth: 0, pulse: pq, wake: 1,
        })
      })
    }

    // ---- ring 1: the person speaks. Expands, tilts, fades.
    driveRing(rings[0], local, B.ring1At, B.ring1Life, { grow: 2.55, tilt: 1 })
    // ---- ring 2: the assistant answers, then is cut off from the inside.
    driveAssistant(rings[1], local, t, B)
    // ---- ring 3: the microphone takes over.
    driveRing(rings[2], local, B.ring3At, B.ring3Life, { grow: 2.30, tilt: -1 })

    // ---- the speaking bars: alive while the assistant holds the turn, flat at the cut.
    const speaking = local >= B.assistantAt
    const cut = Math.max(0, local - B.cutAt)
    const alive = speaking ? Math.max(0, 1 - cut / 0.30) : 0
    const barColor = new THREE.Color(BRAND).lerp(new THREE.Color(ATTENTION), Math.min(1, cut / 0.25))
    for (let i = 0; i < bars.length; i++) {
      const bar = bars[i]
      bar.material.color.copy(barColor)
      if (alive <= 0.001) { bar.material.opacity = 0; continue }
      const wobble = Math.abs(Math.sin(t * 6.1 + i * 1.7)) * 0.6
        + Math.abs(Math.sin(t * 9.3 + i * 0.9)) * 0.4
      const hgt = (0.05 + 0.15 * wobble) * alive
      bar.scale.set(1, hgt, 1)
      bar.position.y = 0.30 + hgt / 2
      bar.material.opacity = 0.85 * alive
    }

    // ---- the steps. Newest at full strength; the earlier ones step back.
    const revealed = local >= B.stepAt
      ? Math.min(pills.length, 1 + Math.floor((local - B.stepAt) / B.stepStagger))
      : 0
    const current = revealed - 1
    for (let i = 0; i < pills.length; i++) {
      const pill = pills[i]
      if (i >= revealed) { pill.visible = false; continue }
      const k = ramp(local, B.stepAt + i * B.stepStagger, B.stepDur, EASE.out)
      pill.visible = k > 0.001
      const baseY = 0.34 - i * 0.30
      pill.position.set(0.86 + (1 - k) * 0.16, baseY + (1 - k) * 0.10, 0.42)
      const dim = i === current ? 1 : 0.52
      pill.material.opacity = k * dim
      // makePill already put the world size in the geometry, so this is a unit scale.
      const sc = 0.97 + 0.03 * k
      pill.scale.set(sc, sc, 1)
    }

  },

  teardown(stage) {
    const group = stage.userData.voice
    if (group) group.visible = false
    // Never let the pulse's post values survive into the next shot.
    if (stage.bloom) stage.bloom.strength = group?.userData?.bloomBase ?? stage.bloom.strength
    if (stage.lens) stage.lens.uniforms.uAberration.value = group?.userData?.abBase ?? stage.lens.uniforms.uAberration.value
  },
})

/** A ring that simply expands and fades. The whisper of asymmetry stops it reading as a circle. */
function driveRing(ring, local, at, life, { grow = 2.4, tilt = 1 } = {}) {
  const age = local - at
  const alive = age >= 0 && age < life
  ring.visible = alive
  if (!alive) return
  const k = age / life
  const s = 0.5 + grow * EASE.out(k)
  ring.scale.set(s, s * (1 + 0.06 * Math.sin(age * 2.1)), 1)
  ring.rotation.z = tilt * (0.25 * Math.sin(age * 1.3)) + k * tilt * 0.20
  const fade = 1 - k
  ring.userData.core.material.opacity = fade * 0.80
  ring.userData.halo.material.opacity = fade * 0.24
}

/**
 * The assistant's ring: it grows like the others, then at the cut it collapses from the
 * *inside* (scale pulled down rather than faded out), reverses its rotation and steps
 * brand -> attention. That reversal is the idea; a plain fade would read as the assistant
 * finishing its sentence.
 */
function driveAssistant(ring, local, t, B) {
  const age = local - B.assistantAt
  const cut = local - B.cutAt
  const alive = age >= 0 && cut < B.cutLife
  ring.visible = alive
  if (!alive) return
  const grow = Math.min(1, age / B.assistantGrow)
  // The collapse is what the beat means: the ring is pulled in from the inside fast, on an
  // accelerating curve, while the colour change runs slower underneath it.
  const shrink = cut > 0 ? EASE.in(Math.min(1, cut / 0.26)) : 0
  const s = (0.5 + 1.35 * EASE.out(grow)) * (1 - 0.85 * shrink)
  ring.scale.set(s, s * (1 + 0.05 * Math.sin(age * 2.0)), 1)
  ring.rotation.z = 0.25 * Math.sin(age * 1.2) - 0.9 * Math.min(1, Math.max(0, cut) / B.cutLife)
  const mix = cut > 0 ? Math.min(1, cut / 0.22) : 0
  const col = new THREE.Color(BRAND).lerp(new THREE.Color(ATTENTION), mix)
  ring.userData.core.material.color.copy(col)
  ring.userData.halo.material.color.copy(col)
  const fade = cut > 0 ? Math.max(0, 1 - cut / B.cutLife) : 1
  ring.userData.core.material.opacity = fade * 0.85
  ring.userData.halo.material.opacity = fade * 0.26
}
