"""
生成材料解析的测试样本。

为什么需要这个脚本：材料模块的验收标准是"能不能从真实简历里抽出对的东西"，
用字符串拼出来的假输入测不出问题。所以这里生成三份**形态不同**的真实文件：

  1. sample-resume.docx   —— 中文简历，教育经历用表格排版（考验表格与段落顺序）
  2. sample-resume.pdf    —— 由上面的 docx 用 LibreOffice 转出，是有文字层的真 PDF
  3. sample-scanned.pdf   —— 整页只有一张图片、没有文字层，用来验证"扫描件检测"

3 是刻意的负样本：如果程序把它当成普通 PDF 去抽，会得到空字符串，
然后大模型就可能基于空白编出东西来。这正是我们要防住的情况。

用法：
    python make-samples.py

依赖：python-docx、Pillow（都在 DSH 的 python 环境里）
生成 sample-resume.pdf 还需要用 LibreOffice 转换，见同目录的 README。
"""

from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Pt

HERE = Path(__file__).resolve().parent
EAST_ASIA_FONT = "微软雅黑"


def style_run(run, size=10.5, bold=False, name=EAST_ASIA_FONT):
    """设置字体。

    中文字体必须同时设置 w:eastAsia，只设 run.font.name 的话
    Word 会给中文回退到默认字体——这是 python-docx 的经典坑。
    """
    run.font.name = name
    run.font.size = Pt(size)
    run.font.bold = bold
    run._element.rPr.rFonts.set(qn("w:eastAsia"), name)


def add_line(document, text, size=10.5, bold=False, space_after=2):
    paragraph = document.add_paragraph()
    paragraph.paragraph_format.space_after = Pt(space_after)
    style_run(paragraph.add_run(text), size=size, bold=bold)
    return paragraph


def add_section_title(document, text):
    paragraph = document.add_paragraph()
    paragraph.paragraph_format.space_before = Pt(10)
    paragraph.paragraph_format.space_after = Pt(4)
    style_run(paragraph.add_run(text), size=12, bold=True)


def build_resume() -> Document:
    document = Document()

    # ---- 抬头 ----
    name = document.add_paragraph()
    name.alignment = WD_ALIGN_PARAGRAPH.CENTER
    style_run(name.add_run("林一舟"), size=20, bold=True)

    contact = document.add_paragraph()
    contact.alignment = WD_ALIGN_PARAGRAPH.CENTER
    style_run(contact.add_run(
        "求职意向：Java 后端开发工程师  |  期望城市：上海  |  138 0013 8000  |  linyizhou@example.com"
    ), size=10)

    # ---- 教育经历（用表格排版，考验表格与段落的顺序处理）----
    add_section_title(document, "教育经历")
    table = document.add_table(rows=1, cols=4)
    table.style = "Table Grid"
    header = ["学校", "专业", "学历", "时间"]
    for index, text in enumerate(header):
        cell = table.rows[0].cells[index]
        cell.text = ""
        style_run(cell.paragraphs[0].add_run(text), size=10.5, bold=True)
    row = table.add_row().cells
    for index, text in enumerate(["上海理工大学", "计算机科学与技术", "本科", "2022.09 - 2026.06"]):
        row[index].text = ""
        style_run(row[index].paragraphs[0].add_run(text), size=10.5)
    add_line(document, "主修课程：数据结构、操作系统、数据库原理、计算机网络；GPA 3.6/4.0（专业前 15%）")

    # ---- 实习经历 ----
    add_section_title(document, "实习经历")
    add_line(document, "上海璞云信息技术有限公司  |  Java 后端开发实习生  |  2025.07 - 2025.09", bold=True)
    for item in [
        "参与公司 SaaS 后台的用户权限模块开发，用 Spring Boot + MyBatis 实现角色与权限的增删改查。",
        "把原本每次请求都查库的权限校验改成 Redis 缓存，接口平均响应时间从 180ms 降到 45ms。",
        "补充了 30 多个单元测试，把权限模块的代码行覆盖率从 42% 提升到 78%。",
    ]:
        add_line(document, "· " + item)

    # ---- 项目经历 ----
    add_section_title(document, "项目经历")
    add_line(document, "校园二手交易平台  |  后端负责人  |  2025.03 - 2025.06", bold=True)
    for item in [
        "4 人小组，我负责后端。用 Spring Boot + MySQL + Redis 实现商品发布、搜索和下单流程。",
        "设计基于 Redis 的分布式锁，解决了同一件商品被两个用户同时下单的超卖问题。",
        "接入 Elasticsearch 做商品搜索，支持按关键词、分类和价格区间筛选。",
    ]:
        add_line(document, "· " + item)

    add_line(document, "分布式缓存练习项目  |  个人项目  |  2024.11 - 2025.01", bold=True)
    for item in [
        "用 Java 实现了一个带 LRU 与过期淘汰的简易缓存，理解了缓存穿透与雪崩的应对方式。",
        "写了压测脚本对比不同淘汰策略的命中率，形成了 3 页的实验报告。",
    ]:
        add_line(document, "· " + item)

    # ---- 专业技能 ----
    add_section_title(document, "专业技能")
    for item in [
        "编程语言：Java（熟练）、SQL、Python（了解）",
        "框架：Spring Boot、Spring MVC、MyBatis",
        "数据库与中间件：MySQL、Redis、Elasticsearch（了解）",
        "工具：Git、Maven、Linux 常用命令、Docker（了解）",
    ]:
        add_line(document, "· " + item)

    # ---- 获奖情况 ----
    add_section_title(document, "获奖情况")
    for item in [
        "2024 年 校级二等奖学金",
        "2025 年 蓝桥杯软件类省赛 三等奖",
        "2024 年 校程序设计竞赛 二等奖",
    ]:
        add_line(document, "· " + item)

    return document


def build_scanned_pdf():
    """生成一个"扫描件"：整页只有一张图片，没有文字层。

    用 Pillow 直接把图片存成 PDF，得到的就是只有图像、没有文字层的 PDF，
    和真实扫描件的解析结果一致。
    """
    from PIL import Image, ImageDraw, ImageFont

    width, height = 1240, 1754  # A4 @150dpi
    image = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(image)

    # 优先找一个能显示中文的字体；找不到就退化成英文，同样能达到"没有文字层"的目的
    font = None
    for candidate in [
        r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\simhei.ttf",
        r"C:\Windows\Fonts\simsun.ttc",
    ]:
        if Path(candidate).exists():
            try:
                font = ImageFont.truetype(candidate, 30)
                break
            except OSError:
                continue
    if font is None:
        font = ImageFont.load_default()

    lines = [
        "林一舟",
        "",
        "求职意向：Java 后端开发工程师",
        "",
        "教育经历",
        "上海理工大学  计算机科学与技术  本科  2022.09-2026.06",
        "",
        "实习经历",
        "上海璞云信息技术有限公司  Java 后端开发实习生  2025.07-2025.09",
        "",
        "技能：Java、Spring Boot、MySQL、Redis",
    ]
    y = 120
    for line in lines:
        draw.text((110, y), line, fill="black", font=font)
        y += 60

    # 加一点噪点，更像真实扫描件
    import random

    random.seed(20260501)
    pixels = image.load()
    for _ in range(20000):
        x = random.randrange(width)
        yy = random.randrange(height)
        grey = random.randint(200, 250)
        pixels[x, yy] = (grey, grey, grey)

    out = HERE / "sample-scanned.pdf"
    image.save(out, "PDF", resolution=150.0)
    return out


def main():
    docx_path = HERE / "sample-resume.docx"
    build_resume().save(docx_path)
    print(f"已生成 {docx_path.name} ({docx_path.stat().st_size / 1024:.1f} KB)")

    scanned = build_scanned_pdf()
    print(f"已生成 {scanned.name} ({scanned.stat().st_size / 1024:.1f} KB)")

    print()
    print("接下来用 LibreOffice 把 docx 转成有文字层的 PDF：")
    print("  tools/material-check.ps1 会自动完成这一步")


if __name__ == "__main__":
    main()
