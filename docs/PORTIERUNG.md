# Portierung auf iOS — Stand und nächste Schritte

Arbeitsplan für die Fortsetzung. Geschrieben am Ende einer Sitzung, die ohne Android SDK,
ohne Xcode und ohne Zugriff auf Google Maven lief — deshalb ist **nichts davon je
übersetzt worden**. Die Reihenfolge unten ist danach sortiert, was das am billigsten
korrigiert.

---

## Erster Schritt lokal

Bevor irgendetwas Neues geschrieben wird:

```bash
./gradlew :androidApp:assembleDebug
```

Das wird fehlschlagen, und zwar reichlich — `commonMain` enthält noch elf Dateien mit
Android-Importen (Liste unten). Der Lauf ist trotzdem der richtige Anfang: Er sagt, ob
die **Gerüstannahmen** stimmen, und die sind das eigentliche Risiko. Fehler in den
Dateien interessieren erst danach.

Konkret zu prüfen, in dieser Reihenfolge:

1. **Löst der Versionskatalog auf?** Compose Multiplatform 1.12.0, Room 2.8.4 mit
   KMP-Zielen, Navigation 2.9.8, Lifecycle 2.10.0, Ktor 3.5.2, kotlinx-datetime 0.8.0.
   Alle Versionen außer den JetBrains-eigenen sind ungeprüft, weil Google Maven in der
   Ursprungsumgebung blockiert war.
2. **Gibt es `compose.material3` über das Plugin?** `shared/build.gradle.kts` nutzt die
   Plugin-Accessors statt fester Koordinaten, weil `org.jetbrains.compose.material3`
   stabil nur bis 1.9.0 existiert und danach auf `androidx.compose.material3` mit
   KMP-Zielen umgestellt wurde. Falls das Plugin das nicht auflöst, ist die Alternative,
   die androidx-Koordinaten direkt einzutragen.
3. **Nimmt Room KSP auf allen vier Zielen an?** Die `add("kspIosArm64", ...)`-Einträge am
   Ende von `shared/build.gradle.kts` sind der wahrscheinlichste Stolperstein.
4. **Ist `kotlin.time.Clock` stabil?** `platform/Time.kt`, eine Zeile. Falls noch
   Opt-in nötig: `@OptIn(ExperimentalTime::class)` dort ergänzen, sonst nirgends — alle
   elf ehemaligen `System.currentTimeMillis()`-Aufrufe hängen an dieser einen Funktion.
5. **Heißen die kotlinx-datetime-Felder noch so?** `VdDate` benutzt `monthNumber`,
   `dayOfMonth`, `dayOfYear`, `dayOfWeek.ordinal`. In 0.8.0 wurden einige davon
   umbenannt. Ebenfalls eine Datei.

Erst wenn das Gerüst steht, lohnt sich Schritt 1 unten.

---

## Was noch in `commonMain` liegt und nicht dorthin gehört

```
11 Stellen  data/repository/BackupRepository.kt      Zip, File, Uri, Log — komplett Android
 6 Stellen  ui/screens/DeliveryDialog.kt             Kamera, FileProvider, File
 6 Stellen  data/SettingsRepository.kt               Context, DataStore, EncryptedSharedPreferences
 5 Stellen  ui/screens/SettingsScreen.kt             WorkManager, TimeUnit, toUri, Dateiauswahl
 2 Stellen  viewmodel/ProductViewModel.kt            InputStream/OutputStream in der Signatur
 2 Stellen  viewmodel/MemberViewModel.kt             dito
 2 Stellen  ui/theme/Theme.kt                        Activity, WindowCompat
 2 Stellen  ui/screens/ProductManagementScreen.kt    ActivityResultContracts
 2 Stellen  ui/screens/MemberManagementScreen.kt     ActivityResultContracts
 2 Stellen  ui/screens/InventoryScreen.kt            ActivityResultContracts
 1 Stelle   ui/components/ComponentPreviews.kt       androidx-Preview-Annotation
```

Nachzählen:

```bash
grep -rn "^import java\.\|^import android\.\|^import androidx\.security\|^import androidx\.activity\|^import androidx\.work\|^import androidx\.core\.\|^import androidx\.compose\.ui\.tooling\|^import androidx\.sqlite\.db\|preferencesDataStore\|FileProvider" \
  shared/src/commonMain --include=*.kt | awk -F: '{print $1}' | sort | uniq -c | sort -rn
```

Ziel: leer.

---

## Schritt 1 · ViewModels von Strömen befreien

`ProductViewModel.importProductsFromCsv(InputStream)` und
`exportProductsToCsv(OutputStream)`, dasselbe in `MemberViewModel`.

Signaturen auf `String` umstellen: `importProductsFromCsv(csv: String)` und
`exportProductsToCsv(): String`. Der Vertrag in `platform/FileExchange.kt` reicht bereits
Zeichenketten durch — eine CSV mit Vereinsprodukten passt in den Speicher, und für
etwas anderes ist dieser Weg nicht gedacht.

Danach fallen die `java.io`-Importe in beiden ViewModels weg.

**Aufrufstellen:** `ProductManagementScreen`, `MemberManagementScreen`, `InventoryScreen`.

## Schritt 2 · Dateiauswahl umsetzen

Die `expect`-Deklarationen stehen in `platform/FileExchange.kt`:
`rememberTextFileReader`, `rememberTextFileWriter`, `rememberPhotoCapture`,
`rememberBackupDestinationPicker`, `rememberBackupFileReader`.

**Android** (`shared/src/androidMain/.../platform/FileExchange.android.kt`): wickelt
`rememberLauncherForActivityResult` ein. Der Code dafür steht heute noch in den vier
Bildschirmen und kann von dort übernommen werden.

**iOS** (`shared/src/iosMain/.../platform/FileExchange.ios.kt`): `UIDocumentPickerViewController`
für Lesen und Schreiben, `UIImagePickerController` für die Kamera. Der Ablageort für
Sicherungen braucht ein per Sicherheits-Scope aufbewahrtes Lesezeichen
(`bookmarkDataWithOptions`) — ein bloßer Pfad überlebt den nächsten Start nicht.

**Danach** die vier Bildschirme auf die neuen Composables umstellen und die
`androidx.activity`-Importe entfernen.

## Schritt 3 · SettingsRepository aufteilen

`data/SettingsRepository.kt` hängt an Context, DataStore und
EncryptedSharedPreferences. Der Ersatz steht schon: `SettingsStore` in
`platform/Platform.kt`, umgesetzt als `AndroidSettingsStore` und `IosSettingsStore`.

Das Repository behält seine `Flow`-Schnittstelle nach außen — Vereinsname,
Vereinsfarbe, Themenwahl, SumUp-Key, Sicherungsort, Auto-Sicherung — und liest
darunter aus `SettingsStore` statt direkt aus DataStore. Der SumUp-Key wandert auf
`getSecret`/`putSecret`.

**Neu dazu:** `apiBaseUrl` als Einstellung. Die braucht Schritt 7.

## Schritt 4 · BackupRepository nach androidMain, iOS nachziehen

`data/repository/BackupRepository.kt` ist zu 100 % Android: `ZipOutputStream`,
`FileInputStream`, `DocumentFile`, `Uri`, `android.util.Log`.

Vorgehen: Vertrag in `commonMain` (`BackupExchange` in `platform/FileExchange.kt` deckt
den Dateizugriff bereits ab), Zip-Logik je Plattform. Unter iOS gibt es kein
`java.util.zip` — entweder `NSFileCoordinator` mit einem Archivformat aus
`libcompression`, oder das Sicherungsformat auf „Ordner mit Dateien" ändern, was auf
beiden Plattformen billiger ist als ein Zip.

**Vorschlag:** Format auf Ordner ändern. Das Zip war nie eine Anforderung, sondern
kam von `ZipOutputStream`.

**`BackupScheduler`** (Vertrag steht) umsetzen: Android über WorkManager — der
bestehende `androidApp/.../worker/BackupWorker.kt` bleibt und wird nur angesteuert —,
iOS über `BGTaskScheduler`. Wichtig für die Oberfläche: iOS entscheidet selbst, wann ein
Hintergrundlauf stattfindet. Die Einstellungen dürfen deshalb nicht „täglich"
versprechen, sondern zeigen, wann zuletzt gesichert wurde. `lastBackupAt()` ist dafür da.

## Schritt 5 · Theme und Vorschauen

`ui/theme/Theme.kt` benutzt `Activity` und `WindowCompat` für Edge-to-Edge. Ersatz:
`SystemBarsEffect(darkTheme)`, `expect` steht bereits am Ende von `platform/Platform.kt`.
Android nimmt den heutigen Code, iOS setzt `preferredStatusBarStyle` am
View-Controller.

`ui/components/ComponentPreviews.kt` importiert die androidx-Preview-Annotation.
Umstellen auf `org.jetbrains.compose.ui.tooling.preview.Preview` und in
`shared/build.gradle.kts` `implementation(compose.components.uiToolingPreview)` zu
`commonMain` ergänzen.

## Schritt 6 · iOS-Host

Fehlt vollständig. Gebraucht wird:

```
iosApp/
  iosApp.xcodeproj/
  iosApp/
    iOSApp.swift            @main, baut das Platform-Objekt und reicht es hinein
    ContentView.swift       UIViewControllerRepresentable auf MainViewController()
    Keychain.swift          erfüllt SecretStore aus IosPlatform.kt
    SumUpBridge.swift       erfüllt SumUpBridge aus SumUpPayments.ios.kt
    Info.plist              NSCameraUsageDescription, NSBluetoothAlwaysUsageDescription
```

Dazu in `shared/src/iosMain` ein `MainViewController.kt`:

```kotlin
fun MainViewController(platform: Platform): UIViewController =
    ComposeUIViewController { VereinsDeckelApp(platform) }
```

**SumUp iOS SDK** über CocoaPods oder SPM einbinden. Es ist ein anderes Produkt als die
Android-Fassung, nicht dieselbe Bibliothek in zwei Hüllen: `SumUpSDK.setup(withAPIKey:)`,
`SumUpSDK.presentLogin(from:animated:completion:)`,
`SumUpSDK.checkout(_:from:completion:)`. Der Vertrag in `SumUpPayments.ios.kt` ist genau
darauf zugeschnitten — rückrufbasiert, weil Swift Kotlins Coroutinen nicht kennt.

**Nur auf echtem Gerät prüfbar.** Ein Simulator kann keine Bluetooth-Kartenzahlung. Ohne
Terminal hält `UnavailablePaymentProcessor` die App vollständig bedienbar; Bar und
Deckel funktionieren, Karte meldet sich sauber ab.

## Schritt 7 · Erst wenn alles übersetzt: Datenmodell und Sync

**Nicht vorziehen.** Dieser Schritt ändert das Schema auf beiden Plattformen und
zusätzlich die laufende Android-Installation. Ihn auf ungeprüftem Code aufzusetzen heißt,
zwei Fehlerquellen zu vermischen.

Grundlage ist `VereinsDeckel-Server-und-API.pdf`, Kapitel 2. Die drei Änderungen:

| | Heute | Muss werden |
|---|---|---|
| Schlüssel | `autoGenerate`-Long | UUIDv7, clientseitig (`platform/Ids.kt` erzeugt sie bereits) |
| Saldo | `SET balance = balance + :amount` | abgeleitet aus `transactions` |
| Bestand | `simple_quantity`, `full_count`, `drawn` | abgeleitet aus den anfügenden Tabellen |

Ohne die zweite verliert der Mehrgerätebetrieb Geld, und zwar so, dass das Ergebnis
plausibel aussieht: Zwei Kassen buchen gleichzeitig auf denselben Deckel, eine Buchung
fällt weg, niemand merkt es.

Danach erst: Ktor-Client, Outbox mit Idempotenzschlüsseln, Pull/Push nach Kapitel 4,
Gerätekopplung, und die API-URL-Einstellung mit Erreichbarkeitsprüfung gegen
`GET /v1/health`.

---

## Reihenfolge, kurz

```
0  Gerüst übersetzen lassen          <- zuerst, sagt ob die Versionen stimmen
1  ViewModels: Ströme raus
2  Dateiauswahl je Plattform
3  SettingsRepository auf SettingsStore
4  BackupRepository aufteilen, Scheduler
5  Theme, Vorschauen
6  iOS-Host und Xcode-Projekt
   ---- ab hier läuft die App auf beiden Plattformen ----
7  Datenmodell umstellen, dann Sync
```

Schritte 1 bis 5 sind Fleißarbeit mit wenig Entwurfsrisiko. Schritt 6 ist das erste Mal,
dass etwas auf dem iPad startet. Schritt 7 ist ein eigenes Vorhaben.

## Was bewusst nicht synchronisiert wird

Der SumUp-Affiliate-Key und die Darstellungseinstellungen bleiben gerätelokal. Der Key
ist ein Zugangsgeheimnis und hat in einem Sync-Payload nichts verloren; Hell/Dunkel ist
pro Gerät sinnvoll verschieden — das Wandtablet hinter der Theke will abends dunkel
bleiben, auch wenn das iPad im Garten hell läuft.
