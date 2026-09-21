# Portierung auf iOS — Stand und nächste Schritte

Stand 21. September 2026. Alle Schritte des ursprünglichen Plans sind umgesetzt, je einer
pro Commit auf `1.0.1-suh2bh`. Die App läuft aus demselben Code auf Android und im
iPad-Simulator. Schritt 7 (Datenmodell auf UUID, abgeleiteter Saldo und Bestand, Sync) ist
am 21. September freigegeben und in vier Commits gebaut worden (7a bis 7d); was dabei
entstand und wo es von der Spezifikation abweicht, steht unten in einem eigenen Abschnitt.

**Vor dem ersten Start der neuen App auf dem Vereinstablet: sichern.** Das Update hebt die
Datenbank von Schema 10 auf 11 und baut dabei jede Tabelle um. Im Emulator ist das mit
einem echten Datenbestand geprüft, auf dem Tablet selbst nicht.

---

## Was geprüft ist — und was nicht

| Prüfung | Ergebnis |
|---|---|
| `./gradlew :shared:compileKotlinIosArm64` | fehlerfrei, ohne Warnungen |
| `./gradlew :androidApp:assembleDebug` | erfolgreich |
| `./gradlew :shared:allTests` | grün, auf der JVM und im iOS-Simulator: `InventoryTest`, der Room-Integrationstest und der Bedientest `SalesFlowOnIosTest` (die Zahlformat-Tests laufen seit dem 21. September in `:core`) |
| `SalesFlowOnIosTest` (Kotlin/Native im Simulator) | Barverkauf und Verkauf auf den Deckel durch die echte Oberfläche — `VereinsDeckelApp` mit Navigation, ViewModels und Dialogen über einer Datenbank im Speicher: Kachel, Bezahlen, Bar, Passend, Abschließen; dann Mitglied wählen, Kachel, Bezahlen, Deckel, Abschließen. Geprüft gegen die Datenbank: eine `CASH`-Zeile ohne Mitglied, eine `MEMBER_BALANCE`-Zeile, Saldo 23,50 → 19,30 € |
| `xcodebuild` für iosApp, Debug, iPad-Simulator | BUILD SUCCEEDED |
| Start im iPad-Simulator (iPad Pro 13", iOS 26.5) | Verkaufsbildschirm mit Leiste, Bestellung, Icons; Datenbank angelegt; Produkte aus der Datenbank erscheinen |
| Dasselbe mit **Xcode 27.0** (SDK iOS 27.0), 21. September | `xcodebuild` BUILD SUCCEEDED, `:shared:allTests` grün, der neue Build startet im Simulator (Runtime iOS 26.5) und öffnet die vorhandene Datenbank |
| Bedienung in der echten App im iPad-Simulator (Build mit Xcode 27), 21. September | Barverkauf: Kachel, Bezahlen, Bar, 10 € gegeben, Rückgeld 5,80 €, Abschließen. Deckel: Mitglied wählen, Kachel, Bezahlen, Deckel-Kachel, Abschließen. Danach in der Datenbank der App eine `CASH`-Zeile ohne Mitglied und eine `MEMBER_BALANCE`-Zeile auf Maria Bauer, Saldo 23,50 → 19,30 €; die Historie zeigt beide Vorgänge. Die Berührungen gingen durch UIKit und den `ComposeUIViewController`. Die Produktsuche nimmt Eingaben der Bildschirmtastatur an und filtert das Raster |
| Android im Emulator (Medium_Tablet, API 37) mit **bestehender** Datenbank | Barverkauf mit Keypad und Rückgeld, Verkauf auf den Deckel (Saldo 43,50 → 40,00 €), Historie, Mitglieder, Einstellungen — alles ohne Absturz |
| **Schritt 7, Tests** (21. September) | 101 Testläufe grün: `:core` 28 je Ziel (JVM und iOS: Geld, Saldoregel, Ids, Drahtformat, Inventar), `:server` 22 gegen einen eingebetteten PostgreSQL, `:shared` 19 im iOS-Simulator und 4 auf der JVM. Darunter `MigrationTo11Test` (Schema 10 → 11 mit Waisen, Übertragsbuchungen, Fässern) und `SyncEngineTest` mit zwei Geräten und nachgebautem Server: Erstbefüllung, Offline-Verkauf mit verlorener Antwort, zwei Theken auf demselben Deckel, Preiskonflikt, Löschmarke, 422, Sperre und Neu-Anmelden, Token nicht haltbar, Entscheidung bei beidseitigen Daten, 520 Zeilen in mehreren Aufrufen |
| **Migration 10 → 11 auf Android** mit echtem Bestand im Emulator | Drei Mitglieder (40,00 / −12,50 / 102,00 €), Lager mit Fässern und vier Anstichen: nach dem Update alle Salden, Bestände, „gezapft" und gelernten Ausbeuten unverändert; ein Verkauf auf den Deckel bucht Transaktion und Lagerabgang |
| **Zwei Geräte gegen den echten Server** (Docker: PostgreSQL 16 + API), Android-Emulator und iPad-Simulator, durch die Oberfläche | Android koppelt als Quelle, 62 Zeilen gehen hoch. iPad koppelt, bekommt den Entscheidungsdialog („beide haben Daten"), übernimmt den Serverstand: dieselben Salden und derselbe Lagerbestand bis auf die Nachkommastelle. **A1** in beide Richtungen (Produkte Android → iPad, Mitglied iPad → Android). **A3/A7/A8:** Server aus, iPad bucht 3,50 € und Android 2,00 € auf denselben Deckel, beide zeigen „Offline · n warten" und verkaufen weiter; Server an — Server, iPad und Android stehen auf 31,00 €. **Sperre:** Android am Server gesperrt, zeigt „Abgemeldet", verkauft weiter, meldet sich mit frischem Code neu an, die wartende Buchung kommt an |
| **Web-Verwaltung gegen dieselbe Aufstellung**, 22. September | Im Browser bei 1440 und 390 Pixeln, hell und dunkel: Übersicht, Mitglieder mit Kontoauszug, Berichte, Lager, Geräte, Benutzer. Das Lager zeigt dieselben Zahlen wie Tablet und iPad (Helles 105,4 l, Soda 9,2 l gezapft, gelernte Erträge 29,2 l und 47 l) — gerechnet mit derselben `Inventory`. Eine Aufladung über 5,00 € vom Schreibtisch stand nach dem nächsten Abgleich auf dem iPad im Simulator |
| Schlüsselbund im Simulator | Mit `CODE_SIGNING_ALLOWED=NO` gebaut verweigert er jeden Zugriff (-34018); die Kopplung merkt das seit 7d und sagt es. Mit Ad-hoc-Signatur gebaut (Xcode-Run, oder `xcodebuild` ohne den Schalter) hält er das Token — so geprüft |

**Nicht geprüft:**

- **Die Migration auf dem echten Vereinstablet.** Emulator und Test sind nicht das Gerät
  mit dem echten Bestand des Vereins. Vorher in der alten App sichern (Einstellungen → Backup →
  Sichern) und die Datei vom Tablet herunterkopieren. Nach dem Update Salden und Bestände
  mit einem Foto von vorher vergleichen.
- **Start auf einem echten iPad.** Auf diesem Mac gibt es keine Signaturidentität und
  kein angemeldetes Apple-Konto; das kann nur der Besitzer nachholen (unten).
- **Die übrigen Bildschirme auf iOS** habe ich nicht bedient: Mitgliedersuche, manueller
  Betrag, Rabatt, Aufladung, und die Verwaltungsbildschirme. Der Besitzer hat sie am
  21. September im Simulator selbst durchgespielt. Sie sind derselbe Compose-Code wie
  unter Android.
- **Dateiauswahl, Kamera, Sicherungsordner, BGTaskScheduler auf iOS.** Übersetzt,
  nie ausgeführt. Delegates und Sicherheits-Scope zeigen ihr Verhalten erst auf einem
  Gerät.
- **Abgleich über HTTPS mit echtem Zertifikat.** Der Zwei-Geräte-Test lief über
  `http://` auf dem eigenen Rechner; Caddy mit Let's Encrypt ist eingerichtet
  (`server/deploy/`), aber nie gegen einen echten Hostnamen gelaufen.
- **Belegfotos von Gerät zu Gerät.** Hochladen und Herunterladen sind gegen den
  nachgebauten Server und im Servertest geprüft, nicht mit einem echten Kamerafoto
  zwischen zwei Geräten.
- **Die Sicherung vor „Serverstand übernehmen".** Im Test war kein Sicherungsort
  eingerichtet; der Dialog hat davor gewarnt, gesichert wurde nichts.

---

## Ergebnis der Gerüstprüfung

Die fünf Annahmen aus dem ursprünglichen Plan, mit dem, was der erste Übersetzungslauf
gezeigt hat:

1. **Versionskatalog.** Löst auf, aber drei Einträge waren falsch. `sqlite-bundled 2.8.4`
   gibt es nicht — Room 2.8.4 verlangt 2.6.2. Googles `androidx.navigation:navigation-compose`
   hat keine iOS-Ziele; Multiplatform ist `org.jetbrains.androidx.navigation:navigation-compose
   2.10.0-beta01`, das an Lifecycle 2.11.0 hängt. Coil ist ganz raus (3.6.x ist mit
   Kotlin 2.4 gebaut, 3.5.0 gegen ein älteres Skiko); das eine Belegfoto lädt
   `platform/Images.kt`.
2. **Compose-Accessors.** Existieren in CMP 1.12.0, sind aber alle veraltet und zeigen auf
   `org.jetbrains.compose.*:1.12.0` sowie `material3` **1.9.0**, den letzten stabilen
   JetBrains-Stand. Google Maven hat keine iOS-Artefakte für foundation, ui oder material3;
   die androidx-Alternative aus dem alten Plan gibt es nicht. Die Koordinaten stehen fest im
   Katalog.
3. **Room-KSP** läuft auf Android und iOS. Handgeschriebene `actual object
   AppDatabaseConstructor` lehnt der Prozessor ab — er erzeugt sie selbst.
4. **`kotlin.time.Clock`** ist mit Kotlin 2.3.21 stabil.
5. **kotlinx-datetime-Felder** existieren noch, sind aber veraltet: `day` statt
   `dayOfMonth`, `month.number` statt `monthNumber`, `kotlin.time.Instant` statt
   `kotlinx.datetime.Instant`. Alles in `Time.kt` erledigt.

Außerhalb der fünf Punkte: AGP 9 lehnt `org.jetbrains.kotlin.android` und
`com.android.library` neben `kotlin.multiplatform` ab (`:shared` nutzt
`com.android.kotlin.multiplatform.library` mit `kotlin { android {} }`); Compose 1.12
verlangt compileSdk 37; `iosX64` gibt es für Compose 1.12 / Lifecycle 2.11 /
Navigation 2.10 nicht mehr; Material3 1.4+ bringt keine `material-icons` mehr mit, alle
Bildschirme nutzen `VdIcons`; das Opt-in für `ExperimentalMaterial3Api` aus dem alten
App-Modul steht jetzt in `:shared`; das SumUp-SDK braucht die Compose-BOM in androidMain.

---

## Was anders ist als im alten Plan

- **Sicherung ist eine Datei, kein Ordner und kein Zip.** Die SQLite-Datenbank nach einem
  WAL-Checkpoint. Das Zip verpackte zusätzlich die verschlüsselten Einstellungen, die mit
  einem Geräteschlüssel verschlüsselt und auf jedem anderen Gerät unlesbar waren.
  Wiederherstellen legt die Datei als `<db>.restore` daneben; `buildDatabase()` übernimmt
  sie beim nächsten Start vor dem Öffnen.
- **`BackupScheduler` hat kein `lastBackupAt()` mehr**, dafür `attach(work)`. Wann zuletzt
  gesichert wurde, weiß `SettingsRepository`; der Planer löst nur aus.
- **`BackupExchange`** kann auflisten und löschen (für das Wegräumen alter Sicherungen);
  `createBackupExchange()` baut ihn je Plattform.
- **`FileExchange`** hat zusätzlich `rememberPhotoPicker` (Mediathek), und `loadImageBitmap`
  in `platform/Images.kt` ersetzt Coil.
- **Kartenzahlung** läuft über `SalesViewModel.checkoutByCard(payments)` im Scope des
  ViewModels, damit ein Drehen des Tablets während das Terminal wartet die Buchung nicht
  verliert. Die Referenz entsteht vorab und geht an den Anbieter mit.
- **ViewModel-Fabriken sind weg**; `VereinsDeckelApp` erzeugt die ViewModels mit
  `viewModel { }` am Wurzel-Owner. `AppGraph` hält Datenbank und Repositories für beide
  Hosts.
- **`SettingsRepository`** hält einen Schnappschuss in einem StateFlow, weil der
  `SettingsStore` nichts beobachten kann. Die Schlüsselnamen sind die der alten
  DataStore-Fassung, der Android-Store liest nach Namen statt nach Typ — deshalb überleben
  die Einstellungen eines bestehenden Geräts das Update.
- **Bundle-ID** ist `com.example.vereinsdeckel` (Unterstriche sind in Bundle-IDs nicht
  erlaubt); die BGTask-Kennung entsprechend `com.example.vereinsdeckel.backup`.

---

## Bauen und starten

Von der Shell aus braucht Gradle das JDK aus Android Studio (das System-`java` ist 8):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :shared:compileKotlinIosArm64
./gradlew :androidApp:assembleDebug
./gradlew :core:allTests :shared:allTests   # braucht eine iOS-Simulator-Runtime
```

**iOS im Simulator:** `iosApp/iosApp.xcodeproj` in Xcode öffnen, Schema `iosApp`, ein
iPad wählen, Run. (Geprüft mit Xcode 26.6 und 27.0. In Xcode 27 gibt es keine
`Simulator.app` mehr; die Simulatoren erscheinen in Device Hub.) Die Skriptphase baut
das Kotlin-Framework über
`:shared:embedAndSignAppleFrameworkForXcode` und setzt `JAVA_HOME` selbst. Von der Shell:

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' build CODE_SIGNING_ALLOWED=NO
```

Das prüft, ob es baut. Wer im Simulator **koppeln** will, lässt `CODE_SIGNING_ALLOWED=NO`
weg (oder startet aus Xcode): Ohne Signatur verweigert der Schlüsselbund das Gerätetoken.
Für den Simulator signiert Xcode ad hoc, ein Team braucht es dafür nicht.

**Server zum Ausprobieren** (Docker): in `server/deploy`

```bash
../../gradlew :server:installDist
DOMAIN=localhost DB_PASSWORD=dev PAIRING_ADMIN_TOKEN=dev-admin-token-1234 \
  docker compose -f compose.yaml -f compose.dev.yaml up -d --build db api
curl -X POST -H "Authorization: Bearer dev-admin-token-1234" http://127.0.0.1:8080/v1/admin/pairing-codes
```

In der App unter Einstellungen → Server und Abgleich: vom Simulator aus
`http://127.0.0.1:8080`, vom Android-Emulator aus `http://10.0.2.2:8080`. Ohne `https://`
lässt die App nur diese Entwickleradressen zu.

**iOS auf dem iPad:** In Xcode unter Settings → Accounts die Apple-ID anmelden, die
Team-Kennung in `iosApp/Configuration/Config.xcconfig` unter `TEAM_ID` eintragen (oder im
Target unter Signing das Team wählen), iPad anschließen, Run. Ein kostenloses Konto
reicht; das Profil gilt dann sieben Tage. Beim ersten Start auf dem Gerät unter
Einstellungen → Allgemein → VPN & Geräteverwaltung dem Entwickler vertrauen.

---

## Nächste Schritte

1. **Auf dem iPad starten** (siehe oben) und Bar und Deckel einmal durchspielen. Danach
   die iOS-Dateiauswahl und Kamera anfassen — beides ist geschrieben, aber nie gelaufen.
2. **SumUp auf iOS.** Das SumUp-iOS-SDK per Swift Package einbinden, eine `SumUpBridge`
   in Swift schreiben (Vertrag in `SumUpPayments.ios.kt`: `isLoggedIn`, `login`,
   `checkout` mit Completion-Blöcken) und sie in `iOSApp.swift` statt `nil` übergeben.
   Bis dahin meldet Karte sich sauber ab; Bar und Deckel funktionieren.
3. **Hintergrundsicherung auf iOS** erst auf einem Gerät beurteilen; der Simulator führt
   BGTasks nicht selbstständig aus.
4. **Das Vereinstablet auf die neue App heben.** Erst sichern und die Sicherung vom
   Gerät herunterkopieren, dann aktualisieren, dann Salden und Bestände vergleichen. Das
   Tablet ist danach die Quelle für den Server (Spezifikation 2.5).
5. **Den Server aufstellen**, beim Bundesbruder im Rechenzentrum: `server/README.md`,
   Abschnitt „Aufstellen". Offen sind dafür der Hostname, die Erreichbarkeit auf 80/443
   und wer außer einer Person den Verwaltungsschlüssel hat.
6. **Optional, in der Spezifikation vorgesehen:** vor einer Deckelbelastung den Saldo
   online nachfragen (`GET /v1/members/{id}/balance`), damit ein Deckel am Limit auch
   dann auffällt, wenn das andere Gerät seit einer Minute nicht abgeglichen hat. Der
   Endpunkt steht, die App nutzt ihn noch nicht.

Kleinigkeiten, die man wissen sollte: Die Statuszeile auf iOS wird über das veraltete
`UIApplication.setStatusBarStyle` gesetzt (die Info.plist hat dafür
`UIViewControllerBasedStatusBarAppearance = NO`); die Vorschau-Annotation in commonMain
ist `androidx.compose.ui.tooling.preview.Preview` aus dem JetBrains-Artefakt
`ui-tooling-preview`; zwei Stellen in `IosFiles.kt` brauchen `BetaInteropApi`.

---

## Schritt 7: was gebaut wurde

Nach `docs/VereinsDeckel-Server-und-API.pdf`, Kapitel 2 und 4. Vier Commits: 7a Drahtformat
und Client in `:core`, 7b Schema 11, 7c Abgleich, 7d die Befunde aus dem Zwei-Geräte-Test.

**Schema 11.** Jede Zeile hat eine UUIDv7 als Schlüssel (`Ids.new()`), eine Löschmarke und
den `updated_at` des Servers (`SyncMeta`). Gelöscht wird weich. Kein Zähler wird mehr
fortgeschrieben: Der Saldo ist die Summe der Buchungen (`DerivedSql.BALANCE_EFFECT`, dieselbe
Regel wie `Ledger.balanceEffect` und die Serversicht `member_balances`), der Bestand ist
Eingänge minus Abgänge, „gezapft" ist die Summe der Abgänge im Zeitfenster des Anstichs.
Die Oberfläche merkt davon fast nichts — `Member`, `StockItem`, `ContainerType` und
`TappedContainer` sind Lesemodelle mit den hergeleiteten Feldern, die Tabellenzeilen heißen
`MemberRow` und so weiter.

**Migration 10 → 11** (`Migration10To11.kt`) baut jede Tabelle neu, vergibt die Schlüssel
(zeitgestempelte Zeilen bekommen eine UUID zu ihrem Zeitpunkt) und sorgt dafür, dass nach
dem Update dieselben Zahlen dastehen wie vorher: Wo der gespeicherte Saldo nicht der Summe
der Buchungen entsprach, entsteht eine Übertragsbuchung (`CORRECTION`), ebenso für Bestände
und die bisher gezapfte Menge offener Fässer. Buchungen gelöschter Mitglieder bleiben, ohne
Mitglied.

**Abgleich** (`data/sync/`). Jede Änderung schreibt in derselben Transaktion einen Auftrag
in `pending_changes` — aber erst, wenn das Gerät gekoppelt ist. Die Engine schiebt zu 200,
zieht zu 500 (Seite und Lesezeiger in einer Transaktion), und zwar nach jeder Änderung,
alle 60 Sekunden, beim Wechsel in den Vordergrund und auf Knopfdruck; nach einem Fehlschlag
mit offener Warteschlange nach 2, 4, 8 Sekunden. Der Verkauf wartet nie darauf. Der Status
steht unten in der Leiste (Telefon: Zeile über der Navigation, nur wenn es etwas zu sagen
gibt): „Abgeglichen", „n warten", „Offline", „Abgemeldet", „Abgelehnt".

**Koppeln** (Einstellungen → Server und Abgleich). Ist der Server leer, wird das Gerät zur
Quelle und lädt alles hoch; ist das Gerät leer, zieht es alles; haben beide Daten, fragt
ein Dialog, und „Serverstand übernehmen" ersetzt den Bestand des Geräts (vorher Sicherung,
wenn ein Sicherungsort eingerichtet ist). Ein am Server gesperrtes Gerät meldet sich mit
einem frischen Code neu an, ohne die Kopplung zu lösen — Bestand und Warteschlange bleiben.

### Entscheidungen des Besitzers, 21. September 2026

- Ein **Rabatt mindert die Deckelbelastung**; ein **Trinkgeld auf den Deckel belastet ihn**.
  Die alte App rechnete an beiden Stellen anders (Rabatt wurde beim Abzug ignoriert;
  Trinkgeld gibt es in der Oberfläche nur bei Karte, der Fall tritt also nicht auf).
- Beim Umstieg **bleiben die Salden, wie sie sind** — über Übertragsbuchungen, nicht durch
  Nachrechnen.
- Das **Android-Tablet ist die Quelle** für den Server.
- Beträge bleiben in der App `Double`, werden beim Schreiben auf Cent gerundet
  (`Money.cents`) und gehen als Zeichenkette mit zwei Stellen über den Draht.

### Abweichungen von der Spezifikation

- **Lagerabgänge sind eine eigene anfügende Tabelle** (`stock_draws`), und
  `stock_entries` trägt `container_type_id`. Die Spezifikation (2.3) leitet den Verbrauch
  nachträglich aus Buchung mal Rezeptur her. Das trägt nicht: Die Glasgröße der Variante
  steht in keiner Buchungszeile, und jede Rezepturänderung schriebe die Vergangenheit um.
  Die App hält beim Verkauf fest, was sie dem Keller entnommen hat.
- **Keine Fremdschlüssel in der App-Datenbank.** Eine geänderte Elternzeile bekommt am
  Server eine neue Sequenznummer und kann deshalb *nach* ihren Kindern ankommen. Der Server
  prüft Verweise, die App verlässt sich darauf.
- **„Zuletzt benutzt"** schreibt niemand mehr in die Mitgliederzeile; es ergibt sich aus der
  letzten Buchung (ohne Übertragsbuchungen). Sonst gäbe es bei zwei Theken laufend
  Stammdatenkonflikte.
- **Neu anmelden** kennt die Spezifikation nicht. Es nutzt denselben Endpunkt wie die
  Kopplung; am Server entsteht ein neues Gerät, das alte bleibt gesperrt in der Liste.
- Die serverseitigen Abweichungen stehen in `server/README.md`.

---

## Regressionsprüfung: nichts Android-Spezifisches in commonMain

```bash
grep -rn "^import java\.\|^import android\.\|^import androidx\.security\|^import androidx\.activity\|^import androidx\.work\|^import androidx\.core\.\|^import androidx\.compose\.ui\.tooling\.\|^import androidx\.sqlite\.db\|preferencesDataStore\|FileProvider" \
  shared/src/commonMain --include="*.kt"
```

Soll leer bleiben. (`androidx.compose.ui.tooling.preview.Preview` ist die Ausnahme und
wird vom Muster nicht getroffen.)

## Was bewusst nicht synchronisiert wird

Der SumUp-Affiliate-Key und die Darstellungseinstellungen bleiben gerätelokal. Der Key
ist ein Zugangsgeheimnis und hat in einem Sync-Payload nichts verloren; Hell/Dunkel ist
pro Gerät sinnvoll verschieden — das Wandtablet hinter der Theke will abends dunkel
bleiben, auch wenn das iPad im Garten hell läuft. Dasselbe gilt für den Zeitpunkt der
letzten Sicherung.

Seit Schritt 7 kommen dazu: das Gerätetoken (Schlüsselbund bzw. verschlüsselte
Einstellungen, nie in der Datenbank und damit nie in einer Sicherung), die Serveradresse,
und der lokale Pfad eines Belegfotos — über den Draht geht nur der Schlüssel, unter dem
der Server das Foto hält. Vereinsname und Vereinsfarbe sind ebenfalls noch je Gerät
einzustellen; die Spezifikation sieht dafür keine Tabelle vor.
