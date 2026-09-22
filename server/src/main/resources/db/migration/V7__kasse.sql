-- Kasse (docs/WEB-VERWALTUNG.md, 4.5): Schichten und Barbewegungen kommen von den Tablets —
-- synchronisiert wie alles, was an der Theke entsteht. Das Kassenbuch der Verwaltung ist eine
-- Sicht darauf: Zählungen, Barverkäufe je Gerät und Schicht, Entnahmen, Einlagen.

CREATE TABLE cash_sessions (
  id             UUID PRIMARY KEY,
  device_label   TEXT           NOT NULL DEFAULT '',
  opened_at      TIMESTAMPTZ(3) NOT NULL,
  opened_by      TEXT           NOT NULL DEFAULT '',
  opening_count  NUMERIC(12,2)  NOT NULL,
  closed_at      TIMESTAMPTZ(3),
  closed_by      TEXT,
  closing_count  NUMERIC(12,2),
  note           TEXT,
  server_seq     BIGINT         NOT NULL,
  updated_at     TIMESTAMPTZ(3) NOT NULL,
  deleted        BOOLEAN        NOT NULL DEFAULT false,
  deleted_at     TIMESTAMPTZ(3),
  origin_device  UUID           REFERENCES devices(id)
);
CREATE TRIGGER trg_sync BEFORE INSERT OR UPDATE ON cash_sessions FOR EACH ROW EXECUTE FUNCTION touch_sync();
CREATE INDEX cash_sessions_server_seq_idx ON cash_sessions (server_seq);
CREATE INDEX cash_sessions_opened_idx ON cash_sessions (opened_at DESC);

CREATE TABLE cash_movements (
  id             UUID PRIMARY KEY,
  session_id     UUID           NOT NULL REFERENCES cash_sessions(id),
  kind           TEXT           NOT NULL CHECK (kind IN ('WITHDRAWAL', 'DEPOSIT')),
  amount         NUMERIC(12,2)  NOT NULL,
  reason         TEXT           NOT NULL DEFAULT '',
  by_name        TEXT           NOT NULL DEFAULT '',
  occurred_at    TIMESTAMPTZ(3) NOT NULL,
  server_seq     BIGINT         NOT NULL,
  updated_at     TIMESTAMPTZ(3) NOT NULL,
  deleted        BOOLEAN        NOT NULL DEFAULT false,
  deleted_at     TIMESTAMPTZ(3),
  origin_device  UUID           REFERENCES devices(id)
);
CREATE TRIGGER trg_sync BEFORE INSERT OR UPDATE ON cash_movements FOR EACH ROW EXECUTE FUNCTION touch_sync();
CREATE INDEX cash_movements_server_seq_idx ON cash_movements (server_seq);
CREATE INDEX cash_movements_session_idx ON cash_movements (session_id, occurred_at);
