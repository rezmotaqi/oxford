package com.example.hermesclient.core.security

interface ApiKeyStore {
    suspend fun read(): String?

    suspend fun save(apiKey: String)
}
