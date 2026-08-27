package com.example.hermesclient.app.di

import com.example.hermesclient.core.network.ApiKeyProvider
import com.example.hermesclient.core.network.ConnectionRuntime
import com.example.hermesclient.core.network.HermesHttpClientFactory
import com.example.hermesclient.core.security.AndroidKeystoreApiKeyStore
import com.example.hermesclient.core.security.ApiKeyStore
import com.example.hermesclient.data.remote.api.HermesApi
import com.example.hermesclient.data.remote.api.HermesApiFactory
import com.example.hermesclient.data.remote.sse.DefaultHermesEventParser
import com.example.hermesclient.data.remote.sse.HermesEventParser
import com.example.hermesclient.data.remote.sse.HermesSseDataSource
import com.example.hermesclient.data.remote.sse.OkHttpHermesSseDataSource
import com.example.hermesclient.data.repository.HermesBackend
import com.example.hermesclient.data.repository.HermesChatRepository
import com.example.hermesclient.data.repository.HermesSessionRepository
import com.example.hermesclient.data.preferences.HermesConnectionRepository
import com.example.hermesclient.domain.repository.AgentBackend
import com.example.hermesclient.domain.repository.ChatRepository
import com.example.hermesclient.domain.repository.ConnectionRepository
import com.example.hermesclient.domain.repository.SessionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class HermesBindingsModule {
    @Binds
    @Singleton
    abstract fun bindApiKeyStore(implementation: AndroidKeystoreApiKeyStore): ApiKeyStore

    @Binds
    @Singleton
    abstract fun bindAgentBackend(implementation: HermesBackend): AgentBackend

    @Binds
    @Singleton
    abstract fun bindConnectionRepository(
        implementation: HermesConnectionRepository,
    ): ConnectionRepository

    @Binds
    @Singleton
    abstract fun bindSessionRepository(implementation: HermesSessionRepository): SessionRepository

    @Binds
    @Singleton
    abstract fun bindChatRepository(implementation: HermesChatRepository): ChatRepository

    @Binds
    @Singleton
    abstract fun bindSseDataSource(implementation: OkHttpHermesSseDataSource): HermesSseDataSource

    @Binds
    @Singleton
    abstract fun bindEventParser(implementation: DefaultHermesEventParser): HermesEventParser
}

@Module
@InstallIn(SingletonComponent::class)
object HermesProvidesModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Provides
    @Singleton
    fun provideApiKeyProvider(connectionRuntime: ConnectionRuntime): ApiKeyProvider =
        ApiKeyProvider { connectionRuntime.config()?.apiKey }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        factory: HermesHttpClientFactory,
        apiKeyProvider: ApiKeyProvider,
    ): OkHttpClient = factory.create(apiKeyProvider)

    @Provides
    @Singleton
    fun provideHermesApi(
        factory: HermesApiFactory,
        apiKeyProvider: ApiKeyProvider,
    ): HermesApi = factory.create(apiKeyProvider)
}
