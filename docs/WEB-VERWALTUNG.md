# Web-Verwaltung für VereinsDeckel — Konzept

Stand 21. September 2026. Ein Vorschlag, was die Web-Oberfläche leisten soll, in welcher
Reihenfolge sie entstehen sollte und was vorher entschieden werden muss. Phase 0 ist
gebaut: der Server (`server/`), das Modul `:core` und Schritt 7 in der App (UUID-Schlüssel,
hergeleiteter Saldo und Bestand, Abgleich). Seit dem 22. September 2026 steht auch **Phase 1**
der Web-Oberfläche, unter `/verwaltung` (siehe `server/README.md`, Abschnitt „Die Verwaltung").
Die Phasen 2 bis 4 sind offen; für sie bleibt dieses Dokument die Grundlage.

**Was feststeht:** Österreich. Der Verein ist eine katholische Studentenverbindung, also
ein Verein nach dem Vereinsgesetz 2002, in aller Regel nicht gemeinnützig im steuerlichen
Sinn. Rechnungslegung als Einnahmen-Ausgaben-Rechnung mit Vermögensübersicht. Der Server
steht bei einem Bundesbruder mit einem kleinen Rechenzentrum. Nach Auskunft des Vereins
(21. September 2026) gelten für ihn keine besonderen zusätzlichen Vorschriften — geplant
wird deshalb **ohne Registrierkasse (RKSV) und ohne E-Bon**; siehe Kapitel 8. Das Dokument
ist auf diese Antworten zugeschnitten; die deutschen Regeln stehen nicht mehr drin.

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
| Server | `server/`: PostgreSQL-Schema nach der Spezifikation, Gerätekopplung, Sync mit Konfliktregeln, Belegfotos, Compose-Aufstellung. Gebaut und getestet; die App spricht ihn noch nicht an. |
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

## 2 · Rahmen: was in Österreich für die Verbindung gilt

Diese Punkte bestimmen den Umfang stärker als jede Feature-Liste. Was davon offen ist,
steht als Frage in Kapitel 8. Der Verein hat am 21. September 2026 geantwortet, dass für
ihn nichts davon greift; das Kapitel bleibt stehen, weil es die Schwellen nennt, an denen
sich das ändern würde. Es ist eine Zusammenstellung, keine Steuerberatung.

### 2.1 Kasse und Belege

- **Registrierkassenpflicht (§ 131b BAO)** trifft einen Betrieb erst, wenn **beide** Grenzen
  überschritten sind: mehr als 15.000 € Jahresumsatz *und* mehr als 7.500 € Barumsatz.
  Als Barumsatz zählen Bargeld, Bankomat- und Kreditkarte (also auch SumUp), Gutscheine —
  **nicht** aber Überweisung und Einziehungsauftrag.
- **Für eine Verbindung ist das die entscheidende Rechenregel.** Was Bundesbrüder auf den
  Deckel schreiben und nach der Abrechnung überweisen, ist kein Barumsatz. Barumsatz sind
  Gäste, die bar oder mit Karte zahlen, und Aufladungen in bar. Ob die Bude unter
  7.500 € bleibt, entscheidet über Registrierkasse ja oder nein. Die Zahlen dafür liefert
  die App (Umsatz nach Zahlart, 4.5); die Einordnung, ob der Budenbetrieb überhaupt ein
  „Betrieb" im Sinn der BAO ist, der Steuerberater.
- **Belegerteilungspflicht (§ 132a BAO):** Unternehmer müssen bei Barzahlung ab dem ersten
  Euro einen Beleg ausstellen, auf Papier oder elektronisch. Ob die Verbindung mit der
  Bude Unternehmer ist, hängt an 2.2. Wenn ja, gehört ein E-Bon (QR-Code am Tablet) oder
  ein Bondrucker für Bar- und Kartenzahlungen an Gäste in die App; für Deckelbuchungen
  ist die Abrechnung der Beleg.
- **Einzelaufzeichnungspflicht (§ 131 BAO):** jede Bareinnahme einzeln. Macht die App.
- **Wenn Registrierkassenpflicht gilt:** Signaturerstellungseinheit (als Cloud-Dienst
  erhältlich), Datenerfassungsprotokoll, QR-Code am Beleg, Registrierung über
  FinanzOnline — und zwar vor der Abrechnung (Phase 3), nicht danach.
- Die Ausnahmen für kleine Vereinskantinen und Vereinsfeste gelten nur begünstigten
  (gemeinnützigen) Vereinen. Für die Verbindung ist damit nicht zu rechnen.

### 2.2 Steuerlicher Status

- **Kleinunternehmer** bis 55.000 € Nettoumsatz im Jahr (seit 2025): keine Umsatzsteuer,
  keine Rechnung mit Steuerausweis. Die „Rechnung an Mitglieder" ist dann das, was sie
  ohnehin sein sollte — ein **Kontoauszug des Deckels mit Zahlungsaufforderung**.
- Wird die Grenze überschritten, kommt Umsatzsteuer auf die Budenumsätze (Getränke 20 %,
  Speisen 10 %) und die Abrechnung braucht Steuerausweis. Dafür bekommt jedes Produkt
  ein Steuersatzfeld, das erst benutzt wird, wenn es so weit ist.
- Echte Mitgliedsbeiträge (Semesterbeiträge) sind nicht steuerbar und gehören in den
  Büchern in einen anderen Bereich als der Budenbetrieb — siehe 4.6.
- Ob auf Gewinne des Budenbetriebs Körperschaftsteuer anfällt, klärt der Steuerberater;
  die Web-Verwaltung liefert ihm die Zahlen getrennt nach Bereich.

### 2.3 Rechnungslegung

- **§ 21 VerG:** Einnahmen-Ausgaben-Rechnung samt Vermögensübersicht binnen fünf Monaten
  nach Ende des Rechnungsjahres. Die **Rechnungsprüfer** prüfen binnen vier Monaten ab
  Erstellung; der Convent beziehungsweise die Generalversammlung entlastet. Eine Bilanz
  wird erst ab 1 Mio. € Einnahmen oder Ausgaben in zwei Folgejahren fällig (§ 22 VerG) —
  für die Verbindung nicht.
- **Aufbewahrung sieben Jahre** (§ 132 BAO) für Kassabuch, Belege und Abrechnungen. Der
  Server muss das halten können, auch wenn Chargen wechseln.
- **E-Rechnung:** keine Pflicht.

### 2.4 Wer die Web-Oberfläche benutzt

| Rolle | Braucht |
|---|---|
| Kassier | Alles zu Geld: Salden, Abrechnungen, Zahlungseingänge, Kassabuch, Belege, Einnahmen-Ausgaben-Rechnung. |
| Senior und Chargen (Vorstand) | Übersicht, Mitglieder, Freigaben; lesen mehr, als sie schreiben. |
| Budenwart | Lager, Bestellvorschlag, Wareneingang, Inventur. |
| Rechnungsprüfer | Lesend, zeitlich begrenzt: Kassabuch, Belege, Abschlüsse. |
| Bundesbrüder (Aktive, Alte Herren) | Später, optional: eigenen Deckel sehen, Auszug laden, per Überweisung aufladen. |
| Gäste | Kein Zugang. Zahlen bar oder mit Karte. |

Chargen wechseln jedes Semester. Rollen und Rechte sind deshalb Grundfunktion, und die
Übergabe eines Kontos an den Nachfolger ein eigener, dokumentierter Vorgang.

### 2.5 Der Server beim Bundesbruder

- **Auftragsverarbeitung.** Steht der Server in einem fremden Rechenzentrum — auch dem
  eines Bundesbruders —, ist dessen Firma Auftragsverarbeiter nach Art. 28 DSGVO. Es
  braucht einen Auftragsverarbeitungsvertrag, benannte Administratoren und die Zusage,
  dass niemand sonst in die Daten schaut.
- **Besondere Kategorie.** Die Mitgliedschaft in einer katholischen Verbindung sagt etwas
  über die religiöse Überzeugung; die Mitgliederliste ist damit Daten nach Art. 9 DSGVO.
  Die Verarbeitung innerhalb der Verbindung ist zulässig (Art. 9 Abs. 2 lit. d), eine
  Weitergabe nach außen nicht. Praktisch: TLS, verschlüsselte Datenträger, Zugriff nur
  für benannte Personen, Protokollierung — und so wenig wie möglich davon auf den
  Tablets (Kapitel 1, Punkt 3).
- **Aufstellung** wie in Kapitel 7 der Spezifikation: PostgreSQL, ein Dienst, Caddy mit
  Let's-Encrypt-Zertifikat unter einem echten Hostnamen. iOS lehnt selbstsignierte
  Zertifikate ab; ein öffentlicher HTTPS-Zugang mit knapper Firewall ist einfacher als
  ein VPN auf jedem Tablet.
- **Sicherung außer Haus.** Ein nächtlicher `pg_dump`, verschlüsselt, an einen zweiten
  Ort, der nicht im selben Rechenzentrum liegt — etwa beim Kassier. Das Rechenzentrum ist
  ein Ort und eine Person; beides kann wegfallen.
- **Bus-Faktor.** Zwei Administratoren, eine geschriebene Betriebsanleitung, ein
  Datenexport, der jederzeit ohne Hilfe des Betreibers geht. Verbindungen leben lange,
  Zuständigkeiten nicht.

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
- **Kategorien und Limits** pflegen — Fuchs, Bursch, Alter Herr, Gast, mit eigenem
  Deckellimit je Kategorie. Heute schon in der App, im Web bequemer.
- **Import und Export** als CSV (vorhanden), zusätzlich der Datenschutz-Export für ein
  einzelnes Mitglied (Auskunft) und das **Löschen nach Austritt**: Das Profil wird
  gelöscht, der Name in den Buchungen bleibt als Schnappschuss, weil die Buchungen der
  Aufbewahrungspflicht unterliegen — nach Ablauf der Frist wird auch er anonymisiert.

Dazu: Tabelle `member_profiles` (nicht synchronisiert), Feld `status` und `blocked_reason`
auf `members` (synchronisiert).

### 4.2 Deckel abrechnen und Rechnungen an Mitglieder

Ein negativer Deckel ist eine Forderung des Vereins, ein positiver eine Verbindlichkeit.
Die Abrechnung — in Verbindungen die Bierrechnung nach jedem Monat oder Semester — macht
daraus einen Vorgang, den man nachvollziehen und mahnen kann. Für Alte Herren, die selten
auf der Bude sind, ist der Versand per E-Mail der Normalfall, nicht die Ausnahme.

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
- **Semesterbeitrag** als eigene Zeile derselben Abrechnung, wenn die Verbindung das
  will: ein Beitrag ist buchhalterisch etwas anderes als ein Bier und bekommt deshalb
  einen eigenen Bereich (4.6), aber der Weg zum Bundesbruder — PDF, E-Mail, QR-Code,
  Zahlungsabgleich — ist derselbe. Frage 5 in Kapitel 8.
- **Später, optional:** SEPA-Lastschrift (Gläubiger-ID, Mandate, pain.008-Export). Das ist
  das Kerngeschäft der Vereinsverwaltungs-Software (easyVerein, ClubDesk, campai, WISO
  MeinVerein), und VereinsDeckel muss es nicht nachbauen — es sei denn, die Verbindung
  hat sonst nichts.

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

### 4.6 Die Einnahmen-Ausgaben-Rechnung

Die Verbindung erstellt eine Einnahmen-Ausgaben-Rechnung mit Vermögensübersicht, und die
Rechnungsprüfer sehen sich Kassabuch und Belege an. Genau das liefert die Web-Verwaltung
— und nicht mehr:

- **Kontenrahmen**, klein, vorbelegt: Getränkeeinkauf, Speiseneinkauf, Budenerlöse,
  Aufladungen, Semesterbeiträge, Spenden, Reinigung, Energie, Miete, Versicherung,
  Veranstaltungen, Sonstiges. Jede Bewegung — Verkauf, Beleg, Entnahme — trägt ein Konto;
  Verkäufe werden über die Produktkategorie automatisch kontiert.
- **Bereiche** statt der deutschen Sphären: *Budenbetrieb* (Getränke, Speisen, Einkauf
  dafür), *Vereinsleben* (Beiträge, Spenden, Verwaltung) und *Veranstaltungen*. So sieht
  der Steuerberater auf einen Blick, was Betrieb ist und was nicht (2.2).
- **Veranstaltungen als Kostenstelle:** Kneipe, Kommers, Stiftungsfest bekommen eine
  Kennung; Verkäufe und Belege lassen sich ihr zuordnen. Dann steht am Ende, was das
  Stiftungsfest gekostet und gebracht hat.
- **Jahresübersicht:** Einnahmen und Ausgaben je Konto und Bereich, Vergleich zum
  Vorjahr, dazu die **Vermögensübersicht** mit Kassabestand, Bankbestand, Lagerwert,
  Forderungen (negative Deckel) und Verbindlichkeiten (positive Deckel, offene
  Lieferantenbelege). Die Deckelsalden sind hier zum ersten Mal das, was sie
  buchhalterisch sind. Das Rechnungsjahr — Kalenderjahr oder Studienjahr — ist
  einstellbar (Frage 3 in Kapitel 8).
- **Mappe für die Rechnungsprüfer:** die Einnahmen-Ausgaben-Rechnung, das Kassabuch, die
  Belegliste mit Dateien, die Abrechnungen — als PDF-Bündel für die Prüfung binnen vier
  Monaten (2.3).
- **Export** für den Steuerberater: CSV mit Konto, Bereich, Betrag, Beleg; das
  BMD-Importformat ist ein Textformat und mit vertretbarem Aufwand erzeugbar.
- **Nicht:** doppelte Buchführung, Bilanz nach UGB, Anlagenverzeichnis mit
  Abschreibungen, Lohn. Wer das braucht, hat einen Steuerberater mit Software; die
  Web-Verwaltung füttert sie.

Dazu: `accounts` (Kontenrahmen mit Bereich), `account_id` auf Bewegungen und
Produktkategorien, `events` (Veranstaltungen) mit optionalem Bezug auf Buchungen und
Belege, das Rechnungsjahr als Einstellung.

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
| **E-Bon am Tablet** (QR-Code oder Bondrucker) | Nötig, sobald die Belegerteilungspflicht gilt (2.1) — dann für jede Bar- und Kartenzahlung an Gäste. Klein, wenn die Buchung ohnehin da ist. |
| **RKSV-Anbindung** (Signatur, Datenerfassungsprotokoll) | Folgt aus 2.1. Wenn ja, vor der Abrechnung, nicht danach. |
| **Dienstplan** (wer steht wann an der Theke) | Sinnvoll, weil Schichten (4.5) ohnehin Personen haben. Klein. |
| **Preislisten mit Gültigkeit** (Fest, Happy Hour) | Klein, und die Veranstaltungs-Kennung aus 4.6 macht es abrechenbar. |
| **Gutscheine, Pfand** | Beides braucht eigene Buchungslogik; erst, wenn der Verein es tatsächlich verkauft. |
| **Spendenbescheinigungen, Newsletter, Vereinswebseite, Lastschrift** | Kerngeschäft der Vereinsverwaltungs-Software. Nicht nachbauen. |
| **Mandantenfähigkeit** (mehrere Vereine auf einem Server) | Nicht, solange es einen Verein gibt. Ein Server pro Verein ist einfacher und datenschutzrechtlich sauberer. |

---

## 6 · Reihenfolge

Jede Phase liefert etwas, das für sich benutzbar ist, und baut auf der vorigen auf.

| Phase | Inhalt | Warum in dieser Reihenfolge |
|---|---|---|
| **0 — Grundlage** | Schritt 7 aus `PORTIERUNG.md` und der Server nach Spezifikation (Kapitel 2 bis 5), `:core`-Modul mit der geteilten Logik. *Stand 21. September 2026: gebaut und mit zwei Geräten gegen den Server durchgespielt. Offen: die Migration auf dem echten Tablet, das Aufstellen des Servers, und die Bestandsherleitung als SQL-Sicht am Server — die App rechnet sie schon, das Web braucht sie in Phase 1.* | Ohne Server keine Web-Oberfläche; ohne abgeleiteten Saldo keine korrekten Zahlen. |
| **1 — Lesen** | Anmeldung, Rollen, Gerätekopplung, Übersicht; Mitglieder mit Salden, Historie, Lager, Belege — alles nur lesend. *Stand 22. September 2026: gebaut, dazu Berichte (Umsatz nach Zahlart je Monat, Aufladungen, Wareneingang, Schwellen), Protokoll, Einstellungen und eine Telefonansicht. Die Bestandsherleitung läuft über dieselbe `Inventory` wie in der App.* | Der Kassier sieht zum ersten Mal alles ohne Tablet. Wenig Risiko, weil nichts geschrieben wird. |
| **2 — Stammdaten** | Mitglieder und Profile pflegen, Sperren, Produkte und Preise, Lieferanten, Eingangsbelege vervollständigen, Dateien. | Schreiben in Stammdaten läuft über den vorhandenen Sync-Konfliktweg. |
| **3 — Geld** | Deckelabrechnung mit PDF, E-Mail und QR; Zahlungseingang und Kontoauszug-Import; Erinnerungen. Schichten, Entnahmen, Kassenbuch (mit den App-Bildschirmen). | Der eigentliche Nutzen. Braucht Profile (2) und den unveränderlichen Buchungsstrom (0). |
| **4 — Bücher** | Kontenrahmen, Bereiche, Veranstaltungen, Jahresübersicht mit Vermögensübersicht, Inventur und Lagerwert, Exporte. | Baut auf allem auf; erst hier zahlt sich die Disziplin der anfügenden Tabellen aus. |
| **5 — Optional** | Mitgliederportal für Bundesbrüder, Dienstplan, Preislisten, SEPA-Lastschrift, RKSV je nach 2.1. | Nach Bedarf. |

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

Land, Rechtsform, Rechnungslegung und Serverstandort sind beantwortet.

**Beantwortet am 21. September 2026** (Fragen 1 und 2, Kasse und Steuer): „Wir sind einfach
nur ein Verein, ohne besondere zusätzliche Vorschriften." Das Konzept plant damit ohne
RKSV, ohne E-Bon und ohne Umsatzsteuerausweis; die Abrechnung an Mitglieder ist ein
Kontoauszug mit Zahlungsaufforderung (2.2). Beides bleibt in Phase 5 geparkt, das
Steuersatzfeld am Produkt bleibt leer. Die Auskunft ist die des Vereins; nachgerechnet hat
sie hier niemand. Die Schwellen aus 2.1 und 2.2 (15.000 € Jahresumsatz *und* 7.500 €
Barumsatz; 55.000 € netto) bleiben der Maßstab, und ab Phase 1 zeigt die Web-Verwaltung
die Jahreszahlen nach Zahlart, an denen sich das mit einem Blick prüfen lässt.

Offen bleibt:

3. **Rechnungsjahr:** Kalenderjahr oder Studienjahr?
4. **Zugang für Bundesbrüder:** Sollen Aktive und Alte Herren ihren Deckel selbst sehen
   und Auszüge laden können (Phase 5), oder bleibt alles beim Kassier?
5. **Semesterbeitrag:** über die Deckelabrechnung mit abrechnen, oder getrennt?
6. **Betrieb:** Wer ist neben dem Bundesbruder mit dem Rechenzentrum der zweite
   Administrator, und wohin geht die Sicherung außer Haus?

Die Frage, die den Umfang um Wochen verändert hätte (1), ist beantwortet; die übrigen
ändern die Reihenfolge, nicht die Größe.

---

## Quellen

- [Sport Austria — Registrierkassenpflicht](https://www.sportaustria.at/de/service-center/recht-und-finanzen/registrierkassenpflicht): Schwellen 15.000 € / 7.500 €, Ausnahmen für Hilfsbetriebe, kleine Vereinskantinen und Vereinsfeste.
- [Linde — Registrierkassenpflicht nach § 131b BAO](https://linda.lindeverlag.at/Dokument/115077/) und [Brandauer Rechtsanwälte — Registrierkassenpflicht 2026](https://brandauer-rechtsanwaelte.at/2026/06/05/registrierkassenpflicht-belegpflicht-unternehmer-oesterreich/): was als Barumsatz zählt (Karte ja, Überweisung nein), beide Grenzen zugleich.
- [JUSLINE — § 132a BAO Belegerteilungspflicht](https://www.jusline.at/gesetz/bao/paragraf/132a), [obono — Registrierkassenpflicht und Belegerteilung](https://obono.at/wissen/fuer-wen-gilt-die-registrierkassenpflicht/).
- [LBG — Erleichterungen für gemeinnützige Vereine und Vereinsfeste](https://www.lbg.at/servicecenter/lbg_steuertipps_praxis/registrierkassenpflicht_erleichterungen_insbesondere_f%C3%BCr_gemeinn%C3%BCtzige_vereine_und_vereinsfeste_izm_ums%C3%A4tzen_im_freien_keine_generelle_anhebung_auf_30_000_jahresumsatz/index_ger.html), [TPA — Vereinsfeste und Registrierkasse](https://www.tpa-group.at/news/vereinsfeste-und-registrierkasse/), [helloCash — Registrierkassenpflicht Vereine](https://hellocash.at/blog/registrierkassenpflicht-vereine/5427).
- [JUSLINE — § 21 VerG](https://www.jusline.at/gesetz/verg/paragraf/21), [§ 22 VerG](https://www.jusline.at/gesetz/verg/paragraf/22), [ICON — Rechnungslegung von Vereinen](https://www.icon.at/news/detail/bilanzierung-rechnungslegung-von-vereinen).
- [IONOS — E-Rechnung Kleinunternehmer Österreich](https://www.ionos.at/digitalguide/e-mail/e-mail-technik/e-rechnung-kleinunternehmer/).
- [heise — Vereinsmanagement-Software im Vergleich](https://www.heise.de/download/specials/Vereinsmanagement-Software-im-Vergleich-9308467), [trusted.de — Vereinsverwaltung](https://trusted.de/vereinsverwaltung), [vereinvereint — Vereinssoftware Vergleich](https://vereinvereint.de/vereinssoftware-vergleich/).
- [Flatpay — Kassensystem für Vereine](https://www.flatpay.com/de/business-type/kassensystem-fur-vereine), [ready2order — Kassensystem Vereine](https://ready2order.com/de/post/kassensystem-vereine/), [tillhub — Kassensystem für Vereine](https://www.tillhub.de/kassensystem-fuer-vereine/).
- [JetBrains — Compose Multiplatform 1.9.0, Compose for Web Beta](https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta/), [Kotlin/Wasm](https://kotlinlang.org/docs/wasm-overview.html).
