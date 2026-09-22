-- Web-Verwaltung, Phase 1 (docs/WEB-VERWALTUNG.md): Benutzer, Rollen, Sitzungen, Protokoll,
-- Einstellungen. Nichts davon wird synchronisiert — was die Theke zum Verkaufen nicht braucht,
-- bleibt auf dem Server.

CREATE TABLE users (
  id             UUID PRIMARY KEY,
  login          TEXT           NOT NULL,
  display_name   TEXT           NOT NULL,
  role           TEXT           NOT NULL CHECK (role IN ('ADMIN', 'KASSIER', 'VORSTAND', 'BUDENWART', 'PRUEFER')),
  password_hash  TEXT           NOT NULL,          -- Argon2id, wie die Gerätetoken
  active         BOOLEAN        NOT NULL DEFAULT true,
  valid_until    DATE,                             -- Rechnungsprüfer: Zugang auf Zeit
  created_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  last_login_at  TIMESTAMPTZ(3)
);
CREATE UNIQUE INDEX users_login_idx ON users (lower(login));

-- Im Cookie steht ein Zufallswert, hier nur sein SHA-256: Wer die Datenbank liest, kann sich
-- damit nicht anmelden.
CREATE TABLE web_sessions (
  token_hash    TEXT PRIMARY KEY,
  user_id       UUID           NOT NULL REFERENCES users(id),
  csrf          TEXT           NOT NULL,
  created_at    TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  last_seen_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ(3) NOT NULL
);
CREATE INDEX web_sessions_user_idx ON web_sessions (user_id);

-- Wer hat wann was getan. Für den Rechnungsprüfer — und für den Abend, an dem ein Gerät
-- „von allein" gesperrt war. Der Name ist ein Schnappschuss: Chargen wechseln, das Protokoll bleibt.
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
CREATE INDEX audit_log_action_idx ON audit_log (action, at DESC);

CREATE TABLE settings (
  key    TEXT PRIMARY KEY,
  value  TEXT NOT NULL
);

-- Die Wirkung einer Buchungszeile auf den Deckel, einmal als Spalte: Der Kontoauszug in der
-- Verwaltung braucht sie je Zeile, der Saldo als Summe. Die Regel selbst steht damit weiterhin
-- an genau einer Stelle in PostgreSQL (und in Ledger.kt und DerivedSql.kt) — member_balances
-- setzt jetzt darauf auf, statt sie zu wiederholen.
CREATE VIEW transaction_effects AS
SELECT t.id, t.transaction_group_id, t.member_id, t.member_name, t.product_ref, t.product_name,
       t.product_category, t.price, t.quantity, t.discount_amount, t.payment_type, t.occurred_at,
       t.is_refund, t.note, t.origin_device,
       (CASE
          -- Aufladung/Korrektur: Sentinel-Produkt, price trägt das Vorzeichen
          WHEN t.product_ref = '00000000-0000-0000-0000-000000000000'
            THEN  t.price * t.quantity
          -- Verkauf auf den Deckel
          WHEN t.payment_type = 'MEMBER_BALANCE'
            THEN -(t.price * t.quantity - t.discount_amount)
          -- Bar und Karte berühren den Deckel nicht
          ELSE 0
        END * CASE WHEN t.is_refund THEN -1 ELSE 1 END)::numeric(12,2) AS balance_effect,
       -- Was der Verkauf eingebracht hat; Aufladungen und Trinkgeld sind kein Umsatz.
       (CASE
          WHEN t.product_ref IN ('00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000003') THEN 0
          WHEN t.payment_type = 'CORRECTION' THEN 0
          ELSE t.price * t.quantity - t.discount_amount
        END * CASE WHEN t.is_refund THEN -1 ELSE 1 END)::numeric(12,2) AS revenue
FROM transactions t
WHERE NOT t.deleted;

CREATE OR REPLACE VIEW member_balances AS
SELECT m.id AS member_id,
       COALESCE(SUM(e.balance_effect), 0)::numeric(12,2) AS balance
FROM members m
LEFT JOIN transaction_effects e ON e.member_id = m.id
GROUP BY m.id;
