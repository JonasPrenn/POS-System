# VereinsDeckel

Kassensystem für ein Vereinsheim. Die Person am Gerät ist ein Mitglied im Schichtdienst,
nicht eine geschulte Kassiererin — nächsten Samstag ist es jemand anders. Der Raum ist
abends dunkel und laut, mittags im Biergarten hell und ausgewaschen. Fehler kosten echtes
Geld und sind vor einer Schlange peinlich aufzulösen.

Ein *Deckel* ist der Bierdeckel, auf dem angeschrieben wird. Das ist kein Ladengeschäft,
sondern ein Anschreibsystem mit angeschlossener Kasse.

## Stand

| Bereich | Stand |
|---|---|
| Designsystem, Phasen 0–5 | fertig, siehe `docs/` und die PR-Beschreibung |
| Server- und API-Spezifikation | fertig, `docs/VereinsDeckel-Server-und-API.pdf` |
| Portierung auf iOS | **etwa zur Hälfte**, siehe `docs/PORTIERUNG.md` |
| Sync gegen einen Server | noch nicht begonnen |

**Nichts vom Multiplatform-Umbau ist je übersetzt worden.** Er entstand in einer
Umgebung ohne Android SDK, ohne Xcode und ohne Zugriff auf Google Maven. Wer lokal
weiterarbeitet, fängt mit einem Übersetzungslauf an — siehe `docs/PORTIERUNG.md`,
Abschnitt „Erster Schritt lokal".

## Aufbau

```
shared/          Kotlin Multiplatform. Datenhaltung, Logik, gesamte Oberfläche.
  commonMain/    Alles Gemeinsame. 69 Dateien.
  androidMain/   Android-Umsetzungen der expect-Deklarationen.
  iosMain/       iOS-Umsetzungen. Bindet Swift über Interfaces ein, nicht umgekehrt.
androidApp/      Nur Hülle: MainActivity, Application, Manifest, Ressourcen.
iosApp/          Fehlt noch. Wird von Xcode gebaut, nicht von Gradle.
docs/            Spezifikation, Portierungsplan, Werkzeuge.
```

## Feste Entscheidungen

Diese sind getroffen und begründet. Nicht ohne Anlass neu aufrollen.

**Farbe bedeutet etwas.** Grün ist Geld und Bestätigung. Messing ist der Deckel. Blau ist
Karte und Auswertung. Bernstein ist Aufmerksamkeit. Rot ist Zerstörung und Fehlschlag —
und sonst nichts, nie. Die Vereinsfarbe färbt Navigation, Kopfzeile und
Mitglieder-Symbole und hält sich von allem anderen fern, weil ein roter Verein sonst
Zahlungen in derselben Farbe bestätigen würde, in der die App Fehler meldet.

**Geld ist das Lauteste.** Beträge haben eine eigene typografische Stimme mit
Tabellenziffern, damit Zahlen in einer Liste nicht zittern. Sonst darf nichts um diese
Rolle konkurrieren.

**Daumen zuerst.** Alles auf dem Verkaufsweg ist mindestens 56dp groß und liegt in den
unteren zwei Dritteln. Nichts Wichtiges versteckt sich hinter einer Schublade.

**Keine losen Werte.** Abstände kommen aus `Spacing`, Radien aus `Shapes`, Schriftgrade
aus `Type.kt`, Beträge durch `Money`, Mengen durch `Quantity`, Zeit durch
`platform/Time.kt`. Rohe `dp`-Literale sind für Icon- und Punktgrößen in Ordnung, für
Abstände nicht. Maschinell prüfbar:

```bash
grep -rn "FontWeight\.\|RoundedCornerShape(" shared/src --include=*.kt | grep -v ui/theme/
grep -rn "String.format" shared/src --include=*.kt | grep -v ui/format/
```

Beide sollen leer bleiben.

**Breite, nicht Ausrichtung.** Layoutentscheidungen hängen an `WindowSizeClass` oder an
`GridCells.Adaptive`, nie an `Configuration.ORIENTATION_LANDSCAPE`. Ein Tablet im
Hochformat und ein geteilter Bildschirm sind genau die Fälle, die Ausrichtung falsch
beantwortet.

**Icons liegen im Repo.** `ui/icons/VdIcons.kt`, erzeugt von `docs/tools/gen_icons.py`.
Nicht von Hand ändern — beim nächsten Lauf wäre es weg. Grund für das Selbermachen:
`material-icons-extended` gibt es für Compose Multiplatform nur bis 1.7.3.

**Plattformgrenzen sind fachlich geschnitten.** `PaymentProcessor` heißt so, weil die App
eine Karte belasten will, nicht weil SumUp ein SDK hat. Schlüsselbund und SumUp-iOS-SDK
werden über Swift-Interfaces hereingereicht statt über Kotlin/Native-Interop angebunden —
CoreFoundation-Interop zeigt seine Fehler erst mit echtem Terminal in der Hand.

## Sprache

Code-Kommentare und Commit-Nachrichten auf Deutsch, passend zum Rest des Projekts.
Bestehende englische Kommentare aus früheren Phasen bleiben, wo sie stehen — sie
nachträglich zu übersetzen wäre Lärm im Verlauf.

Bezeichner bleiben englisch (`fun charge`, `val balance`), Nutzertexte sind deutsch.

## Vor jedem Commit

```bash
./gradlew :shared:compileKotlinIosArm64   # bricht am ehesten
./gradlew :androidApp:assembleDebug
./gradlew :shared:allTests
```

Es gibt keine CI in diesem Repo. Was nicht lokal geprüft wurde, ist ungeprüft.
