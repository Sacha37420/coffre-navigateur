package io.github.sacha37420.coffre

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import io.github.sacha37420.coffre.core.CoffreException
import java.util.concurrent.Executors

/**
 * Reçoit l'adresse de retour de Keycloak (https://<lab>/coffre-retour/?code=…&state=…), ouverte par Android
 * grâce aux App Links. Échange le code (PKCE) hors du fil de l'interface, synchronise, puis revient à
 * l'écran principal. L'adresse reçue n'est ni journalisée ni affichée.
 */
class RetourActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val url = intent?.dataString
        val lab = coffre.lab
        if (url == null || lab == null) return back(getString(R.string.lab_login_failed))
        Executors.newSingleThreadExecutor().execute {
            val message = try {
                lab.completeLogin(url)
                coffre.syncNow()
                null
            } catch (e: CoffreException) {
                e.message
            } catch (e: Exception) {
                getString(R.string.unexpected)
            }
            runOnUiThread { back(message) }
        }
    }

    private fun back(message: String?) {
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
