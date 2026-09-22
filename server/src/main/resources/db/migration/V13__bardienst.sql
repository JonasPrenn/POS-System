-- Bardienst ohne Barkasse (Konzept 4.5): eine Schicht, in der niemand zählt und die Theke kein
-- Bargeld nimmt — nur Deckel und Karte. Gesetzt vom Tablet beim Beginnen, synchronisiert wie
-- die Schicht selbst. Im Kassenbuch kommt so eine Schicht nicht vor: Sie hat keine Lade.
ALTER TABLE cash_sessions ADD COLUMN cashless BOOLEAN NOT NULL DEFAULT false;
