#!/usr/bin/env node
/**
 * T09 前端验收：登录 → 知识问答（SSE 流式）→ 点引用跳原文 → 两种宽度实拍。
 *
 * 覆盖：AC-P1-007（流式输出 + 引用卡片可点击）、AC-P1-008（点引用跳原文并高亮）、
 *       AC-P1-002 的界面呈现（拒答按"无法回答"渲染、不给引用卡片）、AC-P1-010（桌面 / 窄屏两种宽度）。
 *
 * 前置：后端 9090 与前端 dev server 5173 都在跑（cd frontend; npm run dev）。
 * 浏览器：用本机 Edge——playwright-core 不自带内核，也不下载内核，避免把 100MB 浏览器带进仓库。
 * 截图落在 target/ui/，失败时也留图：事后排查靠它，不靠回忆。
 *
 * 用法：node tools/verify-ask-page.mjs [--headed]
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

/** 口令只从 deploy\.env 读，不打印、不进日志。 */
function loadPasswords() {
  const envPath = join(ROOT, 'deploy', '.env')
  if (!existsSync(envPath)) {
    throw new Error(`找不到 ${envPath}，无法取登录口令`)
  }
  const text = readFileSync(envPath, 'utf8')
  const pick = (key) => {
    const line = text.split('\n').find((l) => l.trimStart().startsWith(`${key}=`))
    return line ? line.slice(line.indexOf('=') + 1).trim() : ''
  }
  return {
    admin: pick('ASSISTANT_ADMIN_PASSWORD'),
    demo: pick('ASSISTANT_DEMO_PASSWORD')
  }
}

function resolveBrowser() {
  const exe = EDGE_CANDIDATES.find((candidate) => existsSync(candidate))
  if (!exe) {
    throw new Error('找不到本机 Edge；可用环境变量 UI_BROWSER_EXE 指定浏览器可执行文件')
  }
  return exe
}

async function login(page, username, password) {
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' })
  await page.fill('input[autocomplete="username"]', username)
  await page.fill('input[type="password"]', password)
  await page.click('button[type="submit"]')
  await page.waitForSelector('.topbar nav', { timeout: 20000 })
}

/** 提问并采样正文长度，返回长度变化序列——"一次性出现"和"逐步长出来"在这里分开。 */
async function askAndSample(page, question, { watchSse, sseHits }) {
  if (watchSse) {
    page.on('response', (response) => {
      if (response.url().includes('/api/generation/answer/stream')) {
        sseHits.push({
          status: response.status(),
          contentType: response.headers()['content-type'] || ''
        })
      }
    })
  }
  await page.fill('.ask input', question)
  const started = Date.now()
  await page.click('.btn.primary.send')

  const samples = []
  let midShot = false
  for (let i = 0; i < 200; i += 1) {
    const state = await page.evaluate(() => ({
      length: document.querySelector('.answer')?.innerText.length || 0,
      pill: document.querySelector('.pill')?.innerText || ''
    }))
    samples.push({ at: Date.now() - started, length: state.length })
    if (!midShot && state.length > 40 && state.pill === '引用校验中') {
      await page.screenshot({ path: join(SHOTS, '01-streaming.png') })
      midShot = true
    }
    if (state.pill === '引用已核对' || state.pill === '已拒答') break
    if (await page.locator('.banner.err').count()) break
    await page.waitForTimeout(120)
  }
  await page.waitForTimeout(400)
  // 只保留长度发生变化的采样点：序列本身就是"流式"的证据
  const growth = samples.filter((s, i) => i === 0 || s.length !== samples[i - 1].length)
  return { samples, growth, midShot }
}

/**
 * 反复问同一个问题直到这一次被"放行"（最多 attempts 次）。
 *
 * 作答阈值（P2 起 0.51）是经验值，同一个问题偶尔会掉到阈值下被判"证据不足"，
 * 所以不赌一次。不变式是"权限提示一定送到用户眼前"：放行走提示条、拒答走正文，
 * 两条路都不许说"没找到"——那会把权限问题伪装成"库里没有"。
 */
async function askAllowingRetry(page, question, attempts) {
  let state = null
  let used = 0
  for (let i = 1; i <= attempts; i += 1) {
    used = i
    await askAndSample(page, question, { watchSse: false, sseHits: [] })
    state = await page.evaluate(() => ({
      pill: document.querySelector('.pill')?.innerText || '',
      answer: document.querySelector('.answer')?.innerText || '',
      banners: [...document.querySelectorAll('.banner.warn')].map((el) => el.innerText)
    }))
    if (state.pill === '引用已核对') break
  }
  const byBanner = state.banners.some((b) => b.includes('无权访问'))
  const byAnswer = state.answer.includes('无权访问')
  return {
    ...state,
    attempts: used,
    satisfied: (byBanner || byAnswer) && !state.answer.includes('没有找到'),
    route: byBanner ? '提示条' : byAnswer ? '正文' : '两条路都没出现权限说明'
  }
}

async function overflowAt(page, width) {
  await page.setViewportSize({ width, height: 900 })
  await page.waitForTimeout(350)
  return page.evaluate(() => {
    const doc = document.documentElement
    // 片段是"长引用"的代表：它自己不横向滚动，才算长引用可读
    const clipped = [...document.querySelectorAll('.frag, .citation')]
      .filter((el) => el.scrollWidth > el.clientWidth + 1)
      .map((el) => el.className)
    return {
      viewport: window.innerWidth,
      scrollWidth: doc.scrollWidth,
      overflow: doc.scrollWidth - window.innerWidth,
      clipped
    }
  })
}

async function main() {
  mkdirSync(SHOTS, { recursive: true })
  const passwords = loadPasswords()
  const executablePath = resolveBrowser()

  const browser = await chromium.launch({ executablePath, headless: !HEADED })
  const pageErrors = []
  const apiFailures = []
  const sseHits = []
  let context
  try {
    context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    const page = await context.newPage()
    page.on('pageerror', (error) => pageErrors.push(String(error)))
    page.on('response', (response) => {
      if (response.url().includes('/api/') && response.status() >= 400) {
        apiFailures.push(`${response.status()} ${response.url()}`)
      }
    })

    // 1. 登录后默认落在知识问答页
    await login(page, 'admin', passwords.admin)
    const nav = await page.locator('.topbar nav span').allInnerTexts()
    const active = await page.locator('.topbar nav span.active').allInnerTexts()
    check('登录后默认进入知识问答', active[0] === '知识问答', `导航=${nav.join('/')}，高亮=${active.join('/')}`)
    check('知识域下拉列出三个知识域', (await page.locator('.ask select option').count()) === 3,
      (await page.locator('.ask select option').allInnerTexts()).join('、'))
    await page.screenshot({ path: join(SHOTS, '00-ask-idle.png') })

    // 2. 库内问题：AC-P1-007
    const main = await askAndSample(page, '出差住宿费标准是多少', { watchSse: true, sseHits })
    const grew = main.growth.map((s) => s.length)
    check('正文是流式长出来的，不是一次性出现', grew.length >= 3 && grew[0] < grew[grew.length - 1],
      `采样长度序列=${grew.join('→')}`)
    check('流式过程中先给"引用校验中"，判定完才出结论',
      main.samples.some((s) => s.length > 0) && (await page.locator('.pill.ok').count()) === 1,
      `终态=${await page.locator('.pill').innerText()}`)
    check('SSE 响应为 text/event-stream', sseHits.length >= 1 && sseHits[0].contentType.includes('text/event-stream'),
      sseHits.length ? `HTTP ${sseHits[0].status} ${sseHits[0].contentType}` : '没有抓到流式请求')

    const citations = await page.locator('.citations .citation').allInnerTexts()
    const citationCount = await page.locator('.citations .citation').count()
    check('AC-P1-007 答案带可点击的引用卡片', citationCount >= 1,
      `引用卡片 ${citationCount} 张：${citations.map((c) => c.replace(/\n/g, ' ')).join(' | ')}`)
    const citeButtons = await page.locator('.answer .cite').count()
    check('正文里的 [n] 渲染成可点击按钮', citeButtons >= 1, `正文引用按钮 ${citeButtons} 个`)
    check('加粗标记已被消化，正文不残留 Markdown 星号',
      (await page.locator('.answer').innerText()).includes('**') === false,
      `加粗片段 ${await page.locator('.answer .bold').count()} 处`)

    // 3. AC-P1-008：点引用卡片 → 展开证据 → 高亮并滚动到该片段
    const collapsed = await page.locator('.evidence-item').count()
    await page.locator('.citations .citation').nth(citationCount - 1).click()
    await page.waitForTimeout(900)
    const jump = await page.evaluate(() => {
      const hits = [...document.querySelectorAll('.frag.hit')]
      // 一次问答里被引用的文档可能有多段命中，openCitation 只把被点的那一段滚进视口，
      // 所以这里判"有没有一段完整落在视口内"，而不是盯住第一段。
      const rects = hits.map((el) => {
        const rect = el.getBoundingClientRect()
        return { id: el.id, top: Math.round(rect.top), bottom: Math.round(rect.bottom) }
      })
      const landed = rects.find((r) => r.top >= -8 && r.bottom <= window.innerHeight + 8)
      return {
        scrollY: Math.round(window.scrollY),
        evidenceItems: document.querySelectorAll('.evidence-item').length,
        hitCount: hits.length,
        highlight: hits[0] ? getComputedStyle(hits[0]).backgroundColor : '',
        landed: landed || null,
        rects,
        activeCitations: document.querySelectorAll('.citation.active').length,
        activeMarks: document.querySelectorAll('.cite.active').length
      }
    })
    check('AC-P1-008 点引用后证据面板自动展开', collapsed === 0 && jump.evidenceItems > 0,
      `折叠时 ${collapsed} 份 → 展开后 ${jump.evidenceItems} 份`)
    check('AC-P1-008 目标片段被高亮', jump.hitCount >= 1 && jump.highlight !== 'rgba(0, 0, 0, 0)',
      `${jump.hitCount} 个片段高亮，底色 ${jump.highlight}`)
    check('AC-P1-008 视口滚动到该片段',
      jump.landed !== null && jump.scrollY > 0,
      `像素位置=${jump.rects.map((r) => `${r.top}~${r.bottom}`).join(' , ')}（视口高 900，scrollY=${jump.scrollY}）`)
    check('被点的引用卡片与正文编号同步高亮', jump.activeCitations === 1 && jump.activeMarks >= 1,
      `卡片 ${jump.activeCitations} 张、正文按钮 ${jump.activeMarks} 个`)
    await page.screenshot({ path: join(SHOTS, '03-citation-jump.png'), fullPage: true })

    // 3.5 AC-P2-008：引用卡片与证据片段都要显示页码。
    // 页码来自**我们自己的解析结果**（服务端按 [n] 反查 chunk），不是模型写的——
    // 所以它必须出现在界面上、两处一致，而且要能指到刚才点开的那一段。
    await page.evaluate(() => document.querySelector('.citations')?.scrollIntoView({ block: 'start' }))
    await page.waitForTimeout(250)
    const pageState = await page.evaluate(() => {
      const text = (el) => (el ? el.innerText.trim() : '')
      const cards = [...document.querySelectorAll('.citations .citation')].map((c) => ({
        text: c.innerText.replace(/\n/g, ' '),
        page: text([...c.querySelectorAll('.tag')].find((tag) => /第\s*\d/.test(tag.innerText)))
      }))
      const frags = [...document.querySelectorAll('.frag')].map((f) => ({
        id: f.id,
        page: text(f.querySelector('.frag-page')),
        text: f.innerText.slice(0, 24)
      }))
      return { cards, frags }
    })
    const cardsWithPage = pageState.cards.filter((c) => /^第\s*\d+(-\d+)?\s*页$/.test(c.page))
    const fragsWithPage = pageState.frags.filter((f) => f.page !== '')
    check('AC-P2-008 引用卡片上显示页码', cardsWithPage.length >= 1,
      `${cardsWithPage.length}/${pageState.cards.length} 张卡片带页码：${pageState.cards.map((c) => `${c.page || '无页码'}｜${c.text}`).join(' ｜ ')}`)
    check('AC-P2-008 证据片段上也显示页码', fragsWithPage.length >= 1,
      `${fragsWithPage.length}/${pageState.frags.length} 段片段带页码：${fragsWithPage.map((f) => `${f.page} → ${f.text}…`).join(' ｜ ')}`)
    // 两处页码必须一致：卡片说第 1 页、片段说第 3 页，比不显示更糟——用户会照着翻，然后发现没有
    const cardPages = new Set(cardsWithPage.map((c) => c.page))
    const fragPages = new Set(fragsWithPage.map((f) => f.page))
    check('AC-P2-008 卡片与片段的页码一致', cardPages.size >= 1 && [...cardPages].every((p) => fragPages.has(p)),
      `卡片页码=${[...cardPages].join('/') || '（无）'}；片段页码=${[...fragPages].join('/') || '（无）'}`)
    const clickedIds = new Set(jump.rects.map((r) => r.id))
    const clickedWithPage = fragsWithPage.filter((f) => clickedIds.has(f.id))
    check('AC-P2-008 点引用定位到的那段片段本身带页码', clickedWithPage.length >= 1,
      `定位到的片段=${[...clickedIds].join(',') || '（无）'}；其中带页码=${clickedWithPage.map((f) => f.page).join('/') || '（无）'}`)
    await page.screenshot({ path: join(SHOTS, '09-p2-citation-page.png'), fullPage: true })
    // 4. AC-P1-010：桌面与窄屏两种宽度，长引用都不能被截断
    const wide = await overflowAt(page, 1440)
    await page.screenshot({ path: join(SHOTS, '05-desktop-1440.png'), fullPage: true })
    const narrow = await overflowAt(page, 420)
    await page.screenshot({ path: join(SHOTS, '06-narrow-420-top.png') })
    await page.evaluate(() => document.querySelector('.citations')?.scrollIntoView({ block: 'start' }))
    await page.waitForTimeout(250)
    await page.screenshot({ path: join(SHOTS, '07-narrow-420-citations.png') })
    await page.evaluate(() => document.querySelector('.evidence')?.scrollIntoView({ block: 'start' }))
    await page.waitForTimeout(250)
    await page.screenshot({ path: join(SHOTS, '08-narrow-420-evidence.png') })
    check('AC-P1-010 桌面 1440 无横向溢出', wide.overflow <= 0, `scrollWidth=${wide.scrollWidth} viewport=${wide.viewport}`)
    check('AC-P1-010 窄屏 420 无横向溢出', narrow.overflow <= 0,
      `scrollWidth=${narrow.scrollWidth} viewport=${narrow.viewport}`)
    check('AC-P1-010 长引用与引用卡片都没有被截断',
      wide.clipped.length === 0 && narrow.clipped.length === 0,
      `溢出元素：桌面 ${wide.clipped.length} 个、窄屏 ${narrow.clipped.length} 个`)

    // 5. 库外问题：拒答在界面上的呈现（AC-P1-002 的前端部分）
    await page.setViewportSize({ width: 1440, height: 900 })
    await askAndSample(page, '公司食堂今天的菜单是什么', { watchSse: false, sseHits })
    const refused = await page.evaluate(() => ({
      pill: document.querySelector('.pill').innerText,
      heading: document.querySelector('.card-head h2').innerText,
      answer: document.querySelector('.answer').innerText,
      color: getComputedStyle(document.querySelector('.answer')).color,
      citationCards: document.querySelectorAll('.citations .citation').length
    }))
    check('库外问题按"无法回答"渲染', refused.pill === '已拒答' && refused.heading === '无法回答',
      `标记=${refused.pill} 标题=${refused.heading}`)
    check('拒答文案明确说无法回答且带红字提示', refused.answer.includes('无法可靠回答') && refused.color === 'rgb(163, 51, 51)',
      `正文色=${refused.color}`)
    check('拒答不给引用卡片', refused.citationCards === 0, `引用卡片 ${refused.citationCards} 张`)
    await page.screenshot({ path: join(SHOTS, '04-refused.png'), fullPage: true })

      // 5.5 AC-P2-009：DOCX 没有页码语义 → 界面不许显示猜测的页码。
      // 这一条和 AC-P2-008 是一对：上面证明"PDF 该显示的有显示"，这里证明"没有的不编"。
      // 少了任一条，另一个都可能被"页码功能整个没生效"糊弄过去。
      await page.selectOption('.ask select', 'tech')
      await askAndSample(page, '液压站 HYZ-40 的系统工作压力是多少，溢流阀设定多少', { watchSse: false, sseHits })
      // 每次提问后证据面板都会收起（showEvidence 初值 false），片段不在 DOM 里；
      // 点一下这份文档的引用卡片展开它——顺带把"点引用能落到这份文档的片段上"也走一遍
      const docxCard = page.locator('.citations .citation', { hasText: 'QM-JS-HYZ40-2026' }).first()
      if ((await docxCard.count()) > 0) {
        await docxCard.click()
        await page.waitForTimeout(800)
      }
      const docxState = await page.evaluate(() => {
        const docNo = 'QM-JS-HYZ40-2026'
        const cards = [...document.querySelectorAll('.citations .citation')]
          .filter((c) => c.innerText.includes(docNo))
          .map((c) => ({
            text: c.innerText.replace(/\n/g, ' '),
            pages: [...c.querySelectorAll('.tag')].filter((t) => /第\s*\d/.test(t.innerText)).map((t) => t.innerText)
          }))
        const item = [...document.querySelectorAll('.evidence-item')].find((el) => el.innerText.includes(docNo))
        const frags = item ? [...item.querySelectorAll('.frag')] : []
        return {
          cards,
          fragmentCount: frags.length,
          pageLabels: frags.map((f) => (f.querySelector('.frag-page')?.innerText || '')).filter(Boolean),
          firstFragment: frags[0] ? frags[0].innerText.slice(0, 30) : ''
        }
      })
      check('AC-P2-009 DOCX 文档出现在证据里（否则这条查的是空集）', docxState.fragmentCount >= 1,
        `DOCX 片段 ${docxState.fragmentCount} 段、引用卡片 ${docxState.cards.length} 张；首段：${docxState.firstFragment}…`)
      check('AC-P2-009 DOCX 片段不显示页码', docxState.pageLabels.length === 0,
        docxState.pageLabels.length ? `不该出现的页码：${docxState.pageLabels.join('、')}` : 'DOCX 片段一个页码都没显示')
      check('AC-P2-009 DOCX 引用卡片不显示页码', docxState.cards.every((c) => c.pages.length === 0),
        docxState.cards.map((c) => `${c.pages.join('') || '无页码'}｜${c.text}`).join(' ｜ ') || '没有该文档的引用卡片')
      await page.screenshot({ path: join(SHOTS, '09-p2-docx-nopage.png'), fullPage: true })
      // 6. 换 viewer 身份：notice 必须露出来（§10.9 点名要验的第三条），且无权限文档不许露出来
      const demoSession = await browser.newContext({ viewport: { width: 1440, height: 900 } })
      try {
        const demoPage = await demoSession.newPage()
        demoPage.on('pageerror', (error) => pageErrors.push(`demo: ${error}`))
        await login(demoPage, 'demo', passwords.demo)

        // 作答阈值（P2 起 0.51）是经验值，同一个问题偶尔会掉到阈值下被判"证据不足"。
        // 所以这条不赌一次：先确认"权限提示一定送到用户眼前"的不变式，
        // 再有限重试，把"放行 + 提示条"这条界面路径真正走出来。
        const notice = await askAllowingRetry(demoPage, '报销需要哪些单据', 4)
        check('AC-P1-003 权限提示一定送到用户眼前（放行看提示条，拒答看正文）',
          notice.satisfied,
          `标记=${notice.pill}；${notice.route}`)
        check('AC-P1-003 放行时权限提示以提示条显示',
          notice.banners.some((b) => b.includes('无权访问')),
          `标记=${notice.pill} 提示=${notice.banners.join(' / ') || '（没有提示条）'}；重试 ${notice.attempts} 次`)
        check('权限提示不暴露接口参数（不说"少于请求的 N 份"）',
          notice.banners.every((b) => !b.includes('请求') && !b.includes('少于')),
          notice.banners.join(' / ') || '（没有提示条）')
        await demoPage.screenshot({ path: join(SHOTS, '10-demo-notice.png'), fullPage: true })

        await askAndSample(demoPage, '2026年度薪酬结构调整方案的调薪幅度是多少', { watchSse: false, sseHits })
        const leak = await demoPage.evaluate(() => {
          const text = document.body.innerText
          return {
            pill: document.querySelector('.pill')?.innerText || '',
            answer: document.querySelector('.answer')?.innerText || '',
            citationCards: document.querySelectorAll('.citations .citation').length,
            evidenceItems: document.querySelectorAll('.evidence-item').length,
            hasSecretTitle: text.includes('薪酬'),
            hasSecretDocNo: text.includes('QM-ZD-2026-021')
          }
        })
        check('AC-P1-009 无权限文档整页不出现（标题、编号、卡片、证据）',
          !leak.hasSecretTitle && !leak.hasSecretDocNo && leak.citationCards === 0 && leak.evidenceItems === 0,
          `页面含"薪酬"=${leak.hasSecretTitle}、含编号=${leak.hasSecretDocNo}、引用卡片=${leak.citationCards}、证据=${leak.evidenceItems}`)
        check('AC-P1-003 无权限问题说明是权限原因，不说"没找到"',
          leak.pill === '已拒答' && leak.answer.includes('无权访问') && !leak.answer.includes('没有找到'),
          `标记=${leak.pill}`)
        await demoPage.screenshot({ path: join(SHOTS, '11-demo-noperm.png'), fullPage: true })
      } finally {
        await demoSession.close().catch(() => {})
      }

      // 结论性检查放最后：前面的每一段都可能往里塞异常，早判会漏
      check('全程没有 JS 异常', pageErrors.length === 0, pageErrors.join(' | ') || '无')
      check('全程没有失败的 /api 请求', apiFailures.length === 0, apiFailures.join(' | ') || '无')
  } finally {
    if (context) await context.close().catch(() => {})
    await browser.close().catch(() => {})
  }
}

main()
  .then(() => {
    console.log(`\n通过 ${pass} 项，失败 ${fail} 项；截图在 ${SHOTS}`)
    if (fail > 0) process.exitCode = 1
  })
  .catch((error) => {
    console.error(`\n[FAIL] 验收脚本中断 —— ${error.message}`)
    console.error(`已通过 ${pass} 项，失败 ${fail} 项`)
    process.exitCode = 1
  })
