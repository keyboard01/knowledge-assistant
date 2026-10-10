// 页码标注只在这一个地方定义：引用标签、证据片段、账本三处显示的是同一个页码。
// 各写各的早晚会不一致，而"页码对不上"正是引用可信度崩塌的方式。
//
// 规则：没有页码就返回空串——界面宁可不显示。编一个页码比没有页码更糟，
// 用户会照着它去翻原文，然后发现那里根本没有这句话。
export function pageLabel(item) {
  if (!item || item.pageFrom === null || item.pageFrom === undefined) return ''
  const from = item.pageFrom
  const to = item.pageTo
  if (to === null || to === undefined || to === from) return `第 ${from} 页`
  return `第 ${from}-${to} 页`
}