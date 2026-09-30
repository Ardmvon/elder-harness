// Ⅲ 办得成事 (1:05–1:35, 30s)
//
// The loop, drawn as weather: observations and history spiral into a cloud, the cloud hands
// back one tool at a time, the tool acts, and the new page flows back in. Nothing here is a
// diagram of an architecture — it is a cycle the camera can fly around.

import * as THREE from 'three'
import { defineShot } from '../stage.js'
import {
  makePhone, makeSet, makeLabel, makeCard, makeSparks, glowTexture, mulberry32,
  distanceFor, setGroupOpacity, BRAND, ATTENTION, INK_BG, LAYER_UI,
} from '../props.js'
import { EASE, ramp } from '../ease.js'

export const BEATS = {
  pullOut: 0.6, pullDur: 5.0,      // the phone becomes a point at the centre
  bandIn: 6.2, bandDur: 6.0,       // the observation band winds into the vortex
  toolsOut: 12.6, toolsStep: 2.2,  // tap / input_text / screenshot come back out
  loopFrom: 22.0, loopDur: 6.0,    // a step acts, the new page flows back
}

const TOOLS = ['tap', 'input_text', 'screenshot']
const BAND = ['无障碍树 · 34 个节点', '微信 · 1 个节点', '「我到家了」· 已输入', '第 4 步 · 等待页面', '当前时间 17:44', '工具目录 · 9 项']

export const shotVortex = defineShot({
  name: 'vortex',
  start: 65,
  duration: 30,

  chapter: { num: 'Ⅲ', zh: '办得成事', en: 'GETTING IT DONE' },
  subs: [
    [2.6, 7.0, '模型只决定下一步做什么', 'The model only decides the next step.'],
    [7.2, 12.0, '做完一步，把新页面再看一眼', 'Then it looks at the new screen.'],
    [12.2, 17.0, '看见、决定、动手、再看', 'Look, decide, act, look again.'],
    [17.2, 22.0, '一圈一圈，直到事情办成', 'Round and round, until the thing is done.'],
    [22.2, 27.0, '它不猜，它去看', 'It does not guess. It looks.'],
  ],
  sfx: [[0.6, 'whoosh', { dur: 3.0 }], [6.2, 'swell', { dur: 3.0 }],
    [12.6, 'ding', { midi: 79 }], [14.8, 'ding', { midi: 83 }], [17.0, 'ding', { midi: 86 }],
    [22.0, 'tick'], [23.4, 'tick'], [24.8, 'tick'], [26.2, 'ring', { dur: 2.4 }]],
  mb: 3,

  build(stage) {
    const group = new THREE.Group()
    group.visible = false
    stage.scene.add(group)
    const set = makeSet()
    group.add(set)

    const phone = makePhone({ height: 1.86 })
    phone.position.y = -0.10
    group.add(phone)
    phone.userData.paint((ctx, w, h) => {
      ctx.fillStyle = '#F6F7F8'; ctx.fillRect(0, 0, w, h)
      ctx.fillStyle = '#17202A'
      ctx.font = `600 ${Math.round(w * 0.075)}px "Noto Sans CJK SC", sans-serif`
      ctx.fillText('文件传输助手', w * 0.075, h * 0.09)
      ctx.fillStyle = '#5D6D7E'
      ctx.font = `400 ${Math.round(w * 0.05)}px "Noto Sans CJK SC", sans-serif`
      ctx.fillText('我到家了', w * 0.075, h * 0.30)
    })

    // ---- the vortex: points on a spiral, animated entirely in the vertex shader ----
    const COUNT = 26000
    const pos = new Float32Array(COUNT * 3)
    const seed = new Float32Array(COUNT)
    const rnd = mulberry32(20261001)
    for (let i = 0; i < COUNT; i++) {
      const r = 0.25 + Math.pow(rnd(), 0.7) * 3.1
      const a = rnd() * Math.PI * 2
      pos[i * 3] = Math.cos(a) * r
      pos[i * 3 + 1] = (rnd() - 0.5) * 2.4 * (1 - r / 3.6)
      pos[i * 3 + 2] = Math.sin(a) * r
      seed[i] = rnd()
    }
    const geo = new THREE.BufferGeometry()
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3))
    geo.setAttribute('aSeed', new THREE.BufferAttribute(seed, 1))
    const vortexMat = new THREE.ShaderMaterial({
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
      uniforms: { uTime: { value: 0 }, uAmt: { value: 0 }, uSpin: { value: 1 } },
      vertexShader: /* glsl */`
        attribute float aSeed; uniform float uTime, uAmt, uSpin; varying float vG;
        void main(){
          vec3 p = position;
          float r = length(p.xz);
          float a = atan(p.z, p.x) + uTime * (0.55 + 0.5 / (r + 0.5)) * uSpin;
          p.xz = vec2(cos(a), sin(a)) * r * (1.0 - 0.10 * uAmt);
          p.y += sin(uTime * 0.8 + aSeed * 12.0) * 0.12;
          vec4 mv = modelViewMatrix * vec4(p, 1.0);
          gl_Position = projectionMatrix * mv;
          gl_PointSize = (2.0 + aSeed * 2.6) * (7.0 / -mv.z);
          vG = mix(0.45, 1.0, smoothstep(-14.0, -3.0, mv.z)) * (0.45 + 0.55 * aSeed);
        }`,
      fragmentShader: /* glsl */`
        uniform float uAmt; varying float vG;
        void main(){
          float d = length(gl_PointCoord - 0.5) * 2.0;
          float core = 1.0 - smoothstep(0.0, 1.0, d);
          float halo = pow(core, 3.0);
          vec3 col = mix(vec3(0.07,0.38,0.31), vec3(0.10,0.50,0.42), vG) * (0.6 + 1.3 * vG);
          col += vec3(0.35,0.85,0.75) * halo * 0.5;
          float a = (core * 0.5 + halo * 0.9) * vG * uAmt;
          if (a < 0.002) discard;
          gl_FragColor = vec4(col, a);
        }`,
    })
    const vortex = new THREE.Points(geo, vortexMat)
    group.add(vortex)

    // ---- the observation band: labels riding the same spiral ----
    const band = []
    for (let i = 0; i < BAND.length; i++) {
      const lab = makeLabel(BAND[i], { px: 30, weight: 500, color: '#BDF3E4', layer: LAYER_UI })
      lab.material.opacity = 0
      group.add(lab)
      band.push(lab)
    }

    // ---- the tools the cloud hands back ----
    const tools = TOOLS.map((name) => {
      const chip = makeCard(0.86, 0.26, 0.92)
      const lab = makeLabel(name, { px: 30, weight: 700, color: '#123A30', layer: LAYER_UI })
      lab.position.z = 0.002
      chip.add(lab)
      chip.visible = false
      group.add(chip)
      return chip
    })

    group.userData = { set, phone, vortex, band, tools }
    stage.userData.vortex = group
  },

  enter(stage) {
    const g = stage.userData.vortex
    g.visible = true
    stage.renderer.setClearColor(INK_BG, 1)
  },

  update(stage, local, t) {
    const g = stage.userData.vortex
    const { set, phone, vortex, band, tools } = g.userData
    const B = BEATS
    set.userData.update?.(t)

    // camera: a long pull back from the device, then an orbit half way around the cloud
    const d0 = distanceFor(stage, 2.0, 0.78)
    const out = ramp(local, B.pullOut, B.pullDur, EASE.inOut)
    const d = d0 + 2.5 * out
    const ang = 0.30 * Math.sin(local * 0.16) + ramp(local, B.toolsOut, 14.0, EASE.inOut) * 0.55
    const rise = -0.05 + 0.55 * ramp(local, B.bandIn, 12.0, EASE.inOut)
    stage.camera.position.set(Math.sin(ang) * d, rise, Math.cos(ang) * d)
    stage.camera.lookAt(0, 0.02, 0)

    // the device shrinks to a point at the centre and gives way to the cloud
    const shrink = 1 - 0.92 * out
    phone.scale.setScalar(Math.max(0.02, shrink))
    phone.userData.setFrameOpacity(1 - out)
    phone.userData.surface.material.opacity = (0.12 + 0.88) * (1 - out)
    phone.userData.rim.material.opacity = 0.10 * (1 - out)

    vortexMatU(vortex, t, ramp(local, B.pullOut, 1.6, EASE.out), 1)

    // the band winds in: each label rides the spiral, fading as it is absorbed
    for (let i = 0; i < band.length; i++) {
      const at = B.bandIn + i * 0.85
      const k = ramp(local, at, 1.4, EASE.out)
      const absorbed = ramp(local, at + 2.2, 1.6, EASE.inOut)
      const u = 0.15 + 0.8 * Math.min(1, (local - at) / 6.0)
      const ang = u * Math.PI * 4.0 + t * 0.5
      const r = 2.4 * (1 - absorbed) + 0.35
      band[i].position.set(Math.cos(ang) * r, 1.2 - u * 2.0 + i * 0.05, Math.sin(ang) * r)
      band[i].quaternion.copy(stage.camera.quaternion)
      band[i].material.opacity = k * (1 - absorbed) * 0.95
    }

    // the tools come back out, one at a time, and act on the (now tiny) device
    for (let i = 0; i < tools.length; i++) {
      const at = B.toolsOut + i * B.toolsStep
      const k = ramp(local, at, 1.1, EASE.out)
      const gone = ramp(local, at + 2.6, 1.2, EASE.inOut)
      const ang = -0.5 + i * 0.5
      const r = 1.0 + 0.9 * k - 0.6 * gone
      tools[i].visible = k > 0.01
      tools[i].position.set(Math.cos(ang) * r, 0.35 - i * 0.30, Math.sin(ang) * r)
      tools[i].scale.set(0.9 + 0.1 * k, 0.9 + 0.1 * k, 1)
      setGroupOpacity(tools[i], k * (1 - gone))
    }
  },

  teardown(stage) {
    const g = stage.userData.vortex
    if (g) g.visible = false
  },
})

function vortexMatU(vortex, t, amt, spin) {
  const u = vortex.material.uniforms
  u.uTime.value = t
  u.uAmt.value = amt
  u.uSpin.value = spin
}
