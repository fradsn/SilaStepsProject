package com.example.myapplication.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface AwsApiService {

    // 1. ENDPOINT PER LE MISURAZIONI GENERALI AGGREGATE (Ripristinato da Git originale)
    @POST("v1/data")
    suspend fun uploadRecords(
        @Header("x-api-key") apiKey: String,
        @Body payload: AwsSyncPayload
    ): Response<ResponseBody>

    // 2. NUOVO ENDPOINT PER LE MEDIE DELLE FINESTRE RAW SHIMMER (Fisso a 5 minuti)
    @POST("v1/rawdata")
    suspend fun uploadRawRecords(
        @Header("x-api-key") apiKey: String,
        @Body payload: AwsRawSyncPayload
    ): Response<ResponseBody>
}