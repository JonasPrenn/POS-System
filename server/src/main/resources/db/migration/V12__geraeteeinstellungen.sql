-- Was die Verwaltung für alle Tablets setzt — Vereinsname, Vereinsfarbe, SumUp-Schlüssel, ob
-- täglich gesichert wird: eine synchronisierte Stammdatentabelle mit einer Zeile je Schlüssel.
-- Die Geräte lesen sie nur; ein Schieben darauf lehnt der Server ab (Entities.serverOnly).
-- Die Spezifikation kennt keine Einstellungen über den Draht — Abweichung, siehe README.
CREATE TABLE device_settings (
  id     UUID PRIMARY KEY,
  key    TEXT NOT NULL UNIQUE,
  value  TEXT NOT NULL DEFAULT ''
);
ALTER TABLE device_settings
  ADD COLUMN server_seq    BIGINT         NOT NULL,
  ADD COLUMN updated_at    TIMESTAMPTZ(3) NOT NULL,
  ADD COLUMN deleted       BOOLEAN        NOT NULL DEFAULT false,
  ADD COLUMN deleted_at    TIMESTAMPTZ(3),
  ADD COLUMN origin_device UUID           REFERENCES devices(id);
CREATE TRIGGER trg_sync BEFORE INSERT OR UPDATE ON device_settings
  FOR EACH ROW EXECUTE FUNCTION touch_sync();
CREATE INDEX device_settings_server_seq_idx ON device_settings (server_seq);
