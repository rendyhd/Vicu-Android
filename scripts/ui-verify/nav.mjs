// Navigation helpers shared by shots.mjs and a11y.mjs. All positions come from the screen size and
// the UI dump, never from fixed pixels, so they hold on another AVD.
import * as d from './droid.mjs'

export const sc = d.screen()
export const dp = (px) => px / sc.scale
export const px = (dpValue) => Math.round(dpValue * sc.scale)

const BOTTOM = ['Inbox', 'Today', 'Upcoming', 'Anytime']
const inBottomBar = (n) => n.cy > sc.height * 0.9

export const drawerOpen = (nodes) => nodes.some((n) => n['content-desc'] === 'Close navigation menu')
export const bottomBarVisible = (nodes) => nodes.filter((n) => n.text && BOTTOM.includes(n.text) && inBottomBar(n)).length >= 2

export async function launch(wait = 4000) { d.start(); await d.sleep(wait) }

export const isPermissionDialog = (nodes) => nodes.some((n) => /permissioncontroller/.test(n.package ?? ''))

/** Deny a system permission prompt (the harness never grants). Returns true when it tapped one. */
export async function denyPermissionPrompt(nodes = d.dump()) {
  if (!isPermissionDialog(nodes)) return false
  const deny = nodes.find((n) => n.clickable && (/permission_deny_button/.test(n['resource-id'] ?? '') || /^Don.t allow$/i.test(n.text ?? '')))
  if (!deny) return false
  await d.tap(deny.cx, deny.cy, 1000)
  return true
}

/** Back out of sheets, dialogs and the drawer (never past the app) to a bottom-bar destination. */
export async function home() {
  for (let i = 0; i < 6; i++) {
    if (!d.appInFront()) {
      if (await denyPermissionPrompt()) continue
      await launch()
      continue
    }
    const nodes = d.dump()
    if (drawerOpen(nodes)) { await d.tap(sc.width - 20, Math.round(sc.height / 2), 900); continue }
    if (bottomBarVisible(nodes)) return
    await d.back()
  }
  throw new Error('could not get back to a bottom-bar destination')
}

/** Open a bottom-bar destination (Inbox, Today, Upcoming, Anytime). */
export async function nav(label, wait = 2200) {
  await home()
  const n = d.findNode((x) => x.text === label && inBottomBar(x), `bottom bar ${label}`)
  await d.tap(n.cx, n.cy, wait)
}

export async function openDrawer(wait = 1300) {
  await home()
  await d.tapDesc('Open menu', { wait })
  // The drawer keeps its scroll position between openings: start every time from the top.
  for (let i = 0; i < 3; i++) await d.swipe(Math.round(sc.width * 0.4), Math.round(sc.height * 0.3), Math.round(sc.width * 0.4), Math.round(sc.height * 0.85), 250, 450)
  await d.sleep(500)
}

export async function closeDrawer() {
  if (drawerOpen(d.dump())) await d.tap(sc.width - 20, Math.round(sc.height / 2), 900)
}

/**
 * Scroll the content (swiping up, at horizontal position `x`, a fraction of the width) until
 * `pred` finds a node. Returns it with the dump it came from.
 */
export async function scrollFind(pred, label, x = 0.4) {
  for (let i = 0; i < 6; i++) {
    const nodes = d.dump()
    const n = nodes.find(pred)
    if (n) return { n, nodes }
    await d.swipe(Math.round(sc.width * x), Math.round(sc.height * 0.8), Math.round(sc.width * x), Math.round(sc.height * 0.25), 400, 900)
  }
  throw new Error(`not found after scrolling: ${label}`)
}
const drawerFind = scrollFind

/** Open a drawer entry by its text (Logbook, Review, Routines, Settings, ...). */
export async function drawer(label, wait = 2200) {
  await openDrawer()
  const { n } = await drawerFind((x) => x.text === label, label)
  await d.tap(n.cx, n.cy, wait)
}

/** Text nodes of a drawer section: after its upper-case header, before the next header. */
function sectionItems(nodes, header) {
  const texts = nodes.filter((n) => n.text && n.x1 >= 0 && n.x2 <= sc.width).sort((a, b) => a.y1 - b.y1)
  const start = texts.findIndex((n) => n.text === header)
  if (start < 0) return []
  const items = []
  for (const n of texts.slice(start + 1)) {
    if (/^[A-Z][A-Z ]+$/.test(n.text) && n.x1 < px(40)) break
    items.push(n)
  }
  return items
}

/** First project in the drawer (a child project when there is one, as it has a project screen of its own). `beforeTap` runs once the drawer shows it. */
export async function drawerProject(wait = 2200, beforeTap, index = 0) {
  await openDrawer()
  const { nodes } = await drawerFind((x) => x.text === 'PROJECTS', 'PROJECTS header')
  const items = sectionItems(nodes, 'PROJECTS')
  const children = items.filter((n) => n.x1 > px(76))
  const pick = children[index] ?? items[index] ?? items[0]
  if (!pick) throw new Error('no project in the drawer')
  if (beforeTap) await beforeTap() // a3 slows the animations here, after the drawer is open
  await d.tap(pick.cx, pick.cy, wait)
  return pick.text
}

/** First label in the drawer. */
export async function drawerTag(wait = 2200) {
  await openDrawer()
  await drawerFind((x) => x.text === 'LABELS', 'LABELS header')
  // The header can sit at the very bottom: scroll once more so the first label is on screen.
  await d.swipe(Math.round(sc.width * 0.4), Math.round(sc.height * 0.7), Math.round(sc.width * 0.4), Math.round(sc.height * 0.4), 400, 900)
  const nodes = d.dump()
  const items = sectionItems(nodes, 'LABELS')
  if (!items.length) throw new Error('no label in the drawer')
  await d.tap(items[0].cx, items[0].cy, wait)
  return items[0].text
}

/**
 * Task rows on screen: the circular checkbox (checkable, named) and the title text node that its
 * description contains. Rows are ordered top to bottom.
 */
export function taskRows(nodes = d.dump()) {
  const rows = []
  for (const cb of nodes.filter((n) => n.checkable && n.clickable && n.x1 < px(24) && n.w <= px(72))) {
    const desc = cb['content-desc'] ?? ''
    const title = d.rowTexts(nodes, cb)
      .filter((n) => n.text.length >= 2 && desc.includes(n.text))
      .sort((a, b) => b.text.length - a.text.length)[0]
    const row = nodes[cb.parent]
    const plain = !d.subtree(nodes, row).some((x) => x['content-desc'] === 'Repeating' || /subtasks? completed/.test(x['content-desc'] ?? ''))
    rows.push({ check: cb, title: title ?? null, desc, row, plain })
  }
  return rows.sort((a, b) => a.check.y1 - b.check.y1)
}

/**
 * The first task row on the current screen, or throws. `plain` skips repeating tasks and tasks
 * with subtasks, whose completion changes more than the row itself.
 */
export function firstRow(nodes, { plain = false } = {}) {
  const r = taskRows(nodes).find((x) => x.title && (!plain || x.plain))
  if (!r) throw new Error('no task row on this screen')
  return r
}

/** Open the task editor from the current list (the first task row). Returns the task title. */
export async function openFirstTask(wait = 2200) {
  const r = firstRow()
  await d.tap(r.title.cx, r.title.cy, wait)
  return r.title.text
}

/** The first of Today, Inbox, Anytime that has a task row (the harness data decides which). */
export async function listWithRows() {
  for (const label of ['Today', 'Inbox', 'Anytime']) {
    await nav(label, 2000)
    if (taskRows().some((r) => r.title)) return label
  }
  throw new Error('no task rows in Today, Inbox or Anytime')
}

/** Named screens, each as the steps that reach it from anywhere. Used by a11y.mjs and scenario a9. */
export const SCREENS = {
  inbox: () => nav('Inbox'),
  today: () => nav('Today'),
  upcoming: () => nav('Upcoming'),
  anytime: () => nav('Anytime'),
  drawer: () => openDrawer(),
  review: () => drawer('Review'),
  logbook: () => drawer('Logbook'),
  settings: () => drawer('Settings'),
  editor: async () => { await listWithRows(); await openFirstTask() },
  'quick-add': async () => { await nav('Today'); await d.tapDesc('New task', { wait: 1500 }) },
}

/** Press a node whose description (or text) starts with one of `labels`, in that order of preference. */
export async function tapFirstMatch(labels, wait = 1500) {
  const nodes = d.dump()
  for (const l of labels) {
    const n = nodes.find((x) => x['content-desc']?.startsWith(l) || x.text?.startsWith(l))
    if (n) { await d.tap(n.cx, n.cy, wait); return l }
  }
  throw new Error(`none of: ${labels.join(', ')}`)
}
