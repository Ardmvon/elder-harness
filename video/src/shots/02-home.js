// Shot 2 — 一屏一件事 (0:06–0:12)
//
// The premise in one frame: the phone the elder actually holds has exactly one thing to
// press. No icon grid, no list, no form — because the earlier design had all three and the
// person had to decide before they could ask for anything.
//
// The colour change is the message: green means idle, orange means the microphone is open.
// That is the same signal the real overlay uses, so the film and the product agree.

import * as THREE from 'three'
import { defineShot } from '../stage.js'
import {
  makePhone, makeSubtitle, paintHomeScreen, glowTexture, setGroupOpacity,
  distanceFor, ATTENTION, INK_BG,
} from '../props.js'
import { smooth01, easeOut, spring } from '../ease.js'

export const BEATS = {
  phoneIn: 0.15, phoneInDur: 1.05,
  screenOn: 1.05, screenOnDur: 0.70,
  press: 3.05, pressDur: 0.55,
  subtitleIn: 4.30, subtitleInDur: 0.70,
}

export const shotHome = defineShot({
  name: 'home',
  start: 6,
  duration: 6,

  build(stage) {
    const group = new THREE.Group()
    group.visible = false
    stage.scene.add(group)

    const phone = makePhone({ height: 2.0 })
    group.add(phone)

    // A ripple leaving the button when it is pressed, plus the glow behind the phone.
    const ripple = new THREE.Mesh(
      new THREE.PlaneGeometry(1, 1),
      new THREE.MeshBasicMaterial({
        map: glowTexture(), transparent: true, depthWrite: false,
        blending: THREE.AdditiveBlending, color: ATTENTION, opacity: 0,
      }),
    )
    ripple.position.z = 0.01
    group.add(ripple)

    // Subtitle lives on the orthographic overlay; added here so it can be faded and
    // removed without leaving anything behind for the next shot.
    const subtitle = makeSubtitle({
      text: '老人说出需求',
      sub: '一屏一件事，一个按钮，颜色就是状态',
      y: -0.80,
    })
    subtitle.visible = false
    setGroupOpacity(subtitle, 0)
    stage.overlay.add(subtitle)

    group.userData = { phone, ripple, subtitle, lastPaint: null }
    stage.userData.home = group
  },

  enter(stage) {
    const group = stage.userData.home
    group.visible = true
    group.userData.lastPaint = null
    group.userData.subtitle.visible = true
    group.position.set(0, 0, 0)
    stage.renderer.setClearColor(INK_BG, 1)
  },

  update(stage, local, t) {
    const group = stage.userData.home
    const { phone, ripple } = group.userData

    const arrive = smooth01(local, BEATS.phoneIn, BEATS.phoneInDur)
    group.position.y = (1 - arrive) * -0.55
    group.scale.setScalar(0.94 + 0.06 * arrive)

    // The phone fills ~62% of the frame: the bottom fifth stays clear for subtitles, and a
    // phone that fills the frame leaves nowhere for the sentence to land.
    stage.camera.position.set(0, 0, distanceFor(stage, 2.0, 0.62) - 0.25 * arrive)
    stage.camera.lookAt(0, 0, 0)

    // The screen lights up after the phone has landed: a dark slab that turns into a
    // readable screen is how a device announces itself, and it keeps the frame calm.
    const lit = smooth01(local, BEATS.screenOn, BEATS.screenOnDur)
    const press = smooth01(local, BEATS.press, BEATS.pressDur)
    const settle = spring(Math.max(0, local - BEATS.press), 5.5, 9.5)

    // Quantised so the 2D canvas is repainted only when it would actually differ.
    const state = {
      lit: Math.round(lit * 24) / 24,
      press: Math.round(press * 24) / 24,
    }
    const key = `${state.lit}|${state.press}`
    if (key !== group.userData.lastPaint) {
      group.userData.lastPaint = key
      phone.userData.paint((ctx, w, h) => {
        paintHomeScreen(ctx, w, h, {
          status: state.press > 0.5 ? '正在听…' : '我在',
          tone: state.press > 0.5 ? '#C46A14' : '#1E8449',
          circle: state.press > 0.5 ? '我在听' : '说给接线员听',
          hint: state.press > 0.5 ? '说完就好' : '点一下开始说话',
          pressed: state.press,
        })
        // Screens are not self-luminous in reality; the dimming keeps the phone from
        // looking like a sticker pasted over the background.
        ctx.fillStyle = `rgba(14,20,24,${(1 - state.lit) * 0.94})`
        ctx.fillRect(0, 0, w, h)
      })
    }

    phone.userData.surface.material.opacity = 0.15 + 0.85 * lit
    phone.userData.rim.material.opacity = 0.10 + 0.22 * lit

    // Ripple: a glow that expands once and dies. `spring` gives the press a small
    // overshoot so it reads as a physical button rather than a state change.
    const rp = Math.max(0, local - BEATS.press)
    const rlife = smooth01(rp, 0, 1.15)
    const rsize = 0.5 + 1.5 * easeOut(Math.min(1, rp / 1.15))
    ripple.scale.set(rsize, rsize, 1)
    ripple.material.opacity = (1 - rlife) * 0.55 * (rp > 0 ? 1 : 0)
    ripple.position.y = -0.05 * settle

    const subIn = smooth01(local, BEATS.subtitleIn, BEATS.subtitleInDur)
    setGroupOpacity(group.userData.subtitle, subIn)
  },

  teardown(stage) {
    const group = stage.userData.home
    if (group) group.visible = false
    stage.overlay.remove(stage.userData.home.userData.subtitle)
  },
})
