package app.bosunarr.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * App state (services with their API keys/passwords, dashboard layout, appearance) in one DataStore file,
 * encrypted with AES-256-GCM. The key lives in the Android Keystore (hardware-backed where available), so the
 * file is useless off this device. Excluded from backups (see data_extraction_rules.xml).
 */
class SecureStore(context: Context) {
    private val aead: Aead = run {
        AeadConfig.register()
        fun build() = AndroidKeysetManager.Builder()
            .withSharedPref(context, "bosunarr_keyset", "bosunarr_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://bosunarr_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        try {
            build()
        } catch (e: Exception) {
            // The Keystore key was lost or invalidated (happens on some devices after a lock-screen reset):
            // the saved state can't be decrypted any more, so start fresh rather than crash on every launch.
            context.getSharedPreferences("bosunarr_keyset_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("bosunarr_master_key") }
            File(context.filesDir, "datastore/state.enc").delete()
            build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val associated = "bosunarr-state-v1".toByteArray()

    private val serializer = object : Serializer<AppState> {
        override val defaultValue = AppState()
        override suspend fun readFrom(input: InputStream): AppState {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return defaultValue
            return try {
                json.decodeFromString(AppState.serializer(), aead.decrypt(bytes, associated).decodeToString())
            } catch (e: SerializationException) {
                throw CorruptionException("Unreadable app state", e)
            } catch (e: java.security.GeneralSecurityException) {
                throw CorruptionException("App state can't be decrypted", e)
            }
        }
        override suspend fun writeTo(t: AppState, output: OutputStream) {
            output.write(aead.encrypt(json.encodeToString(AppState.serializer(), t).toByteArray(), associated))
        }
    }

    private val store: DataStore<AppState> = DataStoreFactory.create(
        serializer = serializer,
        // A corrupted or undecryptable file (e.g. keystore reset) starts fresh rather than crashing.
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { AppState() },
        produceFile = { File(context.filesDir, "datastore/state.enc") },
    )

    val state: Flow<AppState> = store.data

    suspend fun update(transform: (AppState) -> AppState) {
        store.updateData(transform)
    }
}
