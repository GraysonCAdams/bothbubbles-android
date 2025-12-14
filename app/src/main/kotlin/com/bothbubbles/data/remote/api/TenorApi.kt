package com.bothbubbles.data.remote.api

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Tenor GIF API v2 interface.
 * https://developers.google.com/tenor/guides/quickstart
 */
interface TenorApi {

    @GET("v2/featured")
    suspend fun getFeatured(
        @Query("key") apiKey: String,
        @Query("limit") limit: Int = 20,
        @Query("pos") position: String? = null,
        @Query("media_filter") mediaFilter: String = "gif,tinygif"
    ): TenorSearchResponse

    @GET("v2/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("key") apiKey: String,
        @Query("limit") limit: Int = 20,
        @Query("pos") position: String? = null,
        @Query("media_filter") mediaFilter: String = "gif,tinygif"
    ): TenorSearchResponse

    companion object {
        const val BASE_URL = "https://tenor.googleapis.com/"
    }
}

data class TenorSearchResponse(
    val results: List<TenorGif>,
    val next: String? = null
)

data class TenorGif(
    val id: String,
    val title: String,
    val media_formats: Map<String, TenorMediaFormat>,
    val content_description: String? = null
)

data class TenorMediaFormat(
    val url: String,
    val dims: List<Int>? = null,
    val size: Long? = null
)
