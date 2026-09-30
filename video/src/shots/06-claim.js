// Ⅳ 办成有证据 (1:35–2:05, 30s)
//
// The film's turn: a claim lands, three mechanical checks ask whether it could be true, and
// the claim breaks. What is left is a smaller, honest sentence — and a read-only answer that
// passes the same checks untouched. The gratings are the film's identity: everyone else films
// how strong the inference is; this films where the constraint bites.

import * as THREE from 'three'
import { defineShot } from '../stage.js'
import {
  makeSet, makeLabel, makeCard, gridTexture, mulberry32,
  distanceFor, setGroupOpacity, BRAND, PROBLEM, GOOD, ATTENTION, INK_BG, LAYER_UI,
} from '../props.js'
import { EASE, ramp, settle } from '../ease.js'

export const BEATS = {
  cardIn: 0.6, cardDur: 3.2,        // the claim flies out of the cloud and turns to face us
  backIn: 4.2, backDur: 1.6,        // its back: the actions this run actually performed
  gate1: 8.0, gate2: 10.4, gate3: 12.8, gateDur: 0.9,
  crackAt: 14.2, crackDur: 1.2,
  breakAt: 16.0, breakDur: 2.6,     // the card comes apart along its seams
  honestAt: 19.4, honestDur: 1.4,
  cleanIn: 23.0, cleanDur: 3.0,     // a read-only answer passes the same gates, untouched
}

const CLAIM = '已帮您把消息发出去了：微信的「文件传输助手」里已经有一条您发出的「我到家了」，时间是 17:37，发送成功。'
const ACTIONS = ['open_app', 'wait', 'tap_xy 0.45 0.593', 'screenshot']
const GATES = [
  { t: '文字来源', y: 0.52 },
  { t: '时间锚点', y: 0.00 },
  { t: '改变类动作', y: -0.52 },
]

export const shotClaim = defineShot({
  name: 'claim',
  start: 95,
  duration: 30,

  chapter: { num: 'Ⅳ', zh: '办成有证据', en: 'EVIDENCE OF DONE' },
  subs: [
    [2.4, 6.6, '有一类错最伤人：明明没办成，却说办成了', 'The worst failure is a claim that is not true.'],
    [6.8, 10.6, '它先问自己三件事', 'It asks itself three questions.'],
    [10.8, 14.6, '这句话里的字，是这一轮打进去的吗？', 'Were these words typed in this run?'],
    [14.8, 18.6, '提到的时间，早于开始办事的时刻吗？', 'Is the time older than the run itself?'],
    [18.8, 23.0, '这一轮，有没有做过能改变外界的动作？', 'Did anything actually change?'],
    [23.2, 28.0, '说不成，也做不成', 'It cannot claim what it did not do.'],
  ],
  sfx: [[0.6, 'braam', { dur: 2.4 }], [4.2, 'glass', { midi: 74 }],
    [8.0, 'tick'], [10.4, 'tick'], [12.8, 'tick'],
    [16.0, 'boom'], [17.0, 'glass', { midi: 88 }],
    [19.4, 'chime', { midi: 81 }], [23.0, 'ding', { midi: 86 }]],
  mb: 4,

  build(stage) {
    const group = new THREE.Group()
    group.visible = false
    stage.scene.add(group)
    const set = makeSet()
    group.add(set)

    // ---- the claim card -------------------------------------------------------------
    const card = new THREE.Group()
    group.add(card)

    const plate = makeCard(1.66, 1.06, 0.94)
    card.add(plate)

    const claimLabel = makeLabel(CLAIM, {
      px: 40, weight: 700, color: '#17202A', layer: LAYER_UI, worldHeight: null,
      align: 'center', maxWidth: 1.36,
    })
    // makeLabel does not wrap; split by hand into two lines for the card
    const claimA = makeLabel('已帮您把消息发出去了：', { px: 40, weight: 700, color: '#17202A', layer: LAYER_UI })
    const claimB = makeLabel('「我到家了」· 17:37 · 发送成功', { px: 40, weight: 700, color: '#17202A', layer: LAYER_UI })
    claimA.position.set(0, 0.18, 0.003)
    claimB.position.set(0, -0.18, 0.003)
    card.add(claimA, claimB)
    claimLabel.visible = false

    // the back: what the run actually did, in mono
    const back = new THREE.Group()
    back.position.z = -0.004
    back.rotation.y = Math.PI
    card.add(back)
    // The other face needs its own plate: a single-sided card vanishes the moment it turns
    // to show its back — which is exactly the moment this shot is about.
    const backPlate = makeCard(1.66, 1.06, 0.94)
    back.add(backPlate)
    const backActs = ACTIONS.map((a, i) => {
      const l = makeLabel(a, { px: 30, weight: 400, color: '#5D6D7E', layer: LAYER_UI })
      l.position.set(0, 0.30 - i * 0.20, 0)
      l.material.opacity = 0
      back.add(l)
      return l
    })

    // ---- three gratings ------------------------------------------------------------
    const gratings = []
    for (let i = 0; i < 3; i++) {
      const g = new THREE.Mesh(
        new THREE.PlaneGeometry(2.6, 1.7),
        new THREE.MeshBasicMaterial({
          map: gratingTexture(), transparent: true, depthWrite: false,
          blending: THREE.AdditiveBlending, color: 0x9FD8FF, opacity: 0,
        }),
      )
      g.position.z = 0.02
      g.renderOrder = 2
      group.add(g)
      gratings.push(g)
    }
    const gateLabels = GATES.map((gt) => {
      const l = makeLabel(gt.t, { px: 30, weight: 700, color: '#BFE9FF', layer: LAYER_UI })
      l.position.set(-1.30, gt.y, 0.05)
      l.material.opacity = 0
      group.add(l)
      return l
    })

    // ---- shards, hidden until the card breaks ---------------------------------------
    const shards = []
    const rnd = mulberry32(4242)
    for (let i = 0; i < 54; i++) {
      const s = new THREE.Mesh(
        new THREE.PlaneGeometry(0.16 + rnd() * 0.10, 0.14 + rnd() * 0.10),
        new THREE.MeshBasicMaterial({
          color: 0xEDF3F4, transparent: true, depthWrite: false,
          blending: THREE.AdditiveBlending, opacity: 0,
        }),
      )
      const u = rnd(), v = rnd()
      s.userData = { x0: (u - 0.5) * 1.6, y0: (v - 0.5) * 1.0, vx: (u - 0.5) * 1.6, vy: (v - 0.5) * 1.2 + 0.4, r: (rnd() - 0.5) * 6 }
      group.add(s)
      shards.push(s)
    }

    // ---- the honest sentence, and the read-only answer -------------------------------
    const honest = new THREE.Group()
    group.add(honest)
    const honestCard = makeCard(1.40, 0.62, 0.06)
    honestCard.userData.plate.material.color.setHex(0x0E1418)
    honest.add(honestCard)
    const honestText = makeLabel('我没法确认这件事真的办成了', { px: 40, weight: 700, color: '#FFD9A8', layer: LAYER_UI })
    honestText.position.z = 0.003
    honest.add(honestText)
    const honestSub = makeLabel('接着办', { px: 30, weight: 500, color: '#8FA3AD', layer: LAYER_UI })
    honestSub.position.set(0, -0.30, 0.003)
    honest.add(honestSub)

    const clean = new THREE.Group()
    clean.visible = false
    group.add(clean)
    const cleanCard = makeCard(1.30, 0.66, 0.95)
    clean.add(cleanCard)
    const cleanText = makeLabel('明天（9月30日 周三）的课表：离散数学、计算机操作基础', { px: 30, weight: 500, color: '#17202A', layer: LAYER_UI })
    cleanText.visible = false
    const cleanLine1 = makeLabel('明天（9月30日 周三）的课表', { px: 32, weight: 700, color: '#17202A', layer: LAYER_UI })
    const cleanLine2 = makeLabel('离散数学 · 计算机操作基础', { px: 30, weight: 500, color: '#5D6D7E', layer: LAYER_UI })
    cleanLine1.position.set(0, 0.12, 0.003)
    cleanLine2.position.set(0, -0.14, 0.003)
    clean.add(cleanLine1, cleanLine2)

    // nothing on this card may be culled by the flip
    card.traverse((o) => { if (o.material) o.material.side = THREE.DoubleSide })
    for (const l of backActs) l.material.side = THREE.DoubleSide
    clean.traverse((o) => { if (o.material) o.material.side = THREE.DoubleSide })

    group.userData = { set, card, plate, backPlate, claimA, claimB, backActs, gratings, gateLabels, shards, honest, honestCard, honestText, honestSub, clean, cleanCard }
    stage.userData.claim = group
  },

  enter(stage) {
    const g = stage.userData.claim
    g.visible = true
    stage.renderer.setClearColor(INK_BG, 1)
  },

  update(stage, local, t) {
    const g = stage.userData.claim
    const { set, card, claimA, claimB, backActs, gratings, gateLabels, shards, honest, honestText, honestSub, clean, cleanCard } = g.userData
    const B = BEATS
    set.userData.update?.(t)

    // camera: push in from the cloud, then crane down as the card falls, then rise with the
    // honest sentence. One continuous move, no shake.
    const d = distanceFor(stage, 1.75, 0.80) + 1.5 * (1 - ramp(local, B.cardIn, B.cardDur + 1.0, EASE.out))
    const crane = ramp(local, B.breakAt, B.breakDur + 4.0, EASE.inOut)
    stage.camera.position.set(Math.sin(0.10) * d, 0.10 - 0.55 * crane, Math.cos(0.10) * d)
    stage.camera.lookAt(0, -0.05 - 0.25 * crane, 0)

    // ---- the card flies in from the centre of the cloud and turns to face us ----
    const inK = ramp(local, B.cardIn, B.cardDur, EASE.out)
    const flip = ramp(local, B.backIn, B.backDur, EASE.inOut)
    card.position.set(0, 0.10 + (1 - inK) * 0.35, 0.60 * (1 - inK) + 0.10 * Math.sin(flip * Math.PI))
    card.rotation.y = flip * Math.PI
    card.scale.setScalar(0.86 + 0.14 * inK)
    setGroupOpacity(card, inK)

    const backK = ramp(local, B.backIn, B.backDur, EASE.out)
    backActs.forEach((l, i) => { l.material.opacity = backK * Math.max(0, 1 - Math.abs(flip - 1) * 1.6) * (0.35 + 0.65 * Math.min(1, (local - B.backIn - i * 0.25) / 0.5)) })

    // ---- the three gates sweep the card; the third one cracks it ----
    const gateAt = [B.gate1, B.gate2, B.gate3]
    for (let i = 0; i < gratings.length; i++) {
      const age = local - gateAt[i]
      const alive = age > -0.2 && age < B.gateDur
      gratings[i].visible = alive
      if (!alive) continue
      const k = Math.max(0, Math.min(1, age / B.gateDur))
      gratings[i].position.y = 0.55 - k * 1.1
      gratings[i].material.opacity = Math.sin(Math.PI * k) * 0.55
    }
    for (let i = 0; i < gateLabels.length; i++) {
      const k = ramp(local, [B.gate1, B.gate2, B.gate3][i] + 0.25, 0.5, EASE.out)
      gateLabels[i].material.opacity = k * (1 - ramp(local, B.honestAt - 1.0, 1.4, EASE.inOut) * 0.5)
    }

    // ---- the break: the card's seams give way, and the pieces blow off as ash ----
    const brk = Math.max(0, local - B.breakAt)
    const brkK = Math.min(1, brk / B.breakDur)
    card.visible = brkK < 0.06
    for (const s of shards) {
      s.visible = brkK > 0
      if (brkK <= 0) continue
      const e = brkK * brkK
      s.position.set(s.userData.x0 + s.userData.vx * e * 1.5, 0.05 + s.userData.y0 + s.userData.vy * e - 0.35 * e * e, 0.10 + e * 0.6)
      s.rotation.z = s.userData.r * e
      s.material.opacity = (1 - brkK) * (1 - brkK) * 0.9
    }

    // ---- what is left: a smaller, honest sentence ----
    const hK = ramp(local, B.honestAt, B.honestDur, EASE.out)
    honest.visible = hK > 0.01
    honest.position.set(0, 0.05 - 0.5 * (1 - hK), 0.12)
    setGroupOpacity(honest, hK)
    honestText.position.y = 0.02
    honestSub.material.opacity = hK * 0.9

    // ---- a read-only answer goes through the same gates untouched, and lands clean ----
    const cK = ramp(local, B.cleanIn, B.cleanDur, EASE.out)
    clean.visible = cK > 0.01
    clean.position.set(0.0, -0.62 + 0.5 * cK, 0.16)
    setGroupOpacity(clean, cK)
    cleanCard.userData.plate.material.color.setHex(0xFFFFFF).lerp(new THREE.Color(GOOD), ramp(local, B.cleanIn + 1.6, 0.8, EASE.out))
  },

  teardown(stage) {
    const g = stage.userData.claim
    if (g) g.visible = false
  },
})

let _grating = null
function gratingTexture() {
  if (_grating) return _grating
  const c = document.createElement('canvas')
  c.width = 8
  c.height = 256
  const ctx = c.getContext('2d')
  for (let y = 0; y < 256; y++) {
    const v = y % 16 < 5 ? 1 : 0
    ctx.fillStyle = `rgba(255,255,255,${v * 0.5})`
    ctx.fillRect(0, y, 8, 1)
  }
  const g = ctx.createLinearGradient(0, 0, 0, 256)
  g.addColorStop(0, 'rgba(0,0,0,1)'); g.addColorStop(0.5, 'rgba(0,0,0,0)'); g.addColorStop(1, 'rgba(0,0,0,1)')
  ctx.globalCompositeOperation = 'destination-out'
  ctx.fillStyle = g
  ctx.fillRect(0, 0, 8, 256)
  _grating = new THREE.CanvasTexture(c)
  _grating.wrapS = _grating.wrapT = THREE.RepeatWrapping
  return _grating
}
