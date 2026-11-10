// Markdown typed or pasted into a task's notes on emulator-5554 (description format, "Markdown
// input"; the desktop's scenario is scripts/ui-verify/scenarios/notes-markdown.mjs in the vicu repo).
// A throwaway task in the Inbox of the desktop seed; each part asserts on what the server holds.
//
//   ANDROID_SERIAL=emulator-5554 ./gradlew :app:installDebug :app:installDebugAndroidTest
//   node login.mjs
//   node notes-markdown.mjs [--out DIR] [--only typing,paste,html]
//
//   typing  the line shortcuts and the inline markers, typed one character at a time
//   paste   a Markdown document pasted with Ctrl+V becomes headings, lists, a checklist, ...
//   html    text that comes with an HTML version pastes as it is
//
// ClipboardSeed (app/src/androidTest) puts the text on the clipboard; that ends the app, so it runs
// before the task is opened. Like comment-paste.mjs it needs an emulator started with -no-window.
// Exit code 1 when a check failed.

import { execFileSync } from 'node:child_process'
import * as d from './droid.mjs'
import { resolveToken } from './login.mjs'
import * as v from './nav.mjs'

const outArg = process.argv.indexOf('--out')
d.setOutDir(outArg > 0 ? process.argv[outArg + 1] : 'notes-markdown')
const onlyArg = process.argv.indexOf('--only')
const only = onlyArg > 0 ? new Set(process.argv[onlyArg + 1].split(',')) : null
const runs = (part) => !only || only.has(part)

const API = 'http://localhost:3456/api/v2'
const TOKEN = resolveToken()
if (!TOKEN) throw new Error('no API token: run the seed script in the vicu repo first')

const DOC = [
  '# Plan',
  '',
  'Some **bold** and a [link](https://example.com/docs).',
  '',
  '- one',
  '  - nested',
  '- [ ] todo',
  '- [x] done',
  '',
  '1. first',
  '2. second',
  '',
  '> quoted',
  '',
  '```',
  'const a = 1 < 2',
  '```',
  '',
  '---',
  '',
  'After',
].join('\n')

let failures = 0
function check(ok, what, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${what}${ok || !detail ? '' : `\n     ${detail}`}`)
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
const items = (page) => (Array.isArray(page) ? page : page.items ?? [])

function refuseWindowedEmulator() {
  if (process.platform !== 'win32') return
  const processes = execFileSync('powershell.exe', ['-NoProfile', '-Command',
    'Get-CimInstance Win32_Process -Filter "Name like \'qemu-system%\'" | Select-Object -ExpandProperty CommandLine'])
  const emulator = processes.toString().split(/\r?\n/).find((line) => /qemu-system/.test(line) && new RegExp(`-port ${d.SERIAL.split('-')[1]}\\b`).test(line))
  if (!emulator?.includes('-no-window')) {
    throw new Error(`${d.SERIAL} must run with -no-window: a windowed emulator would share this computer's clipboard`)
  }
}

function seedClipboard(text, html) {
  const b64 = (s) => Buffer.from(s, 'utf8').toString('base64')
  const htmlArg = html ? ` -e seedHtml ${b64(html)}` : ''
  const answer = d.sh(`am instrument -w -e class com.rendyhd.vicu.ClipboardSeed#putTextOnClipboard -e seedText ${b64(text)}${htmlArg} ${d.APP_ID}.test/androidx.test.runner.AndroidJUnitRunner`)
  if (!answer.includes('OK (1 test)')) throw new Error(`the clipboard seed did not run: ${answer.slice(0, 300)}`)
}

async function waitUntil(pred, what, ms = 15000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (await pred()) return true
    await d.sleep(500)
  }
  throw new Error(`timed out waiting for ${what}`)
}

/** Opens [title] from the Inbox and puts the cursor in its notes. */
async function openNotes(title) {
  await v.launch(5000)
  await v.nav('Inbox', 2500)
  // The task was made behind the app's back: pull to refresh until it is listed.
  const s = v.sc
  await waitUntil(async () => {
    if (d.hasText(title)) return true
    await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.3), Math.round(s.width / 2), Math.round(s.height * 0.75), 400, 2500)
    return d.hasText(title)
  }, `task ${title} in the Inbox`, 30000)
  await d.tapText(title, { wait: 2500 })
  // The notes start one line under the title; their placeholder is drawn, not a node of the dump.
  const nodes = d.dump()
  const notes = nodes.find((n) => (n.text ?? '').startsWith('Start'))
  const heading = nodes.find((n) => n.text === title)
  if (!notes && !heading) throw new Error('no notes field in the editor')
  await d.tap(notes?.cx ?? v.px(60), notes?.cy ?? heading.cy + v.px(34), 1200)
}

/** Leaves the editor (which saves) and returns the notes the server holds once they changed. */
async function savedNotes(task, before) {
  await d.back()
  await d.back()
  let html = before
  await waitUntil(async () => {
    html = (await api('GET', `tasks/${task.id}`)).description ?? ''
    return html !== before
  }, 'the notes on the server')
  return html
}

const paste = () => d.sh('input keycombination 113 50') // Ctrl+V

refuseWindowedEmulator()
const projects = items(await api('GET', 'projects'))
const inbox = projects.find((p) => p.title === 'Inbox')
if (!inbox) throw new Error('no Inbox project: run the seed script in the vicu repo first')

const made = []
async function newTask(title, description = '') {
  const task = await api('POST', `projects/${inbox.id}/tasks`, { title, description })
  made.push(task)
  return task
}

try {
  if (runs('typing')) await step('typing', async () => {
    const task = await newTask('MD typing check')
    d.stop()
    await openNotes(task.title)
    const line = async (text) => {
      await d.typeSlow(text, { pause: 160 })
      await d.key(d.KEY.ENTER, 500)
    }
    await line('## Head')
    await line('Some **bold** and *it* and ~~gone~~ and `code` and [a link](https://example.com) end')
    await d.typeSlow('---', { pause: 160 }) // a divider, and the cursor on a new line under it
    await line('> quote')
    await d.key(d.KEY.ENTER, 500) // an empty line leaves the quote
    await line('* star')
    await d.key(d.KEY.ENTER, 500) // an empty item leaves the list
    await line('3) third')
    await d.key(d.KEY.ENTER, 500)
    await d.typeSlow('[ ] check', { pause: 160 })
    d.shot('typed')
    const html = await savedNotes(task, '')
    const has = (label, re) => check(re.test(html), label, html)
    has('## makes a heading', /<h2>Head<\/h2>/)
    has('**bold** is bold', /<strong>bold<\/strong>/)
    has('*it* is italic', /<em>it<\/em>/)
    has('~~gone~~ is struck', /<s>gone<\/s>/)
    has('`code` is code', /<code>code<\/code>/)
    has('[a link](...) is a link', /<a href="https:\/\/example\.com"[^>]*>a link<\/a> end/)
    has('* makes a bullet', /<ul><li>(<p>)?star(<\/p>)?<\/li><\/ul>/)
    has('3) makes a numbered list', /<ol[^>]*><li>(<p>)?third(<\/p>)?<\/li><\/ol>/)
    has('[ ] makes a checklist item', /data-type="taskList".*check<\/p>/s)
    has('> makes a quote', /<blockquote>(<p>)?quote(<\/p>)?<\/blockquote>/)
    has('--- makes a divider', /<hr>/)
    has('no marker is left', { test: (s) => !/\*\*|~~|`|\]\(|^#|&gt; /.test(s.replace(/<[^>]+>/g, '')) })
  })

  if (runs('paste')) await step('paste', async () => {
    const task = await newTask('MD paste check')
    d.stop()
    seedClipboard(DOC)
    await openNotes(task.title)
    paste()
    await d.sleep(1500)
    d.shot('pasted')
    const html = await savedNotes(task, '')
    const has = (label, re) => check(re.test(html), label, html)
    has('heading', /<h1>Plan<\/h1>/)
    has('bold and link in a paragraph', /<p>Some <strong>bold<\/strong> and a <a href="https:\/\/example\.com\/docs"[^>]*>link<\/a>\.<\/p>/)
    // Android writes list items and quotes without the inner <p>; the contract reads both alike.
    has('nested bullet list', /<ul><li>(<p>)?one(<\/p>)?<ul><li>(<p>)?nested(<\/p>)?<\/li><\/ul><\/li><\/ul>/)
    has('checklist with a ticked item', /data-type="taskList".*data-checked="false".*todo.*data-checked="true".*done/s)
    has('numbered list', /<ol><li>(<p>)?first(<\/p>)?<\/li><li>(<p>)?second(<\/p>)?<\/li><\/ol>/)
    has('quote', /<blockquote>(<p>)?quoted(<\/p>)?<\/blockquote>/)
    has('code block', /<pre><code>const a = 1 &lt; 2<\/code><\/pre>/)
    has('divider', /<hr>/)
    has('the last line', /<p>After<\/p>/)
  })

  if (runs('paste')) await step('paste-inline', async () => {
    const task = await newTask('MD inline paste check', '<p>Start</p>')
    d.stop()
    seedClipboard('[Link Text](http://www.example.com)')
    await openNotes(task.title)
    await d.key(123, 400) // End
    await d.typeSlow(' see ', { pause: 160 })
    paste()
    await d.sleep(1200)
    const html = await savedNotes(task, '<p>Start</p>')
    check(/<p>Start see <a href="http:\/\/www\.example\.com"[^>]*>Link Text<\/a><\/p>/.test(html), 'a pasted Markdown link goes into the same line', html)
  })

  if (runs('html')) await step('html', async () => {
    const task = await newTask('MD html paste check')
    d.stop()
    seedClipboard('Plain **words** stay', '<p>Plain **words** stay</p>')
    await openNotes(task.title)
    paste()
    await d.sleep(1200)
    const html = await savedNotes(task, '')
    check(!/<strong>/.test(html) && html.includes('**words**'), 'text that comes with HTML pastes as it is', html)
  })
} finally {
  for (const task of made) await api('DELETE', `tasks/${task.id}`).catch(() => {})
}

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed')
process.exit(failures ? 1 : 0)
