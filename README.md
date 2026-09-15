# VereinsDeckel

**VereinsDeckel** is a point of sale system for German clubs (*Vereine*). It handles
sales, member tabs, stock and card payments through SumUp.

A *Deckel* is the beermat you tally against. This is not a shop — it is a tab system with
a till attached, built for a volunteer on a shift rather than a trained cashier.

## Features

- **Sales** — colour-coded product grid, search, cart with quantity steppers, haptics
- **Member tabs** — balances with an allowance per category, audited top-ups
- **Stock** — recipes, several container sizes per item, learned keg yields
- **Goods receipts** — a whole receipt with its lines and a photo of the Kassabon
- **SumUp** — card payments through a paired terminal
- **Analytics and history** — per day, week, month; per product and category
- **Backups** — manual and scheduled

## Status

| | |
|---|---|
| Android | works, builds from `:androidApp` |
| iOS | builds and runs in the iPad simulator from `iosApp/`; card payments need the SumUp iOS SDK still — see `docs/PORTIERUNG.md` |
| Multi-device server | specified, not built — see the PDF below |

Both platforms build from the same shared module. `docs/PORTIERUNG.md` lists exactly what
has been verified and what has not.

## Tech stack

- **UI** — Compose Multiplatform, Material 3, own design system (`shared/src/commonMain/.../ui/theme`)
- **Database** — Room KMP with the bundled SQLite driver
- **Async** — Coroutines and Flow
- **Networking** — Ktor (for the planned server sync)
- **Payments** — SumUp Merchant SDK on Android, SumUp iOS SDK through a Swift bridge
- **Architecture** — MVVM, one shared module plus thin platform hosts

## Layout

```
shared/          everything common: data, logic, the whole UI
androidApp/      Android host — activity, application, backup worker, resources
iosApp/          Xcode project and Swift host; builds the Kotlin framework via Gradle
docs/            specification, porting plan, tooling
```

## Getting started

### Android

Android Studio with SDK 37 and its bundled JDK 21. From a shell, point `JAVA_HOME` at
Android Studio's JDK first (the system `java` is 8):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :androidApp:assembleDebug
```

Add your SumUp affiliate key under Einstellungen to enable card payments.

### iOS

macOS with Xcode 26 and an iOS simulator runtime. Open `iosApp/iosApp.xcodeproj`, pick
an iPad simulator and run — the build script phase compiles the Kotlin framework. To
install on a device, sign in with your Apple ID in Xcode and put your team ID into
`iosApp/Configuration/Config.xcconfig`.

```bash
./gradlew :shared:compileKotlinIosArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' build CODE_SIGNING_ALLOWED=NO
```

## Documentation

- **`docs/VereinsDeckel-Server-und-API.pdf`** — how the server database and REST API have
  to be built so two devices can share one dataset without losing a booking. Includes the
  three changes the current data model needs first, the full PostgreSQL schema, the sync
  protocol and ten hand-testable acceptance criteria. Regenerate with
  `python3 docs/spec-src/build_spec.py`.
- **`docs/PORTIERUNG.md`** — remaining work on the iOS port, in order.
- **`CLAUDE.md`** — design decisions that are settled, and the checks that keep them true.

## Note on CI

There is none. Every check in this repository is something a person runs locally.

## License

MIT — see [LICENSE](LICENSE).
