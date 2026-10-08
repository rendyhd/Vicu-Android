// Card 4.12, Android half: the motion interactions at one animator scale, with frame data.
//
//   node motion-audit.mjs --scale 0 [--run NAME] [--token <path>] [--inbox 47]
//   node motion-audit.mjs --scale 1
//
// Sets animator, transition and window animation scale to --scale (and puts all three back to 1 at
// the end), then runs: a completion tick (A1), the editor open and back (A3), tab switches (A3), a
// swipe complete and a swipe to the When sheet (A2), a scroll of Today (A7) and the quick add token
// chip (moments). Throwaway tasks titled "droid motion ..." are made through the API and deleted.
//
// Per interaction: `dumpsys gfxinfo <app> reset` before, the summary and `framestats` after (frames
// over 50 ms, janky frames, 90th and 95th percentile, from both the summary and the frame stats).
// At scale 0 two captures taken right after the action must be identical (nothing moves) and the
// final state must be there at once; at scale 1 the captures may differ. Logcat errors of the app are
// printed at the end. Captures go to out/<run>-motion-s<scale>/.
import { readFileSync } from 'node:fs'
import { PNG } from 'pngjs'
import * as d from './droid.mjs'
import * as v from './nav.mjs'
import { DEFAULT_TOKEN_PATH, resolveToken } from './login.mjs'

const args = process.argv.slice(2)
const opt = (n, dflt) => { const i = args.indexOf(`--${n}`); return i >= 0 ? args[i + 1] : dflt }
const SCALE = Number(opt('scale', '1'))
const RUN = opt('run', 'motion')
const INBOX = Number(opt('inbox', 47))
const API = 'http://localhost:3456/api/v2'
const token = resolveToken(opt('token', DEFAULT_TOKEN_PATH))
if (!token) { console.error('no API token'); process.exit(1) }
const api = async (method, path, body) => {
  const r = await fetch(API + path, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!r.ok) throw new Error(`${method} ${path}: HTTP ${r.status}`)
  const t = await r.text()
  return t ? JSON.parse(t) : null
}

const outDir = d.setOutDir(`${RUN}-motion-s${SCALE}`)
const stamp = Date.now().toString(36).slice(-4)
const made = []
let failed = 0
const results = []
const frames = {}

// ---- helpers --------------------------------------------------------------------------------------

function pngDiff(fileA, fileB, fromY = 160) {
  const a = PNG.sync.read(readFileSync(fileA))
  const b = PNG.sync.read(readFileSync(fileB))
  if (a.width !== b.width || a.height !== b.height) return 1
  let n = 0
  for (let y = fromY; y < a.height; y++) {
    for (let x = 0; x < a.width; x++) {
      const i = (y * a.width + x) * 4
      if (Math.abs(a.data[i] - b.data[i]) + Math.abs(a.data[i + 1] - b.data[i + 1]) + Math.abs(a.data[i + 2] - b.data[i + 2]) > 48) n++
    }
  }
  return n / ((a.height - fromY) * a.width)
}

const gfxReset = () => d.sh(`dumpsys gfxinfo ${d.APP_ID} reset`)

/** Summary and frame stats since the last reset. Frame time is FrameCompleted minus IntendedVsync. */
function gfxRead() {
  const out = d.sh(`dumpsys gfxinfo ${d.APP_ID} framestats`)
  const num = (re) => { const m = re.exec(out); return m ? Number(m[1]) : null }
  const summary = {
    total: num(/Total frames rendered: (\d+)/),
    janky: num(/Janky frames: (\d+)/),
    p50: num(/50th percentile: (\d+)ms/),
    p90: num(/90th percentile: (\d+)ms/),
    p95: num(/95th percentile: (\d+)ms/),
    p99: num(/99th percentile: (\d+)ms/),
  }
  const times = []
  const m = /---PROFILEDATA---\r?\n([\s\S]*?)---PROFILEDATA---/.exec(out)
  if (m) {
    const lines = m[1].split(/\r?\n/).filter(Boolean)
    const head = lines[0].split(',')
    const iFlags = head.indexOf('Flags')
    const iStart = head.indexOf('IntendedVsync')
    const iEnd = head.indexOf('FrameCompleted')
    for (const line of lines.slice(1)) {
      const c = line.split(',')
      if (Number(c[iFlags]) !== 0) continue // layout change or skipped: not a measure of a frame
      const ms = (Number(c[iEnd]) - Number(c[iStart])) / 1e6
      if (Number.isFinite(ms) && ms > 0) times.push(ms)
    }
  }
  return { summary, times }
}

const pctl = (arr, p) => { if (!arr.length) return null; const s = [...arr].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)] }

function report(name, g) {
  const t = g.times
  const over50 = t.filter((x) => x > 50).length
  const row = {
    name,
    frames: g.summary.total,
    janky: g.summary.janky,
    p90: g.summary.p90,
    p95: g.summary.p95,
    statFrames: t.length,
    statP90: pctl(t, 90)?.toFixed(1),
    statP95: pctl(t, 95)?.toFixed(1),
    statMax: t.length ? Math.max(...t).toFixed(1) : null,
    over50,
  }
  console.log(`GFX ${name}: frames ${row.frames}, janky ${row.janky}, p90 ${row.p90} ms, p95 ${row.p95} ms; framestats ${row.statFrames} frames, p90 ${row.statP90}, p95 ${row.statP95}, max ${row.statMax} ms, over 50 ms: ${over50}`)
  results.push(row)
}

async function check(name, fn) {
  try { await fn(); console.log('ok', name) } catch (e) { failed++; console.log('FAIL', name, String(e.message).split('\n')[0]) }
}

/**
 * Three captures right after an action, each about half a second after the one before. A capture
 * takes a few hundred ms here, so the first can still be before the screen changed at all (a tab
 * switch recomposes a whole screen); the last two must be the same picture at scale 0 (nothing
 * moves once the screen is there). The first against the second is printed for information.
 */
function stillness(name, label = '') {
  const a = d.shot(`${name}-a`)
  const b = d.shot(`${name}-b`)
  const c = d.shot(`${name}-c`)
  const ab = pngDiff(a, b)
  const bc = pngDiff(b, c)
  console.log(`  ${name}: first vs second ${(ab * 100).toFixed(2)}%, second vs third ${(bc * 100).toFixed(2)}%`)
  if (SCALE === 0 && bc > 0.003) throw new Error(`${label || name}: still moving at animator scale 0 (${(bc * 100).toFixed(2)}%)`)
  return bc
}

const waitFor = async (pred, what, ms = 9000) => {
  const t0 = Date.now()
  while (Date.now() - t0 < ms) { const r = pred(); if (r) return r; await d.sleep(250) }
  throw new Error(`timed out: ${what}`)
}
const title = (k) => `droid motion ${k} ${stamp}`
const rowOf = (k) => v.taskRows().find((r) => r.title?.text === title(k))
const makeTask = async (k) => {
  const t = await api('POST', `/projects/${INBOX}/tasks`, { title: title(k), due_date: new Date().toISOString() })
  made.push(t.id)
  return t.id
}
const refreshToday = async (k) => {
  await v.nav('Today')
  for (let i = 0; i < 6 && !rowOf(k); i++) {
    if (i < 3) await d.swipe(540, 700, 540, 1700, 400, 7000)
    else await d.swipe(540, 1900, 540, 700, 400, 900)
  }
  if (!rowOf(k)) throw new Error(`throwaway task ${k} is not on Today`)
}
const snack = (nodes = d.dump()) => nodes.find((n) => /^(Completed|\d+ completed)$/.test(n.text ?? ''))

// ---- the interactions -----------------------------------------------------------------------------
// Each interaction is measured in two passes. The frame pass does the action and nothing else (a screen
// dump or a capture keeps the app's main thread busy and shows up as a slow frame), waits, and reads
// gfxinfo. The state pass repeats it with captures and dumps to check the end state, stillness at scale 0
// and that input is not blocked.

const undoNode = (nodes = d.dump()) => nodes.find((n) => n.text === 'Undo' || n['content-desc'] === 'Undo')
const checkedNow = (k) => rowOf(k)?.check.checked

async function framePass(name, act, restore, settle = 1800) {
  gfxReset()
  await act()
  await d.sleep(settle)
  const g = gfxRead()
  report(name, g)
  frames[name] = g
  if (restore) await restore()
  return g
}

async function completion() {
  await makeTask('one')
  await refreshToday('one')
  await d.sleep(500)
  const tick = async () => { const r = rowOf('one'); await d.tap(r.check.cx, r.check.cy, 0) }
  // Frame pass: tick, let the spring and the check settle, then reopen it while it is still held.
  await framePass('completion (tick to settled, held row)', tick, async () => { await d.sleep(200); await tick(); await d.sleep(1500) }, 2200)
  if (checkedNow('one')) throw new Error('could not reopen the task after the frame pass')
  // State pass.
  await tick()
  await check('A1 tick: nothing moves at scale 0', async () => { stillness('a1-tick', 'row after tick') })
  await check('A1 tick: the checkbox is checked at once', async () => { if (!checkedNow('one')) throw new Error('the checkbox is not checked after the tap') })
  // The row leaves 5 s after the tap and the snackbar follows; the snackbar lasts 6 s, so Undo is
  // tapped from the dump that found it, before anything else.
  const sd = await waitFor(() => { const dmp = d.dump(); return snack(dmp) ? dmp : null }, 'the Completed snackbar', 14000)
  const undo = undoNode(sd)
  if (undo) await d.tap(undo.cx, undo.cy, 1500)
  await check('A1 tick: the row left the list', async () => { if (sd.some((n) => n.text === title('one'))) throw new Error('the row was still listed when the snackbar showed') })
  await check('A1 tick: Undo is not blocked and restores the row', async () => {
    if (!undo) throw new Error('no Undo')
    await waitFor(() => rowOf('one') && !rowOf('one').check.checked, 'the row to come back unchecked', 8000)
  })
}

async function editor() {
  await v.nav('Today', 1500)
  const open = async () => { const r = rowOf('one') ?? v.firstRow(undefined, { plain: true }); await d.tap(r.title.cx, r.title.cy, 0) }
  await framePass('editor open', open, null, 1500)
  await check('A3 editor open: the editor is there', async () => { if (!d.hasText('Done')) throw new Error('no Done button, the editor did not open') })
  await framePass('editor back', () => d.back(), null, 1200)
  await check('A3 editor back: the list is back', async () => { if (!v.bottomBarVisible(d.dump())) throw new Error('the bottom bar is not back') })
  // State pass for stillness.
  await open()
  await check('A3 editor open: nothing moves at scale 0', async () => { stillness('a3-editor-open', 'editor after open') })
  await d.back()
  await d.sleep(800)
}

async function tabs() {
  await v.nav('Today', 1500)
  const all = { summary: { total: 0, janky: 0, p90: null, p95: null }, times: [] }
  for (const label of ['Upcoming', 'Anytime', 'Today']) {
    const item = d.dump().find((n) => n.text === label && n.y1 > v.sc.height * 0.85)
    if (!item) throw new Error(`no ${label} tab`)
    const g = await framePass(`tab switch to ${label}`, () => d.tap(item.cx, item.cy, 0), null, 1500)
    all.summary.total += g.summary.total ?? 0
    all.summary.janky += g.summary.janky ?? 0
    all.times.push(...g.times)
  }
  frames.tabs = all
  report('tab switches (3 together)', all)
  // State pass: each switch with captures.
  for (const label of ['Upcoming', 'Anytime', 'Today']) {
    const item = d.dump().find((n) => n.text === label && n.y1 > v.sc.height * 0.85)
    await d.tap(item.cx, item.cy, 0)
    await check(`A3 tab ${label}: nothing moves at scale 0`, async () => { stillness(`a3-tab-${label.toLowerCase()}`, `tab ${label}`) })
    await d.sleep(600)
  }
}

async function swipes() {
  await makeTask('two')
  await refreshToday('two')
  const W = v.sc.width
  let r = rowOf('two')
  const g = await d.holdGesture(300, r.check.cy, 300 + Math.round(W * 0.6), r.check.cy, 1, { steps: 8 })
  await g.release(0)
  await check('A2 swipe right: nothing moves at scale 0', async () => { stillness('a2-swipe-right', 'row after swipe complete') })
  await waitFor(() => { const dmp = d.dump(); return snack(dmp) ? dmp : null }, 'the snackbar after a swipe complete', 14000)
  await check('A2 swipe right: the task completed and Undo restores it', async () => {
    const undo = undoNode()
    if (!undo) throw new Error('no Undo')
    await d.tap(undo.cx, undo.cy, 1500)
    await waitFor(() => rowOf('two'), 'the row to come back', 8000)
  })
  r = rowOf('two')
  const g2 = await d.holdGesture(1000, r.check.cy, 1000 - Math.round(W * 0.6), r.check.cy, 1, { steps: 8 })
  await g2.release(0)
  await check('A2 swipe left: the When sheet is up at once', async () => {
    stillness('a2-swipe-left', 'When sheet')
    if (!d.hasText('When')) throw new Error('the When sheet is not open')
  })
  await d.back()
}

async function scroll() {
  await v.nav('Today', 1500)
  const fab = (nodes) => nodes.find((n) => n['content-desc'] === 'New task')
  const wdp = (nodes) => { const f = fab(nodes); return f ? v.dp(f.x2 - f.x1) : 0 }
  await framePass('scroll of Today (3 flings)', async () => { for (let i = 0; i < 3; i++) await d.swipe(540, 1700, 540, 500, 350, 300) }, null, 1500)
  await check('A7 scroll: FAB is an icon and nothing moves at scale 0', async () => {
    stillness('a7-scrolled', 'Today after the scroll')
    const w = wdp(d.dump())
    if (w > 80) throw new Error(`the FAB is still extended (${w.toFixed(0)} dp)`)
  })
  for (let i = 0; i < 7 && wdp(d.dump()) < 100; i++) await d.swipe(540, 900, 540, 1700, 300, 600)
}

async function quickAddToken() {
  await v.nav('Today', 1500)
  await d.tapDesc('New task', { wait: 1800 })
  await d.clearField()
  await d.typeSlow('Call Ana tomorrow', { pause: 80 })
  await d.sleep(800)
  await check('moments token: the date chip is there and nothing moves at scale 0', async () => {
    stillness('moments-token', 'quick add after the date word')
    if (!d.dump().some((n) => /tomorrow/i.test(`${n.text ?? ''}${n['content-desc'] ?? ''}`))) throw new Error('no tomorrow chip')
  })
  await d.clearField()
  await d.back(); await d.back()
}

// ---- run ------------------------------------------------------------------------------------------

d.sh('logcat -c')
d.animScale(SCALE)
console.log(`animator, transition and window animation scale set to ${SCALE}`)
try {
  await v.home()
  await completion()
  await editor()
  await tabs()
  await swipes()
  await scroll()
  await quickAddToken()
} catch (e) {
  failed++
  console.log('FAIL run', String(e.message).split('\n')[0])
} finally {
  d.animScale(1)
  for (const id of made) { try { await api('DELETE', `/tasks/${id}`) } catch (e) { console.log('could not delete throwaway task', id, e.message) } }
  try { await v.nav('Today'); await d.swipe(540, 700, 540, 1700, 400, 3000); await v.home() } catch { /* best effort */ }
}

const pid = d.sh(`pidof ${d.APP_ID}`).trim()
const log = d.sh(`logcat -d '*:E'`).split(/\r?\n/).filter((l) => /vicu|AndroidRuntime|FATAL/i.test(l) || (pid && l.includes(` ${pid} `)))
console.log(`LOGCAT errors for the app: ${log.length}`)
for (const l of log.slice(0, 30)) console.log('  ' + l.slice(0, 220))
console.log('SUMMARY ' + JSON.stringify({ scale: SCALE, failed, gfx: results }))
console.log(`scale restored to ${d.sh('settings get global animator_duration_scale').trim()}, ${d.sh('settings get global transition_animation_scale').trim()}, ${d.sh('settings get global window_animation_scale').trim()}`)
process.exit(failed ? 1 : 0)
