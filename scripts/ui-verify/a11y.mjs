// Accessibility report from a UI dump of the debug app on emulator-5554.
//
//   node a11y.mjs                           the screen that is showing now
//   node a11y.mjs --screens today,editor    go to each named screen first (see SCREENS in nav.mjs)
//   node a11y.mjs --strict                  exit 1 when anything is found (wave 0 reports only)
//
// Rules:
//   unnamed-clickable  a clickable node with no text or description, itself or below it
//   checkbox           a task checkbox that is not checkable, has no description, or whose
//                      description lacks the title of its row
//   small-target       a clickable or checkable node under 48 dp in width or height
//                      (pixels divided by the density scale from `wm density`)
// Findings are also written to out/a11y/<screen>.json.
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import * as d from './droid.mjs'
import { px, sc, SCREENS } from './nav.mjs'

const TOP_BAR = new Set(['Open menu', 'Search', 'Close', 'Back', 'Cancel selection', 'Close navigation menu'])
const MIN_DP = 48
const TOLERANCE_DP = 1

const label = (n) => n.text || n['content-desc'] || `${n.class?.split('.').pop() ?? 'node'} ${n.bounds}`
const onScreen = (n) => n.w > 0 && n.h > 0 && n.x2 > 0 && n.y2 > 0 && n.x1 < sc.width && n.y1 < sc.height
// A 6 px sliver is layout, not something a finger can hit: leave it to the small-target noise filter.
const visible = (n) => onScreen(n) && n.w >= 10 && n.h >= 10

function named(nodes, n) {
  return d.subtree(nodes, n).some((x) => (x.text && x.text.trim()) || (x['content-desc'] && x['content-desc'].trim()))
}

/** Findings for one dump: [{ rule, label, bounds, detail }]. */
export function audit(nodes) {
  const out = []
  const add = (rule, n, detail) => out.push({ rule, label: label(n), bounds: n.bounds, detail })

  for (const n of nodes) {
    if (!n.clickable || !visible(n)) continue
    if (!named(nodes, n)) add('unnamed-clickable', n, `${(n.w / sc.scale).toFixed(0)} x ${(n.h / sc.scale).toFixed(0)} dp`)
  }

  // Task checkboxes: clickable, about the size of a 48 dp target, at the left edge of a clickable
  // row, below the top bar.
  const topBarBottom = Math.max(0, ...nodes.filter((n) => TOP_BAR.has(n['content-desc']) && n.y1 < sc.height * 0.2).map((n) => n.y2)) + px(8)
  const boxes = nodes.filter((n) => visible(n) && n.clickable && nodes[n.parent]?.clickable && n.x1 < px(24) && n.w >= px(40) && n.w <= px(64) && n.h >= px(40) && n.h <= px(64) && n.y1 > topBarBottom && n.y2 < sc.height * 0.92)
  for (const cb of boxes) {
    const desc = (cb['content-desc'] ?? '').trim()
    if (!cb.checkable) { add('checkbox', cb, `not checkable (no role or state)${desc ? `, desc "${desc}"` : ''}`); continue }
    if (!desc) { add('checkbox', cb, 'no content description'); continue }
    const texts = d.rowTexts(nodes, cb).filter((t) => t.text.trim().length >= 2)
    if (texts.length && !texts.some((t) => desc.includes(t.text))) add('checkbox', cb, `description "${desc}" lacks the row title`)
  }

  for (const n of nodes) {
    if (!(n.clickable || n.checkable) || !visible(n)) continue
    const wdp = n.w / sc.scale
    const hdp = n.h / sc.scale
    if (wdp < MIN_DP - TOLERANCE_DP || hdp < MIN_DP - TOLERANCE_DP) add('small-target', n, `${wdp.toFixed(0)} x ${hdp.toFixed(0)} dp`)
  }
  return out
}

export function printFindings(name, findings) {
  const by = {}
  for (const f of findings) (by[f.rule] ??= []).push(f)
  console.log(`-- ${name}: ${findings.length} finding(s)${Object.entries(by).map(([r, l]) => ` ${r}=${l.length}`).join('')}`)
  for (const f of findings) console.log(`   [${f.rule}] ${f.label} ${f.bounds}${f.detail ? `  ${f.detail}` : ''}`)
  const dir = join(d.HERE, 'out', 'a11y')
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, `${name}.json`), JSON.stringify({ screen: name, density: sc.scale, findings }, null, 2) + '\n')
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2)
  const i = args.indexOf('--screens')
  const names = i >= 0 ? args[i + 1].split(',') : null
  let total = 0
  if (names) {
    for (const name of names) {
      if (!SCREENS[name]) { console.error(`unknown screen ${name} (known: ${Object.keys(SCREENS).join(', ')})`); process.exit(2) }
      try { await SCREENS[name]() } catch (e) { console.log(`FAIL ${name}: ${e.message.split('\n')[0]}`); continue }
      const f = audit(d.dump())
      total += f.length
      printFindings(name, f)
    }
  } else {
    const f = audit(d.dump())
    total += f.length
    printFindings('current', f)
  }
  console.log(`\n${total} finding(s) in total${args.includes('--strict') ? '' : ' (reported only)'}`)
  process.exit(args.includes('--strict') && total ? 1 : 0)
}
