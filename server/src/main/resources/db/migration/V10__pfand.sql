-- Pfand und Leergut (Konzept 4.3/4.4): Gebinde, die der Verein vom Lieferanten hält — Fässer, Kisten,
-- Container — je Gebindeart gezählt, unabhängig vom Inhalt (Cola, Orange und Zitrone zu 1 l stehen in
-- derselben Kiste). Bestand ist geliefert minus zurück, aus Bewegungen hergeleitet, nicht als Zähler.
-- Nur am Server; die Tablets führen kein Pfand.

CREATE TABLE deposit_kinds (
  id           UUID PRIMARY KEY,
  name         TEXT           NOT NULL,
  supplier_id  UUID           REFERENCES suppliers(id),
  code         TEXT           NOT NULL DEFAULT '',        -- Gebindenummer des Lieferanten, etwa 90004
  deposit      NUMERIC(12,2)  NOT NULL,                   -- Pfand je Gebinde, brutto — was der Verein zahlt und zurückbekommt
  active       BOOLEAN        NOT NULL DEFAULT true,
  created_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

CREATE TABLE deposit_movements (
  id           UUID PRIMARY KEY,
  kind_id      UUID           NOT NULL REFERENCES deposit_kinds(id),
  document_id  UUID           REFERENCES purchase_documents(id) ON DELETE SET NULL,
  day          DATE           NOT NULL,
  delivered    INTEGER        NOT NULL DEFAULT 0,
  returned     INTEGER        NOT NULL DEFAULT 0,
  note         TEXT           NOT NULL DEFAULT '',
  created_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  created_by   TEXT           NOT NULL DEFAULT ''
);
CREATE INDEX deposit_movements_kind_idx ON deposit_movements (kind_id, day);

-- Das Gedächtnis des Rechnungslesers kennt jetzt auch Pfandzeilen.
ALTER TABLE supplier_articles ADD COLUMN deposit_kind_id UUID REFERENCES deposit_kinds(id);

-- Pfand ist in der Einnahmen-Ausgaben-Rechnung ein Abfluss, der beim Zurückgeben wieder zufließt;
-- der Bestand steht in der Vermögensübersicht.
INSERT INTO accounts (id, code, name, area, kind, sort) VALUES
  ('00000000-0000-0000-0001-000000000019', 'A-PFAND', 'Pfand und Leergut', 'BUDE', 'EXPENSE', 125);
