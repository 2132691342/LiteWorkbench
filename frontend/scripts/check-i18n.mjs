/** 校验：zh-CN 与 en-US 键集合必须一致，且模板/脚本里引用的静态键都必须在词条里 */
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(import.meta.dirname, '..')
const srcDir = path.join(root, 'src')

/** 载入词条文件：把 `export default` 换成 CommonJS 导出后求值 */
function loadLocale(file) {
  const source = fs
    .readFileSync(path.join(srcDir, 'locales', file), 'utf8')
    .replace(/^\uFEFF/, '')
    .replace(/export default/, 'module.exports =')
  const mod = { exports: {} }
  new Function('module', 'exports', source)(mod, mod.exports)
  return mod.exports
}

function flatten(node, prefix = '', out = {}) {
  for (const [key, value] of Object.entries(node)) {
    const full = prefix ? `${prefix}.${key}` : key
    if (value && typeof value === 'object') flatten(value, full, out)
    else out[full] = value
  }
  return out
}

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const target = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(target, out)
    else if (/\.(ts|vue)$/.test(entry.name)) out.push(target)
  }
  return out
}

const zh = flatten(loadLocale('zh-CN.ts'))
const en = flatten(loadLocale('en-US.ts'))

const problems = []
for (const key of Object.keys(zh)) if (!(key in en)) problems.push(`en-US 缺少 ${key}`)
for (const key of Object.keys(en)) if (!(key in zh)) problems.push(`zh-CN 缺少 ${key}`)

// 静态引用：t('a.b') / $t('a.b') / t(`a.b.${x}`) 的前缀
const used = new Set()
const dynamicPrefixes = new Set()
for (const file of walk(srcDir)) {
  if (file.includes(`${path.sep}locales${path.sep}`)) continue
  const source = fs.readFileSync(file, 'utf8')
  for (const match of source.matchAll(/\$?t\(\s*['"`]([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)['"`]/g)) used.add(match[1])
  for (const match of source.matchAll(/\$?t\(\s*`([A-Za-z0-9_.]*)\$\{/g)) dynamicPrefixes.add(match[1])
}
for (const key of used) {
  if (key in zh) continue
  const covered = [...dynamicPrefixes].some((prefix) => prefix && key.startsWith(prefix))
  if (!covered) problems.push(`词条缺失 ${key}`)
}

if (problems.length) {
  console.error(problems.join('\n'))
  process.exit(1)
}
console.log(`i18n ok: ${Object.keys(zh).length} keys, ${dynamicPrefixes.size} dynamic prefixes`)
