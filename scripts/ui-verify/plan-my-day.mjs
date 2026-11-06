// Plan my day against the local Vikunja, with a fake OpenAI-compatible model on a local port
// (docs/cross-app-semantics-v1.md section 14.10, docs/ai-assistant-v1.md section 10). The checks are on
// what the server holds and what the model was sent.
//
//   node plan-my-day.mjs [tokenPath] [--out DIR] [--server http://127.0.0.1:3456] [--port 11939] [--only a,b]
//
//   A. Without the assistant: a leftover is written at once, a tick and a length change the meter,
//      Arrange draws a draft and saves nothing, a drag and the sheet change only the draft, a kept task,
//      Accept writes the blocks.
//   B. Ready: the start reminder, the evening nudge, Undo all puts everything back.
//   C. The assistant: one request per open or day, what it sends, suggestions dashed with a reason,
//      Apply, a suggested length and start, First free slots without a request, Ask again, a failure said
//      once while the session goes on.
//   D. The request reads the day from the server: a block deleted elsewhere is not busy.
//   E. The nudge in Settings, its notification opening Plan.
//   F. a11y on Plan and on every step, a 24-hour clock, reduced motion, the Plan bottom-bar slot,
//      routine pills on the timeline, a block too short for a check.
//   G. Opened by a link while the planner is off it goes to Today; once it is on, Plan opens.
//
// It creates only tasks titled `PMD ...` and deletes them at the end, and any an earlier run left behind.
// It never seeds the server. The app must be signed in (node login.mjs); set VICU_EMULATOR for the device.

import { createServer } from 'node:http'
import * as d from './droid.mjs'
import * as v from './nav.mjs'
import { audit, printFindings } from './a11y.mjs'
import { DEFAULT_TOKEN_PATH, resolveToken } from './login.mjs'

const arg = (name) => {
  const i = process.argv.indexOf(name)
  return i > 0 ? process.argv[i + 1] : null
}
const OUT = arg('--out') ?? 'plan-my-day'
d.setOutDir(OUT)
const SERVER = arg('--server') ?? 'http://127.0.0.1:3456'
const PORT = Number(arg('--port') ?? 11939)
const ONLY = (arg('--only') ?? '').split(',').filter(Boolean)
const tokenArg = process.argv.slice(2).find((a, i, all) => !a.startsWith('--') && !all[i - 1]?.startsWith('--'))
const TOKEN = resolveToken(tokenArg ?? DEFAULT_TOKEN_PATH)
if (!TOKEN) {
  console.error('no API token found')
  process.exit(2)
}

const PREFIX = 'PMD '
const ROUTINE = 'PMD routine'
const hourPx = v.px(56)

let failures = 0
function check(ok, what) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${what}`)
  if (!ok) failures++
  return ok
}

async function step(name, fn) {
  if (ONLY.length && !ONLY.includes(name[0].toLowerCase()) && !ONLY.includes(name)) return
  console.log(`-- ${name}`)
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
  // The routine this run made lives in a hidden carrier; only that one goes, never a seeded routine.
  const carriers = list(await api('GET', '/tasks?per_page=100&q=vicu-routine'))
  for (const carrier of carriers) {
    if (`${carrier.title} ${carrier.description}`.includes(ROUTINE)) await api('DELETE', `/tasks/${carrier.id}`).catch(() => {})
  }
}

async function serverTask(id, pred, ms = 30000) {
  const end = Date.now() + ms
  for (;;) {
    const task = await getTask(id)
    if (pred(task) || Date.now() > end) return task
    await d.sleep(600)
  }
}

// ---- the device's clock --------------------------------------------------------------------------

const today = d.sh('date +%Y-%m-%d').trim()
const offset = d.sh('date +%z').trim()
const offsetMinutes = (offset[0] === '-' ? -1 : 1) * (+offset.slice(1, 3) * 60 + +offset.slice(3, 5))
const midnightUtc = Date.parse(`${today}T00:00:00Z`) - offsetMinutes * 60000
const DAY = 24 * 3600 * 1000
const isoAt = (days, minute) => new Date(midnightUtc + days * DAY + minute * 60000).toISOString().replace('.000Z', 'Z')
const dueOf = (days) => new Date(midnightUtc + days * DAY + (24 * 3600 - 1) * 1000).toISOString().replace('.000Z', 'Z')
const dayOf = (iso) => Math.floor((Date.parse(iso) - midnightUtc) / DAY)
const minuteOf = (iso) => Math.round(((Date.parse(iso) - midnightUtc) % DAY) / 60000)
const isNull = (iso) => !iso || iso.startsWith('0001-01-01')
const tomorrowText = new Date(midnightUtc + DAY - offsetMinutes * -60000).toISOString().slice(0, 10)

// ---- the fake model ------------------------------------------------------------------------------

const bodies = []
const model = {
  failNext: false,
  // The same answer as the desktop's scenario; a task it does not know is dropped by the checks.
  answer: () => ({
    leftovers: [
      { task_id: created.late, action: 'tomorrow', reason: 'Fits tomorrow' },
      { task_id: created['late two'], action: 'no_date', reason: 'No real deadline' },
    ],
    picks: [
      { task_id: created.first, minutes: 60, start: '10:00', reason: 'Urgent, long stretch' },
      { task_id: created.second, minutes: 15, start: '08:00', reason: 'Quick one first' },
      { task_id: created.inbox, minutes: 30, start: null, reason: 'Fits a gap' },
    ],
    can_wait: [],
  }),
}

const sse = (chunks) => chunks.map((chunk) => `data: ${JSON.stringify(chunk)}\n\n`).join('') + 'data: [DONE]\n\n'
const text = (value) => sse([{ choices: [{ delta: { content: JSON.stringify(value) } }] }, { choices: [{ delta: {}, finish_reason: 'stop' }] }])

const fake = createServer((req, res) => {
  let body = ''
  req.on('data', (chunk) => { body += chunk })
  req.on('end', () => {
    if (req.method === 'GET' && req.url.endsWith('/models')) {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ data: [{ id: 'fake' }] }))
      return
    }
    const request = JSON.parse(body || '{}')
    if (Array.isArray(request.tools) && request.tools.length > 0) {
      res.writeHead(200, { 'content-type': 'text/event-stream' })
      res.end(sse([
        { choices: [{ delta: { tool_calls: [{ index: 0, id: 'call_1', type: 'function', function: { name: 'add_numbers', arguments: '{"a":2,"b":3}' } }] } }] },
        { choices: [{ delta: {}, finish_reason: 'tool_calls' }] },
      ]))
      return
    }
    const user = String(request.messages?.find((m) => m.role === 'user')?.content ?? '')
    if (!user.includes('<plan>')) {
      res.writeHead(200, { 'content-type': 'text/event-stream' })
      res.end(text({ answer: 5 }))
      return
    }
    bodies.push({ user, request })
    if (model.failNext) {
      res.writeHead(400, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ error: { message: 'down' } }))
      return
    }
    res.writeHead(200, { 'content-type': 'text/event-stream' })
    res.end(text(model.answer()))
  })
})

// ---- the screen ----------------------------------------------------------------------------------

async function waitFor(pred, what, ms = 45000) {
  const end = Date.now() + ms
  for (;;) {
    const nodes = d.dump()
    const hit = pred(nodes)
    if (hit) return hit
    const wait = nodes.find((n) => n.text === 'Wait')
    if (wait && nodes.some((n) => /isn.t responding/.test(n.text ?? ''))) await d.tap(wait.cx, wait.cy, 1500)
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`)
    await d.sleep(500)
  }
}

const byText = (t) => (nodes) => nodes.find((n) => n.text === t)
const byDesc = (t) => (nodes) => nodes.find((n) => n['content-desc'] === t)
const descStarts = (t) => (nodes) => nodes.find((n) => n['content-desc']?.startsWith(t))
const textHas = (t) => (nodes) => nodes.find((n) => n.text?.includes(t))
const tapNode = (n, wait = 1000) => d.tap(n.cx, n.cy, wait)

/** Scrolls a list until the node is on screen and clear of the bars. The finger stops before it lifts: a fling could skip the row. */
async function scrollTo(pred, label) {
  const s = v.sc
  const clear = (n) => n && n.y2 < s.height * 0.86 && n.y1 > s.height * 0.12
  const x = Math.round(s.width * 0.3)
  for (const [from, to] of [[0.75, 0.35], [0.35, 0.75]]) {
    for (let i = 0; i < 14; i++) {
      const n = pred(d.dump())
      if (clear(n)) return n
      const g = await d.holdGesture(x, Math.round(s.height * from), x, Math.round(s.height * to), 1, { steps: 5, stepMs: 40 })
      await g.release(350)
    }
  }
  throw new Error(`not found after scrolling: ${label}`)
}

/** Scrolls the timeline until the block's top is in the upper part of the viewport. */
async function revealOnGrid(pred, label) {
  const s = v.sc
  const x = v.px(24)
  const drag = async (y1, y2) => {
    const g = await d.holdGesture(x, y1, x, y2, 1, { steps: 6, stepMs: 50 })
    await g.release(400)
  }
  const high = Math.round(s.height * 0.4)
  const low = Math.round(s.height * 0.8)
  for (let i = 0; i < 8; i++) {
    const n = pred(d.dump())
    if (n && n.y1 > s.height * 0.32 && n.y1 < s.height * 0.55) return n
    if (n && n.y1 >= s.height * 0.55) await drag(n.y1, high)
    else if (n) await drag(high, Math.round(s.height * 0.5))
    else await drag(high, low)
  }
  for (let i = 0; i < 8; i++) {
    const n = pred(d.dump())
    if (n) return n
    await drag(low, high)
  }
  throw new Error(`not on the timeline: ${label}`)
}

async function holdAndDrag(x1, y1, x2, y2, { holdMs = 1100, steps = 8 } = {}) {
  d.motionevent('DOWN', x1, y1)
  await d.sleep(holdMs)
  for (let i = 1; i <= steps; i++) {
    d.motionevent('MOVE', Math.round(x1 + ((x2 - x1) * i) / steps), Math.round(y1 + ((y2 - y1) * i) / steps))
    await d.sleep(90)
  }
  await d.sleep(350)
  d.motionevent('UP', x2, y2)
  await d.sleep(1000)
}

const a11yFindings = {}
function a11y(name) {
  const findings = audit(d.dump())
  a11yFindings[name] = findings
  printFindings(name, findings, `${d.OUT}/a11y`)
}

/** The app on its Plan screen, with what the last session left closed. */
async function openPlan() {
  await v.home()
  await v.drawer('Plan')
  await waitFor(byDesc('Plan my day'), 'the Plan screen')
}

async function openSession() {
  await tapNode(await waitFor(byDesc('Plan my day'), 'the sun button'), 1200)
  await waitFor(textHas('of 4 · Leftovers'), 'the session')
}

const tapStep = async (label) => tapNode(await waitFor(byText(label), label), 900)
const nextToChoose = () => tapStep('Next')
const toArrange = () => tapStep('Arrange')
const exitSession = async () => tapNode(await waitFor(byDesc('Exit Plan my day'), 'the X'), 1200)

/** The day toggle, not a leftover's chip of the same word. */
const dayToggle = (label) => (nodes) => nodes.find((n) => n.text === label && !n['content-desc'] && n.y1 < v.sc.height * 0.3)

const candidate = (title) => (nodes) => nodes.find((n) => n.checkable && (n.text?.includes(title) || d.subtree(nodes, n).some((x) => x.text === title)))
const meterText = (nodes) => nodes.find((n) => /to place · /.test(n.text ?? ''))?.text ?? ''

/** Only this run's tasks get accepted: a seeded task on the day is unticked, so a run cut off after Accept leaves it alone. */
async function untickOthers() {
  const s = v.sc
  const x = Math.round(s.width * 0.3)
  for (let i = 0; i < 4; i++) await d.swipe(x, Math.round(s.height * 0.3), x, Math.round(s.height * 0.8), 300, 300)
  for (let page = 0; page < 3; page++) {
    const nodes = d.dump()
    const ticked = nodes.filter((n) => n.checkable && n.checked && n.y2 < s.height * 0.88 && n.y1 > s.height * 0.12)
      .filter((n) => !d.subtree(nodes, n).concat(n).some((x2) => x2.text?.startsWith(PREFIX)))
    for (const n of ticked) await tapNode(n, 500)
    await d.swipe(x, Math.round(s.height * 0.75), x, Math.round(s.height * 0.4), 400, 500)
  }
}

async function setDevicePlan(on) {
  await openSettingsTab('General')
  const row = await scrollTo(byText('Show Plan in the drawer'), 'the Plan switch')
  const box = switchOf(row)
  if (!box) throw new Error('the Plan switch has no state')
  if (box.checked !== on) await tapNode(box, 1500)
}

const isField = (n) => n.class === 'android.widget.EditText'

/** The switch of a settings row: the row itself, or a node beside its label. */
const switchOf = (row) => d.dump().find((n) => n.checkable && Math.abs(n.cy - row.cy) < v.px(40))

/** Back only takes the keyboard away while it is up; otherwise it would leave the screen. */
async function hideKeyboard() {
  if (/mInputShown=true/.test(d.sh('dumpsys input_method'))) await d.back()
}

/** Types into a field, the keyboard out of the way afterwards so nothing under it is covered. */
async function fill(field, value) {
  await tapNode(field, 700)
  await d.clearField()
  await d.typeSlow(value, { chunk: 8 })
}

async function openSettingsTab(name) {
  await v.home()
  await v.drawer('Settings')
  await tapNode(await waitFor(byText(name), `the ${name} tab`), 900)
}

/** Provider Ollama at the fake model's address, its one model, the Ask switch. */
async function setUpAssistant() {
  const address = `http://10.0.2.2:${PORT}/v1`
  await openSettingsTab('Assistant')
  const provider = await scrollTo(byText('Provider'), 'the provider row')
  if (!d.dump().some((n) => n.text === 'Ollama')) {
    await tapNode(provider, 900)
    await tapNode(await waitFor(byText('Ollama'), 'Ollama in the list'), 1200)
  }
  if (!d.dump().some((n) => n.text === address)) {
    const edit = byText('Edit')(d.dump())
    if (edit) await tapNode(edit, 700)
    await fill(await waitFor((nodes) => nodes.find(isField), 'the address field'), address)
    await tapNode(await waitFor(byText('Save address'), 'Save address'), 1200)
  }
  await waitFor(byText(address), 'the saved address')
  const separate = await scrollTo(byText('Use a different model for Ask'), 'the model row')
  const modelField = d.dump().filter((n) => isField(n) && n.y2 <= separate.y1).at(-1)
  if (modelField.text !== 'fake') {
    await fill(modelField, 'fake')
    await tapNode(await waitFor(byText('Save'), 'Save the model'), 1500)
  }
  await hideKeyboard()
  await tapNode(await scrollTo(byText('Test connection'), 'Test connection'), 1500)
  await d.sleep(3000)
  const ask = await scrollTo(byText('Ask'), 'the Ask switch')
  const askSwitch = switchOf(ask)
  if (askSwitch && !askSwitch.checked) await tapNode(askSwitch, 2500)
  d.shot('assistant-settings')
}

async function tearDownAssistant() {
  await openSettingsTab('Assistant')
  await tapNode(await scrollTo(byText('Delete assistant data'), 'Delete assistant data'), 900)
  await tapNode(await waitFor(byText('Delete'), 'the Delete button'), 1500)
}

let routineMade = false
/** A routine at its default 09:00, every day: the pill on the timeline, and a name never to send. */
async function makeRoutine() {
  if (routineMade) return
  await v.home()
  await v.drawer('Routines')
  await tapNode(await waitFor((nodes) => nodes.find((n) => n['content-desc'] === 'New routine' || n.text === 'New routine'), 'the New routine button'), 1200)
  await fill(await waitFor((nodes) => nodes.find(isField), 'the name field'), ROUTINE)
  await hideKeyboard()
  await tapNode(await waitFor(byText('Save'), 'Save'), 2000)
  await waitFor((nodes) => nodes.find((n) => n.text?.includes(ROUTINE)), 'the routine in the list')
  routineMade = true
}

/** The nudge's switch in Settings, General, Plan. */
async function openNudgeRow() {
  await openSettingsTab('General')
  return scrollTo(byText('Ask me to plan tomorrow'), 'the nudge row')
}

const waitForBody = async (from, day) => {
  const end = Date.now() + 45000
  for (;;) {
    const hit = bodies.slice(from).find((b) => b.user.includes(`"day":"${day}"`))
    if (hit || Date.now() > end) return hit ?? null
    await d.sleep(300)
  }
}
const planBlock = (user) => /<plan>\n(.*)\n<\/plan>/.exec(user ?? '')?.[1] ?? 'no request'

/** A pending job of the app, run now: the nudge's is among them, and the rest are harmless here. */
function runPendingJobs() {
  const dumped = d.sh('dumpsys jobscheduler')
  const jobs = new Set()
  for (const m of dumped.matchAll(/JOB (?:(\S+):)?\S+\/(\d+): \S+ \S*com\.rendyhd\.vicu\.debug\/androidx\.work/g)) {
    jobs.add(`${m[1] ? `-n ${m[1]} ` : ''}${d.APP_ID} ${m[2]}`)
  }
  for (const job of jobs) d.sh(`cmd jobscheduler run -f ${job}`)
  return jobs.size
}

// ---- the run -------------------------------------------------------------------------------------

const created = {}
async function make(key, fields, project) {
  const task = await api('POST', `/projects/${project ?? projectId}/tasks`, { title: `${PREFIX}${key}`, ...fields })
  created[key] = task.id
  return task
}

/** The same six tasks each part starts from, whatever the part before it left. The routine's carrier stays. */
async function freshTasks() {
  const found = list(await api('GET', `/tasks?per_page=100&q=${encodeURIComponent(PREFIX.trim())}`))
  for (const task of found) {
    if (task.title?.startsWith(PREFIX) && task.title !== ROUTINE) await api('DELETE', `/tasks/${task.id}`).catch(() => {})
  }
  await make('late', { due_date: dueOf(-2) })
  await make('late two', { due_date: dueOf(-1) })
  await make('first', { due_date: dueOf(1), priority: 3 })
  await make('second', { due_date: dueOf(1) })
  await make('kept', { due_date: dueOf(1) })
  await make('inbox', {}, inboxId)
}

let projectId
let inboxId
const clockBefore = d.sh('settings get system time_12_24').trim()

await new Promise((resolve) => fake.listen(PORT, '127.0.0.1', resolve))

try {
  await deleteLeftovers()
  const projects = list(await api('GET', '/projects?per_page=200'))
  const inbox = projects.find((p) => p.title === 'Inbox')
  inboxId = inbox.id
  projectId = (projects.find((p) => p.id > 0 && !p.is_archived && p.title !== 'Inbox') ?? inbox).id

  await freshTasks()

  d.sh(`pm grant ${d.APP_ID} android.permission.POST_NOTIFICATIONS`)
  d.sh('settings put system time_12_24 24')
  d.stop()
  await v.launch(4000)
  await waitFor(byDesc('Open menu'), 'the app')
  await setDevicePlan(true)

  await step('A without the assistant', async () => {
    await openPlan()
    d.shot('plan')
    a11y('plan')
    await openSession()
    d.shot('leftovers')
    a11y('leftovers')
    check(!!textHas('Unfinished tasks from before')(d.dump()), 'the session opens on Leftovers')

    await tapNode(await scrollTo(descStarts(`Tomorrow: ${PREFIX}late`), 'the Tomorrow chip'), 900)
    let task = await serverTask(created.late, (t) => dayOf(t.due_date) === 1)
    check(dayOf(task.due_date) === 1, 'a leftover action is written at once')
    check(!!(await waitFor(byText('Moved to tomorrow'), 'the row saying so')), 'the row says what happened')

    await tapNode(await scrollTo(descStarts(`Done: ${PREFIX}late two`), 'the Done chip'), 900)
    task = await serverTask(created['late two'], (t) => t.done)
    check(task.done === true, 'Done completes the task')

    await tapNode(await waitFor(dayToggle('Tomorrow'), 'the day toggle'), 1000)
    await nextToChoose()
    await waitFor(textHas('of 4 · Choose'), 'Choose')
    d.shot('choose')
    a11y('choose')
    const first = await waitFor(candidate(`${PREFIX}first`), 'the first task')
    check(first.checked === true, 'what is on the day is ticked')
    const inboxRow = await scrollTo(candidate(`${PREFIX}inbox`), 'the Inbox task')
    check(inboxRow.checked === false, 'the Inbox is not')
    const before = meterText(d.dump()) || (await scrollTo((n) => n.find((x) => /to place · /.test(x.text ?? '')), 'the meter'), meterText(d.dump()))
    await tapNode(await scrollTo(candidate(`${PREFIX}inbox`), 'the Inbox task'), 700)
    const meterNode = await scrollTo((n) => n.find((x) => /to place · /.test(x.text ?? '')), 'the meter')
    check(meterNode.text !== before, `ticking one changes the meter: ${before} -> ${meterNode.text}`)
    await tapNode(await scrollTo(descStarts(`Length of ${PREFIX}second`), 'its length'), 700)
    const second = await scrollTo(descStarts(`Length of ${PREFIX}second`), 'its length')
    check(second['content-desc'].includes('45m'), `a tap goes to the next length: ${second['content-desc']}`)

    await untickOthers()
    await toArrange()
    await waitFor(textHas('of 4 · Arrange'), 'Arrange')
    d.shot('arrange')
    a11y('arrange')
    await waitFor(descStarts(`${PREFIX}first,`), 'the draft on the timeline')
    check(true, 'Arrange draws the draft on the timeline')
    check(!d.dump().some((n) => n['content-desc'] === `Complete ${PREFIX}first`), 'a draft block has no check')
    task = await getTask(created.first)
    check(isNull(task.start_date), 'nothing is saved yet')

    // A tap: the sheet. No time keeps it without one.
    await tapNode(await revealOnGrid(descStarts(`${PREFIX}kept,`), 'the kept task'), 1000)
    d.shot('draft-sheet')
    a11y('draft-sheet')
    await tapNode(await waitFor(byDesc(`No time: ${PREFIX}kept`), 'No time'), 1000)
    await waitFor(byText('No time, on tomorrow'), 'the row without a time')
    check(!d.dump().some((n) => n['content-desc']?.startsWith(`${PREFIX}kept,`)), 'a kept task leaves the draft')

    // The sheet's nudge on another block.
    const secondBlock = await revealOnGrid(descStarts(`${PREFIX}second,`), 'the second block')
    await tapNode(secondBlock, 1000)
    await tapNode(await waitFor(byText('15m later'), '15m later'), 700)
    await d.back()
    const movedText = (await waitFor(descStarts(`${PREFIX}second,`), 'the second block'))['content-desc']
    check(true, `the sheet moves the draft: ${secondBlock['content-desc']} -> ${movedText}`)

    // A press and hold moves a draft block.
    const firstBlock = await revealOnGrid(descStarts(`${PREFIX}first,`), 'the first block')
    await holdAndDrag(firstBlock.cx, firstBlock.cy, firstBlock.cx, firstBlock.cy + hourPx * 4)
    task = await getTask(created.first)
    check(isNull(task.start_date), 'dragging a draft saves nothing')
    const firstAfter = await revealOnGrid(descStarts(`${PREFIX}first,`), 'the first block')
    check(firstAfter['content-desc'] !== firstBlock['content-desc'], `but moves it: ${firstBlock['content-desc']} -> ${firstAfter['content-desc']}`)
    const movedStart = /(\d\d):(\d\d)/.exec(firstAfter['content-desc'])
    d.shot('arrange-moved')

    await tapNode(await waitFor(byText('Accept the plan'), 'Accept'), 1500)
    await waitFor(byText('Tomorrow is planned'), 'Ready')
    d.shot('ready')
    a11y('ready')
    task = await serverTask(created.first, (t) => !isNull(t.start_date))
    check(dayOf(task.start_date) === 1, 'Accept writes the block')
    check(minuteOf(task.start_date) === +movedStart[1] * 60 + +movedStart[2], 'moved by hand, it stays where it was put')
    const secondTask = await serverTask(created.second, (t) => !isNull(t.start_date))
    check(minuteOf(secondTask.end_date) - minuteOf(secondTask.start_date) === 45, 'a changed length is written')
    const inboxTask = await serverTask(created.inbox, (t) => !isNull(t.start_date))
    check(dayOf(inboxTask.due_date) === 1, 'a task ticked from the Inbox gets the day and a block')
    const keptTask = await getTask(created.kept)
    check(dayOf(keptTask.due_date) === 1 && isNull(keptTask.start_date), 'a kept task stays on the day without a time')
  })

  await step('B ready', async () => {
    await tapNode(await scrollTo(byText('Remind me when each block starts'), 'the reminder switch'), 900)
    let task = await serverTask(created.first, (t) => (t.reminders ?? []).some((r) => r.relative_to === 'start_date'))
    const reminder = (task.reminders ?? []).find((r) => r.relative_to === 'start_date')
    check(reminder?.relative_period === 0, 'the reminder follows the start')

    await tapNode(await scrollTo(textHas('Ask me to plan tomorrow at'), 'the nudge switch'), 900)
    d.shot('ready-switches')

    await tapNode(await scrollTo(byText('Undo all'), 'Undo all'), 1500)
    task = await serverTask(created.first, (t) => isNull(t.start_date))
    check(isNull(task.start_date) && isNull(task.end_date), 'Undo all takes the block away')
    check(!(task.reminders ?? []).some((r) => r.relative_to === 'start_date'), 'and the reminder')
    const late = await serverTask(created.late, (t) => dayOf(t.due_date) === -2)
    check(dayOf(late.due_date) === -2, 'and the leftover')
    const reopened = await serverTask(created['late two'], (t) => !t.done)
    check(reopened.done === false, 'and reopens what it completed')
    const inboxBack = await serverTask(created.inbox, (t) => isNull(t.due_date))
    check(isNull(inboxBack.due_date) && isNull(inboxBack.start_date), "and the Inbox task's date")
    await exitSession()
  })

  await step('C the assistant', async () => {
    await freshTasks()
    await makeRoutine()
    await setUpAssistant()

    await openPlan()
    const asked = bodies.length
    await openSession()
    await tapNode(await waitFor(dayToggle('Tomorrow'), 'the day toggle'), 1000)
    const sent = await waitForBody(asked, tomorrowText)
    check(!!sent, 'it asks for the day on screen')
    await d.sleep(2500)
    const forDay = bodies.slice(asked).filter((b) => b.user.includes(`"day":"${tomorrowText}"`))
    check(forDay.length === 1, `one request for the day: ${forDay.length}, ${bodies.length - asked} in all`)
    check(['<plan>', '<leftovers>', '<candidates>'].every((tag) => sent?.user.includes(tag)), 'it sends the plan, the leftovers and the candidates')
    check(sent?.user.includes('"routines":["09:00"') && !sent?.user.includes(ROUTINE), `a routine goes as its time, never its name: ${planBlock(sent?.user)}`)
    check(sent?.request.temperature === 0 && sent?.request.reasoning_effort === 'low', 'deterministic, as dictation is')
    const schema = sent?.request.response_format?.json_schema?.schema
    check(['leftovers', 'picks', 'can_wait'].every((key) => schema?.properties?.[key]), 'with the planDay schema')
    check(!!byDesc('Assistant suggestions')(d.dump()), 'the assistant switch is there')

    const apply = await waitFor(textHas('Apply the'), 'Apply the suggestions')
    check(apply.text === 'Apply the 2 suggestions', `it counts them: ${apply.text}`)
    d.shot('assistant-leftovers')
    a11y('assistant-leftovers')
    check(!!(await scrollTo(byText('Fits tomorrow'), 'the reason')), 'a suggested leftover has its reason')
    check(isNull((await getTask(created.first)).start_date) && dayOf((await getTask(created.late)).due_date) === -2, 'a suggestion writes nothing')

    await nextToChoose()
    await waitFor(textHas('of 4 · Choose'), 'Choose')
    d.shot('assistant-choose')
    a11y('assistant-choose')
    check(!!(await scrollTo(byText('Suggested'), 'the Suggested heading')), 'what it suggests has a heading')
    const suggested = await scrollTo(candidate(`${PREFIX}inbox`), 'the suggested task')
    check(suggested.checked === false, 'a suggested task is not ticked until you tick it')
    check(!!(await scrollTo(byText('Fits a gap'), 'its reason')), 'its reason shows')
    d.shot('assistant-suggested')
    const length = await scrollTo(descStarts(`Length of ${PREFIX}first`), 'the length chip')
    check(length['content-desc'].includes('1h'), `a suggested length: ${length['content-desc']}`)

    await toArrange()
    await waitFor(textHas('of 4 · Arrange'), 'Arrange')
    d.shot('assistant-arrange')
    const first = await revealOnGrid(descStarts(`${PREFIX}first,`), 'the first block')
    check(first['content-desc'].includes('10:00'), `its start is used: ${first['content-desc']}`)
    const second = await revealOnGrid(descStarts(`${PREFIX}second,`), 'the second block')
    check(second['content-desc'].includes('08:00') && second['content-desc'].includes('15m'), `and so are the others: ${second['content-desc']}`)
    await tapNode(await revealOnGrid(descStarts(`${PREFIX}first,`), 'the first block'), 1000)
    await waitFor(textHas('Urgent, long stretch'), 'the reason in the sheet')
    check(true, 'the sheet has the reason')
    d.shot('assistant-sheet')
    await d.back()

    const before = bodies.length
    await tapNode(await waitFor(byText('First free slots'), 'First free slots'), 1000)
    const plain = await revealOnGrid(descStarts(`${PREFIX}first,`), 'the first block')
    check(plain['content-desc'].includes('08:00'), `First free slots: ${plain['content-desc']}`)
    check(bodies.length === before, 'and no new request')
    await tapNode(await waitFor(byText("Assistant's order"), "Assistant's order"), 1000)

    await tapStep('Back')
    await waitFor(textHas('of 4 · Choose'), 'Choose again')
    await scrollTo(byText('Ask again'), 'Ask again')
    const beforeTick = bodies.length
    await tapNode(await scrollTo(candidate(`${PREFIX}inbox`), 'the suggested task'), 800)
    check(bodies.length === beforeTick, 'a tick sends nothing')
    await tapNode(await scrollTo(byText('Ask again'), 'Ask again'), 2500)
    check(bodies.length === beforeTick + 1, `Ask again sends one more: ${bodies.length - beforeTick}`)

    model.failNext = true
    await tapNode(await scrollTo(byText('Ask again'), 'Ask again'), 1500)
    const failed = await waitFor(textHas('No suggestions'), 'the failure')
    check(failed.text.includes('No suggestions'), `a failure is said: ${failed.text}`)
    d.shot('assistant-failed')
    model.failNext = false
    check(!!byText('Ask again')(d.dump()) || !!(await scrollTo(byText('Ask again'), 'Ask again')), 'and Ask again stays')
    await toArrange()
    await waitFor(descStarts(`${PREFIX}first,`), 'the draft')
    check(true, 'the session goes on with first free slots')
    await exitSession()

    // Apply writes the leftovers it names, in one go.
    await openPlan()
    await openSession()
    await tapNode(await waitFor(textHas('Apply the'), 'Apply the suggestions'), 1500)
    const moved = await serverTask(created.late, (t) => dayOf(t.due_date) === 1)
    check(dayOf(moved.due_date) === 1, 'Apply moves a leftover')
    const cleared = await serverTask(created['late two'], (t) => isNull(t.due_date))
    check(isNull(cleared.due_date), 'and clears the date of another')
    await exitSession()
  })

  await step('D the fresh read', async () => {
    await freshTasks()
    await make('elsewhere', { due_date: dueOf(1), start_date: isoAt(1, 600), end_date: isoAt(1, 660) })
    await setUpAssistant()
    await openPlan()
    let asked = bodies.length
    await openSession()
    await tapNode(await waitFor(dayToggle('Tomorrow'), 'the day toggle'), 1000)
    let sent = await waitForBody(asked, tomorrowText)
    const withBlock = planBlock(sent?.user)
    check(withBlock.includes('"free":[{"start":"08:00","end":"10:00"},{"start":"11:00","end":"18:00"}]'), `a block made elsewhere is busy: ${withBlock}`)
    await exitSession()

    // Another device takes the block away; Room still has it until something reads the server.
    await api('DELETE', `/tasks/${created.elsewhere}`)
    await openPlan()
    asked = bodies.length
    await openSession()
    await tapNode(await waitFor(dayToggle('Tomorrow'), 'the day toggle'), 1000)
    sent = await waitForBody(asked, tomorrowText)
    const fresh = planBlock(sent?.user)
    check(fresh.includes('"free":[{"start":"08:00","end":"18:00"}]'), `the request reads the server first: ${fresh}`)
    await nextToChoose()
    await toArrange()
    const first = await revealOnGrid(descStarts(`${PREFIX}first,`), 'the first block')
    check(first['content-desc'].includes('10:00'), `so the suggested start stands: ${first['content-desc']}`)
    await exitSession()
  })

  await step('E the nudge', async () => {
    const row = await openNudgeRow()
    const box = switchOf(row)
    if (!box?.checked) await tapNode(box ?? row, 1200)
    const at = await waitFor(descStarts('Ask at '), 'the time button')
    check(/\d\d:\d\d/.test(at['content-desc']), `the switch shows a time: ${at['content-desc']}`)
    d.shot('settings-nudge')
    a11y('settings-nudge')
    await tapNode(at, 900)
    check(!!(await waitFor(byText('Ask at'), 'the dialog')), 'the time has its dialog')
    await tapNode(await waitFor(byText('Cancel'), 'Cancel'), 800)

    await d.sleep(1500)
    await v.home()
    const ran = runPendingJobs()
    let found = false
    for (let i = 0; i < 30 && !found; i++) {
      await d.sleep(500)
      // The channel has the same name, so this looks for the notification's own id.
      found = /pkg=com\.rendyhd\.vicu\.debug[^\n]* id=999997 /.test(d.sh('dumpsys notification --noredact'))
    }
    check(found, `the job posts the notification (${ran} jobs run)`)
    d.sh('cmd statusbar expand-notifications')
    await d.sleep(1500)
    const note = await waitFor(byText('Plan tomorrow'), 'the notification in the shade', 15000)
    d.shot('nudge-notification')
    await tapNode(note, 2500)
    await waitFor(byDesc('Plan my day'), 'Plan, opened from the notification')
    check(true, 'a tap opens Plan')

    const offRow = await openNudgeRow()
    await tapNode(switchOf(offRow) ?? offRow, 1000)
  })

  await step('F the open items', async () => {
    await freshTasks()
    await make('short', { due_date: dueOf(0), start_date: isoAt(0, 660), end_date: isoAt(0, 675) })
    await make('after', { due_date: dueOf(0), start_date: isoAt(0, 675), end_date: isoAt(0, 705) })
    await makeRoutine()

    // A block too short for a check, on a 24-hour clock. Plan reads the day from what the phone holds
    // and a read of the day deletes nothing, so an earlier run's block of this name may still be there:
    // the session's full read comes first.
    await openPlan()
    await openSession()
    await d.sleep(5000)
    await exitSession()
    await revealOnGrid(descStarts(`${PREFIX}after,`), 'the next block')
    const short = await revealOnGrid(descStarts(`${PREFIX}short,`), 'the short block')
    d.shot('plan-short-block')
    a11y('plan-short-block')
    const heightDp = short.h / v.sc.scale
    check(heightDp < 20, `15 minutes against the next block is drawn short: ${heightDp.toFixed(0)} dp`)
    let nodes = d.dump()
    check(!nodes.some((n) => n['content-desc'] === `Complete ${PREFIX}short`), 'it has no check')
    check(nodes.some((n) => n['content-desc'] === `Complete ${PREFIX}after`), 'the next one has')
    check(nodes.some((n) => n.text === '11:00'), 'the labels are on a 24-hour clock')
    await tapNode(short, 1000)
    const sheetCheck = await waitFor((all) => all.find((n) => n.checkable && (n.text === `${PREFIX}short` || d.subtree(all, n).some((x) => x.text === `${PREFIX}short`))), 'the check in the sheet')
    d.shot('short-block-sheet')
    await tapNode(sheetCheck, 1000)
    const doneShort = await serverTask(created.short, (t) => t.done)
    check(doneShort.done === true, 'its sheet has the check')
    await d.back()

    // A tap in the middle of a block right after another opens that block, not the one above.
    const after = await revealOnGrid(descStarts(`${PREFIX}after,`), 'the next block')
    await tapNode(after, 1000)
    check(!!(await waitFor(byText('Remove time'), 'the sheet')) && d.dump().some((n) => n.text === `${PREFIX}after`), 'a tap in the middle opens the block itself')
    await d.back()
    // A press and hold in the middle of a short block moves it.
    const pressed = await revealOnGrid(descStarts(`${PREFIX}after,`), 'the next block')
    await holdAndDrag(pressed.cx, pressed.cy, pressed.cx, pressed.cy + hourPx * 2)
    const movedAfter = await serverTask(created.after, (t) => minuteOf(t.start_date) !== 675)
    check(minuteOf(movedAfter.start_date) === 675 + 120, `a press and hold in the middle moves it: ${minuteOf(movedAfter.start_date)}`)

    // The routine's pill.
    const pill = await revealOnGrid(byDesc(`Routine ${ROUTINE}`), 'the routine pill')
    check(!!pill, 'the routine has its pill on the timeline')
    d.shot('plan-routine')
    a11y('plan-routine')

    // A 12-hour clock.
    d.sh('settings put system time_12_24 12')
    d.stop()
    await v.launch(4000)
    await openPlan()
    await revealOnGrid(byDesc(`Routine ${ROUTINE}`), 'the routine pill')
    nodes = d.dump()
    const twelve = nodes.filter((n) => /^\d{1,2}:00\s?[AP]M$/i.test(n.text ?? '') || /^\d{1,2}\s?[AP]M$/i.test(n.text ?? ''))
    check(twelve.length > 3, `the labels follow a 12-hour clock: ${twelve.slice(0, 3).map((n) => n.text).join(', ')}`)
    d.shot('plan-12h')
    a11y('plan-12h')
    d.sh('settings put system time_12_24 24')
    d.stop()
    await v.launch(4000)

    // Reduced motion: every step still works and nothing waits for an animation.
    d.animScale(0)
    await openPlan()
    await openSession()
    await tapNode(await waitFor(dayToggle('Tomorrow'), 'the day toggle'), 800)
    await nextToChoose()
    await waitFor(textHas('of 4 · Choose'), 'Choose')
    await toArrange()
    await waitFor(textHas('of 4 · Arrange'), 'Arrange')
    await waitFor(descStarts(`${PREFIX}first,`), 'the draft')
    d.shot('reduced-motion-arrange')
    check(true, 'with animations off the session walks through its steps')
    await exitSession()
    d.animScale(1)

    // The bottom bar's Plan slot.
    await openSettingsTab('General')
    await scrollTo(byText('Bottom Bar'), 'the Bottom Bar section')
    const slot = await scrollTo(byText('Upcoming'), 'the Upcoming slot')
    await tapNode(slot, 1000)
    await waitFor(textHas('Bottom Bar Slot'), 'the slot editor')
    await tapNode(await waitFor((all) => all.find(isField), 'the view type'), 800)
    await tapNode(await waitFor(byText('Plan'), 'Plan in the menu'), 800)
    d.shot('bottom-bar-editor')
    await tapNode(await waitFor(byText('Save'), 'Save'), 1200)
    await v.home()
    const tab = await waitFor((all) => all.find((n) => n.text === 'Plan' && n.y1 > v.sc.height * 0.85), 'the Plan tab')
    await tapNode(tab, 2000)
    await waitFor(byDesc('Plan my day'), 'Plan from the bottom bar')
    check(true, 'the bottom bar opens Plan')
    d.shot('plan-bottom-bar')
    a11y('plan-bottom-bar')
    await openSession()
    await exitSession()
    check(!!byDesc('Plan my day')(d.dump()), 'and the session comes back to it')
    await openSettingsTab('General')
    await tapNode(await scrollTo(byText('Reset to Defaults'), 'Reset to Defaults'), 1200)

    await tearDownAssistant().catch((e) => console.log(`(could not clear the assistant: ${e.message})`))
  })

  await step('G the planner off and on', async () => {
    // The way a widget or a nudge left over opens it.
    const openByLink = async () => {
      d.sh(`am start -n ${d.MAIN_ACTIVITY} -f 0x24000000 --es ${'navigate_to_view_type'} PLAN`)
      await d.sleep(3000)
    }
    const onPlan = () => !!byDesc('Plan my day')(d.dump())

    await setDevicePlan(false)
    await openByLink()
    check(!onPlan(), 'with the planner off a link to Plan goes elsewhere')
    d.shot('link-planner-off')
    // Long enough for the left screen's flow to stop, so what it holds is what it comes back with.
    await d.sleep(6500)

    await setDevicePlan(true)
    await v.home()
    await v.drawer('Plan')
    await d.sleep(2500)
    const fromDrawer = onPlan()
    check(fromDrawer, 'turned on, the drawer opens Plan')
    d.shot('plan-after-turning-on')
    await v.home()
    await openByLink()
    check(onPlan(), 'and so does the link')
  })

  console.log('-- a11y findings by screen')
  for (const [name, findings] of Object.entries(a11yFindings)) console.log(`   ${name}: ${findings.length}`)
} catch (e) {
  failures++
  console.log(`FAIL the run: ${e.message}`)
  try { d.shot('error-run') } catch { /* ignore */ }
} finally {
  await deleteLeftovers().catch(() => {})
  d.sh(`settings put system time_12_24 ${clockBefore === 'null' ? '12' : clockBefore}`)
  d.animScale(1)
  fake.close()
}

console.log(failures ? `${failures} check(s) failed` : 'all checks passed')
process.exit(failures ? 1 : 0)
