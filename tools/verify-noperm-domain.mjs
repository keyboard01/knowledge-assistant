#!/usr/bin/env node
/**
 * AC-P1-009 的后半段：把"仅含无权限文档的知识域"这个场景**真的造出来**，再验一遍。
 *
 * 为什么需要造：验收语料里三个知识域各自都有当前身份可见的文档，
 * "打开一个看不到任何文档的域"这条分支永远走不到。这里不新增语料、不改产品配置，
 * 而是临时把技术文档域的 ACL 换个哨兵值，让 demo 在该域可见数为 0，验完立刻还原。
 *
 * 验什么（两个问题分开问，结论才能分开下）：
 *   1) 接口：GET /api/domains 给的是**可见数**而不是总数。判据是它与另一条独立路径
 *      （GET /api/documents，本来就被 ACL 过滤）逐域对得上；demo 的 policy=3 而 admin=4，
 *      那个 4 只存在于 admin 的响应里——接口里没有"总数"这个东西可漏。
 *   2) 界面：可见数为 0 的域在问答页被标成"（无可见文档）"且不可选、默认不选中它，
 *      并且整页文本里不出现该域任何文档的标题与编号（截图留证）。
 *
 * 前置：后端 9090、前端 dev server 5173、PostgreSQL 容器 assistant-pg 都在跑，且能访问 Docker 管道。
 * 用法：node tools/verify-noperm-domain.mjs [--headed]
 */

import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { chromium } from 'playwright-core'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..')
const SHOTS = join(ROOT, 'target', 'ui')
const API = process.env.API_BASE || 'http://127.0.0.1:9090'
const BASE = process.env.UI_BASE || 'http://127.0.0.1:5173'
const HEADED = process.argv.includes('--headed')
const PG_CONTAINER = process.env.PG_CONTAINER || 'assistant-pg'

/** 撤销权限用的哨兵值。用改值而不是删行：删行要备份-恢复，中途炸了权限就永久丢了。 */
const SENTINEL = '__t12_no_permission__'
const TARGET_DOMAIN = 'tech'

const EDGE_CANDIDATES = [
  process.env.UI_BROWSER_EXE,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe'
].filter(Boolean)

/** 域对象允许出现的字段：多一个字段就可能是"总数"偷偷混进来，所以按白名单卡死。 */
const DOMAIN_FIELDS = ['code', 'name', 'description', 'enabled', 'visibleDocuments']

let pass = 0
let fail = 0

function check(name, ok, detail) {
  console.log(`${ok ? '[OK]  ' : '[FAIL]'} ${name} —— ${detail}`)
  if (ok) pass += 1
  else fail += 1
}

function loadPasswords() {
  const envPath = join(ROOT, 'deploy', '.env')
  if (!existsSync(envPath)) throw new Error(`找不到 ${envPath}，无法取登录口令`)
  const text = readFileSync(envPath, 'utf8')
  const pick = (key) => {
    const line = text.split('\n').find((l) => l.trimStart().startsWith(`${key}=`))
    return line ? line.slice(line.indexOf('=') + 1).trim() : ''
  }
  return { admin: pick('ASSISTANT_ADMIN_PASSWORD'), demo: pick('ASSISTANT_DEMO_PASSWORD') }
}

function psql(sql) {
  return execFileSync('docker', ['exec', PG_CONTAINER, 'psql', '-U', 'assistant', '-d', 'assistant', '-tAc', sql],
    { encoding: 'utf8' }).trim()
}

async function call(method, path, token, body) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${API}/api${path}`, {
    method, headers, body: body ? JSON.stringify(body) : undefined
  })
  const text = await response.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON 也要能看原文 */ }
  return { status: response.status, json, text }
}

async function login(username, password) {
  const result = await call('POST', '/auth/login', null, { username, password })
  if (!result.json?.token) throw new Error(`登录失败：${username}（HTTP ${result.status}）`)
  return result.json.token
}

function resolveBrowser() {
  const exe = EDGE_CANDIDATES.find((candidate) => existsSync(candidate))
  if (!exe) throw new Error('找不到本机 Edge；可用环境变量 UI_BROWSER_EXE 指定浏览器可执行文件')
  return exe
}

async function uiLogin(page, username, password) {
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' })
  await page.fill('input[autocomplete="username"]', username)
  await page.fill('input[type="password"]', password)
  await page.click('button[type="submit"]')
  await page.waitForSelector('.topbar nav', { timeout: 20000 })
}

const passwords = loadPasswords()
const adminToken = await login('admin', passwords.admin)
const demoToken = await login('demo', passwords.demo)

// ---------- 1. 接口：给的是可见数 ----------
console.log('---------- ① GET /api/domains 只给可见数 ----------')

const adminDomains = (await call('GET', '/domains', adminToken)).json.domains
const demoDomains = (await call('GET', '/domains', demoToken)).json.domains
const adminDocs = (await call('GET', '/documents', adminToken)).json.documents
const demoDocs = (await call('GET', '/documents', demoToken)).json.documents

const byDomain = (domains) => Object.fromEntries(domains.map((d) => [d.code, d.visibleDocuments]))
const countPerDomain = (docs) => docs.reduce((acc, doc) => {
  acc[doc.domainCode] = (acc[doc.domainCode] || 0) + 1
  return acc
}, {})

const adminCounts = byDomain(adminDomains)
const demoCounts = byDomain(demoDomains)
console.log(`admin 可见：${JSON.stringify(adminCounts)}；demo 可见：${JSON.stringify(demoCounts)}`)
console.log(`admin 文档列表逐域计数：${JSON.stringify(countPerDomain(adminDocs))}；demo：${JSON.stringify(countPerDomain(demoDocs))}`)

check('AC-P1-009 可见数按当前身份算，不是文档总数（同一知识域 admin 比 demo 多）',
  adminCounts.policy > demoCounts.policy,
  `policy：admin=${adminCounts.policy}，demo=${demoCounts.policy}（差的那一份是 demo 无权访问的 QM-ZD-2026-021）`)

check('AC-P1-009 可见数与文档列表逐域对得上（两条独立路径，同一份 ACL 语义）',
  domainsMatch(demoCounts, countPerDomain(demoDocs)) &&
    domainsMatch(adminCounts, countPerDomain(adminDocs)),
  `demo：domains=${JSON.stringify(demoCounts)} documents=${JSON.stringify(countPerDomain(demoDocs))}；` +
    `admin：domains=${JSON.stringify(adminCounts)} documents=${JSON.stringify(countPerDomain(adminDocs))}`)

const extraFields = [...new Set([...adminDomains, ...demoDomains].flatMap((d) => Object.keys(d)))]
  .filter((key) => !DOMAIN_FIELDS.includes(key))
check('AC-P1-009 域对象没有多余字段（不给"总数 / 隐藏数"留位置）',
  extraFields.length === 0,
  extraFields.length === 0 ? `字段固定为 ${DOMAIN_FIELDS.join(' / ')}` : `多出字段：${extraFields.join(', ')}`)

const demoPolicy = demoDomains.find((d) => d.code === 'policy')
const totalLeak = Object.entries(demoPolicy).filter(([, value]) => value === adminCounts.policy)
check('AC-P1-009 demo 的响应里不出现"文档总数"这个数',
  totalLeak.length === 0,
  totalLeak.length === 0
    ? `policy 的全部字段值：${JSON.stringify(Object.values(demoPolicy))}（没有 ${adminCounts.policy}）`
    : `字段 ${totalLeak.map(([key]) => key).join(', ')} 的值等于 admin 的总数 ${adminCounts.policy}`)

function domainsMatch(a, b) {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)])
  return [...keys].every((key) => (a[key] || 0) === (b[key] || 0))
}

// ---------- 2. 造场景：技术文档域对 demo 全不可见 ----------
const techDocNos = adminDocs.filter((d) => d.domainCode === TARGET_DOMAIN).map((d) => d.docNo)
const techTitles = adminDocs.filter((d) => d.domainCode === TARGET_DOMAIN).map((d) => d.title)
let browser = null
let context = null
let extraIds = []

try {
  // 这个域平时的授权是 ALL/*（所有人可见），所以要造"仅含无权限文档"的场景，
  // 得同时做两件事：把 ALL 那行挪走，再给 admin 补一行 ROLE 授权——否则 admin 也看不见了，
  // 那样造出来的是"整个域坏了"，不是"这个人没权限"。
  const grantedIds = psql(`insert into document_acl (document_id, subject_type, subject_value, permission) ` +
    `select id, 'ROLE', 'admin', 'READ' from document where domain_code = '${TARGET_DOMAIN}' ` +
    `on conflict do nothing returning id`)
  // psql -tAc 在 RETURNING 之后还会补一行命令标签（"INSERT 0 2"），
  // 只认"整行都是数字"的行——否则 0 和 2 会被当成 id，把别人的授权行删掉
  extraIds = grantedIds.split('\n').map((line) => line.trim())
    .filter((line) => /^\d+$/.test(line)).map(Number)
  const changed = psql(`update document_acl set subject_value = '${SENTINEL}' ` +
    `where subject_type = 'ALL' and document_id in (select id from document where domain_code = '${TARGET_DOMAIN}')`)
  console.log(`\n---------- ② 临时撤销 ${TARGET_DOMAIN} 域对 demo 的访问权限 ----------`)
  console.log(`补授权行：${grantedIds.replace(/\s+/g, ', ')}；改走 ALL 授权：${changed}（哨兵 ${SENTINEL}）`)

  const demoAfter = (await call('GET', '/domains', demoToken)).json.domains
  const demoAfterCounts = byDomain(demoAfter)
  const adminAfter = (await call('GET', '/domains', adminToken)).json.domains

  check('AC-P1-009 该域可见数变为 0（"仅含无权限文档的知识域"造出来了）',
    demoAfterCounts[TARGET_DOMAIN] === 0,
    `demo：${JSON.stringify(demoAfterCounts)}`)
  check('AC-P1-009 同一时刻其他身份不受影响（可见数是按身份算的，不是全局缓存）',
    byDomain(adminAfter)[TARGET_DOMAIN] === adminCounts[TARGET_DOMAIN],
    `admin：${byDomain(adminAfter)[TARGET_DOMAIN]}（原值 ${adminCounts[TARGET_DOMAIN]}）`)

  // 真的问一句该域的问题：必须说成权限问题，且不出现该域文档的编号
  const ask = await call('POST', '/generation/answer', demoToken, {
    domainCode: TARGET_DOMAIN, question: 'CNC-850 主轴转速范围是多少', topN: 5
  })
  const leakText = techDocNos.filter((docNo) => ask.text.includes(docNo))
  // 权限话术有两种，都是设计内的，别把话说死：
  //   全部被挡下 → "命中的内容你都没有访问权限"（在 hint 里）
  //   部分被挡下 → "另有 N 条更相关的命中你无权访问"（在 accessNotice 里）
  // 判据只钉"理由是权限"，不钉具体句式；具体句式由 verify-retrieval / verify-generation 各自钉。
  check('AC-P1-003 该域全被挡下时，拒答理由必须是权限而不是"没找到"',
    ask.json?.refused === true && /权访问|访问权限/.test(ask.text) && !ask.text.includes('没有找到'),
    `refused=${ask.json?.refused}、含权限话术=${/权访问|访问权限/.test(ask.text)}、` +
      `含「没有找到」=${ask.text.includes('没有找到')}；原文：${ask.json?.answer || ask.text}`)
  console.log(`该域拒答原文：${ask.json?.answer || ask.text}`)
  check('AC-P1-009 拒答响应里不出现该域文档编号',
    leakText.length === 0,
    leakText.length === 0 ? `已核对 ${techDocNos.length} 个编号` : `漏出：${leakText.join(', ')}`)

  // ---------- 3. 界面：标出来、不可选、不泄露 ----------
  console.log('\n---------- ③ 问答页把"无可见文档"的域标出来 ----------')
  if (!existsSync(SHOTS)) mkdirSync(SHOTS, { recursive: true })
  browser = await chromium.launch({ executablePath: resolveBrowser(), headless: !HEADED })
  context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  await uiLogin(page, 'demo', passwords.demo)
  // <option> 天然不算"可见元素"，等它等于等一个永远不成立的条件；等"选项被填进来"这件事本身。
  await page.waitForFunction(() => document.querySelectorAll('.field.domain select option').length > 0,
    null, { timeout: 20000 })

  const ui = await page.evaluate(() => {
    const select = document.querySelector('.field.domain select')
    return {
      options: [...(select?.options || [])].map((option) => ({ text: option.innerText, disabled: option.disabled })),
      selected: select?.value || '',
      hint: document.querySelector('.field.domain .no-visible')?.innerText || '',
      body: document.body.innerText
    }
  })

  const blocked = ui.options.filter((option) => option.text.includes('无可见文档'))
  check('AC-P1-009 界面标出"无可见文档"的域',
    blocked.length === 1 && blocked[0].text.includes('技术文档'),
    `选项：${ui.options.map((o) => o.text).join(' | ')}`)
  check('AC-P1-009 该域不可选，且默认没有选中它',
    blocked.every((option) => option.disabled) && ui.selected !== TARGET_DOMAIN,
    `disabled=${blocked.map((o) => o.disabled).join(',')}、默认选中=${ui.selected}`)
  check('AC-P1-009 界面解释了这是权限问题、下一步该找谁',
    ui.hint.includes('技术文档') && ui.hint.includes('联系管理员'),
    ui.hint.replace(/\s+/g, ' '))
  const leaked = techDocNos.filter((docNo) => ui.body.includes(docNo))
    .concat(techTitles.filter((title) => ui.body.includes(title)))
  check('AC-P1-009 整页文本不出现该域文档的编号与标题',
    leaked.length === 0,
    leaked.length === 0 ? `已核对 ${techDocNos.length} 个编号、${techTitles.length} 个标题` : `漏出：${leaked.join(', ')}`)

  await page.screenshot({ path: join(SHOTS, '12-noperm-domain.png'), fullPage: true })
  console.log(`截图：${join(SHOTS, '12-noperm-domain.png')}`)
} finally {
  // 无论中间怎么炸，权限都要还原。哨兵是唯一键，按它复原既能覆盖全部被改的行，也不会误伤别的行。
  const restored = psql(`update document_acl set subject_value = '*' where subject_value = '${SENTINEL}'`)
  const left = psql(`select count(*) from document_acl where subject_value = '${SENTINEL}'`)
  // 只删"这次自己插进去的"那几行：按 id 删，别人原本就有的 ROLE 授权一行都不能动
  const removed = extraIds.length > 0
    ? psql(`delete from document_acl where id in (${extraIds.join(', ')})`)
    : 'DELETE 0'
  console.log(`\n---------- ④ 还原权限 ----------`)
  console.log(`复原 ALL 授权：${restored}；撤销补的 ROLE 授权：${removed}；残留哨兵行：${left}`)
  if (context) await context.close().catch(() => {})
  if (browser) await browser.close().catch(() => {})
}

const finalCounts = byDomain((await call('GET', '/domains', demoToken)).json.domains)
check('AC-P1-009 权限已还原，demo 可见数回到原值',
  JSON.stringify(finalCounts) === JSON.stringify(demoCounts),
  `${JSON.stringify(finalCounts)}`)

console.log(`\n通过 ${pass} 项，失败 ${fail} 项；截图在 ${SHOTS}`)
if (fail > 0) process.exitCode = 1