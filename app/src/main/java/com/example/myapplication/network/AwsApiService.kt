package com.example.myapplication.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface AwsApiService {
    @POST("v1/data")
    suspend fun uploadRecords(
        @Header("x-api-key") apiKey: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body payload: AwsSyncPayload
    ): Response<ResponseBody>
}