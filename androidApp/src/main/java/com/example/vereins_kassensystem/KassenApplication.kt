package com.example.vereins_kassensystem

import android.app.Application
import com.example.vereins_kassensystem.platform.AndroidBackupScheduler
import com.example.vereins_kassensystem.platform.AndroidContextHolder
import com.example.vereins_kassensystem.platform.AndroidPlatform
import com.example.vereins_kassensystem.platform.SumUpPaymentProcessor
import com.example.vereins_kassensystem.platform.initializeSumUp
import com.example.vereins_kassensystem.worker.BackupWorker

/**
 * Die Android-Hülle. Sie hält das Platform-Objekt und den [AppGraph]; alles Fachliche
 * liegt in :shared. Der Zahlungsprozessor lebt hier und nicht in der Activity, weil er
 * ein Ergebnis über einen Bildschirmwechsel hinweg halten muss — MainActivity hängt sich
 * nur an und wieder ab.
 */
class KassenApplication : Application() {

    val payments = SumUpPaymentProcessor()

    val platform: AndroidPlatform by lazy {
        AndroidPlatform(
            application = this,
            payments = payments,
            backupScheduler = AndroidBackupScheduler(this, BackupWorker::class.java)
        )
    }

    val graph: AppGraph by lazy { AppGraph(platform) }

    override fun onCreate() {
        super.onCreate()
        // Muss vor allem anderen kommen: Datenbank, Einstellungen und Sicherung holen
        // sich den Context hierüber.
        AndroidContextHolder.install(this)
        initializeSumUp(this)
    }
}
