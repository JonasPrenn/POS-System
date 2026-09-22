-- Der Couleurname („v. Sokrates") am Mitglied. Synchronisiert: An der Bude ist er das, wonach
-- man sucht. Leer heißt: keiner.
ALTER TABLE members ADD COLUMN nickname TEXT NOT NULL DEFAULT '';
