#!/usr/bin/env python3
"""Build the designed PDF and Markdown figures from the existing audit, without rerunning it.

Run with Python plus reportlab and pypdf. Font paths and Poppler can be overridden through
ENDIVE_REPORT_FONTS and ENDIVE_REPORT_PDFTOPPM. The editable Markdown remains the content source.
"""

import hashlib
import html
import json
import os
import re
import subprocess
import tempfile
import textwrap
from pathlib import Path

from pypdf import PdfReader
from reportlab.graphics import renderPDF, renderSVG
from reportlab.graphics.shapes import Drawing, Line, Polygon, Rect, String
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate,
    Flowable,
    Frame,
    KeepTogether,
    PageBreak,
    PageTemplate,
    Paragraph,
    Spacer,
    Table,
    TableStyle,
)

ROOT = Path(__file__).resolve().parents[5]
HERE = Path(__file__).resolve().parent
FIGURES = HERE / "figures"
REPORT = ROOT / "FORK_AUDIT_2026-10-25.md"
PDF = ROOT / "output/pdf/Endive_Fork_Audit_2026-10-25.pdf"
FONT_DIR = Path(os.environ.get("ENDIVE_REPORT_FONTS", "/usr/share/fonts/truetype"))
POPPLER = os.environ.get("ENDIVE_REPORT_PDFTOPPM", "pdftoppm")

PAPER = colors.HexColor("#FAFAF6")
WHITE = colors.HexColor("#FFFFFF")
INK = colors.HexColor("#193730")
GREEN = colors.HexColor("#21624F")
MUTED = colors.HexColor("#63746E")
LINE = colors.HexColor("#D6DFD7")
PALE = colors.HexColor("#EDF2EC")
RED = colors.HexColor("#A33D43")
RED_PALE = colors.HexColor("#FAEEEE")
AMBER = colors.HexColor("#986218")
AMBER_PALE = colors.HexColor("#F9F1E1")
BLUE = colors.HexColor("#346379")
BLUE_PALE = colors.HexColor("#EDF3F6")
P1, P2, P3 = RED, AMBER, GREEN
WIDTH, HEIGHT = A4
MARGIN = 43
CONTENT = WIDTH - 2 * MARGIN


def register_fonts():
    for name, filename in [
        ("Body", "Roboto-Regular.ttf"),
        ("BodyBold", "Roboto-Bold.ttf"),
        ("BodyItalic", "Roboto-Italic.ttf"),
        ("Display", "LiberationSerif-Regular.ttf"),
        ("DisplayBold", "LiberationSerif-Bold.ttf"),
        ("Code", "DejaVuSansMono.ttf"),
    ]:
        pdfmetrics.registerFont(TTFont(name, str(FONT_DIR / filename)))
    pdfmetrics.registerFontFamily(
        "Body", normal="Body", bold="BodyBold", italic="BodyItalic", boldItalic="BodyBold"
    )
    pdfmetrics.registerFontFamily("Display", normal="Display", bold="DisplayBold")


register_fonts()


def clean(text):
    # ASCII dashes print consistently in prose, code, and metadata; Roboto has no arrow glyph.
    return text.translate(str.maketrans({**{c: "-" for c in "‐‑‒–—−"}, "→": "->"}))


def plain(text):
    text = re.sub(r"\[([^]]+)\]\([^)]+\)", r"\1", text)
    return clean(text.replace("**", "").replace("`", ""))


def markup(text):
    tokens = []

    def token(value):
        tokens.append(value)
        return f"ZZTOKEN{len(tokens) - 1}ZZ"

    def link(match):
        label, target = match.groups()
        if target.startswith("#"):
            href = target
        elif target.startswith(("https://", "http://")):
            href = target
        else:
            href = (ROOT / target).resolve().as_uri()
        return token(
            '<a href="{}" color="#21624F">{}</a>'.format(
                html.escape(href, quote=True), markup(label)
            )
        )

    text = re.sub(r"\[([^]]+)\]\(([^)]+)\)", link, clean(text))
    text = re.sub(
        r"`([^`]+)`",
        lambda m: token('<font name="Code" size="8.6">' + html.escape(m[1]) + "</font>"),
        text,
    )
    text = html.escape(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    for i, value in enumerate(tokens):
        text = text.replace(f"ZZTOKEN{i}ZZ", value)
    return text


STYLES = {
    "body": ParagraphStyle(
        "body", fontName="Body", fontSize=10.2, leading=14.0, textColor=INK,
        spaceAfter=8, splitLongWords=1, allowWidows=0, allowOrphans=0,
    ),
    "small": ParagraphStyle(
        "small", fontName="Body", fontSize=8.2, leading=11.5, textColor=MUTED,
        spaceAfter=8, splitLongWords=1,
    ),
    "sources": ParagraphStyle(
        "sources", fontName="Body", fontSize=8.1, leading=11.2, textColor=MUTED,
        backColor=PALE, borderPadding=8, spaceBefore=2, spaceAfter=13,
    ),
    "proposal": ParagraphStyle(
        "proposal", fontName="Body", fontSize=10.0, leading=14.0, textColor=INK,
        backColor=PALE, borderPadding=9, spaceBefore=4, spaceAfter=14,
    ),
    "acceptance": ParagraphStyle(
        "acceptance", fontName="Body", fontSize=9.7, leading=13.6, textColor=INK,
        backColor=BLUE_PALE, borderPadding=9, spaceBefore=4, spaceAfter=14,
    ),
    "h3": ParagraphStyle(
        "h3", fontName="BodyBold", fontSize=13.1, leading=17, textColor=GREEN,
        spaceBefore=10, spaceAfter=10, keepWithNext=True,
    ),
    "table": ParagraphStyle(
        "table", fontName="Body", fontSize=8.5, leading=11.1, textColor=INK,
        splitLongWords=1,
    ),
    "th": ParagraphStyle(
        "th", fontName="BodyBold", fontSize=8.3, leading=10.8, textColor=WHITE,
    ),
}


ROADMAP_H3 = ParagraphStyle("roadmap-h3", parent=STYLES["h3"], spaceBefore=5, spaceAfter=5)
ROADMAP_BODY = ParagraphStyle("roadmap-body", parent=STYLES["body"], spaceAfter=5)
ROADMAP_ACCEPTANCE = ParagraphStyle("roadmap-acceptance", parent=STYLES["acceptance"], spaceAfter=11)


def para(text, kind="body"):
    return Paragraph(markup(text), STYLES[kind])


class PageHeading(Flowable):
    def __init__(self, kicker, title, bookmark, accent=GREEN, subtitle=None):
        super().__init__()
        self.kicker, self.title, self.bookmark = kicker, clean(title), bookmark
        self.accent, self.subtitle = accent, subtitle
        self.height = 85 if subtitle else 76
        self.width = CONTENT

    def wrap(self, avail_width, avail_height):
        style = ParagraphStyle(
            "page-title", fontName="Display", fontSize=27, leading=29, textColor=INK
        )
        self.p = Paragraph(html.escape(self.title), style)
        _, h = self.p.wrap(CONTENT - 4, 150)
        self.height = h + 41 + (19 if self.subtitle else 0)
        return self.width, self.height

    def draw(self):
        c = self.canv
        c.bookmarkPage(self.bookmark)
        c.addOutlineEntry(self.title, self.bookmark, level=0, closed=False)
        c.setFillColor(self.accent)
        c.setFont("BodyBold", 8.6)
        c.drawString(0, self.height - 9, self.kicker.upper())
        c.setStrokeColor(self.accent)
        c.setLineWidth(2.1)
        c.line(0, self.height - 19, 27, self.height - 19)
        self.p.drawOn(c, 0, 17 + (19 if self.subtitle else 0))
        if self.subtitle:
            c.setFillColor(MUTED)
            c.setFont("Body", 8.8)
            c.drawString(0, 17, self.subtitle)


class CodePanel(Flowable):
    def __init__(self, text, label="OBSERVED OUTPUT"):
        super().__init__()
        lines = []
        for line in clean(text).splitlines():
            lines.extend(textwrap.wrap(
                line, 98, replace_whitespace=False, drop_whitespace=False,
                break_long_words=True, break_on_hyphens=False,
            ) or [""])
        self.lines, self.label = lines, label
        self.height, self.width = 36 + 11.4 * len(lines), CONTENT

    def draw(self):
        c = self.canv
        c.setFillColor(INK)
        c.roundRect(0, 8, self.width, self.height - 8, 5, fill=1, stroke=0)
        c.setFillColor(colors.HexColor("#A5C9B5"))
        c.setFont("BodyBold", 7.6)
        c.drawString(12, self.height - 16, self.label)
        c.setFillColor(colors.HexColor("#F5F7F1"))
        c.setFont("Code", 7.35)
        for i, line in enumerate(self.lines):
            c.drawString(12, self.height - 32 - 11.4 * i, line)


def table(rows, widths=None, font_size=8.5):
    if widths is None:
        if len(rows[0]) == 2:
            widths = [CONTENT * .30, CONTENT * .70]
        elif len(rows[0]) == 4:
            widths = [CONTENT * .36, CONTENT * .15, CONTENT * .25, CONTENT * .24]
        elif len(rows[0]) == 5 and rows[0][:3] == ["ID", "Status", "Commit"]:
            widths = [CONTENT * .08, CONTENT * .13, CONTENT * .14, CONTENT * .32, CONTENT * .33]
        else:
            widths = [CONTENT / len(rows[0])] * len(rows[0])
    data = [
        [Paragraph(markup(cell), STYLES["th" if i == 0 else "table"]) for cell in row]
        for i, row in enumerate(rows)
    ]
    t = Table(data, colWidths=widths, repeatRows=1, hAlign="LEFT")
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), GREEN),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [WHITE, PALE]),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 9),
        ("RIGHTPADDING", (0, 0), (-1, -1), 9),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
        ("LINEBELOW", (0, -1), (-1, -1), .5, LINE),
    ]))
    t.spaceAfter = 13
    return t


def blocks(text):
    result = []
    lines = text.strip().splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if not line:
            i += 1
            continue
        if line.startswith("```"):
            language, code = line[3:], []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code.append(lines[i]); i += 1
            if language != "mermaid":
                label = {"sh": "REPRODUCTION COMMANDS", "java": "SOURCE EXCERPT"}.get(language, "OBSERVED OUTPUT")
                result.append(CodePanel("\n".join(code), label))
            i += 1
            continue
        if line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                row = [x.strip() for x in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-+:?", c) for c in row):
                    rows.append(row)
                i += 1
            result.append(table(rows))
            continue
        if line.startswith("### "):
            result.append(para(line[4:], "h3")); i += 1; continue
        paragraph = [line]
        i += 1
        while i < len(lines) and lines[i].strip():
            if re.match(r"^(?:```|\||### |\d+\. |[-*] )", lines[i].strip()):
                break
            paragraph.append(lines[i].strip()); i += 1
        text = " ".join(paragraph)
        text = re.sub(r"^\d+\. ", "", text)
        if text.startswith(("**Source:", "**Sources:")):
            kind = "sources"
        elif text.startswith("**Proposed change:"):
            kind = "proposal"
        elif text.startswith("**Acceptance:"):
            kind = "acceptance"
        else:
            kind = "body"
        result.append(para(text, kind))
    return result


def dtext(d, x, y, text, size=11, font="Body", fill=INK, anchor="start"):
    d.add(String(x, y, clean(text), fontName=font, fontSize=size * 1.30,
                 fillColor=fill, textAnchor=anchor))


def rounded(d, x, y, w, h, fill=WHITE, stroke=None, radius=8):
    d.add(Rect(x, y, w, h, rx=radius, ry=radius, fillColor=fill,
               strokeColor=stroke, strokeWidth=.8 if stroke else 0))


def arrow(d, x1, y1, x2, y2, color=MUTED, width=1.5):
    d.add(Line(x1, y1, x2, y2, strokeColor=color, strokeWidth=width))
    if x2 > x1:
        d.add(Polygon([x2, y2, x2-6, y2+3.3, x2-6, y2-3.3], fillColor=color, strokeColor=None))
    elif y2 < y1:
        d.add(Polygon([x2, y2, x2-3.3, y2+6, x2+3.3, y2+6], fillColor=color, strokeColor=None))


# Fix status after the third pass (FORK_AUDIT section 9). Status words carry the meaning; the
# stripe colour only repeats it. Priority stays neutral text so it never reads as a status.
FIXES = [("F01", "P1", "fixed"), ("F02", "P1", "fixed"), ("F03", "P1", "fixed"),
         ("F04", "P2", "partly"), ("F05", "P2", "fixed"), ("F06", "P2", "fixed"),
         ("F07", "P2", "fixed"), ("F08", "P2", "disclosed"), ("F09", "P2", "fixed"),
         ("F10", "P2", "fixed"), ("F11", "P3", "cleanup"), ("F12", "P3", "fixed"),
         ("F13", "P1", "via sync"), ("F14", "P2", "fixed")]
STATUS = {"fixed": colors.HexColor("#0CA30C"), "via sync": colors.HexColor("#0CA30C"),
          "cleanup": colors.HexColor("#0CA30C"), "partly": colors.HexColor("#FAB219"),
          "disclosed": colors.HexColor("#EC835A")}


def fix_status():
    d = Drawing(1000, 300)
    soft = colors.HexColor("#A9CBB6")
    rounded(d, 0, 0, 1000, 300, INK, radius=14)
    dtext(d, 30, 266, "ENDIVE FORK AUDIT / FIX STATUS", 13, "BodyBold", soft)
    dtext(d, 970, 266, "25 OCTOBER 2026, F11 ON 26 OCTOBER", 11, fill=WHITE, anchor="end")
    resolved = sum(s in ("fixed", "via sync", "cleanup") for _, _, s in FIXES)
    for x, count, label, status in [
        (30, str(resolved), "FIXED, SYNCED OR CLEANED UP", "fixed"),
        (330, "1", "PARTLY: F04 GIVES THE REAL REASON", "partly"),
        (630, "1", "DISCLOSED: F08 OUTPUTS ARE LOST", "disclosed"),
    ]:
        d.add(Rect(x, 222, 34, 6, fillColor=STATUS[status], strokeColor=None))
        dtext(d, x, 168, count, 44, "Display", WHITE)
        dtext(d, x+2, 146, label, 10, "BodyBold", soft)
    dtext(d, 970, 168, f"of {len(FIXES)}", 22, "Display", soft, "end")
    gap, left = 6, 30
    w = (940 - gap * (len(FIXES) - 1)) / len(FIXES)
    for i, (fid, priority, status) in enumerate(FIXES):
        x = left + i * (w + gap)
        rounded(d, x, 54, w, 70, colors.HexColor("#10241F"), radius=6)
        d.add(Rect(x, 118, w, 6, fillColor=STATUS[status], strokeColor=None))
        dtext(d, x + w/2, 92, fid, 12, "BodyBold", WHITE, "middle")
        dtext(d, x + w/2, 76, priority, 8, "Body", soft, "middle")
        dtext(d, x + w/2, 60, status, 7.6, "BodyBold", WHITE, "middle")
    dtext(d, 30, 22, "F05 not remeasured  /  F09 workflow has not run yet  /  F13 closed by upstream "
          "1cd6d40d through the sync  /  F11 leaves generated bytecode identical", 9.5, fill=soft)
    return d

def loop_diagram():
    d = Drawing(1000, 250)
    rounded(d, 0, 0, 1000, 250, PALE)
    dtext(d, 24, 218, "F01 / A LOOP HAS MORE THAN ONE ENTRY", 13, "BodyBold", GREEN)
    for x, w, title, sub, bg in [
        (24, 230, "First iteration", "struct reference", WHITE),
        (350, 300, "Declared loop input", "eqref: struct OR i31", INK),
        (746, 230, "Later iteration", "i31 value 1234", WHITE),
    ]:
        rounded(d, x, 103, w, 80, bg, LINE if bg == WHITE else None)
        dtext(d, x+w/2, 151, title, 16, "BodyBold", WHITE if bg == INK else INK, "middle")
        dtext(d, x+w/2, 124, sub, 14, fill=colors.HexColor("#B8D4C2") if bg == INK else MUTED, anchor="middle")
    arrow(d, 260, 142, 342, 142)
    # Back edge enters the same declared input; its direction is explicit.
    d.add(Line(740, 142, 658, 142, strokeColor=MUTED, strokeWidth=1.5))
    d.add(Polygon([658, 142, 664, 145.3, 664, 138.7], fillColor=MUTED, strokeColor=None))
    rounded(d, 24, 23, 952, 55, RED_PALE)
    dtext(d, 42, 55, "Unsound proof", 13, "BodyBold", RED)
    dtext(d, 205, 55, "First-entry struct type is reused for every iteration.", 14, fill=RED)
    dtext(d, 205, 34, "Expected equality: 1     Compiled result: 0", 13, "BodyBold", RED)
    return d


def cache_diagram():
    d = Drawing(1000, 230)
    rounded(d, 0, 0, 1000, 230, PALE)
    dtext(d, 24, 199, "F02 / THE KEY PRODUCER AND CONSUMER DISAGREE", 13, "BodyBold", GREEN)
    rounded(d, 24, 95, 400, 76, WHITE, LINE)
    dtext(d, 42, 143, "Valid module digest", 14, "BodyBold")
    dtext(d, 42, 116, "algo:...base64...=", 17, "Code", GREEN)
    arrow(d, 437, 132, 523, 132)
    dtext(d, 479, 151, "+ option", 10, fill=MUTED, anchor="middle")
    rounded(d, 537, 95, 439, 76, RED_PALE)
    dtext(d, 555, 143, "Produced cache key", 14, "BodyBold", RED)
    dtext(d, 555, 116, "algo:...base64...=+ucf", 17, "Code", RED)
    dtext(d, 24, 53, "DirectoryCache requires the final character to be '='.", 15, "BodyBold")
    dtext(d, 24, 27, "Observed: 3 existing tests error. Disabling unsigned fusion makes the control pass.", 13, fill=MUTED)
    return d


def pipeline_diagram():
    d = Drawing(1000, 205)
    rounded(d, 0, 0, 1000, 205, PALE)
    dtext(d, 24, 174, "WHERE THE FORK CHANGES EXECUTION", 13, "BodyBold", GREEN)
    labels = [("Validated", "Wasm"), ("Analyzer +", "type proof"), ("Narrowing +", "fusion"),
              ("JVM methods +", "call bridges"), ("JVM", "execution")]
    for i, (a, b) in enumerate(labels):
        x = 24+i*196
        rounded(d, x, 80, 168, 65, INK if i == 1 else WHITE, None if i == 1 else LINE)
        color = WHITE if i == 1 else INK
        dtext(d, x+84, 119, a, 13, "BodyBold", color, "middle")
        dtext(d, x+84, 97, b, 13, fill=color, anchor="middle")
        if i < 4:
            arrow(d, x+172, 113, x+190, 113)
    dtext(d, 24, 48, "F01: a type proof can change the answer.", 13, "BodyBold", RED)
    dtext(d, 520, 48, "F02: cache identity is an integration contract.", 13, "BodyBold", RED)
    dtext(d, 24, 23, "Generated-code correctness, representability, byte size, and runtime speed are separate checks.", 12, fill=MUTED)
    return d


def finding_visual(fid):
    if fid == "F01":
        return loop_diagram()
    if fid == "F02":
        return cache_diagram()
    d = Drawing(1000, 156)
    rounded(d, 0, 0, 1000, 156, PALE)
    if fid == "F03":
        rows = [("Ordinary loop", "STOPS", GREEN), ("Direct tail", "STILL RUNNING", RED),
                ("Indirect tail", "STILL RUNNING", RED), ("Reference tail", "STILL RUNNING", RED)]
        dtext(d, 24, 127, "INTERRUPTION PROBE / BOTH ENGINES", 13, "BodyBold", GREEN)
        for i, (label, value, color) in enumerate(rows):
            x = 24+i*244
            dtext(d, x, 84, label, 15, "BodyBold")
            dtext(d, x, 51, value, 12, "BodyBold", color)
        dtext(d, 24, 21, "One-second join window; missing polls are also confirmed by source inspection.", 12, fill=MUTED)
    elif fid == "F04":
        dtext(d, 24, 127, "THE SAME LINKED TABLE ENTRY", 13, "BodyBold", GREEN)
        dtext(d, 24, 76, "call_indirect", 21, "Code")
        dtext(d, 370, 76, "42", 29, "Display", GREEN)
        dtext(d, 520, 76, "return_call_indirect", 19, "Code")
        dtext(d, 950, 76, "throws", 23, "Display", RED, "end")
        dtext(d, 24, 28, "Different instances are rejected, even when both use the same engine implementation.", 13, fill=MUTED)
    elif fid == "F05":
        dtext(d, 24, 127, "SAVED LOCAL DIAGNOSTIC / RUSTVM-BASE", 13, "BodyBold", GREEN)
        for x, value, label, color in [(24, "84 B", "saved in the method", GREEN),
                                        (360, "16.6 kB", "method remained large", INK),
                                        (710, "+12.6%", "local runtime slowdown", RED)]:
            dtext(d, x, 70, value, 37, "Display", color)
            dtext(d, x, 42, label, 13, fill=MUTED)
        dtext(d, 24, 15, "Archived bisection was inspected; no new performance campaign was run for this audit.", 10.5, fill=MUTED)
    elif fid == "F06":
        dtext(d, 24, 127, "NEGATIVE CONTROL / DIFFERENT GUEST ANSWERS", 13, "BodyBold", GREEN)
        dtext(d, 24, 76, '{"answer":1}', 20, "Code")
        dtext(d, 335, 76, '{"answer":2}', 20, "Code")
        arrow(d, 640, 82, 710, 82)
        dtext(d, 737, 76, "equal output", 22, "Display", RED)
        dtext(d, 24, 27, "The normalizer removes both JSON lines. A missing arm can also be reported as 'yes (1)'.", 12.5, fill=MUTED)
    elif fid == "F07":
        dtext(d, 24, 127, "NEGATIVE CONTROL / SILENT ZIP TRUNCATION", 13, "BodyBold", GREEN)
        for i, label in enumerate(["UP: 100", "UP: 100", "UP: 100"]):
            rounded(d, 24+i*163, 65, 144, 38, WHITE, LINE)
            dtext(d, 96+i*163, 79, label, 13, "Code", INK, "middle")
        dtext(d, 24, 35, "FORK: 90", 13, "Code")
        arrow(d, 530, 80, 608, 80)
        dtext(d, 630, 78, "reported n = 3", 24, "Display", RED)
        dtext(d, 630, 43, "Only one pair supplied the effect.", 13, fill=MUTED)
    elif fid == "F08":
        dtext(d, 24, 127, "ARCHIVE COVERAGE / 37 WORKLOAD CELLS", 13, "BodyBold", GREEN)
        dtext(d, 24, 70, "24", 43, "Display")
        dtext(d, 100, 70, "outputs per cell in the historical claim", 17)
        dtext(d, 620, 70, "2", 43, "Display", AMBER)
        dtext(d, 675, 70, "outputs retained per cell", 17)
        dtext(d, 24, 28, "74 saved files verify one pair per cell. The stronger all-round claim needs more evidence.", 12.5, fill=MUTED)
    elif fid == "F09":
        dtext(d, 24, 127, "CONFIGURATION IS NOT A VERIFIED LIVE GATE", 13, "BodyBold", GREEN)
        dtext(d, 24, 78, "Integration branch push", 18, "BodyBold")
        dtext(d, 565, 78, "No matching push trigger", 18, "BodyBold", AMBER)
        dtext(d, 24, 42, "Correctness matrix failure", 18, "BodyBold")
        dtext(d, 565, 42, "continue-on-error: true", 16, "Code", RED)
    elif fid == "F10":
        dtext(d, 24, 127, "ONE PROPERTY / TWO STANDARD SPELLINGS", 13, "BodyBold", GREEN)
        dtext(d, 24, 79, "-Dmaven.repo.local=...", 17, "Code")
        dtext(d, 850, 79, "rejected", 20, "Display", GREEN)
        dtext(d, 24, 40, "-D maven.repo.local=...", 17, "Code")
        dtext(d, 850, 40, "forwarded", 20, "Display", RED)
    elif fid == "F11":
        dtext(d, 24, 127, "SMALL EXTRACTIONS / AFTER SEMANTIC REPAIRS", 13, "BodyBold", GREEN)
        for i, label in enumerate(["Argument layout", "Boxing and spills", "Comparison properties"]):
            x = 24+i*326
            rounded(d, x, 51, 300, 51, WHITE, LINE)
            dtext(d, x+150, 70, label, 16, "BodyBold", INK, "middle")
        dtext(d, 24, 22, "Preserve generated behavior and byte size; keep cleanup separate from correctness fixes.", 12.5, fill=MUTED)
    elif fid == "F12":
        dtext(d, 24, 127, "ONE OVERVIEW / TWO CURRENT-TENSE CLAIMS", 13, "BodyBold", GREEN)
        dtext(d, 24, 76, "ref.eq deferred", 23, "Display", AMBER)
        dtext(d, 560, 76, "ref.eq implemented", 23, "Display", GREEN)
        dtext(d, 24, 27, "Label historical snapshots and keep one current implementation / validation status table.", 12.5, fill=MUTED)
    elif fid == "F13":
        dtext(d, 24, 127, "IMPORTED TABLE / SLOT FILLED BY AN INITIALISER", 13, "BodyBold", GREEN)
        dtext(d, 24, 76, "fork, all four pairings", 18, "BodyBold")
        dtext(d, 400, 76, "-1", 29, "Display", RED)
        dtext(d, 540, 76, "upstream bce75ae6", 18, "BodyBold")
        dtext(d, 950, 76, "42", 29, "Display", GREEN, "end")
        dtext(d, 24, 28, "The importer runs its own function at that index. A conflict-free merge of upstream closes it.", 12.5, fill=MUTED)
    elif fid == "F14":
        dtext(d, 24, 127, "VALIDATOR / BOTTOM TYPE OF THE ANY HIERARCHY", 13, "BodyBold", GREEN)
        dtext(d, 24, 78, "(ref null none) as anyref", 16, "Code")
        dtext(d, 950, 78, "accepted", 20, "Display", GREEN, "end")
        dtext(d, 24, 42, "(ref null none) as eqref", 16, "Code")
        dtext(d, 950, 42, "rejected", 20, "Display", RED, "end")
        dtext(d, 24, 12, "Same in fork and upstream: heapTypeSubtype asks any <: target instead of target <: any.", 10.5, fill=MUTED)
    return d


def scaled(drawing, width=CONTENT):
    factor = width / drawing.width
    result = Drawing(width, drawing.height * factor)
    result.add(drawing, name="diagram")
    drawing.scale(factor, factor)
    return result


def save_figure(name, drawing):
    FIGURES.mkdir(parents=True, exist_ok=True)
    renderSVG.drawToFile(drawing, str(FIGURES / (name + ".svg")))
    svg = FIGURES / (name + ".svg")
    vector = svg.read_text()
    for alias, family in [("BodyBold", "Roboto"), ("Body", "Roboto"),
                          ("Display", "Liberation Serif"), ("Code", "DejaVu Sans Mono")]:
        vector = vector.replace("font-family: " + alias, "font-family: " + family)
    svg.write_text(vector)
    with tempfile.TemporaryDirectory(prefix="endive-report-figure-") as directory:
        file = Path(directory) / "figure.pdf"
        renderPDF.drawToFile(drawing, str(file))
        subprocess.run([
            POPPLER, "-singlefile", "-scale-to", "1800", "-png", str(file),
            str(FIGURES / name),
        ], check=True, capture_output=True)


def strip_presentation(text):
    return re.sub(r"<!-- DESIGN:BEGIN .*?-->.*?<!-- DESIGN:END -->\n*", "", text, flags=re.S)


def enhance_markdown():
    text = strip_presentation(REPORT.read_text())
    title, rest = text.split("\n", 1)
    text = title + "\n\n" + rest.lstrip("\n")
    prefix = "compiler/docs/data/fork-audit-2026-10-25/presentation/figures"

    def addition(name, body):
        return "<!-- DESIGN:BEGIN " + name + " -->\n" + body.strip() + "\n<!-- DESIGN:END -->\n\n"

    start = text.index("\n") + 1
    front = addition("front", f"""

![Fix status of the 14 audit findings: 12 fixed, synced or cleaned up, F04 partly, F08 disclosed]({prefix}/fix-status.png)

**[Read the designed PDF](output/pdf/Endive_Fork_Audit_2026-10-25.pdf)** ·
[Reproducers and evidence](compiler/docs/data/fork-audit-2026-10-25/) ·
[Change sequence](#7-proposed-change-sequence-and-acceptance-matrix)

### Reading guide

| Your purpose | Start here |
| --- | --- |
| Understand the decision in two minutes | [Assessment](#1-assessment) and [findings register](#findings-at-a-glance) |
| Review the compiler regressions | [F01: loop typing](#f01--p1-loop-inputs-retain-an-unsound-first-iteration-type) and [F02: cache keys](#f02--p1-option-suffixes-break-the-real-directory-cache) |
| Plan implementation | [Prioritized work packages and acceptance matrix](#7-proposed-change-sequence-and-acceptance-matrix) |
| Present the work | [Presentation outline](#8-presentation-outline); the PDF provides one page per finding |

**Contents:** [Assessment](#1-assessment) · [Scope and existing changes](#2-snapshot-scope-and-evidence-boundaries) ·
[Correctness](#3-correctness-and-execution-findings) · [Performance and evidence](#4-performance-and-evidence-findings) ·
[Build and maintenance](#5-build-automation-and-maintainability-findings) · [Validation](#6-validation-performed) ·
[Change sequence](#7-proposed-change-sequence-and-acceptance-matrix) · [Presentation](#8-presentation-outline)

---
""")
    text = text[:start] + "\n" + front + text[start:].lstrip("\n")
    target = "merge cases, but does not protect loop-entry parameters.\n"
    text = text.replace(target, target + "\n" + addition("loop", f"![The declared eqref loop input must include both first-entry struct and back-edge i31 values]({prefix}/loop-type-flow.png)"), 1)
    target = "`IllegalArgumentException`, so the error aborts compilation rather than behaving as a cache miss.\n"
    text = text.replace(target, target + "\n" + addition("cache", f"![Adding an option suffix after Base64 padding breaks the directory-cache key contract]({prefix}/cache-key-contract.png)"), 1)
    target = "```mermaid\n"
    text = text.replace(target, addition("pipeline", f"![Compiler pipeline and the type-proof and cache-contract failure points]({prefix}/compiler-pipeline.png)") + target, 1)
    REPORT.write_text(text)


class Cover(Flowable):
    def __init__(self):
        super().__init__()
        self.width, self.height = CONTENT, 694

    def draw(self):
        c = self.canv
        c.setFillColor(GREEN)
        c.setFont("BodyBold", 10)
        c.drawString(0, 675, "ENGINEERING REVIEW / 25 OCTOBER 2026")
        c.setFillColor(INK)
        c.setFont("Display", 58)
        c.drawString(-2, 579, "Endive")
        c.drawString(-2, 518, "fork audit")
        c.setFont("Body", 14)
        c.setFillColor(MUTED)
        c.drawString(0, 475, "Compiler correctness, integration, and evidence")
        c.setStrokeColor(GREEN)
        c.setLineWidth(3)
        c.line(0, 445, 65, 445)
        note = Paragraph(
            "A fork with useful compiler improvements.<br/>Two regressions found, both fixed the same day.",
            ParagraphStyle("cover-note", fontName="Display", fontSize=23, leading=28, textColor=INK),
        )
        note.wrap(CONTENT - 10, 100)
        note.drawOn(c, 0, 350)
        for x, count, label, sub, color in [
            (0, "14", "FINDINGS", "4 P1 / 8 P2 / 2 P3", INK),
            (180, "02", "FORK REGRESSIONS", "Wrong result / cache failure", RED),
            (360, "12", "RESOLVED", "F04 partly / F08 disclosed", GREEN),
        ]:
            c.setFillColor(color)
            c.setFont("Display", 43)
            c.drawString(x, 259, count)
            c.setFont("BodyBold", 8)
            c.drawString(x, 235, label)
            c.setFont("Body", 7.8)
            c.setFillColor(MUTED)
            c.drawString(x, 218, sub)
        c.setStrokeColor(LINE)
        c.setLineWidth(.8)
        c.line(0, 188, CONTENT, 188)
        c.setFont("BodyBold", 8)
        c.setFillColor(GREEN)
        c.drawString(0, 161, "AUDITED SNAPSHOT")
        c.setFont("Code", 9)
        c.setFillColor(INK)
        c.drawString(0, 141, "907750cfddb1 / fuse-branch-conditions")
        c.setFont("Body", 9)
        c.setFillColor(MUTED)
        c.drawString(0, 117, "Compared with upstream 1580f5349ae0; second pass against bce75ae6")
        c.drawString(0, 99, "Fixes on fuse-branch-conditions after rebasing onto bce75ae6 (section 09)")
        c.drawString(0, 70, "Findings describe the audited snapshot; the fix status records what changed since.")
        c.drawString(0, 52, "Designed edition of the 25 October audit. Evidence limits remain explicit.")


class AuditDoc(BaseDocTemplate):
    def __init__(self, filename, total=None):
        super().__init__(
            filename, pagesize=A4, leftMargin=MARGIN, rightMargin=MARGIN,
            topMargin=58, bottomMargin=49, title="Endive fork audit | 25 October 2026",
            author="Engineering audit", subject="Compiler correctness, integration and evidence",
        )
        self.total = total
        frame = Frame(MARGIN, 46, CONTENT, HEIGHT - 103, leftPadding=0, rightPadding=0,
                      topPadding=0, bottomPadding=0, id="body")
        self.addPageTemplates(PageTemplate(id="audit", frames=[frame], onPage=self.decorate))

    def decorate(self, c, doc):
        c.saveState()
        c.setFillColor(PAPER)
        c.rect(0, 0, WIDTH, HEIGHT, stroke=0, fill=1)
        if doc.page != 1:
            c.setFillColor(GREEN)
            c.setFont("BodyBold", 7.8)
            c.drawString(MARGIN, HEIGHT - 30, "ENDIVE / FORK AUDIT")
            c.setFillColor(MUTED)
            c.setFont("Body", 7.4)
            c.drawRightString(WIDTH-MARGIN, HEIGHT-30, "25 OCT 2026  /  907750cfddb1")
            c.setStrokeColor(LINE)
            c.setLineWidth(.5)
            c.line(MARGIN, HEIGHT-39, WIDTH-MARGIN, HEIGHT-39)
        c.setStrokeColor(LINE)
        c.setLineWidth(.5)
        c.line(MARGIN, 35, WIDTH-MARGIN, 35)
        c.setFont("Body", 7)
        c.setFillColor(MUTED)
        c.drawString(MARGIN, 21, "AUDIT SNAPSHOT  /  FINDINGS, PROPOSED CHANGES AND FIX STATUS")
        c.setFont("BodyBold", 8)
        c.setFillColor(INK)
        c.drawRightString(WIDTH-MARGIN, 20, f"{doc.page:02d}" + (f" / {self.total:02d}" if self.total else ""))
        c.restoreState()


def parse_report():
    text = strip_presentation(REPORT.read_text())
    positions = list(re.finditer(r"(?m)^## (\d+)\. (.+)$", text))
    sections = {}
    for i, match in enumerate(positions):
        stop = positions[i+1].start() if i+1 < len(positions) else len(text)
        sections[int(match[1])] = (match[2], text[match.end():stop].strip())
    findings = []
    for number in [3, 4, 5]:
        body = sections[number][1]
        matches = list(re.finditer(r"(?m)^### (F\d\d) [—-] (P\d): (.+)$", body))
        for i, match in enumerate(matches):
            stop = matches[i+1].start() if i+1 < len(matches) else len(body)
            findings.append((match[1], match[2], match[3], body[match.end():stop].strip(), number))
    assert sorted(f[0] for f in findings) == [f"F{i:02}" for i in range(1, 15)], findings
    return sections, findings


def markdown_rows(text):
    rows = []
    for line in text.splitlines():
        if line.startswith("|"):
            row = [cell.strip() for cell in line.strip().strip("|").split("|")]
            if not all(re.fullmatch(r":?-+:?", cell) for cell in row):
                rows.append(row)
    return rows


def finding_register(rows):
    data = []
    for i, row in enumerate(rows):
        if i == 0:
            data.append([Paragraph("FINDING", STYLES["th"]), Paragraph("PRIORITY", STYLES["th"]),
                         Paragraph("ATTRIBUTION AND EVIDENCE", STYLES["th"])])
            continue
        fid, priority, title, source, evidence = row
        color = {"P1": RED, "P2": AMBER, "P3": GREEN}[priority]
        data.append([
            Paragraph(f'<a href="#{fid}" color="#21624F"><b>{fid}</b></a>  ' + markup(title), STYLES["table"]),
            Paragraph(f'<font color="{color.hexval()}"><b>{priority}</b></font>', STYLES["table"]),
            Paragraph(markup("**"+source+".** "+evidence), STYLES["table"]),
        ])
    t = Table(data, colWidths=[CONTENT*.47, CONTENT*.12, CONTENT*.41], repeatRows=1)
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), GREEN), ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [WHITE, PALE]),
        ("LEFTPADDING", (0, 0), (-1, -1), 9), ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 5), ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    return t


def change_cards(rows):
    cards = []
    card_style = ParagraphStyle("card", parent=STYLES["body"], fontSize=9.2, leading=12)
    for family, benefit, qualification in rows[1:]:
        content = [
            para("**"+family+"**", "h3"),
            Paragraph(markup(benefit), card_style),
            Paragraph('<font color="#63746E">'+markup(qualification)+"</font>", card_style),
        ]
        cards.append(content)
    table_rows = [[cards[i], cards[i+1]] for i in range(0, len(cards), 2)]
    t = Table(table_rows, colWidths=[CONTENT/2, CONTENT/2])
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("BACKGROUND", (0, 0), (-1, -1), WHITE),
        ("BOX", (0, 0), (-1, -1), .5, LINE),
        ("INNERGRID", (0, 0), (-1, -1), .5, LINE),
        ("LEFTPADDING", (0, 0), (-1, -1), 11), ("RIGHTPADDING", (0, 0), (-1, -1), 11),
        ("TOPPADDING", (0, 0), (-1, -1), 4), ("BOTTOMPADDING", (0, 0), (-1, -1), 11),
    ]))
    return t


def fix_rows(sections):
    """Section 9 table: finding id -> (status, commit); rows like "F06, F07" cover several."""
    result = {}
    for row in markdown_rows(sections[9][1])[1:]:
        for fid in re.findall(r"F\d\d", row[0]):
            result[fid] = (row[1], row[2])
    return result


def story():
    sections, findings = parse_report()
    fixes = fix_rows(sections)
    assert sorted(fixes) == [f"F{i:02}" for i in range(1, 15)], sorted(fixes)
    result = [Cover(), PageBreak()]

    assessment, register = sections[1][1].split("### Findings at a glance", 1)
    result += [PageHeading("01 / EXECUTIVE BRIEFING", "Useful work. Two regressions, both fixed.", "assessment")]
    result += blocks(assessment)
    result += [Spacer(1, 6), para("Reading path", "h3")]
    result += [para("**Decision:** assessment, finding register and fix status. **Implementation:** F01-F14, the change sequence "
                    "and section 09. **Presentation:** the existing-change overview, the two failure diagrams, and the final speaking outline. "
                    "Findings and probe results describe the audited snapshot; section 09 records the fixes and how each was verified."),
               PageBreak()]

    result += [PageHeading("02 / FINDINGS REGISTER", "Fourteen findings, three priorities", "register")]
    intro, rest = register.split("| ID", 1)
    after = re.sub(r"(?m)^\|.*\n?", "", rest.split("\n", 1)[1])
    result += blocks(intro)
    result += [finding_register(markdown_rows(register)), Spacer(1, 13)]
    result += blocks(after)
    result += [para("**Read the attribution column carefully.** New fork regressions, inherited runtime limitations, "
                    "historical performance evidence, and maintenance observations have different implications.", "small"), PageBreak()]

    context, changed = sections[2][1].split("### What is already changed in the fork", 1)
    changed, drift = changed.split("### Upstream drift since the comparison commit (second pass)", 1)
    drift, pipeline_text = drift.split("```mermaid", 1)
    result += [PageHeading("03 / SCOPE AND PROVENANCE", "A pinned, bounded audit", "scope")]
    metadata = (
        "**Audited revision:** `907750cfddb112c635d1af2e5375dbecc79de262`<br/>"
        "**Branch:** `fuse-branch-conditions`<br/>"
        "**Upstream comparison:** `1580f5349ae0ce9a771fa42ee8397ba6d2553685`"
    )
    result += [Paragraph(markup(metadata).replace("&lt;br/&gt;", "<br/>"), STYLES["sources"])]
    result += blocks(context)
    result += [PageBreak(), PageHeading("04 / IMPLEMENTATION OVERVIEW", "What the fork already changes", "changes")]
    result += [change_cards(markdown_rows(changed)), Spacer(1, 12), scaled(pipeline_diagram()), Spacer(1, 12)]
    result += blocks(re.sub(r"(?m)^\|.*\n?", "", changed))
    result += blocks(pipeline_text.split("```", 1)[1])
    result += [para("Upstream drift since the comparison commit (second pass)", "h3")]
    result += blocks(drift)
    result += [PageBreak()]

    for fid, priority, title, body, category in findings:
        accent = {"P1": RED, "P2": AMBER, "P3": GREEN}[priority]
        category_name = {3: "CORRECTNESS AND EXECUTION", 4: "PERFORMANCE AND EVIDENCE", 5: "BUILD AND MAINTENANCE"}[category]
        attribution = {"F01":"Fork regression", "F02":"Fork regression", "F03":"Inherited runtime defect",
                       "F04":"Inherited runtime limitation", "F05":"Fork policy / historical local evidence",
                       "F06":"Benchmark tooling", "F07":"Benchmark tooling", "F08":"Evidence packaging",
                       "F09":"Inherited CI configuration", "F10":"Fork build tooling", "F11":"Maintenance observation",
                       "F12":"Fork documentation", "F13":"Inherited; fixed upstream", "F14":"Inherited validator defect"}[fid]
        display_title = {
            "F01": "Unsound equality in reference loops",
            "F02": "Option keys break the directory cache",
            "F03": "Tail-call cycles ignore interruption",
            "F04": "Linked indirect tail calls are unsupported",
            "F05": "Smaller switches can execute slower",
            "F06": "Output checks can erase wrong answers",
            "F07": "Unequal samples masquerade as pairs",
            "F08": "The archive cannot replay the full claim",
            "F09": "The integration CI gate is too weak",
            "F10": "The Maven guard misses valid syntax",
            "F11": "Repeated bookkeeping raises review cost",
            "F12": "The overview contradicts itself",
            "F13": "Linked tables call the wrong function",
            "F14": "Valid GC nulls fail validation",
        }[fid]
        status, commit = fixes[fid]
        result.append(PageHeading(f"{fid} / {priority} / {category_name}", display_title, fid,
                                  accent, f"{attribution}  /  Now: {status.lower()} ({plain(commit)}, section 09)"))
        # A common visual language makes every finding readable as a standalone review page.
        result += [scaled(finding_visual(fid)), Spacer(1, 12)]
        result += blocks(body)
        result += [PageBreak()]

    validation, reproduction = sections[6][1].split("### Reproduce the focused diagnostics", 1)
    validation, second = validation.split("### Second-pass validation (upstream re-check)", 1)
    commands = re.search(r"```sh\n(.*?)```", validation, re.S).group(1)
    validation = re.sub(r"```sh.*?```", "", validation, flags=re.S)
    result += [PageHeading("05 / VALIDATION", "Passing suites, failing interactions", "validation")]
    result += blocks(validation)
    result += [para("Second-pass validation (upstream re-check)", "h3")]
    result += blocks(second)
    result += [PageBreak(), PageHeading("06 / REPRODUCTION", "From findings to a repeatable check", "reproduce")]
    result += [para("Historical audit commands", "h3"), CodePanel(commands, "EXECUTED DURING THE AUDIT")]
    result += blocks(reproduction)
    result += [para("Evidence bundle", "h3")]
    result += [para("[Reproducer sources, binary fixtures, and recorded outcomes](compiler/docs/data/fork-audit-2026-10-25/). "
                    "The [validation summary](compiler/docs/data/fork-audit-2026-10-25/validation-summary.json) records per-run outcomes; "
                    "the [manifest](compiler/docs/data/fork-audit-2026-10-25/manifest.json) pins source and evidence hashes.")]
    result += [para("Scratch paths in the executed commands identify the original audit run. To repeat it in another environment, "
                    "choose an isolated Maven repository and build the required dependencies first.", "small"), PageBreak()]

    result += [PageHeading("07 / CHANGE SEQUENCE", "Eight reviewable work packages", "roadmap")]
    result += blocks(re.sub(r"(?m)^\|.*\n?", "", sections[7][1]))   # ordering notes lead the list
    road_rows = markdown_rows(sections[7][1])
    for row in road_rows[1:]:
        name, change, acceptance, closes = row
        package = name.split(" ")[0]
        # Compact spacing keeps all eight packages on one page.
        group = [Paragraph(markup("**"+name+"**  /  "+closes), ROADMAP_H3), Paragraph(markup(change), ROADMAP_BODY),
                 Paragraph(markup("**Acceptance:** "+acceptance), ROADMAP_ACCEPTANCE)]
        result.append(KeepTogether(group))
    result += [PageBreak(), PageHeading("08 / PRESENTATION OUTLINE", "Explain the contribution and its limits", "presentation")]
    result += blocks(sections[8][1])
    result += [Spacer(1, 15), scaled(pipeline_diagram()), Spacer(1, 15),
               para("**Discussion close:** agree on the acceptance gate for A/B before broadening the optimization work. "
                    "Keep correctness, representation limits, exact byte sizes, and performance evidence separate.", "proposal")]

    result += [PageBreak(), PageHeading("09 / FIX STATUS", "Fixed the same day, one commit per finding", "fixes")]
    result += [scaled(fix_status()), Spacer(1, 14)]
    result += blocks(sections[9][1])
    return result


def build():
    FIGURES.mkdir(parents=True, exist_ok=True)
    PDF.parent.mkdir(parents=True, exist_ok=True)
    for name, factory in [("fix-status", fix_status), ("loop-type-flow", loop_diagram),
                          ("cache-key-contract", cache_diagram), ("compiler-pipeline", pipeline_diagram)]:
        save_figure(name, factory())
    enhance_markdown()
    with tempfile.TemporaryDirectory(prefix="endive-report-layout-") as directory:
        draft = Path(directory) / "draft.pdf"
        AuditDoc(str(draft)).build(story())
        count = len(PdfReader(draft).pages)
    AuditDoc(str(PDF), count).build(story())
    final = PdfReader(PDF)
    assert len(final.pages) == count
    text = "\n".join(page.extract_text() for page in final.pages)
    for i in range(1, 15):
        assert f"F{i:02}" in text
    print(json.dumps({"pdf": str(PDF), "pages": count, "bytes": PDF.stat().st_size,
                      "report_sha256": hashlib.sha256(REPORT.read_bytes()).hexdigest()}, indent=2))


if __name__ == "__main__":
    import sys
    if sys.argv[1:] == ["--fix-status"]:
        save_figure("fix-status", fix_status())   # only this figure; no PDF, no Markdown change
    else:
        build()
