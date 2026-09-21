-- Grundgerüst nach docs/VereinsDeckel-Server-und-API.pdf, Kapitel 3.
--
-- Zwei Abweichungen vom Wortlaut der Spezifikation, beide bewusst:
--   * Zeitstempel sind TIMESTAMPTZ(3), also millisekundengenau. Die App führt
--     Epoch-Millisekunden, und die Konfliktprüfung über base_updated_at vergleicht nur
--     dann exakt, wenn Server und Client dieselbe Auflösung haben.
--   * applied_changes trägt zusätzlich seq, damit ein Wiederholungsversuch dieselbe
--     Antwort bekommt wie der erste Versuch.

CREATE SEQUENCE sync_seq;

-- Ein Zähler für alle Tabellen: der Client braucht genau einen Lesezeiger.
CREATE FUNCTION touch_sync() RETURNS trigger AS $$
BEGIN
  NEW.server_seq := nextval('sync_seq');
  NEW.updated_at := now();
  RETURN NEW;
END $$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------- 3.2 Geräte und Idempotenz

CREATE TABLE devices (
  id             UUID PRIMARY KEY,
  label          TEXT           NOT NULL,
  platform       TEXT           NOT NULL CHECK (platform IN ('android', 'ios')),
  token_hash     TEXT           NOT NULL,          -- Argon2id des Gerätegeheimnisses
  last_seen_at   TIMESTAMPTZ(3),
  last_ack_seq   BIGINT         NOT NULL DEFAULT 0,
  revoked        BOOLEAN        NOT NULL DEFAULT false,
  created_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

-- Kopplungscodes (5.2): kurzlebig, einmalig, nur als Hash abgelegt.
CREATE TABLE pairing_codes (
  id             UUID PRIMARY KEY,
  code_hash      TEXT           NOT NULL UNIQUE,
  expires_at     TIMESTAMPTZ(3) NOT NULL,
  used_at        TIMESTAMPTZ(3),
  device_id      UUID           REFERENCES devices(id),
  created_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

CREATE TABLE applied_changes (
  client_change_id UUID PRIMARY KEY,
  device_id        UUID           NOT NULL REFERENCES devices(id),
  entity           TEXT           NOT NULL,
  entity_id        UUID           NOT NULL,
  applied_at       TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  result           TEXT           NOT NULL,        -- 'applied' | 'ignored_stale'
  seq              BIGINT
);
CREATE INDEX applied_changes_device_idx ON applied_changes (device_id, applied_at);

-- ---------------------------------------------------------------- 3.3 Stammdaten

CREATE TABLE member_categories (
  id                     UUID PRIMARY KEY,
  name                   TEXT          NOT NULL,
  negative_balance_limit NUMERIC(12,2) NOT NULL DEFAULT 0
);

CREATE TABLE members (
  id                   UUID PRIMARY KEY,
  name                 TEXT NOT NULL,
  category_id          UUID REFERENCES member_categories(id),
  last_used_timestamp  TIMESTAMPTZ(3)
  -- kein balance: abgeleitet, siehe Sicht member_balances
);
CREATE INDEX members_category_idx ON members (category_id);

CREATE TABLE products (
  id            UUID PRIMARY KEY,
  name          TEXT             NOT NULL,
  price         NUMERIC(12,2)    NOT NULL,
  category      TEXT             NOT NULL DEFAULT '',
  image_url     TEXT,
  has_variants  BOOLEAN          NOT NULL DEFAULT false,
  serving_size  DOUBLE PRECISION NOT NULL DEFAULT 1.0
);

CREATE TABLE product_variants (
  id           UUID PRIMARY KEY,
  product_id   UUID             NOT NULL REFERENCES products(id),
  name         TEXT             NOT NULL,
  price        NUMERIC(12,2)    NOT NULL,
  serving_size DOUBLE PRECISION                    -- NULL erbt vom Produkt
);
CREATE INDEX product_variants_product_idx ON product_variants (product_id);

CREATE TABLE stock_items (
  id         UUID PRIMARY KEY,
  name       TEXT             NOT NULL,
  unit       TEXT             NOT NULL DEFAULT 'Stk',
  tracking   TEXT             NOT NULL CHECK (tracking IN ('SIMPLE', 'CONTAINER')),
  min_level  DOUBLE PRECISION NOT NULL DEFAULT 0
  -- kein simple_quantity: abgeleitet
);

CREATE TABLE container_types (
  id                     UUID PRIMARY KEY,
  stock_item_id          UUID             NOT NULL REFERENCES stock_items(id),
  label                  TEXT             NOT NULL,
  nominal_size           DOUBLE PRECISION NOT NULL,
  initial_yield_estimate DOUBLE PRECISION NOT NULL
  -- kein full_count: abgeleitet aus stock_entries minus Anstiche
);
CREATE INDEX container_types_item_idx ON container_types (stock_item_id);

CREATE TABLE product_components (
  id                UUID PRIMARY KEY,
  product_id        UUID             NOT NULL REFERENCES products(id),
  stock_item_id     UUID             NOT NULL REFERENCES stock_items(id),
  quantity_per_unit DOUBLE PRECISION NOT NULL
);
CREATE INDEX product_components_product_idx ON product_components (product_id);
CREATE INDEX product_components_item_idx ON product_components (stock_item_id);

-- ---------------------------------------------------------------- 3.4 Bewegungsdaten

CREATE TABLE transactions (
  id                   UUID PRIMARY KEY,
  transaction_group_id UUID           NOT NULL,    -- klammert einen Kassiervorgang
  member_id            UUID           REFERENCES members(id),
  member_name          TEXT,                       -- Schnappschuss zum Buchungszeitpunkt
  product_ref          UUID           NOT NULL,    -- Sentinel für Guthaben, Trinkgeld, manuelle Beträge
  product_name         TEXT           NOT NULL,
  product_category     TEXT           NOT NULL DEFAULT '',
  price                NUMERIC(12,2)  NOT NULL,
  quantity             INTEGER        NOT NULL,
  discount_amount      NUMERIC(12,2)  NOT NULL DEFAULT 0,
  payment_type         TEXT           NOT NULL,    -- CASH|CARD|MEMBER_BALANCE|TOPUP_*|CORRECTION
  occurred_at          TIMESTAMPTZ(3) NOT NULL,
  is_refund            BOOLEAN        NOT NULL DEFAULT false,
  note                 TEXT
);
CREATE INDEX transactions_member_idx ON transactions (member_id, occurred_at);
CREATE INDEX transactions_occurred_idx ON transactions (occurred_at);
CREATE INDEX transactions_group_idx ON transactions (transaction_group_id);

CREATE TABLE deliveries (
  id            UUID PRIMARY KEY,
  supplier      TEXT           NOT NULL DEFAULT '',
  receipt_total NUMERIC(12,2),
  photo_key     TEXT,                              -- Objektschlüssel, nicht Geräte-URI
  note          TEXT,
  occurred_at   TIMESTAMPTZ(3) NOT NULL
);
CREATE INDEX deliveries_occurred_idx ON deliveries (occurred_at);

CREATE TABLE stock_entries (
  id            UUID PRIMARY KEY,
  stock_item_id UUID             NOT NULL REFERENCES stock_items(id),
  item_name     TEXT             NOT NULL,         -- Schnappschuss
  quantity      DOUBLE PRECISION NOT NULL,         -- negativ = Korrektur
  unit_label    TEXT             NOT NULL,
  total_cost    NUMERIC(12,2),
  note          TEXT,
  source        TEXT             NOT NULL CHECK (source IN ('MANUAL', 'SCAN', 'CORRECTION')),
  occurred_at   TIMESTAMPTZ(3)   NOT NULL,
  delivery_id   UUID             REFERENCES deliveries(id)
);
CREATE INDEX stock_entries_item_idx ON stock_entries (stock_item_id);
CREATE INDEX stock_entries_delivery_idx ON stock_entries (delivery_id);
CREATE INDEX stock_entries_occurred_idx ON stock_entries (occurred_at);

CREATE TABLE tapped_containers (
  id                UUID PRIMARY KEY,
  container_type_id UUID             NOT NULL REFERENCES container_types(id),
  opened_at         TIMESTAMPTZ(3)   NOT NULL,
  closed_at         TIMESTAMPTZ(3),
  close_reason      TEXT             CHECK (close_reason IN ('EMPTIED', 'SPOILED')),
  discarded_volume  DOUBLE PRECISION NOT NULL DEFAULT 0,
  note              TEXT
  -- kein drawn: abgeleitet aus transactions seit opened_at über die Rezeptur
);
CREATE INDEX tapped_containers_type_idx ON tapped_containers (container_type_id);
CREATE INDEX tapped_containers_opened_idx ON tapped_containers (opened_at);

-- ---------------------------------------------------------------- 3.1 Sync-Grundgerüst

-- Jede fachliche Tabelle bekommt dieselben fünf Spalten, den Trigger und den Index.
DO $$
DECLARE
  t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'member_categories', 'members', 'products', 'product_variants', 'stock_items',
    'container_types', 'product_components', 'transactions', 'deliveries',
    'stock_entries', 'tapped_containers'
  ]
  LOOP
    EXECUTE format(
      'ALTER TABLE %I
         ADD COLUMN server_seq    BIGINT         NOT NULL,
         ADD COLUMN updated_at    TIMESTAMPTZ(3) NOT NULL,
         ADD COLUMN deleted       BOOLEAN        NOT NULL DEFAULT false,
         ADD COLUMN deleted_at    TIMESTAMPTZ(3),
         ADD COLUMN origin_device UUID           REFERENCES devices(id)', t);
    EXECUTE format(
      'CREATE TRIGGER trg_sync BEFORE INSERT OR UPDATE ON %I
         FOR EACH ROW EXECUTE FUNCTION touch_sync()', t);
    EXECUTE format('CREATE INDEX %I ON %I (server_seq)', t || '_server_seq_idx', t);
  END LOOP;
END $$;

-- Eine Rezepturzeile je Artikel und Produkt — aber nur unter den lebenden Zeilen, sonst
-- blockiert eine gelöschte Zeile das Wiederanlegen für immer.
CREATE UNIQUE INDEX product_components_unique_live
  ON product_components (product_id, stock_item_id) WHERE NOT deleted;

-- ---------------------------------------------------------------- 2.2 Abgeleiteter Saldo

-- Dieselbe Regel steht als Kotlin in :core (data/Ledger.kt); der Test SyncTest prüft,
-- dass beide dasselbe ergeben. Wer eine Seite ändert, muss die andere mitziehen.
CREATE VIEW member_balances AS
SELECT m.id AS member_id,
       COALESCE(SUM(
         CASE
           -- Aufladung/Korrektur: Sentinel-Produkt, price trägt das Vorzeichen
           WHEN t.product_ref = '00000000-0000-0000-0000-000000000000'
             THEN  t.price * t.quantity
           -- Verkauf auf den Deckel
           WHEN t.payment_type = 'MEMBER_BALANCE'
             THEN -(t.price * t.quantity - t.discount_amount)
           -- Bar und Karte berühren den Deckel nicht
           ELSE 0
         END * CASE WHEN t.is_refund THEN -1 ELSE 1 END
       ), 0)::numeric(12,2) AS balance
FROM members m
LEFT JOIN transactions t
       ON t.member_id = m.id AND NOT t.deleted
GROUP BY m.id;
