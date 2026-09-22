package com.example.vereins_kassensystem

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.vereins_kassensystem.ui.VereinsDeckelApp

/**
 * Nur noch Hülle: Zeigt [VereinsDeckelApp] und reicht dem SumUp-SDK die Activity, die
 * es für `startActivityForResult` braucht. Alles andere — Thema, Navigation,
 * ViewModels — steht in :shared und ist auf iOS dasselbe.
 */
class MainActivity : ComponentActivity() {

    private val app: KassenApplication get() = application as KassenApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.payments.attach(this)
        setContent { VereinsDeckelApp(app.graph) }
    }

    override fun onDestroy() {
        // Beim Drehen des Geräts bleibt der Prozessor angehängt: Die neue Activity
        // ersetzt die Referenz gleich in onCreate, und eine laufende Kartenzahlung darf
        // ihr Ergebnis nicht verlieren, nur weil das Tablet gekippt wurde.
        if (!isChangingConfigurations) app.payments.detach()
        super.onDestroy()
    }

    @Deprecated("Das SumUp-SDK arbeitet noch mit startActivityForResult.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        app.payments.handleActivityResult(requestCode, data)
    }
}
