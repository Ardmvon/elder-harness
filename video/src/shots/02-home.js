// Shot 2 — 一屏一件事 (0:06–0:12)
//
// The premise in one frame: the phone the elder actually holds has exactly one thing to
// press. No icon grid, no list, no form — the earlier design had all three, and the person
// had to make a decision before they could ask for anything.
//
// Motion design (Apple grammar):
//   * the device does not fade in, it LANDS — the shot-1 sparks collapse onto its outline,
//     then the body rises the last centimetre and settles with a damped spring while a
//     specular band sweeps once down the glass.
//   * the screen turns on in reading order (status -> button -> hint -> safety row), each
//     element rising a few pixels into place. One flash would say "image"; this says "device".
//   * the press has three parts: a small lift (anticipation), the press itself, then a
//     damped release. Colour is a separate, slower signal from the physical depth, because
//     the microphone is still open after the finger has gone.
//   * the camera keeps moving the whole six seconds, on one long decelerating curve, and the
//     hand-held layer is a slow drift instead of a jitter.

import * as THREE from 'three'
import { defineShot } from '../stage.js'
import {
  makePhone, paintHomeScreen, glowTexture, bandTexture,
  makeSet, makeMirror, makeSparks, coilPoint, phoneOutlinePoints, camKey,
  distanceFor, makeSoundRing, BRAND, BRAND_DEEP, ATTENTION, INK_BG, DEVICE_ASPECT, LAYER_UI,
} from '../props.js'
import { EASE, ramp, settle, impulse } from '../ease.js'
import { makeCoilField, COIL_POSE, FIELD_SEED } from '../field.js'

export const BEATS = {
  sparkHold: 0.16, sparkGather: 1.05,      // the coil unwinds into the phone outline
  deviceIn: 0.92, deviceDur: 0.95,         // the body arrives and settles
  wake: 1.60, wakeDur: 0.92,               // the screen turns on, top to bottom
  hold: 2.72,                              // one calm beat: nothing moves but the light
  anticipate: 3.42, anticipateDur: 0.17,   // the button lifts, 1/6 s
  press: 3.59, pressDown: 0.17,            // the finger lands
  listenFrom: 3.66, listenDur: 0.55,       // colour / state, slower than the finger
}

const DOWN = 0.10                    // how far the body travels on the way in

export const shotHome = defineShot({
  name: 'home',
  start: 15,
  duration: 12,

  // ---- film metadata
  chapter: { num: 'Ⅰ', zh: '说得出口', en: 'SAYING IT' },
  subs: [[9.4, 12.0, '老人说出需求', 'One screen, one thing. One button. The colour is the state.']],
  sfx: [[0.1, 'whoosh', { dur: 2.0 }], [3.1, 'shimmer'], [7.2, 'tap'], [7.25, 'click'], [9.4, 'chime', { midi: 81 }]],
  hits: [],
  mb: 4,

  build(stage) {
    const group = new THREE.Group()
    group.visible = false
    stage.scene.add(group)

    // Space first, then the device, then its reflection: the phone has to sit *in*
    // something for the camera move to read as a camera move.
    const set = makeSet()
    group.add(set)

    const height = 1.86
    const phone = makePhone({ height })
    phone.position.y = -0.10
    group.add(phone)

    const mirror = makeMirror(phone, { floorY: -1.28, opacity: 0.11 })
    group.add(mirror)

    // The sparks shot 1 handed over: the *same field*, same count, same positions, same
    // world pose. Shot 1 ended with 36 000 points on the coil; starting here with 4 200
    // read as a cut to a thinner effect — brightness dropped 40% across the cut.
    const SPARKS = 36000
    const field = makeCoilField({ count: SPARKS, seed: FIELD_SEED })
    const outline = phoneOutlinePoints(SPARKS, {
      width: height * DEVICE_ASPECT, height, radius: 0.10,
    })
    const sparks = makeSparks({
      count: SPARKS,
      seed: FIELD_SEED,
      spread: 0, // exact positions: the cut has to match point for point
      size: 3.6,
      // Shot 1's own particle palette: a brighter field here flipped the cut from a drop
      // in density to a jump in blown-out highlights.
      brand: BRAND,
      deep: BRAND_DEEP,
      from: (i) => [
        field.coil[i * 3 + 0] * COIL_POSE.scale,
        COIL_POSE.y + field.coil[i * 3 + 1] * COIL_POSE.scale,
        field.coil[i * 3 + 2] * COIL_POSE.scale,
      ],
      to: (i) => [outline[i].x, phone.position.y + outline[i].y, 0],
    })
    // The field is additive and this shot has a floor: without this the floor's depth
    // rejects the lower half of the sparks (the cut lost 40% of its brightness).
    sparks.material.depthTest = false
    group.add(sparks)

    // A specular band that crosses the glass as the device resolves. World layer, so it
    // catches the bloom — a screen that lights up does throw a little light.
    const sweep = new THREE.Mesh(
      new THREE.PlaneGeometry(phone.userData.width * 0.92, 0.16),
      new THREE.MeshBasicMaterial({
        map: bandTexture(), transparent: true, depthWrite: false,
        blending: THREE.AdditiveBlending, color: 0xCFF6EA, opacity: 0,
      }),
    )
    // UI layer: drawn after the screen, so the highlight crosses the glass instead of
    // being hidden behind it. A crisp specular edge is correct here — glass does not bloom.
    sweep.layers.set(LAYER_UI)
    phone.add(sweep)

    // The button's own ripple: a ring and a glow, both born at the real position of the
    // circle on the screen (canvas y = 0.42 h), not at the centre of the device.
    const screenH = height * 0.972
    const buttonY = (0.5 - 0.42) * screenH

    const ripple = new THREE.Group()
    const rippleCore = makeSoundRing({ radius: 0.10, width: 0.012, color: ATTENTION })
    const rippleHalo = makeSoundRing({ radius: 0.10, width: 0.055, color: ATTENTION })
    rippleCore.material.opacity = 0
    rippleHalo.material.opacity = 0
    ripple.add(rippleCore, rippleHalo)
    ripple.position.set(0, buttonY, 0.012)
    rippleCore.layers.set(LAYER_UI)
    rippleHalo.layers.set(LAYER_UI)
    ripple.userData = { core: rippleCore, halo: rippleHalo }
    phone.add(ripple)

    const flash = new THREE.Mesh(
      new THREE.PlaneGeometry(1, 1),
      new THREE.MeshBasicMaterial({
        map: glowTexture(), transparent: true, depthWrite: false,
        blending: THREE.AdditiveBlending, color: 0xFFD9A8, opacity: 0,
      }),
    )
    flash.layers.set(LAYER_UI)
    flash.position.set(0, buttonY, 0.011)
    flash.scale.setScalar(0.4)
    phone.add(flash)

    // The light the open microphone puts into the room, behind the device.
    const listenGlow = new THREE.Mesh(
      new THREE.PlaneGeometry(3.4, 3.0),
      new THREE.MeshBasicMaterial({
        map: glowTexture(), transparent: true, depthWrite: false,
        blending: THREE.AdditiveBlending, color: ATTENTION, opacity: 0,
      }),
    )
    listenGlow.position.set(0, -0.05, -0.30)
    group.add(listenGlow)

    group.userData = { phone, mirror, set, sparks, sweep, ripple, flash, listenGlow, lastPaint: null }
    stage.userData.home = group
  },

  enter(stage) {
    const group = stage.userData.home
    group.visible = true
    group.userData.lastPaint = null
    stage.renderer.setClearColor(INK_BG, 1)
  },

  update(stage, local, t) {
    const group = stage.userData.home
    const { set, phone, mirror, sparks, sweep, ripple, flash, listenGlow } = group.userData
    const B = BEATS

    set.userData.update?.(t)

    // ---- camera: one long decelerating move, no second move, no jitter.
    // Opens exactly on shot 1's final pose and keeps travelling the same way.
    const endZ = distanceFor(stage, 2.0, 0.72) - 0.36
    const cam = camKey(local, [
      { at: 0.00, x: -1.35, y: 0.59, z: 6.70 },
      { at: 3.40, x: -0.30, y: 0.13, z: 4.60 },
      { at: 6.60, x: 0.00, y: -0.02, z: endZ },
      { at: 12.00, x: 0.09, y: 0.01, z: endZ - 0.14 },
    ])
    // No handheld layer and no per-frame noise: one eased dolly, like the reference film's
    // camPush. A frame that wobbles reads as a mistake, not as a camera.
    stage.camera.position.set(cam.x, cam.y, cam.z)
    const eye = 0.10 + (-0.055 - 0.10) * ramp(local, 0, 6.2, EASE.inOut)
    stage.camera.lookAt(0, eye, 0)

    // ---- the handoff: sparks hold, then collapse onto the device outline.
    const sm = sparks.userData.mat.uniforms
    sm.uMorph.value = ramp(local, B.sparkHold, B.sparkGather, EASE.inOut)
    sm.uTime.value = t
    // They do not linger as dust: the outline they drew becomes the object, so they leave.
    sm.uOpacity.value = 1 - ramp(local, B.deviceIn + 0.18, 0.46, EASE.out)

    // ---- the device lands.
    const arrive = ramp(local, B.deviceIn, B.deviceDur, EASE.out)
    const bang = impulse(local - B.deviceIn, { decay: 11, freq: 17 })
    phone.position.y = -0.10 + DOWN * (1 - arrive) + 0.014 * bang
    phone.scale.setScalar(0.972 + 0.028 * arrive)
    phone.userData.setFrameOpacity(arrive)
    phone.userData.sheen.material.opacity = 0.5 * arrive
    mirror.visible = arrive > 0.02
    fadeClone(mirror, arrive)

    // Specular sweep, once, top -> bottom, peaking in the middle.
    const sw = (local - B.wake) / 0.72
    if (sw > 0 && sw < 1) {
      const half = (phone.userData.height * 0.972) * 0.5
      sweep.position.y = half - sw * half * 2
      sweep.material.opacity = Math.sin(Math.PI * sw) * 0.85
    } else {
      sweep.material.opacity = 0
    }

    // ---- the press: lift, land, release. `depth` is physical; colour is slower.
    const d = pressDepth(local)
    const listen = ramp(local, B.listenFrom, B.listenDur, EASE.inOut)

    // The screen repaints only when a quantised value would actually differ.
    const wake = ramp(local, B.wake, B.wakeDur, EASE.out)
    const pulse = listen > 0.01 ? 0.5 + 0.5 * Math.sin(t * 1.7) : 0
    const state = {
      wake: Math.round(wake * 32) / 32,
      color: Math.round(listen * 24) / 24,
      depth: Math.round(d * 24) / 24,
      pulse: Math.round(pulse * 20) / 20,
    }
    const key = `${state.wake}|${state.color}|${state.depth}|${state.pulse}`
    if (key !== group.userData.lastPaint) {
      group.userData.lastPaint = key
      phone.userData.paint((ctx, w, h) => {
        paintHomeScreen(ctx, w, h, {
          status: state.color > 0.5 ? '正在听…' : '我在',
          tone: state.color > 0.5 ? '#C46A14' : '#1E8449',
          circle: state.color > 0.5 ? '我在听' : '说给接线员听',
          hint: state.color > 0.5 ? '说完就好' : '点一下开始说话',
          pressed: state.color,
          depth: state.depth,
          pulse: state.pulse,
          wake: state.wake,
        })
        // Before the panel is lit it is a dark slab; that falloff is what keeps the
        // screen from looking like a printed picture pasted on the device.
        if (state.wake < 1) {
          ctx.fillStyle = `rgba(14,20,24,${(1 - state.wake) * 0.94})`
          ctx.fillRect(0, 0, w, h)
        }
      })
    }

    // The rim light carries the state change: brand while idle, attention while listening.
    const rimCol = new THREE.Color(BRAND).lerp(new THREE.Color(ATTENTION), listen)
    phone.userData.rim.material.color.copy(rimCol)
    phone.userData.rim.material.opacity = (0.07 + 0.30 * listen) * arrive + 0.14 * Math.max(0, bang)
    phone.userData.surface.material.opacity = 0.12 + 0.88 * wake
    listenGlow.material.opacity = 0.16 * listen

    // ---- the ripple leaves the button.
    const rp = Math.max(0, local - B.press)
    const rlife = Math.min(1, rp / 0.80)
    const rsize = 0.6 + 3.4 * EASE.out(rlife)
    ripple.scale.setScalar(rsize)
    const rOp = (1 - rlife) * (rp > 0 ? 1 : 0)
    ripple.userData.core.material.opacity = rOp * 0.85
    ripple.userData.halo.material.opacity = rOp * 0.26

    const fl = Math.min(1, rp / 0.42)
    flash.scale.setScalar(0.35 + 1.15 * EASE.out(fl))
    flash.material.opacity = (1 - fl) * (rp > 0 ? 1 : 0) * 0.45

  },

  teardown(stage) {
    const group = stage.userData.home
    if (group) group.visible = false
  },
})

/**
 * Fade every material of a cloned tree by the same factor, preserving each material's
 * authored opacity. Used for the mirrored device, which is a Group, not a Mesh.
 */
function fadeClone(root, k) {
  root.traverse((o) => {
    if (!o.material) return
    const mats = Array.isArray(o.material) ? o.material : [o.material]
    for (const m of mats) {
      if (m.userData.baseOpacity === undefined) m.userData.baseOpacity = m.opacity ?? 1
      m.opacity = m.userData.baseOpacity * k
    }
  })
}

/**
 * The physical depth of the button, -0.08 (lifted) .. 0 .. 1 (pressed).
 * Three phases, all pure in t, so the frame at 3.70s is the same every run.
 */
function pressDepth(local) {
  const { anticipate, anticipateDur, press, pressDown } = BEATS
  if (local < anticipate) return 0
  if (local < press) {
    // anticipation: the button lifts slightly, as if taking a breath.
    return -0.08 * ramp(local, anticipate, anticipateDur, EASE.out)
  }
  const release = press + pressDown
  if (local < release) {
    return -0.08 + 1.08 * ramp(local, press, pressDown, EASE.inOut)
  }
  // release: a damped spring back to rest, with a small lift as it overshoots.
  return 1 - settle(local - release, { decay: 9.5, freq: 16 })
}
