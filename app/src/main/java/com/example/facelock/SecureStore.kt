package com.example.facelock

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * All persistent data (face templates + protected package list) lives in
 * Keystore-backed EncryptedSharedPreferences (AES-256-GCM values, AES-256-SIV keys).
 * Only geometry vectors are stored - never camera images.
 */
object SecureStore {
    @Volatile private var prefsCache: SharedPreferences? = null
    @Volatile private var protectedCache: Set<String>? = null
    @Volatile private var hasFaceCache: Boolean? = null

    private fun prefs(ctx: Context): SharedPreferences =
        prefsCache ?: synchronized(this) {
            prefsCache ?: run {
                val app = ctx.applicationContext
                val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    app, "facelock_secure", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { prefsCache = it }
            }
        }

    // ---- Protected apps ----
    fun getProtected(ctx: Context): Set<String> =
        protectedCache ?: (prefs(ctx).getStringSet("protected", emptySet()) ?: emptySet())
            .toSet().also { protectedCache = it }

    fun setProtected(ctx: Context, pkg: String, on: Boolean) {
        val next = getProtected(ctx).toMutableSet().apply { if (on) add(pkg) else remove(pkg) }
        prefs(ctx).edit().putStringSet("protected", next).commit()
        protectedCache = next
    }

    // ---- Face templates ----
    fun saveTemplates(ctx: Context, templates: List<FloatArray>) {
        val encoded = templates.joinToString(";") { arr ->
            val bb = ByteBuffer.allocate(arr.size * 4)
            arr.forEach { bb.putFloat(it) }
            Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        }
        prefs(ctx).edit().putString("templates", encoded).commit()
        hasFaceCache = templates.isNotEmpty()
    }

    fun loadTemplates(ctx: Context): List<FloatArray> {
        val s = prefs(ctx).getString("templates", null) ?: return emptyList()
        return s.split(";").filter { it.isNotEmpty() }.map {
            val bb = ByteBuffer.wrap(Base64.decode(it, Base64.NO_WRAP))
            FloatArray(bb.remaining() / 4) { bb.getFloat() }
        }
    }

    fun hasFace(ctx: Context): Boolean =
        hasFaceCache ?: loadTemplates(ctx).isNotEmpty().also { hasFaceCache = it }
    fun addTemplates(ctx: Context, more: List<FloatArray>) = saveTemplates(ctx, loadTemplates(ctx) + more)
    fun clearFaces(ctx: Context) = saveTemplates(ctx, emptyList())

    // ---- App password: PBKDF2-SHA256, random salt, 120k iterations (never stored in plain text) ----
    fun hasPassword(ctx: Context) = prefs(ctx).contains("pw_hash")

    fun setPassword(ctx: Context, pw: CharArray) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString("pw_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pw_hash", Base64.encodeToString(hash(pw, salt), Base64.NO_WRAP))
            .commit()
    }

    fun verifyPassword(ctx: Context, pw: CharArray): Boolean {
        val salt = Base64.decode(prefs(ctx).getString("pw_salt", null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(prefs(ctx).getString("pw_hash", null) ?: return false, Base64.NO_WRAP)
        return MessageDigest.isEqual(hash(pw, salt), expected)
    }

    private fun hash(pw: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw, salt, 120_000, 256)).encoded
}
