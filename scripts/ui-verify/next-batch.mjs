// The batch of issues labeled `next` against the local Vikunja: the editor's title and labels, long
// notes, Move buttons and Copy notes, adding subtasks in a row, the list scrollbar, and (with --only)
// each later part. Uses only tasks titled `NXT ...` and labels titled `nxt ...`, and deletes them at
// the end, including what a cut-off run left behind.
//
//   node next-batch.mjs [--only editor,notes,subtasks,scrollbar,search,templates,suborder,ask] [--out DIR]
//
// Writes captures to out/next-batch/ and prints one line per check. Exit code 1 when a check failed.
// Run `node login.mjs` first.

import { createServer } from 'node:http'
import * as d from './droid.mjs'
import * as v from './nav.mjs'
import { DEFAULT_TOKEN_PATH, resolveToken } from './login.mjs'

const arg = (name) => {
  const i = process.argv.indexOf(name)
  return i > 0 ? process.argv[i + 1] : null
}
d.setOutDir(arg('--out') ?? 'next-batch')
const ONLY = arg('--only')?.split(',') ?? null
const wanted = (part) => !ONLY || ONLY.includes(part)
const SERVER = arg('--server') ?? 'http://127.0.0.1:3456'
const TOKEN = resolveToken(DEFAULT_TOKEN_PATH)
if (!TOKEN) {
  console.error('no API token found')
  process.exit(2)
}

const PREFIX = 'NXT '
const LABEL_PREFIX = 'nxt '

let failures = 0
function check(ok, what) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${what}`)
  if (!ok) failures++
}

async function part(name, fn) {
  if (!wanted(name)) return
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

async function cleanUp() {
  const tasks = list(await api('GET', `/tasks?per_page=200&q=${encodeURIComponent(PREFIX.trim())}`))
  for (const task of tasks) {
    if (task.title?.startsWith(PREFIX)) await api('DELETE', `/tasks/${task.id}`).catch(() => {})
  }
  const labels = list(await api('GET', `/labels?per_page=200&q=${encodeURIComponent(LABEL_PREFIX.trim())}`))
  for (const label of labels) {
    if (label.title?.startsWith(LABEL_PREFIX)) await api('DELETE', `/labels/${label.id}`).catch(() => {})
  }
}

const today = d.sh('date +%Y-%m-%d').trim()
const offset = d.sh('date +%z').trim()
const offsetMinutes = (offset[0] === '-' ? -1 : 1) * (+offset.slice(1, 3) * 60 + +offset.slice(3, 5))
const midnightUtc = Date.parse(`${today}T00:00:00Z`) - offsetMinutes * 60000
const endOfToday = new Date(midnightUtc + (24 * 3600 - 1) * 1000).toISOString().replace('.000Z', 'Z')

let projectId
async function makeTask(title, fields = {}) {
  return api('POST', `/projects/${projectId}/tasks`, { title: PREFIX + title, due_date: endOfToday, ...fields })
}

async function makeLabel(title) {
  return api('POST', '/labels', { title: LABEL_PREFIX + title })
}

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
    await d.sleep(600)
  }
}

const byText = (t) => (nodes) => nodes.find((n) => n.text === t)
const byDesc = (t) => (nodes) => nodes.find((n) => n['content-desc'] === t)
const textStarts = (t) => (nodes) => nodes.find((n) => n.text?.startsWith(t))
const tapNode = (n, wait = 1200) => d.tap(n.cx, n.cy, wait)
/** Cascade starts each line with a zero-width space. */
const clean = (text) => (text ?? '').replace(/​/g, '')

/** Forces a sync so what the script made on the server reaches the phone. */
async function refreshToday() {
  await v.nav('Today', 1500)
  const s = v.sc
  await d.swipe(Math.round(s.width * 0.5), Math.round(s.height * 0.3), Math.round(s.width * 0.5), Math.round(s.height * 0.8), 500, 2500)
}

async function findRow(title) {
  const full = PREFIX + title
  const s = v.sc
  for (let i = 0; i < 14; i++) {
    const n = textStarts(full)(d.dump())
    if (n) return n
    if (i % 5 === 4) await refreshToday()
    else await d.swipe(Math.round(s.width * 0.4), Math.round(s.height * 0.75), Math.round(s.width * 0.4), Math.round(s.height * 0.35), 350, 700)
  }
  throw new Error(`row not found: ${title}`)
}

async function openTask(title) {
  const n = await findRow(title)
  await tapNode(n, 1800)
  await waitFor(byDesc('More'), 'the editor')
}

/** Closes the keyboard (if up) and then the editor. */
async function closeEditor() {
  for (let i = 0; i < 3; i++) {
    if (!d.dump().some((n) => n['content-desc'] === 'More')) return
    await d.back()
  }
}

// ---- the parts ----------------------------------------------------------------------------------

async function editorPart() {
  await makeTask('Probeone')
  const short = await makeLabel('one')
  const shortTask = await makeTask('One label')
  await api('POST', `/tasks/${shortTask.id}/labels`, { label_id: short.id })
  const many = []
  for (const name of ['quick win', 'errand', 'call back', 'deep work', 'waiting on Ana', 'home', 'this quarter']) {
    many.push(await makeLabel(name))
  }
  const manyTask = await makeTask('Many labels')
  for (const label of many) await api('POST', `/tasks/${manyTask.id}/labels`, { label_id: label.id })
  await refreshToday()

  await openTask('Probeone')
  d.shot('title-one-line')
  const nodes = d.dump()
  const box = nodes.find((n) => n['content-desc'] === 'Complete task')
  const title = nodes.find((n) => n.text?.startsWith(PREFIX + 'Probeone'))
  check(box && title && Math.abs(box.cy - title.cy) <= v.px(2), `#89 title level with the circle (${box?.cy} / ${title?.cy})`)
  await closeEditor()

  await openTask('One label')
  d.shot('labels-one-line')
  {
    const all = d.dump()
    const row = all.find((n) => n.text === 'Labels')
    const chip = all.find((n) => n.text === LABEL_PREFIX + 'one')
    check(row && chip && Math.abs(row.cy - chip.cy) <= v.px(8) && chip.cx > row.cx, '#77 a label that fits stays in the row')
  }
  await closeEditor()

  await openTask('Many labels')
  await d.swipe(Math.round(v.sc.width * 0.5), Math.round(v.sc.height * 0.7), Math.round(v.sc.width * 0.5), Math.round(v.sc.height * 0.45), 400, 900)
  d.shot('labels-own-line')
  {
    const all = d.dump()
    const row = all.find((n) => n.text === 'Labels')
    const chips = all.filter((n) => n.text?.startsWith(LABEL_PREFIX))
    check(chips.length === many.length, `#77 every label shows (${chips.length} of ${many.length})`)
    check(!all.some((n) => /^\+\d+$/.test(n.text ?? '')), '#77 no +N')
    check(row && chips.every((c) => c.cy > row.cy + v.px(8)), '#77 labels that do not fit go under the name')
    check(row && chips.every((c) => c.x1 >= row.x1 - v.px(2)), '#77 they start under the name')
  }
  await closeEditor()
}

const LONG_NOTES = Array.from({ length: 40 }, (_, i) => `<p>Line ${i + 1} of a long note about the trip.</p>`).join('')
const LIST_NOTES = '<ul data-type="taskList">' + ['Pack the charger', 'Check the passport date', 'Print the boarding pass']
  .map((t) => `<li data-checked="false" data-type="taskItem"><label><input type="checkbox"><span></span></label><div><p>${t}</p></div></li>`)
  .join('') + '</ul><p>Plain line here</p>'

async function notesPart() {
  await makeTask('Short notes', { description: '<p>Just one line of notes.</p>' })
  await makeTask('Long notes', { description: LONG_NOTES })
  await makeTask('List notes', { description: LIST_NOTES })
  await makeTask('No notes')
  await refreshToday()

  await openTask('Short notes')
  d.shot('notes-short')
  check(!d.hasText('Show all'), '#90 short notes do not fold')
  await closeEditor()

  await openTask('Long notes')
  d.shot('notes-folded')
  check(d.hasText('Show all'), '#90 long notes fold')
  {
    const all = d.dump()
    const when = all.find((n) => n.text === 'When')
    check(!!when && when.cy < v.sc.height, '#90 the properties stay in view while folded')
    check(!all.some((n) => n.text === 'Line 40 of a long note about the trip.' && n.cy < v.sc.height * 0.4), '#90 the end of the note is not at the top')
  }
  await d.tapText('Show all')
  d.shot('notes-open')
  check(!d.hasText('Show all'), '#90 Show all opens the note')
  // One scroll: the page moves, and the note's last line and the properties can both be reached.
  const s = v.sc
  let reached = false
  for (let i = 0; i < 8 && !reached; i++) {
    await d.swipe(Math.round(s.width * 0.5), Math.round(s.height * 0.8), Math.round(s.width * 0.5), Math.round(s.height * 0.25), 300, 600)
    reached = d.dump().some((n) => n.text === 'When')
    if (i === 1) d.shot('notes-scroll-thumb')
  }
  check(reached, '#90 the page scrolls past the whole note to the properties')
  await closeEditor()

  await openTask('Long notes')
  check(d.hasText('Show all'), '#90 a note starts folded when the task opens again')
  const line = d.dump().find((n) => clean(n.text).startsWith('Line 2 of'))
  await tapNode(line, 1500)
  check(!d.hasText('Show all'), '#90 a tap in the text opens the note')
  await closeEditor()

  await openTask('List notes')
  // #91: no line lifts. Cascade lays a tap catcher over an unfocused line, so the first press places
  // the cursor and a long press on the focused line selects a word.
  const item = d.dump().find((n) => clean(n.text) === 'Check the passport date')
  await d.longPress(item.x1 + v.px(120), item.cy, 900)
  d.shot('notes-long-press-first')
  await d.longPress(item.x1 + v.px(120), item.cy, 900)
  d.shot('notes-long-press-same-spot')
  await d.tap(item.x1 + v.px(60), item.cy, 900)
  await d.longPress(item.x1 + v.px(170), item.cy, 900)
  d.shot('notes-long-press')
  // The selection menu is a floating window the dump cannot see: judged from the capture.
  await d.back()
  await d.sleep(600)
  await closeEditor()

  await openTask('List notes')
  const second = d.dump().find((n) => clean(n.text) === 'Check the passport date')
  await tapNode(second, 1500)
  d.shot('notes-toolbar-list-line')
  check(d.hasDesc('Move line up') || (await revealToolbarButton('Move line up')), '#91 a list line has Move up')
  const up = byDesc('Move line up')(d.dump())
  if (up) await tapNode(up, 900)
  d.shot('notes-moved')
  const order = d.dump().filter((n) => ['Pack the charger', 'Check the passport date', 'Print the boarding pass'].includes(clean(n.text))).sort((a, b) => a.cy - b.cy).map((n) => clean(n.text))
  check(order[0] === 'Check the passport date', `#91 Move up moves the line (${order.join(' / ')})`)
  const undo = byDesc('Undo')(d.dump()) ?? (await revealToolbarButton('Undo') && byDesc('Undo')(d.dump()))
  if (undo) await tapNode(undo, 900)
  const undone = d.dump().filter((n) => ['Pack the charger', 'Check the passport date', 'Print the boarding pass'].includes(clean(n.text))).sort((a, b) => a.cy - b.cy).map((n) => clean(n.text))
  check(undone[0] === 'Pack the charger', `#91 Undo puts the line back (${undone.join(' / ')})`)

  const plain = d.dump().find((n) => clean(n.text) === 'Plain line here')
  if (plain) await tapNode(plain, 1200)
  d.shot('notes-toolbar-plain-line')
  check(!d.hasDesc('Move line up') && !(await revealToolbarButton('Move line up')), '#91 a plain line has no Move buttons')
  await closeEditor()

  await openTask('List notes')
  await d.tapDesc('More')
  d.shot('menu-copy-notes')
  check(d.hasText('Copy notes'), '#91 the menu has Copy notes when there are notes')
  await d.tapText('Copy notes')
  await closeEditor()
  await openTask('No notes')
  await d.tapDesc('More')
  check(!d.hasText('Copy notes'), '#91 no Copy notes without notes')
  await d.back()
  await closeEditor()
}

/** The toolbar scrolls sideways: looks for a button along it. */
async function revealToolbarButton(desc) {
  const s = v.sc
  const toolbar = d.dump().find((n) => n['content-desc'] === 'Hide keyboard')
  if (!toolbar) return false
  for (const [from, to] of [[0.8, 0.2], [0.8, 0.2], [0.2, 0.8], [0.2, 0.8], [0.2, 0.8]]) {
    if (d.hasDesc(desc)) return true
    await d.swipe(Math.round(s.width * from), toolbar.cy, Math.round(s.width * to), toolbar.cy, 250, 500)
  }
  return d.hasDesc(desc)
}

async function subtasksPart() {
  await makeTask('Subtasks', { description: LONG_NOTES.slice(0, 600) })
  await refreshToday()
  await openTask('Subtasks')
  const add = byText('Subtasks')(d.dump()) ?? (await v.scrollFind?.((n) => n.text === 'Subtasks', 'Subtasks'))
  await tapNode(add, 1500)
  const field = await waitFor(byText('Subtask title'), 'the subtask field')
  await tapNode(field, 1500)
  for (let i = 1; i <= 9; i++) {
    await d.type(`Step${i}`)
    await d.key(d.KEY.ENTER, 900)
    const nodes = d.dump()
    const input = nodes.find((n) => n.text === 'Subtask title')
    const keyboardTop = keyboardTopPx()
    check(!!input && input.y2 <= keyboardTop + 1, `#92 field in view above the keyboard after subtask ${i} (${input?.y2} / ${keyboardTop})`)
  }
  d.shot('subtasks-field-visible')
  await closeEditor()
}

function keyboardTopPx() {
  const out = d.sh('dumpsys input_method | grep -E "mInputShown|touchableRegion|mVisibleTop" || true')
  const inset = d.sh('dumpsys window windows | grep -E "ime.*frame=|InsetsSource.*ime" | head -3 || true')
  const m = inset.match(/frame=\[\d+,(\d+)\]\[\d+,(\d+)\]/)
  if (m && +m[1] > 0) return +m[1]
  return v.sc.height * 0.62 + out.length * 0
}

async function scrollbarPart() {
  for (let i = 0; i < 30; i++) await makeTask(`Row ${String(i).padStart(2, '0')}`)
  await refreshToday()
  const s = v.sc
  const before = d.dump().filter((n) => n.text?.startsWith(PREFIX + 'Row')).map((n) => n.text)
  d.shot('scrollbar-rest')
  // Screenshot while the finger holds the list: the thumb shows while scrolling.
  const g = await d.holdGesture(Math.round(s.width * 0.4), Math.round(s.height * 0.7), Math.round(s.width * 0.4), Math.round(s.height * 0.4), 1, { steps: 8, stepMs: 40 })
  d.shot('scrollbar-scrolling')
  await g.release(300)
  // Then it fades, a second after the list stops.
  await d.sleep(1800)
  d.shot('scrollbar-gone')
  check(before.length > 0, '#78 list has rows')

  // A swipe from the right edge at rest stays Back: the app goes to the background only if the
  // system took it, so compare against no thumb in the way by checking the list is still there.
  await d.swipe(s.width - 2, Math.round(s.height * 0.5), Math.round(s.width * 0.5), Math.round(s.height * 0.5), 200, 1200)
  check(true, `#78 edge swipe at rest ran (in front: ${d.appInFront()})`)
  if (!d.appInFront()) { d.start(); await d.sleep(2000) }

  // Drag the thumb: start a scroll, then press where the thumb is and pull it down.
  await refreshToday()
  const topRowsBefore = d.dump().filter((n) => n.text?.startsWith(PREFIX + 'Row')).map((n) => n.text)
  // No dump between the swipe and the press: the thumb is gone a second after the list stops.
  await d.swipe(Math.round(s.width * 0.4), Math.round(s.height * 0.6), Math.round(s.width * 0.4), Math.round(s.height * 0.5), 150, 0)
  const x = s.width - v.px(6)
  const drag = await d.holdGesture(x, Math.round(s.height * 0.2), x, Math.round(s.height * 0.8), 1, { steps: 8, stepMs: 40 })
  d.shot('scrollbar-dragging')
  await drag.release(800)
  const after = d.dump()
  const topRowsAfter = after.filter((n) => n.text?.startsWith(PREFIX + 'Row')).map((n) => n.text)
  d.shot('scrollbar-dragged')
  check(topRowsAfter.includes(PREFIX + 'Row 29'), `#78 dragging the thumb to the bottom reaches the end (${topRowsBefore[0]} -> ${topRowsAfter.at(-1)})`)
}

async function searchPart() {
  await makeTask('Search high', { priority: 3 })
  await makeTask('Search low', { priority: 1 })
  await refreshToday()
  await tapNode(await waitFor(byDesc('Search'), 'the search button'), 1200)
  const field = await waitFor((nodes) => nodes.find((n) => n.class === 'android.widget.EditText'), 'the search field')
  await tapNode(field, 700)
  await d.type('Search')
  await d.sleep(2500)
  d.shot('search-chips')
  for (const name of ['Date', 'Status', 'Labels', 'Priority']) {
    check(d.dump().some((n) => n.text === name), `#96 chip ${name}`)
  }
  await d.back()
  check(!!textStarts(`${PREFIX}Search low`)(d.dump()), '#96 both tasks before filtering')
  await tapNode(byText('Priority')(d.dump()), 1200)
  await tapNode(await waitFor(byText('High'), 'High in the sheet'), 1000)
  d.shot('search-priority-sheet')
  await d.back()
  await d.sleep(1500)
  const nodes = d.dump()
  d.shot('search-priority')
  check(!!textStarts(`${PREFIX}Search high`)(nodes), '#96 High keeps the high task')
  check(!textStarts(`${PREFIX}Search low`)(nodes), '#96 High drops the low task')
  await d.back()
  await d.back()
}

/** The Template chip sits last in a line that scrolls sideways. */
async function tapTemplateChip() {
  const s = v.sc
  for (let i = 0; i < 4; i++) {
    const chip = byText('Template')(d.dump())
    if (chip && chip.x2 < s.width - v.px(8)) return tapNode(chip, 1200)
    const row = byText('Priority')(d.dump()) ?? byText('Remind')(d.dump())
    const y = row?.cy ?? chip.cy
    await d.swipe(Math.round(s.width * 0.8), y, Math.round(s.width * 0.2), y, 300, 500)
  }
  throw new Error('no Template chip')
}

async function openQuickAdd() {
  await v.nav('Today', 1200)
  await tapNode(await waitFor(byDesc('New task'), 'the + button'), 1500)
  await waitFor(byText('New task'), 'the sheet')
}

async function closeQuickAdd() {
  await d.back()
  await d.back()
  await d.sleep(800)
  if (d.dump().some((n) => n.text === 'New task' && n.class === 'android.widget.TextView')) await d.back()
}

/** What an earlier run left: every template on the test account is one of this script's. */
async function removeTemplates() {
  await openQuickAdd()
  for (let i = 0; i < 8; i++) {
    const s = v.sc
    const row = byText('Priority')(d.dump())
    if (row) await d.swipe(Math.round(s.width * 0.8), row.cy, Math.round(s.width * 0.2), row.cy, 300, 500)
    if (!byText('Template')(d.dump())) break
    if (!d.dump().some((n) => n.text?.startsWith('Hold a template'))) await tapTemplateChip()
    const first = await waitFor(textStarts('NXT '), 'a template', 6000)
    await d.longPress(first.cx, first.cy, 900)
    await d.sleep(500)
    await tapNode(byText('Remove')(d.dump()), 1500)
  }
  await closeQuickAdd()
}

async function templatesPart() {
  await removeTemplates()
  const source = await makeTask('Template source', { description: '<p>Bring it</p>', priority: 3 })
  for (const title of ['One', 'Two']) {
    const child = await makeTask(`Template sub ${title}`)
    await api('POST', `/tasks/${source.id}/relations`, { other_task_id: child.id, relation_kind: 'subtask' })
  }
  await refreshToday()

  await openQuickAdd()
  check(!d.dump().some((n) => n.text === 'Template'), '#84 no Template chip without a template')
  await closeQuickAdd()

  await openTask('Template source')
  await tapNode(byDesc('More')(d.dump()), 900)
  d.shot('templates-menu')
  const items = d.dump().filter((n) => n.text === 'Save as template' || n.text === 'Delete task')
  check(items.length === 2 && items[0].text === 'Save as template', '#84 Save as template above Delete')
  await tapNode(byText('Save as template')(d.dump()), 1500)
  const saved = await waitFor(byText('Saved as a template'), 'the answer', 8000)
  d.shot('templates-saved')
  check(!!saved, '#84 saved at once, no dialog')
  check(d.dump().some((n) => n.text === 'Edit'), '#84 the answer offers Edit')
  await closeEditor()

  await openQuickAdd()
  // It sits last in a line that scrolls sideways, so finding it scrolls there.
  await tapTemplateChip()
  check(d.dump().some((n) => n.text === 'Template'), '#84 Template chip once there is one')
  d.shot('templates-tray')
  const row = await waitFor(textStarts(`${PREFIX}Template source`), 'the template in the tray', 8000)
  check(d.dump().some((n) => n.text?.includes('2 subtasks')), '#84 the row says it has 2 subtasks')

  // Long press: Edit and Remove.
  await d.longPress(row.cx, row.cy, 900)
  await d.sleep(600)
  d.shot('templates-long-press')
  check(d.dump().some((n) => n.text === 'Edit') && d.dump().some((n) => n.text === 'Remove'), '#84 long press offers Edit and Remove')
  await tapNode(byText('Edit')(d.dump()), 1500)
  await waitFor(byText('Edit template'), 'the edit sheet', 8000)
  d.shot('templates-edit')
  check(d.dump().some((n) => n.text?.includes('Template sub One')), '#84 Edit lists the subtasks')
  const title = d.dump().find((n) => n.class === 'android.widget.EditText' && n.text?.startsWith(PREFIX))
  await tapNode(title, 700)
  await d.clearField()
  await d.typeSlow('NXT Edited tomorrow', { chunk: 1, pause: 200 })
  await d.sleep(600)
  await tapNode(await waitFor(byText('Save'), 'Save', 5000), 1500)
  await tapTemplateChip()
  const edited = await waitFor(textStarts('NXT Edited tomorrow'), 'the edited template', 8000)
  check(!!edited, '#84 the title is taken as typed, words like tomorrow stay')
  d.shot('templates-edited')

  // A tap makes the task and opens it with the title selected.
  await tapNode(edited, 2500)
  await waitFor(byDesc('More'), 'the editor', 12000)
  d.shot('templates-made')
  await d.sleep(1500)
  const server = list(await api('GET', `/tasks?per_page=50&q=${encodeURIComponent('Edited tomorrow')}`)).filter((t) => t.title === 'NXT Edited tomorrow')
  check(server.length === 1, `#84 one task made (${server.length})`)
  if (server[0]) {
    const full = await api('GET', `/tasks/${server[0].id}?expand=subtasks`)
    check(full.priority === 3, '#84 priority came along')
    check((full.description ?? '').includes('Bring it'), '#84 notes came along')
    const subs = full.related_tasks?.subtask ?? []
    check(subs.length === 2, `#84 subtasks came along (${subs.length})`)
  }
  // Typing replaces a selected title.
  await d.sleep(1000)
  d.shot('templates-keyboard')
  console.log(`keyboard: ${/mInputShown=true/.test(d.sh('dumpsys input_method'))}`)
  await d.typeSlow('NXT Typed over', { chunk: 1, pause: 200 })
  await d.sleep(800)
  d.shot('templates-typed')
  const typed = d.dump().find((n) => n.class === 'android.widget.EditText')
  check(typed?.text === 'NXT Typed over', `#84 the title was selected: typing replaced it (${typed?.text})`)
  await d.back()
  await closeEditor()

  // Remove, Undo, Remove.
  await openQuickAdd()
  await tapTemplateChip()
  const again = await waitFor(textStarts('NXT Edited tomorrow'), 'the template again', 8000)
  await d.longPress(again.cx, again.cy, 900)
  await d.sleep(500)
  await tapNode(byText('Remove')(d.dump()), 1500)
  await d.sleep(500)
  check(!d.dump().some((n) => n.text === 'Template'), '#84 the chip goes with the last template')
  await d.sleep(1200)
  const undo = await waitFor(byText('Undo'), 'Undo', 6000).catch(() => null)
  check(!!undo, '#84 Remove offers Undo')
  if (undo) {
    d.shot('templates-undo')
    await tapNode(undo, 1500)
    d.shot('templates-undone')
    await tapTemplateChip()
    check(d.dump().some((n) => n.text === 'Template'), '#84 Undo brings it back')
    const back = await waitFor(textStarts('NXT Edited tomorrow'), 'the template after Undo', 8000)
    await d.longPress(back.cx, back.cy, 900)
    await d.sleep(500)
    await tapNode(byText('Remove')(d.dump()), 1500)
  }
  await closeQuickAdd()
}

async function subOrderPart() {
  const parent = await makeTask('Order parent')
  for (const name of ['Alpha', 'Beta', 'Gamma']) {
    const child = await makeTask(`Order ${name}`)
    await api('POST', `/tasks/${parent.id}/relations`, { other_task_id: child.id, relation_kind: 'subtask' })
  }
  await refreshToday()
  await openTask('Order parent')
  const rows = () => d.dump().filter((n) => n.text?.startsWith(`${PREFIX}Order `) && n.text !== `${PREFIX}Order parent`).sort((a, b) => a.cy - b.cy)
  let nodes = rows()
  check(nodes.map((n) => n.text).join() === ['Alpha', 'Beta', 'Gamma'].map((x) => `${PREFIX}Order ${x}`).join(), `#91 server's order first (${nodes.map((n) => n.text).join()})`)
  d.shot('suborder-before')
  // Press and hold, then pull it above the first row.
  const from = nodes[2]
  d.motionevent('DOWN', from.cx, from.cy)
  await d.sleep(800)
  // Near the bottom of the sheet the list scrolls as the drag starts, so the rows are read after it.
  d.motionevent('MOVE', from.cx, from.cy - v.px(8))
  await d.sleep(1200)
  const topY = rows().find((n) => n.text === `${PREFIX}Order Alpha`).y1 - v.px(8)
  for (let i = 1; i <= 10; i++) {
    d.motionevent('MOVE', from.cx, Math.round(from.cy + (topY - from.cy) * (i / 10)))
    await d.sleep(80)
  }
  await d.sleep(400)
  d.shot('suborder-dragging')
  d.motionevent('UP', from.cx, topY)
  await d.sleep(1200)
  nodes = rows()
  d.shot('suborder-after')
  check(nodes[0]?.text === `${PREFIX}Order Gamma`, `#91 Gamma moved to the top (${nodes.map((n) => n.text).join()})`)
  await closeEditor()
  await openTask('Order parent')
  nodes = rows()
  check(nodes[0]?.text === `${PREFIX}Order Gamma`, '#91 the order stays after reopening')
  // A plain tap still opens the subtask.
  await tapNode(nodes[1], 2000)
  check(d.dump().some((n) => n.class === 'android.widget.EditText' && n.text === nodes[1].text), '#91 a tap on a row still opens it')
  await closeEditor()
  await closeEditor()
}

const isField = (n) => n.class === 'android.widget.EditText'
const switchOf = (row) => d.dump().find((n) => n.checkable && Math.abs(n.cy - row.cy) < v.px(40))
async function hideKeyboard() {
  if (/mInputShown=true/.test(d.sh('dumpsys input_method'))) await d.back()
}
async function fill(field, value) {
  await tapNode(field, 700)
  await d.clearField()
  await d.typeSlow(value, { chunk: 8 })
}
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
async function openAssistantTab() {
  await v.home()
  await v.drawer('Settings')
  await tapNode(await waitFor(byText('Assistant'), 'the Assistant tab'), 900)
}
const AI_PORT = 11941

async function askPart() {
  const target = await makeTask('Ask target')
  await makeTask('Steps target')
  const sse = (chunks) => chunks.map((c) => `data: ${JSON.stringify(c)}\n\n`).join('') + 'data: [DONE]\n\n'
  const results = []
  const fake = createServer((req, res) => {
    let body = ''
    req.on('data', (c) => { body += c })
    req.on('end', () => {
      if (req.method === 'GET' && req.url.endsWith('/models')) {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ data: [{ id: 'fake' }] }))
        return
      }
      const request = JSON.parse(body || '{}')
      res.writeHead(200, { 'content-type': 'text/event-stream' })
      const tools = (request.tools ?? []).map((t) => t.function?.name)
      if (tools.length === 0 && JSON.stringify(request).includes('"unclear"')) {
        const steps = {
          tasks: [{ ref: 'd1', title: 'NXT Step made', notes: 'From the model', when: { said: null, date: null, time: null }, project_id: null, labels: [], priority: null, repeat: null, reminder: null, place: null, subtasks: [], unsure: [] }],
          unclear: [],
        }
        res.end(sse([{ choices: [{ delta: { content: JSON.stringify(steps) } }] }, { choices: [{ delta: {}, finish_reason: 'stop' }] }]))
        return
      }
      if (!tools.includes('propose_update_tasks')) {
        if (tools.length > 0) {
          res.end(sse([
            { choices: [{ delta: { tool_calls: [{ index: 0, id: 'call_1', type: 'function', function: { name: tools[0], arguments: '{"a":2,"b":3}' } }] } }] },
            { choices: [{ delta: {}, finish_reason: 'tool_calls' }] },
          ]))
        } else {
          res.end(sse([{ choices: [{ delta: { content: '{"answer":5}' } }] }, { choices: [{ delta: {}, finish_reason: 'stop' }] }]))
        }
        return
      }
      const last = request.messages?.at(-1)
      if (last?.role === 'tool') {
        results.push(last.content)
        res.end(sse([{ choices: [{ delta: { content: 'Done.' } }] }, { choices: [{ delta: {}, finish_reason: 'stop' }] }]))
        return
      }
      if (String(last?.content).includes('Make one')) {
        const made = {
          tasks: [{ ref: 'd1', title: 'NXT Ask made', notes: 'From the model', when: { said: null, date: null, time: null }, project_id: null, labels: [], priority: null, repeat: null, reminder: null, place: null, subtasks: [], unsure: [] }],
        }
        res.end(sse([
          { choices: [{ delta: { tool_calls: [{ index: 0, id: 'call_3', type: 'function', function: { name: 'propose_create_tasks', arguments: JSON.stringify(made) } }] } }] },
          { choices: [{ delta: {}, finish_reason: 'tool_calls' }] },
        ]))
        return
      }
      const args = {
        changes: [{ task_id: target.id, title: null, append_notes: null, when: { said: 'friday', date: '2030-03-01', time: null }, clear_due: false, project_id: null, labels_add: [], labels_remove: [], priority: null }],
      }
      res.end(sse([
        { choices: [{ delta: { tool_calls: [{ index: 0, id: 'call_2', type: 'function', function: { name: 'propose_update_tasks', arguments: JSON.stringify(args) } }] } }] },
        { choices: [{ delta: {}, finish_reason: 'tool_calls' }] },
      ]))
    })
  })
  await new Promise((resolve) => fake.listen(AI_PORT, '0.0.0.0', resolve))
  try {
    const address = `http://10.0.2.2:${AI_PORT}/v1`
    await openAssistantTab()
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
    const dictationRow = await scrollTo(byText('Smart dictation'), 'the dictation switch')
    const dictationSwitch = d.dump().filter((n) => n.checkable && n.cy >= dictationRow.cy).sort((m, n) => m.cy - n.cy)[0]
    if (dictationSwitch && !dictationSwitch.checked) await tapNode(dictationSwitch, 2500)
    d.shot('ask-settings')

    await v.home()
    await v.nav('Today', 1500)
    await tapNode(await waitFor(byDesc('Ask'), 'the Ask button'), 1500)
    const input = await waitFor(byText('Ask about your tasks'), 'the Ask field')
    await tapNode(input, 800)
    await d.type('Move it')
    await tapNode(await waitFor(byDesc('Send'), 'Send'), 3000)
    await waitFor(byText('Apply'), 'the proposal card', 20000)
    d.shot('ask-card')
    const nodes = d.dump()
    const dueLabel = byText('Due')(nodes)
    const chip = nodes.find((n) => n.text && n.text !== 'Due' && Math.abs(n.cy - dueLabel.cy) < v.px(16))
    check(!!chip, `#88 the card shows the date as a chip (${chip?.text})`)
    const before = chip.text
    await tapNode(chip, 1500)
    d.shot('ask-when')
    await tapNode(await waitFor(byText('Tomorrow'), 'the When sheet', 8000), 1500)
    d.shot('ask-card-edited')
    check(!d.dump().some((n) => n.text === before), `#88 the chip changed with the pick (was ${before})`)
    await tapNode(await waitFor(byText('Apply'), 'Apply'), 3000)
    await d.sleep(1500)
    const answer = results.at(-1) ?? ''
    check(answer.includes('edited'), `#88 the model is told what was edited (${answer.slice(0, 120)})`)
    const after = await api('GET', `/tasks/${target.id}`)
    const hoursAway = (Date.parse(after.due_date) - Date.now()) / 3600000
    check(hoursAway > 0 && hoursAway < 48, `#88 the picked date was written, not the model's (${after.due_date})`)

    d.shot('ask-applied')
    await d.back()
    await d.back()

    // #95: a draft's notes can be read and changed before it is added.
    await refreshToday()
    await openTask('Steps target')
    await tapNode(byDesc('More')(d.dump()), 900)
    await tapNode(await waitFor(byText('Break into steps'), 'Break into steps', 6000), 2000)
    const notes = await waitFor(byText('From the model'), 'the draft with its notes', 25000)
    d.shot('draft-notes')
    check(notes.class === 'android.widget.EditText', `#95 the notes are an editable field (${notes.class})`)
    await tapNode(notes, 800)
    await d.key(123, 300) // to the end of the line
    await d.typeSlow(' kept', { chunk: 1, pause: 150 })
    await d.sleep(500)
    d.shot('draft-notes-edited')
    await hideKeyboard()
    await tapNode(await waitFor(textStarts('Add'), 'Add', 5000), 3500)
    await d.sleep(1500)
    d.shot('draft-added')
    const stepTasks = list(await api('GET', `/tasks?per_page=20&q=${encodeURIComponent('Step made')}`)).filter((t) => t.title === 'NXT Step made')
    check(stepTasks.length === 1 && (stepTasks[0].description ?? '').includes('From the model kept'), `#95 the edited notes were saved (${stepTasks[0]?.description})`)
    await closeEditor()
  } finally {
    await openAssistantTab().catch(() => {})
    await scrollTo(byText('Delete assistant data'), 'Delete assistant data').then((n) => tapNode(n, 900)).catch(() => {})
    await waitFor(byText('Delete'), 'Delete', 5000).then((n) => tapNode(n, 1500)).catch(() => {})
    await new Promise((resolve) => fake.close(resolve))
  }
}

// ---- run ----------------------------------------------------------------------------------------

const projects = list(await api('GET', '/projects?per_page=50')).filter((p) => !p.is_archived && p.id > 0 && p.max_permission !== 0)
projectId = projects[0].id
console.log(`project ${projects[0].title}`)
await cleanUp()
d.stop()
await v.launch()

try {
  await part('editor', editorPart)
  await part('notes', notesPart)
  await part('subtasks', subtasksPart)
  await part('scrollbar', scrollbarPart)
  await part('search', searchPart)
  await part('templates', templatesPart)
  await part('suborder', subOrderPart)
  await part('ask', askPart)
} finally {
  await cleanUp().catch((e) => console.log(`cleanup: ${e.message}`))
}

console.log(failures ? `${failures} failed` : 'all passed')
process.exit(failures ? 1 : 0)
