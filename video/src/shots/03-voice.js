// Shot 3 — 插话与步骤播报 (0:12–0:18)
//
// Two claims, one image each:
//   the person can interrupt the assistant mid-sentence (the ring is cut from the inside
//   and reverses), and they hear every step as it happens rather than a summary at the end.
//
// The reversal is deliberate: a normal "stop" animation would shrink a ring that is still
// expanding outward, which reads as a fade. Reversing the phase and stepping the colour
// brand -> attention is what makes it read as being talked over.

import * as THREE from 'three'
import { defineShot } from '../stage.js'
import {
  makePhone, makeSubtitle, makeLabel, makeSoundRing, paintHomeScreen, setGroupOpacity,
  distanceFor, BRAND, ATTENTION, INK_BG,
} from '../props.js'
import { smooth01, easeOut } from '../ease.js'

export const BEATS = {
  ring1Start: 0.25, ring1Life: 1.35,
  ring2Start: 1.75, cutAt: 2.35, ring2Life: 0.85,
  bubbleStart: 2.80, bubbleStagger: 0.72, bubbleLife: 1.15,
  subtitleIn: 3.40, subtitleInDur: 0.70,
}

const STEPS = ['正在打开微信', '正在找到文件传输助手', '正在把文字填进去']

export const shotVoice = defineShot({
  name: 'voice',
  start: 12,
  duration: 6,

  build(stage) {
    const group = new THREE.Group()
    group.visible = false
    stage.scene.add(group)

    // The phone is a quiet backplate here: the subject is what leaves it.
    const phone = makePhone({ height: 1.9 })
    phone.position.y = -0.05
    group.add(phone)

    const rings = [0, 1].map((i) => {
      const ring = makeSoundRing({ radius: 0.42, width: 0.035, color: i === 0 ? BRAND : ATTENTION })
      ring.position.copy(phone.position)
      ring.material.opacity = 0
      group.add(ring)
      return ring
    })

    const bubbles = STEPS.map((text) => {
      // World-space height ~0.13 units: about 40px on screen at this camera, which matches
      // the storyboard's minimum for on-screen text.
      const bubble = makeLabel(text, { px: 40, weight: 500, color: '#F2FBF8', worldHeight: 0.17 })
      bubble.visible = false
      group.add(bubble)
      return bubble
    })

    const subtitle = makeSubtitle({
      text: '说得进去，也插得进去',
      sub: '说完自动接、说话时能打断、每一步都念出来',
      y: -0.80,
    })
    subtitle.visible = false
    setGroupOpacity(subtitle, 0)
    stage.overlay.add(subtitle)

    group.userData = { phone, rings, bubbles, subtitle, lastPaint: null }
    stage.userData.voice = group
  },

  enter(stage) {
    const group = stage.userData.voice
    group.visible = true
    group.userData.subtitle.visible = true
    group.userData.lastPaint = null
    stage.renderer.setClearColor(INK_BG, 1)
  },

  update(stage, local, t) {
    const group = stage.userData.voice
    const { phone, rings, bubbles, subtitle } = group.userData

    stage.camera.position.set(0, 0.05, distanceFor(stage, 1.9, 0.66))
    stage.camera.lookAt(0, 0, 0)

    const lit = smooth01(local, 0, 0.6)
    if (group.userData.lastPaint !== 'on') {
      group.userData.lastPaint = 'on'
      phone.userData.paint((ctx, w, h) => {
        paintHomeScreen(ctx, w, h, {
          status: '正在听…',
          tone: '#C46A14',
          circle: '我在听',
          hint: '说完就好',
          pressed: 1,
        })
      })
    }
    phone.userData.surface.material.opacity = 0.15 + 0.85 * lit
    phone.userData.rim.material.opacity = 0.10 + 0.20 * lit

    // --- ring 1: the person speaks, and it travels outward untouched.
    const r1 = Math.max(0, local - BEATS.ring1Start)
    const r1t = Math.min(1, r1 / BEATS.ring1Life)
    rings[0].visible = r1 > 0 && r1t < 1
    if (rings[0].visible) {
      const s = 0.55 + 2.1 * easeOut(r1t)
      rings[0].scale.set(s, s, 1)
      rings[0].material.color.setHex(BRAND)
      rings[0].material.opacity = (1 - r1t) * 0.75
    }

    // --- ring 2: the assistant starts, and is interrupted from the inside.
    const r2 = Math.max(0, local - BEATS.ring2Start)
    const cut = local - BEATS.cutAt
    rings[1].visible = r2 > 0 && local < BEATS.cutAt + BEATS.ring2Life
    if (rings[1].visible) {
      const grow = Math.min(1, r2 / 0.9)
      const shrink = cut > 0 ? easeOut(Math.min(1, cut / BEATS.ring2Life)) : 0
      const s = (0.55 + 1.2 * easeOut(grow)) * (1 - 0.72 * shrink)
      rings[1].scale.set(s, s, 1)
      // Colour steps to attention at the cut: the interruption is the moment the
      // microphone takes over, which is the same signal the real UI uses.
      const mix = cut > 0 ? Math.min(1, cut / 0.25) : 0
      rings[1].material.color.setHex(BRAND).lerp(new THREE.Color(ATTENTION), mix)
      rings[1].material.opacity = (cut > 0 ? (1 - shrink) : 0.75) * 0.8
    }

    // --- the steps, rising one at a time. Not one conclusion: every step, in order.
    bubbles.forEach((bubble, i) => {
      const at = BEATS.bubbleStart + i * BEATS.bubbleStagger
      const life = Math.max(0, local - at)
      const alive = local >= at && life < BEATS.bubbleLife
      bubble.visible = alive
      if (!alive) return
      const k = life / BEATS.bubbleLife
      const rise = easeOut(k)
      // Right of the phone, stacked upward, clear of both the device and the subtitle band.
      bubble.position.set(1.16 - 0.14 * rise, 0.62 - i * 0.40 + 0.18 * (1 - rise), 0.4)
      const fade = k < 0.18 ? k / 0.18 : 1 - Math.max(0, (k - 0.65) / 0.35)
      setGroupOpacity(bubble, Math.max(0, fade))
      bubble.quaternion.copy(stage.camera.quaternion)
    })

    const subIn = smooth01(local, BEATS.subtitleIn, BEATS.subtitleInDur)
    setGroupOpacity(subtitle, subIn)
  },

  teardown(stage) {
    const group = stage.userData.voice
    if (group) group.visible = false
    stage.overlay.remove(stage.userData.voice.userData.subtitle)
  },
})
