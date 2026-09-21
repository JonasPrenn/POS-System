package com.example.vereins_kassensystem.data.sync

/** Die Schlüssel der Tabelle `sync_state`. */
object SyncKeys {
    /** "1", sobald das Gerät gekoppelt ist. Erst dann entstehen Warteschlangeneinträge. */
    const val ENABLED = "enabled"

    /** Der Lesezeiger: die höchste Sequenznummer, die dieses Gerät vom Server gesehen hat. */
    const val SINCE = "since"

    /** Wann der letzte Abgleich vollständig durchlief, in Epoch-Millisekunden. */
    const val LAST_SYNC_AT = "last_sync_at"

    /** Wer dieses Gerät am Server ist, und wie es dort heißt. Das Token dazu liegt im Schlüsselbund. */
    const val DEVICE_ID = "device_id"
    const val DEVICE_LABEL = "device_label"

    /** Die letzte Änderung, die der Server als nicht anwendbar abgelehnt hat — ein Programmfehler, zum Nachsehen. */
    const val LAST_REJECTED = "last_rejected"
}
