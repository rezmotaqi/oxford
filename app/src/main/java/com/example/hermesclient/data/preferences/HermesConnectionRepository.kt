package com.example.hermesclient.data.preferences

import com.example.hermesclient.BuildConfig
import com.example.hermesclient.core.network.ConnectionRuntime
import com.example.hermesclient.core.network.HermesErrorMapper
import com.example.hermesclient.core.network.UrlNormalizer
import com.example.hermesclient.domain.model.HermesCapabilities
import com.example.hermesclient.domain.model.HermesConnectionConfig
import com.example.hermesclient.domain.model.HermesError
import com.example.hermesclient.domain.repository.AgentBackend
import com.example.hermesclient.domain.repository.ConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class HermesConnectionRepository @Inject constructor(
    private val backend: AgentBackend,
    private val configStore: ConnectionConfigStore,
    private val urlNormalizer: UrlNormalizer,
    private val connectionRuntime: ConnectionRuntime,
    private val errorMapper: HermesErrorMapper,
) : ConnectionRepository {
    override suspend fun getSavedConfig(): HermesConnectionConfig? = executeOrThrow {
        configStore.read().also(connectionRuntime::update)
    }

    override suspend fun testAndSave(
        baseUrl: String,
        apiKey: String,
    ): Result<HermesCapabilities> {
        val normalizedUrl = urlNormalizer.normalize(
            input = baseUrl,
            allowCleartext = BuildConfig.DEBUG,
        ).getOrElse { return Result.failure(errorMapper.map(it)) }

        val effectiveApiKey = apiKey.takeIf(String::isNotBlank)
            ?: try {
                configStore.read()?.apiKey?.takeIf(String::isNotBlank)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return Result.failure(errorMapper.map(error))
            }
            ?: return Result.failure(HermesError.MissingApiKey)

        val config = HermesConnectionConfig(
            baseUrl = normalizedUrl,
            apiKey = effectiveApiKey,
        )
        val testResult = try {
            backend.testConnection(config)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(errorMapper.map(error))
        }

        return testResult.fold(
            onSuccess = { capabilities ->
                val capabilityError = when {
                    !capabilities.sessionsSupported -> HermesError.SessionsUnsupported
                    !capabilities.sessionStreamingSupported -> HermesError.SessionStreamingUnsupported
                    else -> null
                }
                if (capabilityError != null) {
                    return@fold Result.failure(capabilityError)
                }
                try {
                    configStore.save(config)
                    connectionRuntime.update(config)
                    Result.success(capabilities)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Result.failure(errorMapper.map(error))
                }
            },
            onFailure = { error -> Result.failure(errorMapper.map(error)) },
        )
    }

    private suspend fun <T> executeOrThrow(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw errorMapper.map(error)
    }
}
