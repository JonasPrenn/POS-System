-- Einkauf (docs/WEB-VERWALTUNG.md, 4.4): Lieferanten, Ausgabenkonten, und was die Verwaltung
-- über einen Beleg weiß, das die Theke nicht braucht. Nichts davon wird synchronisiert.
-- Die Lagerpositionen eines Belegs sind gewöhnliche stock_entries mit delivery_id — dieselbe
-- Zeile, die ein Tablet beim Wareneingang schreibt; so kommen sie dort an.

CREATE TABLE suppliers (
  id               UUID PRIMARY KEY,
  name             TEXT           NOT NULL,
  contact          TEXT           NOT NULL DEFAULT '',
  customer_number  TEXT           NOT NULL DEFAULT '',
  created_at       TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX suppliers_name_idx ON suppliers (lower(name));

-- Der kleine Kontenrahmen aus 4.6, vorbelegt. Bereiche statt Sphären: Budenbetrieb,
-- Vereinsleben, Veranstaltungen — so sieht der Steuerberater, was Betrieb ist und was nicht.
CREATE TABLE accounts (
  id      UUID PRIMARY KEY,
  code    TEXT    NOT NULL UNIQUE,
  name    TEXT    NOT NULL,
  area    TEXT    NOT NULL CHECK (area IN ('BUDE', 'VEREIN', 'FEST')),
  kind    TEXT    NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE')),
  sort    INTEGER NOT NULL DEFAULT 0,
  active  BOOLEAN NOT NULL DEFAULT true
);
INSERT INTO accounts (id, code, name, area, kind, sort) VALUES
  ('00000000-0000-0000-0001-000000000001', 'E-BUDE',   'Budenerlöse',              'BUDE',   'INCOME',  10),
  ('00000000-0000-0000-0001-000000000002', 'E-AUFL',   'Aufladungen und Zahlungen', 'BUDE',  'INCOME',  20),
  ('00000000-0000-0000-0001-000000000003', 'E-BEITR',  'Semesterbeiträge',         'VEREIN', 'INCOME',  30),
  ('00000000-0000-0000-0001-000000000004', 'E-SPEND',  'Spenden',                  'VEREIN', 'INCOME',  40),
  ('00000000-0000-0000-0001-000000000005', 'E-FEST',   'Veranstaltungserlöse',     'FEST',   'INCOME',  50),
  ('00000000-0000-0000-0001-000000000011', 'A-GETR',   'Getränkeeinkauf',          'BUDE',   'EXPENSE', 110),
  ('00000000-0000-0000-0001-000000000012', 'A-SPEIS',  'Speiseneinkauf',           'BUDE',   'EXPENSE', 120),
  ('00000000-0000-0000-0001-000000000013', 'A-REIN',   'Reinigung',                'BUDE',   'EXPENSE', 130),
  ('00000000-0000-0000-0001-000000000014', 'A-ENERG',  'Energie',                  'BUDE',   'EXPENSE', 140),
  ('00000000-0000-0000-0001-000000000015', 'A-MIETE',  'Miete',                    'VEREIN', 'EXPENSE', 150),
  ('00000000-0000-0000-0001-000000000016', 'A-VERS',   'Versicherung',             'VEREIN', 'EXPENSE', 160),
  ('00000000-0000-0000-0001-000000000017', 'A-FEST',   'Veranstaltungen',          'FEST',   'EXPENSE', 170),
  ('00000000-0000-0000-0001-000000000018', 'A-SONST',  'Sonstiges',                'VEREIN', 'EXPENSE', 180);

-- Ein Beleg. Hängt an einem Wareneingang (delivery_id), wenn er Lagerpositionen hat; eine
-- Stromrechnung hat keinen und steht für sich.
CREATE TABLE purchase_documents (
  id             UUID PRIMARY KEY,
  delivery_id    UUID           UNIQUE REFERENCES deliveries(id),
  supplier_id    UUID           REFERENCES suppliers(id),
  supplier_name  TEXT           NOT NULL DEFAULT '',   -- Schnappschuss, auch ohne Stammsatz
  number         TEXT           NOT NULL DEFAULT '',
  document_date  DATE           NOT NULL,
  due_date       DATE,
  gross          NUMERIC(12,2),
  vat            NUMERIC(12,2)  NOT NULL DEFAULT 0,    -- enthaltene Umsatzsteuer, nur zur Information
  payment        TEXT           NOT NULL DEFAULT 'OPEN' CHECK (payment IN ('OPEN', 'CASH', 'BANK', 'CARD')),
  paid_at        DATE,
  file_key       TEXT,                                 -- hochgeladene Datei (PDF oder Bild) im ReceiptStore
  note           TEXT           NOT NULL DEFAULT '',
  created_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  created_by     TEXT           NOT NULL DEFAULT ''
);
CREATE INDEX purchase_documents_date_idx ON purchase_documents (document_date DESC);
CREATE INDEX purchase_documents_due_idx ON purchase_documents (due_date) WHERE paid_at IS NULL;
CREATE UNIQUE INDEX purchase_documents_number_idx ON purchase_documents (lower(supplier_name), lower(number)) WHERE number <> '';

-- Belegzeilen ohne Lagerartikel: Fasspfand, Reinigung, Energie — mit Konto.
CREATE TABLE purchase_lines (
  id           UUID PRIMARY KEY,
  document_id  UUID          NOT NULL REFERENCES purchase_documents(id) ON DELETE CASCADE,
  label        TEXT          NOT NULL,
  amount       NUMERIC(12,2) NOT NULL,
  account_id   UUID          NOT NULL REFERENCES accounts(id),
  sort         INTEGER       NOT NULL DEFAULT 0
);
CREATE INDEX purchase_lines_document_idx ON purchase_lines (document_id);
