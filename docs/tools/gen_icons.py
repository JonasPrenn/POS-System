#!/usr/bin/env python3
"""Erzeugt VdIcons.kt aus den offiziellen Material-SVGs.

Grund: Das Artefakt material-icons-extended gibt es fuer Compose Multiplatform nur
bis 1.7.3, was die App an ein Kotlin von vorgestern ketten wuerde. Die 48 tatsaechlich
benutzten Icons als Pfaddaten mitzuliefern kostet eine Datei und macht die Abhaengigkeit
ueberfluessig.
"""
import os
import re
import sys

SVG_DIR = "/tmp/claude-0/-home-user-POS-System/d5e84206-15a3-5bcc-b7ca-f652320268ac/scratchpad/package/filled"
OUT = "/home/user/POS-System/shared/src/commonMain/kotlin/com/example/vereins_kassensystem/ui/icons/VdIcons.kt"

# Compose-Name -> SVG-Dateiname. autoMirrored: spiegelt in RTL-Sprachen.
ICONS = [
    ("ArrowBack", "arrow_back", True),
    ("Backspace", "backspace", True),
    ("KeyboardArrowRight", "keyboard_arrow_right", True),
    ("Label", "label", True),
    ("Login", "login", True),
    ("ReceiptLong", "receipt_long", True),
    ("AccountBalanceWallet", "account_balance_wallet", False),
    ("Add", "add", False),
    ("AddCard", "add_card", False),
    ("AddShoppingCart", "add_shopping_cart", False),
    ("Assessment", "assessment", False),
    ("Backup", "backup", False),
    ("BarChart", "bar_chart", False),
    ("Check", "check", False),
    ("Clear", "clear", False),
    ("CloudDownload", "cloud_download", False),
    ("CloudUpload", "cloud_upload", False),
    ("Contrast", "contrast", False),
    ("CreditCard", "credit_card", False),
    ("Dashboard", "dashboard", False),
    ("Delete", "delete", False),
    ("DeleteSweep", "delete_sweep", False),
    ("Edit", "edit", False),
    ("ExpandMore", "expand_more", False),
    ("Favorite", "favorite", False),
    ("FileDownload", "file_download", False),
    ("FileUpload", "file_upload", False),
    ("Folder", "folder", False),
    ("Groups", "groups", False),
    ("Inventory2", "inventory_2", False),
    ("LocalOffer", "local_offer", False),
    ("Lock", "lock", False),
    ("MoreHoriz", "more_horiz", False),
    ("MoreVert", "more_vert", False),
    ("Payments", "payments", False),
    ("People", "people", False),
    ("PersonAdd", "person_add", False),
    ("PersonRemove", "person_remove", False),
    ("PhotoCamera", "photo_camera", False),
    ("PointOfSale", "point_of_sale", False),
    ("Remove", "remove", False),
    ("Save", "save", False),
    ("Search", "search", False),
    ("SearchOff", "search_off", False),
    ("Settings", "settings", False),
    ("ShoppingCartCheckout", "shopping_cart_checkout", False),
    ("Warehouse", "warehouse", False),
    ("WarningAmber", "warning_amber", False),
]

HEAD = '''package com.example.vereins_kassensystem.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Die Icons der App, als Pfaddaten mitgeliefert.
 *
 * Compose Multiplatform veroeffentlicht `material-icons-extended` nur bis 1.7.3. Daran
 * haengen zu bleiben hiesse, Kotlin und AGP auf einem Stand von vorgestern einzufrieren,
 * nur um achtundvierzig Vektoren zu bekommen. Also liegen sie hier.
 *
 * Erzeugt aus den offiziellen Material-SVGs (@material-design-icons/svg) durch
 * `docs/tools/gen_icons.py` — von Hand geaenderte Pfade waeren beim naechsten Lauf weg.
 *
 * Die Icons werden beim ersten Zugriff gebaut und danach gehalten: ein ImageVector ist
 * unveraenderlich, und ihn pro Aufruf neu zu parsen waere in einer Liste spuerbar.
 */
object VdIcons {

    private fun icon(
        name: String,
        pathData: String,
        autoMirror: Boolean = false
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black)
        )
    }.build()
'''

TAIL = "}\n"


def path_of(svg_file):
    with open(svg_file, encoding="utf-8") as fh:
        svg = fh.read()
    paths = re.findall(r'<path[^>]*\bd="([^"]+)"', svg)
    if not paths:
        raise SystemExit("kein Pfad in %s" % svg_file)
    # Einige SVGs tragen einen leeren Platzhalterpfad ("M0 0h24v24H0z") vor dem
    # eigentlichen Motiv. Der wuerde als gefuelltes Quadrat gezeichnet.
    real = [d for d in paths if not re.fullmatch(r"M0 0h24v24H0z?V?0?z?", d.strip())]
    return " ".join(real) if real else paths[-1]


def main():
    missing = [f for _, f, _ in ICONS if not os.path.exists(os.path.join(SVG_DIR, f + ".svg"))]
    if missing:
        raise SystemExit("fehlende SVGs: %s" % ", ".join(missing))

    out = [HEAD]
    for name, fname, mirror in ICONS:
        d = path_of(os.path.join(SVG_DIR, fname + ".svg"))
        d = d.replace('"', '\\"')
        mirror_arg = ",\n            autoMirror = true" if mirror else ""
        out.append(
            '\n    val %s: ImageVector by lazy {\n'
            '        icon(\n'
            '            name = "%s",\n'
            '            pathData = "%s"%s\n'
            '        )\n'
            '    }\n' % (name, name, d, mirror_arg)
        )
    out.append(TAIL)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write("".join(out))
    print("geschrieben: %s (%d Icons)" % (OUT, len(ICONS)))


if __name__ == "__main__":
    main()
