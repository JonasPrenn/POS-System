---
name: VereinsDeckel
description: Anschreibsystem mit angeschlossener Kasse für das Vereinsheim, im Kassen-Standard
colors:
  ink: "#141414"
  white: "#FFFFFF"
  paper: "#F2F1EC"
  stone-50: "#F4F3EF"
  stone-100: "#EDECE7"
  stone-150: "#E6E4DE"
  stone-200: "#DEDCD6"
  stone-500: "#8F8B83"
  stone-700: "#5E5B55"
  night-950: "#121211"
  night-900: "#1C1B19"
  night-850: "#242321"
  night-800: "#2C2B28"
  night-700: "#3A3835"
  night-500: "#7A766F"
  night-300: "#A8A49C"
  money: "#0B7A3B"
  money-tint: "#E3F2E7"
  money-deep: "#0B6430"
  money-night: "#46C46F"
  brass: "#76611A"
  brass-tint: "#F4EFD9"
  brass-deep: "#5E4D12"
  brass-night: "#D9BE6E"
  blue: "#1F5CC9"
  blue-tint: "#E6EDFB"
  blue-deep: "#1A4FAE"
  blue-night: "#86AEF7"
  amber: "#A84B00"
  amber-tint: "#FDEBDC"
  amber-deep: "#8A3D00"
  amber-night: "#FF9A4A"
  red: "#B8232F"
  red-tint: "#FBE6E7"
  red-deep: "#951B25"
  red-night: "#FF6B76"
  category-plum: "#7A3F72"
  category-petrol: "#0E6F7A"
  category-violet: "#5E48B5"
  category-magenta: "#A3326F"
  category-slate: "#5A5F6B"
  category-ink-blue: "#4A4F8C"
typography:
  display:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "36px"
    fontWeight: 700
    lineHeight: "44px"
    letterSpacing: "-0.25px"
  headline:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "28px"
    fontWeight: 700
    lineHeight: "34px"
    letterSpacing: "-0.3px"
  title:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: "24px"
    letterSpacing: "0"
  title-small:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "20px"
    letterSpacing: "0"
  body:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "20px"
    letterSpacing: "0"
  body-large:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: "24px"
    letterSpacing: "0"
  label:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "20px"
    letterSpacing: "0"
  label-small:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: "16px"
    letterSpacing: "0"
  money-display:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "44px"
    fontWeight: 800
    lineHeight: "52px"
    letterSpacing: "-1px"
    fontFeature: "\"tnum\""
  money-large:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "32px"
    fontWeight: 800
    lineHeight: "38px"
    letterSpacing: "-0.5px"
    fontFeature: "\"tnum\""
  money-medium:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "20px"
    fontWeight: 700
    lineHeight: "26px"
    letterSpacing: "-0.2px"
    fontFeature: "\"tnum\""
  money-small:
    fontFamily: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: "20px"
    letterSpacing: "0"
    fontFeature: "\"tnum\""
rounded:
  xs: "4px"
  sm: "10px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  pill: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  xxl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.white}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 20px"
    height: "44px"
  button-money:
    backgroundColor: "{colors.money}"
    textColor: "{colors.white}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 20px"
    height: "44px"
  button-pay:
    backgroundColor: "{colors.money}"
    textColor: "{colors.white}"
    typography: "{typography.money-medium}"
    rounded: "{rounded.pill}"
    height: "56px"
    width: "100%"
  button-deckel:
    backgroundColor: "{colors.brass}"
    textColor: "{colors.white}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 20px"
    height: "44px"
  button-outline:
    backgroundColor: "{colors.white}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 20px"
    height: "44px"
  filter-pill:
    backgroundColor: "{colors.white}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 16px"
    height: "44px"
  filter-pill-selected:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 16px"
    height: "44px"
  card:
    backgroundColor: "{colors.white}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "{spacing.lg}"
  product-tile:
    backgroundColor: "{colors.white}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "8px 12px"
    height: "88px"
  input:
    backgroundColor: "{colors.white}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    padding: "0 12px"
    height: "44px"
  chip-deckel:
    backgroundColor: "{colors.brass-tint}"
    textColor: "{colors.brass-deep}"
    typography: "{typography.label-small}"
    rounded: "{rounded.pill}"
    padding: "0 10px"
    height: "24px"
  chip-money:
    backgroundColor: "{colors.money-tint}"
    textColor: "{colors.money-deep}"
    typography: "{typography.label-small}"
    rounded: "{rounded.pill}"
    padding: "0 10px"
    height: "24px"
  chip-card:
    backgroundColor: "{colors.blue-tint}"
    textColor: "{colors.blue-deep}"
    typography: "{typography.label-small}"
    rounded: "{rounded.pill}"
    padding: "0 10px"
    height: "24px"
  chip-warning:
    backgroundColor: "{colors.amber-tint}"
    textColor: "{colors.amber-deep}"
    typography: "{typography.label-small}"
    rounded: "{rounded.pill}"
    padding: "0 10px"
    height: "24px"
  chip-error:
    backgroundColor: "{colors.red-tint}"
    textColor: "{colors.red-deep}"
    typography: "{typography.label-small}"
    rounded: "{rounded.pill}"
    padding: "0 10px"
    height: "24px"
  dialog:
    backgroundColor: "{colors.white}"
    textColor: "{colors.ink}"
    rounded: "{rounded.xl}"
    padding: "{spacing.xl}"
---

# Design System: VereinsDeckel

## Overview

**Creative North Star: "Der Kassen-Standard"**

VereinsDeckel sieht aus wie eine gute Kasse und nicht wie eine App, die eine Kasse spielt. Weiße Flächen liegen auf warmem Hellgrau, Tinte ist die Grundstimme, und Farbe steht nur dort, wo sie etwas bedeutet. Maßstab ist SumUp: sauber, ruhig, ohne Zierrat, mit großen Zielen für einen Daumen, der es eilig hat. Die Person an der Theke ist jeden Samstag eine andere, der Raum ist abends dunkel und mittags grell. Deshalb trägt jede Farbe genau eine Bedeutung, Geld hat die lauteste Schrift, und die helle wie die dunkle Palette sind beide vollwertig gemessen. Der Nachtmodus ist kein Nachgedanke, sondern der Raum am Abend.

Dasselbe System gilt für drei Oberflächen: die Compose-App auf dem Tablet (Android und iOS), die Web-Verwaltung unter `/verwaltung` und den Deckel für Mitglieder unter `/konto`. Die Token stehen in `ui/theme/` und werden einmal als CSS-Variablen in `server/src/main/resources/web/app.css` nachgezogen. Wer eine Farbe in `Color.kt` ändert, ändert sie dort mit. Die Verwaltung behält den Seitenaufbau der vom Besitzer am 22. September 2026 freigegebenen Entwurfsfläche (Übersicht mit Kennzahlen, Mitglieder mit Saldo-Feld). Der Kassen-Standard ändert dort nur Token und Bausteine, nicht die Anordnung.

Abgelehnt sind Materials Pastell-Tonflächen, das SaaS-Kachel-Dashboard und dynamische Farbe aus dem Hintergrundbild des Geräts.

**Key Characteristics:**
- Weiße Flächen mit 1-px-Haarlinie auf warmem Hellgrau; Tiefe aus Linien, nicht aus Schatten.
- Tinte für jede alltägliche Hauptaktion und jede Auswahl; Grün nur, wo Geld fließt.
- Fünf Bedeutungsfarben, festgelegt: Grün Geld, Messing Deckel, Blau Karte, Bernstein Achtung, Rot Fehler.
- Die Pille ist das eine Zeichen für „hier kann man tippen“.
- Systemschrift ohne Laufweite, fette Überschriften, eine eigene Geldstimme mit Tabellenziffern.
- Auf dem Verkaufsweg ist jedes Ziel mindestens 56 dp groß.

## Colors

Eine warme, fast farblose Grundpalette aus Stein und Tinte, in der fünf gesättigte Bedeutungsfarben nur dort stehen, wo sie etwas sagen.

### Primary
- **Tinte** (ink): Grundstimme des Produkts. Hauptknopf, Auswahl, Schalter, Fokusring, der schwebende „Neu“-Knopf, Fließtext. Im dunklen Thema übernimmt **Papier** (paper) dieselbe Rolle. Tinte ist auch die Vorgabe der Vereinsfarbe und der Grund des App-Symbols.

### Secondary
- **Messing** (brass): der Deckel. Das gewählte Mitglied, die Aufladung, das Trinkgeld, der Knopf, der auf den Deckel bucht, und die Vereinssumme „Guthaben auf Deckeln“. Messing ist ein olivstichiges Gold und liegt bewusst weit genug vom Bernstein entfernt, dass beide nebeneinander nicht verwechselt werden. Container in **Messing hell** (brass-tint) mit Schrift in **Messing tief** (brass-deep).

### Tertiary
- **Kartenblau** (blue): Kartenzahlung und Auswertung. Abzeichen „Karte“, Diagrammreihen der Kartenumsätze. Container in blue-tint mit blue-deep.

### Semantische Farben
- **Geldgrün** (money): Geld und Bestätigung. Der Bezahlknopf, Kasse öffnen und schließen, Bargeld buchen, Einnahmen, ein Saldo im Plus, „gebucht“, der gewählte Betrag im Mitglieder-Deckel. In der App nur über `VereinsColors.money` und `moneyButtonColors()` erreichbar, nicht über `primary`.
- **Bernstein** (amber): Aufmerksamkeit. Niedriger Bestand, Saldo nahe am Limit, Sperrmarke. Ein Orange, kein Gold. In der App die erweiterte Rolle `VereinsColors.warning`, weil Material keine Warnrolle kennt.
- **Karminrot** (red): Zerstörung und Fehlschlag. Löschen, abgelehnte Zahlung, Saldo über dem Limit, die Minus-Taste des Mengenzählers auf Menge 1. Karmin statt Signalrot, damit es sich vom Bernstein abhebt.

### Neutral
- **Stein 50** (stone-50): der Grund jeder Seite und jedes Bildschirms, auch das Startfenster von Android.
- **Weiß** (white): alle Flächen, die auf dem Grund liegen: Karten, Kacheln, Spalten, Dialoge, Felder.
- **Stein 100 / 150** (stone-100, stone-150): Füllungen innerhalb einer weißen Fläche: Zähler, Tasten des Ziffernfelds, Unterflächen, Hover.
- **Stein 200** (stone-200): die Haarlinie. Reine Trennung, trägt keine Bedeutung.
- **Stein 500** (stone-500): Rand von Eingabefeldern und Umrissknöpfen (3,4:1, über der 3:1-Schwelle für Bedienelemente).
- **Stein 700** (stone-700): zweite Textstimme, Beschriftungen, gedämpfte Werte.
- **Nacht 950 bis 300** (night-*): dieselben Rollen im dunklen Thema, warm wie der Tag. 950 ist der Grund, 900 die Fläche, 700 die Haarlinie, 300 die zweite Textstimme.

### Kategoriefarben
Sechs gedeckte Töne (category-plum, -petrol, -violet, -magenta, -slate, -ink-blue, je mit hellem Nachtpartner), per Namens-Hash fest zugeordnet. Sie erscheinen als 8-dp-Punkt auf der Produktkachel und als 10-dp-Punkt in der Filter-Pille, nie als Fläche.

### Named Rules
**The One Meaning Rule.** Jede Bedeutungsfarbe hat genau eine Aufgabe: Grün Geld, Messing Deckel, Blau Karte, Bernstein Achtung, Rot Zerstörung und Fehlschlag, und Rot sonst nie. Eine Farbe, die zu dekorieren beginnt, lügt an der Theke.

**The Ink Default Rule.** Tinte (`primary`) ist die alltägliche Hauptaktion und die Auswahl. Grün erscheint nur über `VereinsColors.money` und `moneyButtonColors()`: bezahlen, Kasse öffnen oder schließen, Bargeld buchen. Ein Knopf, der kein Geld bewegt, ist nicht grün.

**The Balance State Rule.** Der Saldo eines Mitglieds wird nach seinem Zustand gefärbt, nicht nach dem Vorzeichen: Plus ist Geldgrün, Null ist neutrale Tinte, ein Minus innerhalb des Rahmens ist gedämpft (onSurfaceVariant), im letzten Viertel des Rahmens (oder im Minus ohne Rahmen) Bernstein, über dem Limit Rot. Die Regel steht zweimal und muss übereinstimmen: `balanceColor()` in der App, `MemberLine.tone` in der Verwaltung.

**The Deckel Liability Rule.** Die Vereinssumme „Guthaben auf Deckeln“ steht in Messing, nicht in Grün: Sie ist das Geld der Mitglieder, eine Verbindlichkeit des Vereins. Grün bleibt den Einnahmen.

**The Club Colour Fence Rule.** Die Vereinsfarbe färbt Navigation (aktiver Eintrag, Reiterpille), Kopfzeile, Markenzeichen und Mitglieder-Avatare, und sonst nichts: nie Bezahlknopf, Warnung, Fehler, Saldo oder Kategorie. Schrift darauf wählt `contrastingOn()` (Weiß oder Tinte, was besser liest).

**The Visible Accent Rule.** Liegt die Vereinsfarbe auf dem Grund des aktuellen Themas unter 1,5:1, nimmt sie dessen Schriftfarbe (Tinte am Tag, Papier in der Nacht). Bewusst nicht 3:1: Gold auf Hell ist schwach, aber erkennbar Gold, und die Farbe gehört dem Verein. Die Regel steht in `visibleAccent()` der App und in `accentSheet` der Verwaltung.

**The Neutral Category Rule.** Kategoriefarben meiden die fünf Bedeutungstöne absichtlich. Ein grüner Punkt an „Getränke“ läse sich als Geld.

## Typography

**Display Font:** Systemschrift (`FontFamily.Default`; im Web `system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`)
**Body Font:** dieselbe
**Label/Mono Font:** Monospace (`ui-monospace, "SF Mono", Menlo`) nur für Kopplungscodes und kopierbare Schlüssel in der Verwaltung

**Character:** Eine einzige Familie, deren Rang aus dem Gewicht kommt, nicht aus vielen Größen. Die Systemschrift deckt Umlaute und ß in jedem Gewicht, kostet nichts und folgt der Schriftgröße, die der Mensch am Gerät eingestellt hat (Entscheidung des Besitzers, 1. Oktober 2026).

### Hierarchy
- **Display** (700, 36 bis 52 px, Zeilenhöhe +8, leicht negative Laufweite): selten; große Einzelzahlen außerhalb von Geld.
- **Headline** (700, 28/34 px, -0,3 px; 24 bis 32 px in der App-Skala): Seitentitel der Verwaltung, Bildschirmtitel. Am Telefon 24/32 px.
- **Title** (700 bei 22 px; 600 bei 17/24 und 14/20 px): Flächenköpfe, Listenzeilen-Titel (titleMedium), Markenname.
- **Body** (400, 14/20 px; groß 16/24, klein 12/16): Fließtext, Unterzeilen, Tabellenzellen.
- **Label** (600, 14/20 px für Knöpfe und Pillen; 500, 12/16 und 11/16 px für Beschriftungen): Knopfbeschriftung, Feldbeschriftung, Achsen, Kategoriename auf der Kachel.
- **Money** (eigene Stimme, immer `tnum`): Display 800 44/52 px für Summe und Wechselgeld im Bezahldialog, Large 800 32/38 px für Kennzahlen, Medium 700 20/26 px im Bezahlknopf und auf Statistik-Kacheln, Small 600 15/20 px in Listen, Mengenzähler und Saldo-Zeilen. Am Telefon im Web Large 26/32, Display 38/46.

### Named Rules
**The Money Is Loudest Rule.** Beträge haben ihre eigene Stimme mit Tabellenziffern (`MoneyText`, `.money-*`) und sind das schwerste Gewicht auf jedem Bildschirm. Keine Überschrift konkurriert mit der Summe.

**The No Tracking Rule.** Fließtext und Beschriftungen stehen auf Laufweite 0. Nur Grade ab 20 px ziehen leicht zusammen (-0,2 bis -1 px). Keine gesperrten Großbuchstaben.

**The Baked Weight Rule.** Das Gewicht steckt im Stil. Wer eine Rolle braucht, nimmt den passenden Stil aus `Type.kt`, statt `FontWeight` darüberzulegen; `grep "FontWeight\."` außerhalb von `ui/theme/` bleibt leer.

## Layout

Abstände kommen aus einer Sechser-Skala (`Spacing`: 4, 8, 12, 16, 24, 32 dp); rohe `dp`-Werte sind nur für Icon- und Punktgrößen erlaubt. Dazu `Spacing.fabClearance` (160 dp) unter Listen, damit der letzte Eintrag nicht unter schwebenden Knöpfen liegt.

**App.** Layout hängt an der Fensterbreite (`WindowSizeClass`, `GridCells.Adaptive`) und an der Fensterhöhe, nie an der Ausrichtung. Der Verkauf auf 960 × 600 dp: links das Produktraster auf Hellgrau mit einer Reihe Kategorie-Pillen darüber, rechts die Bestellung als weiße Spalte, unten rechts die Summe und die volle Bezahl-Pille. Unter `CompactWindowHeight` (700 dp) wandert die Verwaltung in der Leiste hinter „Mehr“, der Gesamtbetrag im Bezahldialog in die Titelzeile, und Zähldialoge stellen Bedienung und Zählen nebeneinander. Dialoge werden auf 960 × 600 dp geprüft.

**Verwaltung.** Seitenleiste 264 px (weiß, Haarlinie rechts), Inhalt bis 1240 px breit mit 32 px Rand und 24 px Abstand zwischen Flächen. Raster: Hauptspalte und Nebenspalte (1,9 : 1), zwei gleiche Spalten, Inhalt mit 392-px-Seitenspalte. Kennzahlen stehen als vier Figuren in einer Fläche, getrennt durch Haarlinien statt als einzelne Kacheln. Unter 1180 px fallen die Hauptraster auf eine Spalte; unter 860 px verschwindet die Seitenleiste zugunsten einer festen Reiterleiste unten (vier Ziele, je 56 px hoch), Flächen rücken auf 16 px Innenabstand, Tabellen zeigen Detailwerte als Unterzeile, und eine geteilte Ansicht zeigt Liste oder Einzelnes, nie beides.

**Mitglieder-Deckel.** Am Handy zuerst: eine Spalte, höchstens 520 px, Beträge als 56 px hohe Pillen im Raster (mindestens 84 px breit).

### Named Rules
**The Thumb First Rule.** Alles auf dem Verkaufsweg ist mindestens 56 dp groß (`TouchTarget.sales`) und liegt in den unteren zwei Dritteln; alles andere mindestens 48 dp in der App (`TouchTarget.min`) und 44 px im Web.

**The Width Not Orientation Rule.** Gefragt werden Fensterbreite und Fensterhöhe, nie `ORIENTATION_LANDSCAPE`.

## Elevation & Depth

Ruhende Flächen sind flach. Tiefe entsteht aus dem Wechsel von warmem Grund zu weißer Fläche und aus einer 1-px-Haarlinie (`Stroke.hairline`, `hairline()`, `--hairline`) um jede weiße Fläche. Auswahl zeigt sich als 2-px-Rand (`Stroke.selected`) in Tinte, nicht als Anheben. Schatten gibt es nur für Ebenen, die tatsächlich über der Seite schweben: Dialoge und aufgeklappte Bestätigungen in der Verwaltung, in der App die Standard-Tonhöhe der Material-Dialoge.

### Shadow Vocabulary
- **Dialog** (`box-shadow: 0 24px 64px rgba(0, 0, 0, 0.28)`, Schleier `rgba(20, 20, 20, 0.5)`): Popover-Dialoge der Verwaltung.
- **Aufgeklappte Bestätigung** (`box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18)`): schwebende Formulare unter einem `details`-Knopf; am Telefon stehen sie im Fluss und verlieren den Schatten.

### Named Rules
**The Line Not Shadow Rule.** Karten, Kacheln, Listenzeilen, Spalten und Panels tragen eine Haarlinie und keinen Schatten. Ein Schatten heißt „das liegt über der Seite“, und nichts Ruhendes liegt über der Seite.

## Shapes

Kleine Radien für Flächen, volle Rundung für alles, was man tippt. Eine Rolle, ein Radius (`Shapes`): 4 px für Abzeichen und Bestandszähler, 10 px für Eingabefelder, Ziffernfeld-Tasten, Zählfelder und Unterflächen, 12 px für Kacheln, Karten und Listenzeilen, 16 px für Spalten und große Flächen, 24 px für Dialoge in der Verwaltung und die Oberkante der unteren Schublade. Avatare, Punkte und runde Icon-Knöpfe sind Kreise. Das Markenzeichen der Verwaltung ist ein 40-px-Quadrat mit 10 px Radius in der Vereinsfarbe.

Das Symbol (App und Favicon) ist die Strichliste vom Bierdeckel: vier senkrechte Striche, der fünfte quer, weiß auf Tinte, Strich 1,6 im 24er-Raster mit runden Enden. Erzeugt für iOS von `docs/tools/gen_app_icon.swift`, für Android als Vektor in `ic_launcher_foreground.xml`.

### Named Rules
**The Pill Means Tap Rule.** Knöpfe, Filter, Zustandsmarken, Betragswahl und Mengenzähler sind Pillen (`Pill`, `border-radius: 999px`). Die Pille ist das eine Zeichen für „hier kann man tippen“; Flächen, die man nicht tippt, sind keine Pillen.

## Components

### Buttons
Satt gefüllte Pillen ohne Schatten; die Farbe sagt, was der Knopf tut.
- **Shape:** Pille (999 px), mindestens 44 px hoch im Web, 48 dp in der App, 56 dp auf dem Verkaufsweg.
- **Primary:** Tinte mit weißer Schrift, Label 600 14 px, 20 px seitlich. Die eine Hauptaktion einer Seite, auch der schwebende „Neu“-Knopf.
- **Money:** Geldgrün mit weißer Schrift, nur für Geldbewegungen (`moneyButtonColors()`, `.btn-money`).
- **Deckel:** Messing mit weißer Schrift, wo auf den Deckel gebucht oder aufgeladen wird (`.btn-brass`).
- **Outline (Standard im Web):** weiße Fläche, 1-px-Rand in Stein 500, Tintenschrift. Gefährlich: derselbe Umriss in Rot mit roter Schrift. Leise: Stein-100-Füllung ohne Rand.
- **Hover / Focus:** Gefüllte Knöpfe werden 12 % heller (`filter: brightness(1.12)`), Umrissknöpfe bekommen Stein 100. Übergang 120 ms auf die Hintergrundfarbe, nur ohne reduzierte Bewegung. Fokus ist ein 2-px-Umriss in Tinte mit 2 px Abstand.
- **Row actions (App):** runde Icon-Knöpfe 48 dp, Stein-150-Füllung, Symbol in zweiter Textstimme; „Löschen“ im Menü steht in Rot.

### Bezahl-Pille (Signatur)
Volle Breite, mindestens 56 dp, Geldgrün. Links „Bezahlen“ in Label, rechts der Betrag in Money Medium. Ein Tipp gibt haptisches Feedback und bucht; der Inhalt blendet über in einen Fortschrittskreis und dann in „Bezahlt“ mit Häkchen. Ohne offene Kasse gibt es diesen Knopf nicht.

### Chips
- **Filter-Pille:** weiße Fläche mit Haarlinie, Tintenschrift, Label 600; gewählt invertiert (Tinte als Fläche, Papier als Schrift). Kategorie-Pillen tragen links einen 10-dp-Punkt in der Kategoriefarbe, der in gewähltem Zustand einen Haarlinienring bekommt. Auf dem Verkaufsweg 56 dp hoch, sonst 44.
- **Zustandsmarke:** 24 px hohe Pille, 12 px Schrift 600, Container der Bedeutungsfarbe mit ihrer tiefen Schrift: Bar und „gebucht“ grün, Karte blau, Deckel Messing, Achtung Bernstein, Fehler rot. Neutral: nur ein Innenrand in Stein 500.
- **Segmentierte Wahl (App):** gewählt in `inverseSurface` (Tinte), sonst weiß mit Stein-500-Rand.

### Cards / Containers
- **Corner Style:** 12 px.
- **Background:** Weiß auf Stein 50; im dunklen Thema Nacht 900 auf Nacht 950.
- **Shadow Strategy:** keine, siehe Line Not Shadow Rule.
- **Border:** 1 px Haarlinie (Stein 200 / Nacht 700).
- **Internal Padding:** 16 dp in der App (`VdCard`), 24 px im Web-Panel (Kopf 18/24/10, Fuß 12/24/14), 16 px am Telefon.
- **Hinweise:** Unterfläche mit 10 px Radius und 12 px Innenabstand, neutral in Stein 100 oder im Container einer Bedeutungsfarbe (Achtung, Fehler, gebucht).

### Produktkachel (Signatur)
Weiße Kachel, 12 dp, Haarlinie, mindestens 88 dp hoch, Innenabstand 12/8 dp. Oben ein 8-dp-Kategoriepunkt mit dem Kategorienamen in Label Small; bei niedrigem Bestand rechts eine Bernstein-Pille mit der Restzahl. Ein Tipp gibt haptisches Feedback und lässt die Kachel 120 ms auf 96 % zusammenzucken; langes Drücken öffnet ein Menü.

### Statistik-Kachel und Kennzahl
Weiße Fläche mit Haarlinie, 16 dp Innenabstand; Beschriftung in Label in zweiter Textstimme mit Icon, darunter der Wert in Money Medium (App) oder Money Large (Verwaltung). In der Verwaltung stehen vier Kennzahlen in einer gemeinsamen Fläche, durch senkrechte Haarlinien getrennt.

### Listenzeilen und Mitglieder
Weiße Zeile, 12 dp, Haarlinie, mindestens 48 dp, Innenabstand 12 dp. Titel in titleMedium, Unterzeile bodySmall. Mitgliederzeilen tragen links einen Kreis-Avatar in der Vereinsfarbe mit Initialen, rechts den Saldo in Money Small nach der Balance State Rule.

### Mengenzähler
Pille in Stein 100 mit zwei 56-dp-Tasten und der Menge in Money Small dazwischen. Plus in Tinte, Minus in zweiter Textstimme, auf Menge 1 in Rot, weil der nächste Tipp die Zeile entfernt.

### Inputs / Fields
- **Style:** weiße Fläche, 1-px-Rand in Stein 500, 10 px Radius, mindestens 44 px, 12 px seitlich. Beschriftung darüber in 12 px 600, zweite Textstimme. Suche als Pille mit Icon links.
- **Focus:** 2-px-Umriss in Tinte (Web), Material-Fokus in `primary` = Tinte (App).
- **Auswahl per Farbfeld:** Pillen mit 24-px-Farbkreis; gewählt mit Tinten-Doppelrand.

### Tabellen (Verwaltung)
Zeilen 52 px hoch (dicht 40), getrennt durch Haarlinien, außen 24 px eingerückt. Spaltenköpfe 12 px 600 in zweiter Textstimme. Beträge rechtsbündig in Tabellenziffern. Summenzeile mit 2-px-Oberlinie in Tinte und Gewicht 600. Wählbare Zeilen färben beim Überfahren und gewählt in Stein 100.

### Navigation
- **App:** Navigationsleiste bzw. -schiene; der aktive Eintrag trägt die Vereinsfarbe.
- **Verwaltung, breit:** weiße Seitenleiste; Einträge sind 44 px hohe Pillen, Schrift 600 in zweiter Textstimme, Hover Stein 100, aktiv in der Vereinsfarbe mit `--on-accent`. Gruppen tragen eine Beschriftung in Label Small.
- **Verwaltung, schmal:** feste Reiterleiste unten, weiß mit Haarlinie oben; aktiver Reiter fett, das Icon in einer 56 × 28 px großen Pille in der Vereinsfarbe.

### Dialoge
App: Material-Dialoge im Radius large. Verwaltung: HTML-Popover ohne Skript, höchstens 600 px breit, 24 px Radius, Haarlinie, Dialog-Schatten, abgedunkelter Grund; die Seite dahinter ist still (`body:has(.dialog:popover-open)`).

### Zählen nach Stückelung (Kasse öffnen und schließen)
Drei Zonen nebeneinander auf 960 × 600 dp: links wer, womit, die Summe und der grüne Knopf; in der Mitte die Kacheln der Lade (Scheine, Münzen); rechts die Frage über dem Tastenfeld. Die Frage („Wie viele 50-€-Scheine?“) steht über einem Anzeigefeld mit Tintenrahmen und der Anzahl, darunter „= Betrag“. Sie ist das Bindeglied: Ohne sie weiß niemand, wohin das Tastenfeld schreibt. Die gewählte Kachel trägt denselben Tintenrahmen, leicht hinterlegt. Eine gezählte Kachel zeigt unter dem Wert die Anzahl als Tinten-Marke („3×“), den Betrag nur das Feld über dem Tastenfeld; eine ungezählte zeigt nur ihren Wert, keinen Strich, denn ein „–“ liest sich als Minus-Knopf. Die Weiter-Taste steht in Tinte und sagt in zwei Zeilen, wohin („Weiter“ / „20 €“). Beim Schließen erscheinen Soll, Gezählt und Differenz erst nach der ersten Eingabe; der Grund für eine Differenz kommt danach. Ein grauer Knopf sagt in Bernstein darüber, was noch fehlt.

Geprüft auf 960 × 600 dp (Galaxy Tab Active3 quer, auch mit 130 % Schrift), 600 × 960 dp, Telefon hoch und quer und 1280 × 800 dp. Unter 700 dp Fensterhöhe liegen die Aufladebeträge eines gewählten Mitglieds hinter „Aufladen“; unter 480 dp Höhe gibt es im Verkauf nur eine Spalte, und die Bestellung rollt als Ganzes. Die Bestellung als Schublade öffnet immer ganz, damit Summe und „Bezahlen“ sichtbar sind.

### Auswahl in Dialogen
Die Art einer Eingabe (Prozent oder Betrag; Bar, Karte oder Korrektur) ist ein Umschalter (`vdSegmentedColors()`), die Werte darunter sind Pillen. Beide sehen verschieden aus, weil sie Verschiedenes tun. Ein Dialog hat immer einen sichtbaren Ausweg („Abbrechen“); Zurücknehmen in Rot („Rabatt entfernen“) erscheint nur, wo es etwas zurückzunehmen gibt. Eine Auswahl aus einer Liste sieht aus wie ein Feld: Rahmen, Text links, Pfeil rechts.

## Do's and Don'ts

### Do:
- **Do** nimm für jede Fläche Weiß mit 1-px-Haarlinie auf dem Stein-50-Grund.
- **Do** setze jede alltägliche Hauptaktion und jede Auswahl in Tinte (`primary`); Grün nur über `VereinsColors.money` und `moneyButtonColors()`.
- **Do** färbe einen Saldo nach Zustand: Plus grün, Null Tinte, Minus im Rahmen gedämpft, im letzten Viertel Bernstein, über dem Limit Rot, in App und Verwaltung gleich.
- **Do** zeige „Guthaben auf Deckeln“ in Messing.
- **Do** setze jeden Betrag mit Tabellenziffern über `MoneyText` bzw. `.money-*`.
- **Do** mache alles, was man tippt, zur Pille, und auf dem Verkaufsweg mindestens 56 dp hoch.
- **Do** nimm Abstände aus `Spacing`, Radien aus `Shapes`, Schriftgrade aus `Type.kt`.
- **Do** ziehe jede Farbänderung aus `Color.kt` in `app.css` nach, in beiden Themen.
- **Do** sag bei jedem grauen Knopf, was noch fehlt, und gib jedem Dialog ein sichtbares „Abbrechen“.
- **Do** lass die Vereinsfarbe über `visibleAccent()` bzw. `accentSheet` laufen, damit sie unter 1,5:1 auf die Schriftfarbe des Grunds zurückfällt.

### Don't:
- **Don't** verwende Rot für etwas anderes als Zerstörung und Fehlschlag.
- **Don't** färbe Bezahlknopf, Warnung, Fehler, Saldo oder Kategorie in der Vereinsfarbe.
- **Don't** gib einer Kategorie Grün, Blau, Messing, Bernstein oder Rot.
- **Don't** lege Schatten auf ruhende Karten, Kacheln, Zeilen oder Panels; Schatten gehören nur schwebenden Ebenen.
- **Don't** baue Materials Pastell-Tonflächen oder ein SaaS-Kachel-Dashboard; Kennzahlen teilen sich eine Fläche.
- **Don't** lege `FontWeight` über einen Stil oder sperre Text mit Laufweite.
- **Don't** biete dynamische Farbe aus dem Hintergrundbild des Geräts an.
- **Don't** ändere in der Verwaltung den Seitenaufbau der freigegebenen Entwurfsfläche vom 22. September 2026; der Kassen-Standard ändert dort Token und Bausteine.
- **Don't** entscheide Layout nach der Ausrichtung des Geräts.
