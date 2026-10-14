# -*- coding: utf-8 -*-
"""生成用于验证"PPTX 图片进重排 PDF"功能的样例文件。

刻意覆盖四种情况（对应 DocxToPdf.interleave 的边界）：
  1. 封面：纯文本
  2. 文本 + 图：图应跟在同页文本之后
  3. 纯图片页（layout=Blank，无任何文本框）：图不能丢
  4. 表格 + 图
  5. 纯文本对照页：验证没有图片时排版与从前一致
"""
import io
from PIL import Image, ImageDraw, ImageFont
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN

FONT_PATH = "C:/Windows/Fonts/simhei.ttf"
OUT = r"d:/个人/test-project/PPTX图片验证样例.pptx"


def font(size):
    try:
        return ImageFont.truetype(FONT_PATH, size)
    except OSError:
        return ImageFont.load_default()


def png_bytes(draw_fn, width, height):
    image = Image.new("RGB", (width, height), "#ffffff")
    draw = ImageDraw.Draw(image)
    draw_fn(draw, image)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    buffer.seek(0)
    return buffer


# ---------- 三张内容各异的示意图 ----------

def draw_system(draw, image):
    draw.rectangle([0, 0, image.width, 56], fill="#1f4e79")
    draw.text((16, 12), "液压系统原理图", font=font(28), fill="#ffffff")
    boxes = [("油箱", 60), ("液压泵", 260), ("换向阀", 460), ("液压缸", 660)]
    for label, x in boxes:
        draw.rectangle([x, 200, x + 150, 300], outline="#1f4e79", width=4, fill="#dbe7f3")
        draw.text((x + 40, 235), label, font=font(24), fill="#1f4e79")
        if x < 660:
            draw.line([x + 150, 250, x + 190, 250], fill="#1f4e79", width=5)
            draw.polygon([(x + 190, 250), (x + 178, 242), (x + 178, 258)], fill="#1f4e79")
    draw.text((60, 360), "动力元件 → 控制元件 → 执行元件", font=font(22), fill="#333333")


def draw_cylinder(draw, image):
    draw.rectangle([0, 0, image.width, 56], fill="#7b3f00")
    draw.text((16, 12), "液压缸结构示意", font=font(28), fill="#ffffff")
    draw.rectangle([120, 220, 780, 380], outline="#7b3f00", width=6, fill="#f5e6d3")
    draw.rectangle([120, 220, 420, 380], fill="#c9852f")
    draw.rectangle([420, 240, 800, 360], fill="#e8edf2")
    draw.line([800, 300, 920, 300], fill="#7b3f00", width=10)
    draw.text((150, 400), "活塞", font=font(22), fill="#7b3f00")
    draw.text((520, 400), "活塞杆", font=font(22), fill="#7b3f00")


def draw_curve(draw, image):
    draw.rectangle([0, 0, image.width, 56], fill="#14624a")
    draw.text((16, 12), "压力-流量特性曲线", font=font(28), fill="#ffffff")
    draw.line([80, 380, 640, 380], fill="#333333", width=3)
    draw.line([80, 380, 80, 90], fill="#333333", width=3)
    draw.text((600, 390), "流量", font=font(20), fill="#333333")
    draw.text((20, 70), "压力", font=font(20), fill="#333333")
    points = [(80, 360), (180, 320), (280, 250), (380, 180), (480, 140), (620, 120)]
    draw.line(points, fill="#14624a", width=5, joint="curve")
    for point in points:
        draw.ellipse([point[0] - 6, point[1] - 6, point[0] + 6, point[1] + 6], fill="#14624a")


prs = Presentation()

# ---------- 1. 封面（纯文本） ----------
slide = prs.slides.add_slide(prs.slide_layouts[0])
slide.shapes.title.text = "液压站 HYZ-40 原理与维护说明"
slide.placeholders[1].text = "设备管理部 · V1.0"

# ---------- 2. 文本 + 图 ----------
slide = prs.slides.add_slide(prs.slide_layouts[5])
slide.shapes.title.text = "系统组成"
box = slide.shapes.add_textbox(Inches(0.6), Inches(1.5), Inches(4.4), Inches(3.2)).text_frame
lines = [
    "液压站由动力元件、执行元件、控制元件、辅助元件和液压油五部分组成。",
    "动力元件：柱塞泵，额定压力 25MPa。",
    "执行元件：双作用液压缸，行程 800mm。",
    "控制元件：电磁换向阀与比例溢流阀。",
]
for index, line in enumerate(lines):
    paragraph = box.paragraphs[0] if index == 0 else box.add_paragraph()
    paragraph.text = line
    paragraph.font.size = Pt(16)
slide.shapes.add_picture(io.BytesIO(png_bytes(draw_system, 900, 440).read()),
                         Inches(5.2), Inches(1.7), width=Inches(4.3))

# ---------- 3. 纯图片页（Blank 布局，无文本框） ----------
slide = prs.slides.add_slide(prs.slide_layouts[6])
slide.shapes.add_picture(io.BytesIO(png_bytes(draw_cylinder, 980, 560).read()),
                         Inches(0.7), Inches(1.0), width=Inches(8.6))

# ---------- 4. 表格 + 图 ----------
slide = prs.slides.add_slide(prs.slide_layouts[5])
slide.shapes.title.text = "主要参数"
rows = [
    ("型号", "额定压力", "流量", "行程"),
    ("HYZ-40", "25 MPa", "40 L/min", "800 mm"),
    ("HYZ-63", "31.5 MPa", "63 L/min", "1000 mm"),
]
table = slide.shapes.add_table(len(rows), 4, Inches(0.6), Inches(1.5),
                               Inches(5.4), Inches(1.4)).table
for row_index, row in enumerate(rows):
    for col_index, value in enumerate(row):
        table.cell(row_index, col_index).text = value
slide.shapes.add_picture(io.BytesIO(png_bytes(draw_curve, 760, 440).read()),
                         Inches(0.6), Inches(3.2), width=Inches(4.6))

# ---------- 5. 纯文本对照页 ----------
slide = prs.slides.add_slide(prs.slide_layouts[5])
slide.shapes.title.text = "维护注意事项"
frame = slide.shapes.add_textbox(Inches(0.7), Inches(1.6), Inches(8.6), Inches(3.6)).text_frame
notes = [
    "每运行 500 小时更换一次液压油滤芯。",
    "油温超过 65℃ 时停机检查冷却器。",
    "长期停用前应将系统压力卸至零。",
    "检修时必须确认蓄能器已泄压。",
]
for index, note in enumerate(notes):
    paragraph = frame.paragraphs[0] if index == 0 else frame.add_paragraph()
    paragraph.text = note
    paragraph.font.size = Pt(17)

prs.save(OUT)
print("已生成:", OUT)
print("幻灯片数:", len(prs.slides.__iter__.__self__._sldIdLst))
