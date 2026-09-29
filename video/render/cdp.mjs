// Minimal Chrome DevTools Protocol client — no playwright, no puppeteer.
// We only need four verbs: launch, navigate, evaluate, screenshot.

import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

/**
 * GL flags for the selected backend.
 *
 *   GL_BACKEND=swiftshader (default) software, deterministic, works with no display
 *   GL_BACKEND=gl-egl               ANGLE over desktop GL through EGL (NVIDIA proprietary)
 *   GL_BACKEND=egl                  plain EGL
 *   GL_BACKEND=vulkan               ANGLE over Vulkan
 *
 * Chromium silently falls back to SwiftShader when the GPU stack is unavailable, so the
 * selected backend is not proof of what ran: render/gl-probe.mjs reports the real renderer.
 */
function glArgs() {
  const backend = (process.env.GL_BACKEND || 'swiftshader').toLowerCase()
  if (backend === 'swiftshader') {
    return ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--disable-gpu-sandbox']
  }
  const common = [
    '--ignore-gpu-blocklist', '--enable-gpu', '--disable-gpu-sandbox',
    '--enable-webgl', '--disable-software-rasterizer',
  ]
  if (backend === 'vulkan') return [...common, '--use-gl=angle', '--use-angle=vulkan', '--enable-features=Vulkan']
  if (backend === 'egl') return [...common, '--use-gl=egl']
  return [...common, '--use-gl=angle', '--use-angle=gl-egl']
}

const CHROME = process.env.CHROME_BIN
  || join(process.env.HOME, '.cache/ms-playwright/chromium-1243/chrome-linux64/chrome')

export async function launch({ width = 1920, height = 1080, port = 9222 } = {}) {
  const profile = mkdtempSync(join(tmpdir(), 'yl-video-'))
  const args = [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
    `--window-size=${width},${height}`,
    '--no-first-run', '--no-default-browser-check',
    '--disable-extensions', '--disable-background-networking',
    '--hide-scrollbars', '--mute-audio',
    ...glArgs(),
    'about:blank',
  ]
  const proc = spawn(CHROME, args, { stdio: ['ignore', 'pipe', 'pipe'] })
  const wsUrl = await new Promise((res, rej) => {
    let buf = ''
    const t = setTimeout(() => rej(new Error('chrome did not report a ws endpoint in 20s')), 20000)
    proc.stderr.on('data', d => {
      buf += d
      const m = buf.match(/ws:\/\/[^\s]+/)
      if (m) { clearTimeout(t); res(m[0]) }
    })
    proc.on('exit', c => rej(new Error(`chrome exited early (${c}): ${buf.slice(-400)}`)))
  })

  // The browser-level endpoint lists targets as they appear; we want the page.
  const page = await waitForPage(port)
  const session = await connect(page.webSocketDebuggerUrl)
  const browserSession = await connect(wsUrl)
  return {
    proc, session, browserSession, profile,
    async close() {
      try { session.ws.close(); browserSession.ws.close() } catch {}
      proc.kill('SIGKILL')
      try { rmSync(profile, { recursive: true, force: true }) } catch {}
    },
  }
}

async function waitForPage(port) {
  for (let i = 0; i < 100; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/json/list`)
      const targets = await r.json()
      const p = targets.find(t => t.type === 'page')
      if (p?.webSocketDebuggerUrl) return p
    } catch {}
    await new Promise(r => setTimeout(r, 100))
  }
  throw new Error('no page target')
}

export async function connect(url) {
  // Node 26 ships a global WebSocket; no `ws` package needed.
  const ws = new WebSocket(url)
  await new Promise((res, rej) => {
    ws.onopen = res
    ws.onerror = e => rej(new Error('ws error: ' + (e.message || 'unknown')))
  })
  let id = 0
  const pending = new Map()
  ws.onmessage = ev => {
    const msg = JSON.parse(ev.data)
    if (msg.id && pending.has(msg.id)) {
      const { res, rej } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? rej(new Error(JSON.stringify(msg.error))) : res(msg.result)
    }
  }
  const send = (method, params = {}) => new Promise((res, rej) => {
    const myId = ++id
    pending.set(myId, { res, rej })
    ws.send(JSON.stringify({ id: myId, method, params }))
  })
  return { ws, send }
}
