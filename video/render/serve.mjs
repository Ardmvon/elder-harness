// A tiny static server. Both the offline renderer and the preview need a real
// origin: ES modules are blocked under file:// by CORS, and the imported
// three.module.js is not a data: URL.

import { createServer } from 'node:http'
import { readFileSync, existsSync, statSync } from 'node:fs'
import { join, extname, normalize, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
export const ROOT = join(HERE, '..')

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.glsl': 'text/plain; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.woff2': 'font/woff2',
}

export async function serve({ port = 0 } = {}) {
  const server = createServer((req, res) => {
    const url = decodeURIComponent(req.url.split('?')[0])
    const rel = url === '/' ? '/index.html' : url
    const path = normalize(join(ROOT, rel))
    // Path traversal guard: everything served must live under the project root.
    if (!path.startsWith(ROOT) || !existsSync(path) || !statSync(path).isFile()) {
      res.writeHead(404, { 'content-type': 'text/plain' }); res.end('not found'); return
    }
    res.writeHead(200, {
      'content-type': MIME[extname(path)] || 'application/octet-stream',
      'cache-control': 'no-store',
    })
    res.end(readFileSync(path))
  })
  await new Promise((res, rej) => {
    server.once('error', rej)
    server.listen(port, '127.0.0.1', res)
  })
  const { port: actual } = server.address()
  return {
    origin: `http://127.0.0.1:${actual}`,
    close: () => new Promise(r => server.close(r)),
  }
}
