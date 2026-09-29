// Which GL backend is actually in use, and how fast is it?
//
// The renderer string is the only trustworthy answer: Chromium silently falls back to
// SwiftShader when the GPU stack is unavailable, which looks identical in the logs but
// runs the whole cut at a tenth of the speed.

import { launch } from './cdp.mjs'

const backend = process.env.GL_BACKEND || 'swiftshader'
const browser = await launch({ width: 1280, height: 720, port: 9333 })

async function evalStr(session, expr) {
  const res = await session.send('Runtime.evaluate', {
    expression: expr, returnByValue: true, awaitPromise: true,
  })
  if (res.exceptionDetails) throw new Error(res.exceptionDetails.text)
  return res.result.value
}

await browser.session.send('Runtime.enable')
const renderer = await evalStr(browser.session, `(() => {
  const c = document.createElement('canvas')
  const gl = c.getContext('webgl2')
  if (!gl) return 'no webgl2'
  const ext = gl.getExtension('WEBGL_debug_renderer_info')
  return ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER)
})()`)

// Fill rate test: 200 frames of a full-screen gradient at 1920x1080.
const ms = await evalStr(browser.session, `(async () => {
  const c = document.createElement('canvas'); c.width = 1920; c.height = 1080
  document.body.appendChild(c)
  const gl = c.getContext('webgl2')
  const t0 = performance.now()
  for (let i = 0; i < 200; i++) { gl.clearColor(i/200, 0.2, 0.4, 1); gl.clear(gl.COLOR_BUFFER_BIT) }
  gl.finish()
  return performance.now() - t0
})()`)

console.log(`  ${backend.padEnd(12)} ${renderer}`)
console.log(`  ${''.padEnd(12)} 200 次全屏 clear/finish: ${Math.round(ms)} ms`)
await browser.close()
