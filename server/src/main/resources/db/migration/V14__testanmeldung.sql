-- Testanmeldung: Ein Administrator meldet sich mit admin#benutzer@verein als ein anderer Benutzer
-- an, um zu sehen, was dessen Rolle sieht. Die Sitzung gehört dem Benutzer; hier steht, wer
-- wirklich davorsitzt — es kommt in jede Protokollzeile, die in dieser Sitzung entsteht.
ALTER TABLE web_sessions ADD COLUMN via TEXT;
