// Collaboration checks on emulator-5554 (design handoff M1 to M5): the shared "Trip to Lisbon"
// project of the desktop seed (scripts/ui-verify/seed.mjs in the vicu repo) shows assignee avatars
// and comment counts on its rows, the editor has Assign and Comments chips, the Comments screen
// shows the thread and posts a comment, and a private project shows none of it.
//
//   node collab.mjs [--out DIR]
//
// Writes captures to out/collab/ (or DIR) and prints one line per check. Exit code 1 when a check
// failed. Run `node login.mjs` first; the app must be signed in to the seeded test user.

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import * as d from './droid.mjs'
import { DEFAULT_TOKEN_PATH } from './login.mjs'
import * as v from './nav.mjs'
import { audit, printFindings } from './a11y.mjs'

const outArg = process.argv.indexOf('--out')
d.setOutDir(outArg > 0 ? process.argv[outArg + 1] : 'collab')

let failures = 0
function check(ok, what) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${what}`)
  if (!ok) failures++
}

/** The accessibility rules of a11y.mjs on the screen showing now; a finding on a collaboration control fails. */
function a11y(name) {
  const findings = audit(d.dump())
  printFindings(`collab-${name}`, findings)
  const ours = findings.filter((f) => /comment|assign|notif|send|visible|maya|sam|uiverify|can view|share/i.test(f.label))
  check(ours.length === 0, `${name}: no accessibility finding on a collaboration control${ours.length ? ` (${ours.map((f) => f.label).join(', ')})` : ''}`)
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

/** Opens a project from the drawer by its name, scrolling the drawer when needed. */
async function openProject(name) {
  await v.openDrawer()
  const { n: node } = await v.scrollFind((n) => n.text === name, `project ${name}`)
  await d.tap(node.cx, node.cy, 2500)
}

await v.launch(5000)
await v.denyPermissionPrompt()
await v.home()

const SEED = (() => {
  try {
    return JSON.parse(readFileSync(join(DEFAULT_TOKEN_PATH, 'seed-ids.json'), 'utf8'))
  } catch {
    return {}
  }
})()

const LISBON = 'Trip to Lisbon'
const FLIGHTS = 'Book flights to Lisbon'

await step('rows', async () => {
  await openProject(LISBON)
  // Pull to refresh so the members and signals are read even if the app was fresh.
  const s = v.sc
  await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.3), Math.round(s.width / 2), Math.round(s.height * 0.75), 400, 3500)
  d.shot('lisbon-rows')
  a11y('rows')
  const nodes = d.dump()
  check(nodes.some((n) => /^Assigned to /.test(n['content-desc'] ?? '')), 'a row of the shared project has assignee avatars ("Assigned to ...")')
  check(nodes.some((n) => /^\d+ comments?(, new)?$/.test(n['content-desc'] ?? '')), 'a row of the shared project has a comment count')
  const count = nodes.find((n) => /^\d+ comments?(, new)?$/.test(n['content-desc'] ?? ''))
  if (count) check(count.h >= v.px(47) && count.w >= v.px(47), `the comment count is a 48 dp target (${v.dp(count.w).toFixed(0)} x ${v.dp(count.h).toFixed(0)} dp)`)
})

await step('comments-from-row', async () => {
  const count = d.findNode((n) => /^\d+ comments?(, new)?$/.test(n['content-desc'] ?? ''), 'comment count')
  await d.tap(count.cx, count.cy, 3500)
  d.shot('comments-screen')
  a11y('comments')
  const nodes = d.dump()
  check(nodes.some((n) => n.text === 'Comments'), 'the count opens the Comments screen')
  check(nodes.some((n) => /^Visible to you/.test(n.text ?? '')), 'the thread starts with "Visible to you ..."')
  // The harness signs in with an API token, and Vikunja does not let tokens follow tasks (cross-app semantics 9.7).
  check(!nodes.some((n) => n['content-desc'] === 'Notify me about new comments' || n['content-desc'] === 'Notifications on for this task'), 'no Notify me with an API token')
  check(nodes.some((n) => n.text === 'Comment. Type @ to mention'), 'the composer shows its placeholder')
})

await step('post-comment', async () => {
  const field = d.byText('Comment. Type @ to mention')
  await d.tap(field.cx, field.cy, 800)
  await d.typeSlow('Checked the times @ma', { chunk: 4, pause: 120 })
  await d.sleep(800)
  d.shot('mention-chips')
  const chips = d.dump()
  // The chip sits just above the composer: the lowest 'Maya' on screen (the thread has her name too).
  const maya = chips.filter((n) => n.text === 'Maya').sort((a, b) => b.y1 - a.y1)[0]
  check(!!maya, 'typing "@ma" suggests Maya')
  if (maya) await d.tap(maya.cx, maya.cy, 800)
  await d.tapDesc('Send comment', { wait: 3000 })
  d.shot('posted')
  const after = d.dump()
  check(after.some((n) => (n.text ?? '').includes('Checked the times @Maya')), 'the new comment shows with the mention as @Maya')
})

await step('editor-chips', async () => {
  // Back closes the keyboard first, then the screen.
  for (let i = 0; i < 3 && d.hasText('Comments'); i++) await d.back()
  await d.tapText(FLIGHTS, { wait: 3000 })
  d.shot('editor')
  a11y('editor')
  const nodes = d.dump()
  const descs = nodes.map((n) => n['content-desc'] ?? '')
  const project = descs.findIndex((t) => t.startsWith('Project:'))
  const assign = descs.findIndex((t) => t === 'Assign' || t.startsWith('Assigned:'))
  const attach = descs.findIndex((t) => t.startsWith('Add attachment'))
  const comments = descs.findIndex((t) => t.startsWith('Comments'))
  const more = descs.findIndex((t) => t === 'More')
  check(assign > project && attach > assign && comments > attach && more > comments, `chips in order Project, Assign, Attachments, Comments, More (${[project, assign, attach, comments, more].join(',')})`)
  check(descs.some((t) => t.startsWith('Assigned: You')), 'the Assign chip names the assignees ("Assigned: You, ...")')
})

await step('assign-sheet', async () => {
  await d.tapDesc('Assigned: ', { exact: false, wait: 2000 })
  d.shot('assign-sheet')
  a11y('assign-sheet')
  const nodes = d.dump()
  check(nodes.some((n) => n.text === 'Assign'), 'the Assign sheet opens')
  check(nodes.some((n) => (n.text ?? '').startsWith('People Trip to Lisbon is shared with.')), 'the sheet has its footer')
  check(nodes.some((n) => n.text === 'Can view only'), 'a read-only member says "Can view only"')
  await d.back()
  await d.back()
})

await step('sharing', async () => {
  await v.home()
  await openProject(LISBON)
  await d.tapDesc('Project options', { wait: 900 })
  await d.tapText('Edit project', { wait: 2500 })
  // The dialog scrolls: bring the share list into view.
  const { n: header } = await v.scrollFind((n) => n.text === 'Shared with', 'Shared with', 0.5)
  await d.swipe(Math.round(v.sc.width / 2), header.cy, Math.round(v.sc.width / 2), Math.round(v.sc.height * 0.25), 400, 1500)
  d.shot('sharing')
  a11y('sharing')
  const nodes = d.dump()
  check(nodes.some((n) => n.text === 'Owner'), 'the owner is listed as Owner')
  check(nodes.some((n) => n['content-desc'] === 'Remove Maya'), 'a shared person has a named remove button')
  check(nodes.some((n) => (n['content-desc'] ?? '').startsWith('Permission of Sam: Can view')), 'a read-only share shows "Can view"')
  const field = nodes.find((n) => n.text === 'Username')
  if (field) {
    await d.tap(field.cx, field.cy, 600)
    // Sam's username from the desktop seed's ids (no credentials there); it matches exactly only.
    await d.typeSlow(SEED.people?.sam?.username ?? 'uisam', { chunk: 3 })
    await d.sleep(2000)
    d.shot('sharing-lookup')
    check(d.hasText('Already has access.'), 'looking up someone who has access says "Already has access."')
  } else {
    check(false, 'the username field is there')
  }
  for (let i = 0; i < 3 && d.hasText('Shared with'); i++) await d.back()
})

await step('private-project', async () => {
  await v.home()
  await openProject('Home renovation')
  d.shot('private-rows')
  const nodes = d.dump()
  check(!nodes.some((n) => /^Assigned to /.test(n['content-desc'] ?? '') || /^\d+ comments?/.test(n['content-desc'] ?? '')), 'a private project shows no avatars or counts')
})

console.log(failures ? `${failures} check(s) failed` : 'all checks passed')
process.exit(failures ? 1 : 0)
