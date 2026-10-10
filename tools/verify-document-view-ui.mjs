#!/usr/bin/env node
/**
 * §13 文档查看的界面验收：文档表入口 → 查看器（原文 / Markdown）→ 引用跳原文。
 *
 * 覆盖：G1 的界面呈现（解析状态出现在文档表）、AC-D-002/AC-D-003（原文可看）、
 *       AC-D-005（点引用"看原文"落到对应位置并高亮）、AC-D-009 之外的两个下载口径。
 *
 * 前置：后端 9090 与前端 dev server 5173 都在跑（cd frontend; npm run dev）。
 * 浏览器用本机 Edge：playwright-core 不自带内核。截图落 target/ui/。
 *
 * 用法：node tools/verify-document-view-ui.mjs [--headed]
 */

import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { chromium } from 'playwright-core'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..')
const SHOTS = join(ROOT, 'target', 'ui')
const BASE = process.env.UI_BASE || 'http://127.0.0.1:5173'
const HEADED = process.argv.includes('--headed')

const EDGE_CANDIDATES = [
  process.env.UI_BROWSER_EXE,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe'
].filter(Boolean)

let pass = 0
let fail = 0

function check(name, ok, detail) {
  console.log(`${ok ? '[OK]  ' : '[FAIL]'} ${name} —— ${detail}`)
  if (ok) pass += 1
  else fail += 1
}

function loadPasswords() {
  const envPath = join(ROOT, 'deploy', '.env')
  if (!existsSync(envPath)) throw new Error(`找不到 ${envPath}`)
  const text = readFileSync(envPath, 'utf8')
  const pick = (key) => {
    const line = text.split('\n').find((l) => l.trimStart().startsWith(`${key}=`))
    return line ? line.slice(line.indexOf('=') + 1).trim() : ''
  }
  return { admin: pick('ASSISTANT_ADMIN_PASSWORD'), demo: pick('ASSISTANT_DEMO_PASSWORD') }
}

function resolveBrowser() {
  const exe = EDGE_CANDIDATES.find((candidate) => existsSync(candidate))
  if (!exe) throw new Error('找不到本机 Edge；可用 UI_BROWSER_EXE 指定')
  return exe
}

async function login(page, username, password) {
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' })
  await page.fill('input[autocomplete="username"]', username)
  await page.fill('input[type="password"]', password)
  await page.click('button[type="submit"]')
  await page.waitForSelector('.topbar nav', { timeout: 20000 })
}

async function openIngest(page) {
  await page.click('.topbar nav span:has-text("文档与同步")')
  await page.waitForSelector('.grid tbody tr', { timeout: 20000 })
}

async function main() {
  mkdirSync(SHOTS, { recursive: true })
  const passwords = loadPasswords()
  const browser = await chromium.launch({ executablePath: resolveBrowser(), headless: !HEADED })
  const pageErrors = []
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 950 }, acceptDownloads: true })
    const page = await context.newPage()
    page.on('pageerror', (error) => pageErrors.push(String(error)))

    await login(page, 'admin', passwords.admin)

    // ---------- G1：解析状态出现在文档表 ----------
    await openIngest(page)
    const headers = await page.locator('.grid').first().locator('thead th').allInnerTexts()
    check('文档表新增「解析」列（自建片段状态）', headers.includes('解析'), `表头=${headers.join('/')}`)
    const docxRow = page.locator('.grid tbody tr', { hasText: 'QM-JS-HYZ40-2026' })
    const docxCells = await docxRow.innerText()
    check('DOCX 行显示 chunk 状态与片段数', /INDEXED/.test(docxCells) && /\d+\s*段/.test(docxCells),
      docxCells.replace(/\n/g, ' | '))
    await page.screenshot({ path: join(SHOTS, '10-ingest-parse-column.png') })

    // ---------- AC-D-003：DOCX 原文（转 PDF）在查看器里可读 ----------
    await docxRow.locator('button:has-text("查看")').click()
    await page.waitForSelector('.modal', { timeout: 20000 })
    await page.waitForSelector('.modal iframe.pdf', { timeout: 30000 })
    const iframeSrc = await page.locator('.modal iframe.pdf').getAttribute('src')
    const meta = await page.locator('.modal .head').innerText()
    check('查看器用 blob 载入带水印 PDF（不是直链）', String(iframeSrc).startsWith('blob:'), String(iframeSrc).slice(0, 24) + '…')
    check('查看器显示本次水印（账号 + 当天）', /admin\s+\d{4}-\d{2}-\d{2}/.test(meta), meta.replace(/\n/g, ' | '))
    await page.waitForTimeout(1200)
    await page.screenshot({ path: join(SHOTS, '11-viewer-pdf.png') })

    // ---------- Markdown 页签 ----------
    await page.click('.modal .tabs span:has-text("Markdown")')
    await page.waitForSelector('.modal .md', { timeout: 10000 })
    const headings = await page.locator('.modal .md h2, .modal .md h3, .modal .md h4').allInnerTexts()
    check('Markdown 视图渲染出章节标题', headings.length >= 1, headings.slice(0, 5).join(' / '))
    const sectionHeads = await page.locator('.modal .md .section-head').count()
    check('章节层级来自 section_path（独立小节行）', sectionHeads >= 1, `小节行 ${sectionHeads} 处`)
    await page.screenshot({ path: join(SHOTS, '12-viewer-markdown.png') })

    // ---------- 下载：统一给 PDF ----------
    const download = await Promise.all([
      page.waitForEvent('download', { timeout: 30000 }),
      page.click('.modal .head-actions button:has-text("下载 PDF")')
    ]).then(([d]) => d)
    const suggested = download.suggestedFilename()
    check('下载 PDF：文件名以 .pdf 结尾', suggested.endsWith('.pdf'), suggested)
    await page.click('.modal .head-actions button:has-text("关闭")')
    await page.waitForSelector('.modal', { state: 'detached', timeout: 10000 })

    // ---------- AC-D-005：问答引用卡片「看原文」 ----------
    await page.click('.topbar nav span:has-text("知识问答")')
    await page.waitForSelector('.ask', { timeout: 10000 })
    let citations = 0
    for (let attempt = 1; attempt <= 3 && citations === 0; attempt += 1) {
      await page.fill('.ask input', '出差住宿费标准是多少')
      await page.click('.btn.primary.send')
      for (let i = 0; i < 300; i += 1) {
        const pill = await page.locator('.pill').first().innerText().catch(() => '')
        if (pill === '引用已核对' || pill === '已拒答') break
        if (await page.locator('.banner.err').count()) break
        await page.waitForTimeout(150)
      }
      citations = await page.locator('.citations .citation').count()
    }
    check('答案带引用卡片（有引用才能验证跳转）', citations >= 1, `引用卡片 ${citations} 张`)
    if (citations >= 1) {
      await page.locator('.citation-row .btn.tiny').first().click()
      await page.waitForSelector('.modal', { timeout: 20000 })
      await page.waitForSelector('.modal .md', { timeout: 20000 })
      const activeTab = await page.locator('.modal .tabs span.active').innerText()
      const hits = await page.locator('.modal .md .hit').count()
      check('AC-D-005 「看原文」落在 Markdown 页签', activeTab === 'Markdown', `当前页签=${activeTab}`)
      check('AC-D-005 被引用的片段被高亮', hits === 1, `高亮片段 ${hits} 处`)
      // 平滑滚动有动画，等它落位再截图，否则拍到的是滚动前的画面
      await page.waitForTimeout(900)
      await page.screenshot({ path: join(SHOTS, '13-viewer-jump-highlight.png') })
      await page.click('.modal .head-actions button:has-text("关闭")')
    }

    // ---------- 权限：viewer 看不到秘密文档 ----------
    // 退出按钮在右上角 .who 里，不在主导航里
    await page.click('.who button:has-text("退出")')
    await login(page, 'demo', passwords.demo)
    await openIngest(page)
    const demoRows = await page.locator('.grid tbody tr').count()
    const demoText = await page.locator('.grid').first().innerText()
    check('viewer 的文档表不含秘密文档', demoRows === 7 && !demoText.includes('薪酬'), `行数=${demoRows}`)
    await page.screenshot({ path: join(SHOTS, '14-viewer-demo-list.png') })

    check('界面全程无 JS 异常', pageErrors.length === 0, pageErrors.length ? pageErrors.join(' | ') : '无')
  } finally {
    await browser.close()
  }

  console.log(`\n通过 ${pass} 项，失败 ${fail} 项；截图在 ${SHOTS}`)
  if (fail > 0) process.exit(1)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
