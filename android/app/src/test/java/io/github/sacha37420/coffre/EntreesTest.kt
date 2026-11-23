package io.github.sacha37420.coffre

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import io.github.sacha37420.coffre.core.Entries
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/** Lot 10 sur la JVM : « Mes mots de passe » et la fiche. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EntreesTest {
    private val root = File(System.getProperty("coffre.racine") ?: "../..")
    private val vectors = JSONObject(File(root, "tests/vecteurs/v1.json").readText())
    private val phrase = vectors.getString("passphrase")
    private val app get() = RuntimeEnvironment.getApplication() as CoffreApp

    private fun all() = app.entries.decryptAll(app.vault.peekKey()!!).first
    private fun live() = all().filter { !it.optBoolean("deleted") }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("délai dépassé")
    }

    @Before fun setUp() {
        app.clockOffsetForTests = 0
        app.vault.install(vectors.getJSONObject("keyFile"), phrase)
        val v = app.vault.key()!!
        app.entries.put(v, Entries.newEntry("Messagerie", listOf("mail.exemple.fr"), "moi@exemple.fr", "mdp-mail"))
        app.entries.put(v, Entries.newEntry("Banque Épargne", listOf("banque.fr"), "client42", "mdp-banque"))
    }

    private fun EditText.isRevealed() = inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

    @Test fun `liste triee, recherche sans accent, ouverture d'une fiche`() {
        val a = Robolectric.buildActivity(EntreesActivity::class.java).setup().get()
        val list = a.findViewById<ListView>(R.id.e_liste)
        assertEquals(2, list.adapter.count)
        assertEquals("Banque Épargne", (list.adapter.getItem(0) as Map<*, *>)["titre"])
        assertTrue(a.findViewById<TextView>(R.id.e_compte).text.contains("2"))
        a.findViewById<EditText>(R.id.e_recherche).setText("epargne")
        assertEquals(1, list.adapter.count)
        a.findViewById<EditText>(R.id.e_recherche).setText("exemple.fr")
        assertEquals("Messagerie", (list.adapter.getItem(0) as Map<*, *>)["titre"])
        shadowOf(list).performItemClick(0)
        val open = shadowOf(a).nextStartedActivity
        assertEquals(FicheActivity::class.java.name, open.component!!.className)
        assertEquals(live().first { it.getString("name") == "Messagerie" }.getString("id"), open.getStringExtra(FicheActivity.EXTRA_ID))
    }

    @Test fun `ajout avec mot de passe genere, origines normalisees`() {
        val f = Robolectric.buildActivity(FicheActivity::class.java).setup().get()
        assertEquals("Nouvelle entrée", f.findViewById<TextView>(R.id.f_titre).text.toString())
        f.findViewById<EditText>(R.id.f_nom).setText("Impôts")
        f.findViewById<EditText>(R.id.f_sites).setText("impots.gouv.fr/connexion")
        f.findViewById<EditText>(R.id.f_identifiant).setText("1234567890123")
        f.findViewById<Button>(R.id.f_generer).performClick()
        val generated = f.findViewById<EditText>(R.id.f_mdp).text.toString()
        assertEquals(20, generated.length)
        assertTrue("un mot de passe généré s'affiche", f.findViewById<EditText>(R.id.f_mdp).isRevealed())
        f.findViewById<Button>(R.id.f_enregistrer).performClick()
        assertTrue(f.isFinishing)
        val e = live().first { it.getString("name") == "Impôts" }
        assertEquals(listOf("https://impots.gouv.fr"), Entries.origins(e))
        assertEquals(generated, e.getString("password"))
    }

    @Test fun `fiche - affichage direct, puis redemande apres 5 min, copie sensible, modification`() {
        val id = live().first { it.getString("name") == "Banque Épargne" }.getString("id")
        val f = Robolectric.buildActivity(FicheActivity::class.java, Intent(app, FicheActivity::class.java).putExtra(FicheActivity.EXTRA_ID, id)).setup().get()
        val pwd = f.findViewById<EditText>(R.id.f_mdp)
        assertFalse("masqué à l'ouverture", pwd.isRevealed())
        f.findViewById<Button>(R.id.f_afficher).performClick()
        assertTrue("phrase saisie il y a moins de 5 min : affichage direct", pwd.isRevealed())
        f.findViewById<Button>(R.id.f_afficher).performClick()
        assertFalse(pwd.isRevealed())
        assertFalse("masquer/afficher ne compte pas comme une modification", f.isFinishing)

        app.clockOffsetForTests = 6 * 60_000L
        app.vault.key()                                   // garde le coffre ouvert, sans compter comme une confirmation
        f.findViewById<Button>(R.id.f_afficher).performClick()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("plus de 5 min : la phrase est redemandée", dialog.isShowing)
        assertFalse(pwd.isRevealed())
        (shadowOf(dialog).view as EditText).setText("mauvaise phrase de passe")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        waitFor { f.findViewById<TextView>(R.id.f_msg).text.isNotEmpty() }
        assertFalse("mauvaise phrase : toujours masqué", pwd.isRevealed())
        f.findViewById<Button>(R.id.f_afficher).performClick()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        (shadowOf(dialog).view as EditText).setText(phrase)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        waitFor { pwd.isRevealed() }

        f.findViewById<Button>(R.id.f_copier_mdp).performClick()
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("mdp-banque", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))

        f.findViewById<EditText>(R.id.f_identifiant).setText("client43")
        f.findViewById<Button>(R.id.f_enregistrer).performClick()
        assertTrue(f.isFinishing)
        val e = live().first { it.getString("id") == id }
        assertEquals("client43", e.getString("username"))
        assertEquals(2, live().size)
    }

    @Test fun `suppression - pierre tombale, retiree de la liste`() {
        val id = live().first { it.getString("name") == "Messagerie" }.getString("id")
        val f = Robolectric.buildActivity(FicheActivity::class.java, Intent(app, FicheActivity::class.java).putExtra(FicheActivity.EXTRA_ID, id)).setup().get()
        f.findViewById<Button>(R.id.f_supprimer).performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(f.isFinishing)
        val t = all().first { it.getString("id") == id }
        assertTrue(t.getBoolean("deleted"))
        assertEquals("", t.getString("password"))
        val a = Robolectric.buildActivity(EntreesActivity::class.java).setup().get()
        assertEquals(1, a.findViewById<ListView>(R.id.e_liste).adapter.count)
    }

    @Test fun `coffre verrouille - les ecrans se ferment et la fiche est videe`() {
        val id = live().first().getString("id")
        val fc = Robolectric.buildActivity(FicheActivity::class.java, Intent(app, FicheActivity::class.java).putExtra(FicheActivity.EXTRA_ID, id)).setup()
        val lc = Robolectric.buildActivity(EntreesActivity::class.java).setup()
        app.vault.lock()
        fc.pause().resume()
        lc.pause().resume()
        assertTrue(fc.get().isFinishing)
        assertEquals("", fc.get().findViewById<EditText>(R.id.f_mdp).text.toString())
        assertTrue(lc.get().isFinishing)
        assertTrue("verrouillé : la fiche ne s'ouvre pas", Robolectric.buildActivity(FicheActivity::class.java,
            Intent(app, FicheActivity::class.java).putExtra(FicheActivity.EXTRA_ID, id)).setup().get().isFinishing)
    }

    @Test fun `accueil - bouton Mes mots de passe avec le nombre`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val b = a.findViewById<Button>(R.id.home_entrees)
        assertTrue(b.text.contains("(2)"))
        b.performClick()
        // Le premier écran ouvert peut être le guide de saisie (une fois après l'import) : on cherche la liste.
        val started = generateSequence { shadowOf(a).nextStartedActivity }.map { it.component!!.className }.toList()
        assertTrue(started.toString(), EntreesActivity::class.java.name in started)
    }
}
