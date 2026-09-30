// A lighting rig, installed once on the stage.
//
// Until now every material in the film was MeshBasicMaterial — unlit — so a "metal frame"
// or a "glass front" could only ever be a flat picture of one. The phone rebuild needs real
// shading, and real shading without an environment looks like grey plastic: metals reflect
// their surroundings, and there are no surroundings here.
//
// So: one small procedural environment (a dark gradient sky with the key light in it and a
// brand-green kick from behind), plus a key / rim / fill rig. Nothing else in the film uses
// MeshStandardMaterial, so this changes only the objects that opt in.

import * as THREE from 'three'

export function installLighting(stage) {
  const { scene, renderer } = stage

  // ---- environment: a 256x128 gradient, blurred through PMREM ----
  const c = document.createElement('canvas')
  c.width = 256
  c.height = 128
  const ctx = c.getContext('2d')

  const sky = ctx.createLinearGradient(0, 0, 0, 128)
  sky.addColorStop(0.00, '#2A3540')   // upper: cool grey-blue
  sky.addColorStop(0.45, '#141C22')
  sky.addColorStop(1.00, '#080B0E')   // lower: near black
  ctx.fillStyle = sky
  ctx.fillRect(0, 0, 256, 128)

  // the key light, as a soft streak high on the left
  const key = ctx.createRadialGradient(70, 26, 2, 70, 26, 70)
  key.addColorStop(0, 'rgba(228,240,255,0.95)')
  key.addColorStop(0.35, 'rgba(180,205,230,0.35)')
  key.addColorStop(1, 'rgba(0,0,0,0)')
  ctx.fillStyle = key
  ctx.fillRect(0, 0, 200, 110)

  // a brand-green kick from behind-right, so the rim has something to pick up
  const kick = ctx.createRadialGradient(196, 74, 2, 196, 74, 60)
  kick.addColorStop(0, 'rgba(60,190,150,0.55)')
  kick.addColorStop(1, 'rgba(0,0,0,0)')
  ctx.fillStyle = kick
  ctx.fillRect(120, 20, 136, 108)

  const envTex = new THREE.CanvasTexture(c)
  envTex.mapping = THREE.EquirectangularReflectionMapping
  envTex.colorSpace = THREE.SRGBColorSpace
  const pmrem = new THREE.PMREMGenerator(renderer)
  pmrem.compileEquirectangularShader()
  const env = pmrem.fromEquirectangular(envTex).texture
  scene.environment = env
  envTex.dispose()
  pmrem.dispose()

  // ---- the rig ----
  const keyLight = new THREE.DirectionalLight(0xE8F1FF, 2.4)
  keyLight.position.set(-3.4, 4.4, 4.2)

  const rimLight = new THREE.DirectionalLight(0x2FB894, 1.5)
  rimLight.position.set(3.2, 0.9, -3.4)

  const fill = new THREE.HemisphereLight(0x36434D, 0x070A0C, 0.30)

  scene.add(keyLight, rimLight, fill)
  stage.lights = { keyLight, rimLight, fill }

  // Deterministic and cheap: these are three lights, no shadows, no per-frame updates.
  return { env, keyLight, rimLight, fill }
}
