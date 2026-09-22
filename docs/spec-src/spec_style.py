"""Gemeinsames Layout für die VereinsDeckel-Serverspezifikation.

Die Palette ist die des Apps-Designsystems (Pine / Brass / Harbor / Ember), damit das
Dokument erkennbar zum Produkt gehört und nicht wie ein fremdes Beiblatt wirkt.
"""
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import Paragraph, Preformatted, Spacer, Table, TableStyle

PINE = colors.HexColor("#146B4C")
BRASS = colors.HexColor("#7A5314")
HARBOR = colors.HexColor("#2B5C87")
EMBER = colors.HexColor("#A03E00")
SIGNAL = colors.HexColor("#B3261E")
INK = colors.HexColor("#191D1A")
INK2 = colors.HexColor("#414942")
INK3 = colors.HexColor("#717971")
RULE = colors.HexColor("#C9D1C8")
GROUND2 = colors.HexColor("#ECEFEA")
CODEBG = colors.HexColor("#F4F6F2")
PINE_SOFT = colors.HexColor("#E3F5EB")
EMBER_SOFT = colors.HexColor("#FDEBDF")

_base = getSampleStyleSheet()

S = {}
S["title"] = ParagraphStyle(
    "vdTitle", parent=_base["Title"], fontName="Helvetica-Bold",
    fontSize=30, leading=34, textColor=INK, alignment=TA_LEFT, spaceAfter=6,
)
S["subtitle"] = ParagraphStyle(
    "vdSubtitle", parent=_base["Normal"], fontName="Helvetica",
    fontSize=13.5, leading=19, textColor=INK2, spaceAfter=4,
)
S["eyebrow"] = ParagraphStyle(
    "vdEyebrow", parent=_base["Normal"], fontName="Courier-Bold",
    fontSize=8.5, leading=12, textColor=PINE, spaceAfter=14,
)
S["h1"] = ParagraphStyle(
    "vdH1", parent=_base["Heading1"], fontName="Helvetica-Bold",
    fontSize=19, leading=23, textColor=INK, spaceBefore=4, spaceAfter=10,
)
S["h2"] = ParagraphStyle(
    "vdH2", parent=_base["Heading2"], fontName="Helvetica-Bold",
    fontSize=13, leading=17, textColor=PINE, spaceBefore=15, spaceAfter=6,
)
S["h3"] = ParagraphStyle(
    "vdH3", parent=_base["Heading3"], fontName="Helvetica-Bold",
    fontSize=10.5, leading=14, textColor=INK, spaceBefore=11, spaceAfter=4,
)
S["body"] = ParagraphStyle(
    "vdBody", parent=_base["Normal"], fontName="Helvetica",
    fontSize=9.7, leading=14.6, textColor=INK2, spaceAfter=7,
)
S["lede"] = ParagraphStyle(
    "vdLede", parent=S["body"], fontSize=11, leading=16.5, textColor=INK2, spaceAfter=11,
)
S["bullet"] = ParagraphStyle(
    "vdBullet", parent=S["body"], leftIndent=12, bulletIndent=2, spaceAfter=4,
)
S["code"] = ParagraphStyle(
    # Der eingebaute Code-Stil bringt leftIndent=36 mit, das den ganzen Block
    # einrückt und die nutzbare Zeilenbreite frisst. Hier auf null.
    "vdCode", parent=_base["Code"], fontName="Courier",
    fontSize=7.6, leading=10.4, textColor=INK,
    leftIndent=0, rightIndent=0, spaceBefore=0, spaceAfter=0,
)
S["toc"] = ParagraphStyle(
    "vdToc", parent=_base["Normal"], fontName="Helvetica",
    fontSize=9.2, leading=15.5, textColor=INK2,
)
S["tocnum"] = ParagraphStyle(
    "vdTocNum", parent=_base["Normal"], fontName="Courier",
    fontSize=9.2, leading=15.5, textColor=PINE,
)
S["caption"] = ParagraphStyle(
    "vdCaption", parent=_base["Normal"], fontName="Courier",
    fontSize=7.6, leading=11, textColor=INK3, spaceAfter=10,
)
S["cell"] = ParagraphStyle(
    "vdCell", parent=_base["Normal"], fontName="Helvetica",
    fontSize=8.2, leading=11.4, textColor=INK2,
)
S["cellb"] = ParagraphStyle(
    "vdCellB", parent=S["cell"], fontName="Helvetica-Bold", textColor=INK,
)
S["cellm"] = ParagraphStyle(
    "vdCellM", parent=S["cell"], fontName="Courier", fontSize=7.6, textColor=PINE,
)
S["th"] = ParagraphStyle(
    "vdTh", parent=_base["Normal"], fontName="Helvetica-Bold",
    fontSize=7.4, leading=10, textColor=INK3,
)
S["note"] = ParagraphStyle(
    "vdNote", parent=S["body"], fontSize=9.2, leading=13.6, spaceAfter=0,
)


def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def p(text, style="body"):
    return Paragraph(text, S[style])


def bullets(items, style="bullet"):
    return [Paragraph(t, S[style], bulletText="•") for t in items]


def code(text, caption=None, width=170 * mm):
    """Codeblock auf getöntem Grund, damit er sich vom Fließtext absetzt."""
    body = Preformatted(text.strip("\n"), S["code"])
    t = Table([[body]], colWidths=[width])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), CODEBG),
        ("BOX", (0, 0), (-1, -1), 0.5, RULE),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
    ]))
    out = [t]
    if caption:
        out += [Spacer(1, 3), Paragraph(caption, S["caption"])]
    else:
        out += [Spacer(1, 10)]
    return out


def table(header, rows, widths, align_mono=None):
    """Datentabelle. `align_mono` listet Spaltenindizes, die monospaced gesetzt werden."""
    align_mono = align_mono or []
    data = [[Paragraph(h, S["th"]) for h in header]]
    for r in rows:
        line = []
        for i, cell in enumerate(r):
            st = "cellm" if i in align_mono else "cell"
            line.append(Paragraph(cell, S[st]))
        data.append(line)
    t = Table(data, colWidths=widths, repeatRows=1)
    style = [
        ("BACKGROUND", (0, 0), (-1, 0), GROUND2),
        ("LINEBELOW", (0, 0), (-1, 0), 0.6, RULE),
        ("LINEBELOW", (0, 1), (-1, -2), 0.3, RULE),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("BOX", (0, 0), (-1, -1), 0.5, RULE),
    ]
    t.setStyle(TableStyle(style))
    return [t, Spacer(1, 11)]


def callout(title, text, tone="pine"):
    """Hervorgehobener Kasten für Entscheidungen und Warnungen."""
    bg, bar = (PINE_SOFT, PINE) if tone == "pine" else (EMBER_SOFT, EMBER)
    inner = [
        Paragraph(title, ParagraphStyle(
            "coTitle", parent=S["h3"], textColor=bar, spaceBefore=0, spaceAfter=3)),
        Paragraph(text, S["note"]),
    ]
    t = Table([[inner]], colWidths=[170 * mm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), bg),
        ("LINEBEFORE", (0, 0), (0, -1), 2.5, bar),
        ("LEFTPADDING", (0, 0), (-1, -1), 11),
        ("RIGHTPADDING", (0, 0), (-1, -1), 11),
        ("TOPPADDING", (0, 0), (-1, -1), 9),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 9),
    ]))
    return [t, Spacer(1, 12)]
