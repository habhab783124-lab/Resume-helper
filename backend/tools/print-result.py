#!/usr/bin/env python
"""
把接口返回的 JSON 打印成一行行看得懂的结果。

为什么单独写一个脚本而不是在 PowerShell 里内联 python -c：
PS 5.1 对中文和引号的处理很容易出问题（内联代码里的双引号会提前截断
PowerShell 的字符串），踩过一次就不要再踩。这里用管道喂 stdin，
PowerShell 侧只负责传文件，不做任何解码和拼接。

用法：
    Get-Content resp.json -Raw -Encoding UTF8 | python tools/print-result.py
    curl ... | python tools/print-result.py
"""

import json
import sys


def emit(text):
    """按 UTF-8 字节直接写 stdout。

    不能直接用 print()：Windows 上 Python 的 stdout 编码会跟随控制台代码页
    （中文机器上通常是 GBK），而 PowerShell 那边可能按 UTF-8 解码，
    结果就是脚本里的中文标签变成乱码。写字节就绕开了这一层猜测。
    """
    sys.stdout.buffer.write((text + "\n").encode("utf-8"))
    sys.stdout.buffer.flush()


def describe_material(data):
    material = data.get("material") or {}
    emit("  材料    : {} ({}) {} {}".format(
        material.get("fileName", "?"),
        material.get("fileTypeLabel", "?"),
        material.get("sizeText", "?"),
        "共 {} 页".format(material.get("pageCount")) if material.get("pageCount") else "",
    ))
    emit("  状态    : {} / {}".format(material.get("status"), material.get("statusLabel")))
    emit("  可见字符: {}".format(material.get("textLength")))
    emit("  识别条目: {}".format(material.get("itemCount")))
    if material.get("message"):
        emit("  提示    : {}".format(material["message"]))

    extraction = data.get("extraction")
    if not extraction:
        emit("  抽取结果: （无）")
        return
    profile = extraction.get("profile") or {}
    emit("  学历    : {} | 经验 {} 年".format(
        profile.get("highestEducation") or "(未识别)", profile.get("yearsOfExperience", 0)))
    emit("  技能    : {}".format("、".join(profile.get("skills") or []) or "(未识别)"))
    emit("  教育经历: {} 条".format(len(profile.get("educations") or [])))
    for item in (profile.get("educations") or [])[:3]:
        emit("      - {} {} {} {}".format(
            item.get("school", ""), item.get("major", ""),
            item.get("degree", ""), item.get("period", "")))
    emit("  实习/工作: {} 条".format(len(profile.get("experiences") or [])))
    for item in (profile.get("experiences") or [])[:3]:
        emit("      - {} | {} | {}".format(
            item.get("company", ""), item.get("role", ""), item.get("period", "")))
        if item.get("evidence"):
            emit("        证据: {}".format(item["evidence"][:50]))
    emit("  项目经历: {} 条".format(len(profile.get("projects") or [])))
    for item in (profile.get("projects") or [])[:3]:
        emit("      - {} | {}".format(item.get("name", ""), item.get("period", "")))
    highlights = profile.get("highlights") or []
    if highlights:
        emit("  获奖    : {}".format("、".join(highlights)))
    for warning in profile.get("warnings") or []:
        emit("  [提示]  {}".format(warning))


def main():
    raw = sys.stdin.buffer.read().decode("utf-8", errors="replace")
    if not raw.strip():
        emit("  (响应为空)")
        return
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as error:
        # 后端返回非 JSON（例如被 SPA 路由吞掉，拿到一份 HTML）。
        # 这种情况下把开头打出来，比抛异常有用。
        emit("  (不是合法 JSON: {})".format(error))
        emit("  开头: {}".format(raw.strip()[:160].replace("\n", " ")))
        return

    success = payload.get("success")
    emit("  success : {}".format(success))
    if payload.get("message"):
        emit("  message : {}".format(payload["message"]))

    data = payload.get("data")
    if data is None:
        return
    if isinstance(data, list):
        emit("  列表长度: {}".format(len(data)))
        for item in data[:8]:
            if isinstance(item, dict):
                emit("      - {} | {} | {}".format(
                    item.get("fileName", item.get("title", item.get("name", "?"))),
                    item.get("statusLabel", item.get("company", "")),
                    item.get("createdAt", item.get("score", ""))))
        return
    if isinstance(data, dict):
        if "material" in data:
            describe_material(data)
        elif "profile" in data:
            emit("  抽取结果:")
            describe_material({"material": {}, "extraction": data})
        else:
            emit("  data    : {}".format(json.dumps(data, ensure_ascii=False)[:300]))
        return
    emit("  data    : {}".format(data))


if __name__ == "__main__":
    main()
