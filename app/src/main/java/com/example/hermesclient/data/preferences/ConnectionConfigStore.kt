package com.example.hermesclient.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.hermesclient.core.security.ApiKeyStore
import com.example.hermesclient.domain.model.HermesConnectionConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.connectionDataStore by preferencesDataStore(name = "connection_settings")

@Singleton
class ConnectionConfigStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val apiKeyStore: ApiKeyStore,
) {
    suspend fun read(): HermesConnectionConfig? {
        val preferences = try {
            context.connectionDataStore.data.first()
        } catch (_: IOException) {
            return null
        }
        val baseUrl = preferences[BASE_URL]?.takeIf(String::isNotBlank) ?: return null

        return HermesConnectionConfig(
            baseUrl = baseUrl,
            apiKey = apiKeyStore.read().orEmpty(),
        )
    }

    suspend fun save(config: HermesConnectionConfig) {
        require(config.baseUrl.isNotBlank()) { "Base URL must not be blank" }

        if (config.apiKey.isNotBlank()) {
            apiKeyStore.save(config.apiKey)
        }
        context.connectionDataStore.edit { preferences ->
            preferences[BASE_URL] = config.baseUrl.trimEnd('/')
        }
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
    }
}
