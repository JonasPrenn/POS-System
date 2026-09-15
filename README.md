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
| iOS | **in progress**, roughly half ported — see `docs/PORTIERUNG.md` |
| Multi-device server | specified, not built — see the PDF below |

The move to Kotlin Multiplatform is underway on branch `1.0.1-suh2bh` and **has not been
compiled yet**. Anyone picking it up should start with the first section of
`docs/PORTIERUNG.md`.

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
androidApp/      Android host — activity, manifest, resources
iosApp/          Xcode project (not yet present)
docs/            specification, porting plan, tooling
```

## Getting started

### Android

Android Studio with SDK 36, JDK 17.

```bash
./gradlew :androidApp:assembleDebug
```

Add your SumUp affiliate key under Einstellungen to enable card payments.

### iOS

Not runnable yet. Once `iosApp/` exists it needs macOS with Xcode, plus an Apple
Developer account to install on a device.

```bash
./gradlew :shared:compileKotlinIosArm64
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
