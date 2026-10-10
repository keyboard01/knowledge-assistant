# -*- coding: utf-8 -*-
"""
生成「OA 流程」测试语料的 PDF（采购流程 / 报销流程 / OA 审批流程）。

用途：给一个空知识域造几份可解析、可检索的中文文档，用来演示/验收
"接入 → 解析 → 检索 → 问答"这条链路。内容里埋了可验证的具体事实
（金额分级、审批链、时限、城市分档标准），方便出题。

依赖：reportlab（本机已有）+ Windows 中文字体 simhei.ttf。
用法：python tools/make-oa-corpus.py
产物：knowledge/OA流程/*.pdf（另外需在 knowledge/manifest.json 里登记，域代码 oa）
"""
import os

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (PageBreak, Paragraph, SimpleDocTemplate, Spacer,
                                Table, TableStyle)

FONT = 'SimHei'
_FONT_FILE = r'C:\Windows\Fonts\simhei.ttf'

H1 = ParagraphStyle('h1', fontName=FONT, fontSize=16, leading=24, alignment=1, spaceAfter=10)
H2 = ParagraphStyle('h2', fontName=FONT, fontSize=12.5, leading=18, spaceBefore=10, spaceAfter=4)
BODY = ParagraphStyle('body', fontName=FONT, fontSize=10.5, leading=16, wordWrap='CJK', spaceAfter=4)
LI = ParagraphStyle('li', parent=BODY, leftIndent=14, spaceAfter=2)
NOTE = ParagraphStyle('note', parent=BODY, textColor=colors.HexColor('#555555'), fontSize=10)


def footer(canvas, doc):
    canvas.saveState()
    canvas.setFont(FONT, 9)
    canvas.setFillColor(colors.HexColor('#888888'))
    canvas.drawCentredString(A4[0] / 2, 12 * mm, f'第 {doc.page} 页')
    canvas.restoreState()


def build(path, title, blocks):
    doc = SimpleDocTemplate(
        path, pagesize=A4,
        leftMargin=20 * mm, rightMargin=20 * mm, topMargin=18 * mm, bottomMargin=18 * mm,
        title=title, author='企业知识助手测试语料',
    )
    story = [Paragraph(title, H1), Spacer(1, 4)]
    for kind, payload in blocks:
        if kind == 'h2':
            story.append(Paragraph(payload, H2))
        elif kind == 'p':
            story.append(Paragraph(payload, BODY))
        elif kind == 'li':
            story.append(Paragraph('• ' + payload, LI))
        elif kind == 'note':
            story.append(Paragraph(payload, NOTE))
        elif kind == 'table':
            header, rows = payload
            data = [header] + rows
            table = Table(data, colWidths=[28 * mm, 62 * mm, 70 * mm], hAlign='LEFT')
            table.setStyle(TableStyle([
                ('FONTNAME', (0, 0), (-1, -1), FONT),
                ('FONTSIZE', (0, 0), (-1, -1), 10),
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#eef2f9')),
                ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor('#c9d3e0')),
                ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
                ('LEFTPADDING', (0, 0), (-1, -1), 6),
                ('RIGHTPADDING', (0, 0), (-1, -1), 6),
                ('TOPPADDING', (0, 0), (-1, -1), 4),
                ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
            ]))
            story.append(Spacer(1, 4))
            story.append(table)
            story.append(Spacer(1, 6))
        elif kind == 'break':
            story.append(PageBreak())
    doc.build(story, onFirstPage=footer, onLaterPages=footer)


PROCUREMENT = [
    ('h2', '第一章 目的与适用范围'),
    ('p', '第一条 为规范公司采购活动、控制采购成本与风险，明确各环节审批权限，制定本流程。'),
    ('p', '第二条 本流程适用于公司及所属单位使用公司资金进行的一切物资、设备、工程与服务的采购。'
          '员工个人报销性质的支出见《费用报销管理流程》，不适用本流程。'),

    ('h2', '第二章 职责分工'),
    ('li', '需求部门：提出采购申请，明确规格、数量与到货时间，参与验收。'),
    ('li', '采购部：组织询比价、选择供应商、签订合同、跟催到货。'),
    ('li', '财务部：审核预算与付款条件，办理付款结算。'),
    ('li', '分管副总 / 总经理办公会：按金额分级行使审批权。'),

    ('h2', '第三章 金额分级审批权限'),
    ('p', '第三条 采购金额以含税总额为准，按下列档级确定审批权限：'),
    ('table', (
        ['档级', '单笔金额（含税，人民币）', '审批权限'],
        [
            ['一档', '50,000 元及以下', '部门经理审批'],
            ['二档', '50,001 元 ~ 200,000 元', '部门经理 + 分管副总审批'],
            ['三档', '200,001 元 ~ 1,000,000 元', '部门经理 + 分管副总 + 财务总监审批'],
            ['四档', '1,000,000 元以上', '总经理办公会集体决策'],
        ],
    )),
    ('p', '第四条 同一事项不得为规避审批权限而拆分采购申请。'),

    ('break', None),
    ('h2', '第四章 采购流程步骤'),
    ('p', '第五条 采购按下列顺序执行：'),
    ('li', '需求提出与采购申请：填写《采购申请单》，注明预算来源。'),
    ('li', '询比价：单笔金额 100,000 元及以上的，必须取得三家及以上供应商报价。'),
    ('li', '供应商确定：综合价格、质量、交期与售后，填写比价结果说明。'),
    ('li', '合同评审：合同条款经法务与财务会签后，由授权人在权限内签署。'),
    ('li', '到货验收：需求部门与采购部共同验收，出具《验收单》。'),
    ('li', '付款结算：凭合同、发票与《验收单》办理付款。'),

    ('h2', '第五章 付款与合同'),
    ('p', '第六条 验收合格后 30 日内支付款项；预付款不得超过合同金额的 30%。'),
    ('p', '第七条 合同金额 500,000 元以上的，须约定质保金，质保期不少于 12 个月。'),

    ('h2', '第六章 附则'),
    ('p', '第八条 本流程由采购部负责解释，自 2026 年 1 月 1 日起施行。'),
]

REIMBURSEMENT = [
    ('h2', '第一章 适用范围与报销类别'),
    ('p', '第一条 本流程适用于员工因公发生的费用报销，包括差旅费、业务招待费、办公用品与市内交通费。'),
    ('p', '第二条 报销遵循"先审批后发生、票据真实、标准内据实"的原则。'),

    ('h2', '第二章 差旅费标准'),
    ('p', '第三条 出差住宿费按出差地城市分档限额，伙食与市内交通实行定额补助：'),
    ('table', (
        ['城市分档', '城市举例', '住宿限额 / 伙食补助'],
        [
            ['一线城市', '北京、上海、广州、深圳', '住宿 ≤ 500 元/晚；伙食 100 元/天'],
            ['二线城市', '各省省会城市', '住宿 ≤ 350 元/晚；伙食 80 元/天'],
            ['其他城市', '其余地级市及以下', '住宿 ≤ 250 元/晚；伙食 60 元/天'],
        ],
    )),
    ('p', '第四条 超标部分原则上自理；因特殊情况超标的，须在报销单中说明并经分管副总批准。'),

    ('break', None),
    ('h2', '第三章 报销审批链'),
    ('p', '第五条 报销单按下列顺序流转审批：'),
    ('li', '申请人提交报销单并附原始票据。'),
    ('li', '直属主管审核事项真实性与必要性。'),
    ('li', '部门负责人审核预算与标准。'),
    ('li', '财务审核票据合规性与金额。'),
    ('li', '单笔报销金额 5,000 元及以上的，须再经财务总监审批。'),
    ('li', '出纳办理付款，款项打入员工工资卡。'),

    ('h2', '第四章 报销时限与票据要求'),
    ('p', '第六条 费用发生后 30 日内提交报销；跨年度费用须在次年 1 月 15 日前完成报销，逾期原则上不予受理。'),
    ('p', '第七条 票据须为合法有效的增值税发票或电子发票，抬头为公司全称，开票内容与报销事项一致；'
          '无票据或票据不合规的不予报销。'),

    ('h2', '第五章 附则'),
    ('p', '第八条 本流程由财务部负责解释，自 2026 年 1 月 1 日起施行。'),
]

OA_APPROVAL = [
    ('h2', '第一章 目的与适用范围'),
    ('p', '第一条 本说明规范公司 OA 系统中各类审批流程的配置与使用，明确流程要素、节点类型与审批时限。'),
    ('p', '第二条 公司所有线上审批（采购、报销、请假、用章、合同等）统一在 OA 系统的审批中心发起与流转。'),

    ('h2', '第二章 流程要素'),
    ('p', '第三条 一条审批流程由下列要素组成：'),
    ('li', '发起人：流程的提交人，可查看本人发起的全部流程与流转记录。'),
    ('li', '审批节点：需要指定人员做出"同意 / 驳回"处理的节点。'),
    ('li', '知会节点：仅通知相关人员查看，不阻塞流程，也无需处理。'),
    ('li', '抄送 / 归档：流程结束后进入归档，便于查询与审计。'),
    ('li', '条件分支：按金额、部门、假别等条件自动选择后续审批路径。'),

    ('h2', '第三章 节点类型与处理规则'),
    ('table', (
        ['节点类型', '是否需要处理', '说明'],
        [
            ['审批节点', '需要', '任一或全部审批人同意后流转下一节点'],
            ['知会节点', '不需要', '仅推送通知，便于相关人知晓'],
            ['抄送节点', '不需要', '流程结束后可归档可见'],
        ],
    )),
    ('p', '第四条 审批人可在节点上执行加签、转办或委托；委托仅在指定时间段内生效。'),

    ('break', None),
    ('h2', '第四章 审批时限与提醒'),
    ('p', '第五条 普通审批节点处理时限为 3 个工作日；超时系统自动提醒审批人。'),
    ('p', '第六条 单个节点超过 5 个工作日未处理的，流程自动升级提醒至该审批人的上级。'),
    ('p', '第七条 审批意见须明确；驳回时须填写驳回原因，流程退回发起人修改后可重新提交。'),

    ('h2', '第五章 归档与查询'),
    ('p', '第八条 流程结束后自动归档，保留流转轨迹、审批意见与附件，便于审计追溯。'),
    ('p', '第九条 发起人与审批人可在"我的审批"中按时间、类型、状态查询历史流程。'),

    ('h2', '第六章 常见问题'),
    ('li', '找不到审批入口：确认已在 OA 审批中心开通对应流程权限。'),
    ('li', '流程卡住不动：多为审批节点超时未处理，可按第六条申请升级提醒。'),

    ('h2', '第七章 附则'),
    ('p', '第十条 本说明由信息部负责解释，自 2026 年 1 月 1 日起施行。'),
]


def main():
    if not os.path.isfile(_FONT_FILE):
        raise SystemExit(f'缺少中文字体：{_FONT_FILE}')
    pdfmetrics.registerFont(TTFont(FONT, _FONT_FILE))
    out_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           'knowledge', 'OA流程')
    os.makedirs(out_dir, exist_ok=True)
    docs = [
        ('公司采购管理流程_V1.0.pdf', '公司采购管理流程', PROCUREMENT),
        ('费用报销管理流程_V1.0.pdf', '费用报销管理流程', REIMBURSEMENT),
        ('OA审批流程配置说明_V1.0.pdf', 'OA 审批流程配置说明', OA_APPROVAL),
    ]
    for name, title, blocks in docs:
        path = os.path.join(out_dir, name)
        build(path, title, blocks)
        print(f'已生成 {path}')


if __name__ == '__main__':
    main()
