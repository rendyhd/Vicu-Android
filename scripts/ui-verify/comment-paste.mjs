// Pasting a screenshot into a comment on emulator-5554, on a throwaway task in the shared "Trip to
// Lisbon" project of the desktop seed: the Paste image chip, the text field's own Paste, and the
// composer's typing, mentions and draft, which moved to a TextFieldState for this.
//
//   ANDROID_SERIAL=emulator-5554 ./gradlew :app:installDebug :app:installDebugAndroidTest
//   node login.mjs
//   node comment-paste.mjs [--out DIR]
//
// adb cannot copy an image, so ClipboardSeed (app/src/androidTest) puts one on the clipboard; that
// also ends the app, which is why it runs before the app is opened. Exit code 1 when a check failed.
//
// Start the emulator with -no-window. The windowed one mirrors its clipboard to the computer's, both
// ways: an image has no text, so the seed empties the computer's clipboard and the empty text comes
// back over the image. This script refuses to run on a windowed emulator.

import { execFileSync } from 'node:child_process'
import * as d from './droid.mjs'
import { resolveToken } from './login.mjs'
import * as v from './nav.mjs'
import { audit, printFindings } from './a11y.mjs'

const outArg = process.argv.indexOf('--out')
d.setOutDir(outArg > 0 ? process.argv[outArg + 1] : 'comment-paste')

const API = 'http://localhost:3456/api/v2'
const TOKEN = resolveToken()
if (!TOKEN) throw new Error('no API token: run the seed script in the vicu repo first')

const LISBON = 'Trip to Lisbon'
const TITLE = 'Comment paste check'
const PASTE_CHIP = 'Paste image'
const PLACEHOLDER = 'Comment. Type @ to mention'
// The seed's name, or the digits Gboard names the copy it hands over through its own provider.
const SHOT = /^(Screenshot_vicu_check_)?\d+\.png$/

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

async function api(method, path, body) {
  const response = await fetch(`${API}/${path}`, {
    method,
    headers: { Authorization: `Bearer ${TOKEN}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!response.ok) throw new Error(`${method} ${path}: ${response.status}`)
  return response.status === 204 ? null : response.json()
}

const items = (answer) => (Array.isArray(answer) ? answer : answer?.items ?? [])
const onTask = async (id) => items(await api('GET', `tasks/${id}/attachments`))
const comments = async (id) => items(await api('GET', `tasks/${id}/comments`))

function refuseWindowedEmulator() {
  const processes = process.platform === 'win32'
    ? execFileSync('powershell', ['-NoProfile', '-Command', "(Get-CimInstance Win32_Process -Filter \"Name like 'qemu-system%'\").CommandLine"])
    : execFileSync('ps', ['-axo', 'command'])
  const emulator = processes.toString().split(/\r?\n/).find((line) => /qemu-system/.test(line) && new RegExp(`-port ${d.SERIAL.split('-')[1]}\\b`).test(line))
  if (!emulator?.includes('-no-window')) {
    throw new Error(`${d.SERIAL} must run with -no-window: a windowed emulator would empty this computer's clipboard`)
  }
}

function seedClipboard() {
  const answer = d.sh(`am instrument -w -e class com.rendyhd.vicu.ClipboardSeed -e seedClipboard 1 ${d.APP_ID}.test/androidx.test.runner.AndroidJUnitRunner`)
  if (!answer.includes('OK (1 test)')) throw new Error(`the clipboard seed did not run: ${answer.slice(0, 300)}`)
}

async function waitUntil(pred, what, ms = 15000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (await pred(d.dump())) return true
    await d.sleep(500)
  }
  throw new Error(`timed out waiting for ${what}`)
}

const composerField = (nodes = d.dump()) => nodes.find((n) => /EditText/.test(n.class ?? ''))

/** Opens the keyboard. The composer moves up with it, so read its place again after this. */
async function focusComposer() {
  const field = composerField()
  await d.tap(field.cx, field.cy, 1500)
}

const stagedShot = (nodes) => nodes.find((n) => SHOT.test(n.text ?? ''))
const removeShot = (nodes) => nodes.find((n) => /^Remove (Screenshot_vicu_check_)?\d+\.png$/.test(n['content-desc'] ?? ''))

async function openThread() {
  await v.openDrawer()
  const { n: project } = await v.scrollFind((n) => n.text === LISBON, `project ${LISBON}`)
  await d.tap(project.cx, project.cy, 2500)
  const s = v.sc
  await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.3), Math.round(s.width / 2), Math.round(s.height * 0.75), 400, 3500)
  await d.tapText(TITLE, { wait: 2500 })
  const chip = d.dump().find((n) => n.text === 'Comments' || (n['content-desc'] ?? '').startsWith('Comments'))
  if (!chip) throw new Error('no Comments chip in the editor')
  await d.tap(chip.cx, chip.cy, 3000)
}

refuseWindowedEmulator()

const projects = items(await api('GET', 'projects'))
const lisbon = projects.find((p) => p.title === LISBON)
if (!lisbon) throw new Error(`no project ${LISBON}: run the seed script in the vicu repo first`)
const task = await api('POST', `projects/${lisbon.id}/tasks`, { title: TITLE })
console.log(`task ${task.id}`)

try {
  seedClipboard()
  await v.launch(5000)
  await v.denyPermissionPrompt()
  await v.home()
  await step('open', openThread)

  // ---- The chip ---------------------------------------------------------------------------------
  await step('chip', async () => {
    await waitUntil((nodes) => nodes.some((n) => n.text === PASTE_CHIP), 'the Paste image chip', 6000)
    d.shot('chip-offered')
    check(true, 'with an image on the clipboard the composer offers Paste image')
    const findings = audit(d.dump())
    printFindings('comment-paste-chip', findings)
    check(findings.length === 0, 'the composer with the chip has no accessibility findings')
    await d.tapText(PASTE_CHIP, { wait: 700 })
    await waitUntil((nodes) => !!stagedShot(nodes), 'the staged screenshot')
    check(!d.hasText(PASTE_CHIP), 'the chip goes away once it is pasted')
    await waitUntil(async () => (await onTask(task.id)).some((a) => SHOT.test(a.file?.name ?? '')), 'the file on the task')
    check(true, 'the screenshot is on the task at once')
    d.shot('chip-pasted')
  })

  let shotName = ''
  await step('send', async () => {
    const attachment = (await onTask(task.id)).find((a) => SHOT.test(a.file?.name ?? ''))
    shotName = attachment.file.name
    check(attachment.file.mime === 'image/png', `the server has it as a PNG (${attachment.file.mime})`)
    await d.sleep(800)
    await d.tapDesc('Send comment', { wait: 3000 })
    const html = await waitUntil(async () => (await comments(task.id)).length === 1, 'the comment on the server').then(async () => (await comments(task.id))[0].comment)
    console.log(`     ${html}`)
    check(html.endsWith(`/api/v1/tasks/${task.id}/attachments/${attachment.id}" alt="${shotName}">`) && html.startsWith('<img'), 'the comment is the image, as an img with the v1 address')
    await waitUntil((nodes) => nodes.some((n) => n['content-desc'] === shotName), 'the preview in the thread')
    check(true, 'the thread shows the preview')
    check(!d.hasText(PASTE_CHIP), 'an image already pasted is not offered again')
    d.shot('chip-sent')
  })

  // ---- The text field's own Paste ---------------------------------------------------------------
  await step('long-press', async () => {
    d.stop()
    seedClipboard()
    await v.launch(5000)
    await v.home()
    await openThread()
    await waitUntil((nodes) => nodes.some((n) => n.text === PASTE_CHIP), 'the chip for a new image', 6000)
    check(true, 'a new image on the clipboard is offered again')
    await focusComposer()
    const focused = composerField()
    await d.longPress(focused.cx, focused.cy, 900)
    await d.sleep(500)
    d.shot('long-press-menu')
    // The text menu is an overlay the dump does not list. It opens above the field, Paste first; its
    // capture shows it. A press there that misses leaves nothing staged, which fails below.
    await d.tap(v.px(50), focused.cy - v.px(41), 900)
    await waitUntil((nodes) => !!stagedShot(nodes), 'the staged screenshot after Paste')
    check(true, 'the field\'s Paste adds the image as a file, not as text')
    check(!d.hasText(PASTE_CHIP), 'and the chip does not offer the same image again')
    d.shot('long-press-pasted')
    await waitUntil(async () => (await onTask(task.id)).filter((a) => SHOT.test(a.file?.name ?? '')).length === 2, 'the second file on the task')
    await d.sleep(800)
    const remove = removeShot(d.dump())
    await d.tap(remove.cx, remove.cy, 1000)
    await waitUntil(async () => (await onTask(task.id)).filter((a) => SHOT.test(a.file?.name ?? '')).length === 1, 'the file off the task')
    check(true, 'removing the chip takes the file off the task')
  })

  // ---- The keyboard's clipboard --------------------------------------------------------------------
  await step('keyboard', async () => {
    d.stop()
    seedClipboard()
    await v.launch(5000)
    await v.home()
    await openThread()
    await focusComposer()
    d.shot('keyboard-tile')
    // Gboard's window is not in the dump: its Image tile sits in the middle of the suggestion strip.
    const before = (await onTask(task.id)).length
    await d.tap(Math.round(v.sc.width * 0.5), Math.round(v.sc.height * 0.6595), 1500)
    await waitUntil((nodes) => !!stagedShot(nodes), 'the staged screenshot after the keyboard tile', 8000)
    check(true, 'the keyboard\'s clipboard tile adds the image as a file')
    await waitUntil(async () => (await onTask(task.id)).length === before + 1, 'the file on the task')
    const tiled = (await onTask(task.id)).find((a) => a.file?.name === stagedShot(d.dump())?.text)
    check(tiled?.file?.mime === 'image/png', `what the keyboard hands over reaches the server as a PNG (${tiled?.file?.mime})`)
    check(!d.hasText(PASTE_CHIP), 'and the chip does not offer the same image again')
    d.shot('keyboard-pasted')
    await d.sleep(800)
    const remove = removeShot(d.dump())
    await d.tap(remove.cx, remove.cy, 1000)
    await waitUntil(async () => (await onTask(task.id)).length === before, 'the file off the task')
  })

  // ---- Typing, mentions and the draft on the new field --------------------------------------------
  await step('typing', async () => {
    await d.key(d.KEY.BACK, 900)
    await focusComposer()
    await d.typeSlow('@ma', { chunk: 1, pause: 200 })
    await waitUntil((nodes) => nodes.some((n) => n.text === 'Maya'), 'Maya in the suggestions', 5000)
    await d.tapText('Maya', { wait: 900 })
    await d.typeSlow('see you there', { chunk: 3, pause: 150 })
    const draft = d.dump().find((n) => /see you there/.test(n.text ?? ''))
    check(/^@\w+ see you there$/.test(draft?.text ?? ''), `a picked mention leaves the caret after it (${draft?.text})`)
    d.shot('typing')
  })

  await step('rotate', async () => {
    d.sh('settings put system accelerometer_rotation 0')
    d.sh('settings put system user_rotation 1')
    await d.sleep(2500)
    const kept = d.dump().some((n) => /see you there/.test(n.text ?? ''))
    d.shot('rotated')
    d.sh('settings put system user_rotation 0')
    await d.sleep(2500)
    check(kept && d.dump().some((n) => /see you there/.test(n.text ?? '')), 'the draft survives a rotation')
  })

  await step('send-text', async () => {
    await d.tapDesc('Send comment', { wait: 2500 })
    await waitUntil(async () => (await comments(task.id)).length === 2, 'the second comment')
    const html = (await comments(task.id)).find((c) => c.comment.includes('see you there'))?.comment ?? ''
    console.log(`     ${html}`)
    check(html.includes('<mention-user') && html.includes('see you there</p>'), 'the comment has the mention element and the text')
    check(!d.dump().some((n) => /see you there/.test(n.text ?? '') && (n.class ?? '').includes('EditText')), 'the composer is empty again')
  })
} finally {
  d.sh('settings put system user_rotation 0')
  await api('DELETE', `tasks/${task.id}`).catch(() => {})
}

console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILED`)
process.exit(failures === 0 ? 0 : 1)
