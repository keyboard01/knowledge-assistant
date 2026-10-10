# -*- coding: utf-8 -*-
"""生成知识助手 P1 验收语料（PDF / DOCX，含矢量图纸）。

使用方法：python tools/build_corpus.py
输出目录：knowledge/{制度流程,技术文档,维修}
"""
import os
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_DIR = r"C:\Windows\Fonts"

pdfmetrics.registerFont(TTFont("CN", os.path.join(FONT_DIR, "msyh.ttc"), subfontIndex=0))
pdfmetrics.registerFont(TTFont("CNB", os.path.join(FONT_DIR, "msyhbd.ttc"), subfontIndex=0))
pdfmetrics.registerFont(TTFont("CNH", os.path.join(FONT_DIR, "simhei.ttf")))

INK = colors.HexColor("#1a1a1a")
GREY = colors.HexColor("#6b6b6b")
LINE = colors.HexColor("#3f3f3f")
HEAD = colors.HexColor("#1f3864")
FILL = colors.HexColor("#eef2f9")
WARN = colors.HexColor("#a33333")


def wrap_cn(text, font, size, maxw):
    lines, cur = [], ""
    for ch in str(text):
        if ch == "\n":
            lines.append(cur)
            cur = ""
            continue
        if pdfmetrics.stringWidth(cur + ch, font, size) <= maxw:
            cur += ch
        else:
            lines.append(cur)
            cur = ch
    lines.append(cur)
    return lines or [""]


class Doc:
    def __init__(self, path, doc_no, title, version, secrecy, owner, status="现行"):
        self.path = path
        self.doc_no = doc_no
        self.title = title
        self.version = version
        self.secrecy = secrecy
        self.owner = owner
        self.status = status
        self.c = canvas.Canvas(path, pagesize=A4)
        self.W, self.H = A4
        self.ml, self.mr = 20 * mm, 18 * mm
        self.mt, self.mb = 24 * mm, 18 * mm
        self.y = self.H - self.mt
        self.page = 0
        self.c.setTitle(title)
        self.new_page(first=True)

    # ---------- 页面骨架 ----------
    def new_page(self, first=False):
        if not first:
            self._footer()
            self.c.showPage()
        self.page += 1
        c = self.c
        c.setStrokeColor(LINE)
        c.setLineWidth(0.7)
        c.line(self.ml, self.H - 18 * mm, self.W - self.mr, self.H - 18 * mm)
        c.setFont("CN", 8)
        c.setFillColor(GREY)
        c.drawString(self.ml, self.H - 15 * mm, "%s" % self.doc_no)
        c.drawCentredString(self.W / 2, self.H - 15 * mm, self.title)
        c.drawRightString(self.W - self.mr, self.H - 15 * mm, "%s · %s" % (self.version, self.secrecy))
        self.y = self.H - self.mt

    def _footer(self):
        c = self.c
        c.setStrokeColor(LINE)
        c.setLineWidth(0.5)
        c.line(self.ml, 14 * mm, self.W - self.mr, 14 * mm)
        c.setFont("CN", 8)
        c.setFillColor(GREY)
        c.drawString(self.ml, 10 * mm, "责任部门：%s" % self.owner)
        c.drawCentredString(self.W / 2, 10 * mm, "状态：%s" % self.status)
        c.drawRightString(self.W - self.mr, 10 * mm, "第 %d 页" % self.page)

    def save(self):
        self._footer()
        self.c.save()
        return self.path

    def need(self, h):
        if self.y - h < self.mb:
            self.new_page()

    # ---------- 内容块 ----------
    def title_block(self, subtitle=None):
        self.need(34 * mm)
        c = self.c
        c.setFillColor(HEAD)
        c.setFont("CNH", 19)
        lines = wrap_cn(self.title, "CNH", 19, self.W - self.ml - self.mr)
        for ln in lines:
            c.drawCentredString(self.W / 2, self.y, ln)
            self.y -= 9 * mm
        self.y -= 1 * mm
        if subtitle:
            c.setFont("CN", 10)
            c.setFillColor(GREY)
            c.drawCentredString(self.W / 2, self.y, subtitle)
            self.y -= 7 * mm
        c.setStrokeColor(HEAD)
        c.setLineWidth(1.4)
        c.line(self.ml, self.y, self.W - self.mr, self.y)
        self.y -= 7 * mm

    def h1(self, text):
        self.need(14 * mm)
        c = self.c
        c.setFillColor(HEAD)
        c.setFont("CNB", 13)
        c.drawString(self.ml, self.y, text)
        self.y -= 6.6 * mm

    def h2(self, text):
        self.need(11 * mm)
        self.c.setFillColor(INK)
        self.c.setFont("CNB", 11)
        self.c.drawString(self.ml, self.y, text)
        self.y -= 5.6 * mm

    def para(self, text, indent=0, size=9.6, leading=5.6 * mm, color=INK):
        font = "CN"
        c = self.c
        c.setFont(font, size)
        c.setFillColor(color)
        maxw = self.W - self.ml - self.mr - indent
        for ln in wrap_cn(text, font, size, maxw):
            self.need(leading)
            self.c.drawString(self.ml + indent, self.y, ln)
            self.y -= leading
        self.y -= 1.4 * mm

    def bullets(self, items, indent=4 * mm, size=9.6):
        for it in items:
            self.need(6 * mm)
            c = self.c
            c.setFont("CN", size)
            c.setFillColor(INK)
            c.drawString(self.ml + indent, self.y, "·")
            maxw = self.W - self.ml - self.mr - indent - 5 * mm
            lines = wrap_cn(it, "CN", size, maxw)
            for idx, ln in enumerate(lines):
                self.need(5.6 * mm)
                self.c.drawString(self.ml + indent + 4 * mm, self.y, ln)
                self.y -= 5.6 * mm
        self.y -= 1.4 * mm

    def spacer(self, h=3 * mm):
        self.y -= h

    def table(self, rows, widths, size=8.8, header=True, pad=1.6 * mm):
        c = self.c
        line_h = size + 2.6
        total = sum(widths)
        avail = self.W - self.ml - self.mr
        widths = [w * avail / total for w in widths]
        for r_i, row in enumerate(rows):
            font = "CNB" if (header and r_i == 0) else "CN"
            cell_lines = []
            for c_i, cell in enumerate(row):
                cell_lines.append(wrap_cn(cell, font, size, widths[c_i] - 2 * pad))
            row_h = max(len(x) for x in cell_lines) * line_h + 2 * pad
            self.need(row_h)
            x = self.ml
            if header and r_i == 0:
                c.setFillColor(FILL)
                c.rect(self.ml, self.y - row_h, avail, row_h, stroke=0, fill=1)
            for c_i, cell in enumerate(row):
                c.setStrokeColor(LINE)
                c.setLineWidth(0.4)
                c.rect(x, self.y - row_h, widths[c_i], row_h, stroke=1, fill=0)
                c.setFillColor(INK)
                c.setFont(font, size)
                ty = self.y - pad - size
                for ln in cell_lines[c_i]:
                    c.drawString(x + pad, ty, ln)
                    ty -= line_h
                x += widths[c_i]
            self.y -= row_h
        self.y -= 2.2 * mm

    def figure(self, height, draw_fn, caption=None):
        self.need(height + (8 * mm if caption else 4 * mm))
        box_h = height
        self.c.setStrokeColor(colors.HexColor("#c9d3e4"))
        self.c.setLineWidth(0.6)
        self.c.setFillColor(colors.white)
        self.c.rect(self.ml, self.y - box_h, self.W - self.ml - self.mr, box_h, stroke=1, fill=0)
        draw_fn(self.c, self.ml, self.y - box_h, self.W - self.ml - self.mr, box_h)
        self.y -= box_h + 2 * mm
        if caption:
            self.c.setFont("CN", 8.2)
            self.c.setFillColor(GREY)
            self.c.drawCentredString(self.W / 2, self.y, caption)
            self.y -= 6 * mm


# ---------- 绘图原语 ----------
def box(c, x, y, w, h, text, size=8.6, fill=None, font="CN"):
    if fill:
        c.setFillColor(fill)
        c.rect(x, y, w, h, stroke=0, fill=1)
    c.setStrokeColor(LINE)
    c.setLineWidth(0.8)
    c.rect(x, y, w, h, stroke=1, fill=0)
    c.setFillColor(INK)
    c.setFont(font, size)
    lines = wrap_cn(text, font, size, w - 3 * mm)
    ty = y + h / 2 + (len(lines) - 1) * (size + 2.4) / 2 - size / 2 + 1
    for ln in lines:
        c.drawCentredString(x + w / 2, ty, ln)
        ty -= size + 2.4


def diamond(c, cx, cy, w, h, text, size=8.4):
    c.setStrokeColor(LINE)
    c.setLineWidth(0.8)
    p = c.beginPath()
    p.moveTo(cx, cy + h / 2)
    p.lineTo(cx + w / 2, cy)
    p.lineTo(cx, cy - h / 2)
    p.lineTo(cx - w / 2, cy)
    p.close()
    c.drawPath(p)
    c.setFillColor(INK)
    c.setFont("CN", size)
    for i, ln in enumerate(wrap_cn(text, "CN", size, w - 5 * mm)):
        c.drawCentredString(cx, cy + 1.5 - i * (size + 2), ln)


def arrow(c, x1, y1, x2, y2, head=2.4 * mm, label=None, size=7.6):
    c.setStrokeColor(LINE)
    c.setLineWidth(0.8)
    c.line(x1, y1, x2, y2)
    import math
    ang = math.atan2(y2 - y1, x2 - x1)
    for s in (-0.42, 0.42):
        c.line(x2, y2, x2 - head * math.cos(ang + s), y2 - head * math.sin(ang + s))
    if label:
        c.setFont("CN", size)
        c.setFillColor(GREY)
        c.drawString((x1 + x2) / 2 + 1.5 * mm, (y1 + y2) / 2 + 1.2 * mm, label)


def dim_h(c, x1, x2, y, text, tick=1.6 * mm):
    c.setStrokeColor(GREY)
    c.setLineWidth(0.5)
    c.line(x1, y, x2, y)
    c.line(x1, y - tick, x1, y + tick)
    c.line(x2, y - tick, x2, y + tick)
    c.setFont("CN", 7.6)
    c.drawCentredString((x1 + x2) / 2, y + 1.4 * mm, text)


def label_callout(c, px, py, tx, ty, text, size=7.6):
    c.setStrokeColor(GREY)
    c.setLineWidth(0.5)
    c.circle(px, py, 1.1 * mm, stroke=1, fill=0)
    c.line(px, py, tx, ty)
    c.setFont("CN", size)
    c.setFillColor(INK)
    anchor = "left" if tx >= px else "right"
    if anchor == "left":
        c.drawString(tx + 1.2 * mm, ty - 0.6 * mm, text)
    else:
        c.drawRightString(tx - 1.2 * mm, ty - 0.6 * mm, text)


def hflow(c, x, y, w, h, nodes, box_h=17 * mm, dia_h=22 * mm, labels=None, note=None):
    n = len(nodes)
    gap = 8 * mm
    bw = (w - 8 * mm - (n - 1) * gap) / n
    cy = y + h / 2 + (4 * mm if note else 0)
    for i, (text, kind) in enumerate(nodes):
        bx = x + 4 * mm + i * (bw + gap)
        if kind == "d":
            diamond(c, bx + bw / 2, cy, bw, dia_h, text)
        else:
            box(c, bx, cy - box_h / 2, bw, box_h, text, fill=colors.HexColor("#f7f9fc") if i == 0 else None)
        if i < n - 1:
            arrow(c, bx + bw + 1 * mm, cy, bx + bw + gap - 1 * mm, cy)
            if labels and labels[i]:
                c.setFont("CN", 7.2)
                c.setFillColor(GREY)
                c.drawCentredString(bx + bw + gap / 2, cy + 1.8 * mm, labels[i])
    if note:
        c.setFont("CN", 7.4)
        c.setFillColor(GREY)
        ty = y + 9 * mm
        for ln in note:
            c.drawString(x + 4 * mm, ty, ln)
            ty -= 9.4


def fig_travel_flow(c, x, y, w, h):
    hflow(c, x, y, w, h, [
        ("申请人在系统提交", "b"),
        ("部门负责人审批", "b"),
        ("财务部审核", "b"),
        ("单笔 > 5000 元？", "d"),
        ("分管副总审批", "b"),
        ("出纳付款", "b"),
    ], box_h=16 * mm, dia_h=21 * mm, labels=["", "", "", "是", ""],
        note=["否：单笔不超过 5000 元时，财务审核通过后直接进入出纳付款环节。",
              "超期报销（费用发生超过 30 日提交）无论金额均须分管副总审批。"])


def fig_downtime_flow(c, x, y, w, h):
    hflow(c, x, y, w, h, [
        ("发现停机", "b"),
        ("现场紧急处置", "b"),
        ("判定停机等级", "d"),
        ("按时限上报", "b"),
        ("填写停机记录", "b"),
    ], box_h=16 * mm, dia_h=21 * mm, labels=["", "", "", ""],
        note=["A 级（整线停线）：5 分钟内电话上报设备动力部，30 分钟内在系统补录；",
              "B 级（单机停机 ≥ 2 小时）：30 分钟内在系统上报；C 级（单机停机 < 2 小时）：4 小时内补录。"])


def fig_cnc850_outline(c, x, y, w, h):
    ox, oy = x + 26 * mm, y + 15 * mm
    c.setStrokeColor(LINE)
    c.setLineWidth(1.1)
    c.rect(ox, oy, 62 * mm, 7 * mm, stroke=1, fill=0)          # 底座
    c.rect(ox + 40 * mm, oy + 7 * mm, 20 * mm, 30 * mm, stroke=1, fill=0)   # 立柱
    c.rect(ox + 22 * mm, oy + 7 * mm, 16 * mm, 4 * mm, stroke=1, fill=0)    # 工作台
    c.rect(ox + 30 * mm, oy + 11 * mm, 20 * mm, 12 * mm, stroke=1, fill=0)  # 主轴箱
    c.line(ox + 40 * mm, oy + 11 * mm, ox + 40 * mm, oy + 2 * mm)           # 主轴
    c.rect(ox + 4 * mm, oy + 7 * mm, 14 * mm, 20 * mm, stroke=1, fill=0)    # 电控柜
    c.setFont("CN", 7.4)
    c.setFillColor(INK)
    label_callout(c, ox + 50 * mm, oy + 33 * mm, ox + 66 * mm, oy + 34 * mm, "立柱 / Z 轴")
    label_callout(c, ox + 40 * mm, oy + 17 * mm, ox + 20 * mm, oy + 36 * mm, "主轴箱 / 主轴 ISO40")
    label_callout(c, ox + 30 * mm, oy + 9 * mm, ox + 4 * mm, oy + 34 * mm, "工作台 1000×500")
    label_callout(c, ox + 11 * mm, oy + 17 * mm, ox + 66 * mm, oy + 4 * mm, "电控柜")
    dim_h(c, ox, ox + 62 * mm, oy - 6 * mm, "2200")
    c.setStrokeColor(GREY)
    c.setLineWidth(0.5)
    c.line(ox + 68 * mm, oy, ox + 68 * mm, oy + 37 * mm)
    c.line(ox + 66 * mm, oy, ox + 70 * mm, oy)
    c.line(ox + 66 * mm, oy + 37 * mm, ox + 70 * mm, oy + 37 * mm)
    c.setFont("CN", 7.6)
    c.drawString(ox + 70 * mm, oy + 18 * mm, "2650")
    c.setFont("CN", 8)
    c.setFillColor(GREY)
    c.drawString(x + 3 * mm, y + h - 6 * mm, "正视图 ｜ 单位：mm ｜ 未注公差按 GB/T 1804-m")


def fig_fault_tree(c, x, y, w, h):
    box(c, x + w / 2 - 32 * mm, y + h - 15 * mm, 64 * mm, 11 * mm, "主轴运转异响（ALM-2201 报警）", fill=colors.HexColor("#fdf2f2"))
    mid_y = y + h - 24 * mm
    c.setStrokeColor(LINE)
    c.setLineWidth(0.8)
    c.line(x + w / 2, y + h - 15 * mm, x + w / 2, mid_y + 6 * mm)
    c.line(x + 24 * mm, mid_y + 6 * mm, x + w - 24 * mm, mid_y + 6 * mm)
    causes = [
        ("轴承磨损或点蚀", "更换主轴轴承组；检查润滑油脂"),
        ("刀具动平衡不良", "重新动平衡；更换刀柄"),
        ("主轴拉刀机构松动", "检查拉刀力 12~14kN 并调整"),
        ("同步带张紧不足", "按 5mm 挠度重新张紧"),
    ]
    n = len(causes)
    cw = (w - 16 * mm) / n
    for i, (cause, action) in enumerate(causes):
        cx = x + 8 * mm + i * cw
        c.line(cx + cw / 2, mid_y + 6 * mm, cx + cw / 2, mid_y)
        box(c, cx + 1 * mm, mid_y - 14 * mm, cw - 2 * mm, 14 * mm, cause)
        arrow(c, cx + cw / 2, mid_y - 14 * mm, cx + cw / 2, mid_y - 19 * mm, head=2 * mm)
        c.setFont("CN", 7.2)
        c.setFillColor(GREY)
        ty = mid_y - 22 * mm
        for ln in wrap_cn(action, "CN", 7.2, cw - 4 * mm):
            c.drawCentredString(cx + cw / 2, ty, ln)
            ty -= 8.4


def fig_ga75_parts(c, x, y, w, h):
    ox, oy = x + 30 * mm, y + 14 * mm
    c.setStrokeColor(LINE)
    c.setLineWidth(1.1)
    c.rect(ox, oy, 70 * mm, 30 * mm, stroke=1, fill=0)
    c.rect(ox + 6 * mm, oy + 30 * mm, 34 * mm, 9 * mm, stroke=1, fill=0)
    c.circle(ox + 52 * mm, oy + 15 * mm, 10 * mm, stroke=1, fill=0)
    c.circle(ox + 52 * mm, oy + 15 * mm, 3 * mm, stroke=1, fill=0)
    c.rect(ox + 66 * mm, oy + 5 * mm, 10 * mm, 16 * mm, stroke=1, fill=0)
    c.setFont("CN", 7.4)
    c.setFillColor(INK)
    label_callout(c, ox + 12 * mm, oy + 18 * mm, ox - 24 * mm, oy + 30 * mm, "1 空气滤清器")
    label_callout(c, ox + 40 * mm, oy + 34 * mm, ox + 48 * mm, oy + 40 * mm, "2 油气分离器")
    label_callout(c, ox + 52 * mm, oy + 15 * mm, ox + 78 * mm, oy + 32 * mm, "3 主机 / 皮带")
    label_callout(c, ox + 71 * mm, oy + 13 * mm, ox + 78 * mm, oy + 6 * mm, "4 冷却器")
    label_callout(c, ox + 18 * mm, oy + 6 * mm, ox - 24 * mm, oy + 2 * mm, "5 油过滤器")
    label_callout(c, ox + 46 * mm, oy + 3 * mm, ox + 78 * mm, oy - 2 * mm, "6 排水阀")
    c.setFont("CN", 8)
    c.setFillColor(GREY)
    c.drawString(x + 3 * mm, y + h - 6 * mm, "保养部位示意图 ｜ GA-75 螺杆空压机")


# ============================== 语料文档 ==============================
K = os.path.join(ROOT, "knowledge")
D_ZD = os.path.join(K, "制度流程")
D_JS = os.path.join(K, "技术文档")
D_WX = os.path.join(K, "维修")


def build_travel_current():
    p = os.path.join(D_ZD, "员工差旅与费用报销管理办法_V3.2_现行.pdf")
    d = Doc(p, "QM-ZD-2026-003", "员工差旅与费用报销管理办法", "V3.2", "内部公开", "财务部", "现行")
    d.title_block("生效日期：2026-03-01 ｜ 替代版本：V2.8")
    d.h1("1 目的与适用范围")
    d.para("为规范公司员工因公出差的审批与费用报销，明确费用标准与审批权限，特制定本办法。")
    d.para("本办法适用于公司及各分子公司全体在职员工的国内出差、培训、会议、客户拜访等公务活动。境外差旅、外派常驻另行规定，不适用本办法。")
    d.h1("2 职责分工")
    d.table([
        ["角色", "职责"],
        ["申请人", "如实填写出差申请与报销单，按期提交，对票据真实性负责"],
        ["部门负责人", "审核出差必要性与业务真实性，控制本部门差旅预算"],
        ["财务部", "审核票据合规性与费用标准执行情况，办理付款"],
        ["分管副总", "审批超标准、超期以及单笔金额较大的报销事项"],
    ], widths=[22, 78])
    d.h1("3 审批流程")
    d.para("差旅费用报销按图 1 所示流程执行。凡单笔报销金额超过 5000 元，或费用发生之日起超过 30 个自然日才提交的，均须经分管副总审批后方可付款。")
    d.figure(62 * mm, fig_travel_flow, "图 1 差旅费用报销审批流程")
    d.h1("4 费用标准")
    d.para("住宿费、市内交通费与伙食补贴按表 1 标准执行。实际发生额低于标准的据实报销；超出标准部分需事前经分管副总批准，否则超标部分由个人承担。")
    d.table([
        ["费用项目", "一线城市", "二线城市", "其他地区"],
        ["住宿标准（元 / 人·晚）", "500", "380", "300"],
        ["市内交通（元 / 人·天）", "120", "100", "80"],
        ["伙食补贴（元 / 人·天）", "120", "100", "80"],
        ["交通工具", "高铁二等座 / 飞机经济舱", "同左", "同左"],
    ], widths=[34, 24, 21, 21], size=8.6)
    d.para("注 1：总监及以上人员可乘坐高铁一等座。注 2：一线城市指北京、上海、广州、深圳。", size=8.4, color=GREY)
    d.h1("5 报销时限与凭证要求")
    d.bullets([
        "报销单据应在费用发生之日起 30 个自然日内提交至财务部；",
        "提交时应附发票原件、行程单（机票 / 车票）、出差审批记录；",
        "超过 30 日提交的，须书面说明原因并经分管副总批准；超过 90 日的，财务部不予受理；",
        "市内交通费自 V3.2 起改为凭票报销，无票据不予报销。",
    ])
    d.h1("6 不予报销的情形")
    d.bullets([
        "与公务无关的私人行程及其费用；",
        "未经审批的出差所发生的费用；",
        "票据不合法、不完整或与行程不符的费用；",
        "因个人原因产生的罚款、滞纳金、改签退票手续费。",
    ])
    d.h1("7 版本历史")
    d.table([
        ["版本", "生效日期", "状态", "主要变化"],
        ["V2.8", "2023-06-01", "已废止（2026-03-01 废止）", "报销时限为 15 个自然日；一线城市住宿标准 450 元"],
        ["V3.2", "2026-03-01", "现行", "一线城市住宿标准由 450 元上调至 500 元；报销时限由 15 个自然日延长至 30 个自然日；市内交通费改为凭票报销"],
    ], widths=[10, 20, 26, 44], size=8.4)
    return d.save()


def build_travel_obsolete():
    p = os.path.join(D_ZD, "员工差旅与费用报销管理办法_V2.8_已废止.pdf")
    d = Doc(p, "QM-ZD-2026-003", "员工差旅与费用报销管理办法", "V2.8", "内部公开", "财务部", "已废止")
    d.title_block("生效日期：2023-06-01 ｜ 废止日期：2026-03-01 ｜ 替代版本：V3.2")
    d.para("本文件为历史版本，已于 2026-03-01 废止，仅供追溯查询使用。现行有效版本为 V3.2，两者在报销时限与住宿标准上存在差异，业务办理请以 V3.2 为准。", color=WARN)
    d.h1("1 费用标准（V2.8）")
    d.table([
        ["费用项目", "一线城市", "二线城市", "其他地区"],
        ["住宿标准（元 / 人·晚）", "450", "350", "280"],
        ["伙食补贴（元 / 人·天）", "100", "90", "70"],
    ], widths=[38, 22, 20, 20], size=8.6)
    d.h1("2 报销时限（V2.8）")
    d.bullets([
        "报销单据应在费用发生之日起 15 个自然日内提交至财务部；",
        "超过 15 日提交的，须经部门负责人与财务部经理共同批准；",
        "市内交通费按 80 元 / 人·天包干，不需提供票据。",
    ])
    d.h1("3 与现行版本的差异")
    d.table([
        ["项目", "V2.8（本文件）", "V3.2（现行）"],
        ["一线城市住宿标准", "450 元 / 人·晚", "500 元 / 人·晚"],
        ["报销时限", "15 个自然日", "30 个自然日"],
        ["市内交通费", "80 元 / 天包干", "120 元 / 天凭票报销"],
    ], widths=[30, 35, 35], size=8.5)
    return d.save()


def build_downtime():
    p = os.path.join(D_ZD, "设备停机与故障上报管理规定_V1.4.pdf")
    d = Doc(p, "QM-ZD-2026-011", "设备停机与故障上报管理规定", "V1.4", "内部公开", "设备动力部", "现行")
    d.title_block("生效日期：2026-05-10")
    d.h1("1 目的")
    d.para("为规范设备停机与故障的上报、处置与记录，缩短故障响应时间，制定本规定。")
    d.h1("2 停机分级与上报时限")
    d.table([
        ["等级", "判定标准", "上报方式", "上报时限"],
        ["A 级", "整条产线停止运行，或单台设备停机预计超过 8 小时", "电话上报设备动力部，并同步车间主任", "5 分钟内"],
        ["B 级", "单台设备停机 2 小时（含）以上、8 小时以下", "系统上报", "30 分钟内"],
        ["C 级", "单台设备停机不足 2 小时，已自行恢复", "系统补录", "4 小时内"],
    ], widths=[10, 44, 30, 16], size=8.4)
    d.h1("3 上报流程")
    d.figure(58 * mm, fig_downtime_flow, "图 1 设备停机与故障上报流程")
    d.h1("4 记录要求")
    d.bullets([
        "停机记录应包含：设备编号、停机起止时间、停机等级、故障现象、处理措施、更换备件、恢复确认人；",
        "A 级停机须在恢复后 24 小时内提交书面分析，说明根本原因与防止再发措施；",
        "同一设备 30 日内重复发生 2 次以上同类故障的，由设备维修部组织专项分析。",
    ])
    d.h1("5 考核")
    d.para("未按时限上报，或瞒报、漏报的，对责任人及其直接主管按公司绩效管理办法处理；因瞒报导致故障扩大的，从重处理。")
    return d.save()


def build_salary_draft():
    p = os.path.join(D_ZD, "2026年度薪酬结构调整方案_草案.pdf")
    d = Doc(p, "QM-ZD-2026-021", "2026 年度薪酬结构调整方案", "V0.9（草案）", "秘密", "人力资源部", "草案·未生效")
    d.title_block("密级：秘密 ｜ 查阅范围：人力资源部成员及总监以上人员")
    d.para("本文件为草案，尚未履行审批程序，未生效。正式发布前不得作为薪酬调整依据，不得对外提供，不得在会议中引用具体数值。", color=WARN)
    d.h1("1 调整原则")
    d.bullets([
        "向关键技术岗位与一线维修岗位倾斜；",
        "保持同职级带宽内差异不超过 30%；",
        "本次调整不涉及岗位撤销与人员优化。",
    ])
    d.h1("2 薪酬带宽（草案）")
    d.table([
        ["职级", "带宽下限（元 / 月）", "带宽上限（元 / 月）", "调整幅度"],
        ["P4 工程师", "12000", "18000", "+6%"],
        ["P5 高级工程师", "18000", "26000", "+8%"],
        ["M2 部门经理", "22000", "32000", "+5%"],
        ["M3 总监", "32000", "48000", "+4%"],
    ], widths=[26, 26, 26, 22], size=8.5)
    d.h1("3 实施安排（草案）")
    d.para("拟自 2026 年 7 月 1 日起执行，具体发放口径与追溯方式由人力资源部另行通知。")
    d.h1("4 保密要求")
    d.para("本文件属公司秘密级信息，仅限人力资源部成员及总监以上人员查阅。任何形式的转发、截屏、复制均须经人力资源部负责人书面同意。")
    return d.save()


def build_cnc850_spec():
    p = os.path.join(D_JS, "CNC-850立式加工中心技术规格书_V2.0.pdf")
    d = Doc(p, "QM-JS-CNC850-2025", "CNC-850 立式加工中心技术规格书", "V2.0", "内部公开", "工艺技术部", "现行")
    d.title_block("适用机型：CNC-850 ｜ 适用厂区：一号、二号车间")
    d.h1("1 概述")
    d.para("CNC-850 为三轴立式加工中心，采用立柱固定、工作台移动结构，配置 ISO40 主轴与 24 刀位圆盘刀库，适用于箱体类、板类零件的铣削、钻孔与攻丝加工。")
    d.h1("2 主要技术参数")
    d.table([
        ["项目", "参数", "项目", "参数"],
        ["X / Y / Z 行程", "850 / 520 / 560 mm", "主轴转速范围", "60 ~ 12000 r/min"],
        ["工作台尺寸", "1000 × 500 mm", "主轴功率（连续）", "11 kW"],
        ["工作台最大承重", "600 kg", "主轴锥孔", "BT40 / ISO40"],
        ["快速移动速度", "36 / 36 / 24 m/min", "重复定位精度", "±0.003 mm"],
        ["刀库容量", "24 把（圆盘式）", "换刀时间（刀对刀）", "2.5 s"],
        ["定位精度", "±0.005 mm", "机床重量", "6800 kg"],
    ], widths=[26, 24, 26, 24], size=8.3)
    d.h1("3 外形尺寸与安装")
    d.para("机床外形见图 1。安装须在独立基础上进行，基础混凝土强度等级不低于 C25，养护期不少于 28 天。")
    d.figure(58 * mm, fig_cnc850_outline, "图 1 CNC-850 外形示意图（正视图，单位 mm）")
    d.h1("4 电气与气源要求")
    d.table([
        ["项目", "要求"],
        ["电源", "AC 380 V ± 10%，50 Hz，三相五线制，独立接地电阻 < 4 Ω"],
        ["总装机容量", "22 kVA"],
        ["压缩空气", "0.6 ~ 0.8 MPa，露点 ≤ 2 ℃，含油量 ≤ 0.1 mg/m³，耗气量约 120 L/min"],
        ["环境温度", "5 ~ 40 ℃；相对湿度 ≤ 85%，无凝露"],
    ], widths=[24, 76], size=8.5)
    d.h1("5 润滑与液压")
    d.bullets([
        "导轨与丝杠采用集中自动润滑，润滑泵每 30 分钟供油一次，油箱容积 2 L；",
        "主轴采用油气润滑，气压须稳定在 0.45 ~ 0.55 MPa，低于 0.40 MPa 时触发 ALM-3305 报警；",
        "液压系统仅用于松拉刀机构，工作压力 5.5 MPa，拉刀力须保持在 12 ~ 14 kN。",
    ])
    d.h1("6 安全与联锁")
    d.bullets([
        "安全门开启时主轴与进给自动停止，且禁止启动自动运行；",
        "刀库换刀区域设光栅保护，检测到遮挡立即中断换刀；",
        "急停按钮触发后须手动复位，并确认各轴回参考点后方可继续加工。",
    ])
    return d.save()


def build_fault_manual():
    p = os.path.join(D_WX, "CNC-850常见故障排查手册_V1.3.pdf")
    d = Doc(p, "QM-WX-CNC850-2026", "CNC-850 常见故障排查手册", "V1.3", "内部公开", "设备维修部", "现行")
    d.title_block("适用机型：CNC-850 ｜ 配套文档：QM-JS-CNC850-2025")
    d.h1("1 安全提示")
    d.bullets([
        "排查前必须执行急停并挂牌上锁，确认主轴完全停止；",
        "涉及电气柜作业时须由持证电工执行，禁止带电插拔板卡；",
        "液压、气动回路泄压后方可拆卸接头。",
    ])
    d.h1("2 常见报警代码及处理")
    d.table([
        ["报警代码", "含义", "可能原因", "处理措施"],
        ["ALM-2201", "主轴驱动器过载", "切削负荷过大、轴承磨损、驱动器电流限值设置不当", "降低进给倍率；检查主轴轴承；核对驱动器电流限值 22 A"],
        ["ALM-3305", "润滑压力低", "油气润滑气压不足、油路堵塞、润滑泵故障", "确认气压 ≥ 0.45 MPa；清洗油路；检查润滑泵供油间隔 30 min"],
        ["ALM-4102", "刀库定位超时", "刀盘异物、定位销磨损、接近开关松动", "清理刀盘；更换定位销；调整接近开关间隙至 1.5 mm"],
        ["ALM-5108", "Z 轴伺服过载", "导轨润滑不良、丝杠预紧过大、平衡缸压力异常", "检查润滑；核对平衡缸压力 5.5 MPa"],
        ["ALM-6203", "安全门未闭合", "门锁开关损坏、门体变形、联锁线路断路", "更换门锁开关；校正门体；检查联锁回路"],
    ], widths=[16, 18, 30, 36], size=8.0)
    d.h1("3 主轴运转异响的排查")
    d.para("主轴异响通常伴随 ALM-2201 报警。按图 1 的故障树逐项排查，先排除刀具与工艺因素，再拆检主轴本体。")
    d.figure(62 * mm, fig_fault_tree, "图 1 主轴运转异响故障树")
    d.h1("4 备件清单")
    d.table([
        ["备件名称", "规格 / 型号", "建议库存"],
        ["主轴轴承组", "7014C / 7014AC 配对", "1 套"],
        ["圆盘刀库定位销", "D12 × 45", "4 件"],
        ["安全门锁开关", "AZ16 系列", "2 件"],
        ["油气润滑滤芯", "HX-10", "6 件"],
    ], widths=[34, 40, 26], size=8.5)
    return d.save()


def build_ga75():
    p = os.path.join(D_WX, "空压机GA-75定期保养作业指导书_V1.0.pdf")
    d = Doc(p, "QM-WX-GA75-2026", "空压机 GA-75 定期保养作业指导书", "V1.0", "内部公开", "设备维修部", "现行")
    d.title_block("适用设备：GA-75 螺杆式空气压缩机（75 kW）")
    d.h1("1 适用范围与安全要求")
    d.para("本指导书适用于 GA-75 螺杆空压机的日常与定期保养。保养前必须停机、断电、挂牌上锁，并等待油气分离器压力降至 0 MPa、机组冷却至 40 ℃ 以下后方可作业。")
    d.h1("2 保养部位")
    d.figure(62 * mm, fig_ga75_parts, "图 1 GA-75 保养部位示意图")
    d.h1("3 保养周期与项目")
    d.table([
        ["周期", "保养项目", "部位编号"],
        ["每 500 h", "检查空气滤清器指示器；排放储气罐冷凝水", "1、6"],
        ["每 2000 h", "更换空气滤清器滤芯；更换油过滤器", "1、5"],
        ["每 4000 h", "更换油气分离器；检查皮带张紧度与磨损", "2、3"],
        ["每 8000 h", "更换润滑油；清洗冷却器；检查主机轴承", "4、3"],
    ], widths=[18, 58, 24], size=8.5)
    d.h1("4 作业步骤")
    d.bullets([
        "确认机组停机并断电挂牌，记录停机前排气压力与运行小时数；",
        "按部位编号更换对应耗材，耗材须使用原厂或同等品质配件；",
        "更换油气分离器时须在密封面涂抹少量润滑油，紧固力矩 60 N·m；",
        "皮带张紧度以中部施加 30 N 压力时挠度 8 ~ 10 mm 为合格；",
        "保养完成后点动确认转向正确，运行 15 分钟无异常后再交付使用。",
    ])
    d.h1("5 记录与验收")
    d.para("保养完成后填写《设备保养记录表》，记录更换件批次、运行小时数与保养人，由班组长确认签字。")
    return d.save()


# ---------- DOCX（含位图图纸）----------
def fig_hydraulic_png(path):
    from PIL import Image, ImageDraw, ImageFont
    W, H = 1600, 900
    img = Image.new("RGB", (W, H), "white")
    dr = ImageDraw.Draw(img)
    f = ImageFont.truetype(os.path.join(FONT_DIR, "msyh.ttc"), 24, index=0)
    ink = (40, 40, 40)
    lw = 3
    dr.rectangle([100, 700, 440, 860], outline=ink, width=lw)
    dr.line([100, 740, 440, 740], fill=ink, width=lw)
    dr.text((120, 872), "油箱 160 L", font=f, fill=ink)
    dr.line([440, 780, 495, 780], fill=ink, width=lw)
    dr.ellipse([495, 735, 585, 825], outline=ink, width=lw)
    dr.polygon([(520, 815), (565, 815), (542, 760)], outline=ink)
    dr.text((468, 840), "液压泵 40 mL/r", font=f, fill=ink)
    dr.line([585, 780, 1075, 780], fill=ink, width=lw)
    dr.line([940, 780, 940, 690], fill=ink, width=lw)
    dr.rectangle([880, 600, 1000, 690], outline=ink, width=lw)
    dr.text((856, 555), "溢流阀 10 MPa", font=f, fill=ink)
    dr.line([940, 600, 240, 600], fill=ink, width=lw)
    dr.line([240, 600, 240, 740], fill=ink, width=lw)
    dr.rectangle([1075, 720, 1215, 840], outline=ink, width=lw)
    dr.text((1085, 858), "三位四通换向阀", font=f, fill=ink)
    dr.line([1215, 750, 1280, 750], fill=ink, width=lw)
    dr.line([1215, 810, 1280, 810], fill=ink, width=lw)
    dr.rectangle([1280, 690, 1500, 770], outline=ink, width=lw)
    dr.line([1400, 690, 1400, 770], fill=ink, width=lw)
    dr.line([1400, 730, 1560, 730], fill=ink, width=lw)
    dr.text((1270, 640), "液压缸 Φ80 / 45", font=f, fill=ink)
    dr.text((100, 40), "液压站 HYZ-40 液压原理示意图", font=ImageFont.truetype(os.path.join(FONT_DIR, "msyhbd.ttc"), 30, index=0), fill=ink)
    dr.text((100, 96), "压力油路 ｜ 工作压力 5.5 MPa ｜ 溢流设定 10 MPa", font=f, fill=(110, 110, 110))
    img.save(path)
    return path


def build_hyz40_docx():
    from docx import Document
    from docx.shared import Pt, Cm, RGBColor
    from docx.enum.text import WD_ALIGN_PARAGRAPH
    from docx.oxml.ns import qn

    png = fig_hydraulic_png(os.path.join(ROOT, "tools", "_hyz40_schematic.png"))
    p = os.path.join(D_JS, "液压站HYZ-40原理与维护说明_V1.1.docx")
    doc = Document()
    normal = doc.styles["Normal"]
    normal.font.name = "微软雅黑"
    normal.font.size = Pt(10.5)
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")

    def head(text, size=14, space_before=10):
        par = doc.add_paragraph()
        par.paragraph_format.space_before = Pt(space_before)
        par.paragraph_format.space_after = Pt(4)
        run = par.add_run(text)
        run.bold = True
        run.font.size = Pt(size)
        run.font.color.rgb = RGBColor(0x1F, 0x38, 0x64)
        return par

    def body(text):
        par = doc.add_paragraph(text)
        par.paragraph_format.space_after = Pt(4)
        return par

    def table(rows, header=True):
        t = doc.add_table(rows=0, cols=len(rows[0]))
        t.style = "Table Grid"
        for i, row in enumerate(rows):
            cells = t.add_row().cells
            for j, val in enumerate(row):
                cells[j].text = ""
                run = cells[j].paragraphs[0].add_run(str(val))
                run.font.size = Pt(9.5)
                run.font.name = "微软雅黑"
                run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
                if header and i == 0:
                    run.bold = True
        return t

    title = doc.add_paragraph()
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    trun = title.add_run("液压站 HYZ-40 原理与维护说明")
    trun.bold = True
    trun.font.size = Pt(18)
    sub = doc.add_paragraph()
    sub.alignment = WD_ALIGN_PARAGRAPH.CENTER
    srun = sub.add_run("文档编号 QM-JS-HYZ40-2026 ｜ 版本 V1.1 ｜ 密级 内部公开 ｜ 责任部门 工艺技术部 ｜ 状态 现行")
    srun.font.size = Pt(9)
    srun.font.color.rgb = RGBColor(0x6B, 0x6B, 0x6B)

    head("1 概述")
    body("HYZ-40 液压站为 CNC-850 加工中心松拉刀机构配套动力单元，由油箱、柱塞泵、溢流阀、三位四通换向阀、液压缸与滤油器组成，系统工作压力 5.5 MPa，溢流阀设定压力 10 MPa。")
    body("液压站安装于机床后侧，与主机共用 380 V 三相电源，正常工作油温 25 ~ 55 ℃，油温超过 65 ℃ 时须停机检查冷却回路。")

    head("2 液压原理")
    doc.add_picture(png, width=Cm(15.5))
    doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
    cap = doc.add_paragraph()
    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
    crun = cap.add_run("图 1 液压站 HYZ-40 液压原理示意图")
    crun.font.size = Pt(9)
    crun.font.color.rgb = RGBColor(0x6B, 0x6B, 0x6B)

    head("3 主要元件")
    table([
        ["代号", "名称", "规格 / 参数", "备注"],
        ["1", "液压泵", "柱塞泵 40 mL/r，额定压力 16 MPa", "电机 5.5 kW"],
        ["2", "溢流阀", "设定压力 10 MPa", "不得随意调整"],
        ["3", "三位四通换向阀", "中位机能 O 型，工作压力 10 MPa", "电磁换向 DC24V"],
        ["4", "液压缸", "缸径 Φ80 mm，杆径 Φ45 mm，行程 40 mm", "拉刀力 12 ~ 14 kN"],
        ["5", "回油滤油器", "过滤精度 10 μm，HX-10 滤芯", "每 2000 h 更换"],
    ])

    head("4 维护周期")
    table([
        ["周期", "维护项目", "判定标准"],
        ["每 500 h", "检查油位与油温；检查接头渗漏", "油位在液位计 1/2 以上；油温 25 ~ 55 ℃"],
        ["每 2000 h", "更换回油滤芯", "压差报警或累计运行 2000 h"],
        ["每 4000 h", "检测油液清洁度；清洗油箱磁性滤网", "清洁度不低于 NAS 9 级"],
        ["每 12 个月", "取样化验液压油；必要时整体更换", "酸值、水分、粘度超出厂家限值即更换"],
    ])

    head("5 常见压力异常处理")
    table([
        ["现象", "可能原因", "处理措施"],
        ["系统压力上不去（低于 5.0 MPa）", "溢流阀卡滞、泵内泄漏、滤芯堵塞", "清洗溢流阀阀芯；检查泵容积效率；更换滤芯"],
        ["压力波动大", "油液含气、油箱油位低、溢流阀弹簧疲劳", "排气；补油；更换溢流阀"],
        ["油温超过 65 ℃", "冷却器脏堵、环境温度高、连续保压时间长", "清洗冷却器；检查风扇；调整保压程序"],
    ])

    head("6 安全提示")
    body("拆卸任何液压元件前必须停机泄压至 0 MPa，并确认液压缸不带压；更换滤芯时须防止杂质进入回路。")
    doc.save(p)
    return p


def main():
    for d in (D_ZD, D_JS, D_WX):
        os.makedirs(d, exist_ok=True)
    tasks = [
        ("制度流程", build_travel_current),
        ("制度流程", build_travel_obsolete),
        ("制度流程", build_downtime),
        ("制度流程", build_salary_draft),
        ("技术文档", build_cnc850_spec),
        ("技术文档", build_hyz40_docx),
        ("维修", build_fault_manual),
        ("维修", build_ga75),
    ]
    ok, fail = [], []
    for domain, fn in tasks:
        try:
            path = fn()
            size = os.path.getsize(path)
            ok.append((domain, path, size))
            print("[OK]   %s  %s  (%.1f KB)" % (domain, os.path.basename(path), size / 1024))
        except Exception as exc:
            fail.append((domain, fn.__name__, repr(exc)))
            print("[FAIL] %s  %s  %r" % (domain, fn.__name__, exc))
    print("\n生成成功 %d 份，失败 %d 份" % (len(ok), len(fail)))
    return 0 if not fail else 1


if __name__ == "__main__":
    raise SystemExit(main())
