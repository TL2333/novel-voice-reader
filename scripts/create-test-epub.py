#!/usr/bin/env python3
"""Create the deterministic, public-domain-free EPUB used for offline checks."""

from __future__ import annotations

import io
import pathlib
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "testbooks" / "storyvoice-test.epub"

CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""

PACKAGE = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="zh-CN">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">urn:uuid:novel-voice-reader-test-book</dc:identifier>
    <dc:title>雨夜灯火：离线朗读测试</dc:title>
    <dc:creator>Novel Voice Reader</dc:creator>
    <dc:language>zh-CN</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
    <meta name="cover" content="cover-image"/>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml" properties="svg"/>
    <item id="cover-image" href="cover.svg" media-type="image/svg+xml" properties="cover-image"/>
    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
    <item id="c3" href="chapter3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="cover"/><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
</package>
"""

NAV = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="zh-CN">
<head><title>目录</title></head><body><nav epub:type="toc"><h1>目录</h1><ol>
<li><a href="chapter1.xhtml">第一章 雨夜来信</a></li>
<li><a href="chapter2.xhtml">第二章 晨光计划</a></li>
<li><a href="chapter3.xhtml">第三章 城门回声</a></li>
</ol></nav></body></html>
"""

COVER = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>封面</title></head><body>
<svg xmlns="http://www.w3.org/2000/svg" width="600" height="800" viewBox="0 0 600 800">
<rect width="600" height="800" fill="#263238"/><circle cx="450" cy="160" r="70" fill="#ffd54f"/>
<text x="300" y="390" text-anchor="middle" fill="white" font-size="44">雨夜灯火</text>
<text x="300" y="455" text-anchor="middle" fill="#b0bec5" font-size="26">离线朗读测试</text></svg>
</body></html>
"""

COVER_SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="600" height="800"><rect width="600" height="800" fill="#263238"/><circle cx="450" cy="160" r="70" fill="#ffd54f"/><text x="300" y="390" text-anchor="middle" fill="white" font-size="44">雨夜灯火</text></svg>"""

CHAPTERS = {
    "chapter1.xhtml": ("第一章 雨夜来信", [
        "夜色渐渐沉了下来，远处的灯火在雨幕中忽明忽暗。",
        "“你真的要在今晚出发吗？”林青问。",
        "“当然！”阿遥笑着回答，“车票只要一百二十八元五角，已经便宜了百分之二十。”",
        "短短一声雷——窗外忽然亮如白昼……随后，一切又安静下来。",
    ]),
    "chapter2.xhtml": ("第二章 晨光计划", [
        "二〇二六年八月三日早上七点半，Project Dawn 正式启动。",
        "太好了！他们终于找到了地图上标记的旧桥。",
        "别碰那扇门！守门人愤怒地喊道。",
        "快。跑！",
        "计划清单包含三项：检查补给；确认方向；在日落前抵达河谷。",
    ]),
    "chapter3.xhtml": ("第三章 城门回声", [
        "雨停后，石板路映着清晨的微光，所有人都放慢了脚步。",
        "也许我们来得太迟了……林青低声说。",
        "看啊，城门正在打开！阿遥激动得几乎忘了呼吸。",
        "这是一段特意写得很长的测试句子，它包含中文、English words、数字12345、日期2026-08-03和金额￥99.90，并且在接近朗读器建议上限时用逗号提供自然断点，以验证超长句不会在数字或英文单词内部被错误切开。",
        "归途。",
    ]),
}


def xhtml(title: str, paragraphs: list[str]) -> str:
    body = "\n".join(f"<p>{p}</p>" for p in paragraphs)
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN"><head><title>{title}</title></head>
<body><h1>{title}</h1>{body}</body></html>'''


def add(zf: zipfile.ZipFile, name: str, data: str, compression: int = zipfile.ZIP_DEFLATED) -> None:
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.compress_type = compression
    info.external_attr = 0o644 << 16
    zf.writestr(info, data.encode("utf-8"))


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w") as zf:
        add(zf, "mimetype", "application/epub+zip", zipfile.ZIP_STORED)
        add(zf, "META-INF/container.xml", CONTAINER)
        add(zf, "EPUB/package.opf", PACKAGE)
        add(zf, "EPUB/nav.xhtml", NAV)
        add(zf, "EPUB/cover.xhtml", COVER)
        add(zf, "EPUB/cover.svg", COVER_SVG)
        for name, (title, paragraphs) in CHAPTERS.items():
            add(zf, f"EPUB/{name}", xhtml(title, paragraphs))
    OUTPUT.write_bytes(payload.getvalue())
    print(f"CREATED {OUTPUT} {OUTPUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
