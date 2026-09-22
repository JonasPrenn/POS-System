-- Rechnungen per E-Mail (Konzept 4.4): Das Postfach, das die Abrechnungen verschickt, ist auch die
-- Rechnungsadresse bei Brauerei und Händler. Jede Mail mit PDF wird hier festgehalten — von einem
-- bekannten Absender wird sie gleich ein Beleg, von einem unbekannten wartet sie, bis der Kassier
-- sie einmal freigibt. Nur am Server.

CREATE TABLE mail_senders (
  address      TEXT           PRIMARY KEY,   -- klein geschrieben
  supplier_id  UUID           REFERENCES suppliers(id),
  trusted      BOOLEAN        NOT NULL DEFAULT true,
  created_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);

CREATE TABLE mail_intake (
  id           UUID           PRIMARY KEY,
  message_id   TEXT           NOT NULL UNIQUE,   -- gegen doppeltes Einlesen
  sender       TEXT           NOT NULL,
  subject      TEXT           NOT NULL DEFAULT '',
  received_at  TIMESTAMPTZ(3) NOT NULL,
  file_key     TEXT,                             -- das PDF im ReceiptStore; NULL: die Mail hatte keins
  file_name    TEXT           NOT NULL DEFAULT '',
  status       TEXT           NOT NULL CHECK (status IN ('NEW', 'DONE', 'REJECTED', 'NOFILE', 'FAILED')),
  document_id  UUID           REFERENCES purchase_documents(id) ON DELETE SET NULL,
  note         TEXT           NOT NULL DEFAULT '',
  created_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now()
);
CREATE INDEX mail_intake_status_idx ON mail_intake (status, received_at DESC);

-- Welche Zeilen eines Belegs schon gebucht sind — damit ein zweites Lesen der Datei nichts doppelt vorschlägt.
CREATE TABLE purchase_line_keys (
  document_id  UUID NOT NULL REFERENCES purchase_documents(id) ON DELETE CASCADE,
  article_key  TEXT NOT NULL,
  PRIMARY KEY (document_id, article_key)
);
