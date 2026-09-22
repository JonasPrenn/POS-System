#!/usr/bin/env python3
"""Erzeugt die Icons der Web-Verwaltung aus denselben Pfaden wie die der App.

Die App bekommt ihre 48 Material-Icons als Pfaddaten aus VdIcons.kt (docs/tools/gen_icons.py);
das Web soll aus einem Schnitt sein, also zeichnet es aus denselben Pfaden. Was das Web
zusätzlich braucht (Tablet, Postfach, Drucker, Bank, Fass …), liegt als SVG neben diesem
Skript unter docs/tools/web-icons/, aus demselben Paket (@material-design-icons/svg, filled).

Aufruf aus der Repo-Wurzel:  python3 docs/tools/gen_web_icons.py
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APP_ICONS = os.path.join(ROOT, "shared/src/commonMain/kotlin/com/example/vereins_kassensystem/ui/icons/VdIcons.kt")
EXTRA_DIR = os.path.join(ROOT, "docs/tools/web-icons")
OUT = os.path.join(ROOT, "server/src/main/kotlin/com/example/vereins_kassensystem/server/web/MaterialIcons.kt")

# Web-Name -> Icon der App (VdIcons) oder Datei unter web-icons/. Die Bedeutung folgt der App:
# Übersicht = Dashboard, Mitglieder = People, Historie/Abrechnung = ReceiptLong, Kasse = PointOfSale, …
WEB = [
    ("grid", "Dashboard"), ("users", "People"), ("receipt", "ReceiptLong"), ("cash", "PointOfSale"),
    ("book", "BarChart"), ("ledger", "Assessment"), ("box", "Warehouse"), ("tag", "Inventory2"),
    ("filein", "Folder"), ("tablet", "tablet_mac.svg"), ("shield", "admin_panel_settings.svg"),
    ("history", "history.svg"), ("gear", "Settings"), ("search", "Search"), ("plus", "Add"),
    ("check", "Check"), ("alert", "WarningAmber"), ("cloudoff", "cloud_off.svg"), ("lock", "Lock"),
    ("chevron", "KeyboardArrowRight"), ("back", "ArrowBack"), ("logout", "logout.svg"),
    ("keg", "sports_bar.svg"), ("camera", "PhotoCamera"), ("close", "Clear"), ("mail", "mail.svg"),
    ("printer", "print.svg"), ("bank", "account_balance.svg"), ("download", "FileDownload"),
    ("more", "MoreHoriz"), ("edit", "Edit"), ("delete", "Delete"), ("wallet", "AccountBalanceWallet"),
    ("card", "CreditCard"), ("upload", "FileUpload"), ("label", "Label"), ("person-add", "PersonAdd"),
    ("groups", "Groups"), ("payments", "Payments"),
]


def app_paths():
    text = open(APP_ICONS, encoding="utf-8").read()
    return dict(re.findall(r'name = "(\w+)",\s*pathData = "([^"]+)"', text))


def extra_path(file_name):
    svg = open(os.path.join(EXTRA_DIR, file_name), encoding="utf-8").read()
    return " ".join(re.findall(r'<path d="([^"]+)"', svg))


def main():
    paths = app_paths()
    lines = []
    for web, source in WEB:
        d = extra_path(source) if source.endswith(".svg") else paths[source]
        lines.append(f'        "{web}" to "{d}",')
    body = "\n".join(lines)
    out = f'''package com.example.vereins_kassensystem.server.web

/**
 * Die Icons der Verwaltung: dieselben Material-Pfade wie die der App (ui/icons/VdIcons.kt),
 * damit beide Oberflächen aus einem Schnitt sind. Erzeugt von docs/tools/gen_web_icons.py —
 * nicht von Hand ändern, beim nächsten Lauf wäre es weg.
 */
object MaterialIcons {{
    val paths: Map<String, String> = mapOf(
{body}
    )
}}
'''
    open(OUT, "w", encoding="utf-8").write(out)
    print(f"{len(WEB)} Icons -> {os.path.relpath(OUT, ROOT)}")


if __name__ == "__main__":
    main()
