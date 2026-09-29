// The stage: one scene, one camera rig, one clock.
//
// Everything in this film obeys a single rule — the picture at time T depends
// only on T. No `+=` velocities, no state carried between frames. That is what
// makes offline frame extraction and live preview produce the same film, and
// it is why every shot's update() takes t rather than dt.

import * as THREE from 'three'

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
    this.renderer.autoClear = true
    this.renderer.render(this.scene, this.camera)
    this.renderer.autoClear = false
    this.renderer.clearDepth()
    this.renderer.render(this.overlay, this.overlayCam)
    this.renderer.autoClear = true
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
