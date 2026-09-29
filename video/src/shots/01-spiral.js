// Shot 1 — 开机 · 螺旋成形 (0:00–0:06)
//
// One image for the whole premise: loose sparks are pulled into a structure.
// The spiral is the shape because the project is a loop (observe → plan → act →
// observe), and a loop drawn as a rising line looks like progress rather than a
// badge. Each of the twelve nodes is a task the project has actually run on a
// real phone, so the opening image is the evidence list.
//
// The shot is TWO BEATS, not one crowded frame:
//   0.4–2.6s  the coil assembles out of the particle cloud
//   2.0–4.3s  twelve task labels light up on the coil  (the evidence)
//   3.9–4.4s  labels withdraw
//   4.25–6.0s the wordmark fades in, centred on the coil (the name)
//
// Sequencing the two text layers is the fix for what four rounds of spatial
// nudging could not solve. Text over text is unreadable at any size; text over
// a light sculpture is fine. No arrangement of a full-height helix and a 150px
// wordmark fits in one 1080p frame side by side — but they never need to
// coexist.

import * as THREE from 'three'
import { defineShot } from '../stage.js'
import { makeTextTexture } from '../text.js'
import { NOISE, COMMON } from '../glsl.js'
import { makeSparks, sampleGlyphPoints, coilPoint } from '../props.js'
import { smooth01, easeInOut } from '../ease.js'

const NODES = [
  { label: '课表' },
  { label: '外卖' },
  { label: '快递' },
  { label: '火车票' },
  { label: '微信填字' },
  { label: '发消息' },
  { label: '总结动态' },
  { label: '拒绝付款码' },
  { label: '调大字体' },
  { label: '取消支付' },
  { label: '问老人' },
  { label: '找家人' },
]

const PARTICLES = 36000

// Shape parameters live at module scope, not inside build(): update() needs
// them to frame the camera, and a const inside build() is invisible there.
const TURNS = 1.5
const HEIGHT = 3.6
const GROUP_SCALE = 0.85
const GROUP_Y = 0.10

// The beat sheet. Exported so the storyboard doc and the shot cannot drift.
export const BEATS = {
  assembleStart: 0.40, assembleEnd: 2.60,
  labelStart: 2.00, labelStagger: 0.14, labelFade: 0.45,
  labelOutStart: 3.90, labelOutDur: 0.50,
  titleIn: 4.25, titleInDur: 0.85,
  subIn: 4.55, subInDur: 0.85,
  // The handoff: the title comes apart into sparks that land on the coil. Shot 2 opens with
  // those same sparks (same formula, same seed) and gathers them into the phone, so the cut
  // lands mid-motion instead of between two unrelated images.
  dissolveStart: 5.15, dissolveDur: 0.80,
}

export const shotSpiral = defineShot({
  name: 'spiral',
  start: 0,
  duration: 6,

  build(stage) {
    const g = new THREE.Group()
    // The coil sits centred; the wordmark is drawn over it once the labels have
    // gone. Displacing the coil to make room was the earlier, wrong approach.
    g.position.y = GROUP_Y
    g.scale.setScalar(GROUP_SCALE)
    stage.scene.add(g)
    g.visible = false
    g.userData.type = 'spiral'

    const spiralPoint = (u) => {
      const ang = u * Math.PI * 2 * TURNS
      const rad = 0.50 + u * 1.15
      return new THREE.Vector3(Math.cos(ang) * rad, (u - 0.5) * HEIGHT, Math.sin(ang) * rad)
    }

    // --- the sparks
    const positions = new Float32Array(PARTICLES * 3)
    const targets = new Float32Array(PARTICLES * 3)
    const seeds = new Float32Array(PARTICLES)
    const rels = new Float32Array(PARTICLES)

    for (let i = 0; i < PARTICLES; i++) {
      const u = Math.pow(Math.random(), 0.65)
      const p = spiralPoint(u)
      // Scatter around the spine. This number IS the shot: at 0.055 the helix
      // collapses into a 1-pixel filament and additive blending saturates it to
      // a white scratch. At 0.9 the spine disappears inside the cloud.
      const j = 0.30 * (1 - u * 0.45)
      targets[i * 3 + 0] = p.x + (Math.random() - 0.5) * j
      targets[i * 3 + 1] = p.y + (Math.random() - 0.5) * j
      targets[i * 3 + 2] = p.z + (Math.random() - 0.5) * j

      const theta = Math.random() * Math.PI * 2
      const phi = Math.acos(2 * Math.random() - 1)
      const r = 5.5 + Math.random() * 3.5
      positions[i * 3 + 0] = r * Math.sin(phi) * Math.cos(theta)
      positions[i * 3 + 1] = r * Math.cos(phi) * 0.6
      positions[i * 3 + 2] = r * Math.sin(phi) * Math.sin(theta)

      seeds[i] = Math.random()
      rels[i] = u
    }

    const geo = new THREE.BufferGeometry()
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
    geo.setAttribute('aTarget', new THREE.BufferAttribute(targets, 3))
    geo.setAttribute('aSeed', new THREE.BufferAttribute(seeds, 1))
    geo.setAttribute('aU', new THREE.BufferAttribute(rels, 1))

    const mat = new THREE.ShaderMaterial({
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
      uniforms: {
        uTime: { value: 0 },
        uAssemble: { value: 0 },
        uOpacity: { value: 0 },
        uBrand: { value: new THREE.Color(0x1A7F6B) },
        uDeep: { value: new THREE.Color(0x12604F) },
      },
      vertexShader: /* glsl */`
        attribute vec3 aTarget;
        attribute float aSeed;
        attribute float aU;
        uniform float uTime, uAssemble;
        varying float vGlow;
        varying float vSeed;
        varying float vDrift;
        ${NOISE}
        void main() {
          // Stagger: inner turns land first, so the coil grows outward from the
          // middle rather than all at once.
          float stagger = clamp((uAssemble - aU * 0.45) / 0.55, 0.0, 1.0);
          float ease = stagger * stagger * (3.0 - 2.0 * stagger);

          vec3 pos = mix(position, aTarget, ease);

          // A curl of noise while in flight, dying out as they arrive.
          float curl = (1.0 - ease) * 0.55;
          pos += vec3(
            snoise(pos * 0.6 + uTime * 0.25 + aSeed * 10.0),
            snoise(pos * 0.6 + uTime * 0.25 + aSeed * 10.0 + 31.7),
            snoise(pos * 0.6 + uTime * 0.25 + aSeed * 10.0 + 71.3)
          ) * curl;

          // How far this particle still is from its resting place, 0..1.
          vDrift = 1.0 - ease;

          vec4 mv = modelViewMatrix * vec4(pos, 1.0);
          gl_Position = projectionMatrix * mv;
          gl_PointSize = (3.0 + aSeed * 3.0) * (7.0 / -mv.z);

          // Depth as a gentle modulation, never a mask: read as a mask, every
          // particle at this camera distance landed near the low end and the
          // whole helix quantised away under the fragment discard threshold.
          vGlow = mix(0.55, 1.0, smoothstep(-14.0, -4.0, mv.z)) * (0.55 + 0.45 * aSeed);
          vSeed = aSeed;
        }
      `,
      fragmentShader: /* glsl */`
        uniform float uOpacity;
        uniform vec3 uBrand, uDeep;
        varying float vGlow;
        varying float vSeed;
        varying float vDrift;
        ${COMMON}
        void main() {
          float d = length(gl_PointCoord - 0.5) * 2.0;
          float core = 1.0 - smoothstep(0.0, 1.0, d);
          float halo = pow(core, 3.0);

          vec3 col = mix(uDeep, uBrand, vSeed) * (0.6 + 1.4 * vGlow);
          col += vec3(0.30, 0.80, 0.70) * halo * 0.55;

          // Visible in flight but faint: a straggler should read as a spark
          // travelling, not as part of the structure.
          float arrival = mix(0.22, 1.0, 1.0 - vDrift);
          float alpha = (core * 0.55 + halo * 0.9) * vGlow * arrival * uOpacity;
          if (alpha < 0.0015) discard;
          gl_FragColor = vec4(col, alpha);
        }
      `,
    })

    const points = new THREE.Points(geo, mat)
    g.add(points)
    g.userData.points = points

    // --- the twelve task nodes: a bead, a halo, and a label.
    //
    // Explicit lists. Iterating the group's children and guessing what each
    // object is produced exactly the bug that first made this shot render as a
    // solid background colour.
    const beads = new THREE.Group()
    g.add(beads)
    const beadList = [], haloList = [], labelList = []

    const beadGeo = new THREE.SphereGeometry(0.035, 20, 16)
    const haloGeo = new THREE.PlaneGeometry(0.42, 0.42)

    for (let i = 0; i < NODES.length; i++) {
      const u = i / (NODES.length - 1)
      const pos = spiralPoint(u)

      const bead = new THREE.Mesh(beadGeo, new THREE.MeshBasicMaterial({ color: 0x9FF3DC }))
      bead.position.copy(pos)
      bead.userData.appearAt = BEATS.labelStart + i * BEATS.labelStagger
      bead.userData.base = pos.clone()
      beads.add(bead)
      beadList.push(bead)

      const halo = new THREE.Mesh(haloGeo, new THREE.MeshBasicMaterial({
        map: haloTex(), transparent: true, depthWrite: false,
        blending: THREE.AdditiveBlending, color: 0x1A7F6B, opacity: 0,
      }))
      halo.position.copy(pos)
      beads.add(halo)
      haloList.push(halo)

      // Sized in world units chosen so the on-screen text lands near the 40px
      // floor the storyboard sets. Scaling by width/900 rendered at about 13px
      // — readable in the source, invisible on screen.
      const tex = makeTextTexture({ text: NODES[i].label, size: 44, weight: 500, color: '#D8FFF4' })
      const label = new THREE.Mesh(
        new THREE.PlaneGeometry(1, 1 / tex.userData.aspect),
        new THREE.MeshBasicMaterial({ map: tex, transparent: true, opacity: 0, depthWrite: false })
      )
      label.scale.set(0.62, 0.62, 1)
      // Pushed away from the axis so a label never crosses the spine.
      const out = new THREE.Vector3(pos.x, 0, pos.z).normalize().multiplyScalar(0.95)
      label.position.set(pos.x + out.x, pos.y, pos.z + out.z)
      beads.add(label)
      labelList.push(label)
    }

    g.userData.beadList = beadList
    g.userData.haloList = haloList
    g.userData.labelList = labelList

    // --- the wordmark, added to the SCENE rather than to `g`.
    //
    // Nesting them inside the coil group meant their position and scale were
    // multiplied by the group transform, so every adjustment moved the title by
    // an amount I could not predict — several rounds of "move it down a bit"
    // that all landed wrong. Independent transforms.
    const titleTex = makeTextTexture({
      text: '银龄智办', size: 150, weight: 700, color: '#FFFFFF', letterSpacing: 6,
    })
    const title = new THREE.Mesh(
      new THREE.PlaneGeometry(1, 1 / titleTex.userData.aspect),
      new THREE.MeshBasicMaterial({ map: titleTex, transparent: true, opacity: 0, depthWrite: false })
    )
    title.scale.set(3.4, 3.4, 1)
    title.position.set(0, 0.16, 1.2)
    stage.scene.add(title)
    g.userData.title = title

    // --- the title's own glyphs, as sparks, so the title can become the next shot's material.
    const SPARKS = 4200
    const titleW = title.scale.x
    const titleH = title.scale.x / titleTex.userData.aspect
    const glyphs = sampleGlyphPoints(titleTex, { count: SPARKS, seed: 11 })
    const sparks = makeSparks({
      count: SPARKS,
      seed: 11,
      spread: 0.035,
      size: 4.2,
      from: (i) => [
        title.position.x + (glyphs[i][0] - 0.5) * titleW,
        title.position.y + (0.5 - glyphs[i][1]) * titleH,
        title.position.z,
      ],
      to: (i) => {
        const p = coilPoint(i / SPARKS)
        return [p.x * GROUP_SCALE, GROUP_Y + p.y * GROUP_SCALE, p.z * GROUP_SCALE]
      },
    })
    stage.scene.add(sparks)
    g.userData.sparks = sparks
    g.userData.sparkMat = sparks.userData.mat

    const subTex = makeTextTexture({
      text: '可信跨应用助老智能体', size: 44, weight: 400, color: '#7FE3C8', letterSpacing: 8,
    })
    const sub = new THREE.Mesh(
      new THREE.PlaneGeometry(1, 1 / subTex.userData.aspect),
      new THREE.MeshBasicMaterial({ map: subTex, transparent: true, opacity: 0, depthWrite: false })
    )
    sub.scale.set(1.55, 1.55, 1)
    sub.position.set(0, -0.52, 1.2)
    stage.scene.add(sub)
    g.userData.sub = sub

    stage.spiralGroup = g
  },

  enter(stage) {
    stage.spiralGroup.visible = true
  },

  update(stage, local, t) {
    const g = stage.spiralGroup
    const p = g.userData.points
    const u = p.material.uniforms
    const B = BEATS

    u.uTime.value = t
    u.uAssemble.value = clamp01((local - B.assembleStart) / (B.assembleEnd - B.assembleStart))
    u.uOpacity.value = smooth01(local, 0, 0.7)

    // The labels have their own life: in staggered, out together, both gone
    // before the wordmark arrives.
    const labelOut = 1 - smooth01(local, B.labelOutStart, B.labelOutDur)
    for (let i = 0; i < g.userData.beadList.length; i++) {
      const bead = g.userData.beadList[i]
      const halo = g.userData.haloList[i]
      const label = g.userData.labelList[i]
      const at = bead.userData.appearAt
      const k = smooth01(local, at, B.labelFade) * labelOut

      bead.scale.setScalar(0.4 + k * 0.6)
      bead.visible = k > 0.001

      // The halo flies in larger than its resting size and settles.
      halo.material.opacity = k * 0.5 * (0.85 + 0.15 * Math.sin(t * 3 + at * 9))
      halo.position.copy(bead.userData.base)
      halo.quaternion.copy(stage.camera.quaternion)
      halo.scale.setScalar((0.7 + k * 0.5 + 0.06 * Math.sin(t * 2.2 + at * 7)) * (2.0 - k))

      label.material.opacity = k * 0.95
      label.quaternion.copy(stage.camera.quaternion)
    }

    // The wordmark beat, then its exit: the plane gives way to its own sparks. The plane fades
    // slightly faster than the sparks arrive, so no frame shows both at full strength.
    const dissolve = smooth01(local, B.dissolveStart, B.dissolveDur)
    const planeGone = smooth01(local, B.dissolveStart, B.dissolveDur * 0.55)
    g.userData.title.material.opacity = smooth01(local, B.titleIn, B.titleInDur) * (1 - planeGone)
    g.userData.sub.material.opacity = smooth01(local, B.subIn, B.subInDur) * (1 - planeGone)
    if (g.userData.sparkMat) {
      const u = g.userData.sparkMat.uniforms
      u.uMorph.value = dissolve
      u.uTime.value = t
      u.uOpacity.value = smooth01(local, B.dissolveStart - 0.25, 0.45)
    }

    // --- camera: a slow push in from three-quarters.
    //
    // Not down the spiral axis: that collapses the helix into a single line,
    // which is what the very first render of this shot looked like. The framing
    // is derived from the object's own size rather than guessed — the helix
    // stands HEIGHT tall (times GROUP_SCALE), and at fov 38 the visible
    // half-height at distance d is d*tan(19deg).
    const k = easeInOut(local / 6)
    const halfH = (HEIGHT * GROUP_SCALE / 2 + 1.0) / Math.tan(THREE.MathUtils.degToRad(stage.camera.fov / 2))
    const dist = halfH * 1.05 - 0.9 * k
    const swing = -0.42 + 0.22 * k
    stage.camera.position.set(
      Math.sin(swing) * dist,
      GROUP_Y + 0.30 + 0.20 * Math.sin(local * 0.22),
      Math.cos(swing) * dist,
    )
    stage.camera.lookAt(0, GROUP_Y, 0)

    // The background lifts off black as the coil takes shape.
    stage.renderer.setClearColor(
      new THREE.Color(0x0e1418).multiplyScalar(0.25 + 0.75 * u.uOpacity.value), 1)
  },

  teardown(stage) {
    stage.spiralGroup.visible = false
  },

  labels: ['开机 · 螺旋成形'],
})

const clamp01 = (x) => Math.max(0, Math.min(1, x))

let _halo = null
function haloTex() {
  if (_halo) return _halo
  const size = 128
  const c = document.createElement('canvas')
  c.width = c.height = size
  const ctx = c.getContext('2d')
  const grd = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2)
  grd.addColorStop(0, 'rgba(255,255,255,1)')
  grd.addColorStop(0.25, 'rgba(255,255,255,0.45)')
  grd.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = grd
  ctx.fillRect(0, 0, size, size)
  _halo = new THREE.CanvasTexture(c)
  return _halo
}
