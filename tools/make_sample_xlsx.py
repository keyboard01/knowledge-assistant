# -*- coding: utf-8 -*-
"""生成用于验证"XLSX 解析入库"的样例文件（多工作表 + 表头 + 数字/日期）。

刻意覆盖这几种情况：
  1. 常规表：表头 + 多行数据（表头要能拼到每行行首）
  2. 第二个工作表：行不会跟第一张表串在一起
  3. 单行工作表：不能被当成表头吃掉
  4. 数字单元格：取显示值（不能变成 500.0 这种底层浮点写法）
"""
from datetime import date
from openpyxl import Workbook

OUT = r"d:/个人/test-project/XLSX表格验证样例.xlsx"

workbook = Workbook()

sheet = workbook.active
sheet.title = "液压站参数"
sheet.append(["型号", "额定压力", "流量", "行程"])
sheet.append(["HYZ-40", "25 MPa", "40 L/min", "800 mm"])
sheet.append(["HYZ-63", "31.5 MPa", "63 L/min", "1000 mm"])
sheet.append(["HYZ-80", "31.5 MPa", "80 L/min", "1200 mm"])

oil = workbook.create_sheet("换油周期")
oil.append(["工况", "换油周期", "滤芯更换"])
oil.append(["常规", 2000, 500])
oil.append(["高温", 1000, 300])
oil.append(["多尘", 800, 250])

fault = workbook.create_sheet("故障代码")
fault.append(["代码", "含义", "处理"])
fault.append(["E01", "油温过高", "检查冷却器与油位"])
fault.append(["E02", "压力不足", "检查溢流阀设定"])
fault.append(["E03", "滤芯堵塞", "更换回油滤芯"])

single = workbook.create_sheet("备注")
single.append(["本表由设备管理部维护，2026 年起执行。"])

workbook.save(OUT)
print("已生成:", OUT)
print("工作表:", workbook.sheetnames)
