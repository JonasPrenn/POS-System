# VereinsDeckel-Server

Der Dienst nach `docs/VereinsDeckel-Server-und-API.pdf`: PostgreSQL, ein HTTP-Dienst,
zwei Sync-Endpunkte plus Geräteanmeldung. Reine JVM, hängt im Repo nur an `:core`.

## Bauen und prüfen

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :server:test          # startet einen eingebetteten PostgreSQL 16
./gradlew :server:installDist   # build/install/vereinsdeckel-server/
```

Die Tests brauchen kein Docker und kein installiertes PostgreSQL: Das Artefakt
`embedded-postgres` bringt die Binärdateien mit (Apple Silicon und Linux/ARM sind
eingetragen, x86-64 kommt transitiv mit).

## Lokal starten

```bash
export DATABASE_URL=postgres://vereinsdeckel:geheim@localhost:5432/vereinsdeckel
export PAIRING_ADMIN_TOKEN=$(openssl rand -hex 24)
export MEDIA_DIR=./media
./gradlew :server:run
```

Migrationen laufen beim Start. Ohne PostgreSQL bricht der Start ab — das ist Absicht.

## Aufstellen

`deploy/compose.yaml` ist die Aufstellung aus Kapitel 7.1: `db`, `api`, `proxy` (Caddy
mit Let's Encrypt). Das Image enthält kein Gradle, sondern das Ergebnis von
`installDist`. `./gradlew :server:installDist` läuft auch auf einem Rechner ohne
Android-SDK — Gradle fasst die Android-Module erst an, wenn eine ihrer Aufgaben gebraucht
wird (geprüft mit frischem Daemon und ohne `local.properties`).

```bash
cd server/deploy
cp .env.example .env            # DOMAIN, DB_PASSWORD, PAIRING_ADMIN_TOKEN eintragen
(cd .. && ../gradlew :server:installDist)
docker compose up -d --build
```

### Zum Ausprobieren auf dem eigenen Rechner

`compose.dev.yaml` legt den Port der API frei und lässt den Proxy weg — ohne Hostnamen
gibt es kein Zertifikat, und die App lässt `http://` nur für Entwickleradressen zu:

```bash
DOMAIN=localhost DB_PASSWORD=dev PAIRING_ADMIN_TOKEN=dev-admin-token-1234 \
  docker compose -f compose.yaml -f compose.dev.yaml up -d --build db api
```

Vom iPad-Simulator aus ist das `http://127.0.0.1:8080`, vom Android-Emulator aus
`http://10.0.2.2:8080`. So ist der Zwei-Geräte-Test in `docs/PORTIERUNG.md` gelaufen.

## Die ersten Anfragen

```bash
BASE=https://deckel.example.at
curl $BASE/v1/health

# Kopplungscode erzeugen (10 Minuten gültig, einmal einlösbar)
curl -X POST $BASE/v1/admin/pairing-codes -H "Authorization: Bearer $PAIRING_ADMIN_TOKEN"

# Gerät koppeln — das macht später der Einrichtungsdialog der App
curl -X POST $BASE/v1/devices/register -H "Content-Type: application/json" \
  -d '{"pairing_code":"8K4M-2QX9","label":"iPad Garten","platform":"ios"}'

# Geräte sehen, eines sperren
curl $BASE/v1/admin/devices -H "Authorization: Bearer $PAIRING_ADMIN_TOKEN"
curl -X POST $BASE/v1/admin/devices/<id>/revoke -H "Authorization: Bearer $PAIRING_ADMIN_TOKEN"

# Abgleich, mit dem Gerätetoken aus der Registrierung
curl "$BASE/v1/sync/changes?since=0&limit=500" -H "Authorization: Bearer vd_dev_..."
```

## Was wo steht

| Datei | Inhalt |
|---|---|
| `src/main/resources/db/migration/V1__grundgeruest.sql` | Schema nach Kapitel 3, Sicht `member_balances` nach 2.2 |
| `src/main/resources/db/migration/V2__lagerabgaenge.sql` | `stock_draws` und `stock_entries.container_type_id` (siehe Abweichungen) |
| `sync/Entities.kt` | Die zwölf Tabellen mit Spalten, Vorgabewerten und Konfliktart |
| `sync/SyncStore.kt` | Ziehen, Schieben, Konfliktregeln (Kapitel 4) |
| `sync/Values.kt` | Zahlenformate nach 5.4: Geld als Zeichenkette, Zeit als ISO 8601 |
| `devices/` | Kopplungscodes, Gerätetoken (Argon2id), Sperren |
| `media/ReceiptStore.kt` | Belegfotos als Dateien unter `MEDIA_DIR/receipts` |
| `http/` | Ktor-Routen, Fehlerbilder nach 5.3 |
| `core/.../sync/Wire.kt`, `SyncClient.kt` | Drahtformat und Client, gemeinsam mit der App; `SyncClientTest` prüft beide gegeneinander |

## Abweichungen von der Spezifikation

- **Lagerabgänge sind eine eigene anfügende Tabelle** (`stock_draws`, Migration V2), und
  `stock_entries` trägt `container_type_id`. Die Spezifikation (2.3) leitet den Verbrauch
  nachträglich aus Buchung mal Rezeptur her; das trägt nicht, weil die Glasgröße der
  Variante in keiner Buchungszeile steht und jede Rezepturänderung die Vergangenheit
  umschriebe. Die App hält beim Verkauf fest, was sie dem Keller entnommen hat. Gezapft je
  Anstich ist die Summe der Abgänge des Artikels im Zeitfenster des Anstichs — so zählen
  auch die Abgänge eines Geräts, dessen doppelter Anstich verworfen wurde.
- `members.last_used_timestamp` schreibt die App nicht mehr: „zuletzt benutzt" ergibt sich
  aus der letzten Buchung des Mitglieds, sonst gäbe es bei zwei Theken laufend
  Stammdatenkonflikte.
- Zeitstempel sind `TIMESTAMPTZ(3)`: Die App führt Millisekunden, und der Vergleich über
  `base_updated_at` muss exakt sein.
- `applied_changes` hat eine Spalte `seq`, damit ein Wiederholungsversuch dieselbe
  Antwort bekommt.
- Alle Schreibzugriffe auf Sync-Tabellen laufen über eine Advisory-Sperre
  (`Database.write`), damit Sequenznummern in Vergabereihenfolge sichtbar werden. Die
  Web-Verwaltung muss denselben Weg nehmen.
- Ein Einfügen mit bereits bekannter `id` wird als `ignored_stale` mit dem Serverstand
  beantwortet, auch bei anfügenden Tabellen. Ein `update` auf eine unbekannte Zeile ebenso,
  mit `current` weggelassen.
- `deliveries` folgt den Stammdatenregeln (änderbar, löschbar): Der Belegschlüssel kommt
  erst nach dem Foto-Upload dazu.
- Belegfotos werden als roher Body mit `Content-Type: image/jpeg` (png, webp, heic)
  hochgeladen, nicht als Multipart.
- Die Saldoregel (Sicht `member_balances`, `core/.../data/Ledger.kt`) folgt der
  Spezifikation: Rabatt mindert die Belastung, ein Trinkgeld auf den Deckel belastet ihn.
  Die alte App rechnete an beiden Stellen anders; der Besitzer hat am 21. September 2026
  für die Regel der Spezifikation entschieden, und seit Schema 11 rechnet die App genauso
  (`DerivedSql.kt`).
- **Neu anmelden** gibt es in der Spezifikation nicht: Ein gesperrtes oder sonst abgemeldetes
  Gerät löst einen frischen Kopplungscode ein, behält aber Bestand und Warteschlange. Für
  den Server ist das eine gewöhnliche Registrierung — es entsteht ein neues Gerät, das alte
  bleibt gesperrt in der Liste. Möglich ist das, weil Änderungen an ihrer
  `client_change_id` erkannt werden, nicht am Gerät.
