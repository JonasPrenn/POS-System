-- Gedächtnis fürs Lesen von Rechnungen (Konzept 4.4): Welche Zeile eines Lieferanten welcher
-- Lagerartikel (mit Gebinde) oder welches Konto ist, einmal vom Kassier bestätigt — die nächste
-- Rechnung desselben Lieferanten liegt dann von selbst richtig. Nur am Server, nichts fürs Tablet.
CREATE TABLE supplier_articles (
  id                 UUID PRIMARY KEY,
  supplier_key       TEXT           NOT NULL,   -- Lieferanten-ID, sonst der Name in Kleinbuchstaben
  article_key        TEXT           NOT NULL,   -- die Artikelzeile, normalisiert
  stock_item_id      UUID           REFERENCES stock_items(id),
  container_type_id  UUID           REFERENCES container_types(id),
  account_id         UUID           REFERENCES accounts(id),
  factor             NUMERIC(12,3)  NOT NULL DEFAULT 1,  -- Lagermenge je Rechnungseinheit, etwa 20 bei einer Kiste zu 20 Flaschen
  updated_at         TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  UNIQUE (supplier_key, article_key)
);
