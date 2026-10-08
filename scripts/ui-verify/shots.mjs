// Screen captures of the debug app on emulator-5554.
//
//   node shots.mjs --scenario baseline --theme light     (or dark, or both)
//   node shots.mjs --wave 1 --theme dark                 (every scenario the plan lists for wave 1)
//   node shots.mjs --list
//   options: --token <path>   token file or .local folder (the a10 scenario signs in again)
//            --query <text>   search text for the search step (default "the")
//
// Captures land in out/<scenario>/<theme>-<step>.png. A step that cannot run prints FAIL and the
// run exits 1; WARN lines are expectations the app does not meet yet and never fail the run.
// Scenario ids follow the plan: a1..a12. Only baseline, a4, a8, a9, a10 and a11 run today; the
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
  const outDir = d.setOutDir(scenario)
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
 * 50 percent schedules the task on the crossing too and cannot be undone from here, so only the
 * partial (40 percent) left swipe is captured.
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
  })
}

/** A9: accessibility report on the main screens (also available as a11y.mjs). */
async function a9A11y(ctx) {
  await ctx.step('a11y-report', async () => {
    for (const name of ['today', 'upcoming', 'editor', 'quick-add', 'drawer']) {
      await v.SCREENS[name]()
      printFindings(name, audit(d.dump()))
    }
  })
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

const stub = (id, wave) => Object.assign(async () => { stubs.push(id); console.log(`STUB ${id}: not implemented yet (plan wave ${wave})`) }, { isStub: true })

// id, wave (from the plan's Android scenario table), run
const SCENARIOS = {
  baseline: { wave: 0, run: baseline, about: 'Inbox, Today, Upcoming, Anytime, drawer, project, tag, Logbook, Review, Settings, editor, quick add, date picker, swipes, search' },
  a1: { wave: 4, run: stub('a1', 4), about: 'tick a checkbox in Today: spring, hold, snackbar, Undo' },
  a2: { wave: 4, run: stub('a2', 4), about: 'swipe to 40 and 60 percent, armed pop, WhenSheet' },
  a3: { wave: 4, run: stub('a3', 4), about: 'predictive back on the editor, container transform' },
  a4: { wave: 1, run: a4Upcoming, about: 'Upcoming day groups and sticky headers' },
  a5: { wave: 3, run: stub('a5', 3), about: 'quick add sheet with chips' },
  a6: { wave: 3, run: stub('a6', 3), about: 'WhenSheet results' },
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
