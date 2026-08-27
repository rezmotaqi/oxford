package com.example.hermesclient.core.network

import com.example.hermesclient.domain.model.HermesConnectionConfig
import com.example.hermesclient.domain.model.HermesError
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.HttpException

class UrlNormalizer @Inject constructor() {
    fun normalize(input: String, allowCleartext: Boolean): Result<String> = runCatching {
        val value = input.trim()
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase() ?: throw HermesError.InvalidUrl
        if (scheme != "https" && scheme != "http") throw HermesError.InvalidUrl
        if (scheme == "http" && !allowCleartext) throw HermesError.InvalidUrl
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            throw HermesError.InvalidUrl
        }
        val normalizedPath = uri.path.orEmpty().trimEnd('/')
        URI(scheme, uri.authority, normalizedPath.ifEmpty { null }, null, null).toString().trimEnd('/')
    }.recoverCatching { error ->
        throw if (error is HermesError) error else HermesError.InvalidUrl
    }
}

@Singleton
class ConnectionRuntime @Inject constructor() {
    private val current = AtomicReference<HermesConnectionConfig?>(null)

    fun update(config: HermesConnectionConfig?) {
        current.set(config)
    }

    fun config(): HermesConnectionConfig? = current.get()
}

class EndpointResolver @Inject constructor() {
    fun resolve(baseUrl: String, path: String): String {
        val root = "${baseUrl.trimEnd('/')}/".toHttpUrl()
        return requireNotNull(root.resolve(path.removePrefix("/"))) { "Invalid Hermes endpoint" }.toString()
    }

    fun resolveSegments(baseUrl: String, vararg segments: String): String {
        val builder = "${baseUrl.trimEnd('/')}/".toHttpUrl().newBuilder()
        segments.forEach(builder::addPathSegment)
        return builder.build().toString()
    }
}

fun interface ApiKeyProvider {
    fun apiKey(): String?
}

class AuthenticationInterceptor @Inject constructor(
    private val apiKeyProvider: ApiKeyProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val key = apiKeyProvider.apiKey()?.takeIf(String::isNotBlank)
        val request = if (key == null || chain.request().header(AUTHORIZATION) != null) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header(AUTHORIZATION, "Bearer $key")
                .build()
        }
        return chain.proceed(request)
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}

class HermesHttpClientFactory @Inject constructor() {
    fun create(apiKeyProvider: ApiKeyProvider): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(AuthenticationInterceptor(apiKeyProvider))
        .build()

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 90L
        const val WRITE_TIMEOUT_SECONDS = 30L
    }
}

class HermesTransportException(
    val statusCode: Int?,
    cause: Throwable? = null,
) : IOException(cause)

class HermesErrorMapper @Inject constructor() {
    fun map(error: Throwable): HermesError = when (error) {
        is HermesError -> error
        is HttpException -> fromStatus(error.code())
        is HermesTransportException -> error.statusCode?.let(::fromStatus) ?: fromIo(error)
        is SocketTimeoutException -> HermesError.Timeout
        is UnknownHostException, is ConnectException -> HermesError.Unreachable
        is IOException -> fromIo(error)
        else -> HermesError.Unknown(error)
    }

    private fun fromStatus(statusCode: Int): HermesError = when (statusCode) {
        401, 403 -> HermesError.Unauthorized
        else -> HermesError.ServerError(statusCode)
    }

    private fun fromIo(error: IOException): HermesError = when (error.cause) {
        is SocketTimeoutException -> HermesError.Timeout
        is UnknownHostException, is ConnectException -> HermesError.Unreachable
        else -> HermesError.Unreachable
    }
}
