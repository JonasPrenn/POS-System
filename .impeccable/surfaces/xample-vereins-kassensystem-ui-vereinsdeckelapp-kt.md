---
version: 1
slug: "xample-vereins-kassensystem-ui-vereinsdeckelapp-kt"
primary_target: "shared/src/commonMain/kotlin/com/example/vereins_kassensystem/ui/VereinsDeckelApp.kt"
related_targets: ["server/src/main/resources/web/app.css"]
---

# Oberfläche: VereinsDeckel App, Verwaltung und Mitglieder-Deckel

Modus: Operate. Zielgruppe, Aufgabe und Grenzen stehen in PRODUCT.md und CLAUDE.md, die festen Entscheidungen gelten unverändert.
Gewählt hat der Besitzer am 1. Oktober 2026 den Kassen-Standard, mit SumUp als Maßstab und der Systemschrift.

## Direction contract

THESIS: Der Kassen-Standard, so sauber wie SumUp: weiße Flächen auf warmem Hellgrau, Tinte als Grundfarbe, Farbe nur, wo sie Bedeutung trägt. Abgelehnt werden Materials Pastell-Tonflächen und das SaaS-Kachel-Dashboard.
OWN-WORLD: Warme Neutraltöne, Tinte #141414 für alltägliche Hauptaktionen, Grün nur für Geld und Bestätigung, Messing für den Deckel, Blau für Karte, Bernstein für Achtung, Rot nur für Fehler. Pillen-Knöpfe, Karten mit 1 px Rand statt Schatten, Systemschrift ohne Laufweite, fette Tabellenziffern.
STORY: Wer an die Theke tritt, sieht sofort Produkte, die Bestellung und eine Summe. Ein Tipp bucht. Die Verwaltung liest sich wie dasselbe Produkt am Schreibtisch.
FIRST VIEWPORT: Verkauf, 960 × 600 dp: links das Produktraster auf Hellgrau mit Kategorie-Pillen darüber, rechts die Bestellung als weiße Spalte; unten rechts die Summe groß und die volle Bezahl-Pille (56 dp).
NOTE: Die Verwaltung behält den Seitenaufbau der vom Besitzer am 22. September 2026 freigegebenen Entwurfsfläche (Übersicht mit Kennzahlen, Mitglieder mit Saldo-Feld); der Kassen-Standard ändert dort Token und Bausteine, nicht die Anordnung.
FORM: Canon (Standard), SumUp als Maßstab; Seed d62756b6.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
