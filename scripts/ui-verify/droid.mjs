// adb driver for the Vicu Android UI harness.
//
// Device safety: every adb call goes through adb() below, which always passes `-s emulator-5554`.
// Nothing here ever talks to another device (a wireless phone may be connected). Importing this
// module aborts unless `adb -s emulator-5554 get-state` prints `device`.
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'

export const HERE = dirname(fileURLToPath(import.meta.url))
export const REPO = resolve(HERE, '..', '..')
export const SERIAL = 'emulator-5554'
export const APP_ID = 'com.rendyhd.vicu.debug'
export const MAIN_ACTIVITY = `${APP_ID}/com.rendyhd.vicu.MainActivity`

// ---- adb location -------------------------------------------------------------------------------

function sdkDir() {
  const props = join(REPO, 'local.properties')
  if (existsSync(props)) {
    const m = readFileSync(props, 'utf8').match(/^sdk\.dir\s*=\s*(.+)$/m)
    if (m) return m[1].trim().replace(/\\:/g, ':').replace(/\\\\/g, '\\')
  }
  return process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || null
}

function adbPath() {
  const sdk = sdkDir()
  if (!sdk) throw new Error('No Android SDK: set sdk.dir in local.properties or ANDROID_HOME')
  const exe = join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb')
  if (!existsSync(exe)) throw new Error(`adb not found at ${exe}`)
  return exe
}

const ADB = adbPath()

// ---- adb calls ----------------------------------------------------------------------------------

// Flags that would pick or reach another device. They are never allowed in a call.
const FORBIDDEN = new Set(['-s', '-d', '-e', '-t', 'connect', 'disconnect', 'pair', 'devices', 'kill-server', 'start-server'])

function redact(args) {
  // Keep failure messages free of typed text (the login script types a token through here).
  const shell = args[0] === 'shell' ? args.slice(1).join(' ') : null
  if (shell && /^input\s+text\b/.test(shell)) return 'shell input text <redacted>'
  return args.join(' ').slice(0, 160)
}

export function adb(...args) {
  if (args.some((a) => FORBIDDEN.has(a))) throw new Error(`adb argument not allowed: ${redact(args)}`)
  const r = spawnSync(ADB, ['-s', SERIAL, ...args.map(String)], { maxBuffer: 128 * 1024 * 1024 })
  if (r.error || r.status !== 0) {
    const err = (r.stderr?.toString() || r.error?.message || '').trim().split('\n')[0]
    throw new Error(`adb ${redact(args)} failed (exit ${r.status}): ${err}`)
  }
  return r.stdout
}

export const sh = (cmd) => adb('shell', cmd).toString()
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const sleepSync = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms)

function ensureDevice() {
  const r = spawnSync(ADB, ['-s', SERIAL, 'get-state'], { encoding: 'utf8' })
  if (r.stdout?.trim() !== 'device') {
    console.error(`${SERIAL} is not up (get-state: ${(r.stdout || r.stderr || '').trim() || 'no answer'}).`)
    console.error('Start it with: emulator/emulator.exe -avd Pixel_10   (it must come up as emulator-5554)')
    process.exit(2)
  }
}
ensureDevice()

// ---- screen facts -------------------------------------------------------------------------------

/** Pixel size and the density scale (dp = px / scale). */
export function screen() {
  const size = sh('wm size').match(/(?:Override|Physical) size:\s*(\d+)x(\d+)/g)?.pop()?.match(/(\d+)x(\d+)/)
  const dens = sh('wm density').match(/(?:Override|Physical) density:\s*(\d+)/g)?.pop()?.match(/(\d+)$/)
  if (!size || !dens) throw new Error('could not read wm size / wm density')
  const dpi = +dens[1]
  return { width: +size[1], height: +size[2], dpi, scale: dpi / 160 }
}

// ---- output -------------------------------------------------------------------------------------

export let OUT = join(HERE, 'out')
/** Captures go to out/<dir>/. Returns the folder. */
export function setOutDir(dir) {
  OUT = join(HERE, 'out', dir)
  mkdirSync(OUT, { recursive: true })
  return OUT
}
mkdirSync(OUT, { recursive: true })

/**
 * Full-screen capture to <OUT>/<name>.png. `clip` ({ x, y, w, h } in pixels) crops it.
 * Returns the file path.
 */
export function shot(name, clip) {
  let png = adb('exec-out', 'screencap', '-p')
  if (clip) {
    const src = PNG.sync.read(png)
    const x = Math.max(0, Math.min(clip.x, src.width - 1))
    const y = Math.max(0, Math.min(clip.y, src.height - 1))
    const w = Math.min(clip.w, src.width - x)
    const h = Math.min(clip.h, src.height - y)
    const dst = new PNG({ width: w, height: h })
    PNG.bitblt(src, dst, x, y, w, h, 0, 0)
    png = PNG.sync.write(dst)
  }
  const file = join(OUT, `${name}.png`)
  writeFileSync(file, png)
  console.log('shot', name)
  return file
}

// ---- UI tree ------------------------------------------------------------------------------------

const BOOL_ATTRS = ['checkable', 'checked', 'clickable', 'enabled', 'focusable', 'focused', 'scrollable', 'long-clickable', 'password', 'selected']
const ENTITIES = { '&quot;': '"', '&apos;': "'", '&lt;': '<', '&gt;': '>', '&amp;': '&' }
const decode = (s) =>
  s
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(+d))
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&(quot|apos|lt|gt|amp);/g, (m) => ENTITIES[m])

/**
 * uiautomator dump as a flat list of nodes in document order. Each node has the raw attributes
 * (`text`, `content-desc`, `resource-id`, `class`, ...), the boolean ones as booleans
 * (`checkable`, `checked`, `clickable`, ...), `x1 y1 x2 y2 w h cx cy` from `bounds` in pixels,
 * and the tree: `i` (index), `parent` (index or -1), `depth` and `children` (indices).
 */
export function dump() {
  // uiautomator refuses to dump while the UI is still animating; retry a few times.
  for (let i = 0; ; i++) {
    try {
      sh('uiautomator dump /sdcard/ui.xml')
      break
    } catch (e) {
      if (i >= 6) throw e
      sleepSync(700)
    }
  }
  return parseDump(adb('exec-out', 'cat', '/sdcard/ui.xml').toString())
}

export function parseDump(xml) {
  const nodes = []
  const stack = []
  for (const m of xml.matchAll(/<node\s+((?:[^>"]|"[^"]*")*?)\s*(\/?)>|<\/node>/g)) {
    if (m[0] === '</node>') { stack.pop(); continue }
    const a = {}
    for (const p of m[1].matchAll(/([\w-]+)="([^"]*)"/g)) a[p[1]] = decode(p[2])
    for (const k of BOOL_ATTRS) a[k] = a[k] === 'true'
    const b = a.bounds?.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/)
    if (b) {
      a.x1 = +b[1]; a.y1 = +b[2]; a.x2 = +b[3]; a.y2 = +b[4]
      a.w = a.x2 - a.x1; a.h = a.y2 - a.y1
      a.cx = Math.round((a.x1 + a.x2) / 2); a.cy = Math.round((a.y1 + a.y2) / 2)
    }
    a.i = nodes.length
    a.parent = stack.length ? stack[stack.length - 1] : -1
    a.depth = stack.length
    a.children = []
    if (a.parent >= 0) nodes[a.parent].children.push(a.i)
    nodes.push(a)
    if (!m[2]) stack.push(a.i)
  }
  return nodes
}

/** The node and everything below it. */
export function subtree(nodes, n) {
  const out = [n]
  for (let k = 0; k < out.length; k++) for (const c of out[k].children) out.push(nodes[c])
  return out
}

/**
 * The text nodes that share a row with `n`: the text below the nearest ancestor (within three
 * levels) that holds any besides n's own. Used to tie a checkbox to its row title.
 */
export function rowTexts(nodes, n) {
  let p = n.parent
  for (let up = 0; up < 3 && p >= 0; up++, p = nodes[p].parent) {
    const own = new Set(subtree(nodes, n).map((x) => x.i))
    const texts = subtree(nodes, nodes[p]).filter((x) => x.text && x.text.trim() && !own.has(x.i))
    if (texts.length) return texts
  }
  return []
}

/** One line per node that has text or a description (for reading what is on screen). */
export function list(filter = () => true) {
  return dump()
    .filter((n) => (n.text || n['content-desc']) && filter(n))
    .map((n) => `${n.text ? `"${n.text}"` : ''}${n['content-desc'] ? ` desc="${n['content-desc']}"` : ''} ${n.bounds}${n.clickable ? ' C' : ''}${n.checkable ? (n.checked ? ' [x]' : ' [ ]') : ''}`)
}

export function findNode(pred, label, nodes = dump()) {
  const n = nodes.find(pred)
  if (!n) throw new Error(`not found: ${label}`)
  return n
}
export const hasText = (t, exact = true, nodes = dump()) => nodes.some((n) => (exact ? n.text === t : n.text?.includes(t)))
export const hasDesc = (t, exact = true, nodes = dump()) => nodes.some((n) => (exact ? n['content-desc'] === t : n['content-desc']?.includes(t)))
export const byText = (t, exact = true, nodes) => findNode((n) => (exact ? n.text === t : n.text?.includes(t)), `text ${t}`, nodes)
export const byDesc = (t, exact = true, nodes) => findNode((n) => (exact ? n['content-desc'] === t : n['content-desc']?.includes(t)), `desc ${t}`, nodes)

// ---- input --------------------------------------------------------------------------------------

export async function tap(x, y, wait = 900) { sh(`input tap ${x} ${y}`); await sleep(wait) }
export async function tapText(t, opts = {}) { const n = byText(t, opts.exact ?? true); await tap(n.cx, n.cy, opts.wait) }
export async function tapDesc(t, opts = {}) { const n = byDesc(t, opts.exact ?? true); await tap(n.cx, n.cy, opts.wait) }
export async function longPress(x, y, ms = 900) { sh(`input swipe ${x} ${y} ${x} ${y} ${ms}`); await sleep(ms) }

/**
 * Type text into the focused field. `input text` takes spaces as %s; the whole argument goes to the
 * device shell in single quotes, so nothing else needs escaping (a quote is closed, escaped, reopened).
 */
export async function type(text) {
  const esc = text.replace(/ /g, '%s').replace(/'/g, "'\\''")
  sh(`input text '${esc}'`)
  await sleep(500)
}
/**
 * Type a few characters at a time with a pause, for fields that rewrite their text while you type
 * (the quick add sheet parses as you type and drops or reorders characters that arrive in a burst).
 */
export async function typeSlow(text, { chunk = 1, pause = 140 } = {}) {
  for (let i = 0; i < text.length; i += chunk) {
    const part = text.slice(i, i + chunk)
    if (part === ' ') await key(62, pause) // space bar
    else { await type(part); await sleep(pause) }
  }
}

/** Empty the focused text field: select all (Ctrl+A) and delete. */
export async function clearField() {
  sh('input keycombination 113 29')
  await sleep(200)
  await key(KEY.DEL, 300)
}
export async function key(code, wait = 600) { sh(`input keyevent ${code}`); await sleep(wait) }
export const KEY = { BACK: 4, HOME: 3, ENTER: 66, DEL: 67, TAB: 61, ESCAPE: 111 }
export async function back() { await key(KEY.BACK, 900) }
export async function swipe(x1, y1, x2, y2, ms = 300, wait = 900) { sh(`input swipe ${x1} ${y1} ${x2} ${y2} ${ms}`); await sleep(wait) }

/** Raw touch event: action is DOWN, MOVE or UP. Lets a gesture be held part-way. */
export function motionevent(action, x, y) { sh(`input motionevent ${action} ${x} ${y}`) }

/**
 * Press at (x1, y1), drag to the point `fraction` of the way to (x2, y2) and hold there.
 * Capture while held, then call release() (lift where it is) or cancel() (go back and lift).
 *   const g = await holdGesture(300, 1200, 900, 1200, 0.5)
 *   shot('half')
 *   await g.release()
 */
export async function holdGesture(x1, y1, x2, y2, fraction = 1, { steps = 6, stepMs = 60 } = {}) {
  motionevent('DOWN', x1, y1)
  let px = x1
  let py = y1
  for (let i = 1; i <= steps; i++) {
    const f = (fraction * i) / steps
    px = Math.round(x1 + (x2 - x1) * f)
    py = Math.round(y1 + (y2 - y1) * f)
    motionevent('MOVE', px, py)
    await sleep(stepMs)
  }
  await sleep(250)
  return {
    x: px,
    y: py,
    async release(wait = 800) { motionevent('UP', px, py); await sleep(wait) },
    async cancel(wait = 800) { motionevent('MOVE', x1, y1); await sleep(stepMs); motionevent('UP', x1, y1); await sleep(wait) },
  }
}

// ---- app and device state -----------------------------------------------------------------------

export function start(extra = '') { sh(`am start -n ${MAIN_ACTIVITY} ${extra}`) }
export function stop() { sh(`am force-stop ${APP_ID}`) }
export function pmClear() { return sh(`pm clear ${APP_ID}`).trim() }
export function focusedWindow() { return sh('dumpsys window | grep -E "mCurrentFocus|mFocusedApp"') }
export const appInFront = () => focusedWindow().includes(APP_ID)

export function night(on) { sh(`cmd uimode night ${on ? 'yes' : 'no'}`) }
/** Font scale as set in Settings, Display (1.0 is normal). */
export function fontScale(x) { sh(`settings put system font_scale ${x}`) }
/**
 * Animator, transition and window animation scale. 1 is normal, 0 is off. Use 5 to 10 to hold a
 * mid-animation frame long enough to capture instead of recording the screen.
 */
export function animScale(x) {
  for (const k of ['animator_duration_scale', 'transition_animation_scale', 'window_animation_scale']) {
    sh(`settings put global ${k} ${x}`)
  }
}
/** Back to a normal device: light, font 1.0, animations on. */
export function resetDevice() { night(false); fontScale(1.0); animScale(1) }

