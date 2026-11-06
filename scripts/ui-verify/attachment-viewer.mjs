// Image attachments on emulator-5554, on a throwaway Inbox task: the files are uploaded the way a
// shared screenshot is (the part says application/octet-stream, the server reads the real type). An
// image row shows a thumbnail and opens the in-app viewer with Save and Share; a text file stays a file.
//
//   node attachment-viewer.mjs [--out DIR]
//
// Writes captures to out/attachment-viewer/ (or DIR) and prints one line per check. Exit code 1 when
// a check failed. Run `node login.mjs` first. The task is made through the API and deleted at the end.

import * as d from './droid.mjs'
import { resolveToken } from './login.mjs'
import * as v from './nav.mjs'
import { audit, printFindings } from './a11y.mjs'

const outArg = process.argv.indexOf('--out')
d.setOutDir(outArg > 0 ? process.argv[outArg + 1] : 'attachment-viewer')

const API = 'http://localhost:3456/api/v2'
const TOKEN = resolveToken()
if (!TOKEN) throw new Error('no API token: run the seed script in the vicu repo first')

const PROJECT = 'Trip to Lisbon'
const TITLE = 'Attachment viewer check'
const SHOT = 'Screenshot_20261105-035614.png'
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

async function upload(taskId, name, bytes) {
  const form = new FormData()
  form.append('files', new Blob([bytes], { type: 'application/octet-stream' }), name)
  const response = await fetch(`${API}/tasks/${taskId}/attachments`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${TOKEN}` },
    body: form,
  })
  if (!response.ok) throw new Error(`upload ${name}: ${response.status}`)
}

const items = (answer) => (Array.isArray(answer) ? answer : answer?.items ?? [])

async function waitUntil(pred, what, ms = 15000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (pred(d.dump())) return true
    await d.sleep(500)
  }
  throw new Error(`timed out waiting for ${what}`)
}

const project = items(await api('GET', 'projects')).find((p) => p.title === PROJECT)
if (!project) throw new Error(`no project ${PROJECT}: run the seed script in the vicu repo first`)
const task = await api('POST', `projects/${project.id}/tasks`, { title: TITLE })
console.log(`task ${task.id}`)

try {
  await v.launch(5000)
  await v.denyPermissionPrompt()
  await v.home()

  await upload(task.id, SHOT, d.adb('exec-out', 'screencap', '-p'))
  await upload(task.id, NOTES, new TextEncoder().encode('Notes for the attachment viewer check.\n'))
  const stored = items(await api('GET', `tasks/${task.id}/attachments`))
  console.log(`     stored type ${stored.find((a) => a.file?.name === SHOT)?.file?.mime}`)

  await step('open', async () => {
    await v.openDrawer()
    const { n: row } = await v.scrollFind((n) => n.text === PROJECT, `project ${PROJECT}`)
    await d.tap(row.cx, row.cy, 2500)
    const s = v.sc
    await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.3), Math.round(s.width / 2), Math.round(s.height * 0.75), 400, 3500)
    await d.tapText(TITLE, { wait: 2500 })
    const s2 = v.sc
    await waitUntil((nodes) => {
      if (nodes.some((n) => n.text === SHOT)) return true
      d.sh(`input swipe ${Math.round(s2.width / 2)} ${Math.round(s2.height * 0.8)} ${Math.round(s2.width / 2)} ${Math.round(s2.height * 0.35)} 300`)
      return false
    }, 'the attachment rows')
    await d.sleep(2500)
    d.shot('list')
    check(d.hasText(SHOT) && d.hasText(NOTES), 'the task lists both files')
  })

  await step('thumbnail', async () => {
    const nodes = d.dump()
    const name = nodes.find((n) => n.text === SHOT)
    const row = name && nodes
      .filter((n) => n.clickable && n.y1 <= name.y1 && n.y2 >= name.y2 && n.w > v.sc.width * 0.5)
      .sort((a, b) => a.h - b.h)[0]
    check(!!row && v.dp(row.h) >= 48, `the image row is tall enough for its thumbnail (${row ? v.dp(row.h).toFixed(0) : '?'} dp)`)
    const textRow = nodes.find((n) => n.text === NOTES)
    check(!!name && !!textRow && name.x1 > textRow.x1, 'the image name sits after a thumbnail, further in than the file row')
  })

  await step('viewer', async () => {
    await d.tapText(SHOT, { wait: 2500 })
    await waitUntil((nodes) => nodes.some((n) => n['content-desc'] === 'Close'), 'the viewer')
    await d.sleep(1500)
    d.shot('viewer')
    check(d.hasDesc('Image'), 'a tap opens the in-app viewer')
    check(d.hasDesc('Save image') && d.hasDesc('Share image') && d.hasDesc('Close'), 'the viewer has Save, Share and Close')
    check(d.appInFront(), 'it stays in the app')
    printFindings('attachment-viewer', audit(d.dump()))
    await d.tapDesc('Save image', { wait: 3000 })
    d.shot('viewer-saved')
    check(d.hasText('Saved', false) || d.hasText('Could not', false), 'Save says what happened on the viewer')
    await d.tapDesc('Close', { wait: 1200 })
    check(d.hasText(SHOT), 'Close goes back to the task')
  })

  await step('text file', async () => {
    await d.tapText(NOTES, { wait: 3000 })
    check(!d.hasDesc('Save image'), 'a text file does not open the image viewer')
    d.shot('text-file')
    if (!d.appInFront()) await d.back()
  })
} finally {
  await api('DELETE', `tasks/${task.id}`).catch(() => {})
  await d.back().catch(() => {})
}

console.log(failures === 0 ? 'all checks passed' : `${failures} checks failed`)
process.exit(failures === 0 ? 0 : 1)
