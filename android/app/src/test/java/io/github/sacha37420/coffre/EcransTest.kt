package io.github.sacha37420.coffre

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import io.github.sacha37420.coffre.core.VaultState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/** Écrans du lot 8, simulés sur la JVM (Robolectric) : import, déverrouillage, accueil. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EcransTest {
    private val root = File(System.getProperty("coffre.racine") ?: "../..")
    private val vectors = JSONObject(File(root, "tests/vecteurs/v1.json").readText())
    private val phrase = vectors.getString("passphrase")

    private fun <T : View> Activity.v(id: Int): T = findViewById(id)

    /** Attend la fin du calcul en arrière-plan (PBKDF2) et son retour sur le fil de l'interface. */
    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("délai dépassé")
    }

    private fun chooseFile(activity: Activity, text: String) {
        val uri = Uri.parse("content://essai/cle.coffre-cle")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        val pick = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pick.intent.action)
        shadowOf(activity).receiveResult(pick.intent, Activity.RESULT_OK, Intent().setData(uri))
    }

    @Test fun `import, mauvaise phrase, ouverture, verrouillage, deverrouillage, retrait`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        val vault = a.coffre.vault

        assertTrue("écran sécurisé", a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertNotNull("écran d'import", a.findViewById<Button>(R.id.choose))
        assertFalse(a.v<Button>(R.id.import_go).isEnabled)

        // Un fichier qui n'est pas un fichier de clé est refusé.
        a.v<Button>(R.id.choose).performClick()
        chooseFile(a, "pas un fichier de clé")
        assertEquals(View.VISIBLE, a.v<TextView>(R.id.import_msg).visibility)
        assertFalse(a.v<Button>(R.id.import_go).isEnabled)

        // Le vrai fichier, produit par l'extension.
        a.v<Button>(R.id.choose).performClick()
        chooseFile(a, vectors.getJSONObject("keyFile").toString())
        assertTrue(a.v<Button>(R.id.import_go).isEnabled)
        assertTrue(a.v<TextView>(R.id.file_state).text.contains(vectors.getJSONObject("keyFile").getString("key_id").take(6)))

        a.v<EditText>(R.id.import_pass).setText("mauvaise phrase de passe")
        a.v<Button>(R.id.import_go).performClick()
        waitFor { a.v<TextView>(R.id.import_msg).visibility == View.VISIBLE && a.v<Button>(R.id.import_go).isEnabled }
        assertTrue(a.v<TextView>(R.id.import_msg).text.contains("incorrecte"))
        assertEquals(VaultState.State.AUCUN, vault.state())
        assertEquals("la phrase saisie est effacée du champ", "", a.v<EditText>(R.id.import_pass).text.toString())

        a.v<EditText>(R.id.import_pass).setText(phrase)
        a.v<Button>(R.id.import_go).performClick()
        waitFor { a.findViewById<Button>(R.id.home_lock) != null }
        assertEquals(VaultState.State.DEVERROUILLE, vault.state())
        assertTrue(File(a.filesDir, "cle.coffre-cle").readText().contains(vectors.getJSONObject("keyFile").getString("wrapped")))

        // L'option empreinte suit ce que l'appareil annonce ; jamais cochée tant qu'elle n'a pas été activée.
        val available = a.coffre.biometrie.available()
        assertEquals(available, a.v<Switch>(R.id.bio).isEnabled)
        assertFalse(a.v<Switch>(R.id.bio).isChecked)
        assertEquals(if (available) View.GONE else View.VISIBLE, a.v<TextView>(R.id.bio_info).visibility)

        // Verrouiller → écran de déverrouillage.
        a.v<Button>(R.id.home_lock).performClick()
        assertNotNull(a.findViewById<Button>(R.id.unlock_go))
        assertEquals(View.GONE, a.v<Button>(R.id.unlock_bio).visibility)
        a.v<EditText>(R.id.unlock_pass).setText("mauvaise phrase de passe")
        a.v<Button>(R.id.unlock_go).performClick()
        waitFor { a.v<TextView>(R.id.unlock_msg).visibility == View.VISIBLE }
        assertEquals(VaultState.State.VERROUILLE, vault.state())
        a.v<EditText>(R.id.unlock_pass).setText(phrase)
        a.v<Button>(R.id.unlock_go).performClick()
        waitFor { a.findViewById<Button>(R.id.home_lock) != null }

        // Réglage du délai, puis verrouillage automatique quand l'activité revient au premier plan.
        vault.setAutoLockMinutes(1)
        vault.lock()
        controller.pause().resume()
        assertNotNull("retour à l'écran de déverrouillage", a.findViewById<Button>(R.id.unlock_go))

        // Le coffre a survécu à une « nouvelle » activité (même processus) : fichier relu.
        vault.unlock(phrase)
        val b = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(b.findViewById<Button>(R.id.home_lock))

        // Retirer le coffre.
        b.v<Button>(R.id.forget).performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        waitFor { vault.state() == VaultState.State.AUCUN && b.findViewById<Button>(R.id.choose) != null }   // retrait hors du fil de l'interface
        assertFalse(File(b.filesDir, "cle.coffre-cle").exists())
        assertNotNull(b.findViewById<Button>(R.id.choose))
    }

    @Test fun `generateur sur l'accueil et copie marquee sensible`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.coffre.vault.install(vectors.getJSONObject("keyFile"), phrase)
        a.coffre.vault.lock(); a.coffre.vault.unlock(phrase)
        val b = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val pwd = b.v<TextView>(R.id.password).text.toString()
        assertEquals(20, pwd.length)
        b.v<Button>(R.id.copy).performClick()
        val clip = b.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!
        assertEquals(pwd, clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
    }
}
