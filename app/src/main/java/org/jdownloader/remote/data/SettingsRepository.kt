package org.jdownloader.remote.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.jdownloader.remote.domain.*

interface SessionStorage {
    suspend fun saveSession(value: Session?)
    suspend fun loadSession(): Session?
    suspend fun saveDevice(id: String)
    suspend fun reserveRequestId(candidate: Long): Long = candidate
}
private val Context.store by preferencesDataStore("preferences")
@Singleton class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) : SessionStorage {
    private val theme = stringPreferencesKey("theme")
    private val dynamic = booleanPreferencesKey("dynamic")
    private val locale = stringPreferencesKey("locale")
    private val cnl = booleanPreferencesKey("cnl")
    private val session = stringPreferencesKey("session")
    private val selected = stringPreferencesKey("device")
    private val requestId = longPreferencesKey("last_request_id")
    val settings = context.store.data.map { p -> Settings(
        runCatching { ThemeMode.valueOf(p[theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        p[dynamic] ?: true, p[locale].orEmpty(), p[cnl] ?: false) }
    suspend fun update(value: Settings) { context.store.edit { it[theme] = value.theme.name; it[dynamic] = value.dynamic; it[locale] = value.locale; it[cnl] = value.cnl } }
    @Synchronized private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("jd-session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("jd-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override suspend fun saveSession(value: Session?) {
        if (value == null) { context.store.edit { it.remove(session); it.remove(selected) }; return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(Json.encodeToString(Session.serializer(), value).toByteArray())
        context.store.edit { it[session] = Base64.getEncoder().encodeToString(cipher.iv + bytes) }
    }
    override suspend fun loadSession(): Session? {
        val encoded = context.store.data.first()[session] ?: return null
        return try {
            val bytes = Base64.getDecoder().decode(encoded)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            Json.decodeFromString(Session.serializer(), String(cipher.doFinal(bytes.copyOfRange(12, bytes.size))))
        } catch (_: Exception) { saveSession(null); null }
    }
    override suspend fun saveDevice(id: String) { context.store.edit { it[selected] = id } }
    override suspend fun reserveRequestId(candidate: Long): Long {
        var reserved = candidate
        context.store.edit { p -> reserved = maxOf(candidate, (p[requestId] ?: 0) + 1); p[requestId] = reserved }
        return reserved
    }
    suspend fun deviceId() = context.store.data.first()[selected]
}
