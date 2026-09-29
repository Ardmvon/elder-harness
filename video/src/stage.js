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
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping
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
    this.composer.addPass(this.lens)
    this.composer.addPass(new OutputPass())

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

  /** Hide every scene object and empty the overlay, releasing its textures. */
  clearFrame() {
    for (const child of this.scene.children) child.visible = false
    for (const child of [...this.overlay.children]) {
      this.overlay.remove(child)
      disposeTree(child)
    }
  }

  /** Render exactly one moment. Pure in t. */
  renderAt(t) {
    const shot = this.shotAt(t)
    if (shot !== this.active) this.cutTo(shot.name)
    const local = t - shot.start
    shot.update?.(this, local, t)

    // Grain and aberration are seeded from the time, never from Math.random(), so the same
    // frame is produced in every run.
    this.lens.uniforms.uTime.value = t

    // Two passes, by layer.
    //
    //   layer 0  the world: the set, the light sculptures, the glows. It goes through the
    //            composer, because bloom and grain are what make those layers read as light.
    //   layer 1  interface: the phone's screen, cards, labels, the wordmark. Rendered after
    //            the composer straight to the canvas, in the colours they were authored in.
    //
    // Putting both in one pass was the mistake that blew the frame out: bloom has no idea
    // that a big white screen is meant to be flat, so it turned the whole phone into a flare.
    this.camera.layers.set(0)
    this.composer.render()

    this.camera.layers.set(1)
    this.renderer.autoClear = false
    this.renderer.clearDepth()
    this.renderer.render(this.scene, this.camera)

    // The overlay (subtitles) follows the same rule: text that has been through bloom is
    // text with halos, and subtitles have to stay legible above everything.
    this.renderer.clearDepth()
    this.renderer.render(this.overlay, this.overlayCam)
    this.renderer.autoClear = true

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
export function defineShot({ name, start, duration, build, enter, update, teardown, labels }) {
  return { name, start, duration, end: start + duration, build, enter, update, teardown, labels }
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
    uGrain: { value: 0.030 },
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

      gl_FragColor = vec4(col, 1.0);
    }
  `,
}
