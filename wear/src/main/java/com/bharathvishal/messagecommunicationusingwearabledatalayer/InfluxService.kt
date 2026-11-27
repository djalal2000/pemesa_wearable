package com.bharathvishal.messagecommunicationusingwearabledatalayer

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface InfluxService {
    @POST("api/v2/write")
    suspend fun sendLineProtocol(
        @Query("org") org: String,
        @Query("bucket") bucket: String,
        @Query("precision") precision: String,
        @Body body: RequestBody
    ): Response<ResponseBody>
}
