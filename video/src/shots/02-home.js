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
  makeSet, makeMirror, makeSparks, coilPoint, phoneOutlinePoints, camKey,
  distanceFor, DEVICE_ASPECT, ATTENTION, INK_BG,
} from '../props.js'
import { smooth01, easeOut, spring } from '../ease.js'

export const BEATS = {
  // The shot opens on the coil shot 1 handed over, then gathers it into the phone. Nothing
  // fades in from nowhere: the same sparks change shape, which is what makes the cut invisible.
  hold: 0.35, gatherDur: 1.10,
  sparkFadeOut: 1.60, sparkFadeDur: 0.55,
  phoneIn: 1.30, phoneInDur: 0.80,
  screenOn: 1.85, screenOnDur: 0.85,
  press: 3.55, pressDur: 0.55,
  subtitleIn: 4.45, subtitleInDur: 0.70,
}

export const shotHome = defineShot({
  name: 'home',
  start: 6,
  duration: 6,

  build(stage) {
    const group = new THREE.Group()
    group.visible = false
    stage.scene.add(group)

    // Space first, then the device, then its reflection: the phone has to sit *in* something
    // for the camera move to read as a camera move.
    const set = makeSet()
    group.add(set)

    const phone = makePhone({ height: 1.86 })
    phone.position.y = -0.10
    group.add(phone)

    const mirror = makeMirror(phone, { floorY: -1.28, opacity: 0.11 })
    group.add(mirror)

    // The sparks shot 1 handed over: they begin exactly on the coil (same formula, same seed,
    // same world position as shot 1's group) and gather into the phone's outline.
    const SPARKS = 4200
    const outline = phoneOutlinePoints(SPARKS, {
      width: 1.86 * DEVICE_ASPECT, height: 1.86, radius: 0.10,
    })
    const sparks = makeSparks({
      count: SPARKS,
      seed: 11,
      spread: 0.035,
      size: 4.2,
      from: (i) => {
        const p = coilPoint(i / SPARKS)
        return [p.x * 0.85, 0.10 + p.y * 0.85, p.z * 0.85]
      },
      to: (i) => [outline[i].x, phone.position.y + outline[i].y, 0],
    })
    group.add(sparks)

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

    group.userData = { phone, mirror, set, sparks, ripple, subtitle, lastPaint: null }
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
    const { phone, ripple, sparks } = group.userData

    // The camera opens on the pose shot 1 ended on and keeps travelling the same way as it
    // settles in front of the phone: the move crosses the cut instead of restarting after it.
    const cam = camKey(local, [
      { at: 0.0, x: -1.35, y: 0.59, z: 6.70 },
      { at: 1.70, x: -0.42, y: 0.14, z: 4.95 },
      { at: 3.30, x: 0.00, y: -0.02, z: distanceFor(stage, 2.0, 0.72) },
      { at: 6.00, x: 0.17, y: 0.03, z: distanceFor(stage, 2.0, 0.72) - 0.36 },
    ])
    stage.camera.position.set(cam.x, cam.y, cam.z)
    // The look-at drifts from the coil's height down to the phone's face with the move, so the
    // subject stays framed while the camera is still arriving.
    const eye = 0.10 + (-0.06 - 0.10) * smooth01(local, 0.0, 3.3)
    stage.camera.lookAt(0, eye + 0.02 * Math.sin(local * 0.3), 0)

    // The screen lights up after the phone has landed: a dark slab that turns into a
    // readable screen is how a device announces itself, and it keeps the frame calm.
    // Sparks: hold on the coil, gather into the outline, then give way to the solid device.
    const sm = sparks.userData.mat.uniforms
    sm.uMorph.value = smooth01(local, BEATS.hold, BEATS.gatherDur)
    sm.uTime.value = t
    sm.uOpacity.value = 1 - smooth01(local, BEATS.sparkFadeOut, BEATS.sparkFadeDur)

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

    const bodyIn = smooth01(local, BEATS.phoneIn, BEATS.phoneInDur)
    phone.userData.surface.material.opacity = (0.15 + 0.85 * lit) * bodyIn
    phone.userData.body.material.opacity = bodyIn
    phone.userData.rim.material.opacity = (0.10 + 0.22 * lit) * bodyIn

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
