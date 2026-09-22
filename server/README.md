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

Auf dem Server braucht es **Docker** (mit dem Compose-Plugin) und **git**, sonst nichts — kein
Java, kein Gradle: Der Dienst wird im Container gebaut (`Dockerfile`, zwei Stufen). Ein
Raspberry Pi 5 oder ein kleiner Linux-Server reicht. Der Hostname muss per DNS auf den Server
zeigen, die Ports 80 und 443 müssen erreichbar sein; Caddy holt das Zertifikat selbst.

```bash
# 1. Zugang zum Repo: ein Deploy-Key (nur lesend), solange das Repo privat ist
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N ""
cat ~/.ssh/id_ed25519.pub          # in GitHub → Repo → Settings → Deploy keys eintragen

# 2. Klonen und aufstellen
git clone git@github.com:JonasPrenn/POS-System.git /opt/vereinsdeckel
cd /opt/vereinsdeckel/server/deploy
./install.sh                        # fragt den Hostnamen ab, erzeugt Passwörter, baut, startet
```

`install.sh` legt `.env` an (Datenbankpasswort und Verwaltungsschlüssel erzeugt, `REPO_DIR`
und Zweig eingetragen), baut die Images und startet `db`, `api`, `proxy` und `updater`.
Beim ersten Mal dauert das Bauen einige Minuten (Gradle lädt im Container); der Cache
bleibt für das nächste Mal. Danach:

- `https://<Hostname>/verwaltung` öffnen und mit dem Verwaltungsschlüssel aus `.env`
  (`PAIRING_ADMIN_TOKEN`, `install.sh` zeigt ihn) den ersten Administrator anlegen.
- Unter Einstellungen Name, Farbe, Bankverbindung, E-Mail eintragen; unter Geräte einen
  Kopplungscode erzeugen und am Tablet eintragen.

Noch einmal `./install.sh` baut und startet neu, ohne die `.env` anzufassen. Die Daten liegen
in den Docker-Volumes `pgdata` (Datenbank), `media` (Belegfotos), `updates`; eine Sicherung
ist `docker compose exec db pg_dump -U vereinsdeckel vereinsdeckel > sicherung.sql` plus das
Volume `media`.

### Updates

Was im Git-Repo neu ist, kommt ohne Anmeldung am Server auf den Dienst: In der Verwaltung
unter **Einstellungen → Updates** (nur Administrator) steht, welcher Stand läuft und welcher
im Repo ist; „Jetzt suchen“ fragt nach, „Jetzt installieren“ spielt ein. Einstellbar ist,
ob der Updater von selbst sucht und installiert (`AUTO`), nur sucht (`CHECK`) oder nichts
von selbst tut (`MANUAL`), und in welchem Abstand.

Das tut der Dienst `updater` (`deploy/updater/`): ein kleiner Container mit git und der
Docker-CLI, der das Repo auf dem Server (`REPO_DIR`), den Docker-Socket und denselben
SSH-Schlüssel sieht, mit dem geklont wurde. Er redet mit dem Dienst nur über das Volume
`updates` (`web/Updates.kt`: `settings.json`, `request`, `status.json`) — der Dienst selbst
sieht weder git noch Docker. Installieren heißt: Zweig holen, Image bauen, `api` neu
starten; schlägt das Bauen fehl, läuft der alte Stand weiter, und der Grund steht in der
Verwaltung. Von Hand geht dasselbe mit `./update.sh`. Welcher Stand läuft, trägt das Image
als `VEREINSDECKEL_VERSION` (Commit-Kennung, gesetzt von `install.sh`, `update.sh` und dem
Updater); die Versionsnummer dazu (`VERSION` im Repo, etwa „1.2.0 Beta“) zeigen die Seite,
`/v1/health` und der Updater für den Stand im Repo.

### Zum Ausprobieren auf dem eigenen Rechner

`compose.dev.yaml` legt den Port der API frei und lässt Proxy und Updater weg — ohne
Hostnamen gibt es kein Zertifikat, und die App lässt `http://` nur für Entwickleradressen
und Adressen im eigenen Netz zu:

```bash
cd server/deploy
DOMAIN=localhost DB_PASSWORD=dev PAIRING_ADMIN_TOKEN=dev-admin-token-1234 \
  docker compose -f compose.yaml -f compose.dev.yaml up -d --build db api
```

Die Verwaltung liegt dann unter `http://127.0.0.1:8080/verwaltung`; der Verwaltungsschlüssel
für die Ersteinrichtung ist der `PAIRING_ADMIN_TOKEN` aus dem Aufruf oben.

Vom iPad-Simulator aus ist das `http://127.0.0.1:8080`, vom Android-Emulator aus
`http://10.0.2.2:8080`. So ist der Zwei-Geräte-Test in `docs/PORTIERUNG.md` gelaufen.
## Die Verwaltung

Unter `/verwaltung` liegt die Web-Oberfläche (`docs/WEB-VERWALTUNG.md`), derselbe Dienst,
server-gerendertes HTML. Gebaut ist Phase 1: Anmeldung mit Rollen, Übersicht, Mitglieder mit
Deckelstand und Kontoauszug, Berichte, Lager, Einkauf (die Wareneingänge mit Belegfoto),
Geräte koppeln und sperren, Benutzer, Protokoll, Einstellungen. Aus Phase 2 dazu: Kassier und
Administrator legen Mitglieder an, ändern Name, Couleurname und Kategorie, sperren einen Deckel
mit Grund (die Theke zeigt ihn und schreibt nicht mehr an), löschen ein Mitglied, dessen Deckel
auf null steht (weich: die Tablets nehmen es aus ihren Listen, die Buchungen behalten den Namen,
das Profil am Server ist weg), und buchen Aufladungen (bar, Karte,
Überweisung) und Korrekturen mit Grund auf den Deckel. Mitglieder kommen auch als CSV herein
(`web/MemberCsv.kt`: die Datei des Tablets oder eine aus Excel mit Kopfzeile, Vorschau zuerst,
wen es schon gibt, den gibt es nur einmal, leere Profilfelder werden ergänzt) und als CSV
wieder heraus. Das ist das Einzige, was die
Verwaltung in synchronisierte Tabellen schreibt, und es geht über `Database.write`
(`web/Writes.kt`) — dieselbe Sequenzsperre wie beim Abgleich; die Tablets holen es sich beim
nächsten Abgleich. Gebucht wird in der Form, in der die App bucht; jede Buchung trägt den
Schlüssel ihres Formulars, ein Doppelklick bucht einmal. Ebenso der **Einkauf** (`web/Purchases.kt`):
Belege mit Datei (PDF oder Foto), Lieferant, Nummer, Fälligkeit, Zahlung; Lagerpositionen
werden `stock_entries` mit `delivery_id` — dieselbe Zeile, die ein Tablet beim Wareneingang
schreibt, also kommen sie dort an. Zeilen ohne Lagerartikel (Pfand, Energie) bekommen ein
Konto aus dem vorbelegten Kontenrahmen (`accounts`, Konzept 4.6). Ein hochgeladenes PDF mit
Textebene **liest die Verwaltung** (`InvoiceReader.kt`, PDFBox, Muster statt Modell): Lieferant,
Nummer, Datum, Fälligkeit, Brutto, Umsatzsteuer und die Positionen — die einfache Zeile („2 Fass
Helles 50 l 142,00 284,00“) wie die einer Brauereirechnung mit Artikelnummer voran und der
Menge vor den Beträgen („10020 gold spezial Fass (20 Liter) 6 56,20 -20% … 298,56“), netto
ausgewiesen und brutto hochgerechnet, mit umbrochenen Bezeichnungen und dem Gebindesaldo als
eigener Zeile; die Lieferaufstellung dahinter zählt nicht doppelt. Gebaut an einer echten
Rechnung der Brauerei Frastanz. Leere Felder füllt die Datei, Eingetragenes gilt. Je
Position schlägt sie den Lagerartikel vor, dessen Name in der Zeile steckt (bei Fassware das
Gebinde, dessen Größe dasteht), oder das, was der Kassier beim letzten Beleg dieses Lieferanten
bestätigt hat (`supplier_articles`, mit dem Verhältnis Lagermenge zu Rechnungsmenge, etwa 20
Flaschen je Kiste). Bestätigt wird jede Zeile vor dem Buchen. Ein Scan ohne Textebene ergibt
nichts, und die Seite sagt es. Und das **Sortiment**
(`web/Products.kt`): Produkte mit Preis, Kategorie und Ausschankgröße, Varianten, Rezepturen
(welcher Lagerartikel je Einheit), aus dem Sortiment nehmen — dazu die Mitgliederkategorien
mit ihren Limits. Es sind dieselben Zeilen in denselben synchronisierten Tabellen, die die App
führt; ein Preis, der hier und am Tablet geändert wird, folgt der Regel „letzter
Schreibvorgang gewinnt“ wie zwischen zwei Tablets.

**Abrechnung** (`web/Statements.kt`, Konzept 4.2): Ein Lauf zum Stichtag erzeugt je Mitglied
unter der Schwelle einen Kontoauszug mit Zahlungsaufforderung — Nummer `VD-JJJJMM-NNNN` als
Verwendungszweck, Anfangs- und Endstand als Schnappschuss, Zahlungsziel, auf Wunsch eine
Zusatzzeile wie der Semesterbeitrag. Das PDF (`StatementPdf.kt`, openhtmltopdf) trägt einen
EPC-QR-Code (zxing), den jede Banking-App liest. Versand per E-Mail (Jakarta Mail über den
SMTP-Zugang aus den Einstellungen) an Mitglieder mit Adresse und Einwilligung im Profil
(`member_profiles`, nur am Server), für die anderen eine Druckmappe. Zahlungseingang von Hand
oder aus dem Kontoauszug der Bank (`BankImport.kt`: CAMT.053 als XML, oder eine CSV, deren
Spalten am Kopf erkannt werden; ein zweites Einlesen bucht nichts doppelt). Eine Zahlung wird
als Aufladung mit Zahlart `BANK` gebucht und erreicht die Tablets über den Abgleich — einen
zweiten Kontostand gibt es nicht. Erinnerungen per E-Mail oder als Vermerk, Storno mit Grund.
Zahlt jemand an der Theke statt per Überweisung, sieht die Verwaltung die Aufladung nach dem
Stichtag: Die Abrechnung bleibt offen, steht aber als „aufgeladen“ da und auf der Übersicht
als Hinweis für den Kassier; ein Klick schließt sie mit genau dieser Aufladung — ohne zweite
Buchung, und erinnert wird dann nicht mehr.
Das SMTP-Passwort liegt in der Tabelle `settings`, wie alles andere in dieser Datenbank.

**Kasse** (`web/Cash.kt`, Konzept 4.5): Gezählt wird am Tablet, nicht im Web. Dort öffnet
die Schicht mit dem gezählten Wechselgeld, nimmt Entnahmen und Einlagen mit Grund und Namen
auf und schließt mit der Zählung — eine Differenz braucht einen Vermerk. Das kommt als
`cash_sessions` und `cash_movements` über den Abgleich an, wie jede andere Zeile der Theke.
Ein Bardienst ohne Barkasse (Schema 15, `cash_sessions.cashless`, V13) hat keine Lade: Die Theke
nimmt in der Zeit kein Bargeld, unter Kasse steht er als solcher, im Kassenbuch kommt er nicht
vor. Die Verwaltung rechnet daraus das Kassenbuch: je Schicht Öffnung, Bareinnahmen des Geräts
(`transaction_effects` nach `origin_device`, Barverkäufe nach Rabatt, Aufladungen und
Trinkgeld in bar, Stornos ziehen ab), Entnahmen, Einlagen, Schluss mit Differenz, dazu der
fortgeführte Bestand — nichts davon steht als Zähler in einer Spalte. Jede Lade ist eine
eigene: Was das zweite Tablet bar einnimmt, taucht in der Schicht des ersten nicht auf.
Dazu der Tagesbericht nach Gerät und Zahlart, die laufenden Schichten und das Bankbuch aus
dem Kontoauszug-Import; das Kassenbuch gibt es als CSV. Ändern lässt sich hier nichts —
ein Fehler bekommt am Tablet eine Gegenbuchung.

**Bücher** (`web/Books.kt`, Konzept 4.6): die Einnahmen-Ausgaben-Rechnung je Rechnungsjahr
nach dem Zufluss-Abfluss-Prinzip, hergeleitet aus Buchungen und Belegen. Einnahme ist, was bar,
mit Karte oder per Überweisung eingegangen ist (Budenerlöse, Aufladungen und Zahlungen,
Trinkgeld); ein Verkauf auf den Deckel ist eine Forderung, keine Einnahme. Ausgabe ist ein
bezahlter Beleg zum Zahltag — Belegzeilen auf ihr Konto, Lagerzeilen als Getränkeeinkauf.
Dazu die Vermögensübersicht zum Stichtag (Kassabestand aus der letzten Zählung je Gerät,
Bankstand wie vom Kassier eingetragen, Lagerwert, Forderungen, Guthaben, offene Belege) und
zwei CSV-Dateien für den Steuerberater: die Rechnung je Konto mit Vorjahr und das Journal.
Für die Rechnungsprüfer die **Prüfermappe** (`AuditBundlePdf.kt`): ein PDF mit Rechnung,
Vermögensübersicht, dem Kassabuch des Jahres, der Belegliste und den Abrechnungsläufen — die
Belegdateien selbst bleiben unter Einkauf. Nicht darin: Veranstaltungen als Kostenstelle, das
BMD-Format.

Am Telefon gibt es eine untere Leiste mit Übersicht, Mitgliedern und Berichten; der
Rest liegt unter „Mehr".

**Ersteinrichtung:** Solange es keinen Benutzer gibt, führt `/verwaltung` auf eine Seite, die
den `PAIRING_ADMIN_TOKEN` verlangt und den ersten Administrator anlegt. Danach ist sie zu.
Weitere Zugänge legt der Administrator unter „Benutzer und Rollen" an; das Passwort gibt er
persönlich weiter. Der letzte Administrator kann sich nicht selbst herabstufen oder sperren.

| Rolle | Sieht |
|---|---|
| Administrator | alles, dazu Benutzer |
| Kassier | Übersicht, Mitglieder, Abrechnung, Kasse, Berichte, Bücher, Lager, Sortiment, Einkauf, Geräte, Protokoll, Einstellungen |
| Senior und Chargen | Übersicht, Mitglieder, Abrechnung, Kasse, Berichte, Bücher, Lager, Sortiment, Einkauf — lesend |
| Budenwart | Lager, Sortiment, Einkauf — keine Deckel (Art. 9 DSGVO, 2.5 im Konzept) |
| Rechnungsprüfer | Abrechnung, Kasse, Berichte, Bücher, Einkauf, Protokoll; mit „Zugang bis" zeitlich begrenzt |

**Wie sie gebaut ist:** Passwörter mit Argon2id wie die Gerätetoken. Im Cookie steht ein
Zufallswert (`HttpOnly`, `Secure`, `SameSite=Lax`, nur unter `/verwaltung`), in der Datenbank
sein SHA-256. Jedes Formular trägt das Geheimnis der Sitzung. Die Seiten kommen ohne Skripte
und ohne Inline-Styles aus, die Content-Security-Policy ist entsprechend `default-src 'none'`
mit einem Stylesheet; berechnete Breiten (Balken, Diagramme) sind SVG mit Attributen, die
Vereinsfarbe ein eigenes kleines Stylesheet. Anmeldeversuche sind auf zehn je Minute und
Adresse begrenzt.

| Variable | Bedeutung |
|---|---|
| `VEREIN_ZONE` | Zeitzone für „heute" und die Monatsgrenzen, Vorgabe `Europe/Vienna` |
| `TRUST_PROXY` | `true`, wenn der Dienst nur über Caddy erreichbar ist — dann gilt `X-Forwarded-For` als Adresse des Anrufers. In `compose.yaml` gesetzt, in `compose.dev.yaml` nicht |
| (Einstellungen) | IMAP-Server, Port, Benutzer, Passwort, Ordner und „alle 10 Minuten abrufen“ stehen in der Tabelle `settings`, nicht in der Umgebung |
| `WEB_INSECURE_COOKIES` | `true` nur zum Ausprobieren ohne HTTPS (`compose.dev.yaml`); im Betrieb nie |

**Rechnungen per E-Mail** (`Mailbox.kt`, `MailIntake.kt`, V11): Dasselbe Postfach, das die
Abrechnungen verschickt, ist die Rechnungsadresse bei Brauerei und Händler. Ist unter
Einstellungen der IMAP-Zugang eingetragen (Benutzer und Passwort leer: die vom Versand), holt
der Dienst alle zehn Minuten die ungelesenen Mails (und auf Knopfdruck unter Einkauf →
Posteingang); jede mit PDF wird festgehalten. Von einem Absender, den der Kassier einmal
freigegeben hat, wird sie gleich ein Beleg: Kopf aus der Datei, die gemerkten Positionen und
bekannten Pfandgebinde sofort gebucht, alles andere wartet als Vorschlag am Beleg. Ein
unbekannter Absender wartet im Posteingang — übernehmen (und freigeben) oder ablehnen. Nichts
wird zweimal eingelesen (Message-ID), ein Beleg mit derselben Nummer bleibt als „nicht
angelegt“ stehen, ein Verbindungsfehler steht in den Einstellungen. Die Mails bleiben im
Postfach, nur die Lesemarke wird gesetzt. Was der Posteingang selbst bucht, steht im Protokoll
unter „Posteingang (automatisch)“.

Unter dem Lager steht **Pfand und Leergut** (`deposit_kinds`, `deposit_movements`, V10): je
Gebindeart — Fass 20 l, Fass 30 l, Kiste 12 × 1 l, Container — unabhängig vom Inhalt, was beim
Lieferanten liegt (geliefert minus zurück, aus Bewegungen hergeleitet) und der Pfandwert dazu.
Gefüttert wird es aus der Rechnung: die Gebindetabelle einer Brauereirechnung wird gelesen
(geliefert, zurück, Pfand je Stück), eine Pfandzeile eines Händlers ordnet der Kassier einem
Gebinde zu; Leergut ohne Beleg bucht er von Hand. In den Büchern ist Pfand ein Abfluss auf
„Pfand und Leergut“, der beim Zurückgeben zufließt; der Bestand steht in der Vermögensübersicht.

Das Lager zeigt neben dem Bestand den **Bestellvorschlag**: alles unter Mindestbestand,
gruppiert nach dem Lieferanten der letzten Lieferung (aus dem Beleg oder dem Wareneingang),
aufgefüllt auf das Doppelte des Mindestbestands, Fässer in ganzen Gebinden. Bestellt wird
beim Lieferanten, nicht hier.

Die Berichte zeigen, was sich aus den Buchungen der Theke sicher sagen lässt: Umsatz nach
Zahlart je Monat, Aufladungen, Wareneingang, Forderungen und Guthaben, dazu die Schwellen
des § 131b BAO fürs Kalenderjahr. Die Einnahmen-Ausgaben-Rechnung mit Vermögensübersicht
steht unter „Bücher“ (Phase 4), mit Vermögensübersicht und CSV-Export.

**Erscheinungsbild:** Was etwas ändert — anlegen, aufladen, korrigieren, sperren, löschen —
liegt als Dialog über der Seite (`dialog()` in `Html.kt`): ein HTML-Popover ohne Skript,
`popovertarget` öffnet, Escape, das × oder ein Klick daneben schließt, und solange einer offen
ist, ist die Seite dahinter still. Die Symbole sind dieselben Material-Icons wie in der App, als
Pfade in `web/MaterialIcons.kt`, erzeugt von `docs/tools/gen_web_icons.py` aus
`docs/tools/web-icons/` — nicht von Hand ändern. Das Stylesheet ist mit einem Kürzel aus
seinem Inhalt verlinkt (`app.css?v=…`), damit nach einem Update niemand eine Stunde lang das
alte Blatt sieht. Am Telefon rückt ein Status-Abzeichen unter den Namen, statt die Zeile über
den Rand zu schieben. Der Couleurname steht überall, wo der Name steht: Liste, Kopf,
Kontoauszug, PDF.

**Einstellungen der Tablets:** Vereinsname und Vereinsfarbe aus den Einstellungen, dazu
der SumUp-Affiliate-Key und ob die Tablets täglich sichern, gehen an alle gekoppelten
Geräte — als Zeilen der synchronisierten Tabelle `device_settings` (V12, eine je
Schlüssel), die nur der Server schreibt (`VereinSettings.mirror`, unter der Sperre von
`Database.write`). Unverändertes bekommt keine neue Sequenznummer. Die App liest die
Zeilen im Abgleich und legt sie ab, wo sie immer lagen — den Key im Schlüsselbund; am
Gerät sind sie dann nur zu sehen. Ein Tablet, das solche Zeilen schiebt, bekommt 422.

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
| `sync/Entities.kt` | Die vierzehn synchronisierten Tabellen mit Spalten, Vorgabewerten und Konfliktart |
| `sync/SyncStore.kt` | Ziehen, Schieben, Konfliktregeln (Kapitel 4) |
| `sync/Values.kt` | Zahlenformate nach 5.4: Geld als Zeichenkette, Zeit als ISO 8601 |
| `devices/` | Kopplungscodes, Gerätetoken (Argon2id), Sperren |
| `media/ReceiptStore.kt` | Belegfotos als Dateien unter `MEDIA_DIR/receipts` |
| `http/` | Ktor-Routen, Fehlerbilder nach 5.3 |
| `deploy/` | `compose.yaml` (db, api, proxy, updater), `install.sh` (Erstaufstellung), `update.sh` (von Hand), `updater/` (der Updater), `Caddyfile`, `.env.example` |
| `Dockerfile` | Zwei Stufen: Gradle baut :server im Container, das Laufzeit-Image trägt nur das Ergebnis und die Commit-Kennung |
| `web/` | Die Verwaltung: `Accounts.kt` (Benutzer, Sitzungen, Protokoll, Einstellungen), `Reads.kt` (alle Abfragen), `Html.kt` und `Pages*.kt` (Seiten), `Writes.kt`, `Products.kt`, `Purchases.kt`, `Statements.kt`, `Cash.kt`, `Books.kt`, `MemberCsv.kt`, `Updates.kt` (die Fachlogik je Bereich), `resources/web/app.css` |
| `src/main/resources/db/migration/V13__bardienst.sql` | `cash_sessions.cashless`, synchronisiert — der Bardienst ohne Barkasse |
| `src/main/resources/db/migration/V12__geraeteeinstellungen.sql` | `device_settings`, synchronisiert, nur vom Server geschrieben — Vereinsname, Vereinsfarbe, SumUp-Schlüssel, tägliche Sicherung für alle Tablets |
| `src/main/resources/db/migration/V11__posteingang.sql` | Posteingang, freigegebene Absender, gebuchte Zeilen je Beleg |
| `src/main/resources/db/migration/V10__pfand.sql` | Pfandgebinde und Bewegungen, Konto „Pfand und Leergut“ |
| `src/main/resources/db/migration/V9__rechnung_lesen.sql` | `supplier_articles` — was eine Rechnungszeile eines Lieferanten im Lager ist, einmal bestätigt |
| `src/main/resources/db/migration/V8__sperre.sql` | `members.blocked_reason`, synchronisiert |
| `src/main/resources/db/migration/V7__kasse.sql` | `cash_sessions` und `cash_movements`, synchronisiert — die Schichten und Barbewegungen der Tablets |
| `src/main/resources/db/migration/V6__abrechnung.sql` | Profile, Abrechnungsläufe, Abrechnungen mit Nummernkreis, importierte Bankumsätze |
| `src/main/resources/db/migration/V5__einkauf.sql` | Lieferanten, Kontenrahmen, Belegdaten (`purchase_documents`, 1:1 zu `deliveries`), Belegzeilen mit Konto |
| `src/main/resources/db/migration/V4__couleurname.sql` | `members.nickname`, synchronisiert |
| `src/main/resources/db/migration/V3__verwaltung.sql` | Benutzer, Sitzungen, Protokoll, Einstellungen; Sicht `transaction_effects`, auf der `member_balances` jetzt aufsetzt |
| `core/.../sync/Wire.kt`, `SyncClient.kt` | Drahtformat und Client, gemeinsam mit der App; `SyncClientTest` prüft beide gegeneinander |

## Abweichungen von der Spezifikation

- **Lagerabgänge sind eine eigene anfügende Tabelle** (`stock_draws`, Migration V2), und
  `stock_entries` trägt `container_type_id`. Die Spezifikation (2.3) leitet den Verbrauch
  nachträglich aus Buchung mal Rezeptur her; das trägt nicht, weil die Glasgröße der
  Variante in keiner Buchungszeile steht und jede Rezepturänderung die Vergangenheit
  umschriebe. Die App hält beim Verkauf fest, was sie dem Keller entnommen hat. Gezapft je
  Anstich ist die Summe der Abgänge des Artikels im Zeitfenster des Anstichs — so zählen
  auch die Abgänge eines Geräts, dessen doppelter Anstich verworfen wurde.
- **`device_settings` ist eine synchronisierte Tabelle, die nur der Server schreibt** (V12).
  Die Spezifikation kennt keine Einstellungen über den Draht; Vereinsname, Vereinsfarbe,
  SumUp-Schlüssel und die tägliche Sicherung sollen aber an einer Stelle gepflegt werden,
  nicht je Tablet. Ein Schieben darauf beantwortet der Server mit 422.
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
