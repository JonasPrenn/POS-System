-- Die Systemdatenbank: welche Vereine es auf diesem Server gibt, wer das System verwaltet
-- (Hauptadmin), und was für alle Vereine gilt (Mindestversion der App). Die Daten eines Vereins
-- liegen in seiner eigenen Datenbank; hier steht nur, welche das ist.

CREATE TABLE tenants (
  id          UUID PRIMARY KEY,
  slug        TEXT           NOT NULL UNIQUE,  -- die Adresse der Verwaltung: /verwaltung/<slug>
  name        TEXT           NOT NULL,
  db_name     TEXT           NOT NULL UNIQUE,
  media_dir   TEXT           NOT NULL,         -- relativ zu MEDIA_DIR; leer beim ersten Verein
  is_default  BOOLEAN        NOT NULL DEFAULT false,
  active      BOOLEAN        NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
-- Genau einer ist der erste: der Bestand von vor den Vereinen, unter /verwaltung wie bisher.
CREATE UNIQUE INDEX tenants_one_default ON tenants (is_default) WHERE is_default;

-- Welches Gerät zu welchem Verein gehört. Das Token trägt die Geräte-ID; der Verein steht hier.
-- Geräte, die vor den Vereinen gekoppelt wurden, fehlen — sie gehören zum ersten Verein.
CREATE TABLE device_routes (
  device_id   UUID           PRIMARY KEY,
  tenant_id   UUID           NOT NULL REFERENCES tenants(id),
  created_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

-- Was für alle Vereine gilt, etwa die Mindestversion der App.
CREATE TABLE system_settings (
  key    TEXT PRIMARY KEY,
  value  TEXT NOT NULL
);

-- Die Hauptadmins, in derselben Form wie die Benutzer eines Vereins: So trägt sie dieselbe Anmeldung.
CREATE TABLE users (
  id             UUID PRIMARY KEY,
  login          TEXT           NOT NULL,
  display_name   TEXT           NOT NULL,
  role           TEXT           NOT NULL CHECK (role IN ('ADMIN')),
  password_hash  TEXT           NOT NULL,
  active         BOOLEAN        NOT NULL DEFAULT true,
  valid_until    DATE,
  created_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  last_login_at  TIMESTAMPTZ(3)
);
CREATE UNIQUE INDEX users_login_idx ON users (lower(login));

CREATE TABLE web_sessions (
  token_hash    TEXT PRIMARY KEY,
  user_id       UUID           NOT NULL REFERENCES users(id),
  csrf          TEXT           NOT NULL,
  created_at    TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  last_seen_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ(3) NOT NULL,
  via           TEXT                              -- wie in der Datenbank eines Vereins (V14), hier immer leer
);
CREATE INDEX web_sessions_user_idx ON web_sessions (user_id);

-- Was im System geschah: Verein angelegt, Mindestversion geändert, Update angestoßen.
CREATE TABLE audit_log (
  id       BIGSERIAL PRIMARY KEY,
  at       TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  user_id  UUID           REFERENCES users(id),
  actor    TEXT           NOT NULL,
  action   TEXT           NOT NULL,
  subject  TEXT           NOT NULL DEFAULT '',
  detail   TEXT           NOT NULL DEFAULT ''
);
CREATE INDEX audit_log_at_idx ON audit_log (at DESC);
