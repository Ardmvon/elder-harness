// Ⅱ 看得见页面 (0:40–1:05, 25s)
//
// Two claims, one image each: the machine can read the words on a screen (the accessibility
// tree), and when it cannot read them it still has the pixels (a screenshot and proportion
// coordinates). The cut after "blind" is the film's turn from the abstract to the concrete.

import * as THREE from 'three'
import { defineShot } from '../stage.js'
import {
  makePhone, makeSet, makeMirror, makeLabel, makeCard, gridTexture, glowTexture,
  distanceFor, setGroupOpacity, BRAND, ATTENTION, INK_BG, LAYER_UI,
} from '../props.js'
import { EASE, ramp } from '../ease.js'

export const BEATS = {
  treeIn: 1.6, treeStep: 0.28, treeDur: 1.1,        // nodes rise, lines follow
  blindFrom: 6.6, blindDur: 3.4,                     // uBlind 0 -> 1
  shotIn: 11.4, shotDur: 1.6,                        // screenshot slides out
  gridIn: 14.0, gridDur: 1.4,
  lockAt: 17.6, lockDur: 0.9,
}

const TREE = [
  { x: 0.00, y: 0.62, w: 0.42, t: '页面', lv: 0 },
  { x: -0.72, y: 0.22, w: 0.46, t: '标题栏', lv: 1 },
  { x: 0.00, y: 0.22, w: 0.52, t: '内容区', lv: 1 },
  { x: 0.72, y: 0.22, w: 0.46, t: '底部条', lv: 1 },
  { x: -0.72, y: -0.18, w: 0.40, t: '节次', lv: 2 },
  { x: 0.00, y: -0.18, w: 0.44, t: '日期', lv: 2 },
  { x: 0.72, y: -0.18, w: 0.40, t: '时间', lv: 2 },
  { x: -0.36, y: -0.52, w: 0.40, t: '课程名', lv: 3 },
  { x: 0.36, y: -0.52, w: 0.40, t: '教师', lv: 3 },
]

export const shotTree = defineShot({
  name: 'tree',
  start: 40,
  duration: 25,

  chapter: { num: 'Ⅱ', zh: '看得见页面', en: 'SEEING THE PAGE' },
  subs: [
    [3.2, 7.4, '屏幕上的字，机器能读出来', 'The words on screen can be read.'],
    [7.6, 12.4, '读不到的时候，还有截图', 'When it cannot read them, a screenshot answers.'],
    [12.6, 17.4, '比例坐标，按编号点准', 'Proportion coordinates, aimed by number.'],
    [17.6, 22.0, '键盘弹起时，也只是另外一层编号', 'When the keyboard rises, it is one more layer of numbers.'],
  ],
  sfx: [[1.4, 'whoosh', { dur: 1.2 }], [6.6, 'suck', { dur: 1.0 }], [7.0, 'rip'],
    [11.4, 'glass', { midi: 84 }], [14.0, 'tick'], [14.6, 'tick'], [15.2, 'tick'], [17.6, 'ding', { midi: 81 }]],
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

    // ---- the accessibility tree: glowing plates on three levels, joined by hairlines ----
    const tree = new THREE.Group()
    group.add(tree)
    const nodes = []
    const plateGeo = new THREE.PlaneGeometry(1, 0.20)
    for (let i = 0; i < TREE.length; i++) {
      const n = TREE[i]
      const plate = new THREE.Mesh(plateGeo, new THREE.MeshBasicMaterial({
        map: glowTexture(), transparent: true, depthWrite: false,
        blending: THREE.AdditiveBlending, color: 0x2E8F76, opacity: 0,
      }))
      plate.position.set(n.x, n.y, 0.30 + n.lv * 0.03)
      tree.add(plate)

      const label = makeLabel(n.t, { px: 34, weight: 500, color: '#D8FFF4', layer: LAYER_UI })
      label.position.set(n.x, n.y, 0.36 + n.lv * 0.03)
      label.material.opacity = 0
      tree.add(label)
      nodes.push({ n, plate, label })
    }
    // the links: a hairline from each node to its parent, drawn as a stretched additive quad
    const links = []
    for (let i = 1; i < TREE.length; i++) {
      const a = TREE[0]
      const b = TREE[i]
      const mid = new THREE.Vector3((a.x + b.x) / 2, (a.y + b.y) / 2, 0.28)
      const len = Math.hypot(b.x - a.x, b.y - a.y)
      const link = new THREE.Mesh(new THREE.PlaneGeometry(0.004, len), new THREE.MeshBasicMaterial({
        color: 0x39C39B, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, opacity: 0,
      }))
      link.position.copy(mid)
      link.rotation.z = Math.atan2(b.x - a.x, b.y - a.y)
      tree.add(link)
      links.push(link)
    }
    tree.userData = { nodes, links }

    // ---- the blind layer: a noise wash over the screen when the tree gives nothing ----
    const blindMat = new THREE.ShaderMaterial({
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
      uniforms: { uAmt: { value: 0 }, uTime: { value: 0 } },
      vertexShader: `varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }`,
      fragmentShader: /* glsl */`
        uniform float uAmt, uTime; varying vec2 vUv;
        float h(vec2 p){ return fract(sin(dot(p, vec2(127.1,311.7)))*43758.5453); }
        void main(){
          float n = h(floor(vUv * 220.0) + floor(uTime * 24.0));
          gl_FragColor = vec4(vec3(0.55,0.62,0.68) * n * 0.5, n * 0.34 * uAmt);
        }`,
    })
    const blind = new THREE.Mesh(new THREE.PlaneGeometry(height * 0.4543 * 0.955, height * 0.972), blindMat)
    blind.position.set(0, -0.10, 0.028)
    blind.layers.set(LAYER_UI)
    blind.renderOrder = 6
    group.add(blind)

    // ---- the screenshot: a matte card pulled out of the back, with a coordinate grid ----
    const shotGroup = new THREE.Group()
    shotGroup.position.set(1.35, -0.05, 0.45)
    group.add(shotGroup)
    const shotCard = makeCard(1.02, 1.72, 0.94)
    shotGroup.add(shotCard)
    const shotLabel = makeLabel('截图', { px: 30, weight: 500, color: '#5D6D7E', layer: LAYER_UI })
    shotLabel.position.set(0, 0.78, 0.002)
    shotGroup.add(shotLabel)

    const grid = new THREE.Mesh(
      new THREE.PlaneGeometry(1.02, 1.72),
      new THREE.MeshBasicMaterial({
        map: gridTexture({ cells: 6, color: '90,140,170', line: 2, vignette: 0.95 }),
        transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, color: 0x2F6B8C, opacity: 0,
      }),
    )
    grid.position.z = 0.004
    grid.renderOrder = 3
    shotGroup.add(grid)

    const lock = new THREE.Mesh(
      new THREE.PlaneGeometry(0.34, 0.16),
      new THREE.MeshBasicMaterial({ color: ATTENTION, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, opacity: 0 }),
    )
    lock.position.set(0.30, -0.52, 0.006)
    lock.renderOrder = 4
    shotGroup.add(lock)
    const lockLabel = makeLabel('k10', { px: 30, weight: 700, color: '#FFE0B8', layer: LAYER_UI })
    lockLabel.position.set(0.30, -0.40, 0.007)
    lockLabel.material.opacity = 0
    shotGroup.add(lockLabel)

    group.userData = { set, phone, mirror, tree, blind, shotGroup, grid, lock, lockLabel, shotLabel }
    stage.userData.tree = group
  },

  enter(stage) {
    const g = stage.userData.tree
    g.visible = true
    stage.renderer.setClearColor(INK_BG, 1)
  },

  update(stage, local, t) {
    const g = stage.userData.tree
    const { set, phone, tree, blind, shotGroup, grid, lock, lockLabel } = g.userData
    const B = BEATS
    set.userData.update?.(t)

    // camera: a slow orbit to the right and a rise, ending looking down at the screenshot
    const d = distanceFor(stage, 2.05, 0.74)
    const orb = ramp(local, 0.0, 25.0, EASE.inOut)
    const ang = -0.30 + 0.46 * orb
    const rise = -0.16 + 0.30 * ramp(local, 2.0, 16.0, EASE.inOut)
    stage.camera.position.set(Math.sin(ang) * d, rise, Math.cos(ang) * d)
    stage.camera.lookAt(0.10 * orb, -0.06, 0)

    const lit = ramp(local, 0, 1.2, EASE.out)
    phone.userData.setFrameOpacity(lit)
    phone.userData.surface.material.opacity = 0.12 + 0.88 * lit
    phone.userData.rim.material.opacity = 0.07 + 0.20 * lit
    if (!g.userData.painted) {
      g.userData.painted = true
      phone.userData.paint((ctx, w, h) => {
        ctx.fillStyle = '#F6F7F8'; ctx.fillRect(0, 0, w, h)
        ctx.fillStyle = '#17202A'
        ctx.font = `600 ${Math.round(w * 0.075)}px "Noto Sans CJK SC", sans-serif`
        ctx.fillText('我的课程', w * 0.075, h * 0.09)
        // rows of a timetable — the words that are NOT in the tree
        ctx.fillStyle = '#8A98A5'
        ctx.font = `500 ${Math.round(w * 0.055)}px "Noto Sans CJK SC", sans-serif`
        for (let r = 0; r < 6; r++) {
          ctx.fillText(['第 1-2 节', '第 3-4 节', '第 5-6 节'][r % 3], w * 0.075, h * (0.20 + r * 0.10))
          ctx.fillText(['离散数学', '计算机操作基础', '大学英语'][r % 3], w * 0.42, h * (0.20 + r * 0.10))
        }
      })
    }

    // tree: nodes rise in, then the whole tree collapses when the page goes blind
    const blindK = ramp(local, B.blindFrom, B.blindDur, EASE.inOut)
    blind.material.uniforms.uAmt.value = blindK
    blind.material.uniforms.uTime.value = t
    for (let i = 0; i < tree.userData.nodes.length; i++) {
      const { n, plate, label } = tree.userData.nodes[i]
      const at = B.treeIn + n.lv * 0.35 + (i % 3) * B.treeStep
      const k = ramp(local, at, B.treeDur, EASE.out) * (1 - blindK)
      plate.material.opacity = k * 0.5
      plate.scale.set(1, 1, 1)
      label.material.opacity = k * 0.95
      label.position.y = n.y - (1 - ramp(local, at, B.treeDur, EASE.out)) * 0.10
    }
    for (const link of tree.userData.links) link.material.opacity = (1 - blindK) * 0.5 * ramp(local, B.treeIn, B.treeDur, EASE.out)

    // screenshot card: in, gridded, then locked by number
    const shotK = ramp(local, B.shotIn, B.shotDur, EASE.out)
    shotGroup.position.x = 1.35 + (1 - shotK) * 0.55
    setGroupOpacity(shotGroup, shotK)
    grid.material.opacity = ramp(local, B.gridIn, B.gridDur, EASE.out) * 0.75
    const lockK = ramp(local, B.lockAt, B.lockDur, EASE.out)
    lock.material.opacity = lockK * 0.5
    lockLabel.material.opacity = lockK
    lock.scale.set(0.9 + 0.1 * lockK, 0.9 + 0.1 * lockK, 1)
  },

  teardown(stage) {
    const g = stage.userData.tree
    if (g) g.visible = false
  },
})
