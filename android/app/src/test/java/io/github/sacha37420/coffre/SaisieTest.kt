package io.github.sacha37420.coffre

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.github.sacha37420.coffre.core.Entries
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Lot 11 sur la JVM : écran d'explication, déverrouillage pour la saisie, confiance dans le navigateur. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SaisieTest {
    private val root = File(System.getProperty("coffre.racine") ?: "../..")
    private val vectors = JSONObject(File(root, "tests/vecteurs/v1.json").readText())
    private val phrase = vectors.getString("passphrase")
    private val app get() = RuntimeEnvironment.getApplication() as CoffreApp

    private fun install(pkg: String, system: Boolean, installer: String?) {
        val pi = PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0 }
        }
        val spm = shadowOf(app.packageManager)
        spm.installPackage(pi)
        if (installer != null) spm.setInstallSourceInfo(pkg, installer, installer)
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("délai dépassé")
    }

    @Test fun `navigateur fiable - Chrome systeme ou du Play Store oui, appli quelconque non`() {
        install("com.android.chrome", system = true, installer = null)
        install("org.mozilla.firefox", system = false, installer = "com.android.vending")
        install("com.brave.browser", system = false, installer = "com.exemple.magasin")
        install("com.exemple.jeu", system = false, installer = "com.android.vending")
        assertTrue(Saisie.trusted(app, "com.android.chrome"))
        assertTrue(Saisie.trusted(app, "org.mozilla.firefox"))
        assertFalse("installé hors Play Store", Saisie.trusted(app, "com.brave.browser"))
        assertFalse("pas un navigateur connu", Saisie.trusted(app, "com.exemple.jeu"))
        assertFalse("absent", Saisie.trusted(app, "com.microsoft.emmx"))
    }

    @Test fun `ecran d'explication - etats et boutons qui ouvrent les bons reglages`() {
        install("com.android.chrome", system = true, installer = null)
        val a = Robolectric.buildActivity(SaisieActivity::class.java).setup().get()
        assertTrue(a.findViewById<TextView>(R.id.saisie_etat_android).text.startsWith("✗"))
        assertEquals(View.VISIBLE, a.findViewById<Button>(R.id.saisie_android).visibility)
        // Le simulateur ne répond pas au fournisseur de Chrome : l'état est « inconnu », le bouton reste proposé.
        assertTrue(a.findViewById<TextView>(R.id.saisie_etat_chrome).text.contains("vérifiez"))
        assertEquals(View.VISIBLE, a.findViewById<Button>(R.id.saisie_chrome).visibility)

        a.findViewById<Button>(R.id.saisie_android).performClick()
        val i1 = shadowOf(a).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, i1.action)
        assertEquals("package:${a.packageName}", i1.dataString)

        a.findViewById<Button>(R.id.saisie_chrome).performClick()
        val i2 = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_APPLICATION_PREFERENCES, i2.action)
        assertEquals("com.android.chrome", i2.`package`)
        assertTrue(i2.hasCategory(Intent.CATEGORY_APP_BROWSER) && i2.hasCategory(Intent.CATEGORY_PREFERENCE))

        // Service choisi : l'état passe au vert et le bouton disparaît.
        shadowOf(a.getSystemService(AutofillManager::class.java))
            .setAutofillServiceComponentName(android.content.ComponentName(a.packageName, CoffreAutofillService::class.java.name))
        Robolectric.buildActivity(SaisieActivity::class.java).setup().get().let { b ->
            assertTrue(b.findViewById<TextView>(R.id.saisie_etat_android).text.startsWith("✓"))
            assertEquals(View.GONE, b.findViewById<Button>(R.id.saisie_android).visibility)
        }
    }

    @Test fun `sans Chrome - rien a regler pour lui`() {
        val a = Robolectric.buildActivity(SaisieActivity::class.java).setup().get()
        assertTrue(a.findViewById<TextView>(R.id.saisie_etat_chrome).text.contains("pas installé"))
        assertEquals(View.GONE, a.findViewById<Button>(R.id.saisie_chrome).visibility)
    }

    @Test fun `le guide s'ouvre une fois apres l'import, puis plus`() {
        app.vault.install(vectors.getJSONObject("keyFile"), phrase)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(SaisieActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        val b = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(shadowOf(b).nextStartedActivity == null)
        b.findViewById<Button>(R.id.home_saisie).performClick()
        assertEquals(SaisieActivity::class.java.name, shadowOf(b).nextStartedActivity.component!!.className)
    }

    @Test fun `coffre verrouille - deverrouiller puis enregistrer le compte saisi`() {
        app.vault.install(vectors.getJSONObject("keyFile"), phrase)
        app.vault.lock()
        val intent = Intent(app, DeverrouillageSaisieActivity::class.java)
            .putExtra(DeverrouillageSaisieActivity.EXTRA_MODE, DeverrouillageSaisieActivity.MODE_ENREGISTRER)
            .putExtra(DeverrouillageSaisieActivity.EXTRA_ORIGIN, "https://site.fr")
            .putExtra(DeverrouillageSaisieActivity.EXTRA_USERNAME, "moi")
            .putExtra(DeverrouillageSaisieActivity.EXTRA_PASSWORD, "nouveau-secret")
        val c = Robolectric.buildActivity(DeverrouillageSaisieActivity::class.java, intent).setup()
        val a = c.get()
        assertTrue(a.findViewById<TextView>(R.id.unlock_title).text.contains("site.fr"))
        a.findViewById<EditText>(R.id.unlock_pass).setText("mauvaise phrase de passe")
        a.findViewById<Button>(R.id.unlock_go).performClick()
        waitFor { a.findViewById<TextView>(R.id.unlock_msg).visibility == View.VISIBLE }
        assertFalse(a.isFinishing)
        a.findViewById<EditText>(R.id.unlock_pass).setText(phrase)
        a.findViewById<Button>(R.id.unlock_go).performClick()
        waitFor { a.isFinishing }
        assertEquals(Activity.RESULT_OK, shadowOf(a).resultCode)
        val saved = Entries.forOrigin(app.entries.decryptAll(app.vault.key()!!).first, "https://site.fr")
        assertEquals(1, saved.size)
        assertEquals("moi", saved[0].getString("username"))
        assertEquals("nouveau-secret", saved[0].getString("password"))
    }

    @Test fun `coffre verrouille - deverrouiller puis rendre les propositions a Android`() {
        app.vault.install(vectors.getJSONObject("keyFile"), phrase)
        app.entries.put(app.vault.key()!!, Entries.newEntry("Site", listOf("https://site.fr"), "moi", "secret"))
        app.vault.lock()
        // AutofillId n'a pas de constructeur public : le système les fournit ; ici, par réflexion.
        val newId = { n: Int -> AutofillId::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(n) }
        val intent = Intent(app, DeverrouillageSaisieActivity::class.java)
            .putExtra(DeverrouillageSaisieActivity.EXTRA_MODE, DeverrouillageSaisieActivity.MODE_REMPLIR)
            .putExtra(DeverrouillageSaisieActivity.EXTRA_ORIGIN, "https://site.fr")
            .putExtra(DeverrouillageSaisieActivity.EXTRA_USERNAME_ID, newId(1))
            .putExtra(DeverrouillageSaisieActivity.EXTRA_PASSWORD_IDS, arrayOf(newId(2)))
        val a = Robolectric.buildActivity(DeverrouillageSaisieActivity::class.java, intent).setup().get()
        a.findViewById<EditText>(R.id.unlock_pass).setText(phrase)
        a.findViewById<Button>(R.id.unlock_go).performClick()
        waitFor { a.isFinishing }
        assertEquals(Activity.RESULT_OK, shadowOf(a).resultCode)
        assertNotNull(shadowOf(a).resultIntent.extras?.get(AutofillManager.EXTRA_AUTHENTICATION_RESULT))
    }
}
