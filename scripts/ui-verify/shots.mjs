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
// Scenario ids follow the plan: a1..a12. Only baseline, a4, a6, a8, a9, a10 and a11 run today; the
// others are stubs that print STUB and pass.
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
    await d.tapDesc('Add task', { wait: 1800 })
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
      await d.tapDesc('Add task', { wait: 1800 })
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
  const draftCleanup = async () => {
    try {
      const field = titleField(d.dump())
      if (field) { await d.tap(field.cx, field.cy, 500); await d.clearField() }
    } catch { /* best effort */ }
    await d.back(); await d.back()
  }

  await step('open', async () => {
    await v.nav('Today')
    await d.tapDesc('Add task', { wait: 1800 })
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
    for (const name of ['When', 'Project', 'Tags', 'Priority']) {
      if (name === 'When' || name === 'Project') continue // these hold values here
      if (!t.includes(name)) warn(`a5: no "${name}" chip`)
    }
    if (!t.includes('+ Notes')) throw new Error('no "+ Notes" chip')
    printFindings(`${ctx.theme}-compact`, audit(nodes), ctx.outDir) // reported, never failing
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
    await d.clearField()
    await d.typeSlow('Call Ana !low')
    await d.sleep(900)
    if (!texts(d.dump()).includes('Low')) throw new Error('the priority chip does not read Low for "!low"')
    await d.tapText('Low', { wait: 1200 })
    await d.tapText('Urgent', { wait: 1200 })
    const t = texts(d.dump())
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

const stub = (id, wave) => Object.assign(async () => { stubs.push(id); console.log(`STUB ${id}: not implemented yet (plan wave ${wave})`) }, { isStub: true })

// id, wave (from the plan's Android scenario table), run
const SCENARIOS = {
  baseline: { wave: 0, run: baseline, about: 'Inbox, Today, Upcoming, Anytime, drawer, project, tag, Logbook, Review, Settings, editor, quick add, date picker, swipes, search' },
  a1: { wave: 4, run: stub('a1', 4), about: 'tick a checkbox in Today: spring, hold, snackbar, Undo' },
  a2: { wave: 4, run: stub('a2', 4), about: 'swipe to 40 and 60 percent, armed pop, WhenSheet' },
  a3: { wave: 4, run: stub('a3', 4), about: 'predictive back on the editor, container transform' },
  rings: { wave: 3, run: rings, about: 'drawer project progress rings and the number of done-count requests' },
  a4: { wave: 1, run: a4Upcoming, about: 'Upcoming day groups and sticky headers' },
  a5: { wave: 3, run: a5QuickAdd, about: 'compact quick add: chips in words, the date once, + Notes grows it, a picked chip wins over the text' },
  a6: { wave: 3, run: a6WhenSheet, about: 'WhenSheet: tomorrow 9am, a calendar day, Next week (throwaway tasks, read back from the server)' },
  clock24: { wave: 3, run: clock24, about: 'device on the 24-hour clock: editor, When sheet and quick add show 15:00, never PM' },
  editor: { wave: 3, run: editor37, about: 'the task editor: back and Done, headline, one row of property chips in the desktop order, footer' },
  a7: { wave: 4, run: stub('a7', 4), about: 'scroll Today: large title folds, FAB shrinks' },
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
