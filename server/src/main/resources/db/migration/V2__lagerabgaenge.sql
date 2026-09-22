-- Lagerabgänge als eigene anfügende Tabelle, und die Gebindegröße am Wareneingang.
--
-- Abweichung von der Spezifikation 2.3, bewusst: Dort wird der Verbrauch nachträglich aus
-- transactions mal Rezeptur hergeleitet. Das trägt nicht — die Glasgröße der Variante
-- steht in keiner Buchungszeile, und jede spätere Rezepturänderung schriebe den Verbrauch
-- der Vergangenheit um. Die App hält deshalb beim Verkauf fest, was sie dem Keller
-- entnommen hat; das ist wie transactions anfügend und damit konfliktfrei.
--
--   Bestand (Stück)    = Summe stock_entries.quantity  −  Summe stock_draws.volume
--   volle Gebinde      = Summe stock_entries.quantity je container_type_id  −  Anstiche
--   gezapft je Anstich = Summe stock_draws.volume des Artikels im Zeitfenster
--                        [opened_at, closed_at) des Anstichs
--
-- Das Zeitfenster statt eines Verweises auf den Anstich: Verwirft der Server einen
-- doppelten Anstich (4.3), zählen die Abgänge des unterlegenen Geräts trotzdem zum Fass,
-- das wirklich am Hahn hing.

ALTER TABLE stock_entries
  ADD COLUMN container_type_id UUID REFERENCES container_types(id);
CREATE INDEX stock_entries_type_idx ON stock_entries (container_type_id);

CREATE TABLE stock_draws (
  id             UUID PRIMARY KEY,
  stock_item_id  UUID             NOT NULL REFERENCES stock_items(id),
  transaction_id UUID             REFERENCES transactions(id),   -- NULL: Übernahme oder Korrektur
  volume         DOUBLE PRECISION NOT NULL,                      -- in der Einheit des Artikels
  occurred_at    TIMESTAMPTZ(3)   NOT NULL,
  note           TEXT,
  server_seq     BIGINT           NOT NULL,
  updated_at     TIMESTAMPTZ(3)   NOT NULL,
  deleted        BOOLEAN          NOT NULL DEFAULT false,
  deleted_at     TIMESTAMPTZ(3),
  origin_device  UUID             REFERENCES devices(id)
);
CREATE TRIGGER trg_sync BEFORE INSERT OR UPDATE ON stock_draws
  FOR EACH ROW EXECUTE FUNCTION touch_sync();
CREATE INDEX stock_draws_server_seq_idx ON stock_draws (server_seq);
CREATE INDEX stock_draws_item_idx ON stock_draws (stock_item_id, occurred_at);
CREATE INDEX stock_draws_transaction_idx ON stock_draws (transaction_id);
