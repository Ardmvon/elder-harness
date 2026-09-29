// ffmpeg assembly. Frames in, one mp4 out.
//
// The device-recording slot (shot 4b) renders as magenta; if a real recording
// exists we key it in here, and if not the magenta stays as a visible
// "素材待插入" marker rather than a silently empty gap.

import { spawnSync } from 'node:child_process'
import { existsSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..')

const args = parseArgs(process.argv.slice(2))
const FPS = args.fps ?? 60
const OUT = args.out ?? join(ROOT, 'out/yinling-demo.mp4')
const DEVICE = args.device ?? firstExisting([
  join(ROOT, 'assets/device/wechat-blind-page.mp4'),
  join(ROOT, 'assets/device/blind-page.mp4'),
])

const framesDir = join(ROOT, 'out/frames')
if (!existsSync(framesDir)) die(`no frames in ${framesDir} — run: npm run frames`)
const count = readdirSync(framesDir).filter(f => /^f\d+\.(jpg|png)$/.test(f)).length
if (!count) die('out/frames is empty')

const ff = ['-y', '-hide_banner', '-loglevel', 'warning', '-stats',
  '-framerate', String(FPS), '-i', join(framesDir, 'f%06d.jpg')]

if (DEVICE) {
  console.log(`keying device recording: ${DEVICE}`)
  ff.push('-i', DEVICE)
}

const filters = []
if (DEVICE) {
  // 4b occupies 26.0–28.0s in the 60s cut. Overlay the recording, scaled to
  // the frame, with the magenta plate acting as the key.
  const start = args.deviceStart ?? 26.0
  const dur = args.deviceDur ?? 2.0
  filters.push(
    `[1:v]scale=1920:1080:force_original_aspect_ratio=decrease,` +
    `pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x0E1418,` +
    `setpts=PTS-STARTPTS+${start}/TB[dev]`,
    `[0:v][dev]overlay=0:0:enable='between(t,${start},${start + dur})':eof_action=pass[v]`
  )
} else {
  const start = 26.0, dur = 2.0
  filters.push(
    // Leave the magenta plate in place, but label it so a missing asset is
    // impossible to mistake for an intentional design choice.
    `[0:v]drawtext=fontfile=/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc:` +
    `text='真机录屏 · 待插入':fontcolor=white:fontsize=44:x=(w-text_w)/2:y=(h-text_h)/2:` +
    `enable='between(t,${start},${start + dur})'[v]`
  )
}

ff.push('-filter_complex', filters.join(';'), '-map', '[v]')
ff.push(
  '-c:v', 'libx264', '-preset', args.preset ?? 'slow', '-crf', String(args.crf ?? 18),
  '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
  '-r', String(FPS),
  OUT,
)

console.log('ffmpeg', ff.join(' '))
const r = spawnSync('ffmpeg', ff, { stdio: 'inherit' })
if (r.status !== 0) die(`ffmpeg exited ${r.status}`)
console.log(`\nwrote ${OUT}`)

function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (!a.startsWith('--')) continue
    const key = a.slice(2).replace(/-([a-z])/g, (_, c) => c.toUpperCase())
    const next = argv[i + 1]
    if (next === undefined || next.startsWith('--')) out[key] = true
    else { out[key] = isNaN(Number(next)) ? next : Number(next); i++ }
  }
  return out
}
function firstExisting(paths) { return paths.find(p => existsSync(p)) || null }
function die(msg) { console.error(msg); process.exit(1) }
