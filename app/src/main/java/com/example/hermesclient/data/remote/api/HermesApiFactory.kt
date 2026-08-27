package com.example.hermesclient.data.remote.api

import com.example.hermesclient.core.network.ApiKeyProvider
import com.example.hermesclient.core.network.HermesHttpClientFactory
import javax.inject.Inject
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class HermesApiFactory @Inject constructor(
    private val json: Json,
    private val httpClientFactory: HermesHttpClientFactory,
) {
    fun create(apiKeyProvider: ApiKeyProvider): HermesApi = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(httpClientFactory.create(apiKeyProvider))
        .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
        .build()
        .create(HermesApi::class.java)

    private companion object {
        const val PLACEHOLDER_BASE_URL = "https://localhost/"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
