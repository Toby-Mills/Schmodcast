package com.schmodcast.data.remote

import com.schmodcast.data.model.Podcast
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

interface ItunesSearchApi {
    @GET("search")
    suspend fun searchPodcasts(
        @Query("term") term: String,
        @Query("media") media: String = "podcast",
        @Query("entity") entity: String = "podcast",
        @Query("limit") limit: Int = 25,
    ): ItunesSearchResponse
}

@Serializable
data class ItunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ItunesPodcastDto> = emptyList(),
)

@Serializable
data class ItunesPodcastDto(
    @SerialName("collectionId") val collectionId: Long,
    @SerialName("collectionName") val collectionName: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("artworkUrl100") val artworkUrl100: String? = null,
    @SerialName("feedUrl") val feedUrl: String? = null,
)

// The directory lists episodes and other collection types alongside podcasts;
// entries without a feed can't be subscribed to, so they're dropped here.
fun ItunesPodcastDto.toDomainOrNull(): Podcast? {
    val feed = feedUrl ?: return null
    return Podcast(
        id = collectionId,
        title = collectionName ?: "Untitled podcast",
        author = artistName ?: "Unknown",
        artworkUrl = artworkUrl100.orEmpty(),
        feedUrl = feed,
    )
}
