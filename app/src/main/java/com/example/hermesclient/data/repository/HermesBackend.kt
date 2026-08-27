package com.example.hermesclient.data.repository

import com.example.hermesclient.core.network.ApiKeyProvider
import com.example.hermesclient.core.network.EndpointResolver
import com.example.hermesclient.core.network.HermesErrorMapper
import com.example.hermesclient.data.remote.api.HermesApiFactory
import com.example.hermesclient.data.remote.mapper.toDomain
import com.example.hermesclient.domain.model.HermesCapabilities
import com.example.hermesclient.domain.model.HermesConnectionConfig
import com.example.hermesclient.domain.model.HermesError
import com.example.hermesclient.domain.repository.AgentBackend
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class HermesBackend @Inject constructor(
    private val apiFactory: HermesApiFactory,
    private val endpointResolver: EndpointResolver,
    private val errorMapper: HermesErrorMapper,
) : AgentBackend {
    override suspend fun testConnection(config: HermesConnectionConfig): Result<HermesCapabilities> = try {
        val api = apiFactory.create(ApiKeyProvider { config.apiKey })
        val health = api.health(endpointResolver.resolve(config.baseUrl, "health"))
        if (health.status != "ok") throw HermesError.UnsupportedServer

        val capabilities = api.capabilities(
            endpointResolver.resolve(config.baseUrl, "v1/capabilities"),
        ).toDomain()
        Result.success(capabilities)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(errorMapper.map(error))
    }
}
