package com.example.hermesclient.data.remote.api

import com.example.hermesclient.data.remote.dto.CapabilitiesDto
import com.example.hermesclient.data.remote.dto.CreateSessionRequestDto
import com.example.hermesclient.data.remote.dto.HealthDto
import com.example.hermesclient.data.remote.dto.MessageListDto
import com.example.hermesclient.data.remote.dto.ApprovalResponseDto
import com.example.hermesclient.data.remote.dto.ApprovalResponseRequestDto
import com.example.hermesclient.data.remote.dto.RunCreatedDto
import com.example.hermesclient.data.remote.dto.RunRequestDto
import com.example.hermesclient.data.remote.dto.StopRunDto
import com.example.hermesclient.data.remote.dto.SessionEnvelopeDto
import com.example.hermesclient.data.remote.dto.SessionListDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

interface HermesApi {
    @GET
    suspend fun health(@Url url: String): HealthDto

    @GET
    suspend fun capabilities(@Url url: String): CapabilitiesDto

    @GET
    suspend fun sessions(
        @Url url: String,
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
    ): SessionListDto

    @POST
    suspend fun createSession(
        @Url url: String,
        @Body request: CreateSessionRequestDto = CreateSessionRequestDto(),
    ): SessionEnvelopeDto

    @GET
    suspend fun session(@Url url: String): SessionEnvelopeDto

    @GET
    suspend fun messages(
        @Url url: String,
        @Query("limit") limit: Int = 500,
        @Query("order") order: String = "latest",
    ): MessageListDto

    @POST
    suspend fun createRun(
        @Url url: String,
        @Body request: RunRequestDto,
    ): RunCreatedDto

    @POST
    suspend fun respondToApproval(
        @Url url: String,
        @Body request: ApprovalResponseRequestDto,
    ): ApprovalResponseDto

    @POST
    suspend fun stopRun(@Url url: String): StopRunDto
}
