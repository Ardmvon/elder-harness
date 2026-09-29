// Frame capture, done inside the page.
//
// NOT Page.captureScreenshot. That grabs the *composited* surface, and a WebGL
// drawing buffer is cleared as soon as compositing finishes (we do not enable
// preserveDrawingBuffer — it costs memory and bandwidth nothing here needs).
//
// The symptom was subtle enough to be worth writing down: every screenshot came
// back as a clean plate of the background colour while the renderer reported
// 36000 points drawn, which reads like a rendering bug and is actually a
// capture bug. Reading the canvas synchronously, in the same evaluate() call
// that drew it, is guaranteed to see the buffer we just wrote.

/** An expression that renders frame time `t` and returns it as a data URL. */
export function captureExpr(t, format = 'jpeg', quality = 92) {
  const args = format === 'jpeg' ? `'image/jpeg', ${quality}` : `'image/png'`
  return `(() => {
    window.__renderAt(${Number(t).toFixed(6)});
    const c = document.querySelector('canvas');
    return c.toDataURL(${args});
  })()`
}

export function decodeDataURL(dataURL) {
  const comma = dataURL.indexOf(',')
  if (comma < 0) throw new Error('not a data URL: ' + String(dataURL).slice(0, 60))
  return Buffer.from(dataURL.slice(comma + 1), 'base64')
}

export const extFor = (format) => (format === 'jpeg' ? 'jpg' : 'png')
