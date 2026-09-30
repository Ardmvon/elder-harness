// The stage: one scene, one camera rig, one clock.
//
// Everything in this film obeys a single rule — the picture at time T depends
// only on T. No `+=` velocities, no state carried between frames. That is what
// makes offline frame extraction and live preview produce the same film, and
// it is why every shot's update() takes t rather than dt.

import * as THREE from 'three'
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js'
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js'
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js'
import { ShaderPass } from 'three/addons/postprocessing/ShaderPass.js'
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js'
import { Presentation } from './presentation.js'
import { installLighting } from './lighting.js'

export class Stage {
  constructor({ width = 1920, height = 1080 } = {}) {
    this.width = width
    this.height = height

    this.renderer = new THREE.WebGLRenderer({
      antialias: true,
      alpha: false,
      powerPreference: 'high-performance',
      // Offline rendering wants the real thing: no context loss on long runs.
      preserveDrawingBuffer: false,
    })
    this.renderer.setSize(width, height, false)
    this.renderer.setPixelRatio(1)
    this.renderer.setClearColor(0x0e1418, 1)
    // Tone mapping and sRGB encoding are done OUR way, in the lens pass (see LENS_SHADER).
    // Letting three do it through OutputPass double-encoded everything: a mid grey came out
    // at 198 instead of 128, and the film's near-black background at 55 instead of 14. That
    // single mismatch is why the whole picture looked washed and flat.
    this.renderer.toneMapping = THREE.NoToneMapping
    this.renderer.toneMappingExposure = 1.0
    this.renderer.outputColorSpace = THREE.SRGBColorSpace
    document.body.appendChild(this.renderer.domElement)

    this.scene = new THREE.Scene()
    this.camera = new THREE.PerspectiveCamera(38, width / height, 0.1, 200)
    this.camera.position.set(0, 0, 6)

    // The subtitle/overlay camera: an orthographic view 2 units tall, so text
    // plates can be positioned in readable units instead of world units.
    const halfH = 1
    const halfW = (width / height) * halfH
    this.overlay = new THREE.Scene()
    this.overlayCam = new THREE.OrthographicCamera(-halfW, halfW, halfH, -halfH, -10, 10)
    this.overlayHalfW = halfW
    this.overlayHalfH = halfH

    // Chapter headings, bilingual subtitles, letterbox and hit flashes. Owned by the
    // stage (not by any shot) so a cut can never take the film's voice off screen.
    this.presentation = new Presentation(this)

    // Post. This is the difference between "a 3D render" and "a film": shot 1 looked finished
    // and shots 2-3 looked pasted on, and the missing chain was here — bloom to make the light
    // bleed, aberration and vignette to give the frame a lens, grain to stop the flat areas
    // from banding. UI plates opt out of tone mapping per material, so the screen and the
    // subtitles stay exactly the colour they were authored as.
    this.composer = new EffectComposer(this.renderer)
    this.composer.setSize(width, height)
    this.renderPass = new RenderPass(this.scene, this.camera)
    this.composer.addPass(this.renderPass)
    // Weak on purpose, and overridable from the URL (?bloom=0&haze=0) so a shot can be
    // diagnosed one layer at a time without editing code.
    const params = new URLSearchParams(globalThis.location?.search || '')
    const bloomStrength = Number(params.get('bloom') ?? 0.30)
    this.bloom = new UnrealBloomPass(new THREE.Vector2(width, height), bloomStrength, 0.62, 0.86)
    this.composer.addPass(this.bloom)
    this.lens = new ShaderPass(LENS_SHADER)
    this.composer.addPass(this.lens)   // last pass: tone maps and encodes to sRGB itself

    // ---- the backdrop ---------------------------------------------------------------
    //
    // A fullscreen quad, drawn first, that paints the background colour in LINEAR space.
    //
    // It exists because three writes the *clear* colour through
    // `getUnlitUniformColorSpace(renderer)`, and when the RenderPass clears its HalfFloat
    // target that path still lands on sRGB — so the background was written as sRGB into a
    // linear buffer and then encoded a second time. Measured: the designed #0E1418 came out
    // at (55,72,81), and #808080 came out at 198 instead of 128. A backdrop mesh goes through
    // the ordinary material path, so it is linear and correct, and the clear stops mattering.
    this.bgMat = new THREE.MeshBasicMaterial({ color: 0x0e1418, depthTest: false, depthWrite: false })
    this.bgMat.toneMapped = false
    this.bgQuad = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), this.bgMat)
    this.bgQuad.renderOrder = -10000
    this.bgQuad.frustumCulled = false
    this.scene.add(this.bgQuad)
    this._bgColor = new THREE.Color()

    // ---- motion blur (offline export only; the preview stays single-sample).
    //
    // Each sub-frame is rendered through the exact single-frame path (the one that has been
    // looked at for weeks), then averaged on a 2D canvas with `lighter` at 1/K, and the
    // average is uploaded back and the overlay drawn over it. Averaging the *finished*
    // frames instead of accumulating WebGL targets is a deliberate choice: three rewrites a
    // target's output colour space and blend state in ways that silently scaled the
    // accumulation to ~20% here, and a path whose output can be compared against the
    // single-frame render pixel for pixel is worth more than one that is theoretically
    // nicer. The blur is real (sub-frames at different times) and it cannot be wrong.
    this.fsGeom = new THREE.BufferGeometry()
    this.fsGeom.setAttribute('position', new THREE.Float32BufferAttribute([-1, -1, 0, 3, -1, 0, -1, 3, 0], 3))
    this.fsGeom.setAttribute('uv', new THREE.Float32BufferAttribute([0, 0, 2, 0, 0, 2], 2))
    this.fsCam = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)

    // Sub-pixel jitter for the sub-frames (the reference film's table): the average then
    // anti-aliases as well as blurs. Centred at use so it never drifts the frame.
    this.jitter = [
      [0.5, 0.5], [0.25, 0.75], [0.75, 0.25], [0.125, 0.625],
      [0.625, 0.125], [0.375, 0.375], [0.875, 0.875], [0.0625, 0.5625],
    ]
    this.accum2d = document.createElement('canvas')
    this.accum2d.width = width
    this.accum2d.height = height
    this.accum2dCtx = this.accum2d.getContext('2d')
    // NoColorSpace on purpose: the accumulation canvas already holds display-referred
    // values, and tagging it sRGB makes three upload it as an SRGB8_ALPHA8 texture, which
    // the GPU decodes to linear on sample — the blit then came back at 46% brightness.
    this.accumTex = new THREE.CanvasTexture(this.accum2d)
    this.accumTex.minFilter = THREE.LinearFilter
    this.accumTex.generateMipmaps = false

    // Real materials (metal, glass) need light and an environment; everything that does not
    // opt in keeps its MeshBasicMaterial and is unaffected.
    installLighting(this)

    this.shots = new Map()
    this.active = null

    // Scratch space for shots: cross-shot objects (the phone slab, the accessibility
    // tree, the subtitle plate) live here so one shot can hand something to the next.
    this.userData = {}
  }

  add(shot) {
    this.shots.set(shot.name, shot)
    shot.build?.(this)
    return shot
  }

  /**
   * Install the edit's text and hit data. The timeline aggregates it from the shots'
   * own metadata, so a shot carries its chapter heading, bilingual subtitles and
   * sound cues next to the code that draws it.
   */
  setFilm(film) {
    this.film = film
    this.presentation.setFilm(film)
  }

  /** A crossfade between shots; the outgoing shot is torn down at the midpoint. */
  cutTo(name) {
    if (this.active?.teardown) this.active.teardown(this)
    // Nothing may leak from one shot into the next. Shot 1 adds its wordmark directly to the
    // scene (not to its spiral group) and its teardown only hid the group, so the giant title
    // stayed on screen over shots 2 and 3. Hiding everything and emptying the overlay here
    // makes that whole class of mistake impossible instead of merely fixing this instance.
    this.clearFrame()
    this.active = this.shots.get(name)
    if (!this.active) throw new Error(`no shot named ${name}`)
    this.active.enter?.(this)
  }

  /**
   * Hide every scene object. The overlay is deliberately NOT touched.
   *
   * All shots are built up front and each one puts its subtitle straight into the
   * overlay. Emptied here on every cut, that overlay wipe detached the subtitles before
   * they were ever shown, so the film ran with no subtitles at all and no error. A shot
   * now owns its overlay children and removes them in teardown().
   */
  clearFrame() {
    for (const child of this.scene.children) child.visible = false
  }

  /**
   * Render exactly one moment.
   *
   * `opts.samples` is 1 (preview), a count, or 'auto' (the shot's own `mb`). Anything above 1
   * runs the motion-blur path: sub-frames spread over `shutter` x 1/fps around t, averaged
   * before the overlay is drawn. The film is still a pure function of t — a sub-frame is just
   * a different t.
   */
  renderAt(t, opts = {}) {
    const shot = this.shotAt(t)
    const auto = opts.samples === 'auto'
    const k = auto ? Math.max(1, Math.round(shot.mb ?? 1)) : Math.max(1, Math.round(opts.samples ?? 1))
    const shutter = opts.shutter ?? 0.5
    const fps = opts.fps ?? 60
    if (k <= 1) {
      this.prepare(t)
      this.drawFrame(t)
      return
    }
    this.drawBlurred(t, k, shutter, fps)
  }

  /** Select the shot for `t`, advance it and the presentation, apply the hit shake. */
  prepare(t) {
    const shot = this.shotAt(t)
    if (shot !== this.active) this.cutTo(shot.name)
    shot.update?.(this, t - shot.start, t)

    // The film's voice advances with the same clock as the scene. HITS kick the camera
    // (the world) but never the type: a shaking subtitle is an unreadable subtitle.
    this.presentation.update(t)
    const sh = this.presentation.shake
    if (sh && (sh[0] || sh[1])) {
      this.camera.position.x += sh[0]
      this.camera.position.y += sh[1]
    }
    return shot
  }

  /** One sub-frame, the way the preview has always drawn it. */
  /** Put the backdrop one unit in front of the camera, sized to cover the frame. */
  placeBackdrop() {
    this.bgQuad.visible = true
    const d = 1.0
    const h = 2 * Math.tan(THREE.MathUtils.degToRad(this.camera.fov) / 2) * d * 1.2
    const w = h * (this.width / this.height) * 1.2
    this.bgQuad.position.copy(this.camera.position)
    this.bgQuad.quaternion.copy(this.camera.quaternion)
    this.bgQuad.translateZ(-d)
    this.bgQuad.scale.set(w, h, 1)
    // the shots still drive the background through setClearColor(); take it from there
    this.renderer.getClearColor(this._bgColor)
    this.bgMat.color.copy(this._bgColor)
  }

  drawFrame(t) {
    // Grain and aberration are seeded from the time, never from Math.random(), so the same
    // frame is produced in every run.
    this.lens.uniforms.uTime.value = t

    // Two passes, by layer.
    //
    //   layer 0  the world: the set, the light sculptures, the glows. It goes through the
    //            composer, because bloom and grain are what make those layers read as light.
    //   layer 1  interface: the phone's screen, cards, labels, the wordmark. Rendered after
    //            the composer straight to the canvas, in the colours they were authored in.
    this.camera.layers.set(0)
    this.composer.renderToScreen = true
    // three encodes the clear colour to `renderer.outputColorSpace` even when it is clearing
    // a linear HDR target, so a #0E1418 background was written as its sRGB value and then
    // encoded again — the film's blacks sat at 55 instead of 14 and the picture read as flat
    // grey. Rendering the composer with a linear output space makes the clear linear, which is
    // what a HalfFloat target expects. Materials targeting a render target already compile
    // with the working colour space, so this changes no shader.
    const prevOCS = this.renderer.outputColorSpace
    this.renderer.outputColorSpace = THREE.LinearSRGBColorSpace
    this.composer.render()
    this.renderer.outputColorSpace = prevOCS

    this.camera.layers.set(1)
    this.renderer.autoClear = false
    this.renderer.clearDepth()
    this.renderer.render(this.scene, this.camera)

    this.drawOverlay()
    this.camera.layers.enable(0)
    this.camera.layers.enable(1)
  }

  /**
   * Chapters, subtitles, letterbox and flashes, crisp above everything.
   *
   * Owns autoClear itself: the caller may have just blitted the accumulated frame, and
   * `renderer.render` with autoClear left true clears the colour buffer first — which
   * silently replaced the whole frame with the overlay on black.
   */
  drawOverlay() {
    this.renderer.autoClear = false
    this.renderer.clearDepth()
    this.renderer.render(this.overlay, this.overlayCam)
    this.renderer.autoClear = true
  }

  /** Copy a texture straight to the canvas, raw (debug and final presentation). */
  blitTexture(tex) {
    if (!this.copyMat) {
      this.copyMat = new THREE.RawShaderMaterial({
        uniforms: { tDiffuse: { value: null } },
        vertexShader: `precision highp float; attribute vec3 position; attribute vec2 uv; varying vec2 vUv;
          void main(){ vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }`,
        fragmentShader: `precision highp float; uniform sampler2D tDiffuse; varying vec2 vUv;
          void main(){ gl_FragColor = vec4(texture2D(tDiffuse, vUv).rgb, 1.0); }`,
        depthTest: false, depthWrite: false,
      })
      this.copyMesh = new THREE.Mesh(this.fsGeom, this.copyMat)
      this.copyMesh.frustumCulled = false
    }
    // No clear and no clear-colour change: the fullscreen triangle covers the frame, and the
    // film's backgrounds come from the shots' own clear colour. Leaving black behind here
    // darkened every later frame (a shot sets its clear colour once, in enter()).
    const prevAuto = this.renderer.autoClear
    this.copyMat.uniforms.tDiffuse.value = tex
    this.renderer.setRenderTarget(null)
    this.renderer.autoClear = false
    this.renderer.render(this.copyMesh, this.fsCam)
    this.renderer.autoClear = prevAuto
  }

  /** Render one sub-frame to the canvas: world through post, then the interface over it. */
  drawSceneToCanvas(t) {
    this.placeBackdrop()
    this.lens.uniforms.uTime.value = t
    this.camera.layers.set(0)
    this.composer.renderToScreen = true
    this.composer.render()
    this.camera.layers.set(1)
    this.renderer.autoClear = false
    this.renderer.clearDepth()
    this.renderer.render(this.scene, this.camera)
    this.renderer.autoClear = true
  }

  /**
   * Motion blur: K sub-frames spread over the shutter, averaged.
   *
   * A sub-frame that lands in a different shot is pulled back to t: a cut must not be blurred
   * across, which is the one thing that reads as a mistake rather than as motion. The overlay
   * (chapters, subtitles) is drawn once, at the frame's own time, crisp.
   */
  drawBlurred(t, k, shutter, fps) {
    const base = this.shotAt(t)

    // Centred jitter offsets, so the average never shifts the frame by a fraction of a pixel.
    const js = []
    for (let i = 0; i < k; i++) {
      const j = this.jitter[i % this.jitter.length]
      js.push([j[0] - 0.5, j[1] - 0.5])
    }
    const mx = js.reduce((a, j) => a + j[0], 0) / k
    const my = js.reduce((a, j) => a + j[1], 0) / k
    for (const j of js) { j[0] -= mx; j[1] -= my }

    const ctx = this.accum2dCtx
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.globalCompositeOperation = 'source-over'
    ctx.globalAlpha = 1
    ctx.clearRect(0, 0, this.width, this.height)
    ctx.globalCompositeOperation = 'lighter'
    ctx.globalAlpha = 1 / k

    for (let i = 0; i < k; i++) {
      let tk = Math.max(0, t + ((i + 0.5) / k - 0.5) * shutter / fps)
      if (this.shotAt(tk) !== base) tk = t
      this.prepare(tk)

      // Sub-pixel camera jitter, in world units at the subject's distance.
      const dist = Math.max(0.5, this.camera.position.length())
      const px = (2 * Math.tan(THREE.MathUtils.degToRad(this.camera.fov) / 2) * dist) / this.height
      this.camera.position.x += js[i][0] * px
      this.camera.position.y += js[i][1] * px

      this.drawSceneToCanvas(tk)
      ctx.drawImage(this.renderer.domElement, 0, 0)
    }
    ctx.globalCompositeOperation = 'source-over'
    ctx.globalAlpha = 1

    // The average back to the WebGL canvas, then the overlay at the frame's own time.
    this.accumTex.needsUpdate = true
    this.blitTexture(this.accumTex)
    this.presentation.update(t)
    this.drawOverlay()
    this.camera.layers.enable(0)
    this.camera.layers.enable(1)
  }

  shotAt(t) {
    let best = null
    for (const s of this.shots.values()) {
      if (t >= s.start - 1e-6 && (!best || s.start > best.start)) best = s
    }
    return best
  }
}

/** A shot is a named segment of the timeline with build/enter/update/teardown. */
export function defineShot({ name, start, duration, build, enter, update, teardown, labels, ...extra }) {
  // `...extra` carries the film metadata a shot declares about itself: its chapter
  // heading, bilingual subtitles, sound cues, motion-blur budget and hits.
  return { name, start, duration, end: start + duration, build, enter, update, teardown, labels, ...extra }
}

/** Release geometry, materials and their textures for an object tree. */
function disposeTree(root) {
  root.traverse((o) => {
    o.geometry?.dispose?.()
    if (!o.material) return
    const materials = Array.isArray(o.material) ? o.material : [o.material]
    for (const m of materials) {
      m.map?.dispose?.()
      m.dispose?.()
    }
  })
}

/**
 * The lens: chromatic aberration toward the edges, a vignette, and fine grain.
 *
 * All three are deliberately small. They are here to make a flat dark frame read as
 * photographed, not to be noticed.
 */
const LENS_SHADER = {
  uniforms: {
    tDiffuse: { value: null },
    uTime: { value: 0 },
    uVignette: { value: 0.62 },
    uGrain: { value: 0.016 },
    uAberration: { value: 0.0022 },
  },
  vertexShader: /* glsl */`
    varying vec2 vUv;
    void main() {
      vUv = uv;
      gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    }
  `,
  fragmentShader: /* glsl */`
    uniform sampler2D tDiffuse;
    uniform float uTime, uVignette, uGrain, uAberration;
    varying vec2 vUv;

    float hash(vec2 p) {
      return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
    }

    // A shoulder, not ACES. Below k this is the identity, so the colours the film was
    // designed in survive exactly — an ACES curve crushes #0E1418 to near black and takes the
    // whole blue-grey depth of the set with it. Above k it rolls off smoothly, and very bright
    // values desaturate toward white. (Same curve as the reference project's shoulder().)
    vec3 shoulder(vec3 x) {
      const float k = 0.72;
      vec3 y = mix(x, k + (1.0 - k) * (1.0 - exp(-(x - k) / (1.0 - k))), step(k, x));
      float over = max(max(x.r, x.g), x.b);
      return mix(y, vec3(1.0), smoothstep(2.0, 12.0, over) * 0.85);
    }

    vec3 linearToSRGB(vec3 c) {
      c = clamp(c, 0.0, 1.0);
      vec3 s = step(vec3(0.0031308), c);
      return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, s);
    }

    void main() {
      vec2 c = vUv - 0.5;
      float r2 = dot(c, c);

      // Aberration grows with distance from the axis, like a real lens.
      float k = uAberration * (0.30 + r2 * 2.4);
      vec3 col;
      col.r = texture2D(tDiffuse, vUv + c * k).r;
      col.g = texture2D(tDiffuse, vUv).g;
      col.b = texture2D(tDiffuse, vUv - c * k).b;

      col *= 1.0 - uVignette * smoothstep(0.10, 0.80, r2);

      float g = hash(vUv * vec2(1920.0, 1080.0) + uTime * 60.0) - 0.5;
      col += g * uGrain;

      // ---- highlight roll-off only ------------------------------------------------
      //
      // The sRGB encode is NOT done here. three already converts a ShaderPass's output when
      // it renders to the canvas, and doing it here as well encoded the frame twice: the
      // designed #0E1418 came out at (66,79,87) instead of (14,20,24), and #808080 at 189
      // instead of 128 — every dark frame was lifted and the film read as flat grey. This
      // pass only rolls off the highlights (an identity below 0.72, so the designed colours
      // survive exactly) and leaves the encode to the renderer.
      col = shoulder(col);
      gl_FragColor = vec4(col, 1.0);
    }
  `,
}
