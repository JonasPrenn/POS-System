-- Online aufladen: Mitglieder laden ihren Deckel am PC oder Handy auf und zahlen auf der Seite
-- des Zahlungsanbieters (SumUp). Angemeldet wird mit einem Link an die Adresse im Profil, ohne
-- Passwort. Nichts davon wird synchronisiert — auf die Tablets kommt nur die Aufladung selbst,
-- als gewöhnliche Buchung mit Zahlungsart Karte.

-- Ein Anmeldelink: einmal benutzbar, eine halbe Stunde gültig; hier steht nur sein SHA-256.
CREATE TABLE portal_links (
  token_hash  TEXT PRIMARY KEY,
  email       TEXT           NOT NULL,
  created_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ(3) NOT NULL,
  used_at     TIMESTAMPTZ(3)
);
CREATE INDEX portal_links_email_idx ON portal_links (lower(email), created_at DESC);

-- Eine Sitzung gehört einer Adresse, nicht einem Mitglied: Teilen sich zwei Mitglieder eine
-- Adresse (Vater und Sohn), sieht die Sitzung beide Deckel.
CREATE TABLE portal_sessions (
  token_hash  TEXT PRIMARY KEY,
  email       TEXT           NOT NULL,
  csrf        TEXT           NOT NULL,
  created_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ(3) NOT NULL
);

-- Jede Online-Aufladung vom Anlegen bis zur Buchung. Die id ist zugleich die Referenz beim
-- Anbieter und, wenn bezahlt, die id der Buchung in `transactions` — so wird genau einmal gebucht,
-- egal wie oft der Anbieter Bescheid gibt.
CREATE TABLE online_topups (
  id           UUID PRIMARY KEY,
  member_id    UUID           NOT NULL REFERENCES members(id),
  amount       NUMERIC(12,2)  NOT NULL CHECK (amount > 0),
  provider     TEXT           NOT NULL,
  checkout_id  TEXT,
  pay_url      TEXT,                                -- die Bezahlseite beim Anbieter, eine halbe Stunde gültig
  status       TEXT           NOT NULL CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED')),
  email        TEXT           NOT NULL,
  detail       TEXT           NOT NULL DEFAULT '',
  created_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  settled_at   TIMESTAMPTZ(3)
);
CREATE INDEX online_topups_checkout_idx ON online_topups (checkout_id);
CREATE INDEX online_topups_open_idx ON online_topups (created_at) WHERE status = 'PENDING';
