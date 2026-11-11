// Events and calendars on the Plan screen (docs/cross-app-semantics-v1.md section 20): an Event made
// by a press and hold on an empty time, its sheet, Skip this one with Undo, the gear's list, then a
// phone calendar: a local calendar account with two events made through the calendar provider, the
// permission asked when Calendars opens, the events on the timeline and the all-day row, and Busy
// only. Every Event is asserted on the synced preferences carrier the server holds.
//
//   node plan-events.mjs [tokenPath] [--out DIR] [--server http://127.0.0.1:3456]
//
// Run `node login.mjs` first and turn the planner on (plan.mjs --keep-on does). It makes only Events
// titled `PEVT ...` and a local calendar account `vicu-test`, and removes them at the end (its own,
// and what an earlier run left behind). It never seeds the server.

import * as d from './droid.mjs'
import * as v from './nav.mjs'
import { DEFAULT_TOKEN_PATH, resolveToken } from './login.mjs'
import { audit, printFindings } from './a11y.mjs'

const arg = (name) => {
  const i = process.argv.indexOf(name)
  return i > 0 ? process.argv[i + 1] : null
}
d.setOutDir(arg('--out') ?? 'plan-events')
const SERVER = arg('--server') ?? 'http://127.0.0.1:3456'
const tokenArg = process.argv.slice(2).find((a, i, all) => !a.startsWith('--') && !all[i - 1]?.startsWith('--'))
const TOKEN = resolveToken(tokenArg ?? DEFAULT_TOKEN_PATH)
if (!TOKEN) {
  console.error('no API token found')
  process.exit(2)
}

const PREFIX = 'PEVT '
const ACCOUNT = 'vicu-test'
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

// ---- the server: the preferences carrier ---------------------------------------------------------

async function api(method, path) {
  const res = await fetch(`${SERVER}/api/v2${path}`, {
    method,
    headers: { Authorization: `Bearer ${TOKEN}` },
    signal: AbortSignal.timeout(15000),
  })
  if (!res.ok) throw new Error(`${method} ${path} answered ${res.status}`)
  return res.status === 204 ? null : res.json()
}
const list = (json) => (Array.isArray(json) ? json : json?.items ?? [])

/** The Event entries the server's preferences carrier holds, by key. */
async function serverEvents() {
  const found = list(await api('GET', `/tasks?per_page=50&q=vicu-prefs`))
  const out = {}
  for (const task of found) {
    // `<!-- vicu-prefs:v1:<base64url JSON> -->` (SyncedPrefsEnvelope).
    const m = /<!--\s*vicu-prefs:v1:([A-Za-z0-9_-]+={0,2})\s*-->/.exec(task.description ?? '')
    if (!m) continue
    let doc
    try { doc = JSON.parse(Buffer.from(m[1], 'base64url').toString('utf8')) } catch { continue }
    for (const [key, entry] of Object.entries(doc.entries ?? {})) {
      if (key.startsWith('event.')) out[key] = entry?.value ?? null
    }
  }
  return out
}

async function waitServer(pred, what, ms = 45000) {
  const end = Date.now() + ms
  let last
  for (;;) {
    last = await serverEvents().catch(() => ({}))
    if (pred(last)) return last
    if (Date.now() > end) throw new Error(`the server never showed ${what}`)
    await d.sleep(2500)
  }
}

const ours = (events) => Object.entries(events).filter(([, value]) => value?.title?.startsWith(PREFIX))

// ---- the phone's calendar provider (through adb's content command, as the shell user) -----------

const content = (cmd) => d.sh(`content ${cmd}`)
function calendarIds() {
  const out = content(`query --uri content://com.android.calendar/calendars --projection _id:account_name`)
  return [...out.matchAll(/_id=(\d+), account_name=([^,\n]*)/g)].filter((m) => m[2].trim() === ACCOUNT).map((m) => m[1])
}
function removeCalendar() {
  for (const id of calendarIds()) {
    content(`delete --uri 'content://com.android.calendar/events?caller_is_syncadapter=true&account_name=${ACCOUNT}&account_type=LOCAL' --where 'calendar_id=${id}'`)
    content(`delete --uri 'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=${ACCOUNT}&account_type=LOCAL' --where '_id=${id}'`)
  }
}
function addCalendar() {
  content(
    `insert --uri 'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=${ACCOUNT}&account_type=LOCAL'` +
      ` --bind account_name:s:${ACCOUNT} --bind account_type:s:LOCAL --bind name:s:VicuTestWork` +
      ` --bind calendar_displayName:s:'PEVT Work' --bind calendar_color:i:-15435521 --bind calendar_access_level:i:700` +
      ` --bind ownerAccount:s:${ACCOUNT} --bind visible:i:1 --bind sync_events:i:1`,
  )
  return calendarIds()[0]
}
function addEvent(calendarId, title, startMs, endMs, { allDay = false } = {}) {
  content(
    `insert --uri content://com.android.calendar/events --bind calendar_id:i:${calendarId} --bind title:s:'${title}'` +
      ` --bind dtstart:l:${startMs} --bind dtend:l:${endMs} --bind eventTimezone:s:${allDay ? 'UTC' : tz}` +
      ` --bind allDay:i:${allDay ? 1 : 0} --bind eventLocation:s:'Room 4'`,
  )
}

// ---- the device's day ----------------------------------------------------------------------------

const today = d.sh('date +%Y-%m-%d').trim()
const tz = d.sh('getprop persist.sys.timezone').trim() || 'UTC'
const offset = d.sh('date +%z').trim()
const offsetMinutes = (offset[0] === '-' ? -1 : 1) * (+offset.slice(1, 3) * 60 + +offset.slice(3, 5))
const midnightUtc = Date.parse(`${today}T00:00:00Z`) - offsetMinutes * 60000
const atMs = (minute) => midnightUtc + minute * 60000

// ---- the screen ----------------------------------------------------------------------------------

async function waitFor(pred, what, ms = 40000) {
  const end = Date.now() + ms
  for (;;) {
    const nodes = d.dump()
    const hit = pred(nodes)
    if (hit) return hit
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`)
    await d.sleep(700)
  }
}
const byText = (t) => (nodes) => nodes.find((n) => n.text === t)
const byDesc = (t) => (nodes) => nodes.find((n) => n['content-desc'] === t)
const descHas = (t) => (nodes) => nodes.find((n) => n['content-desc']?.includes(t))
const tapNode = (n, wait = 1200) => d.tap(n.cx, n.cy, wait)

/** The hour gutter's label of [hour], in either clock: "13:00", "1:00 PM". */
const hourLabel = (hour) => (nodes) => nodes.find((n) => n.cx < v.px(80) && (n.text === `${String(hour).padStart(2, '0')}:00` ||
  n.text?.replace(/\s/g, ' ') === `${hour % 12 === 0 ? 12 : hour % 12}:00 ${hour < 12 ? 'AM' : 'PM'}`))

/** The hour a gutter label names, in either clock, or null. */
function hourOf(text) {
  const m = /^(\d{1,2}):00(?:\s?(AM|PM))?$/.exec(text?.replace(/\s/g, ' ') ?? '')
  if (!m) return null
  let h = +m[1]
  if (m[2] === 'AM' && h === 12) h = 0
  if (m[2] === 'PM' && h !== 12) h += 12
  return h
}

/** Drags the grid by its hour gutter until [hour] sits between a third and 60 % down the screen. */
async function scrollGridTo(hour) {
  const x = v.px(24)
  for (let i = 0; i < 14; i++) {
    const nodes = d.dump()
    const label = hourLabel(hour)(nodes)
    if (label && label.cy > v.sc.height * 0.3 && label.cy < v.sc.height * 0.6) return label
    // Up when it is above what shows (or high on screen), down when below.
    const shown = nodes.filter((n) => n.cx < v.px(80)).map((n) => hourOf(n.text)).filter((h) => h !== null)
    const above = label ? label.cy < v.sc.height * 0.3 : shown.length > 0 && hour < Math.min(...shown)
    const from = Math.round(v.sc.height * (above ? 0.4 : 0.75))
    const to = Math.round(v.sc.height * (above ? 0.7 : 0.45))
    const g = await d.holdGesture(x, from, x, to, 1, { steps: 6, stepMs: 50 })
    await g.release(500)
  }
  throw new Error(`hour ${hour} not on screen`)
}

/** Scrolls a settings list until the node shows. */
async function scrollTo(pred, label) {
  for (const [from, to] of [[0.8, 0.3], [0.3, 0.8]]) {
    for (let i = 0; i < 12; i++) {
      const n = pred(d.dump())
      if (n) return n
      await d.swipe(Math.round(v.sc.width * 0.4), Math.round(v.sc.height * from), Math.round(v.sc.width * 0.4), Math.round(v.sc.height * to), 350, 700)
    }
  }
  throw new Error(`not found after scrolling: ${label}`)
}

/** Turns the planner on in Settings, General, as plan.mjs does. */
async function plannerOn() {
  await v.drawer('Settings')
  const general = d.dump().find((n) => n.text === 'General')
  if (general) await tapNode(general)
  let row = await scrollTo(byText('Show Plan in the drawer'), 'the Plan switch')
  const x = Math.round(v.sc.width * 0.4)
  await d.swipe(x, row.cy, x, Math.round(v.sc.height * 0.3), 600, 1000)
  row = byText('Show Plan in the drawer')(d.dump()) ?? row
  if (!d.dump().some((n) => n.text === 'Default length')) await tapNode(row, 1800)
  await d.back()
}

async function openPlan() {
  await waitFor(byDesc('Open menu'), 'the app')
  try {
    await v.drawer('Plan')
  } catch {
    await v.closeDrawer().catch(() => {})
    await plannerOn()
    await waitFor(byDesc('Open menu'), 'the app')
    await v.drawer('Plan')
  }
  await waitFor(byDesc('Plan settings'), 'the Plan screen')
}

async function openGear() {
  let gear = await waitFor(byDesc('Plan settings'), 'the gear')
  // Scrolling the grid tucks the top bar under the status bar; scroll up a little to bring it back.
  for (let i = 0; i < 4 && gear.cy < v.px(56); i++) {
    const g = await d.holdGesture(v.px(24), Math.round(v.sc.height * 0.45), v.px(24), Math.round(v.sc.height * 0.6), 1, { steps: 6, stepMs: 50 })
    await g.release(600)
    gear = await waitFor(byDesc('Plan settings'), 'the gear')
  }
  await tapNode(gear, 1500)
}

/** Scrolls an open sheet until the node shows. */
async function inSheet(pred, label) {
  for (let i = 0; i < 10; i++) {
    const n = pred(d.dump())
    if (n) return n
    await d.swipe(Math.round(v.sc.width / 2), Math.round(v.sc.height * 0.8), Math.round(v.sc.width / 2), Math.round(v.sc.height * 0.45), 350, 700)
  }
  throw new Error(`not in the sheet: ${label}`)
}

/** Opens the calendar's choices in the Calendars sheet and sets Busy only. */
async function setBusyOnly(on) {
  if (!byText('Show only Busy, not titles')(d.dump())) await tapNode(await waitFor(byText('PEVT Work'), 'the calendar'), 1200)
  const row = await inSheet(byText('Show only Busy, not titles'), 'Busy only')
  const nodes = d.dump()
  const toggle = nodes.find((n) => n.checkable && Math.abs(n.cy - row.cy) < v.px(30))
  if ((toggle?.checked ?? false) !== on) await tapNode(row, 1200)
}

async function deleteOurEvents() {
  // Through the app: Events live in the synced preferences, which only the app writes.
  for (let i = 0; i < 6; i++) {
    await openGear()
    const row = await inSheet((ns) => ns.find((n) => n.text?.startsWith(PREFIX)) ?? ns.find((n) => n.text === 'New event'), 'the list')
    if (!row.text?.startsWith(PREFIX)) {
      await d.back()
      return
    }
    await tapNode(row, 1800)
    await tapNode(await inSheet(byText('Delete'), 'Delete'), 1800)
  }
}

// ---- the run -------------------------------------------------------------------------------------

const TITLE = `${PREFIX}Manager call`
try {
  removeCalendar()
  d.sh(`pm revoke ${d.APP_ID} android.permission.READ_CALENDAR`)
  d.stop()
  await v.launch(5000)
  await openPlan()
  await deleteOurEvents()

  await step('new-event', async () => {
    await scrollGridTo(10)
    const label = await waitFor(hourLabel(10), '10:00')
    // A press and hold on the empty time at 10:00, in the middle of the column.
    await d.longPress(Math.round(v.sc.width * 0.55), label.cy + v.px(8), 1100)
    await waitFor(byText('Task'), 'the new sheet')
    d.shot('new-sheet-task')
    await tapNode(await waitFor(byText('Event'), 'the Event switch'), 800)
    await tapNode(await waitFor((ns) => ns.find((n) => n.class === 'android.widget.EditText'), 'the title field'), 600)
    await d.typeSlow(TITLE, { chunk: 3, pause: 120 })
    await tapNode(await waitFor(byText('Repeat'), 'Repeat'), 900)
    const weekday = new Date(`${today}T12:00:00Z`).toLocaleDateString('en-US', { weekday: 'long', timeZone: 'UTC' })
    await tapNode(await inSheet(byText(`Every ${weekday}`), `Every ${weekday}`), 900)
    d.shot('new-sheet-event')
    await tapNode(await inSheet(byText('Add event'), 'Add event'), 2000)
    const block = await waitFor(descHas(`${TITLE},`), 'the Event on the timeline')
    check(/event$/.test(block['content-desc']), `the Event reads its time and title: ${block['content-desc']}`)
    d.shot('event-on-timeline')
    const server = await waitServer((e) => ours(e).length === 1, 'the new Event')
    const [, value] = ours(server)[0]
    check(value.time === '10:00' && value.minutes > 0 && value.repeat?.kind === 'weekly', `the carrier holds it: ${JSON.stringify(value)}`)
  })

  await step('event-sheet-skip', async () => {
    await tapNode(await waitFor(descHas(`${TITLE},`), 'the Event'), 1500)
    await waitFor(byText('Skip this one'), 'the Event sheet')
    d.shot('event-sheet')
    await tapNode(byText('Skip this one')(d.dump()), 1500)
    await waitFor((ns) => !ns.some((n) => n['content-desc']?.startsWith(`${TITLE},`)), 'the skipped day empty')
    const skipped = await waitServer((e) => ours(e)[0]?.[1]?.skips?.includes(today), 'the skip')
    check(ours(skipped)[0][1].skips.includes(today), 'Skip this one writes the day into skips')
    await tapNode(await waitFor(byText('Undo'), 'Undo', 8000), 2000)
    await waitFor(descHas(`${TITLE},`), 'the Event back after Undo')
    const back = await waitServer((e) => ours(e).length === 1 && !ours(e)[0][1].skips, 'the Undo')
    check(!ours(back)[0][1].skips, 'Undo puts the Event back as it was stored')
  })

  await step('a11y-plan', async () => {
    const found = audit(d.dump())
    printFindings('plan-events', found)
    check(found.filter((f) => f.rule === 'unnamed-clickable').length === 0, `no unnamed controls on Plan (${found.length} findings in all)`)
  })

  await step('calendars', async () => {
    const calendarId = addCalendar()
    check(!!calendarId, 'a local calendar account on the emulator')
    addEvent(calendarId, `${PREFIX}Dentist`, atMs(13 * 60), atMs(14 * 60))
    const day = Date.parse(`${today}T00:00:00Z`)
    addEvent(calendarId, `${PREFIX}Offsite`, day, day + 86400000, { allDay: true })

    await openGear()
    await tapNode(await inSheet(byText('Calendars'), 'Calendars'), 2500)
    // The permission is asked as the sheet opens; the system's question is answered here.
    const allow = await waitFor((ns) => ns.find((n) => /^(Allow|While using the app)$/i.test(n.text ?? '')) ?? ns.find((n) => n.text === 'PEVT Work'), 'the permission or the list')
    if (allow.text !== 'PEVT Work') {
      d.shot('calendar-permission')
      await tapNode(allow, 2500)
    }
    await waitFor(byText('PEVT Work'), 'the calendar in the list')
    d.shot('calendars-sheet')
    // Choices are per calendar id, which the provider can give again: start from Busy only off.
    await setBusyOnly(false)
    await d.back()
    await waitFor(byDesc('Plan settings'), 'Plan')
    await scrollGridTo(13)
    const dentist = await waitFor(descHas(`${PREFIX}Dentist,`), 'the phone event on the timeline')
    check(dentist['content-desc'].includes('Room 4'), `the phone event reads title, time and place: ${dentist['content-desc']}`)
    await waitFor(descHas(`${PREFIX}Offsite`), 'the all-day event in the top row')
    d.shot('phone-calendar-on-timeline')
    await tapNode(dentist, 1500)
    await waitFor((ns) => ns.find((n) => n.text?.includes('read only')), 'the read-only sheet')
    d.shot('phone-event-sheet')
    await d.back()
  })

  await step('busy-only', async () => {
    await openGear()
    await tapNode(await inSheet(byText('Calendars'), 'Calendars'), 2500)
    await waitFor(byText('PEVT Work'), 'the calendar')
    await setBusyOnly(true)
    d.shot('calendars-busy-only')
    await d.back()
    await waitFor(byDesc('Plan settings'), 'Plan')
    await scrollGridTo(13)
    const busy = await waitFor((ns) => ns.find((n) => n['content-desc']?.startsWith('Busy,') && n['content-desc'].includes('event')), 'the event as Busy')
    check(!d.dump().some((n) => (n['content-desc'] ?? n.text ?? '').includes(`${PREFIX}Dentist`)), 'Busy only leaves no title on screen')
    check(!busy['content-desc'].includes('Room 4'), 'Busy only drops the place')
    d.shot('busy-only-on-timeline')
  })
} catch (e) {
  failures++
  console.log(`FAIL run: ${e.message}`)
} finally {
  try {
    await deleteOurEvents()
    const left = await waitServer((e) => ours(e).length === 0, 'no PEVT Events', 45000).catch(() => null)
    check(left !== null, 'every PEVT Event deleted on the server')
  } catch (e) {
    console.log(`WARN cleanup: ${e.message}`)
  }
  removeCalendar()
}
console.log(failures ? `${failures} failed` : 'all passed')
process.exit(failures ? 1 : 0)
