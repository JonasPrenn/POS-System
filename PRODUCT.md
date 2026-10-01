# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

Die App ist Compose Multiplatform: dieselbe Oberfläche läuft auf Android (Vereinsgerät Galaxy Tab
Active3, 960 × 600 dp) und im iPad-Simulator. Sie hat eine gemeinsame Designsprache und keine
eigene pro Betriebssystem. Daneben gibt es zwei Web-Oberflächen am Server: die Verwaltung
(`/verwaltung`) und den Mitglieder-Deckel (`/konto/<kürzel>`), serverseitig gerendert, ohne Skripte.

## Users

- **Person im Bardienst:** ein Mitglied im Schichtdienst, keine geschulte Kassiererin. Nächsten
  Samstag steht jemand anders hinter der Theke. Es bucht Getränke und Speisen auf den Deckel
  (anschreiben), kassiert bar oder mit Karte, lädt Guthaben auf, öffnet und schließt die Kasse.
- **Kassier und Vorstand:** arbeiten in der Web-Verwaltung am Schreibtisch oder am Telefon:
  Mitglieder, Abrechnungen, Kassenbuch, Lager, Einkauf, Bücher, Geräte.
- **Mitglieder:** sehen ihren Deckel im Browser und laden ihn online auf.
- **Hauptadmin:** betreibt den Server für mehrere Vereine.

Der erste Verein ist eine Studentenverbindung (Couleurname im Schema). Das Grunddesign bleibt
für jeden Verein nutzbar (Sportverein, Musikverein, Feuerwehr) und ist auf den ersten hin
abgestimmt (Antwort des Besitzers, 1. Oktober 2026).

## Product Purpose

Ein Anschreibsystem mit angeschlossener Kasse für ein Vereinsheim. Ein *Deckel* ist der
Bierdeckel, auf dem angeschrieben wird. Erfolg heißt: Jedes Mitglied kann ohne Einweisung eine
Schicht machen, kein Buchungsfehler vor der Schlange, und am Monatsende stimmen Kasse, Deckel
und Bücher, ohne dass jemand nachrechnet.

## Positioning

Kein Ladenkassensystem, sondern der Deckel als Konto jedes Mitglieds. Daneben eine Kasse, die
nur offen kassiert, mehrere Theken, die offline weiterbuchen, und Bücher, die aus den Buchungen
hergeleitet statt fortgeschrieben werden.

## Operating Context

- Abends dunkler, lauter Raum. Mittags heller Biergarten mit ausgewaschenem Bildschirm.
  Nasse Finger, ein Glas in der anderen Hand, eine Schlange vor der Theke.
- Tablet auf der Theke, oft quer. Unter 700 dp Höhe wird verdichtet.
- Bezahlen: auf den Deckel (ein Tipp, ohne zweiten Schritt), bar mit Wechselgeld, Karte über
  SumUp. Die Kasse wird im Warenkorb geöffnet: mit Barkasse und gezähltem Wechselgeld oder ohne.
- Die Verwaltung läuft am Schreibtisch und am Telefon, Kassenbericht und Abrechnung als PDF.

## Capabilities and Constraints

Alle Funktionsentscheidungen in `CLAUDE.md` unter „Feste Entscheidungen“ sind bindend und werden
durch keine Designarbeit berührt. Für die Oberfläche heißt das:

- **Farbe bedeutet etwas:** Grün ist Geld und Bestätigung. Messing ist der Deckel. Blau ist
  Karte und Auswertung. Bernstein ist Aufmerksamkeit. Rot ist Zerstörung und Fehlschlag, nie
  etwas anderes. Die Vereinsfarbe färbt nur Navigation, Kopfzeile und Mitglieder-Symbole.
- **Geld ist das Lauteste,** mit Tabellenziffern.
- **Daumen zuerst:** Auf dem Verkaufsweg ist alles mindestens 56 dp groß und liegt in den
  unteren zwei Dritteln. Der Deckel bucht mit einem Tipp.
- **Keine losen Werte:** `Spacing`, `Shapes`, `Type.kt`, `Money`, `Quantity`, `platform/Time.kt`.
- **Breite, nicht Ausrichtung,** dazu die Fensterhöhe (`CompactWindowHeight` 700 dp). Dialoge
  werden auf 960 × 600 dp geprüft.
- **Icons liegen im Repo,** erzeugt von `docs/tools/gen_icons.py` und `gen_web_icons.py`.
- Compose von JetBrains-Koordinaten (CMP 1.12, material3 1.9). Die Verwaltung hat keine Skripte
  und keine Inline-Styles, CSP `default-src 'none'` mit genau einem Stylesheet. Dialoge sind
  HTML-Popover. Die Token in `app.css` sind die aus `ui/theme`.
- Ohne offene Kasse wird nicht kassiert. Ein abgemeldetes Gerät heißt „Abgemeldet“, nicht
  „Offline“. Unter der Mindestversion zeigt die App nur `UpdateRequiredScreen`.
- Die Überarbeitung darf Optik und Anordnung der Bildschirme ändern. Abläufe, Funktionen und
  alle festen Entscheidungen bleiben (Besitzer, 1. Oktober 2026).
- Schrift ist die Systemschrift in App und Verwaltung. Eine eigene wäre erlaubt gewesen,
  gewählt wurde sie nicht (Besitzer, 1. Oktober 2026).

## Brand Commitments

- Name: VereinsDeckel. Der Bierdeckel ist das Bild des Produkts.
- Die Vereinsfarbe ist frei wählbar (zehn Vorgaben) und darf nie eine Bedeutungsfarbe ersetzen.
- Nutzertexte deutsch, Code-Bezeichner englisch, Kommentare deutsch.
- Ein roter Faden durch App, Verwaltung und Mitglieder-Deckel. Nichts darf nach KI oder
  Baukasten aussehen (Besitzer, 1. Oktober 2026).
- Gewählte Richtung ist der Kassen-Standard, ohne Ironie umgesetzt. Maßstab ist die
  Verarbeitung von SumUp, ohne deren Marke zu übernehmen. Schrift ist die Systemschrift
  (Besitzer, 1. Oktober 2026).

## Evidence on Hand

- Bisheriges Designsystem „Die ruhige Theke“: https://claude.ai/artifact/51wNmrLFCfEQV4dgP5BBo8
  (umgesetzt in `ui/theme`, `ui/components`, `server/.../web/app.css`).
- Freigegebene Entwurfsfläche der Verwaltung: https://claude.ai/artifact/Gzuq5UkyXtgZa1eoHUiZjQ
- Es gibt keine Fotos, kein Logo des Produkts und keine echten Mitgliederdaten im Repo. Beispiel-
  daten in Entwürfen sind erfunden und als solche zu behandeln.

## Product Principles

1. Wer heute hinter der Theke steht, braucht keine Einweisung.
2. Ein Fehler vor der Schlange ist teurer als ein zusätzlicher Tipp anderswo. Deshalb sind
   Bedeutungen fest und Beträge unübersehbar.
3. Das Gerät dient dem Abend, nicht umgekehrt: Der Verkauf wartet nie auf das Netz.
4. Was ein Update übersteht, bleibt: Design ändert nichts an Daten, Drahtformat oder Abläufen.

## Accessibility & Inclusion

- Text in beiden Themen mindestens WCAG AA, Bedienelemente mindestens 3:1. Lesbar im Biergarten
  bei Sonne und im dunklen Raum.
- Tippziele mindestens 48 dp, auf dem Verkaufsweg 56 dp. Die Systemschriftgröße wird beachtet.
- Bedeutung nie nur über Farbe: Vorzeichen, Symbol oder Wort gehören dazu.
