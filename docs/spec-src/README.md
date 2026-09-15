# Quelle der Server-Spezifikation

`../VereinsDeckel-Server-und-API.pdf` wird aus diesen beiden Dateien erzeugt:

    pip install reportlab
    python3 build_spec.py

`spec_style.py` hält Palette, Typografie und die Bausteine (Codeblock, Tabelle,
Hervorhebung); `build_spec.py` den Inhalt. Die Palette ist dieselbe wie im
Designsystem der App, damit das Dokument erkennbar zum Produkt gehört.

Inhaltliche Änderungen gehören in `build_spec.py` — das PDF selbst wird nicht
von Hand bearbeitet, sonst laufen Quelle und Ergebnis auseinander.
