package io.github.sacha37420.coffre

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.sacha37420.coffre.core.Base64Url
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Déverrouillage par empreinte ou visage (Android 11+, authentification biométrique **forte** seulement).
 *
 * Une clé AES du coffre matériel du téléphone (Android Keystore), inutilisable sans authentification
 * biométrique à chaque usage et détruite si une empreinte est ajoutée, chiffre une copie de la clé de
 * coffre. Seul ce chiffré est écrit (stockage privé). Ni la phrase ni la clé de coffre en clair ne sont
 * jamais écrites.
 */
class Biometrie(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "empreinte.json"))

    companion object {
        private const val ALIAS = "coffre-empreinte"
    }

    /** L'appareil sait-il le faire (Android 11+, biométrie forte enregistrée) ? */
    fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun enabledFor(keyId: String?): Boolean = keyId != null && read()?.optString("key_id") == keyId

    private fun read(): JSONObject? = try {
        JSONObject(String(file.readFully(), Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    fun disable() {
        file.delete()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) { /* rien à retirer */ }
    }

    private fun newKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as SecretKey?

    private fun prompt(activity: Activity, title: String, cipher: Cipher, onCipher: (Cipher) -> Unit, onFail: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return onFail(null)
        val executor = activity.mainExecutor
        BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton(activity.getString(R.string.bio_use_phrase), executor) { _, _ -> onFail(null) }
            .build()
            .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val c = result.cryptoObject?.cipher ?: return onFail(activity.getString(R.string.bio_failed))
                        try { onCipher(c) } catch (e: Exception) { onFail(activity.getString(R.string.bio_failed)) }
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        // Annulation par l'utilisateur : pas de message, la phrase reste proposée.
                        onFail(if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) null else activity.getString(R.string.bio_failed))
                    }
                })
    }

    /** Active l'empreinte pour le coffre ouvert. `raw` est effacée dans tous les cas. */
    fun enable(activity: Activity, keyId: String, raw: ByteArray, onDone: () -> Unit, onFail: (String?) -> Unit) {
        val cipher = try {
            disable()
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, newKey()) }
        } catch (e: Exception) {
            raw.fill(0)
            return onFail(activity.getString(R.string.bio_failed))
        }
        prompt(activity, activity.getString(R.string.bio_enable_title), cipher, { c ->
            try {
                val ct = c.doFinal(raw)
                val json = JSONObject().put("key_id", keyId).put("iv", Base64Url.encode(c.iv)).put("ct", Base64Url.encode(ct))
                val out = file.startWrite()
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
                onDone()
            } finally {
                raw.fill(0)
            }
        }, { msg -> raw.fill(0); onFail(msg) })
    }

    /** Déchiffre la copie de la clé de coffre après une empreinte réussie. */
    fun unlock(activity: Activity, onRaw: (keyId: String, raw: ByteArray) -> Unit, onFail: (String?) -> Unit) {
        val saved = read() ?: return onFail(null)
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, existingKey() ?: throw KeyPermanentlyInvalidatedException(),
                    GCMParameterSpec(128, Base64Url.decode(saved.getString("iv"))))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable()
            return onFail(activity.getString(R.string.bio_invalidated))
        } catch (e: Exception) {
            return onFail(activity.getString(R.string.bio_failed))
        }
        prompt(activity, activity.getString(R.string.bio_unlock_title), cipher, { c ->
            onRaw(saved.getString("key_id"), c.doFinal(Base64Url.decode(saved.getString("ct"))))
        }, onFail)
    }
}
