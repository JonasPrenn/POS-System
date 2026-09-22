-- Abrechnung (docs/WEB-VERWALTUNG.md, 4.1 Profile und 4.2 Deckel abrechnen). Nichts davon geht
-- auf die Tablets: Anschrift und E-Mail eines Bundesbruders braucht die Theke nicht, und der
-- Zahlungseingang kommt bei ihr als gewöhnliche Aufladung (transactions) an.

-- Was die Verwaltung über ein Mitglied weiß, das Tablet aber nicht bekommt (Art. 9 DSGVO: so
-- wenig wie möglich davon auf den Geräten).
CREATE TABLE member_profiles (
  member_id      UUID PRIMARY KEY REFERENCES members(id),
  number         TEXT    NOT NULL DEFAULT '',   -- Mitgliedsnummer der Verbindung, wenn es eine gibt
  email          TEXT    NOT NULL DEFAULT '',
  address        TEXT    NOT NULL DEFAULT '',   -- mehrzeilig, wie sie auf den Auszug gedruckt wird
  consent_email  BOOLEAN NOT NULL DEFAULT false,
  notes          TEXT    NOT NULL DEFAULT '',
  updated_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

-- Ein Abrechnungslauf: „Bierrechnung August 2026" — Zeitraum, Stichtag, Schwelle, Zahlungsziel,
-- und ob eine Zeile wie der Semesterbeitrag mit drauf soll.
CREATE TABLE statement_runs (
  id            UUID PRIMARY KEY,
  label         TEXT           NOT NULL,
  period_from   DATE           NOT NULL,
  period_to     DATE           NOT NULL,
  due_date      DATE           NOT NULL,
  threshold     NUMERIC(12,2)  NOT NULL DEFAULT 0,   -- abgerechnet wird, wer darunter liegt (0: jeder im Minus)
  extra_label   TEXT           NOT NULL DEFAULT '',
  extra_amount  NUMERIC(12,2)  NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  created_by    TEXT           NOT NULL DEFAULT ''
);

-- Die fortlaufende Nummer für den Verwendungszweck: kurz, eindeutig, ohne Verwechslung.
CREATE SEQUENCE statement_seq START 1;

-- Der Kontoauszug mit Zahlungsaufforderung, je Mitglied und Lauf. Die Beträge sind Schnappschüsse
-- zum Stichtag; der Deckel läuft danach weiter, die Abrechnung nicht.
CREATE TABLE statements (
  id              UUID PRIMARY KEY,
  run_id          UUID           NOT NULL REFERENCES statement_runs(id),
  member_id       UUID           NOT NULL REFERENCES members(id),
  member_name     TEXT           NOT NULL,
  number          TEXT           NOT NULL UNIQUE,     -- VD-202608-0017: der Verwendungszweck
  opening         NUMERIC(12,2)  NOT NULL,            -- Stand vor dem Zeitraum
  closing         NUMERIC(12,2)  NOT NULL,            -- Stand am Stichtag
  amount          NUMERIC(12,2)  NOT NULL,            -- was zu zahlen ist: Minus am Stichtag plus Zusatzzeile
  extra_label     TEXT           NOT NULL DEFAULT '',
  extra_amount    NUMERIC(12,2)  NOT NULL DEFAULT 0,
  due_date        DATE           NOT NULL,
  status          TEXT           NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PAID', 'CANCELLED')),
  sent_at         TIMESTAMPTZ(3),
  sent_via        TEXT           CHECK (sent_via IN ('EMAIL', 'PRINT')),
  sent_to         TEXT           NOT NULL DEFAULT '',
  reminded_at     TIMESTAMPTZ(3),
  reminder_level  INTEGER        NOT NULL DEFAULT 0,
  paid_at         DATE,
  payment_id      UUID           REFERENCES transactions(id),  -- die Aufladung, mit der bezahlt wurde
  created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX statements_run_idx ON statements (run_id);
CREATE INDEX statements_member_idx ON statements (member_id, created_at DESC);
CREATE INDEX statements_open_idx ON statements (due_date) WHERE status = 'OPEN';

-- Importierte Bankumsätze (CAMT.053 oder CSV). Der Verwendungszweck ordnet sie einer Abrechnung
-- zu; die Zahlung selbst ist dann eine Aufladung in transactions.
CREATE TABLE bank_transactions (
  id            UUID PRIMARY KEY,
  fingerprint   TEXT           NOT NULL UNIQUE,      -- Datum, Betrag, Text, Gegenseite: so wird ein Auszug nicht zweimal eingelesen
  booking_date  DATE           NOT NULL,
  amount        NUMERIC(12,2)  NOT NULL,
  counterparty  TEXT           NOT NULL DEFAULT '',
  reference     TEXT           NOT NULL DEFAULT '',
  status        TEXT           NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'MATCHED', 'IGNORED')),
  statement_id  UUID           REFERENCES statements(id),
  payment_id    UUID           REFERENCES transactions(id),
  imported_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  imported_by   TEXT           NOT NULL DEFAULT ''
);
CREATE INDEX bank_transactions_open_idx ON bank_transactions (booking_date DESC) WHERE status = 'OPEN';
