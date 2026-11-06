// Files in comments on emulator-5554 (cross-app semantics 9.5), on a throwaway task in the shared
// "Trip to Lisbon" project of the desktop seed: attach a screenshot and a text file through the real
// pickers, send, see the preview and the file row, open the viewer, save, edit the text and keep the
// files, see a deleted file say so, and take a staged photo out again.
//
//   node comment-files.mjs [--out DIR]
//
// Writes captures to out/comment-files/ (or DIR) and prints one line per check. Exit code 1 when a
// check failed. Run `node login.mjs` first. The task is made through the API and deleted at the end.

import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import * as d from './droid.mjs'
import { resolveToken } from './login.mjs'
import * as v from './nav.mjs'
import { audit, printFindings } from './a11y.mjs'

const outArg = process.argv.indexOf('--out')
d.setOutDir(outArg > 0 ? process.argv[outArg + 1] : 'comment-files')

const API = 'http://localhost:3456/api/v2'
const TOKEN = resolveToken()
if (!TOKEN) throw new Error('no API token: run the seed script in the vicu repo first')

const LISBON = 'Trip to Lisbon'
const TITLE = 'Comment files check'
const SHOT = 'vicu-check-shot.png'
const NOTES = 'vicu-check-notes.txt'

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

/** Waits for a file in the system's file picker and picks it. */
async function pickInFiles(name) {
  await d.sleep(2500)
  if (!d.hasText(name)) {
    const roots = d.dump().find((n) => n['content-desc'] === 'Show roots')
    if (roots) {
      await d.tap(roots.cx, roots.cy, 1200)
      await d.tapText('Downloads', { wait: 2000 })
    }
  }
  await d.tapText(name, { wait: 2500 })
}

/** The editor's Comments chip: text alone while the task has none, else named with the count. */
async function openComments(wait) {
  const chip = d.dump().find((n) => n.text === 'Comments' || (n['content-desc'] ?? '').startsWith('Comments'))
  if (!chip) throw new Error('no Comments chip in the editor')
  await d.tap(chip.cx, chip.cy, wait)
}

async function openAttachMenu() {
  await d.tapDesc('Attach a photo or file', { wait: 900 })
}

async function waitUntil(pred, what, ms = 15000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (pred(d.dump())) return true
    await d.sleep(500)
  }
  throw new Error(`timed out waiting for ${what}`)
}

const lisbon = items(await api('GET', 'projects')).find((p) => p.title === LISBON)
if (!lisbon) throw new Error(`no project ${LISBON}: run the seed script in the vicu repo first`)
const task = await api('POST', `projects/${lisbon.id}/tasks`, { title: TITLE })
console.log(`task ${task.id}`)

try {
  await v.launch(5000)
  await v.denyPermissionPrompt()
  await v.home()

  // Real files for the pickers: a screenshot of the app and a short text file.
  const dir = mkdtempSync(join(tmpdir(), 'vicu-check-'))
  writeFileSync(join(dir, SHOT), d.adb('exec-out', 'screencap', '-p'))
  writeFileSync(join(dir, NOTES), 'Notes for the comment files check.\n')
  for (const name of [SHOT, NOTES]) {
    d.adb('push', join(dir, name), `/sdcard/Download/${name}`)
    d.sh(`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/${name}`)
  }

  await step('open', async () => {
    await v.openDrawer()
    const { n: project } = await v.scrollFind((n) => n.text === LISBON, `project ${LISBON}`)
    await d.tap(project.cx, project.cy, 2500)
    const s = v.sc
    await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.3), Math.round(s.width / 2), Math.round(s.height * 0.75), 400, 3500)
    await d.tapText(TITLE, { wait: 2500 })
    await openComments(3000)
    check(d.hasText('Comments'), 'the Comments screen opens')
    check(d.hasDesc('Attach a photo or file'), 'the composer has the attach button')
  })

  await step('menu', async () => {
    await openAttachMenu()
    d.shot('attach-menu')
    check(d.hasText('Photo') && d.hasText('File'), 'the menu offers Photo and File')
    await d.back()
  })

  await step('attach', async () => {
    await openAttachMenu()
    await d.tapText('File', { wait: 800 })
    await pickInFiles(SHOT)
    await waitUntil((nodes) => nodes.some((n) => n.text === SHOT), 'the screenshot chip')
    await openAttachMenu()
    await d.tapText('File', { wait: 800 })
    await pickInFiles(NOTES)
    await waitUntil((nodes) => nodes.some((n) => n.text === NOTES), 'the file chip')
    await d.sleep(2500)
    d.shot('staged')
    check(d.hasDesc(`Remove ${SHOT}`) && d.hasDesc(`Remove ${NOTES}`), 'each staged file can be removed by name')
    const send = d.byDesc('Send comment')
    check(send.enabled !== 'false', 'a comment of files alone can be sent')
    const field = d.byText('Comment. Type @ to mention')
    await d.tap(field.cx, field.cy, 800)
    await d.typeSlow('Look at this', { chunk: 4, pause: 120 })
    await d.key(d.KEY.BACK, 800)
    await d.tapDesc('Send comment', { wait: 3500 })
    d.shot('posted')
  })

  let shotId = 0
  let notesId = 0
  await step('server', async () => {
    const attachments = items(await api('GET', `tasks/${task.id}/attachments`))
    shotId = attachments.find((a) => a.file?.name === SHOT)?.id ?? 0
    notesId = attachments.find((a) => a.file?.name === NOTES)?.id ?? 0
    check(shotId > 0 && notesId > 0, 'both files are task attachments')
    const comments = items(await api('GET', `tasks/${task.id}/comments`))
    const html = comments[0]?.comment ?? ''
    console.log(`     ${html}`)
    check(html.startsWith('<p>Look at this</p>'), 'the comment starts with its text')
    check(html.includes(`/api/v1/tasks/${task.id}/attachments/${shotId}" alt="${SHOT}">`), 'the screenshot is an img with the v1 address')
    check(html.includes(`/api/v1/tasks/${task.id}/attachments/${notesId}">${NOTES}</a>`), 'the text file is a link with the v1 address')
  })

  await step('thread', async () => {
    await waitUntil((nodes) => nodes.some((n) => n['content-desc'] === SHOT), 'the preview')
    await d.sleep(2000)
    d.shot('thread')
    const nodes = d.dump()
    const image = nodes.find((n) => n['content-desc'] === SHOT)
    check(!!image, 'the image shows inline, named after the file')
    if (image) {
      check(Math.abs(v.dp(image.h) - 200) < 3, `the preview is 200 dp high (${v.dp(image.h).toFixed(0)} dp)`)
      const ratio = image.w / image.h
      console.log(`     preview ratio ${ratio.toFixed(2)}`)
      check(ratio < 1, 'the preview takes the width of the portrait screenshot, not the whole row')
    }
    check(nodes.some((n) => n.text === NOTES), 'the file shows its name')
    check(nodes.some((n) => n['content-desc'] === `Save ${NOTES}`) && nodes.some((n) => n['content-desc'] === `Share ${NOTES}`), 'the file has Save and Share')
    const findings = audit(nodes)
    printFindings('comment-files-thread', findings)
    const ours = findings.filter((f) => /save|share|attach|send|vicu-check|open/i.test(f.label))
    check(ours.length === 0, `no accessibility finding on the new controls${ours.length ? ` (${ours.map((f) => f.label).join(', ')})` : ''}`)
  })

  await step('save-file', async () => {
    await d.tapDesc(`Save ${NOTES}`, { wait: 400 })
    await waitUntil((nodes) => nodes.some((n) => /saved|download/i.test(n.text ?? '') && n.text !== NOTES), 'a message about the save')
    d.shot('file-saved')
    check(true, 'saving the file says where it went')
    // The name is taken (the file we picked), so the system numbers it: before the extension, when
    // it knows the type. The server's "text/plain; charset=utf-8" would put the number after it.
    const saved = d.sh('ls /sdcard/Download/').split(/\r?\n/).map((l) => l.trim()).filter((l) => l.startsWith('vicu-check-notes'))
    console.log(`     ${saved.join(' | ')}`)
    check(saved.some((l) => /^vicu-check-notes \(\d+\)\.txt$/.test(l)), 'the saved copy keeps its extension')
  })

  await step('open-file', async () => {
    await d.sleep(4500)
    await d.tapText(NOTES, { wait: 3500 })
    const left = !d.appInFront()
    d.shot('file-opened')
    check(left || d.dump().some((n) => /no app|open/i.test(n.text ?? '')), left ? 'the file opens in another app' : 'the file says no app can open it')
    if (left) {
      await d.back()
      await d.sleep(1200)
      if (!d.appInFront()) d.start()
      await d.sleep(1500)
    }
  })

  await step('viewer', async () => {
    const image = d.byDesc(SHOT)
    await d.tap(image.cx, image.cy, 2000)
    d.shot('viewer')
    check(d.hasDesc('Save image') && d.hasDesc('Share image') && d.hasDesc('Close'), 'the viewer has Save, Share and Close')
    await d.tapDesc('Save image', { wait: 400 })
    await waitUntil((nodes) => nodes.some((n) => /saved|download/i.test(n.text ?? '')), 'the note in the viewer')
    d.shot('viewer-saved')
    check(true, 'the viewer itself says where the image went')
    await d.tapDesc('Close', { wait: 1200 })
    check(d.hasText('Comments'), 'closing the viewer returns to the thread')
  })

  await step('edit', async () => {
    const body = d.byText('Look at this', false)
    await d.longPress(body.cx, body.cy, 900)
    await d.sleep(700)
    d.shot('selected')
    await d.tapText('Edit', { wait: 1200 })
    d.shot('editing')
    check(d.hasDesc(`Save ${NOTES}`), 'the files stay in view while the text is edited')
    const field = d.dump().find((n) => n.text === 'Look at this')
    await d.tap(field.cx, field.cy, 700)
    d.sh('input keyevent KEYCODE_MOVE_END')
    await d.typeSlow(' again', { chunk: 3, pause: 120 })
    await d.key(d.KEY.BACK, 600)
    await d.tapText('Save', { wait: 3000 })
    const html = items(await api('GET', `tasks/${task.id}/comments`))[0]?.comment ?? ''
    console.log(`     ${html}`)
    check(html.startsWith('<p>Look at this again</p>'), 'the edit changed the text')
    check(html.includes(`attachments/${shotId}" alt="${SHOT}">`) && html.includes(`attachments/${notesId}">${NOTES}</a>`), 'the edit kept both files')
  })

  await step('delete-question', async () => {
    const body = d.byText('Look at this again', false)
    await d.longPress(body.cx, body.cy, 900)
    await d.sleep(700)
    await d.tapText('Delete', { wait: 1000 })
    d.shot('delete-question')
    check(d.hasText("This cannot be undone. Its files stay in the task's attachments."), 'the delete question says the files stay')
    await d.tapText('Cancel', { wait: 800 })
    const selected = d.byText('Look at this again', false)
    await d.longPress(selected.cx, selected.cy, 900)
    await d.sleep(500)
  })

  await step('gone', async () => {
    await api('DELETE', `tasks/${task.id}/attachments/${notesId}`)
    await api('DELETE', `tasks/${task.id}/attachments/${shotId}`)
    // Leave the thread and come back: the app reads the task's attachments again.
    await d.back()
    await d.sleep(1200)
    await d.back()
    await d.sleep(1500)
    await d.tapText(TITLE, { wait: 2500 })
    await openComments(3500)
    await waitUntil((nodes) => nodes.filter((n) => n.text === 'No longer available').length >= 2, 'both files to say they are gone')
    d.shot('gone')
    check(true, 'files deleted from the task say "No longer available"')
    check(!d.hasDesc(`Save ${NOTES}`), 'a file that is gone offers no Save')
  })

  await step('photo-removed', async () => {
    await openAttachMenu()
    await d.tapText('Photo', { wait: 3500 })
    d.shot('photo-picker')
    const photo = d.dump().find((n) => /^Photo taken|^Screenshot|^Image/.test(n['content-desc'] ?? ''))
    if (!photo) throw new Error('no photo in the picker')
    await d.tap(photo.cx, photo.cy, 1000)
    const add = d.dump().find((n) => n.text === 'Add' || n.text === 'Add (1)' || n.text === 'Done')
    if (add) await d.tap(add.cx, add.cy, 1000)
    await waitUntil((nodes) => nodes.some((n) => (n['content-desc'] ?? '').startsWith('Remove ')), 'the photo chip')
    await d.sleep(2500)
    d.shot('photo-staged')
    const before = items(await api('GET', `tasks/${task.id}/attachments`)).length
    check(before === 1, 'the photo is on the task as soon as it is picked')
    const remove = d.dump().find((n) => (n['content-desc'] ?? '').startsWith('Remove '))
    await d.tap(remove.cx, remove.cy, 2500)
    check(!d.dump().some((n) => (n['content-desc'] ?? '').startsWith('Remove ')), 'the chip is gone')
    const after = items(await api('GET', `tasks/${task.id}/attachments`)).length
    check(after === 0, 'removing it takes it off the task')
  })
} finally {
  await api('DELETE', `tasks/${task.id}`).catch((e) => console.log(`could not delete task ${task.id}: ${e.message}`))
  try { d.sh('rm -f /sdcard/Download/vicu-check-*') } catch { /* ignore */ }
  try { await v.home() } catch { /* ignore */ }
}

console.log(failures ? `${failures} failed` : 'all passed')
process.exit(failures ? 1 : 0)
