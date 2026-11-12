// Pro on the phone against the local Vikunja (plan section 9.2, contract section 21): with Pro off
// the drawer, a project and Today are as before; with Pro on the drawer has Flagged, Waiting on and
// People, Flagged lists a flagged task, a sequential project's Available view shows only its first
// task, and a promise of theirs typed in quick add ("Zelda Pro: ...") is on Today under Promises due.
//
//   node pro.mjs [tokenPath] [--out DIR] [--server http://127.0.0.1:3456] [--keep-on]
//
// Writes captures to out/pro/ (or DIR) and prints one line per check. Exit code 1 when a check
// failed. Run `node login.mjs` first. It creates only tasks titled `PROV ...`, the project `PROV seq`
// and the label `Zelda Pro`, and deletes them at the end (its own, and any an earlier run left). The
// person mark and the project type stay in the synced preferences under ids that no longer exist.

import * as d from './droid.mjs'
import * as v from './nav.mjs'
import { DEFAULT_TOKEN_PATH, resolveToken } from './login.mjs'

const arg = (name) => {
  const i = process.argv.indexOf(name)
  return i > 0 ? process.argv[i + 1] : null
}
d.setOutDir(arg('--out') ?? 'pro')
const SERVER = arg('--server') ?? 'http://127.0.0.1:3456'
const tokenArg = process.argv.slice(2).find((a, i, all) => !a.startsWith('--') && !all[i - 1]?.startsWith('--'))
const TOKEN = resolveToken(tokenArg ?? DEFAULT_TOKEN_PATH)
if (!TOKEN) {
  console.error('no API token found')
  process.exit(2)
}

const PREFIX = 'PROV '
const PROJECT = 'PROV seq'
const PERSON = 'Zelda Pro'
const PRO_VIEWS = ['Flagged', 'Waiting on', 'People']

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

async function deleteLeftovers() {
  const found = list(await api('GET', `/tasks?per_page=100&q=${encodeURIComponent(PREFIX.trim())}`))
  for (const task of found) {
    if (task.title?.includes(PREFIX)) await api('DELETE', `/tasks/${task.id}`).catch(() => {})
  }
  for (const p of list(await api('GET', '/projects?per_page=200'))) {
    if (p.title === PROJECT) await api('DELETE', `/projects/${p.id}`).catch(() => {})
  }
  for (const l of list(await api('GET', '/labels?per_page=200'))) {
    if (l.title === PERSON) await api('DELETE', `/labels/${l.id}`).catch(() => {})
  }
}

// ---- the screen ----------------------------------------------------------------------------------

async function waitFor(pred, what, ms = 60000) {
  const end = Date.now() + ms
  for (;;) {
    const nodes = d.dump()
    const hit = pred(nodes)
    if (hit) return hit
    const wait = nodes.find((n) => n.text === 'Wait')
    if (wait && nodes.some((n) => /isn.t responding/.test(n.text ?? ''))) await d.tap(wait.cx, wait.cy, 1500)
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`)
    await d.sleep(700)
  }
}

const byText = (t) => (nodes) => nodes.find((n) => n.text === t)
const byDesc = (t) => (nodes) => nodes.find((n) => n['content-desc'] === t)
const tapNode = (n, wait = 1200) => d.tap(n.cx, n.cy, wait)

/** Scrolls the current list up or down until the node is on screen. */
async function scrollTo(pred, label) {
  const s = v.sc
  for (const [from, to] of [[0.8, 0.3], [0.3, 0.8]]) {
    for (let i = 0; i < 14; i++) {
      const n = pred(d.dump())
      if (n) return n
      await d.swipe(Math.round(s.width * 0.4), Math.round(s.height * from), Math.round(s.width * 0.4), Math.round(s.height * to), 350, 700)
    }
  }
  throw new Error(`not found after scrolling: ${label}`)
}

/** The Pro switch in Settings, General; turns it on or off when it is not already. */
async function proSwitch(on) {
  await waitFor(byDesc('Open menu'), 'the app')
  await v.drawer('Settings')
  const general = d.dump().find((n) => n.text === 'General')
  if (general) await tapNode(general)
  let row = await scrollTo(byText('Turn on Pro on this device'), 'the Pro switch')
  // High on the screen, so the layer rows under it are in the dump when it is on.
  const x = Math.round(v.sc.width * 0.4)
  await d.swipe(x, row.cy, x, Math.round(v.sc.height * 0.3), 600, 1000)
  row = byText('Turn on Pro on this device')(d.dump()) ?? row
  const isOn = d.dump().some((n) => n.text === 'Know what you can do now')
  if (isOn !== on) await tapNode(row, 1800)
  if (on) {
    const nodes = await waitFor((ns) => ns.some((n) => n.text === 'Know what you can do now') && ns, 'the layers')
    check(nodes.some((n) => n.text === 'Promises and people'), 'Pro on: Settings shows the two layers')
  }
}

/** The drawer's entries, from the top, as texts. */
async function drawerTexts() {
  await v.openDrawer()
  const nodes = d.dump()
  const texts = nodes.map((n) => n.text).filter(Boolean)
  await v.closeDrawer()
  return texts
}

/** A drawer entry, looked for again after a refresh when the app has not synced it yet. */
async function openFromDrawer(label) {
  for (let i = 0; i < 4; i++) {
    try {
      await v.drawer(label)
      return
    } catch (e) {
      if (i === 3) throw e
      await v.closeDrawer()
      await v.nav('Today')
      // Pull to refresh, so a project or label made on the server reaches the device.
      await d.swipe(Math.round(v.sc.width * 0.5), Math.round(v.sc.height * 0.3), Math.round(v.sc.width * 0.5), Math.round(v.sc.height * 0.8), 500, 4000)
    }
  }
}

// ---- the run -------------------------------------------------------------------------------------

const ids = { tasks: [] }
try {
  await deleteLeftovers()
  const projects = list(await api('GET', '/projects?per_page=200'))
  const inbox = projects.find((p) => p.title === 'Inbox') ?? projects.find((p) => p.id > 0 && !p.is_archived)

  const seq = await api('POST', '/projects', { title: PROJECT })
  ids.project = seq.id
  for (const title of ['one', 'two']) {
    const task = await api('POST', `/projects/${seq.id}/tasks`, { title: `${PREFIX}${title}` })
    ids.tasks.push(task.id)
  }
  const flag = await api('POST', `/projects/${inbox.id}/tasks`, { title: `${PREFIX}flag`, is_favorite: true })
  ids.tasks.push(flag.id)
  const label = await api('POST', '/labels', { title: PERSON, hex_color: 'e8a400' })
  ids.label = label.id

  d.stop()
  await v.launch(5000)
  await waitFor(byDesc('Open menu'), 'the app')

  await step('pro-off', async () => {
    await proSwitch(false)
    const texts = await drawerTexts()
    for (const view of PRO_VIEWS) check(!texts.includes(view), `Pro off: no ${view} in the drawer`)
    await openFromDrawer(PROJECT)
    await waitFor(byText(`${PREFIX}one`), 'the project')
    const nodes = d.dump()
    check(!nodes.some((n) => n['content-desc'] === 'Show Available'), 'Pro off: a project has no Available switch')
    check(nodes.some((n) => n.text === `${PREFIX}two`), 'Pro off: a project lists every task')
    await d.tapDesc('Project options', { wait: 1200 })
    check(!d.dump().some((n) => n.text === 'Type'), 'Pro off: the project menu has no Type')
    d.shot('off-project-menu')
    await d.back()
    await v.nav('Today')
    check(!d.dump().some((n) => /^promises due$/i.test(n.text ?? '')), 'Pro off: Today has no Promises due')
    d.shot('off-today')
  })

  await step('pro-on', async () => {
    await proSwitch(true)
    d.shot('on-settings')
    const texts = await drawerTexts()
    for (const view of PRO_VIEWS) check(texts.includes(view), `Pro on: ${view} in the drawer`)
  })

  await step('flagged', async () => {
    await v.drawer('Flagged')
    const n = await waitFor(byText(`${PREFIX}flag`), 'the flagged task', 30000).catch(() => null)
    check(!!n, 'Flagged lists a flagged task')
    check(d.dump().some((x) => x['content-desc'] === 'Flagged'), 'the row shows the flag')
    d.shot('flagged')
  })

  await step('sequential', async () => {
    await openFromDrawer(PROJECT)
    await waitFor(byText(`${PREFIX}one`), 'the project')
    await d.tapDesc('Project options', { wait: 1200 })
    await tapNode(await waitFor(byText('Type'), 'Type in the menu'), 1200)
    await tapNode(await waitFor(byText('Sequential'), 'the Type dialog'), 1000)
    d.shot('type-dialog')
    await tapNode(await waitFor(byText('Done'), 'Done'), 1500)
    const nodes = await waitFor(
      (ns) => ns.some((n) => n.text === '1 task is not available yet.') && ns,
      'the hidden count',
      20000,
    )
    const shown = [`${PREFIX}one`, `${PREFIX}two`].filter((t) => nodes.some((n) => n.text === t))
    check(nodes.some((n) => n['content-desc'] === 'Show Available'), 'the project shows the view switch')
    check(shown.length === 1, `the Available view shows only the first task (${shown.join(', ') || 'none'})`)
    d.shot('sequential-available')
    await tapNode(await waitFor(byDesc('Show All'), 'the All view'), 1500)
    const all = d.dump()
    check(all.some((n) => n.text === `${PREFIX}one`) && all.some((n) => n.text === `${PREFIX}two`), 'All shows both tasks')
    check(all.some((n) => n.text === 'Later in sequence'), 'the second task says why it waits')
    d.shot('sequential-all')
    // Back to the default, so a later run starts from Available.
    await tapNode(await waitFor(byDesc('Show Available'), 'the Available view'), 1200)
  })

  await step('person', async () => {
    await openFromDrawer(PERSON)
    await d.tapDesc('Label options', { wait: 1200 })
    await tapNode(await waitFor(byText('This is a person'), 'This is a person'), 1500)
    await v.drawer('People')
    check(!!(await waitFor(byText(PERSON), 'the person', 20000).catch(() => null)), 'People lists the person')
    d.shot('people')
  })

  await step('promise-on-today', async () => {
    await v.nav('Today', 1200)
    await tapNode(await waitFor(byDesc('New task'), 'the + button'), 1500)
    await waitFor(byText('New task'), 'the sheet')
    await d.typeSlow(`${PERSON}: ${PREFIX}deck today`)
    await d.sleep(800)
    check(d.dump().some((n) => n.text === 'In their words'), 'quick add offers the promise fields')
    d.shot('quick-add-promise')
    await tapNode(await waitFor(byDesc('Save task'), 'Save task'), 2500)
    if (d.dump().some((n) => n['content-desc'] === 'Save task')) await d.back()
    await v.nav('Today')
    const header = await scrollTo((ns) => ns.find((n) => /^promises due$/i.test(n.text ?? '')), 'Promises due').catch(() => null)
    check(!!header, 'Today shows Promises due')
    const nodes = d.dump()
    check(nodes.some((n) => n.text === `${PREFIX}deck`), 'the promise is on Today')
    check(nodes.some((n) => n['content-desc'] === `${PERSON}'s promise`), "the row shows the person's mark")
    check(nodes.some((n) => n.text === `From ${PERSON}`), 'the row says whose promise it is')
    d.shot('today-promise')
    const mark = nodes.find((n) => n['content-desc'] === `${PERSON}'s promise`)
    if (mark) {
      await tapNode(mark, 1500)
      check(!!(await waitFor(byText('Ask me tomorrow'), 'the verdict sheet', 10000).catch(() => null)), 'the mark opens the verdict sheet')
      d.shot('verdict-sheet')
      await d.back()
    }
  })

  await step('waiting-on', async () => {
    await v.drawer('Waiting on')
    const n = await waitFor(byText(`${PREFIX}deck`), 'the promise', 20000).catch(() => null)
    check(!!n, 'Waiting on lists the promise')
    d.shot('waiting-on')
  })
} catch (e) {
  failures++
  console.log(`FAIL ${e.message}`)
  try { d.shot('error') } catch { /* ignore */ }
} finally {
  for (const id of ids.tasks) await api('DELETE', `/tasks/${id}`).catch(() => {})
  await deleteLeftovers().catch(() => {})
  if (!process.argv.includes('--keep-on')) {
    await proSwitch(false).catch((e) => console.log(`could not turn Pro off again: ${e.message}`))
  }
}

console.log(failures ? `${failures} failed` : 'all passed')
process.exit(failures ? 1 : 0)
