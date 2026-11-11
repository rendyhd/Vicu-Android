// Files added while offline (issue 104) on emulator-5554: a throwaway task in the seeded "Trip to
// Lisbon" project, airplane mode on, a file attached through the editor's File chip and the real
// picker, the waiting row and the snackbar, then airplane mode off and the file on the server.
//
//   node offline-attach.mjs [--out DIR]
//
// Writes captures to out/offline-attach/ (or DIR) and prints one line per check. Exit code 1 when a
// check failed. Run `node login.mjs` first. The task is made through the API and deleted at the end,
// and airplane mode is always turned off again.

import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import * as d from './droid.mjs'
import { resolveToken } from './login.mjs'
import * as v from './nav.mjs'
import { audit, printFindings } from './a11y.mjs'

const outArg = process.argv.indexOf('--out')
d.setOutDir(outArg > 0 ? process.argv[outArg + 1] : 'offline-attach')

const API = 'http://localhost:3456/api/v2'
const TOKEN = resolveToken()
if (!TOKEN) throw new Error('no API token: run the seed script in the vicu repo first')

const PROJECT = 'Trip to Lisbon'
const TITLE = 'OFFA Offline attach check'
const FILE = 'vicu-offline-photo.png'

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

function airplane(on) {
  d.sh(`cmd connectivity airplane-mode ${on ? 'enable' : 'disable'}`)
}

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

async function waitUntil(pred, what, ms = 15000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const nodes = d.dump()
    if (pred(nodes)) return nodes
    await d.sleep(400)
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

  const dir = mkdtempSync(join(tmpdir(), 'vicu-offline-'))
  writeFileSync(join(dir, FILE), d.adb('exec-out', 'screencap', '-p'))
  d.adb('push', join(dir, FILE), `/sdcard/Download/${FILE}`)
  d.sh(`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/${FILE}`)

  await step('open', async () => {
    await v.openDrawer()
    const { n: row } = await v.scrollFind((n) => n.text === PROJECT, `project ${PROJECT}`)
    await d.tap(row.cx, row.cy, 2500)
    const s = v.sc
    await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.3), Math.round(s.width / 2), Math.round(s.height * 0.75), 400, 3500)
    await d.tapText(TITLE, { wait: 2500 })
    check(d.hasText(TITLE), 'the editor opens on the task')
  })

  await step('offline-attach', async () => {
    airplane(true)
    // The editor covers the list's Offline banner; give the network callback a moment.
    await d.sleep(4000)
    check(d.sh('settings get global airplane_mode_on').trim() === '1', 'airplane mode is on')
    await d.tapText('File', { wait: 1500 })
    await pickInFiles(FILE)
    const nodes = await waitUntil((ns) => ns.some((n) => n.text === `${FILE} uploads when you're back online`), 'the waiting snackbar')
    d.shot('waiting-snackbar')
    check(true, 'the snackbar says the file uploads when back online')
    const row = await waitUntil((ns) => ns.some((n) => (n.text ?? '').startsWith('Waiting to upload')), 'the waiting row').catch(async () => {
      // The row sits under the notes: scroll down to it.
      const s = v.sc
      await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.75), Math.round(s.width / 2), Math.round(s.height * 0.3), 400, 1200)
      return d.dump()
    })
    d.shot('waiting-row')
    check(row.some((n) => (n.text ?? '').startsWith('Waiting to upload')), 'the editor lists the file as waiting to upload')
    check(row.some((n) => n['content-desc'] === `Remove ${FILE}`), 'the waiting file can be removed by name')
    check(row.some((n) => n['content-desc'] === `${FILE}, waiting to upload`), 'TalkBack reads the file as waiting to upload')
    const before = items(await api('GET', `tasks/${task.id}/attachments`))
    check(before.length === 0, 'nothing reached the server while offline')
    const findings = audit(row)
    printFindings('offline-attach-waiting', findings)
    const ours = findings.filter((f) => /remove|retry|vicu-offline|waiting/i.test(f.label))
    check(ours.length === 0, `no accessibility finding on the waiting row${ours.length ? ` (${ours.map((f) => f.label).join(', ')})` : ''}`)
    void nodes
  })

  await step('back-online', async () => {
    airplane(false)
    const end = Date.now() + 60000
    let uploaded = []
    while (Date.now() < end) {
      uploaded = items(await api('GET', `tasks/${task.id}/attachments`).catch(() => []))
      if (uploaded.length) break
      await d.sleep(1500)
    }
    check(uploaded.length === 1 && uploaded[0].file?.name === FILE, `the file reached the server once (${uploaded.map((a) => a.file?.name).join(', ') || 'none'})`)
    const nodes = await waitUntil((ns) => !ns.some((n) => (n.text ?? '').startsWith('Waiting to upload')), 'the waiting row to go', 30000)
    await d.sleep(1500)
    d.shot('uploaded')
    check(d.dump().some((n) => n.text === FILE), 'the file now shows as an ordinary attachment')
    const leftover = d.sh(`run-as ${d.APP_ID} ls no_backup/offline-attachments`).trim()
    console.log(`     offline copies left: ${leftover || '(none)'}`)
    check(!/\.bin/.test(leftover), 'the copy on the device is deleted after the upload')
    void nodes
  })
} finally {
  airplane(false)
  await api('DELETE', `tasks/${task.id}`).catch((e) => console.log(`could not delete task ${task.id}: ${e.message}`))
  try { d.sh(`rm -f /sdcard/Download/${FILE}`) } catch { /* ignore */ }
  try { await v.home() } catch { /* ignore */ }
}

console.log(failures ? `${failures} failed` : 'all passed')
process.exit(failures ? 1 : 0)
