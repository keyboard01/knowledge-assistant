#!/usr/bin/env python
"""独立工具：用 pypdf 逐页导出文本，供 AC-P2-003 复核页码。

为什么不复用后端的解析器：让被测对象证明自己是对的，等于没测。pypdf 与后端用的
PDFBox 是两套完全不同的实现，它们在同一页上得出同样的文字，才说明页码不是解析器自己编的。

输出：每页一行，`<页码>\t<该页文本（所有空白折叠成单个空格）>`
用法：python tools/pdf-page-text.py <file.pdf>
"""
import sys

from pypdf import PdfReader


def main(argv):
    if len(argv) < 2:
        print("usage: pdf-page-text.py <file.pdf>", file=sys.stderr)
        return 2
    sys.stdout.reconfigure(encoding="utf-8")
    reader = PdfReader(argv[1])
    for index, page in enumerate(reader.pages, start=1):
        text = page.extract_text() or ""
        print("{}\t{}".format(index, " ".join(text.split())))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))