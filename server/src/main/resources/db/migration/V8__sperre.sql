-- Die Sperre des Deckels (Konzept 4.1): gesetzt in der Verwaltung mit Grund, synchronisiert wie
-- der Rest der Stammdaten, damit die Theke sie sieht, bevor sie anschreibt. NULL: nicht gesperrt.
ALTER TABLE members ADD COLUMN blocked_reason TEXT;
