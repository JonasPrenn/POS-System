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
| Server für den Mehrgerätebetrieb | **steht und ist getestet**, `server/` — Schema, Kopplung, Sync, Belegfotos nach der Spezifikation. Aufstellen mit `server/deploy/install.sh` (Docker und git genügen, gebaut wird im Container); Updates aus dem Repo über die Verwaltung (`web/Updates.kt`, Dienst `updater`). Aufgestellt ist er bisher nur zum Probieren auf dem eigenen Rechner |
| Mehrgerätebetrieb in der App (Schritt 7) | **fertig**: Schema 15 (11 = UUID-Schlüssel, 12 = Couleurname, 13 = Kasse, 14 = Sperre, 15 = Bardienst ohne Barkasse) mit hergeleitetem Saldo und Bestand, Abgleich und Kopplung. Mit zwei Geräten (Emulator, Simulator) gegen den Server in Docker durchgespielt. **Auf dem echten Vereinstablet ist die Migration ungeprüft — vorher sichern** |
| Web-Verwaltung | **Phase 1 gebaut** (`server/.../web/`, unter `/verwaltung`): Anmeldung mit Rollen, Übersicht, Mitglieder, Berichte, Lager, Einkauf, Geräte, Protokoll, mit Telefonansicht. Dazu Abrechnung mit PDF, E-Mail und Bankimport (`web/Statements.kt`) und die Kasse (`web/Cash.kt`: Tagesbericht, Kassenbuch, Bankbuch aus dem, was die Tablets als `cash_sessions` und `cash_movements` melden — gezählt wird am Tablet, `ui/components/CashSection.kt`), das Sortiment (`web/Products.kt`) und die Bücher (`web/Books.kt`: Einnahmen-Ausgaben-Rechnung und Vermögensübersicht, hergeleitet, nichts fortgeschrieben). Schreibt in synchronisierte Tabellen nur Mitglieder (anlegen, ändern, sperren, löschen), Aufladungen und Korrekturen (`web/Writes.kt`, auch die Zahlung einer Abrechnung), Wareneingänge (`web/Purchases.kt`) und das Sortiment samt Kategorien (`web/Products.kt`) sowie die Einstellungen der Tablets (`device_settings`: Vereinsname, Vereinsfarbe, SumUp-Schlüssel, tägliche Sicherung — die Geräte lesen sie nur), immer über `Database.write`. Konzept und Phasen 2–4 in `docs/WEB-VERWALTUNG.md`; der klickbare Entwurf liegt als Artifact vor |

Beide Plattformen bauen aus demselben Code. Was geprüft ist und was nicht, steht in
`docs/PORTIERUNG.md`; Kartenzahlung gibt es auf iOS erst, wenn das SumUp-iOS-SDK per
Swift-Brücke angebunden ist.

## Aufbau

```
core/            Reines Kotlin ohne Compose und Room, gemeinsam für App und Server: Ids, Zeit,
                 Geld- und Mengenformat, die Saldoregel (Ledger), das Inventar, das Drahtformat
                 und der Sync-Client. Ziele: JVM (Android nutzt die JVM-Variante), iOS.
shared/          Kotlin Multiplatform. Datenhaltung, Logik, gesamte Oberfläche.
  commonMain/    Alles Gemeinsame, 84 Dateien. AppGraph und ui/VereinsDeckelApp sind die Wurzel.
                 data/repository/AppRepository ist der einzige Schreibweg, data/sync/ der Abgleich.
  androidMain/   Android-Umsetzungen der expect-Deklarationen.
  iosMain/       iOS-Umsetzungen. Bindet Swift über Interfaces ein, nicht umgekehrt.
  iosTest/       Läuft im Simulator: Room, Migration 10 → 11, Bedientest, Kassenlade, und der Abgleich mit
                 zwei Geräten gegen einen nachgebauten Server.
androidApp/      Nur Hülle: MainActivity, Application, BackupWorker, Manifest, Ressourcen.
iosApp/          Xcode-Projekt und Swift-Host. Baut das Kotlin-Framework über Gradle.
server/          Der Sync-Server nach der Spezifikation: Kotlin/JVM, Ktor, PostgreSQL. Eigene README.
                 web/ ist die Verwaltung: server-gerendertes HTML, ein Stylesheet, keine Skripte.
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
Die Verwaltung nimmt denselben Satz: `server/.../web/MaterialIcons.kt`, erzeugt von
`docs/tools/gen_web_icons.py` aus `docs/tools/web-icons/`.

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
denselben Weg.

**Nichts wird fortgeschrieben, was sich herleiten lässt.** Saldo, Bestand, „gezapft" und
„zuletzt benutzt" sind Summen über anfügende Tabellen, keine Spalten — ein Zähler, den zwei
Theken gleichzeitig fortschreiben, verliert eine der beiden Buchungen. Die Saldoregel steht
genau dreimal: in `core/.../data/Ledger.kt`, als SQLite-Ausdruck in `DerivedSql.kt` und in der
Serversicht `transaction_effects` (auf ihr setzt `member_balances` auf). `RoomOnIosTest` und `SyncTest` prüfen beide SQL-Fassungen
gegen die in Kotlin. Wer die Regel ändert, ändert alle drei. Entschieden ist: Ein Rabatt
mindert die Deckelbelastung, ein Trinkgeld auf den Deckel belastet ihn.

**Abgänge werden festgehalten, nicht nachgerechnet.** Was ein Verkauf dem Keller entnimmt,
steht als Zeile in `stock_draws`. Die Spezifikation (2.3) wollte es aus Buchung mal Rezeptur
herleiten; die Glasgröße der Variante steht aber in keiner Buchungszeile, und jede
Rezepturänderung schriebe die Vergangenheit um.

**Ein Schreibweg, und der Auftrag an den Server entsteht in derselben Transaktion.** Jede
Änderung geht durch `AppRepository.write`; ist das Gerät gekoppelt, liegt danach eine Zeile
in `pending_changes`. Die eine Ausnahme ist `SyncApplier`: Was der Server sagt, ist keine
Änderung dieses Geräts. Gelöscht wird weich, und die App-Datenbank hat keine
Fremdschlüssel — eine geänderte Elternzeile kann nach ihren Kindern ankommen.

**Der Verkauf wartet nie auf das Netz.** Geschrieben wird lokal; der Abgleich läuft
daneben und meldet sich nur im Status. Dort steht „Offline" nur, wenn es stimmt: Ein am
Server abgemeldetes Gerät heißt „Abgemeldet" und meldet sich mit einem frischen Code neu
an, ohne die Kopplung zu lösen — das würde die wartenden Buchungen kosten.

**Die Verwaltung kommt ohne Skripte und ohne Inline-Styles aus.** Ihre
Content-Security-Policy ist `default-src 'none'` mit genau einem Stylesheet; `WebTest` prüft,
dass keine Seite ein `style=` oder `<script` enthält. Was eine berechnete Breite braucht, ist
SVG mit Attributen (`bar()`, `weekChart()`), die Vereinsfarbe ein eigenes Blatt
(`/verwaltung/assets/verein.css`). Die Token in `app.css` sind die aus `ui/theme` — wer dort
eine Farbe ändert, zieht sie hier nach. Und: Die Verwaltung rechnet das Lager mit derselben
`Inventory` aus `:core` wie die App, nicht mit eigenem SQL.
Dialoge sind HTML-Popover (`dialog()` in `Html.kt`): `popovertarget` öffnet und schließt ohne
Skript, `body:has(.dialog:popover-open)` stellt die Seite dahinter still.

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

Das prüft nur, ob es baut. Ein so gebauter Build kann im Simulator nicht koppeln: Ohne
Signatur verweigert der Schlüsselbund das Gerätetoken (-34018). Zum Ausprobieren den
Schalter weglassen — für den Simulator signiert Xcode ad hoc, ohne Team.

Es gibt keine CI in diesem Repo. Was nicht lokal geprüft wurde, ist ungeprüft.
