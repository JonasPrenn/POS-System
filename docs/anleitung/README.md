# Anleitung

`../Anleitung-VereinsDeckel.pdf` entsteht aus `anleitung.html` und den Bildern in `bilder/`.
Geändert wird `anleitung.html`, nie das PDF — und zwar bei jedem Commit auf main, der etwas
Sichtbares ändert (CLAUDE.md, „Die Anleitung zieht mit main mit“).

    python3 docs/tools/anleitung/anleitung.py alles app-bar verwaltung-kasse   # diese Bilder neu, dann das PDF
    python3 docs/tools/anleitung/anleitung.py pdf                              # nur Text geändert
    python3 docs/tools/anleitung/anleitung.py alles                            # alle Bilder neu (selten)

`alles` baut den Server aus diesem Stand, startet ihn mit einem Wegwerf-PostgreSQL in Docker (nur
auf 127.0.0.1), spielt einen erfundenen Verein ein („Verbindung Musterbude“: Mitglieder, zwei
Wochen Thekenbetrieb, Lieferungen, eine Bierrechnung, Online-Aufladung, Systemverwaltung), macht
die Bilder und druckt das PDF. Die Schritte gibt es auch einzeln: `start`, `web`, `app`, `pdf`,
`stop`.

Ein Bild heißt wie seine Datei ohne `.jpg`; welche es gibt, steht in `anleitung.html` (ein
falscher Name wird gemeldet). Neu gemacht werden nur die Bilder, deren Bildschirm sich geändert
hat: Jedes neue Bild trägt andere Uhrzeiten, landet als neue Datei in der Geschichte des Repos —
und die holt auch der Server bei jedem Update. Alle auf einmal sind gut 5 MB. Ein neu gedrucktes
PDF mit unveränderten Bildern kostet dagegen fast nichts: Git speichert davon nur den Unterschied
(gemessen: gut 20 KB statt 4,4 MB).

- **Verwaltung, Systemverwaltung, Mitglieder-Deckel** (`verwaltung-…`, `system-…`, `konto-…`):
  Chrome ohne Fenster (`chrome.py`), hell, 1280 × 820 bzw. Handygröße.
- **App** (`app-…`): ein laufender Android-Emulator (`app.py`). Die App wird mit
  `-PappIdSuffix=.anleitung` gebaut und als eigenes Paket installiert — die Entwicklungs-App und
  ihre Daten bleiben unberührt —, an den Wegwerf-Server gekoppelt und durch die Bildschirme
  geführt, danach wieder deinstalliert. Im Format des Vereinstablets: `adb shell wm size
  1920x1200`, Dichte 320. Status- und Navigationsleiste schneidet das Skript ab. Bedient wird
  über die Texte auf dem Bildschirm; findet es einen Knopf nicht mehr, steht in `app.py`, welchen
  es sucht.
- **PDF**: Chrome druckt `anleitung.html` (A4, Inhaltsverzeichnis aus den Überschriften, als
  Lesezeichen und anklickbar); Version, Datum und Commit kommen beim Drucken auf das Titelblatt.

Alle Zugänge im Skript sind Demo-Werte für den Wegwerf-Server; echte Daten kommen nie hinein.
