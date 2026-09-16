# Web-Verwaltung für VereinsDeckel — Konzept

Stand 16. September 2026. Ein Vorschlag, was die Web-Oberfläche leisten soll, in welcher
Reihenfolge sie entstehen sollte und was vorher entschieden werden muss. Nichts davon ist
gebaut; das Dokument ist die Grundlage für die Entscheidung, nicht ihr Ergebnis.

Kurzfassung: Die Theke bleibt das Tablet. Das Web ist der Schreibtisch des Kassiers und
des Vorstands — Mitglieder pflegen, Deckel abrechnen, Einkauf und Lager führen, die
Kasse abschließen, und am Jahresende das liefern, was Rechnungsprüfer und Steuerberater
brauchen. Eine eigene doppelte Buchführung mit Bilanz ist bewusst nicht dabei; die
Web-Verwaltung liefert saubere, exportierbare Bücher, keine zweite Buchhaltungssoftware.

---

## 1 · Ausgangslage

Was heute existiert und worauf die Web-Verwaltung aufsetzt:

| Bestandteil | Stand |
|---|---|
| App (Android, iOS) | Verkauf, Deckel, Lager mit Rezepturen und Gebinden, Wareneingang mit Belegfoto, Historie, Auswertung, Sicherung. Läuft lokal, Schema-Version 10. |
| Server- und API-Spezifikation | `docs/VereinsDeckel-Server-und-API.pdf`: PostgreSQL, REST, Offline-First-Sync, Gerätekopplung, Objektspeicher für Belegfotos. Nicht gebaut. |
| Datenmodell | Mitglieder haben Name, Kategorie, Saldo und letzte Nutzung — **keine Kontaktdaten**. Preise sind Bruttobeträge, **keine Umsatzsteuer** im Modell. Ein Wareneingang (`deliveries`) ist Lieferant als Freitext, Bonsumme, Foto, Positionen. |

Drei Folgerungen daraus:

1. **Die Web-Verwaltung ist ein Teil des Servers**, nicht eine dritte App. Der Server aus
   der Spezifikation hält die gemeinsame Wahrheit; die Web-Oberfläche liest und schreibt
   dieselben Tabellen und erreicht die Tablets über den vorhandenen Sync.
2. **Schritt 7 (UUID-Schlüssel, abgeleiteter Saldo, Sync) ist die Voraussetzung.** Ohne
   ihn gibt es keinen Server, ohne Server keine Web-Oberfläche. Alles hier Beschriebene
   kommt danach.
3. **Was an Daten dazukommt, gehört überwiegend nur auf den Server.** Adressen,
   E-Mail-Adressen, Bankdaten, Rechnungen: nichts davon braucht die Theke. Sie werden
   nicht auf die Tablets synchronisiert — Datensparsamkeit, und weniger Angriffsfläche auf
   einem Gerät, das im Biergarten herumliegt.

---

## 2 · Rahmen, der vorher entschieden werden muss

Diese Punkte bestimmen den Umfang stärker als jede Feature-Liste. Sie sind Fragen an den
Verein, nicht an den Code.

### 2.1 Österreich oder Deutschland

Der Code sagt „Kassabon", also vermutlich Österreich. Die Regeln unterscheiden sich in
genau den Bereichen, um die es hier geht:

| | Österreich | Deutschland |
|---|---|---|
| Elektronische Kasse | Registrierkassenpflicht (RKSV) ab 15.000 € Jahresumsatz und 7.500 € Barumsatz: Signatureinrichtung, Datenerfassungsprotokoll, QR-Code am Beleg, Meldung über FinanzOnline. | KassenSichV: jedes elektronische Kassensystem braucht eine zertifizierte TSE, Belegausgabepflicht, Meldung des Systems beim Finanzamt (§ 146a AO). Die Pflicht hängt am System, nicht an der Rechtsform. |
| Ausnahmen für Vereine | Unentbehrliche Hilfsbetriebe ganz befreit; kleine Vereinskantinen bis 52 Öffnungstage im Jahr und einer Umsatzgrenze (BMF nennt 30.000 €, Sport Austria 45.000 €); kleine Vereinsfeste bis 72 Stunden im Jahr. | Keine vereinsspezifische Ausnahme. Nur die offene Ladenkasse (Geldkassette ohne Elektronik) ist frei von TSE und Belegpflicht, verlangt aber täglichen Kassenbericht und Kassensturzfähigkeit. |
| Rechnungslegung | § 21 VerG: Einnahmen-Ausgaben-Rechnung samt Vermögensübersicht binnen fünf Monaten nach Jahresende. Bilanz erst ab 1 Mio. € Einnahmen oder Ausgaben in zwei Folgejahren (§ 22 VerG). | Vier Sphären (ideell, Vermögensverwaltung, Zweckbetrieb, wirtschaftlicher Geschäftsbetrieb); die Vereinsgaststätte ist wirtschaftlicher Geschäftsbetrieb. Einnahmen-Überschuss-Rechnung; Körperschaftsteuer erst über 45.000 € Einnahmen im wGB. |
| Aufbewahrung | 7 Jahre (§ 132 BAO). | 10 Jahre, Buchungsbelege 8 Jahre (§ 147 AO). |
| E-Rechnung | Keine Pflicht für Kleinunternehmer. | Nur B2B; Rechnungen an private Mitglieder sind nicht betroffen. Kleinunternehmer müssen nur empfangen können. |

**Die unbequeme Konsequenz:** VereinsDeckel *ist* eine elektronische Kasse. Ein
Vereinsheim, das jedes Wochenende offen hat, liegt über 52 Öffnungstagen; wenn dazu die
Umsatzgrenzen überschritten sind, ist die Kasse in Österreich registrierkassenpflichtig
und in Deutschland TSE-pflichtig. Je mehr Buchhaltung die App übernimmt, desto weniger
lässt sich später argumentieren, sie sei nur eine Anschreibhilfe. Das ist mit dem
Steuerberater des Vereins zu klären, **bevor** die Kassenfunktionen ausgebaut werden —
und wenn die Pflicht gilt, gehört die Anbindung einer Signatur- bzw. TSE-Lösung (in
beiden Ländern als Cloud-Dienst verfügbar) auf die Liste, und zwar vor der Abrechnung.

### 2.2 Steuerlicher Status des Vereins

Gemeinnützig oder nicht, Kleinunternehmer oder umsatzsteuerpflichtig (Grenze 2025:
55.000 € in Österreich, 25.000 € Vorjahr in Deutschland). Davon hängt ab, ob eine
„Rechnung an Mitglieder" eine Rechnung im Sinne des Umsatzsteuergesetzes mit
Steuerausweis sein muss oder — der Normalfall — ein **Kontoauszug des Deckels mit
Zahlungsaufforderung**. Das Datenmodell muss beides können (Steuersatz je Produkt,
optional), die Oberfläche zeigt nur, was der Verein braucht.

### 2.3 Wer die Web-Oberfläche benutzt

| Rolle | Braucht |
|---|---|
| Kassier | Alles zu Geld: Salden, Abrechnungen, Zahlungseingänge, Kassenbuch, Belege, Jahresabschluss. |
| Vorstand | Übersicht, Mitglieder, Freigaben; liest mehr, als er schreibt. |
| Getränkewart | Lager, Bestellvorschlag, Wareneingang, Inventur. |
| Rechnungsprüfer | Lesend, zeitlich begrenzt: Kassenbuch, Belege, Abschlüsse. |
| Mitglied | Später, optional: eigenen Deckel sehen, Auszug laden, per Überweisung aufladen. |

Rollen und Rechte sind damit keine Zusatzfunktion, sondern Grundfunktion der ersten
Version.

### 2.4 Wo der Server steht

Ein Raspberry Pi im Vereinsheim oder ein gemieteter Server. Der Unterschied ist nicht
technisch: Mit Kontaktdaten und Kontoständen auf einem fremden Server braucht der Verein
einen Auftragsverarbeitungsvertrag, ein Verzeichnis der Verarbeitungstätigkeiten hat er
ohnehin (siehe Spezifikation, 7.3). E-Mail-Versand an Mitglieder braucht deren
Einwilligung, die pro Mitglied gespeichert wird.

---

## 3 · Architekturentscheidung

**Die Web-Oberfläche ist Teil des API-Dienstes, in Kotlin, server-gerendertes HTML.**

| Entscheidung | Begründung |
|---|---|
| Kotlin auf dem Server (Ktor) | Die Spezifikation lässt die Sprache offen, verlangt aber, dass die Bestandslogik aus `data/stock/Inventory.kt` auf dem Server *bitweise gleich* rechnet. Das geht nur mit derselben Sprache: `Inventory`, `Money`, `Ids` und `VdDate` wandern in ein reines Kotlin-Modul `:core` ohne Compose und Room, das `:shared` und der Server gemeinsam benutzen. |
| HTML vom Server, ergänzt um htmx für Teilaktualisierungen | Die Verwaltung besteht aus Tabellen, Formularen, Drucksachen und PDFs. Dafür ist gewöhnliches HTML das beste Werkzeug: Tastaturbedienung, Druck-CSS, Screenreader, Browser-Suche funktionieren von selbst. Kein zweites Ökosystem mit eigener Build-Kette. |
| Nicht Compose Multiplatform für Web | Seit 1.9 in Beta, zeichnet auf Canvas. Für Text, Druck und Barrierefreiheit die falsche Technik; die geteilte Oberfläche der App ist ohnehin für Daumen gebaut, nicht für Tabellen mit 300 Zeilen. |
| Ein Deployment | Die Compose-Datei aus Kapitel 7 der Spezifikation bleibt: PostgreSQL, ein Dienst, Caddy. Die Web-Oberfläche ist derselbe Dienst unter `/verwaltung`. |
| Bausteine | PDF aus HTML (openhtmltopdf), QR-Codes (zxing), E-Mail über den SMTP-Zugang des Vereins (Jakarta Mail), Migrationen (Flyway), Passwörter mit Argon2id wie die Gerätetoken. |
| Gestaltung | Dieselben Farbregeln wie die App: Grün ist Geld, Rot ist Zerstörung und sonst nichts, Vereinsfarbe nur in Kopfzeile und Navigation. Beträge mit Tabellenziffern. Die Token aus `ui/theme` werden einmal als CSS-Variablen nachgezogen. |

---

## 4 · Die Funktionsbereiche

Je Bereich: was er leistet, was an Daten dazukommt, was die App dafür braucht.

### 4.1 Mitglieder

Heute: Name, Kategorie, Saldo. Für Abrechnung und Verwaltung fehlt der Rest.

- **Profil (nur Server):** E-Mail, Anschrift, Telefon, Mitgliedsnummer, Eintritt,
  Austritt, Status (aktiv, ruhend, ausgetreten), Einwilligung E-Mail, Notizen. Das
  Tablet bekommt davon nichts.
- **Deckelkonto:** Kontoauszug aus den Buchungen, Aufladungen und Korrekturen mit Grund
  (gibt es schon), Guthaben und Außenstände auf einen Blick.
- **Sperre:** Deckel gesperrt, mit Grund. Wird auf die Tablets synchronisiert; die Theke
  zeigt es, bevor angeschrieben wird.
- **Kategorien und Limits** pflegen — heute schon in der App, im Web bequemer.
- **Import und Export** als CSV (vorhanden), zusätzlich der Datenschutz-Export für ein
  einzelnes Mitglied (Auskunft) und das **Löschen nach Austritt**: Das Profil wird
  gelöscht, der Name in den Buchungen bleibt als Schnappschuss, weil die Buchungen der
  Aufbewahrungspflicht unterliegen — nach Ablauf der Frist wird auch er anonymisiert.

Dazu: Tabelle `member_profiles` (nicht synchronisiert), Feld `status` und `blocked_reason`
auf `members` (synchronisiert).

### 4.2 Deckel abrechnen und Rechnungen an Mitglieder

Ein negativer Deckel ist eine Forderung des Vereins, ein positiver eine Verbindlichkeit.
Die Abrechnung macht daraus einen Vorgang, den man nachvollziehen und mahnen kann.

- **Abrechnungslauf** zum Stichtag: alle Mitglieder mit Saldo unter einer Schwelle, oder
  alle, oder eine Auswahl. Je Mitglied ein PDF: Positionen seit der letzten Abrechnung,
  Aufladungen, Saldo, Zahlungsziel, Bankverbindung des Vereins, **EPC-QR-Code** zum
  Bezahlen mit der Banking-App („Zahlen mit Code", Girocode) mit eindeutigem
  Verwendungszweck.
- **Versand** per E-Mail an Mitglieder mit Einwilligung, sonst Druck. Was versandt wurde,
  wann, an welche Adresse, bleibt stehen.
- **Zahlungseingang:** von Hand, oder über einen **Kontoauszug-Import** (CAMT.053-XML
  oder CSV der Bank; FinTS/HBCI gibt es nur in Deutschland). Der Verwendungszweck ordnet
  die Zahlung der Abrechnung zu; die Zahlung wird als Aufladung mit Zahlungsart
  `TOPUP_BANK` gebucht und gleicht den Deckel aus — dieselbe Buchungslogik wie bisher,
  kein zweiter Kontostand.
- **Erinnerung** nach Frist, mit Stufe und Datum. Keine Mahngebühren-Automatik; das
  entscheidet ein Mensch.
- **Was eine „Rechnung" ist:** Normalerweise ein Kontoauszug mit Zahlungsaufforderung.
  Nur wenn der Verein umsatzsteuerpflichtig ist, wird daraus eine Rechnung mit
  Steuerausweis; dafür braucht jedes Produkt einen Steuersatz. Das Feld wird angelegt,
  aber erst benutzt, wenn 2.2 es verlangt.
- **Später, optional:** SEPA-Lastschrift (Gläubiger-ID, Mandate, pain.008-Export) und
  Mitgliedsbeiträge über dieselbe Maschinerie. Das ist das Kerngeschäft der
  Vereinsverwaltungs-Software (easyVerein, ClubDesk, campai, WISO MeinVerein), und
  VereinsDeckel muss es nicht nachbauen — es sei denn, der Verein hat sonst nichts.

Dazu: `statements` (Mitglied, Zeitraum, Saldo, Betrag, Fälligkeit, Status, Versand),
`payments` und `bank_transactions` (Import mit Zuordnung), `members.number`,
`products.vat_rate` (optional). Die App braucht nichts Neues; die Aufladung kommt über
den Sync an.

### 4.3 Lager

Die App führt heute Artikel, Gebinde, Rezepturen, Anstiche und gelernte Erträge. Das Web
ergänzt den Blick von oben:

- **Übersicht:** abgeleiteter Bestand je Artikel, Mindestbestände, Warnungen, offene
  Fässer, Lagerwert zu Einstandspreisen (gleitender Durchschnitt aus den Wareneingängen).
- **Inventur:** Zählung als Ereignis mit Datum und Zähler. Die Differenz zum Sollbestand
  wird als Korrekturbuchung (`CORRECTION`) mit Grund gebucht — die anfügende Tabelle
  bleibt anfügend. Der **Schwundbericht** zeigt je Artikel Soll, Ist, Differenz, Wert.
- **Bestellvorschlag:** alles unter Mindestbestand, gruppiert nach Lieferant, als Liste
  zum Drucken oder Mailen. Keine Bestellung an den Lieferanten aus der App heraus.
- **Fasserträge:** was die App lernt, hier lesbar — welche Gebindegröße wie viel liefert,
  Verderb je Fass.

Dazu: `stock_counts` und `stock_count_lines`, `suppliers` (Name, Kontakt, Kundennummer).

### 4.4 Einkauf: Eingangsrechnungen einpflegen

Der Wareneingang der App (Lieferant, Bonsumme, Foto, Positionen) ist bereits ein
Eingangsbeleg. Im Web wird er vollständig:

- **Beleg:** Lieferant aus dem Stamm, Belegnummer, Belegdatum, Fälligkeit, Brutto,
  Umsatzsteuer nach Sätzen, Zahlungsart (bar aus der Kasse, Bank, Karte), bezahlt am.
  Das Foto der App oder ein hochgeladenes PDF hängt daran (Objektspeicher aus 5.5 der
  Spezifikation). Prüfung auf Dubletten über Lieferant und Belegnummer.
- **Zuordnung:** Positionen an Lagerartikel (das ist der Wareneingang), der Rest an eine
  Ausgabenkategorie (Reinigung, Energie, GEMA/AKM, Sonstiges). Die Kategorie ist das
  Konto aus 4.6.
- **Offene Posten:** unbezahlte Belege mit Fälligkeit, damit Skonto nicht verfällt.
- **Bareinkäufe** senken die Kasse (4.5); Banküberweisungen tauchen im Kontoauszug-Import
  wieder auf und werden abgeglichen.
- **Nicht in der ersten Fassung:** Texterkennung des Belegfotos. Ein Kassabon vom
  Großmarkt hat zwanzig Zeilen, die niemand alle als Lagerartikel führt; die zwei
  Zahlen, auf die es ankommt, tippt man schneller ab.

Dazu: `deliveries` wird zu `purchase_documents` erweitert (Nummer, Datum, Fälligkeit,
Steuer, Zahlung, Datei), `purchase_lines` mit optionalem Bezug auf `stock_entries`.

### 4.5 Kasse: Schichten, Abschlüsse, Kassenbuch

Das ist der Teil, den GoBD beziehungsweise BAO tatsächlich verlangen, und den heute
niemand hat.

- **Schicht öffnen und schließen** am Tablet: Wechselgeld zählen, am Ende den Bestand
  zählen. Das System weiß, was drin sein müsste (Anfangsbestand + Bareinnahmen −
  Barausgaben) und protokolliert die Differenz mit Namen des Schließenden. Keine
  Korrektur ohne Grund.
- **Entnahme und Einlage** mit Grund (Bank, Bareinkauf, Wechselgeld), ebenfalls am Tablet.
- **Kassenbuch** im Web: chronologisch, alle Barbewegungen, jederzeit kassensturzfähig,
  nachträglich nicht änderbar — nur stornierbar mit Gegenbuchung. Export als PDF und CSV
  für den Rechnungsprüfer.
- **Tagesbericht:** Umsatz nach Zahlart (Bar, Karte, Deckel), Aufladungen, Stornos,
  Differenzen, je Gerät und gesamt.
- **Bankbuch:** die importierten Kontoauszüge, zugeordnet zu Abrechnungen, Belegen und
  Einlagen.

Dazu: `cash_sessions`, `cash_movements`. **Das ist der einzige Bereich, der auch die App
verändert** (Schicht, Entnahme, Zählung als Bildschirme im Verkaufsweg).

### 4.6 „Bilanzierung": was gemeint sein sollte

Ein Verein dieser Größe erstellt keine Bilanz. Er erstellt eine
Einnahmen-Ausgaben-Rechnung mit Vermögensübersicht (Österreich) beziehungsweise eine
Einnahmen-Überschuss-Rechnung nach Sphären (Deutschland), und ein Rechnungsprüfer sieht
sich Kassenbuch und Belege an. Genau das liefert die Web-Verwaltung — und nicht mehr:

- **Kontenrahmen für Vereine**, klein, vorbelegt (Getränkeeinkauf, Speiseneinkauf,
  Kantinenerlöse, Aufladungen, Reinigung, Energie, Miete, Versicherung, Sonstiges), mit
  Zuordnung zur **Sphäre** beziehungsweise zum Betrieb (Deutschland: die vier Sphären;
  Österreich: unentbehrlicher Hilfsbetrieb, entbehrlicher Hilfsbetrieb,
  begünstigungsschädlicher Betrieb). Jede Bewegung — Verkauf, Beleg, Entnahme — trägt ein
  Konto; Verkäufe werden über die Produktkategorie automatisch kontiert.
- **Jahresübersicht:** Einnahmen und Ausgaben je Konto und Sphäre, Vergleich zum Vorjahr,
  Umsatzsteuer-Übersicht falls steuerpflichtig, **Vermögensübersicht** mit
  Kassenbestand, Bankbestand, Lagerwert, Forderungen (negative Deckel) und
  Verbindlichkeiten (positive Deckel, offene Lieferantenbelege). Die Deckelsalden sind
  hier zum ersten Mal das, was sie buchhalterisch sind.
- **Veranstaltungen als Kostenstelle:** ein Fest bekommt eine Kennung, Verkäufe und Belege
  lassen sich ihm zuordnen. In Österreich ist das die Voraussetzung, die 72-Stunden-Regel
  überhaupt belegen zu können; in Deutschland trennt es Zweckbetrieb von
  Geschäftsbetrieb.
- **Export** für den Steuerberater: CSV mit Konto, Sphäre, Betrag, Beleg; die Formate,
  die die Branchenprogramme lesen (DATEV-Buchungsstapel in Deutschland, BMD in
  Österreich), sind Textformate und lassen sich mit vertretbarem Aufwand erzeugen.
- **Nicht:** doppelte Buchführung, Bilanz nach UGB oder HGB, Anlagenverzeichnis mit
  Abschreibungen, Lohn. Wer das braucht, hat einen Steuerberater mit Software; die
  Web-Verwaltung füttert sie.

Dazu: `accounts` (Kontenrahmen mit Sphäre), `account_id` auf Bewegungen und
Produktkategorien, `events` (Veranstaltungen) mit optionalem Bezug auf Buchungen und
Belege.

### 4.7 Querschnitt

- **Benutzer, Rollen, Sitzungen.** Passwort mit Argon2id, für Vorstand und Kassier
  optional ein zweiter Faktor (TOTP). Gerätekopplung (Spezifikation 5.2) wird hier
  ausgelöst, Geräte werden hier gesperrt.
- **Änderungsprotokoll.** Wer hat wann welchen Stammdatensatz, welche Abrechnung, welche
  Korrektur angelegt oder geändert. Für den Rechnungsprüfer, und für den Abend, an dem
  ein Preis „von allein" anders war.
- **Übersichtsseite:** Umsatz heute und diese Woche nach Zahlart, Außenstände gesamt,
  Lagerwarnungen, letzte Sicherung, letzter Abgleich je Gerät, offene Belege.
- **Sicherung** des Servers ist Betriebsthema (Spezifikation 7.2), aber die Oberfläche
  zeigt, wann sie zuletzt gelaufen ist.

---

## 5 · Was außerdem in Frage kommt

Kandidaten aus dem Vergleich mit Vereins- und Kassensoftware, mit Einschätzung:

| Kandidat | Einschätzung |
|---|---|
| **Mitgliederportal** (eigenen Deckel sehen, Auszug laden, Aufladen per QR-Überweisung) | Hoher Nutzen, geringes Risiko, sobald Abrechnung und Zahlungsimport stehen. Verringert die Rückfragen beim Kassier. Phase 5. |
| **Belegdruck / E-Bon** am Tablet | Nur nötig, wenn Belegpflicht gilt (2.1). Dann Pflicht, sonst Ballast. |
| **RKSV- bzw. TSE-Anbindung** | Folgt aus 2.1. Wenn ja, vor der Abrechnung, nicht danach. |
| **Dienstplan** (wer steht wann an der Theke) | Sinnvoll, weil Schichten (4.5) ohnehin Personen haben. Klein. |
| **Preislisten mit Gültigkeit** (Fest, Happy Hour) | Klein, und die Veranstaltungs-Kennung aus 4.6 macht es abrechenbar. |
| **Gutscheine, Pfand** | Beides braucht eigene Buchungslogik; erst, wenn der Verein es tatsächlich verkauft. |
| **Spendenbescheinigungen, Newsletter, Vereinswebseite, Beitragsverwaltung** | Kerngeschäft der Vereinsverwaltungs-Software. Nicht nachbauen. |
| **Mandantenfähigkeit** (mehrere Vereine auf einem Server) | Nicht, solange es einen Verein gibt. Ein Server pro Verein ist einfacher und datenschutzrechtlich sauberer. |

---

## 6 · Reihenfolge

Jede Phase liefert etwas, das für sich benutzbar ist, und baut auf der vorigen auf.

| Phase | Inhalt | Warum in dieser Reihenfolge |
|---|---|---|
| **0 — Grundlage** | Schritt 7 aus `PORTIERUNG.md` und der Server nach Spezifikation (Kapitel 2 bis 5), `:core`-Modul mit der geteilten Logik. | Ohne Server keine Web-Oberfläche; ohne abgeleiteten Saldo keine korrekten Zahlen. |
| **1 — Lesen** | Anmeldung, Rollen, Gerätekopplung, Übersicht; Mitglieder mit Salden, Historie, Lager, Belege — alles nur lesend. | Der Kassier sieht zum ersten Mal alles ohne Tablet. Wenig Risiko, weil nichts geschrieben wird. |
| **2 — Stammdaten** | Mitglieder und Profile pflegen, Sperren, Produkte und Preise, Lieferanten, Eingangsbelege vervollständigen, Dateien. | Schreiben in Stammdaten läuft über den vorhandenen Sync-Konfliktweg. |
| **3 — Geld** | Deckelabrechnung mit PDF, E-Mail und QR; Zahlungseingang und Kontoauszug-Import; Erinnerungen. Schichten, Entnahmen, Kassenbuch (mit den App-Bildschirmen). | Der eigentliche Nutzen. Braucht Profile (2) und den unveränderlichen Buchungsstrom (0). |
| **4 — Bücher** | Kontenrahmen, Sphären, Veranstaltungen, Jahresübersicht mit Vermögensübersicht, Inventur und Lagerwert, Exporte. | Baut auf allem auf; erst hier zahlt sich die Disziplin der anfügenden Tabellen aus. |
| **5 — Optional** | Mitgliederportal, Dienstplan, Preislisten, SEPA-Lastschrift, Signatur/TSE je nach 2.1. | Nach Bedarf. |

Größenordnung: Phase 0 ist die größte einzelne Arbeit (Migration beider Plattformen plus
Server); die Phasen 1 und 2 sind Fleißarbeit mit wenig Entwurfsrisiko; Phase 3 hat mit
PDF, E-Mail, Bankimport und den App-Bildschirmen die meisten beweglichen Teile; Phase 4
ist fachlich anspruchsvoll, technisch klein.

---

## 7 · Datenmodell in einem Bild

| Tabelle | Neu / geändert | Auf die Tablets |
|---|---|---|
| `members` | + `number`, `status`, `blocked_reason` | ja |
| `member_profiles` | neu: Kontakt, Ein- und Austritt, Einwilligungen | **nein** |
| `products` | + `vat_rate` (optional), + `account_id` über Kategorie | ja |
| `suppliers` | neu | nein |
| `purchase_documents`, `purchase_lines` | aus `deliveries` erweitert: Nummer, Datum, Fälligkeit, Steuer, Zahlung, Datei | Kopf ja (Wareneingang), Rest nein |
| `stock_counts`, `stock_count_lines` | neu; erzeugen `stock_entries` mit `CORRECTION` | Korrekturen ja |
| `cash_sessions`, `cash_movements` | neu | ja (entstehen am Tablet) |
| `statements`, `payments`, `bank_transactions` | neu | nein (die Aufladung kommt als `transaction`) |
| `accounts`, `events` | neu | `events` ja (Kennung am Verkauf), `accounts` nein |
| `users`, `roles`, `sessions`, `audit_log` | neu | nein |

Die Regel dahinter: **Was die Theke zum Verkaufen braucht, wird synchronisiert. Alles
andere bleibt auf dem Server.**

---

## 8 · Offene Fragen an den Verein

1. Österreich oder Deutschland — und wie steht der Verein zur Registrierkassen- bzw.
   TSE-Pflicht? (Öffnungstage, Umsatz, Barumsatz.)
2. Gemeinnützig? Kleinunternehmer? Umsatzsteuerpflichtig?
3. Wer soll die Oberfläche benutzen — nur Kassier und Vorstand, oder auch Mitglieder?
4. Server im Vereinsheim oder gemietet?
5. Soll die Beitragsverwaltung (Mitgliedsbeiträge, SEPA) mit hinein, oder bleibt die
   bei der bestehenden Vereinssoftware?
6. Was heißt „Bilanzierung" für den Kassier konkret — die Jahresübersicht mit
   Vermögensübersicht aus 4.6, oder verlangt jemand tatsächlich eine Bilanz?

Die Antworten auf 1 und 2 ändern den Umfang um Wochen; die anderen ändern die
Reihenfolge.

---

## Quellen

- [Sport Austria — Registrierkassenpflicht](https://www.sportaustria.at/de/service-center/recht-und-finanzen/registrierkassenpflicht): Schwellen 15.000 € / 7.500 €, Ausnahmen für Hilfsbetriebe, kleine Vereinskantinen und Vereinsfeste.
- [LBG — Erleichterungen für gemeinnützige Vereine und Vereinsfeste](https://www.lbg.at/servicecenter/lbg_steuertipps_praxis/registrierkassenpflicht_erleichterungen_insbesondere_f%C3%BCr_gemeinn%C3%BCtzige_vereine_und_vereinsfeste_izm_ums%C3%A4tzen_im_freien_keine_generelle_anhebung_auf_30_000_jahresumsatz/index_ger.html), [TPA — Vereinsfeste und Registrierkasse](https://www.tpa-group.at/news/vereinsfeste-und-registrierkasse/), [helloCash — Registrierkassenpflicht Vereine](https://hellocash.at/blog/registrierkassenpflicht-vereine/5427).
- [ebing — TSE-Pflicht im Verein](https://ebing.io/blog/tse-pflicht-verein), [Vereinswelt — Kassenbonpflicht für Vereine](https://www.vereinswelt.de/kassenbon-pflicht-fuer-vereine-ja-oder-nein), [Wikipedia — Kassensicherungsverordnung](https://de.wikipedia.org/wiki/Kassensicherungsverordnung).
- [JUSLINE — § 21 VerG](https://www.jusline.at/gesetz/verg/paragraf/21), [§ 22 VerG](https://www.jusline.at/gesetz/verg/paragraf/22), [ICON — Rechnungslegung von Vereinen](https://www.icon.at/news/detail/bilanzierung-rechnungslegung-von-vereinen).
- [WISO MeinVerein — Steuerbereiche im Verein](https://www.meinverein.de/blog/vereinsbuchhaltung-finanzierung/steuerbereiche/), [Vereinswelt — EÜR im Verein](https://www.vereinswelt.de/finanzen/steuern/einnahmen-ueberschuss-rechnung/), [Vereinswelt — Bilanz im Verein](https://www.vereinswelt.de/finanzen/kassenwart/bilanz-im-verein/), [WINHELLER — Buchführungspflicht gemeinnütziger Vereine](https://winheller.com/blog/buchfuehrungspflicht-gemeinnuetzige-vereine/).
- [kostenlose-erechnung.de — E-Rechnung für Vereine](https://kostenlose-erechnung.de/ratgeber/e-rechnung-vereine/), [BMF — FAQ E-Rechnung](https://www.bundesfinanzministerium.de/Content/DE/FAQ/e-rechnung.html), [IONOS — E-Rechnung Kleinunternehmer Österreich](https://www.ionos.at/digitalguide/e-mail/e-mail-technik/e-rechnung-kleinunternehmer/).
- [heise — Vereinsmanagement-Software im Vergleich](https://www.heise.de/download/specials/Vereinsmanagement-Software-im-Vergleich-9308467), [trusted.de — Vereinsverwaltung](https://trusted.de/vereinsverwaltung), [vereinvereint — Vereinssoftware Vergleich](https://vereinvereint.de/vereinssoftware-vergleich/).
- [Flatpay — Kassensystem für Vereine](https://www.flatpay.com/de/business-type/kassensystem-fur-vereine), [ready2order — Kassensystem Vereine](https://ready2order.com/de/post/kassensystem-vereine/), [tillhub — Kassensystem für Vereine](https://www.tillhub.de/kassensystem-fuer-vereine/).
- [JetBrains — Compose Multiplatform 1.9.0, Compose for Web Beta](https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta/), [Kotlin/Wasm](https://kotlinlang.org/docs/wasm-overview.html).
