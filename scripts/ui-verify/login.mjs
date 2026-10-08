// Sets up the debug app against the local Vikunja server with an API token.
//
//   node login.mjs [tokenPath] [--server http://10.0.2.2:3456] [--inbox Inbox] [--force]
//
// tokenPath is a file holding the token, or the .local folder the desktop seed script writes
// (default ../../../vicu/scripts/ui-verify/.local). The token is typed through adb and never
// printed or logged. Without --force an app that is already signed in is left alone; --force runs
// `pm clear` on the debug app first (emulator-5554 only).
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import * as d from './droid.mjs'
import { bottomBarVisible, denyPermissionPrompt, isPermissionDialog, launch, sc } from './nav.mjs'

export const DEFAULT_SERVER = 'http://10.0.2.2:3456'
export const DEFAULT_TOKEN_PATH = resolve(d.REPO, '..', 'vicu', 'scripts', 'ui-verify', '.local')

const TOKEN_FILES = ['api-token.txt', 'api_token.txt', 'token.txt', 'token', 'api-token', 'vikunja-token.txt']
const TOKEN_KEYS = ['token', 'apiToken', 'api_token', 'apiKey', 'VIKUNJA_TOKEN', 'VICU_TOKEN', 'API_TOKEN']

function fromText(text) {
  const line = text.split(/\r?\n/).map((l) => l.trim()).find((l) => l && !l.startsWith('#'))
  if (!line) return null
  return line.replace(/^(?:export\s+)?[A-Z_]+=/, '').replace(/^["']|["']$/g, '').replace(/^Bearer\s+/i, '').trim() || null
}

function fromFile(file) {
  const raw = readFileSync(file, 'utf8')
  if (file.endsWith('.json')) {
    try {
      const o = JSON.parse(raw)
      for (const k of TOKEN_KEYS) if (typeof o?.[k] === 'string' && o[k]) return o[k].trim()
    } catch { /* not JSON: fall through */ }
    return null
  }
  if (file.endsWith('.env') || /(^|[\\/])\.env/.test(file)) {
    for (const l of raw.split(/\r?\n/)) {
      const m = l.match(/^(?:export\s+)?([A-Za-z_]+)=(.*)$/)
      if (m && /TOKEN/i.test(m[1])) return m[2].replace(/^["']|["']$/g, '').trim() || null
    }
    return null
  }
  return fromText(raw)
}

/** The token from a file or folder, or null. Never logs it. */
export function resolveToken(path = DEFAULT_TOKEN_PATH) {
  if (!existsSync(path)) return null
  if (!statSync(path).isDirectory()) return fromFile(path)
  const names = readdirSync(path)
  const ordered = [...TOKEN_FILES.filter((n) => names.includes(n)), ...names.filter((n) => /\.(json|env)$/.test(n) || n === '.env')]
  for (const n of ordered) {
    const t = fromFile(join(path, n))
    if (t) return t
  }
  return null
}

async function waitFor(pred, what, ms = 25000) {
  const end = Date.now() + ms
  for (;;) {
    const nodes = d.dump()
    const hit = pred(nodes)
    if (hit) return { nodes, hit }
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`)
    await d.sleep(600)
  }
}

const tapNode = (n, wait = 1200) => d.tap(n.cx, n.cy, wait)
const textNode = (nodes, t) => nodes.find((n) => n.text === t)

/**
 * Follow what shows after setup until the main screen is quiet: the notification rationale sheet
 * ("Allow notifications" is tapped, the system prompt that follows is denied) and "not now" cards.
 */
async function settle(notes, onShot) {
  let quiet = 0
  for (let i = 0; i < 14; i++) {
    const nodes = d.dump()
    if (isPermissionDialog(nodes)) {
      quiet = 0
      const texts = nodes.filter((n) => n.text).map((n) => n.text).slice(0, 4).join(' | ')
      notes.push(`system permission prompt after setup: ${texts}`)
      onShot?.('setup-6-permission-prompt')
      if (await denyPermissionPrompt(nodes)) continue
    }
    const allow = nodes.find((n) => n.text === 'Allow notifications')
    if (allow) {
      quiet = 0
      notes.push('rationale sheet shown after setup')
      onShot?.('setup-5-rationale')
      await tapNode(allow, 1500)
      continue
    }
    const later = nodes.find((n) => /^(Not now|Skip|Later|Maybe later|No thanks|Dismiss)$/i.test(n.text))
    if (later) { quiet = 0; notes.push(`dismissed "${later.text}"`); onShot?.('setup-5-card'); await tapNode(later); continue }
    if (bottomBarVisible(nodes) && ++quiet >= 2) return
    await d.sleep(1000)
  }
  throw new Error('main screen did not show after setup')
}

/** onShot(name) is called at the setup steps so a scenario can capture them (never the token step). */
export async function login({ tokenPath = DEFAULT_TOKEN_PATH, server = DEFAULT_SERVER, inbox = 'Inbox', force = false, onShot } = {}) {
  const notes = []
  await launch(3000)
  if (!force) {
    const nodes = d.dump()
    if (bottomBarVisible(nodes)) { console.log('already signed in (use --force to set up again)'); return notes }
  }
  const token = resolveToken(tokenPath)
  if (!token) throw new Error(`no API token found at ${tokenPath} (the seed script has not written it yet?)`)

  if (force) {
    console.log('pm clear:', d.pmClear())
    await launch(3500)
  }

  // A permission prompt before setup is a finding (A10), not something to hide.
  let nodes = d.dump()
  if (isPermissionDialog(nodes)) {
    notes.push('permission prompt BEFORE setup')
    await denyPermissionPrompt(nodes)
  }

  // Step 1: server URL.
  ;({ nodes } = await waitFor((ns) => textNode(ns, 'Welcome to Vicu'), 'the server URL step'))
  onShot?.('setup-1-server')
  const field = nodes.find((n) => n.class === 'android.widget.EditText')
  if (!field) throw new Error('no server URL field')
  await tapNode(field, 600)
  await d.type(server)
  await d.tapText('Continue', { wait: 1500 })

  // Step 2: pick the API token method.
  ;({ nodes } = await waitFor((ns) => textNode(ns, 'Use API token') || textNode(ns, 'Sign In'), 'the sign-in method step'))
  onShot?.('setup-2-method')
  const method = textNode(nodes, 'Use API token')
  if (!method) throw new Error('the server offers no API token sign-in')
  await tapNode(method)

  // Step 3: the token, typed and never echoed. Hide the keyboard with Go (the field's IME action).
  ;({ nodes } = await waitFor((ns) => textNode(ns, 'Connect'), 'the API token step'))
  const tokenField = nodes.find((n) => n.class === 'android.widget.EditText')
  if (!tokenField) throw new Error('no API token field')
  await tapNode(tokenField, 600)
  await d.type(token)
  await d.key(d.KEY.ENTER, 1500)

  // The Go action may already have connected; otherwise press Connect.
  let after = d.dump()
  if (!textNode(after, 'Select Inbox Project') && textNode(after, 'Connect')) {
    await d.tapText('Connect', { wait: 1500 })
  }

  // Step 4: Inbox project.
  try {
    ;({ nodes } = await waitFor((ns) => textNode(ns, 'Select Inbox Project'), 'the Inbox project step', 20000))
  } catch (e) {
    const err = d.dump().find((n) => n.text && /invalid|failed|error|denied|unauthor|could not/i.test(n.text))
    throw new Error(err ? `sign-in failed: ${err.text}` : e.message)
  }
  const pick = textNode(nodes, inbox) ?? nodes.find((n) => n.text && n.y1 > sc.height * 0.25 && n.y2 < sc.height * 0.8 && n.text !== 'Select Inbox Project' && n.x1 > 100 && !/^Choose which/.test(n.text))
  onShot?.('setup-4-project')
  if (!pick) throw new Error('no project to use as Inbox')
  if (pick.text !== inbox) notes.push(`no project named "${inbox}", used "${pick.text}"`)
  await tapNode(pick, 700)
  await d.tapText('Complete Setup', { wait: 2500 })

  await settle(notes, onShot)
  console.log('signed in', notes.length ? `(${notes.join('; ')})` : '')
  return notes
}

// CLI
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2)
  const flag = (n) => args.includes(`--${n}`)
  const opt = (n, dflt) => { const i = args.indexOf(`--${n}`); return i >= 0 ? args[i + 1] : dflt }
  const positional = args.find((a, i) => !a.startsWith('--') && !(i > 0 && ['--server', '--inbox'].includes(args[i - 1])))
  try {
    await login({ tokenPath: positional ? resolve(positional) : DEFAULT_TOKEN_PATH, server: opt('server', DEFAULT_SERVER), inbox: opt('inbox', 'Inbox'), force: flag('force') })
  } catch (e) {
    console.error('login failed:', e.message)
    process.exit(1)
  }
}
