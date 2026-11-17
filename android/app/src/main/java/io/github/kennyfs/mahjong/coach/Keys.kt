package io.github.kennyfs.mahjong.coach

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The player's API keys, one per provider, each encrypted (AES-GCM) by a key of the Android Keystore that never
 * leaves it: the file holds only what that key can open, on this device. The file is left out of backups and device
 * transfers (res/xml/data_extraction_rules.xml, backup_rules.xml): a key restored elsewhere could not be opened, and
 * should not travel. Nothing here is logged or shown again: the settings say only whether a key is there.
 *
 * The custom server's address and model are kept in the same file, not encrypted (they are not secrets).
 */
class CoachKeys(context: Context) {
  private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  /** Changes with every key set or removed, for the screens to follow. */
  var version by mutableIntStateOf(0)
    private set

  fun has(p: Provider): Boolean {
    version
    return if (p == Provider.CUSTOM) custom.ready else prefs.contains(p.id)
  }

  /** The key, or null: none, or one this device's Keystore cannot open (then it is removed). */
  fun get(p: Provider): String? {
    val stored = prefs.getString(p.id, null) ?: return null
    return try {
      val bytes = Base64.decode(stored, Base64.NO_WRAP)
      val cipher = Cipher.getInstance(TRANSFORM)
      cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes, 0, IV))
      String(cipher.doFinal(bytes, IV, bytes.size - IV), Charsets.UTF_8)
    } catch (e: Exception) {
      clear(p)
      null
    }
  }

  fun set(p: Provider, key: String) {
    val cipher = Cipher.getInstance(TRANSFORM)
    cipher.init(Cipher.ENCRYPT_MODE, secret())
    val iv = cipher.iv
    check(iv.size == IV)
    val out = iv + cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
    prefs.edit().putString(p.id, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    version++
  }

  fun clear(p: Provider) {
    prefs.edit().remove(p.id).apply()
    version++
  }

  var custom: CustomServer
    get() {
      version
      return CustomServer(prefs.getString("custom.url", "") ?: "", prefs.getString("custom.model", "") ?: "")
    }
    set(v) {
      prefs.edit().putString("custom.url", v.url.trim()).putString("custom.model", v.model.trim()).apply()
      version++
    }

  /** Whether the player has been told, before a first sending to this provider, what is sent and where. */
  fun told(p: Provider) = prefs.getBoolean("told.${p.id}", false)

  fun setTold(p: Provider) = prefs.edit().putBoolean("told.${p.id}", true).apply()

  private fun secret(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    gen.init(
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build(),
    )
    return gen.generateKey()
  }

  companion object {
    /** The preferences' file (coach_keys.xml): the backup rules name it. */
    const val FILE = "coach_keys"
    private const val ALIAS = "coach_keys"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV = 12
  }
}
