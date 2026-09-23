# VereinsDeckel

**VereinsDeckel** is a point of sale system for German clubs (*Vereine*). It handles
sales, member tabs, stock and card payments through SumUp.

A *Deckel* is the beermat you tally against. This is not a shop — it is a tab system with
a till attached, built for a volunteer on a shift rather than a trained cashier.

## Features

- **Sales** — colour-coded product grid, search, cart with quantity steppers, haptics; cash tips from the change or as a typed amount
- **Member tabs** — balances with an allowance per category, audited top-ups
- **Stock** — recipes, several container sizes per item, learned keg yields
- **Goods receipts** — a whole receipt with its lines and a photo of the Kassabon
- **SumUp** — card payments through a paired terminal; the guest picks the tip on the terminal (SumUp Solo, Solo Lite), otherwise the till offers it, and every SumUp error is shown in plain words
- **Analytics and history** — per day, week, month; per product and category
- **Backups** — manual and scheduled

## Status

| | |
|---|---|
| Android | works, builds from `:androidApp` |
| iOS | builds and runs in the iPad simulator from `iosApp/`; card payments need the SumUp iOS SDK still — see `docs/PORTIERUNG.md` |
| Multi-device server | built and tested in `server/` (PostgreSQL, sync protocol, device pairing, receipt photos). Installs on any Linux box with Docker and git via `server/deploy/install.sh`, updates itself from this repo on request — see `server/README.md` |
| Web administration | built into the server under `/verwaltung`: sign-in with roles, members (with CSV import/export, profiles, blocking), statements with PDF, e-mail and bank import, cash book, stock, purchases with invoice reading and a mail inbox, deposits, books (income statement, assets, auditor bundle), devices, users, audit log, settings for the tablets, updates. Server-rendered, no scripts, with a phone layout. Concept: `docs/WEB-VERWALTUNG.md` |
| Multi-device in the app | done: UUID keys, balance and stock derived from append-only rows, offline-first sync, device pairing, shifts with or without a cash drawer, settings pushed from the server. Played through with two devices (Android emulator, iPad simulator) against the server in Docker. **The schema upgrade (10 → 15) has not run on a real tablet — take a backup first** |

Both platforms build from the same shared module. `docs/PORTIERUNG.md` lists exactly what
has been verified and what has not.

## Tech stack

- **UI** — Compose Multiplatform, Material 3, own design system (`shared/src/commonMain/.../ui/theme`)
- **Database** — Room KMP with the bundled SQLite driver
- **Async** — Coroutines and Flow
- **Networking** — Ktor client in the app, Ktor server in `server/`
- **Server** — Kotlin/JVM, PostgreSQL via JDBC with Flyway migrations, Argon2id device tokens; tests run against an embedded PostgreSQL
- **Payments** — SumUp Merchant SDK on Android, SumUp iOS SDK through a Swift bridge
- **Architecture** — MVVM, one shared module plus thin platform hosts

## Layout

```
core/            pure Kotlin shared by app and server: ids, time, money and quantity formats, the balance rule, wire format and sync client
shared/          everything common to both apps: data, logic, the whole UI
androidApp/      Android host — activity, application, backup worker, resources
iosApp/          Xcode project and Swift host; builds the Kotlin framework via Gradle
server/          the sync server from the specification, with its own README and deploy/ files
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

macOS with Xcode 26 or 27 and an iOS simulator runtime. Open `iosApp/iosApp.xcodeproj`, pick
an iPad simulator and run — the build script phase compiles the Kotlin framework. To
install on a device, sign in with your Apple ID in Xcode and put your team ID into
`iosApp/Configuration/Config.xcconfig`.

```bash
./gradlew :shared:compileKotlinIosArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' build CODE_SIGNING_ALLOWED=NO
```

That only checks that it builds. To pair with a server from the simulator, drop
`CODE_SIGNING_ALLOWED=NO` (or run from Xcode): an unsigned build cannot use the keychain,
which is where the device token lives.

### Server

To run it: a Linux box with Docker and git, nothing else — the service is built inside
Docker. Clone the repository there and run `server/deploy/install.sh`; it asks for the
hostname, generates the secrets and starts database, service, proxy and the updater.
Updates are then a click in the web administration (Einstellungen → Updates).

To develop: Java 17 or newer. The tests need neither Docker nor a local PostgreSQL — they
start an embedded one.

```bash
./gradlew :server:test
cd server/deploy && DOMAIN=localhost DB_PASSWORD=dev PAIRING_ADMIN_TOKEN=dev-admin-token-1234 \
  docker compose -f compose.yaml -f compose.dev.yaml up -d --build db api
```

Everything else is in `server/README.md`.

## Versions

The version lives in one file, `VERSION`: `x.y.z-beta` while in development, `x.y.z` once
the owner has released it. `docs/tools/version.sh` changes it (`beta x.y.z`, `release` —
which also tags `vx.y.z` — and `next`); Android, iOS, the app's settings screen, the
server's health endpoint and the web administration all show the same number from there.

## Documentation

- **`docs/VereinsDeckel-Server-und-API.pdf`** — how the server database and REST API have
  to be built so two devices can share one dataset without losing a booking. Includes the
  three changes the current data model needs first, the full PostgreSQL schema, the sync
  protocol and ten hand-testable acceptance criteria. Regenerate with
  `python3 docs/spec-src/build_spec.py`.
- **`server/README.md`** — building, running and deploying the server; where it deviates from the specification and why.
- **`docs/WEB-VERWALTUNG.md`** — concept for the web administration (members, invoices, stock, purchases, accounts) on top of the server.
- **`docs/PORTIERUNG.md`** — what has been verified and what has not, how step 7 (data model and sync) was built and where it deviates from the specification, and what is left.
- **`CLAUDE.md`** — design decisions that are settled, and the checks that keep them true.

## Note on CI

There is none. Every check in this repository is something a person runs locally.

## License

MIT — see [LICENSE](LICENSE).
