// Screen captures of the debug app on emulator-5554.
//
//   node shots.mjs --scenario baseline --theme light     (or dark, or both)
//   node shots.mjs --wave 1 --theme dark                 (every scenario the plan lists for wave 1)
//   node shots.mjs --list
//   options: --token <path>   token file or .local folder (the a10 scenario signs in again)
//            --run <name>     write to out/<name>-<scenario>/ instead of out/<scenario>/ (keeps out/baseline intact)
//            --query <text>   search text for the search step (default "the")
//
// Captures land in out/<scenario>/<theme>-<step>.png. A step that cannot run prints FAIL and the
// run exits 1; WARN lines are expectations the app does not meet yet and never fail the run.
// Scenario ids follow the plan: a1..a12. Only baseline, a1, a2, a3, a4, a7, a6, a8, a9, a10 and a11 run today; the
// others are stubs that print STUB and pass.
import { readFileSync } from 'node:fs'
import { PNG } from 'pngjs'
import * as d from './droid.mjs'
import * as v from './nav.mjs'
import { DEFAULT_TOKEN_PATH, login, resolveToken } from './login.mjs'
import { audit, printFindings } from './a11y.mjs'

const args = process.argv.slice(2)
const opt = (n, dflt) => { const i = args.indexOf(`--${n}`); return i >= 0 ? args[i + 1] : dflt }
const has = (n) => args.includes(`--${n}`)

let failed = 0
const stubs = []

// ---- helpers shared by scenarios ----------------------------------------------------------------

function makeCtx(scenario, theme) {
  const outDir = d.setOutDir(opt('run') ? `${opt('run')}-${scenario}` : scenario)
  return {
    theme,
    outDir,
    shot: (name, clip) => d.shot(`${theme}-${name}`, clip),
    async step(name, fn) {
      try { await fn(); console.log('ok', name) } catch (e) { failed++; console.log('FAIL', name, String(e.message).split('\n')[0]) }
    },
    warn: (msg) => console.log('WARN', msg),
  }
}

/**
 * Hold a horizontal drag on a task row at `fraction` of the screen width, capture, then lift where
 * the drag started. A right swipe past 50 percent completes the task as soon as it crosses (the
 * app commits on the crossing, not on release), so `undo` reopens it afterwards and checks that
 * the checkbox is clear again: the harness must leave the data as it found it. A left swipe past
 * 50 percent opens the When sheet on the crossing, so only the partial (40 percent) left swipe
 * is captured here (a6 covers the sheet).
 */
async function swipeShot(ctx, name, dir, fraction, { undo = false } = {}) {
  const row = v.firstRow(undefined, { plain: true })
  const y = row.check.cy
  const x1 = dir === 'right' ? Math.round(v.sc.width * 0.28) : Math.round(v.sc.width * 0.78)
  const x2 = dir === 'right' ? x1 + Math.round(v.sc.width * fraction) : x1 - Math.round(v.sc.width * fraction)
  const g = await d.holdGesture(x1, y, x2, y, 1, { steps: 8 })
  ctx.shot(name)
  await g.cancel(1200)
  if (undo) {
    const cb = d.dump().find((n) => n.checkable && n['content-desc'] === row.desc)
    if (cb?.checked) {
      await d.tap(cb.cx, cb.cy, 1500)
      const again = d.dump().find((n) => n.checkable && n['content-desc'] === row.desc)
      if (again?.checked) throw new Error(`could not reopen "${row.desc}" after the swipe`)
      console.log(`  reopened "${row.desc}"`)
    }
  }
}

const DAY_HEADER = /^(Today|Tomorrow|(Mon|Tues|Wednes|Thurs|Fri|Satur|Sun)day|(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\b|(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.? \d)/i

// ---- scenarios ----------------------------------------------------------------------------------

async function baseline(ctx) {
  const { step, shot } = ctx
  for (const label of ['Inbox', 'Today', 'Upcoming', 'Anytime']) {
    await step(label.toLowerCase(), async () => { await v.nav(label); shot(label.toLowerCase()) })
  }
  await step('drawer', async () => { await v.openDrawer(1400); shot('drawer'); await v.closeDrawer() })
  await step('project', async () => { const name = await v.drawerProject(); console.log('  project:', name); shot('project') })
  await step('tag', async () => { const name = await v.drawerTag(); console.log('  tag:', name); shot('tag') })
  await step('logbook', async () => { await v.drawer('Logbook'); shot('logbook') })
  await step('review', async () => { await v.drawer('Review'); shot('review') })
  await step('settings', async () => { await v.drawer('Settings'); shot('settings') })
  await step('task-editor', async () => {
    await v.listWithRows()
    const title = await v.openFirstTask()
    console.log('  task:', title)
    shot('task-editor')
  })
  await step('date-picker', async () => {
    await v.tapFirstMatch(['Due date', 'Due:', 'When'], 1500)
    shot('date-picker')
    await d.back()
  })
  await step('quick-add', async () => {
    await v.nav('Today')
    await d.tapDesc('New task', { wait: 1800 })
    await d.clearField() // the sheet keeps an unsent draft
    await d.typeSlow('Call Ana Saturday 3pm #Personal') // the sheet parses while you type
    await d.sleep(1200)
    shot('quick-add')
    await d.clearField()
    await d.back(); await d.back()
  })
  await step('swipe-partial', async () => {
    await v.listWithRows()
    await swipeShot(ctx, 'swipe-partial', 'right', 0.4)
    await swipeShot(ctx, 'swipe-partial-left', 'left', 0.4)
  })
  await step('swipe-armed', async () => {
    await v.listWithRows()
    await swipeShot(ctx, 'swipe-armed', 'right', 0.6, { undo: true })
  })
  await step('search', async () => {
    await v.nav('Today')
    await d.tapDesc('Search', { wait: 1200 })
    await d.type(opt('query', 'the'))
    await d.sleep(1500)
    shot('search')
  })
}

/**
 * 3.9b: the drawer with project progress rings, and what they cost. The debug app logs every request
 * (tag KtorClient); the done counts are `projects/{id}/tasks?...per_page=1`. The first opening may
 * ask once per project that has a row on screen, never twice for the same project; opening the drawer
 * again inside ten minutes asks for nothing.
 */
async function rings(ctx) {
  const { step, shot, warn } = ctx
  const countRequests = () => {
    const log = d.sh('logcat -d -v brief')
    const ids = []
    for (const line of log.split(/\r?\n/)) {
      const m = line.match(/REQUEST: \S*\/projects\/(\d+)\/tasks\?\S*per_page=1(?!\d)/)
      if (m) ids.push(Number(m[1]))
    }
    return ids
  }
  const ringNodes = () => d.dump().filter((n) => /\b\d+ of \d+ done\b/.test(`${n['content-desc'] ?? ''} ${n.text ?? ''}`))
  await step('first-open', async () => {
    await v.nav('Today')
    d.sh('logcat -c')
    await v.openDrawer(2500)
    await d.sleep(2500)
    shot('drawer')
    const ids = countRequests()
    const rows = ringNodes()
    console.log(`  done-count requests: ${ids.length} (projects ${[...new Set(ids)].join(', ') || 'none'}); rings on screen: ${rows.length}`)
    for (const n of rows.slice(0, 6)) console.log('   ', (n['content-desc'] || n.text || '').slice(0, 80))
    if (new Set(ids).size !== ids.length) throw new Error('a project was asked for more than once')
    if (!rows.length) warn('no progress ring on screen (no project with tasks, or the counts were not read)')
    await v.closeDrawer()
  })
  await step('second-open', async () => {
    d.sh('logcat -c')
    await v.openDrawer(2500)
    await d.sleep(1500)
    const ids = countRequests()
    console.log(`  done-count requests on the second opening: ${ids.length}`)
    if (ids.length) warn(`the second opening asked for ${ids.length} done count(s) inside the cache window`)
    await v.closeDrawer()
  })
}

/** A4: Upcoming day groups, sticky from wave 1. */
async function a4Upcoming(ctx) {
  const { step, shot, warn } = ctx
  await step('upcoming-top', async () => { await v.nav('Upcoming'); shot('upcoming-top') })
  await step('upcoming-scrolled', async () => {
    const w = v.sc.width
    await d.swipe(Math.round(w / 2), Math.round(v.sc.height * 0.75), Math.round(w / 2), Math.round(v.sc.height * 0.35), 500, 1000)
    shot('upcoming-scrolled')
    const nodes = d.dump()
    const bandTop = v.sc.height * 0.1
    const bandBottom = v.sc.height * 0.28
    const pinned = nodes.filter((n) => n.text && DAY_HEADER.test(n.text) && n.x1 < v.px(120) && n.y1 >= bandTop && n.y1 < bandBottom)
    if (pinned.length) console.log('  header near the top while scrolled:', pinned.map((n) => `"${n.text}"`).join(', '))
    else warn('a4: no day header found pinned below the app bar after scrolling (sticky headers arrive in wave 1)')
    const headers = nodes.filter((n) => n.text && DAY_HEADER.test(n.text) && n.x1 < v.px(120))
    if (!headers.length) warn('a4: no day group headers on screen')
  })
}

/** A8: Today with the Vicu scheme and with device colours, in the theme given by --theme. */
async function a8Colours(ctx) {
  const { step, shot, theme } = ctx
  let original = null
  async function deviceColors(on) {
    await v.drawer('Settings', 1800)
    const { n: label, nodes } = await v.scrollFind((x) => /^Use device colou?rs$/.test(x.text || ''), 'Use device colours')
    const sw = nodes.find((n) => n.checkable && n.x1 > v.sc.width * 0.7 && n.cy >= label.y1 - 40 && n.cy <= label.y2 + 120)
    if (!sw) throw new Error('no switch next to "Use device colours"')
    if (original === null) original = sw.checked
    if (sw.checked !== on) { await d.tap(sw.cx, sw.cy, 1200) }
    shot(`settings-appearance-colors-${on ? 'on' : 'off'}`)
  }
  try {
    for (const on of [false, true]) {
      await step(`colors-${on ? 'on' : 'off'}`, async () => {
        await deviceColors(on)
        await v.nav('Today')
        shot(`today-colors-${on ? 'on' : 'off'}`)
        await v.nav('Inbox')
        shot(`inbox-colors-${on ? 'on' : 'off'}`)
      })
    }
  } finally {
    if (original !== null) { try { await deviceColors(original) } catch (e) { console.log('WARN could not restore "Use device colors":', e.message) } }
  }
  console.log(`  a8 theme: ${theme}`)
}

/** A10: first launch after `pm clear`, no permission prompt before setup, then set up again. */
async function a10FirstRun(ctx) {
  const { step, shot, warn } = ctx
  const tokenPath = opt('token', DEFAULT_TOKEN_PATH)
  if (!resolveToken(tokenPath)) {
    console.log('SKIP a10: no API token at', tokenPath, '(running it would sign the app out)')
    return
  }
  await step('first-launch', async () => {
    console.log('  pm clear:', d.pmClear())
    await v.launch(4000)
    shot('first-launch')
    const nodes = d.dump()
    const prompt = nodes.some((n) => n.package?.includes('permissioncontroller'))
    if (prompt) warn('a10: a permission prompt shows before setup')
    else console.log('  no permission prompt before setup')
  })
  await step('set-up', async () => {
    const notes = await login({ tokenPath, onShot: (n) => shot(n) })
    for (const n of notes) console.log('  note:', n)
    if (!notes.some((n) => n.startsWith('rationale sheet'))) warn('a10: no rationale sheet after setup')
    else if (!notes.some((n) => n.startsWith('system permission prompt'))) warn('a10: no system prompt after the rationale sheet')
    else console.log('  rationale sheet, then the system prompt, after setup')
  })
}

/** A9: accessibility report on the main screens (also available as a11y.mjs). Findings and a capture per screen go to the run folder. */
async function a9A11y(ctx) {
  for (const name of ['today', 'upcoming', 'editor', 'quick-add', 'drawer']) {
    await ctx.step(`a11y-${name}`, async () => {
      await v.SCREENS[name]()
      await d.sleep(800)
      ctx.shot(name)
      const findings = audit(d.dump())
      printFindings(`${ctx.theme}-${name}`, findings, ctx.outDir)
    })
  }
}

/** A11: font scale 1.3 on Review, Today and the editor. */
async function a11FontScale(ctx) {
  const { step, shot } = ctx
  d.fontScale(1.3)
  try {
    await d.sleep(1500)
    await step('review', async () => { await v.drawer('Review'); shot('review-font1.3') })
    await step('today', async () => { await v.nav('Today'); shot('today-font1.3') })
    await step('editor', async () => { await v.listWithRows(); await v.openFirstTask(); shot('editor-font1.3') })
  } finally {
    d.fontScale(1.0)
  }
}

/**
 * A6: the When sheet (opened by a full swipe to the left) gives the same results as the desktop
 * When panel (E5): "tomorrow 9am" is tomorrow 09:00, a calendar day is that day (date-only,
 * local 23:59:59), "Next week" is the coming Monday. The due dates are read back from the server
 * API (the local test server on this machine; the token from --token or the default .local folder).
 * It creates three throwaway tasks due today and deletes them at the end; seeded tasks stay as they are.
 */
const API = 'http://localhost:3456/api/v2'

async function a6WhenSheet(ctx) {
  const { step, shot, warn } = ctx
  const token = resolveToken(opt('token', DEFAULT_TOKEN_PATH))
  if (!token) { console.log('SKIP a6: no API token (see --token)'); return }
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

  // Dates in the device's own zone (it can differ from this machine's).
  const zone = d.sh('getprop persist.sys.timezone').trim() || 'UTC'
  const parts = (instant) => Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
      .formatToParts(instant).filter((p) => p.type !== 'literal').map((p) => [p.type, p.value]),
  )
  const local = (instant) => { const p = parts(instant); return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}` }
  const ymd = (date) => local(date).slice(0, 10)
  const today = new Date(`${ymd(new Date())}T12:00:00Z`) // noon UTC of the device's local date: whole days can be added
  const plusDays = (n) => new Date(today.getTime() + n * 86400000)
  const comingMonday = plusDays(((8 - today.getUTCDay()) % 7) || 7)
  const picked = today.getUTCDate() < 20 ? plusDays(20 - today.getUTCDate()) : null // the 20th of this month, while it is ahead
  const todayDateOnly = `${ymd(today)} 23:59:59`
  const expect = {
    text: `${ymd(plusDays(1))} 09:00:00`,
    day: picked ? `${ymd(picked)} 23:59:59` : null,
    week: `${ymd(comingMonday)} 23:59:59`,
  }
  // Today as a date-only due date: the instant whose device-local time is 23:59:59 of today.
  const dueIso = (() => {
    for (let h = -14; h <= 38; h++) {
      const t = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()) + h * 3600000 + 59 * 60000 + 59000)
      if (local(t) === todayDateOnly) return t.toISOString()
    }
    throw new Error('could not work out a date-only due date for today')
  })()

  const made = {}
  const stamp = Date.now().toString(36).slice(-4)
  const title = (k) => `zz a6 ${k} ${stamp}`
  try {
    for (const k of ['text', 'day', 'week']) {
      const inbox = Number(opt('inbox', 47))
      made[k] = (await api('POST', `/projects/${inbox}/tasks`, { title: title(k), due_date: dueIso })).id
    }
    const waitDue = async (k) => {
      let got = ''
      for (let i = 0; i < 20; i++) {
        got = local(new Date((await api('GET', `/tasks/${made[k]}`)).due_date))
        if (got !== todayDateOnly) return got
        await d.sleep(1000)
      }
      return got
    }
    const check = (label, got, want) => {
      if (got !== want) throw new Error(`${label}: server has ${got}, expected ${want}`)
      console.log(`  ${label}: ${got}`)
    }
    const openSheet = async (k) => {
      await v.nav('Today')
      let row
      for (let i = 0; i < 4 && !row; i++) {
        if (i === 0) await d.swipe(540, 700, 540, 1700, 400, 3000) // pull to refresh
        else await d.swipe(540, 1900, 540, 700, 400, 900)
        row = v.taskRows().find((r) => r.title?.text === title(k))
      }
      if (!row) throw new Error(`throwaway task ${k} is not on Today`)
      await d.swipe(Math.round(v.sc.width * 0.926), row.check.cy, Math.round(v.sc.width * 0.139), row.check.cy, 350, 2000)
      if (!d.hasText('When')) throw new Error('the When sheet did not open on a swipe to the left')
    }

    await step('sheet', async () => { await openSheet('text'); shot('sheet') })
    await step('text-tomorrow-9am', async () => {
      const field = d.findNode((n) => /EditText/.test(n.class ?? ''), 'date and time field')
      await d.tap(field.cx, field.cy, 700)
      await d.clearField()
      await d.type('tomorrow 9am')
      await d.sleep(800)
      shot('text-tomorrow-9am')
      if (!d.dump().some((n) => /9:00 AM|09:00/.test(n.text ?? ''))) warn('a6: the value that was read is not shown')
      await d.back() // the keyboard
      await d.tapText('Done', { wait: 1500 })
      check('tomorrow 9am', await waitDue('text'), expect.text)
    })

    await step('calendar-day', async () => {
      if (!picked) { warn('a6: the 20th is past this month; the calendar-day step is skipped'); return }
      await openSheet('day')
      const label = new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' }).format(picked)
      const cell = (await v.scrollFind((n) => n.text === label, `calendar day ${label}`, 0.5)).n
      await d.tap(cell.cx, cell.cy, 900)
      shot('calendar-day')
      const field = d.dump().find((n) => /EditText/.test(n.class ?? ''))
      if (field && !/(^|\D)20(\D|$)/.test(field.text ?? '')) warn(`a6: the text did not follow the calendar (it reads "${field.text}")`)
      await d.tapText('Done', { wait: 1500 })
      check('calendar day', await waitDue('day'), expect.day)
    })

    await step('next-week', async () => {
      await openSheet('week')
      await d.tapDesc('Next week, ', { exact: false, wait: 1800 })
      check('next week', await waitDue('week'), expect.week)
    })
  } finally {
    for (const id of Object.values(made)) {
      try { await api('DELETE', `/tasks/${id}`) } catch { console.log('  could not delete throwaway task', id) }
    }
    try { await v.home() } catch { /* best effort */ }
  }
}

/**
 * The user's clock reaches every surface that shows a time: with the device on the 24-hour clock
 * the task editor, the When sheet and the quick add sheet show 15:00 and never "3:00 PM". Creates
 * one throwaway task due tomorrow at 15:00, deletes it through the app at the end (no ghost row
 * is left in the cache) and puts the clock setting back.
 */
async function clock24(ctx) {
  const { step, shot } = ctx
  const token = resolveToken(opt('token', DEFAULT_TOKEN_PATH))
  if (!token) { console.log('SKIP clock24: no API token (see --token)'); return }
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
  const zone = d.sh('getprop persist.sys.timezone').trim() || 'UTC'
  const localStamp = (instant) => new Intl.DateTimeFormat('en-CA', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(instant)
  const tomorrowKey = localStamp(new Date(Date.now() + 86400000)).slice(0, 10)
  let due = null
  for (let m = 0; m < 60 * 60 && !due; m += 15) {
    const t = new Date(Math.floor(Date.now() / 900000) * 900000 + m * 60000)
    if (localStamp(t) === `${tomorrowKey}, 15:00` || localStamp(t) === `${tomorrowKey} 15:00`) due = t
  }
  if (!due) throw new Error('could not work out tomorrow 15:00 in the device zone')

  const was = d.sh('settings get system time_12_24').trim()
  const stamp = Date.now().toString(36).slice(-4)
  const title = `zz clock ${stamp}`
  let id = null
  try {
    id = (await api('POST', `/projects/${Number(opt('inbox', 47))}/tasks`, { title, due_date: due.toISOString() })).id
    d.sh('settings put system time_12_24 24')
    d.stop()
    await d.sleep(800)
    await v.launch(4000)

    const noMeridiem = (nodes) => !nodes.some((n) => /\b(AM|PM)\b/i.test(`${n.text ?? ''} ${n['content-desc'] ?? ''}`))
    await step('editor', async () => {
      await v.nav('Upcoming')
      let row
      for (let i = 0; i < 4 && !row; i++) {
        if (i === 0) await d.swipe(540, 700, 540, 1700, 400, 3000)
        else await d.swipe(540, 1900, 540, 700, 400, 900)
        row = v.taskRows().find((r) => r.title?.text === title)
      }
      if (!row) throw new Error('throwaway task is not on Upcoming')
      await d.tap(row.title.cx, row.title.cy, 2200)
      shot('editor-24h')
      const nodes = d.dump()
      if (!nodes.some((n) => /15:00/.test(`${n.text ?? ''} ${n['content-desc'] ?? ''}`))) throw new Error('the editor does not show 15:00')
      if (!noMeridiem(nodes)) throw new Error('the editor shows AM or PM on a 24-hour clock')
    })
    await step('when-sheet', async () => {
      await d.tapDesc('Due date', { exact: false, wait: 1800 })
      shot('when-24h')
      const nodes = d.dump()
      if (!nodes.some((n) => n.text === '15:00')) throw new Error('the When sheet has no 15:00 chip')
      if (!noMeridiem(nodes)) throw new Error('the When sheet shows AM or PM on a 24-hour clock')
      await d.back()
    })
    await step('quick-add', async () => {
      await d.back() // the editor
      await v.nav('Today')
      await d.tapDesc('New task', { wait: 1800 })
      await d.clearField()
      await d.typeSlow('Call Ana tomorrow 3pm')
      await d.sleep(1200)
      shot('quick-add-24h')
      const nodes = d.dump()
      if (!nodes.some((n) => /15:00/.test(`${n.text ?? ''} ${n['content-desc'] ?? ''}`))) throw new Error('the quick add sheet does not show 15:00')
      if (!noMeridiem(nodes.filter((n) => !/Call Ana/.test(n.text ?? '')))) throw new Error('the quick add sheet shows AM or PM on a 24-hour clock')
      await d.clearField()
      await d.back(); await d.back()
    })
  } finally {
    if (was && was !== 'null') d.sh(`settings put system time_12_24 ${was}`)
    else d.sh('settings delete system time_12_24')
    try {
      // Deleted through the app so the cache drops it at once; the server copy goes either way.
      await v.nav('Upcoming')
      const row = v.taskRows().find((r) => r.title?.text === title)
      if (row) {
        await d.tap(row.title.cx, row.title.cy, 2000)
        await d.tapText('Delete task', { wait: 1200 })
        await d.tapText('Delete', { wait: 1800 })
      }
    } catch { /* the API delete below still removes it */ }
    if (id) { try { await api('DELETE', `/tasks/${id}`) } catch { /* already gone */ } }
    try { await v.home() } catch { /* best effort */ }
  }
}

/**
 * A5: the compact quick add sheet. "Call Ana Saturday 3pm #Personal" shows the coming Saturday at
 * 3 PM (once) and "Personal" on the chips; the sheet is compact on the keyboard; "+ Notes" grows it;
 * and a priority picked with its chip wins over "!low" in the text (the chip rule of card 3.5).
 * Nothing is saved: the draft is cleared at the end.
 */
async function a5QuickAdd(ctx) {
  const { step, shot, warn } = ctx
  const zone = d.sh('getprop persist.sys.timezone').trim() || 'UTC'
  const dayOf = (instant) => Number(new Intl.DateTimeFormat('en-GB', { timeZone: zone, day: 'numeric' }).format(instant))
  const weekdayOf = (instant) => new Intl.DateTimeFormat('en-US', { timeZone: zone, weekday: 'short' }).format(instant)
  let saturday = null
  for (let i = 1; i <= 7 && !saturday; i++) {
    const t = new Date(Date.now() + i * 86400000)
    if (weekdayOf(t) === 'Sat') saturday = t
  }
  const texts = (nodes) => nodes.filter((n) => n.text).map((n) => n.text)
  const titleField = (nodes) => nodes.find((n) => /EditText/.test(n.class ?? ''))
  // The chips are one row that scrolls sideways ("+ Notes" stays at its end): a chip that is not in
  // the first view is looked for after scrolling the row to the left.
  const chipTexts = async (want) => {
    let t = texts(d.dump())
    if (t.includes(want)) return t
    const notes = d.dump().find((n) => n.text === '+ Notes')
    for (let i = 0; notes && i < 3 && !t.includes(want); i++) {
      await d.swipe(760, notes.cy, 60, notes.cy, 350, 900)
      t = texts(d.dump())
    }
    return t
  }
  const draftCleanup = async () => {
    try {
      const field = titleField(d.dump())
      if (field) { await d.tap(field.cx, field.cy, 500); await d.clearField() }
    } catch { /* best effort */ }
    await d.back(); await d.back()
  }

  await step('open', async () => {
    await v.nav('Today')
    await d.tapDesc('New task', { wait: 1800 })
    await d.clearField() // the sheet keeps an unsent draft
    await d.typeSlow('Call Ana Saturday 3pm #Personal')
    await d.sleep(1200)
    shot('compact')
  })

  await step('chips', async () => {
    const nodes = d.dump()
    const t = texts(nodes)
    const dateChips = t.filter((x) => /^Sat, /.test(x))
    if (dateChips.length !== 1) throw new Error(`the date shows ${dateChips.length} times as a chip (${dateChips.join(' | ')}), expected once`)
    if (!new RegExp(`\\b${dayOf(saturday)}\\b`).test(dateChips[0]) || !/(3:00 PM|15:00)/.test(dateChips[0])) {
      throw new Error(`the date chip reads "${dateChips[0]}", expected the coming Saturday ${dayOf(saturday)} at 3 PM`)
    }
    console.log(`  date chip: ${dateChips[0]}`)
    if (!t.includes('Personal')) throw new Error(`no "Personal" chip (texts: ${t.slice(0, 12).join(' | ')})`)
    if (!t.includes('+ Notes')) throw new Error('no "+ Notes" chip')
    printFindings(`${ctx.theme}-compact`, audit(nodes), ctx.outDir) // reported, never failing
    const scrolled = await chipTexts('Priority')
    for (const name of ['Tags', 'Priority']) if (!scrolled.includes(name)) warn(`a5: no "${name}" chip, even after scrolling the row`)
    await d.swipe(60, nodes.find((n) => n.text === '+ Notes').cy, 700, nodes.find((n) => n.text === '+ Notes').cy, 300, 600) // back
  })

  await step('compact', async () => {
    const nodes = d.dump()
    const field = titleField(nodes)
    const notes = nodes.find((n) => n.text === '+ Notes')
    if (!field || !notes) throw new Error('title field or "+ Notes" not found')
    // The sheet rests on the keyboard: its top is the title field less the drag handle, and it
    // takes well under half of the screen.
    const topDp = (field.y1 - v.px(40)) / v.sc.scale
    const heightDp = v.sc.height / v.sc.scale
    const keyboardTop = d.screen().height - v.px(300)
    console.log(`  title field at ${topDp.toFixed(0)} dp of ${heightDp.toFixed(0)} dp; chips row at ${(notes.y1 / v.sc.scale).toFixed(0)} dp`)
    if (field.y1 < v.sc.height * 0.3) throw new Error('the sheet is tall: the title field starts in the top third of the screen')
    if (notes.y2 > keyboardTop + v.px(500)) warn('a5: the chips sit very low; check the capture')
  })

  await step('chip-rule', async () => {
    // The text says !low; picking Urgent with the chip wins, and the chip then reads Urgent.
    const field = titleField(d.dump())
    await d.tap(field.cx, field.cy, 500)
    // Ctrl+A does not always reach a field that is showing its autocomplete: clear until it is empty.
    for (let i = 0; i < 4; i++) {
      await d.clearField()
      const now = titleField(d.dump())
      if (!now?.text || now.text === 'New task') break
    }
    await d.typeSlow('Call Ana !low')
    await d.sleep(900)
    const lowTexts = await chipTexts('Low')
    if (!lowTexts.includes('Low')) throw new Error(`the priority chip does not read Low for "!low" (texts: ${lowTexts.join(' | ')})`)
    await d.tapText('Low', { wait: 1200 })
    await d.tapText('Urgent', { wait: 1200 })
    const t = await chipTexts('Urgent')
    shot('chip-rule')
    if (!t.includes('Urgent')) throw new Error('the priority chip does not read Urgent after picking it')
    if (t.includes('Low')) throw new Error('the typed "!low" still shows as a Low chip after picking Urgent')
  })

  await step('notes', async () => {
    await d.tapText('+ Notes', { wait: 1500 })
    shot('notes')
    const t = texts(d.dump())
    if (t.includes('+ Notes')) throw new Error('"+ Notes" is still there after tapping it')
    for (const name of ['Repeat', 'Reminder']) if (!t.includes(name)) throw new Error(`no "${name}" chip after "+ Notes"`)
  })

  await draftCleanup()
}

/**
 * The task editor (card 3.7): a back arrow and Done, the headline, the notes as plain text, one row
 * of property chips in the desktop order, and the footer "Created <date>. Saved as you type."
 * Opens a seeded task that has several properties set, changes nothing.
 */
async function editor37(ctx) {
  const { step, shot, warn } = ctx
  const want = ['Due date', 'Priority', 'Labels', 'Subtasks', 'Reminder', 'Recurrence', 'Project', 'Add attachment', 'More']
  await step('open', async () => {
    await v.nav('Today')
    // The seeded task with notes, a date, a priority, a label and subtasks; else the first row.
    let target = null
    try { target = (await v.scrollFind((x) => x.text === 'Draft Q4 roadmap', 'Draft Q4 roadmap', 0.5)).n } catch { /* fall back */ }
    if (!target) target = v.taskRows().find((r) => r.title)?.title
    if (!target) throw new Error('no task row to open')
    console.log('  task:', target.text)
    await d.tap(target.cx, target.cy, 2200)
    shot('editor-top')
  })
  await step('bar', async () => {
    const nodes = d.dump()
    for (const desc of ['Back']) if (!nodes.some((n) => n['content-desc'] === desc)) throw new Error(`no "${desc}" button`)
    if (!nodes.some((n) => n.text === 'Done')) throw new Error('no Done button')
    if (nodes.some((n) => n['content-desc'] === 'Close')) throw new Error('the X is still there')
  })
  await step('chips-in-desktop-order', async () => {
    const nodes = d.dump()
    const found = []
    for (const w of want) {
      // "Reminder"/"Recurrence" cover both the set form and the "+" form of the chip
      const n = nodes.find((x) => {
        const c = x['content-desc'] ?? ''
        if (w === 'Reminder') return /^(Add reminder|Reminders:)/.test(c)
        if (w === 'Recurrence') return /^(Set recurrence|Recurrence:)/.test(c)
        if (w === 'Subtasks') return /^(Add subtask|Subtasks:)/.test(c)
        if (w === 'Labels') return /^(Add label|Labels:)/.test(c)
        if (w === 'Due date') return /^(Add due date|Due date:)/.test(c)
        if (w === 'Priority') return /^(Set priority|Priority:)/.test(c)
        return c.startsWith(w)
      })
      if (!n) throw new Error(`no ${w} chip`)
      found.push({ w, y: n.cy, x: n.x1 })
    }
    // Reading order: rows top to bottom, then left to right (within a row, y differs by a few px).
    const row = (y) => Math.round(y / v.px(24))
    const order = [...found].sort((a, b) => row(a.y) - row(b.y) || a.x - b.x).map((f) => f.w)
    if (order.join() !== want.join()) throw new Error(`chip order is ${order.join(', ')}; expected ${want.join(', ')}`)
    const small = nodes.filter((n) => n.clickable && /chip|Add|Set|Project:|Due date|Priority|Labels|Subtasks|Reminders|Recurrence|More/.test(n['content-desc'] ?? '') && n.h < v.px(47))
    for (const s of small) warn(`editor: ${s['content-desc']} is ${(s.h / v.sc.scale).toFixed(0)} dp tall`)
  })
  await step('footer', async () => {
    const { n } = await v.scrollFind((x) => /Saved as you type\.$/.test(x.text ?? ''), 'footer', 0.5)
    shot('editor-footer')
    if (!/^(Created [^.]*\. )?Saved as you type\.$/.test(n.text)) throw new Error(`footer reads "${n.text}"`)
    console.log(`  footer: ${n.text}`)
  })
  await step('more-menu', async () => {
    await d.swipe(540, 700, 540, 1700, 300, 700) // back to the top
    await d.swipe(540, 700, 540, 1700, 300, 700)
    await d.tapDesc('More', { wait: 900 })
    shot('editor-more')
    const t = d.dump().map((n) => n.text)
    for (const item of ['Add relation', 'Delete task']) if (!t.includes(item)) throw new Error(`More has no "${item}"`)
    await d.back() // the menu
  })
  await d.back() // the editor
}

/**
 * A1: ticking a checkbox in Today. Spring check and the 5 s hold (the row stays, struck through),
 * then the row leaves and the snackbar says "Completed" with Undo for 6 s; two ticks close together
 * merge into "2 completed"; Undo brings the rows back open (read back from the server). Creates
 * throwaway tasks due now in the Inbox and deletes them at the end. The mid-animation frame is taken
 * at animator scale 5 and the scale goes back to 1.
 */
async function a1Completing(ctx) {
  const { step, shot } = ctx
  const token = resolveToken(opt('token', DEFAULT_TOKEN_PATH))
  if (!token) { console.log('SKIP a1: no API token (see --token)'); return }
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
  const stamp = Date.now().toString(36).slice(-4)
  const title = (k) => `droid a1 ${k} ${stamp}`
  const made = {}
  const rowOf = (k) => v.taskRows().find((r) => r.title?.text === title(k))
  const snack = (nodes = d.dump()) => nodes.find((n) => /^(Completed|\d+ completed)$/.test(n.text ?? ''))
  const serverDone = async (k) => (await api('GET', `/tasks/${made[k]}`)).done
  const waitFor = async (pred, what, ms = 9000) => {
    const t0 = Date.now()
    while (Date.now() - t0 < ms) { const r = pred(); if (r) return r; await d.sleep(250) }
    throw new Error(`timed out: ${what}`)
  }
  const refreshToday = async (k) => {
    await v.nav('Today')
    for (let i = 0; i < 4 && !rowOf(k); i++) {
      if (i === 0) await d.swipe(540, 700, 540, 1700, 400, 3000) // pull to refresh
      else await d.swipe(540, 1900, 540, 700, 400, 900)
    }
    if (!rowOf(k)) throw new Error(`throwaway task ${k} is not on Today`)
  }
  try {
    for (const k of ['one', 'two', 'three']) {
      const inbox = Number(opt('inbox', 47))
      made[k] = (await api('POST', `/projects/${inbox}/tasks`, { title: title(k), due_date: new Date().toISOString() })).id
    }
    await refreshToday('one')

    // A dump of the screen takes about 2 s, so the timeline is counted from the tap and each dump is
    // started early enough to be taken inside the window it is checking.
    let tapAt = 0
    const until = async (ms) => { const wait = tapAt + ms - Date.now(); if (wait > 0) await d.sleep(wait) }

    await step('tick-spring', async () => {
      const cb = rowOf('one').check
      d.animScale(5) // hold the spring and the drawn check long enough to capture
      tapAt = Date.now()
      await d.tap(cb.cx, cb.cy, 300)
      shot('tick-mid')
      d.animScale(1)
      await until(1200)
      const now = rowOf('one') // a dump, taken while the row is still held
      if (!now) throw new Error('the row left before the hold ended')
      if (!now.check.checked) throw new Error('the checkbox is not checked after the tap')
      shot('held')
    })

    await step('leaves-and-snackbar', async () => {
      await until(5700) // the hold is 5 s; the snackbar then stays 6 s
      shot('snackbar')
      const nodes = d.dump()
      if (nodes.some((n) => n.text === title('one'))) throw new Error('the row is still on screen after the hold')
      const s = snack(nodes)
      if (!s) throw new Error('no "Completed" snackbar after the hold')
      if (s.text !== 'Completed') throw new Error(`the snackbar says "${s.text}"`)
      const undo = nodes.find((n) => n.text === 'Undo' || n['content-desc'] === 'Undo')
      if (!undo) throw new Error('the snackbar has no Undo')
      await d.tap(undo.cx, undo.cy, 1500)
      const back = await waitFor(() => rowOf('one'), 'the row to come back after Undo', 8000)
      if (back.check.checked) throw new Error('the row came back still checked')
      for (let i = 0; i < 10 && (await serverDone('one')); i++) await d.sleep(1000)
      if (await serverDone('one')) throw new Error('the server still has the task done after Undo')
      shot('undone')
    })

    await step('merged-count', async () => {
      await refreshToday('two')
      const first = rowOf('two').check
      tapAt = Date.now()
      await d.tap(first.cx, first.cy, 1000)
      const second = rowOf('three').check
      await d.tap(second.cx, second.cy, 100)
      await until(9000) // the second row leaves 5 s after its tap (about 3.5 s in); the toast then reads "2 completed"
      const nodes = d.dump()
      shot('merged')
      const s = snack(nodes)
      if (s?.text !== '2 completed') throw new Error(`the snackbar says "${s?.text}"`)
      const undo = nodes.find((n) => n.text === 'Undo' || n['content-desc'] === 'Undo')
      if (!undo) throw new Error('the snackbar has no Undo')
      await d.tap(undo.cx, undo.cy, 1500)
      await waitFor(() => rowOf('two') && rowOf('three'), 'both rows to come back after Undo', 8000)
      for (const k of ['two', 'three']) for (let i = 0; i < 10 && (await serverDone(k)); i++) await d.sleep(1000)
      if ((await serverDone('two')) || (await serverDone('three'))) throw new Error('the server still has a task done after Undo')
      console.log('  Undo reopened both tasks')
    })
  } finally {
    d.animScale(1)
    for (const id of Object.values(made)) {
      try { await api('DELETE', `/tasks/${id}`) } catch (e) { console.log('  could not delete throwaway task', id, e.message) }
    }
    // The app still holds the deleted rows until it syncs: pull to refresh so no ghost row stays behind.
    try { await v.nav('Today'); await d.swipe(540, 700, 540, 1700, 400, 3000); await v.home() } catch { /* best effort */ }
  }
}

/**
 * A2: the swipe on a task row. Right to 40 percent (tinted row, icon and "Complete" in the strip),
 * right to 60 percent (the strip goes full colour and the icon pops; the task completes), left to
 * 40 percent, left past 50 percent (the When sheet opens, because the swipe setting is "Choose
 * when"; with the setting on "Urgent" the swipe marks the task urgent instead). Captured while the
 * finger is down, through motionevent. The left swipe starts at x=1000: x=1004 and further right is
 * the system back-gesture zone. Uses two throwaway tasks due now in the Inbox, deleted at the end.
 * The armed pop is captured at animator scale 5, which goes back to 1.
 */
async function a2Swipe(ctx) {
  const { step, shot } = ctx
  const token = resolveToken(opt('token', DEFAULT_TOKEN_PATH))
  if (!token) { console.log('SKIP a2: no API token (see --token)'); return }
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
  const stamp = Date.now().toString(36).slice(-4)
  const title = (k) => `droid a2 ${k} ${stamp}`
  const made = {}
  const rowOf = (k) => v.taskRows().find((r) => r.title?.text === title(k))
  const serverDone = async (k) => (await api('GET', `/tasks/${made[k]}`)).done
  const W = v.sc.width
  try {
    for (const k of ['right', 'left']) {
      const inbox = Number(opt('inbox', 47))
      made[k] = (await api('POST', `/projects/${inbox}/tasks`, { title: title(k), due_date: new Date().toISOString() })).id
    }
    if (!d.appInFront()) await v.launch(4000)
    await v.nav('Today')
    for (let i = 0; i < 4 && !(rowOf('right') && rowOf('left')); i++) {
      if (i === 0) await d.swipe(540, 700, 540, 1700, 400, 3000) // pull to refresh
      else await d.swipe(540, 1900, 540, 700, 400, 900)
    }
    if (!rowOf('right') || !rowOf('left')) throw new Error('the throwaway tasks are not on Today')

    await step('right-40', async () => {
      const y = rowOf('right').check.cy
      const g = await d.holdGesture(300, y, 300 + Math.round(W * 0.4), y, 1, { steps: 8 })
      shot('right-40')
      await g.cancel(1200)
      if (await serverDone('right')) throw new Error('a swipe to 40 percent completed the task')
    })

    await step('right-60-armed', async () => {
      const y = rowOf('right').check.cy
      d.animScale(5) // hold the icon pop long enough to capture
      const g = await d.holdGesture(300, y, 300 + Math.round(W * 0.6), y, 1, { steps: 8 })
      shot('right-60-armed')
      await g.release(1500)
      d.animScale(1)
      await d.sleep(1500)
      shot('right-60-released')
      for (let i = 0; i < 10 && !(await serverDone('right')); i++) await d.sleep(1000)
      if (!(await serverDone('right'))) throw new Error('a swipe past 50 percent did not complete the task')
    })

    await step('left-40', async () => {
      const y = rowOf('left').check.cy
      const g = await d.holdGesture(1000, y, 1000 - Math.round(W * 0.4), y, 1, { steps: 8 })
      shot('left-40')
      await g.cancel(1200)
      if (d.hasText('When')) throw new Error('the When sheet opened at 40 percent')
    })

    await step('left-60-when-sheet', async () => {
      const y = rowOf('left').check.cy
      const g = await d.holdGesture(1000, y, 1000 - Math.round(W * 0.6), y, 1, { steps: 8 })
      shot('left-60-armed')
      await g.release(1800)
      if (!d.hasText('When')) throw new Error('the When sheet did not open on a swipe past 50 percent to the left')
      shot('left-60-when')
      await d.back()
    })
  } finally {
    d.animScale(1)
    for (const id of Object.values(made)) {
      try { await api('DELETE', `/tasks/${id}`) } catch (e) { console.log('  could not delete throwaway task', id, e.message) }
    }
    try { await v.nav('Today'); await d.swipe(540, 700, 540, 1700, 400, 3000); await v.home() } catch { /* best effort */ }
  }
}

/**
 * A7: scrolling Today. At the top the title is large with the date under it and the FAB reads
 * "New task"; after scrolling the title has folded into the bar (the date is gone) and the FAB is
 * only its icon (still named "New task"); back at the top both are back.
 */
async function a7LargeTitle(ctx) {
  const { step, shot } = ctx
  const label = (n) => (n.text ?? n['content-desc'] ?? '')
  const fab = (nodes) => nodes.find((n) => n['content-desc'] === 'New task')
  const fabWidthDp = (nodes) => { const f = fab(nodes); return f ? v.dp(f.x2 - f.x1) : 0 }
  const subtitle = (nodes) => nodes.some((n) => /^(Mon|Tues|Wednes|Thurs|Fri|Satur|Sun)day, [A-Z][a-z]+ \d+/.test(label(n)))
  await step('top', async () => {
    await v.nav('Today')
    await d.swipe(540, 1100, 540, 1500, 300, 1200) // up, short of a pull to refresh
    shot('top')
    const nodes = d.dump()
    if (fabWidthDp(nodes) < 100) throw new Error(`the FAB is not extended at the top (${fabWidthDp(nodes)} dp wide)`)
    if (!subtitle(nodes)) throw new Error('the date is not under the large title at the top')
    const title = nodes.find((n) => n.text === 'Today' && n.y1 < v.px(260))
    if (!title) throw new Error('no title "Today" in the bar')
    console.log(`  large title at ${v.dp(title.y1).toFixed(0)} to ${v.dp(title.y2).toFixed(0)} dp`)
  })
  await step('scrolled', async () => {
    for (let i = 0; i < 3; i++) await d.swipe(540, 1700, 540, 500, 350, 700)
    await d.sleep(800)
    shot('scrolled')
    const nodes = d.dump()
    const w = fabWidthDp(nodes)
    if (!fab(nodes)) throw new Error('the FAB has lost its name after scrolling')
    if (w > 80) throw new Error(`the FAB is still extended after scrolling (${w} dp wide)`)
    console.log(`  FAB ${w.toFixed(0)} dp wide`)
    if (subtitle(nodes)) throw new Error('the date is still in the bar after the title folded')
    const title = nodes.find((n) => n.text === 'Today' && n.y1 < v.px(260))
    if (!title) throw new Error('no title "Today" in the folded bar')
    console.log(`  folded title at ${v.dp(title.y1).toFixed(0)} to ${v.dp(title.y2).toFixed(0)} dp`)
  })
  await step('back-to-top', async () => {
    for (let i = 0; i < 4; i++) await d.swipe(540, 900, 540, 1700, 300, 600)
    await d.sleep(3500) // a pull to refresh may have started
    await d.sleep(1000)
    shot('back-to-top')
    if (fabWidthDp(d.dump()) < 100) throw new Error('the FAB did not extend again at the top')
  })
}

/**
 * Compares two captures below the status bar: the share of pixels that differ clearly (the sum of
 * the three channel differences over 48). Used to tell whether a mid-animation frame shows a state
 * that is neither the start nor the end of the animation.
 */
function pngDiff(fileA, fileB, fromY = 160) {
  const a = PNG.sync.read(readFileSync(fileA))
  const b = PNG.sync.read(readFileSync(fileB))
  if (a.width !== b.width || a.height !== b.height) return 1
  let n = 0
  const rows = a.height - fromY
  for (let y = fromY; y < a.height; y++) {
    for (let x = 0; x < a.width; x++) {
      const i = (y * a.width + x) * 4
      if (Math.abs(a.data[i] - b.data[i]) + Math.abs(a.data[i + 1] - b.data[i + 1]) + Math.abs(a.data[i + 2] - b.data[i + 2]) > 48) n++
    }
  }
  return n / (rows * a.width)
}

/**
 * Judges one animation from its captures: `before` (rest, start), `mids` (frames taken while it
 * runs, at animator scale 5 or with a held gesture) and `after` (rest, end). It is visible
 * mid-way when some frame differs from both ends; frames identical to the start mean it had not
 * moved, frames identical to the end mean it was already over. `minShare` is the share of the
 * screen a frame must differ by; a small element (a digit, a chip) needs a smaller one than a screen.
 */
function judgeMotion(ctx, label, before, mids, after, minShare = 0.01) {
  const pct = (x) => `${(x * 100).toFixed(1)}%`
  const rows = mids.map((m) => ({ toBefore: pngDiff(m, before), toAfter: pngDiff(m, after) }))
  rows.forEach((r, i) => console.log(`  ${label} frame ${i + 1}: differs from start ${pct(r.toBefore)}, from end ${pct(r.toAfter)}`))
  console.log(`  ${label}: start and end differ by ${pct(pngDiff(before, after))}`)
  const visible = rows.some((r) => r.toBefore > minShare && r.toAfter > minShare)
  console.log(`${visible ? 'VISIBLE' : 'NOT VISIBLE'} ${label}: ${visible ? 'a mid frame shows a state between start and end' : 'every mid frame equals the start or the end'}`)
  if (!visible) ctx.warn(`${label} is not visible mid-way`)
  return visible
}

/**
 * A3: the editor motion (4.5a) and the screen transitions (4.7). For each animation: the start, the
 * end, and frames in between. The editor opens at animator scale 10 (fade and rise slowed tenfold);
 * the predictive back gesture is held part-way on the left edge, then cancelled and released; a tab
 * switch (fade-through) runs at scale 10; a project opened from the drawer (fade-through) and a move from
 * one project to another (shared axis slide) run at scale 5. The scale is back at 1 at the end.
 * Each capture takes a few hundred milliseconds, so the first frame of an animation is rarely its first moment.
 * The judgement is by picture difference (see judgeMotion); look at the captures too.
 */
async function a3EditorAndTransitions(ctx) {
  const { step, shot } = ctx
  const frames = (name, n = 3) => { const out = []; for (let i = 1; i <= n; i++) out.push(shot(`${name}-mid${i}`)); return out }
  const midY = Math.round(v.sc.height * 0.5)
  const edgeHold = () => d.holdGesture(2, midY, Math.round(v.sc.width * 0.55), midY, 1, { steps: 10, stepMs: 50 })
  let listBefore = null
  try {
    await step('editor-open', async () => {
      await v.listWithRows()
      await d.sleep(600)
      listBefore = shot('editor-open-start')
      const row = v.firstRow()
      // Scale 10, not 5: a capture takes up to a second, and the editor's fade is over in about a second at scale 5.
      d.animScale(10)
      await d.tap(row.title.cx, row.title.cy, 0)
      const mids = frames('editor-open', 6)
      await d.sleep(5000)
      const end = shot('editor-open-end')
      d.animScale(1)
      judgeMotion(ctx, 'editor open (fade and rise)', listBefore, mids, end)
    })
    await step('editor-rest', async () => { await d.sleep(800); shot('editor-rest') })
    await step('editor-back-held', async () => {
      const rest = shot('editor-back-start')
      const g = await edgeHold()
      const held = shot('editor-back-held')
      await g.cancel(1800)
      const cancelled = shot('editor-back-cancelled')
      const k = pngDiff(rest, cancelled)
      console.log(`  after the cancelled gesture the editor differs from rest by ${(k * 100).toFixed(1)}%`)
      if (k > 0.02) throw new Error('the editor did not return to rest after a cancelled back gesture')
      judgeMotion(ctx, 'editor follows the back gesture (held)', rest, [held], listBefore)
    })
    await step('editor-back-release', async () => {
      const g = await edgeHold()
      await g.release(2200)
      shot('editor-back-released')
      if (!v.bottomBarVisible(d.dump())) throw new Error('the back gesture did not return to the list')
    })
    await step('tab-switch', async () => {
      await v.nav('Today', 2000)
      const before = shot('tab-start')
      const tab = d.dump().find((x) => x.text === 'Upcoming' && x.cy > v.sc.height * 0.9)
      if (!tab) throw new Error('no Upcoming tab in the bottom bar')
      d.animScale(10) // a capture can take most of a second: at scale 5 the whole fade can fall between two frames
      await d.tap(tab.cx, tab.cy, 0)
      const mids = frames('tab', 4)
      await d.sleep(4000)
      const end = shot('tab-end')
      d.animScale(1)
      judgeMotion(ctx, 'tab switch (fade-through)', before, mids, end)
    })
    await step('project-open', async () => {
      await v.nav('Today', 2000)
      let before = null
      await v.drawerProject(0, async () => { before = shot('project-start'); d.animScale(5) })
      const mids = frames('project', 4)
      await d.sleep(3000)
      const end = shot('project-end')
      d.animScale(1)
      judgeMotion(ctx, 'drawer to project (fade-through)', before, mids, end)
    })
    await step('project-to-project', async () => {
      // Moving from one project to another is the shared axis slide (Today to a project is a fade-through).
      let before = null
      await v.drawerProject(0, async () => { before = shot('p2p-start'); d.animScale(5) }, 1)
      const mids = frames('p2p', 4)
      await d.sleep(3000)
      const end = shot('p2p-end')
      d.animScale(1)
      judgeMotion(ctx, 'project to project (shared axis slide)', before, mids, end)
    })
    await step('project-back-held', async () => {
      const rest = shot('project-back-start')
      const g = await edgeHold()
      const held = shot('project-back-held')
      await g.cancel(1800)
      const cancelled = shot('project-back-cancelled')
      console.log(`  after the cancelled gesture the project differs from rest by ${(pngDiff(rest, cancelled) * 100).toFixed(1)}%`)
      const moved = pngDiff(rest, held)
      console.log(`${moved >= 0.01 ? 'VISIBLE' : 'NOT VISIBLE'} project follows the back gesture (held): ${(moved * 100).toFixed(1)}% of the pixels differ`)
      if (moved < 0.01) ctx.warn('a3: the project screen does not follow a held back gesture')
    })
  } finally {
    d.animScale(1)
    try { await v.nav('Today', 1500) } catch { /* best effort */ }
  }
}

/**
 * 4.11b signature moments: a count that rolls, a token that travels into its chip, and Today's All
 * clear. Each runs at animator scale 10 so a mid-way frame can be captured; the scale is back at 1
 * (and the task reopened) at the end. All clear is only captured when Today happens to be empty:
 * the scenario never changes the data to make it so.
 */
async function momentsSignature(ctx) {
  const { step, shot, warn } = ctx
  const frames = (name, n = 4) => { const out = []; for (let i = 1; i <= n; i++) out.push(shot(`${name}-mid${i}`)); return out }
  const headerCounts = (nodes) => nodes
    .map((n) => /^(Overdue|Today|Tomorrow|.+), (\d+) tasks?$/.exec(n['content-desc'] ?? ''))
    .filter(Boolean)
    .map((m) => `${m[1]} ${m[2]}`)
  try {
    await step('count-rolls', async () => {
      await v.nav('Today', 2000)
      const row = v.firstRow(undefined, { plain: true })
      const before = shot('roll-start')
      console.log('  section counts before:', headerCounts(d.dump()).join(', '))
      d.animScale(10)
      await d.tap(row.check.cx, row.check.cy, 0)
      const mids = frames('roll')
      await d.sleep(3000)
      const end = shot('roll-end')
      d.animScale(1)
      console.log('  section counts after:', headerCounts(d.dump()).join(', '))
      judgeMotion(ctx, 'count roll (section header)', before, mids, end, 0.0005)
      // Leave the data as it was: reopen the task while its row is still held on screen.
      const cb = d.dump().find((n) => n.checkable && n['content-desc'] === row.desc)
      if (cb?.checked) await d.tap(cb.cx, cb.cy, 1500)
      const again = d.dump().find((n) => n.checkable && n['content-desc'] === row.desc)
      if (again?.checked) throw new Error(`could not reopen "${row.desc}"`)
    })
    await step('token-travels', async () => {
      await v.nav('Today', 1500)
      await d.tapDesc('New task', { wait: 1800 })
      await d.clearField()
      await d.typeSlow('Call Ana ')
      await d.sleep(600)
      const before = shot('token-start')
      d.animScale(10)
      await d.typeSlow('tomorrow', { pause: 60 })
      const mids = frames('token')
      await d.sleep(4000)
      const end = shot('token-end')
      d.animScale(1)
      judgeMotion(ctx, 'token travels into its chip', before, mids, end, 0.002)
      await d.clearField()
      await d.back(); await d.back()
    })
    await step('all-clear', async () => {
      await v.nav('Today', 2000)
      if (d.hasText('All clear')) {
        shot('all-clear')
        console.log('  Today is empty:', d.list((n) => /All clear|Nothing is due|Next up/.test(`${n.text ?? ''}`)).join(' | '))
      } else {
        console.log('SKIP all-clear: Today has tasks (the scenario does not empty it)')
      }
    })
  } finally {
    d.animScale(1)
    try { await v.home() } catch { /* best effort */ }
  }
}

const stub = (id, wave) => Object.assign(async () => { stubs.push(id); console.log(`STUB ${id}: not implemented yet (plan wave ${wave})`) }, { isStub: true })

// id, wave (from the plan's Android scenario table), run
const SCENARIOS = {
  baseline: { wave: 0, run: baseline, about: 'Inbox, Today, Upcoming, Anytime, drawer, project, tag, Logbook, Review, Settings, editor, quick add, date picker, swipes, search' },
  a1: { wave: 4, run: a1Completing, about: 'tick a checkbox in Today: spring, hold, snackbar, Undo' },
  a2: { wave: 4, run: a2Swipe, about: 'swipe to 40 and 60 percent, armed pop, WhenSheet' },
  a3: { wave: 4, run: a3EditorAndTransitions, about: 'editor open and predictive back, tab switch, project open: mid-way frames' },
  rings: { wave: 3, run: rings, about: 'drawer project progress rings and the number of done-count requests' },
  a4: { wave: 1, run: a4Upcoming, about: 'Upcoming day groups and sticky headers' },
  a5: { wave: 3, run: a5QuickAdd, about: 'compact quick add: chips in words, the date once, + Notes grows it, a picked chip wins over the text' },
  a6: { wave: 3, run: a6WhenSheet, about: 'WhenSheet: tomorrow 9am, a calendar day, Next week (throwaway tasks, read back from the server)' },
  clock24: { wave: 3, run: clock24, about: 'device on the 24-hour clock: editor, When sheet and quick add show 15:00, never PM' },
  editor: { wave: 3, run: editor37, about: 'the task editor: back and Done, headline, one row of property chips in the desktop order, footer' },
  a7: { wave: 4, run: a7LargeTitle, about: 'scroll Today: large title folds, FAB shrinks' },
  moments: { wave: 4, run: momentsSignature, about: '4.11b: a count rolls, a token travels into its chip, Today All clear (when empty)' },
  a8: { wave: 1, run: a8Colours, about: 'light, dark, device colours off and on' },
  a9: { wave: 2, run: a9A11y, about: 'a11y report on Today, Upcoming, editor, quick add, drawer' },
  a10: { wave: 1, run: a10FirstRun, about: 'pm clear, first launch, set up (signs the app out and in again)' },
  a11: { wave: 2, run: a11FontScale, about: 'font scale 1.3 on Review, Today, editor' },
  a12: { wave: 6, run: stub('a12', 6), about: 'Today in both apps side by side' },
}
const ALIASES = { 'a4-upcoming': 'a4', 'a8-colours': 'a8', 'a10-first-run': 'a10', 'a9-a11y': 'a9', 'a11-font-scale': 'a11' }

function pick() {
  if (has('wave')) {
    const wave = Number(opt('wave'))
    const ids = Object.entries(SCENARIOS).filter(([, s]) => s.wave === wave).map(([id]) => id)
    if (!ids.length) throw new Error(`no scenarios for wave ${wave}`)
    return ids
  }
  const names = opt('scenario', 'baseline').split(',').map((s) => ALIASES[s] ?? s)
  for (const n of names) if (!SCENARIOS[n]) throw new Error(`unknown scenario ${n} (try --list)`)
  return names
}

if (has('list')) {
  for (const [id, s] of Object.entries(SCENARIOS)) console.log(`${id.padEnd(9)} wave ${s.wave}  ${s.run.isStub ? 'stub ' : '     '} ${s.about}`)
  process.exit(0)
}

const themes = opt('theme', 'light') === 'both' ? ['light', 'dark'] : [opt('theme', 'light')]
for (const t of themes) if (!['light', 'dark'].includes(t)) { console.error('--theme must be light, dark or both'); process.exit(2) }

const ids = pick()
try {
  d.animScale(1) // captures run at full motion whatever the device was left at
  for (const theme of themes) {
    d.night(theme === 'dark')
    await d.sleep(1500)
    for (const id of ids) {
      console.log(`== ${id} (${theme})`)
      await SCENARIOS[id].run(makeCtx(id, theme))
    }
  }
} finally {
  d.night(false)
  d.fontScale(1.0)
}

console.log(failed ? `\n${failed} step(s) failed` : '\nclean', stubs.length ? `(${stubs.length} stub scenario(s): ${stubs.join(', ')})` : '')
process.exit(failed ? 1 : 0)
