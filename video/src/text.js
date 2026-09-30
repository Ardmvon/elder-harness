// Chinese text -> CanvasTexture.
//
// CJK is baked rather than drawn with an SDF font: a glyph atlas for a few
// hundred hanzi is megabytes of texture and a week of shaping work, while the
// canvas path is exact, uses the same Noto Sans CJK SC the Android app does,
// and can be compared 1:1 against a device screenshot. Only the abstract layer
// (particles, vortices, grating, fracture) belongs in GLSL.

import * as THREE from 'three'

const FAMILY = '"Noto Sans CJK SC", "Microsoft YaHei", sans-serif'

/** Canvas pixels per CSS pixel. 2 keeps text crisp when the plate is seen close. */
const SS = 2

export function makeTextTexture({
  text, size = 56, weight = 400, color = '#FFFFFF', lineHeight = 1.35,
  align = 'left', padding = 24, maxWidth = null, letterSpacing = 0,
  /** Font stack. Defaults to the app's sans; the film's chapter/subtitle voice is serif. */
  family = FAMILY,
  italic = false,
  /** Dark bloom behind the glyphs (CSS px), the reference film's subtitle legibility trick. */
  shadowColor = null,
  shadowBlur = 0,
}) {
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')
  const style = italic ? 'italic ' : ''
  const font = `${style}${weight} ${size * SS}px ${family}`

  // Measure first so the texture is exactly the size of the text: a canvas that
  // is mostly empty still costs memory and blurs when filtered.
  ctx.font = font
  if (letterSpacing) ctx.letterSpacing = `${letterSpacing * SS}px`
  const lines = wrap(ctx, text, maxWidth ? maxWidth * SS : Infinity)

  let w = 0
  for (const line of lines) w = Math.max(w, ctx.measureText(line).width)
  const lh = size * SS * lineHeight
  const h = lh * lines.length

  canvas.width = Math.ceil(w + padding * 2 * SS)
  canvas.height = Math.ceil(h + padding * 2 * SS)

  ctx.font = font
  if (letterSpacing) ctx.letterSpacing = `${letterSpacing * SS}px`
  ctx.fillStyle = color
  if (shadowColor) { ctx.shadowColor = shadowColor; ctx.shadowBlur = shadowBlur * SS }
  ctx.textBaseline = 'top'
  for (let i = 0; i < lines.length; i++) {
    const x = align === 'center' ? canvas.width / 2 : padding * SS
    ctx.textAlign = align === 'center' ? 'center' : 'left'
    ctx.fillText(lines[i], x, padding * SS + i * lh)
  }

  const tex = new THREE.CanvasTexture(canvas)
  tex.colorSpace = THREE.SRGBColorSpace
  tex.anisotropy = 8
  tex.minFilter = THREE.LinearMipmapLinearFilter
  tex.generateMipmaps = true
  tex.needsUpdate = true
  tex.userData = {
    aspect: canvas.width / canvas.height,
    width: canvas.width / SS,
    height: canvas.height / SS,
  }
  return tex
}

function wrap(ctx, text, maxWidth) {
  if (!Number.isFinite(maxWidth)) return text.split('\n')
  const out = []
  for (const para of text.split('\n')) {
    let line = ''
    for (const ch of para) {
      const test = line + ch
      if (ctx.measureText(test).width > maxWidth && line) {
        out.push(line); line = ch
      } else line = test
    }
    out.push(line)
  }
  return out
}

/** A subtitle plate pinned to the bottom of the frame, on the 2D overlay camera. */
export function subtitle({ text, sub = null, opacity = 1 }) {
  const group = new THREE.Group()

  const mainTex = makeTextTexture({ text, size: 56, weight: 600, color: '#FFFFFF', align: 'center' })
  const main = new THREE.Mesh(
    new THREE.PlaneGeometry(1, 1 / mainTex.userData.aspect),
    new THREE.MeshBasicMaterial({ map: mainTex, transparent: true, opacity, depthWrite: false })
  )
  const mainW = Math.min(2.6, mainTex.userData.width / 380)
  main.scale.set(mainW, mainW, 1)
  group.add(main)

  if (sub) {
    const subTex = makeTextTexture({ text: sub, size: 32, weight: 400, color: '#8FA3AD', align: 'center' })
    const subMesh = new THREE.Mesh(
      new THREE.PlaneGeometry(1, 1 / subTex.userData.aspect),
      new THREE.MeshBasicMaterial({ map: subTex, transparent: true, opacity: opacity * 0.85, depthWrite: false })
    )
    const subW = Math.min(2.2, subTex.userData.width / 380)
    subMesh.scale.set(subW, subW, 1)
    subMesh.position.y = -0.24
    group.add(subMesh)
    group.userData.height = 0.24 + subW / subTex.userData.aspect
  } else {
    group.userData.height = mainW / mainTex.userData.aspect
  }
  return group
}
