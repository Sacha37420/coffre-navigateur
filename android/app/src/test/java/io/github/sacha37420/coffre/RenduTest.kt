package io.github.sacha37420.coffre

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Dessine les écrans dans build/rendus/ (PNG), pour les regarder sans téléphone.
 * Ne vérifie rien d'autre que l'absence de plantage : c'est un outil de relecture visuelle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RenduTest {
    private fun capture(name: String, unlocked: Boolean = false) {
        if (unlocked) {
            val v = org.json.JSONObject(File(System.getProperty("coffre.racine") ?: "../..", "tests/vecteurs/v1.json").readText())
            val first = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            first.coffre.setupLab("https://lab.essai.fr")
            first.coffre.vault.install(v.getJSONObject("keyFile"), v.getString("passphrase"))
        }
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root: View = a.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val out = File("build/rendus/$name.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "w393dp-h851dp-xxhdpi") fun importClair() = capture("import-clair")

    @Test @Config(qualifiers = "w393dp-h851dp-night-xxhdpi") fun importSombre() = capture("import-sombre")

    @Test @Config(qualifiers = "w393dp-h1600dp-xxhdpi") fun accueil() = capture("accueil", unlocked = true)

    private fun dessine(a: android.app.Activity, name: String) {
        val root: View = a.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/rendus/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "w393dp-h851dp-xxhdpi") fun entrees() {
        val v = org.json.JSONObject(File(System.getProperty("coffre.racine") ?: "../..", "tests/vecteurs/v1.json").readText())
        val app = org.robolectric.RuntimeEnvironment.getApplication() as CoffreApp
        app.vault.install(v.getJSONObject("keyFile"), v.getString("passphrase"))
        val k = app.vault.key()!!
        for ((n, s, u) in listOf(Triple("Banque Épargne", "banque.fr", "client42"), Triple("Messagerie", "mail.exemple.fr", "moi@exemple.fr"), Triple("Impôts", "impots.gouv.fr", "1234567890123"))) {
            app.entries.put(k, io.github.sacha37420.coffre.core.Entries.newEntry(n, listOf(s), u, "x7!kQ2#pLm9\$wR4z"))
        }
        dessine(Robolectric.buildActivity(EntreesActivity::class.java).setup().get(), "entrees")
        val id = app.entries.decryptAll(k).first.first { it.getString("name") == "Banque Épargne" }.getString("id")
        dessine(Robolectric.buildActivity(FicheActivity::class.java, android.content.Intent(app, FicheActivity::class.java).putExtra(FicheActivity.EXTRA_ID, id)).setup().get(), "fiche")
    }

    @Test @Config(qualifiers = "w393dp-h2200dp-xxhdpi") fun saisie() {
        val a = Robolectric.buildActivity(SaisieActivity::class.java).setup().get()
        val root: View = a.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/rendus/saisie.png").apply { parentFile!!.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
