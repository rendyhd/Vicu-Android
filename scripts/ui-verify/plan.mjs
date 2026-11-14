// The day planner against the local Vikunja (docs/cross-app-semantics-v1.md section 16): Settings
// turns it on, Today with a block, the Day switch, picking a task and placing it, the length handle,
// a press and hold move, a drag from the drawer, the sheet, remove time, and moving between days
// (issue 101): a swipe on the timeline, a drop on a day of the strip, resting on a day while
// dragging, the sheet's Day row with Undo, the No time row and a drop on it, the drawer's lists.
// Every change is asserted on what the server holds. A second finger on the strip is not covered:
// adb's motion events have one pointer.
//
//   node plan.mjs [tokenPath] [--out DIR] [--server http://127.0.0.1:3456] [--keep-on]
//
// Writes captures to out/plan/ (or DIR), light and dark, and prints one line per check. Exit code 1
// when a check failed. Run `node login.mjs` first. It creates only tasks titled `PLND ...` and deletes
// them at the end (its own, and any an earlier run left behind). It never seeds the server.

import * as d from './droid.mjs'
import * as v from './nav.mjs'
import { DEFAULT_TOKEN_PATH, resolveToken } from './login.mjs'

const arg = (name) => {
  const i = process.argv.indexOf(name)
  return i > 0 ? process.argv[i + 1] : null
}
d.setOutDir(arg('--out') ?? 'plan')
const SERVER = arg('--server') ?? 'http://127.0.0.1:3456'
const tokenArg = process.argv.slice(2).find((a, i, all) => !a.startsWith('--') && !all[i - 1]?.startsWith('--'))
const TOKEN = resolveToken(tokenArg ?? DEFAULT_TOKEN_PATH)
if (!TOKEN) {
  console.error('no API token found')
  process.exit(2)
}

const PREFIX = 'PLND '
const NULL_DATE = '0001-01-01T00:00:00Z'
const hourPx = v.px(56)

let failures = 0
function check(ok, what) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${what}`)
  if (!ok) failures++
}

async function step(name, fn) {
  try {
    await fn()
  } catch (e) {
    failures++
    console.log(`FAIL ${name}: ${e.message}`)
    try { d.shot(`error-${name}`) } catch { /* ignore */ }
  }
}

// ---- the server ---------------------------------------------------------------------------------

async function api(method, path, body) {
  const res = await fetch(`${SERVER}/api/v2${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  })
  if (!res.ok) throw new Error(`${method} ${path} answered ${res.status}`)
  return res.status === 204 ? null : res.json()
}

const list = (json) => (Array.isArray(json) ? json : json?.items ?? [])
const getTask = (id) => api('GET', `/tasks/${id}`)

async function deleteLeftovers() {
  const found = list(await api('GET', `/tasks?per_page=100&q=${encodeURIComponent(PREFIX.trim())}`))
  for (const task of found) {
    if (task.title?.startsWith(PREFIX)) await api('DELETE', `/tasks/${task.id}`).catch(() => {})
  }
}

/** Waits until the server's task answers `pred`; returns it, or the last one read. */
async function serverTask(id, pred, ms = 30000) {
  const end = Date.now() + ms
  for (;;) {
    const task = await getTask(id)
    if (pred(task) || Date.now() > end) return task
    await d.sleep(800)
  }
}

// ---- the device's clock --------------------------------------------------------------------------

const today = d.sh('date +%Y-%m-%d').trim()
const offset = d.sh('date +%z').trim()
const offsetMinutes = (offset[0] === '-' ? -1 : 1) * (+offset.slice(1, 3) * 60 + +offset.slice(3, 5))
const midnightUtc = Date.parse(`${today}T00:00:00Z`) - offsetMinutes * 60000
/** A minute of the device's local today as an instant string. */
const at = (minute) => new Date(midnightUtc + minute * 60000).toISOString().replace('.000Z', 'Z')
const dateOnlyToday = () => new Date(midnightUtc + (24 * 3600 - 1) * 1000).toISOString().replace('.000Z', 'Z')
const minuteOf = (iso) => Math.round((Date.parse(iso) - midnightUtc) / 60000)
const isNull = (iso) => !iso || iso.startsWith('0001-01-01')
const hm = (m) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
/** The device's local date of an instant string. */
const dayOf = (iso) => new Date(Date.parse(iso) + offsetMinutes * 60000).toISOString().slice(0, 10)
const tomorrow = new Date(Date.parse(`${today}T00:00:00Z`) + 86400000).toISOString().slice(0, 10)

// ---- the screen ----------------------------------------------------------------------------------

async function waitFor(pred, what, ms = 60000) {
  const end = Date.now() + ms
  for (;;) {
    const nodes = d.dump()
    const hit = pred(nodes)
    if (hit) return hit
    // A slow emulator can raise "isn't responding" while it catches up.
    const wait = nodes.find((n) => n.text === 'Wait')
    if (wait && nodes.some((n) => /isn.t responding/.test(n.text ?? ''))) await d.tap(wait.cx, wait.cy, 1500)
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`)
    await d.sleep(700)
  }
}

const byText = (t) => (nodes) => nodes.find((n) => n.text === t)
const byDesc = (t) => (nodes) => nodes.find((n) => n['content-desc'] === t)
const descStarts = (t) => (nodes) => nodes.find((n) => n['content-desc']?.startsWith(t))
const tapNode = (n, wait = 1200) => d.tap(n.cx, n.cy, wait)

/** Scrolls the Settings list up or down until the node is on screen. */
async function scrollTo(pred, label) {
  const s = v.sc
  for (const [from, to] of [[0.8, 0.3], [0.3, 0.8]]) {
    for (let i = 0; i < 12; i++) {
      const n = pred(d.dump())
      if (n) return n
      await d.swipe(Math.round(s.width * 0.4), Math.round(s.height * from), Math.round(s.width * 0.4), Math.round(s.height * to), 350, 700)
    }
  }
  throw new Error(`not found after scrolling: ${label}`)
}

/** Scrolls the timeline until the block's top is in the upper part of the viewport. */
async function revealOnGrid(pred, label) {
  const s = v.sc
  const x = v.px(30) // the hour gutter, where no block takes the swipe
  // Held before the finger lifts, so the grid does not fling past what it is looking for.
  const drag = async (y1, y2) => {
    const g = await d.holdGesture(x, y1, x, y2, 1, { steps: 6, stepMs: 50 })
    await g.release(500)
  }
  const high = Math.round(s.height * 0.35)
  const low = Math.round(s.height * 0.85)
  for (let i = 0; i < 8; i++) {
    const n = pred(d.dump())
    if (n && n.y1 > s.height * 0.28 && n.y1 < s.height * 0.5) return n
    if (n && n.y1 >= s.height * 0.5) await drag(n.y1, high)
    else if (n) await drag(high, Math.round(s.height * 0.42))
    else await drag(high, low)
  }
  // Nothing above: it may lie further down.
  for (let i = 0; i < 8; i++) {
    const n = pred(d.dump())
    if (n) return n
    await drag(low, high)
  }
  throw new Error(`not on the timeline: ${label}`)
}

/** Press and hold, travel, let go, with raw events so the long press is real. */
async function holdAndDrag(x1, y1, x2, y2, { holdMs = 1100, steps = 8 } = {}) {
  d.motionevent('DOWN', x1, y1)
  await d.sleep(holdMs)
  for (let i = 1; i <= steps; i++) {
    d.motionevent('MOVE', Math.round(x1 + ((x2 - x1) * i) / steps), Math.round(y1 + ((y2 - y1) * i) / steps))
    await d.sleep(90)
  }
  await d.sleep(350)
  d.motionevent('UP', x2, y2)
  await d.sleep(1200)
}

/**
 * Press and hold at the first point, then travel through the others; a third number on a point rests
 * there that long before going on.
 */
async function holdPath(points, { holdMs = 1100, steps = 8 } = {}) {
  let [px, py] = points[0]
  d.motionevent('DOWN', px, py)
  await d.sleep(holdMs)
  for (const [x, y, rest = 0] of points.slice(1)) {
    for (let i = 1; i <= steps; i++) {
      d.motionevent('MOVE', Math.round(px + ((x - px) * i) / steps), Math.round(py + ((y - py) * i) / steps))
      await d.sleep(90)
    }
    px = x
    py = y
    if (rest) await d.sleep(rest)
  }
  await d.sleep(350)
  d.motionevent('UP', px, py)
  await d.sleep(1200)
}

/**
 * The strip's seven days, left to right: the node with the day's name. The selected day is marked
 * checked on its tap target, a sibling node with the same bounds, so each cell carries it as `isSelected`.
 */
function stripCells(nodes = d.dump()) {
  const todayCell = nodes.find((n) => n['content-desc']?.startsWith('Today, '))
  if (!todayCell) return []
  return nodes
    .filter((n) => n['content-desc'] && Math.abs(n.cy - todayCell.cy) < v.px(20))
    .map((n) => ({ ...n, isSelected: nodes.some((m) => m.bounds === n.bounds && (m.selected || m.checked)) }))
    .sort((a, b) => a.cx - b.cx)
}

const selectedCell = () => stripCells().find((n) => n.isSelected)
const selectedIsToday = () => selectedCell()?.['content-desc']?.startsWith('Today, ') === true

async function backToToday() {
  if (selectedIsToday()) return
  const cell = stripCells().find((n) => n['content-desc'].startsWith('Today, '))
  if (cell) await tapNode(cell, 1800)
}

/**
 * The way into the drawer's filter: the search button in its header, which shows the field and puts
 * the cursor in it, or the field itself once it shows. Both carry the name.
 */
const filterBox = (nodes) => nodes.find((n) => n['content-desc'] === 'Filter these tasks' || (n.class === 'android.widget.EditText' && n.cx > v.sc.width * 0.4))

/** The drawer's header, which names the list on show with its count ("Tasks from Today, 8"). */
const listPicker = descStarts('Tasks from ')

/** A line of the header's menu: the lowest match in the drawer's half, above the bottom bar. */
const inMenu = (text) => (nodes) =>
  nodes
    .filter((n) => n.text === text && n.cx > v.sc.width * 0.4 && n.cy < v.sc.height * 0.85)
    .sort((a, b) => b.cy - a.cy)[0]

/** A task in the open drawer, found with the filter box: the seeded day holds more than one screen. */
async function inDrawer(title, word) {
  await tapNode(await waitFor(filterBox, 'the filter box'), 700)
  await d.clearField()
  // One letter at a time: the field takes its text back from the view model, and a burst comes out mixed up.
  await d.typeSlow(word, { chunk: 1, pause: 250 })
  await d.back()
  // The filter field holds the same words: the row is the one that is not a text field.
  return waitFor((ns) => ns.find((n) => n.text === title && n.class !== 'android.widget.EditText' && n.cx > v.sc.width * 0.4), `${title} in the drawer`)
}

/** A chip of the No time row, scrolled into sight. */
async function inTopRow(title) {
  for (let i = 0; i < 14; i++) {
    const nodes = d.dump()
    const chip = nodes.find((n) => n.text === title)
    if (chip) return chip
    const row = nodes.find((n) => n['content-desc'] === 'Tasks without a time')
    if (!row) break
    await d.swipe(Math.round(v.sc.width * 0.85), row.cy, Math.round(v.sc.width * 0.35), row.cy, 350, 600)
  }
  throw new Error(`not in the No time row: ${title}`)
}

/** Chips that wrap: by line, then left to right. */
const readingOrder = (a, b) => (Math.abs(a.cy - b.cy) > v.px(12) ? a.cy - b.cy : a.cx - b.cx)

/** The snackbar's Undo, while it shows. */
async function undo() {
  await tapNode(await waitFor(byText('Undo'), 'Undo on the snackbar', 8000), 1500)
}

async function settingsSwitch(on) {
  // A cold start can hold the screen back for a while on a busy machine.
  await waitFor(byDesc('Open menu'), 'the app')
  await v.drawer('Settings')
  const general = d.dump().find((n) => n.text === 'General')
  if (general) await tapNode(general)
  let row = await scrollTo(byText('Show Plan in the drawer'), 'the Plan switch')
  // High on the screen, so the options under it are in the dump when it is on.
  const x = Math.round(v.sc.width * 0.4)
  await d.swipe(x, row.cy, x, Math.round(v.sc.height * 0.3), 600, 1000)
  row = byText('Show Plan in the drawer')(d.dump()) ?? row
  const isOn = d.dump().some((n) => n.text === 'Default length')
  if (isOn !== on) await tapNode(row, 1800)
  return row
}

// ---- the run -------------------------------------------------------------------------------------

const created = {}
async function create(key, fields) {
  const task = await api('POST', `/projects/${projectId}/tasks`, { title: `${PREFIX}${key}`, ...fields })
  created[key] = task.id
  return task
}

let projectId
try {
  await deleteLeftovers()
  const projects = list(await api('GET', '/projects?per_page=200'))
  // A project of the test user's own, not the Inbox (a repeat of the seed's work is not wanted).
  const own = projects.find((p) => p.title === 'Inbox') ?? projects.find((p) => p.id > 0 && !p.is_archived)
  projectId = own.id

  await create('block', { start_date: at(9 * 60), end_date: at(9 * 60 + 30) })
  await create('pick', { due_date: dateOnlyToday() })
  await create('drag', { due_date: dateOnlyToday() })
  await create('start only', { start_date: at(10 * 60) })

  d.stop()
  await v.launch(5000)
  await waitFor((nodes) => nodes.some((n) => n['content-desc'] === 'Open menu'), 'the app')

  await step('settings-off', async () => {
    await settingsSwitch(false)
    await v.openDrawer()
    check(!d.dump().some((n) => n.text === 'Plan'), 'planner off: no Plan in the drawer')
    await v.closeDrawer()
    await v.nav('Today')
    check(!d.dump().some((n) => n.text === 'Day'), 'planner off: no Day switch on Today')
  })

  await step('today-with-a-block', async () => {
    await v.nav('Today')
    const title = await scrollTo(byText(`${PREFIX}block`), 'the block on Today').catch(() => null)
    check(!!title, 'Today lists a task with a block starting today and no due date')
    check(!d.dump().some((n) => n.text === `${PREFIX}start only`), 'Today leaves out a start date alone')
    const nodes = d.dump()
    const shown = nodes.some((n) => /(9|09)[:.]00/.test(n.text ?? ''))
    check(shown, 'the row shows the block range')
    d.shot('light-today-list')
  })

  await step('settings-on', async () => {
    await settingsSwitch(true)
    const nodes = await waitFor((ns) => (ns.some((n) => n.text === 'Default length') ? ns : null), 'the planner options')
    check(nodes.some((n) => n.text === 'Default length'), 'Settings offers the default length once on')
    await scrollTo(byText('Work hours'), 'work hours')
    check(true, 'Settings offers the work hours once on')
    d.shot('light-settings')
    await v.openDrawer()
    check(d.dump().some((n) => n.text === 'Plan'), 'planner on: Plan is in the drawer')
    await v.closeDrawer()
  })

  await step('day-view', async () => {
    await v.nav('Today')
    await waitFor(byText('List'), 'the List switch')
    const day = await waitFor(byText('Day'), 'the Day switch')
    await tapNode(day, 2500)
    const totals = await waitFor((ns) => ns.find((n) => /planned · .* free$/.test(n.text ?? '')), 'the totals')
    check(!!totals, `Day view has the totals: ${totals.text}`)
    // The chips scroll sideways, and the seeded day may hold many.
    const plan = await waitFor(byText('Plan'), 'the Plan button')
    let chip = null
    for (let i = 0; i < 14 && !chip; i++) {
      chip = d.dump().find((n) => n.text === `${PREFIX}pick`)
      if (!chip) await d.swipe(Math.round(v.sc.width * 0.85), plan.y2 + v.px(26), Math.round(v.sc.width * 0.15), plan.y2 + v.px(26), 350, 600)
    }
    check(!!chip, 'a task without a time is a chip')
    const block = await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block')
    check(!!block, `the block is on the timeline: ${block['content-desc']}`)
    check(d.dump().some((n) => n['content-desc'] === `Complete ${PREFIX}block`), 'a block can be ticked from Day')
    check(!d.dump().some((n) => n['content-desc'] === `Length of ${PREFIX}block`), 'nothing else changes from Day: no length handle')
    d.shot('light-today-day')
  })

  await step('open-plan', async () => {
    const plan = await waitFor(byText('Plan'), 'the Plan button')
    await tapNode(plan, 2800)
    await waitFor(byDesc('Show tasks to plan'), 'the tab at the right edge')
    const nodes = d.dump()
    const sun = nodes.find((n) => n['content-desc'] === 'Plan my day')
    check(!!sun, 'the sun button is there')
    // The icon carries the name, and the button around it the click.
    const sunButton = sun?.clickable ? sun : nodes[sun?.parent]
    check(sunButton?.clickable === true && sunButton?.enabled !== false, 'the sun button can be pressed')
    check(nodes.some((n) => n['content-desc'] === 'Plan settings'), 'the gear is there')
    check(nodes.some((n) => n['content-desc']?.startsWith('Today, ')), 'the strip has today')
    d.shot('light-plan')
  })

  await step('pick-and-place', async () => {
    const block = await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block')
    const tab = await waitFor(byDesc('Show tasks to plan'), 'the tab')
    await tapNode(tab, 1500)
    const row = await inDrawer(`${PREFIX}pick`, 'pick')
    d.shot('light-plan-drawer')
    await tapNode(row, 1500)
    await waitFor(byText(`Tap a time for ${PREFIX}pick`), 'the placing banner')
    d.shot('light-plan-picked')
    // Two hours under the block's top: 11:00.
    await d.tap(Math.round(v.sc.width / 2), block.y1 + hourPx * 2, 1500)
    const task = await serverTask(created.pick, (t) => !isNull(t.start_date))
    check(minuteOf(task.start_date) === 11 * 60, `placing wrote the start: ${hm(minuteOf(task.start_date))}`)
    check(minuteOf(task.end_date) === 11 * 60 + 30, `placing wrote the default length: ${hm(minuteOf(task.end_date))}`)
    check(Date.parse(task.due_date) === Date.parse(dateOnlyToday()), 'a date-only due date on that day stays')
    d.shot('light-plan-placed')
  })

  await step('length-handle', async () => {
    const handle = await waitFor(byDesc(`Length of ${PREFIX}pick`), 'the length handle')
    await d.swipe(handle.cx, handle.cy, handle.cx, handle.cy + hourPx, 900, 1500)
    const task = await serverTask(created.pick, (t) => minuteOf(t.end_date) !== 11 * 60 + 30)
    check(minuteOf(task.start_date) === 11 * 60, 'the handle leaves the start')
    check(minuteOf(task.end_date) === 12 * 60 + 30, `the handle moved the end by the finger: ${hm(minuteOf(task.end_date))}`)
  })

  await step('the-strip', async () => {
    const nodes = d.dump()
    const todayCell0 = nodes.find((n) => n['content-desc']?.startsWith('Today, '))
    const cells = nodes.filter((n) => n['content-desc'] && Math.abs(n.cy - todayCell0.cy) < v.px(20))
    const next = cells.find((n) => !n['content-desc'].startsWith('Today'))
    check(cells.length === 7, `the strip has seven days: ${cells.map((n) => n['content-desc']).join(' | ')}`)
    await tapNode(next, 1800)
    check(!d.dump().some((n) => n['content-desc']?.startsWith(`${PREFIX}block,`)), 'another day has its own blocks')
    d.shot('light-plan-another-day')
    const todayCell = d.dump().find((n) => n['content-desc']?.startsWith('Today, '))
    await tapNode(todayCell, 1800)
    await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block back on today')
    check(true, 'tapping today brings it back')
  })

  await step('the-lists', async () => {
    const tab = await waitFor(byDesc('Show tasks to plan'), 'the tab')
    // A swipe on the tab itself opens the drawer too.
    await d.swipe(tab.cx, tab.cy, tab.cx - v.px(60), tab.cy, 250, 1500)
    await waitFor(filterBox, 'the drawer from a swipe on the tab')
    check(true, 'a swipe on the tab opens the drawer')
    await tapNode(await waitFor(filterBox, 'the filter box'), 700)
    await d.clearField()
    await d.typeSlow('dra', { chunk: 3 })
    await waitFor(byText(`${PREFIX}drag`), 'the filtered list')
    check(!d.dump().some((n) => n.text === 'Buy groceries'), 'the filter box narrows the list')
    await d.clearField()
    await d.back()
    // The header names the list on show with its count; the other lists are behind it.
    const header = await waitFor(listPicker, 'the header with the list')
    check(/^Tasks from Today, \d+$/.test(header['content-desc']), `the header names the list and its count: ${header['content-desc']}`)
    await tapNode(header, 1200)
    const menu = await waitFor((ns) => (inMenu('Next 7 days')(ns) ? ns : null), 'the menu of lists')
    check(
      ['Today', 'Inbox', 'Next 7 days', 'No date', 'A project'].every((text) => !!inMenu(text)(menu)),
      'the menu has the four lists and the way to a project',
    )
    d.shot('light-plan-lists')
    await tapNode(inMenu('A project')(menu), 1200)
    await waitFor(byText('Find a project'), 'the project finder')
    check(d.dump().some((n) => n.text === 'Projects'), 'A project opens the projects')
    await tapNode(await waitFor(byDesc('Back to the tasks'), 'the way back to the tasks'), 1200)
    await tapNode(await waitFor(listPicker, 'the header with the list'), 1200)
    await tapNode(await waitFor(inMenu('Inbox'), 'Inbox in the menu'), 1500)
    const inbox = await waitFor(descStarts('Tasks from Inbox, '), 'the Inbox list')
    check(/^Tasks from Inbox, \d+$/.test(inbox['content-desc']), 'a list can be chosen')
    d.shot('light-plan-inbox')
    await tapNode(inbox, 1200)
    await tapNode(await waitFor(inMenu('Today'), 'Today in the menu'), 1500)
    await waitFor(descStarts('Tasks from Today, '), 'the day list again')
    await tapNode(await waitFor(byDesc('Close'), 'Close'), 1200)
  })

  await step('a-past-day', async () => {
    const strip = await waitFor((ns) => ns.find((n) => n['content-desc']?.startsWith('Today, ')), 'the strip')
    await d.swipe(strip.cx + v.px(40), strip.cy, strip.cx + v.px(240), strip.cy, 300, 2000)
    const back = await waitFor(byText('Today'), 'the way back to today', 8000)
    await tapNode(await waitFor(byDesc('Show tasks to plan'), 'the tab'), 1500)
    d.shot('light-plan-past-day')
    await tapNode(await waitFor(byDesc('Close'), 'Close'), 1000)
    check(!d.dump().some((n) => n['content-desc']?.startsWith('Length of ')), 'a past day has no length handles')
    await tapNode(back, 1800)
    await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block on today again')
  })

  await step('press-and-hold-moves', async () => {
    const block = await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block')
    await holdAndDrag(block.cx, block.cy, block.cx, block.cy + hourPx)
    const task = await serverTask(created.block, (t) => minuteOf(t.start_date) !== 9 * 60)
    check(minuteOf(task.start_date) === 10 * 60, `a press and hold moved it: ${hm(minuteOf(task.start_date))}`)
    check(minuteOf(task.end_date) === 10 * 60 + 30, 'the move kept the length')
  })

  await step('swipe-days', async () => {
    await backToToday()
    await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block')
    const y = Math.round(v.sc.height * 0.7)
    await d.swipe(Math.round(v.sc.width * 0.8), y, Math.round(v.sc.width * 0.2), y, 220, 1800)
    const next = stripCells()[1]
    const shown = selectedCell()
    check(!!next && shown?.['content-desc'] === next['content-desc'], `a swipe to the left shows the next day: ${shown?.['content-desc']}`)
    check(!d.dump().some((n) => n['content-desc']?.startsWith(`${PREFIX}block,`)), 'the next day has its own blocks')
    d.shot('light-plan-swiped')
    await d.swipe(Math.round(v.sc.width * 0.2), y, Math.round(v.sc.width * 0.8), y, 220, 1800)
    check(selectedIsToday(), 'a swipe to the right comes back')
    // A slow, short sideways move is not a swipe.
    await d.swipe(Math.round(v.sc.width * 0.6), y, Math.round(v.sc.width * 0.5), y, 600, 1200)
    check(selectedIsToday(), 'a short sideways move stays on the day')
  })

  await step('drop-on-a-strip-day', async () => {
    await backToToday()
    await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block')
    await d.sleep(1200)
    const block = await waitFor(descStarts(`${PREFIX}block,`), 'the block, settled')
    const dueBefore = (await getTask(created.block)).due_date
    const cell = stripCells()[1]
    // Let go on the day at once: the same times on that day.
    await holdAndDrag(block.cx, block.cy, cell.cx, cell.cy)
    let task = await serverTask(created.block, (t) => dayOf(t.start_date) === tomorrow)
    check(dayOf(task.start_date) === tomorrow, `a drop on a day of the strip moves it there: ${dayOf(task.start_date)}`)
    check(minuteOf(task.start_date) - 1440 === 10 * 60 && minuteOf(task.end_date) - 1440 === 10 * 60 + 30, 'the same times on that day')
    check(Date.parse(task.due_date) > Date.parse(dateOnlyToday()), 'the due rule gave it a due date on the new day')
    check(!selectedIsToday(), 'Plan follows the block to its day')
    d.shot('light-plan-moved-by-strip')
    await undo()
    task = await serverTask(created.block, (t) => dayOf(t.start_date) === today)
    check(dayOf(task.start_date) === today && minuteOf(task.start_date) === 10 * 60, 'Undo puts it back')
    check(Date.parse(task.due_date) === Date.parse(dueBefore), 'Undo puts the due date back as it was')
  })

  await step('move-by-the-sheet', async () => {
    await backToToday()
    const block = await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block')
    await d.tap(block.cx, block.cy, 1800)
    await waitFor(byText('Day'), 'the Day row')
    const days = d.dump().filter((n) => n['content-desc']?.startsWith('Move to ')).sort(readingOrder)
    check(days.length >= 2, `the sheet offers days: ${days.map((n) => n['content-desc']).join(' | ')}`)
    check(d.dump().some((n) => n.text === 'Pick a date'), 'the sheet offers Pick a date')
    d.shot('light-block-sheet-days')
    await tapNode(days[1], 1800)
    let task = await serverTask(created.block, (t) => dayOf(t.start_date) === tomorrow)
    check(dayOf(task.start_date) === tomorrow && minuteOf(task.start_date) - 1440 === 10 * 60, 'the Day row moves it, the same times')
    check(d.dump().some((n) => /^Moved to /.test(n.text ?? '')), 'the snackbar says where it went')
    // Back with the sheet on the new day.
    const moved = await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block on its new day')
    await d.tap(moved.cx, moved.cy, 1800)
    await waitFor(byText('Day'), 'the Day row')
    const first = d.dump().filter((n) => n['content-desc']?.startsWith('Move to ')).sort(readingOrder)[0]
    await tapNode(first, 1800)
    task = await serverTask(created.block, (t) => dayOf(t.start_date) === today)
    check(dayOf(task.start_date) === today && minuteOf(task.start_date) === 10 * 60, 'and back to today')
  })

  await step('rest-on-a-day', async () => {
    await backToToday()
    await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block')
    await d.sleep(1200)
    const block = await waitFor(descStarts(`${PREFIX}block,`), 'the block, settled')
    const cell = stripCells()[1]
    // Rest on the day until it opens, then down onto its timeline.
    await holdPath([[block.cx, block.cy], [cell.cx, cell.cy, 1400], [block.cx, block.cy + hourPx]])
    const task = await serverTask(created.block, (t) => dayOf(t.start_date) === tomorrow)
    const start = minuteOf(task.start_date) - 1440
    check(dayOf(task.start_date) === tomorrow && start % 15 === 0, `resting on a day opened it and the drop landed there: ${dayOf(task.start_date)} ${hm(start)}`)
    check(minuteOf(task.end_date) - minuteOf(task.start_date) === 30, 'the drag kept the length')
    d.shot('light-plan-moved-by-rest')
    // Back with the sheet: its due date may have stayed a deadline on that day (16.2), so there is no Undo to rely on.
    const moved = await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block on its new day')
    await d.tap(moved.cx, moved.cy, 1800)
    await waitFor(byText('Day'), 'the Day row')
    await tapNode(d.dump().filter((n) => n['content-desc']?.startsWith('Move to ')).sort(readingOrder)[0], 1800)
    await serverTask(created.block, (t) => dayOf(t.start_date) === today)
    await backToToday()
  })

  await step('drag-from-the-drawer', async () => {
    const tab = await waitFor(byDesc('Show tasks to plan'), 'the tab')
    await tapNode(tab, 1500)
    const row = await inDrawer(`${PREFIX}drag`, 'drag')
    const target = d.dump().find((n) => n['content-desc']?.startsWith(`${PREFIX}pick,`))
    const y = target ? target.y1 : Math.round(v.sc.height * 0.5)
    await holdAndDrag(row.cx, row.cy, v.px(90), y, { steps: 10 })
    const task = await serverTask(created.drag, (t) => !isNull(t.start_date))
    const start = minuteOf(task.start_date)
    check(!isNull(task.start_date) && start % 15 === 0, `the drop landed on a quarter: ${hm(start)}`)
    check(minuteOf(task.end_date) - start === 30, 'the drop took the default length')
    check(Date.parse(task.due_date) === Date.parse(dateOnlyToday()), 'the drop left a date-only due date')
    await waitFor(listPicker, 'the drawer coming back', 8000)
    check(true, 'the drawer came back after the drop')
    d.shot('light-plan-dropped')
    const close = d.dump().find((n) => n['content-desc'] === 'Close')
    if (close) await tapNode(close, 1200)
  })

  await step('the-top-row', async () => {
    await backToToday()
    // PLND drag has a block now; PLND pick is placed too. A new date-only task shows in the row.
    await create('row', { due_date: dateOnlyToday() })
    d.stop()
    await v.launch(5000)
    await waitFor(byDesc('Open menu'), 'the app')
    await v.drawer('Plan')
    await waitFor(byDesc('Show tasks to plan'), 'the Plan screen')
    const chip = await inTopRow(`${PREFIX}row`)
    check(d.dump().some((n) => n['content-desc'] === 'Tasks without a time'), 'Plan has the No time row')
    check(d.dump().some((n) => n['content-desc'] === `Complete ${PREFIX}row`), 'a chip can be ticked')
    d.shot('light-plan-top-row')
    await tapNode(chip, 1500)
    await waitFor(byText(`Tap a time for ${PREFIX}row`), 'the placing banner from a chip')
    check(true, 'a tap on a chip picks it for a time')
    await tapNode(await waitFor(byText('Cancel'), 'Cancel'), 1200)
  })

  await step('drop-on-the-top-row', async () => {
    const block = await revealOnGrid(descStarts(`${PREFIX}drag,`), 'the dropped block')
    const row = await waitFor(byDesc('Tasks without a time'), 'the No time row')
    await holdAndDrag(block.cx, block.cy, Math.round(v.sc.width / 2), row.cy, { steps: 12 })
    const task = await serverTask(created.drag, (t) => isNull(t.start_date))
    check(isNull(task.start_date) && isNull(task.end_date), 'a block dropped on the No time row loses its time')
    check(Date.parse(task.due_date) === Date.parse(dateOnlyToday()), 'and keeps that day, date only')
    await inTopRow(`${PREFIX}drag`)
    check(true, 'it is in the No time row now')
  })

  await step('the-sheet', async () => {
    const block = await revealOnGrid(descStarts(`${PREFIX}pick,`), 'the placed block')
    await d.tap(block.cx, block.cy, 1800)
    await waitFor(byText('Remove time'), 'the sheet')
    const nodes = d.dump()
    check(nodes.some((n) => n.text === 'Open task'), 'the sheet offers Open task')
    check(nodes.some((n) => n.text === 'Length'), 'the sheet offers exact lengths')
    d.shot('light-block-sheet')

    await tapNode(await waitFor(byText('45m'), 'the 45m length'), 1500)
    let task = await serverTask(created.pick, (t) => minuteOf(t.end_date) - minuteOf(t.start_date) === 45)
    check(minuteOf(task.end_date) - minuteOf(task.start_date) === 45, 'an exact length from the sheet')

    await tapNode(await waitFor(byText('Later'), 'the nudge'), 1500)
    task = await serverTask(created.pick, (t) => minuteOf(t.start_date) === 11 * 60 + 15)
    check(minuteOf(task.start_date) === 11 * 60 + 15, 'a nudge moves it by a quarter')
    check(minuteOf(task.end_date) - minuteOf(task.start_date) === 45, 'a nudge keeps the length')

    await tapNode(await waitFor(byText('Remove time'), 'Remove time'), 1800)
    task = await serverTask(created.pick, (t) => isNull(t.start_date))
    check(isNull(task.start_date) && isNull(task.end_date), 'Remove time clears both dates')
    check(Date.parse(task.due_date) === Date.parse(dateOnlyToday()), 'Remove time leaves the due date')
    await d.sleep(800)
    check(!d.dump().some((n) => n['content-desc']?.startsWith(`${PREFIX}pick,`)), 'the block has left the timeline')
    d.shot('light-plan-time-removed')
  })

  await step('dark', async () => {
    d.night(true)
    await d.sleep(3500)
    await waitFor(byDesc('Show tasks to plan'), 'the Plan screen in dark')
    await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block')
    d.shot('dark-plan')
    await tapNode(await waitFor(byDesc('Show tasks to plan'), 'the tab'), 1500)
    await waitFor(listPicker, 'the drawer')
    d.shot('dark-plan-drawer')
    await tapNode(await waitFor(byDesc('Close'), 'Close'), 1200)
    const block = await waitFor(descStarts(`${PREFIX}block,`), 'the block')
    await d.tap(block.cx, block.cy, 1800)
    await waitFor(byText('Remove time'), 'the sheet')
    d.shot('dark-block-sheet')
    await d.back()
    await v.nav('Today')
    await waitFor(byText('Day'), 'the Day switch')
    await revealOnGrid(descStarts(`${PREFIX}block,`), 'the block').catch(() => {})
    d.shot('dark-today-day')
    await tapNode(await waitFor(byText('List'), 'the List switch'), 2000)
    await d.sleep(800)
    d.shot('dark-today-list')
  })
} catch (e) {
  failures++
  console.log(`FAIL ${e.message}`)
  try { d.shot('error') } catch { /* ignore */ }
} finally {
  try { d.night(false) } catch { /* ignore */ }
  for (const id of Object.values(created)) await api('DELETE', `/tasks/${id}`).catch(() => {})
  await deleteLeftovers().catch(() => {})
  if (!process.argv.includes('--keep-on')) {
    await settingsSwitch(false).catch((e) => console.log(`could not turn the planner off again: ${e.message}`))
  }
}

console.log(failures ? `${failures} failed` : 'all passed')
process.exit(failures ? 1 : 0)
