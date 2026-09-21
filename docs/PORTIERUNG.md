# Portierung auf iOS — Stand und nächste Schritte

Stand 15. September 2026. Die Schritte 0 bis 6 des ursprünglichen Plans sind umgesetzt,
je einer pro Commit auf `1.0.1-suh2bh`. Die App läuft aus demselben Code auf Android
und im iPad-Simulator. Schritt 7 (Datenmodell und Sync) ist unverändert offen — seit dem
21. September steht aber der Server (`server/`), gegen den er gebaut wird, und die
Saldoregel liegt in `core/.../data/Ledger.kt`.

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

**Nicht geprüft:**

- **Start auf einem echten iPad.** Auf diesem Mac gibt es keine Signaturidentität und
  kein angemeldetes Apple-Konto; das kann nur der Besitzer nachholen (unten).
- **Die übrigen Bildschirme auf iOS.** Im Simulator bedient wurden der Verkauf (bar und
  auf den Deckel), die Produktsuche mit der Bildschirmtastatur und die Historie. Nicht
  bedient: Mitgliedersuche, manueller Betrag, Rabatt, Aufladung, und die
  Verwaltungsbildschirme für Produkte, Lager, Mitglieder, Kategorien, Auswertung und
  Einstellungen. Sie sind derselbe Compose-Code wie unter Android, wo sie durchgespielt
  wurden.
- **Dateiauswahl, Kamera, Sicherungsordner, BGTaskScheduler auf iOS.** Übersetzt,
  nie ausgeführt. Delegates und Sicherheits-Scope zeigen ihr Verhalten erst auf einem
  Gerät.
- **Die Android-App auf einem echten Gerät nach dem Update.** Im Emulator hat die
  alte Datenbank (Schemaversion 10) mit dem gebündelten SQLite-Treiber ohne Migration
  geöffnet, die Einstellungen (Vereinsfarbe, Themenwahl) waren noch da.

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
4. **Schritt 7: Datenmodell auf UUID, abgeleiteter Saldo, dann Sync.** Unverändert nach
   `docs/VereinsDeckel-Server-und-API.pdf`, Kapitel 2 und 4. Jetzt erst, weil beide
   Plattformen starten. Die Gegenseite gibt es inzwischen: `server/` mit dem Schema, dem
   Tabellenregister (`sync/Entities.kt`, Spaltennamen und Vorgabewerte), dem JSON-Format
   (`sync/Values.kt`) und den Sentinel-Schlüsseln für Aufladung, manuellen Betrag und
   Trinkgeld in `Ledger`. Zwei Punkte sind dabei zu entscheiden, weil Server und heutige App
   verschieden rechnen: ob ein Rabatt die Deckelbelastung mindert (Server: ja, App: nein)
   und ob ein Trinkgeld auf den Deckel belastet wird (Server: ja, App: nein).

Kleinigkeiten, die man wissen sollte: Die Statuszeile auf iOS wird über das veraltete
`UIApplication.setStatusBarStyle` gesetzt (die Info.plist hat dafür
`UIViewControllerBasedStatusBarAppearance = NO`); die Vorschau-Annotation in commonMain
ist `androidx.compose.ui.tooling.preview.Preview` aus dem JetBrains-Artefakt
`ui-tooling-preview`; zwei Stellen in `IosFiles.kt` brauchen `BetaInteropApi`.

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
