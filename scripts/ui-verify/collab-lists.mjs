// Custom lists that gather shared work, on emulator-5554 (docs/cross-app-semantics-v1.md section
// 3.3): the list editor shows the "Shared projects" section while a project is shared, and a list
// with Assigned "To me" shows the flights task of the desktop seed (you and Maya) and none of the
// other Lisbon tasks. "To someone else" then shows the dinner task (Maya only).
//
//   node collab-lists.mjs [--out DIR]
//
// Writes captures to out/collab-lists/ (or DIR) and prints one line per check. Exit code 1 when a
// check failed. Run `node login.mjs` first; the app must be signed in to the seeded test user. The
// list is deleted at the end.

import * as d from './droid.mjs'
import * as v from './nav.mjs'
import { audit, printFindings } from './a11y.mjs'

const outArg = process.argv.indexOf('--out')
d.setOutDir(outArg > 0 ? process.argv[outArg + 1] : 'collab-lists')

const LIST = 'Shared work'
const FLIGHTS = 'Book flights to Lisbon'
const DINNER = 'Reserve dinner at a tasca in Alfama'
const OTHER_LISBON = ['Research a day trip to Sintra', 'Make a packing list']

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

/** Scrolls the open dialog until a node matches. */
async function findInDialog(pred, label) {
  for (let i = 0; i < 8; i++) {
    const node = d.dump().find(pred)
    if (node) return node
    const s = v.sc
    await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.62), Math.round(s.width / 2), Math.round(s.height * 0.38), 350, 700)
  }
  throw new Error(`not found in the dialog: ${label}`)
}

/** Picks [option] in the dropdown under the label [field]. */
async function pick(field, option) {
  const label = await findInDialog((n) => n.text === field, field)
  const box = d.dump().filter((n) => n.class === 'android.widget.EditText' && n.cy > label.cy).sort((a, b) => a.cy - b.cy)[0]
  if (!box) throw new Error(`no dropdown under ${field}`)
  await d.tap(box.cx, box.cy, 900)
  await d.tapText(option, { wait: 900 })
}

/** The task titles the list screen shows, after a pull to refresh. */
async function shownTitles() {
  const s = v.sc
  await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.3), Math.round(s.width / 2), Math.round(s.height * 0.75), 400, 3500)
  const texts = d.dump().map((n) => n.text).filter(Boolean)
  return [FLIGHTS, DINNER, ...OTHER_LISBON].filter((t) => texts.includes(t))
}

await v.launch(5000)
await v.denyPermissionPrompt()
await v.home()

// Members are read on a refresh: open the shared project once so the app knows it is shared.
await step('members', async () => {
  await v.openDrawer()
  const { n } = await v.scrollFind((x) => x.text === 'Trip to Lisbon', 'Trip to Lisbon')
  await d.tap(n.cx, n.cy, 2500)
  const s = v.sc
  await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.3), Math.round(s.width / 2), Math.round(s.height * 0.75), 400, 4000)
})

await step('create', async () => {
  await v.openDrawer()
  const { n } = await v.scrollFind((x) => x.text === 'New List', 'New List')
  await d.tap(n.cx, n.cy, 1500)
  const name = d.dump().find((x) => x.class === 'android.widget.EditText')
  await d.tap(name.cx, name.cy, 600)
  await d.type(LIST)
  await d.back() // close the keyboard
  check(!!(await findInDialog((x) => x.text === 'Shared projects', 'Shared projects')), 'the editor shows the Shared projects section while a project is shared')
  await pick('Assigned', 'To me')
  d.shot('editor')
  const findings = audit(d.dump())
  printFindings('collab-lists-editor', findings)
  const ours = findings.filter((f) => /assigned|comments|changed|shared|any task|to me/i.test(f.label))
  check(ours.length === 0, `no accessibility finding on the new editor controls${ours.length ? ` (${ours.map((f) => f.label).join(', ')})` : ''}`)
  await d.tapText('Create', { wait: 1500 })
})

await step('assigned to me', async () => {
  await v.openDrawer()
  const { n } = await v.scrollFind((x) => x.text === LIST, LIST)
  await d.tap(n.cx, n.cy, 3000)
  const shown = await shownTitles()
  d.shot('assigned-to-me')
  check(JSON.stringify(shown) === JSON.stringify([FLIGHTS]), `Assigned to me shows the flights task only (${shown.join(' | ') || 'nothing'})`)
})

await step('to someone else', async () => {
  await d.tapDesc('Edit list', { wait: 1500 })
  await pick('Assigned', 'To someone else')
  await d.tapText('Save', { wait: 2000 })
  const shown = await shownTitles()
  d.shot('to-someone-else')
  check(JSON.stringify(shown) === JSON.stringify([DINNER]), `To someone else shows the dinner task only (${shown.join(' | ') || 'nothing'})`)
})

await step('delete', async () => {
  await d.tapDesc('Delete list', { wait: 1200 })
  await d.tapText('Delete', { wait: 2000 })
})

console.log(failures ? `${failures} check(s) failed` : 'all checks passed')
process.exit(failures ? 1 : 0)
