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
| Portierung auf iOS | **läuft im iPad-Simulator**, Gerätestart steht aus, siehe `docs/PORTIERUNG.md` |
| Server für den Mehrgerätebetrieb | **steht und ist getestet**, `server/` — Schema, Kopplung, Sync, Belegfotos nach der Spezifikation. Die App spricht ihn noch nicht an: Schritt 7 |
| Web-Verwaltung | Konzept in `docs/WEB-VERWALTUNG.md`, nicht begonnen |

Beide Plattformen bauen aus demselben Code. Was geprüft ist und was nicht, steht in
`docs/PORTIERUNG.md`; Kartenzahlung gibt es auf iOS erst, wenn das SumUp-iOS-SDK per
Swift-Brücke angebunden ist.

## Aufbau

```
core/            Reines Kotlin ohne Compose und Room, gemeinsam für App und Server: Ids, Zeit,
                 Geld- und Mengenformat. Ziele: JVM (Android nutzt die JVM-Variante), iOS.
shared/          Kotlin Multiplatform. Datenhaltung, Logik, gesamte Oberfläche.
  commonMain/    Alles Gemeinsame, 79 Dateien. AppGraph und ui/VereinsDeckelApp sind die Wurzel.
  androidMain/   Android-Umsetzungen der expect-Deklarationen.
  iosMain/       iOS-Umsetzungen. Bindet Swift über Interfaces ein, nicht umgekehrt.
  iosTest/       Room-Integrationstest und Bedientest (Bar, Deckel), laufen im Simulator.
androidApp/      Nur Hülle: MainActivity, Application, BackupWorker, Manifest, Ressourcen.
iosApp/          Xcode-Projekt und Swift-Host. Baut das Kotlin-Framework über Gradle.
server/          Der Sync-Server nach der Spezifikation: Kotlin/JVM, Ktor, PostgreSQL. Eigene README.
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

**Compose kommt von JetBrains-Koordinaten.** `org.jetbrains.compose.*:1.12.0` und
`org.jetbrains.compose.material3:material3:1.9.0` stehen fest im Katalog. Google
veröffentlicht foundation, ui und material3 nicht für iOS, und die Plugin-Accessors
(`compose.material3`) sind seit CMP 1.12 veraltet. Ein Intel-Simulator (`iosX64`) gibt
es dafür nicht mehr.

**Sicherung ist eine Datei.** Die SQLite-Datenbank nach einem Checkpoint, nichts weiter.
Wiederherstellen legt sie als `<db>.restore` daneben und `buildDatabase()` übernimmt sie
beim nächsten Start — die offene Datenbank wird nie unter Room ausgetauscht.

**Sequenznummern entstehen unter einer Sperre.** Jeder Schreibzugriff auf eine Sync-Tabelle
des Servers läuft über `Database.write`, das eine Advisory-Sperre nimmt. Ohne sie könnte
eine höhere Nummer vor einer niedrigeren sichtbar werden, und ein Client, der sich die
höchste gesehene Nummer merkt, sähe die niedrigere nie. Die Web-Verwaltung nimmt
denselben Weg. Dazu gehört: Die Saldoregel steht genau zweimal, in `core/.../data/Ledger.kt`
und als SQL-Sicht `member_balances`, und `SyncTest` prüft, dass beide dasselbe ergeben.

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
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"  # System-java ist 8
./gradlew :shared:compileKotlinIosArm64   # bricht am ehesten
./gradlew :androidApp:assembleDebug
./gradlew :core:jvmTest :shared:testAndroidHostTest   # ohne Simulator
./gradlew :core:allTests :shared:allTests   # braucht eine iOS-Simulator-Runtime
```

Wer `server/` oder `core/` anfasst, zusätzlich `./gradlew :server:test` (startet einen
eingebetteten PostgreSQL, braucht weder Docker noch eine Installation).

Wer iosApp anfasst, zusätzlich:

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' build CODE_SIGNING_ALLOWED=NO
```

Es gibt keine CI in diesem Repo. Was nicht lokal geprüft wurde, ist ungeprüft.
