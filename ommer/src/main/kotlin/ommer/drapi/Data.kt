package ommer.drapi

import org.http4k.core.Uri

data class Group(
    val limit: Int,
    val offset: Int,
    val totalSize: Int,
    val next: Uri?,
    val title: String,
    val groupId: String,
    val items: List<Item>,
)

data class Show(
    val categories: List<String>,
//    val channel: String?,
    val description: String,
//    val explicitContent: String?,
//    val groupingType: String?,
    val id: String,
    val imageAssets: List<ImageAsset>,
    val isAvailableOnDemand: String?,
    val latestEpisodeStartTime: String,
    val learnId: String?,
    val numberOfEpisodes: String?,
    val ocsUrn: String?,
    val podcastUrl: String?,
    val presentationType: String?,
    val presentationUrl: String,
    val productionNumber: String?,
    val psdbSlug: String?,
    val psdbUrn: String?,
    val punchline: String?,
    val slug: String?,
    val sortLetter: String?,
    val title: String,
    val type: String?,
//    val visualIdentity: String?,
)

data class ImageAsset(
    val id: String,
    val target: String,
    val ratio: String,
    val format: String,
)

data class Episodes(
    val items: List<Item>,
    val limit: Int,
    val offset: Int,
    val previous: Uri?,
    val next: Uri?,
    val self: Uri?,
    val totalSiz: Int,
)

data class Item(
    val id: String,
    val durationMilliseconds: Long,
    val productionNumber: String,
    val audioAssets: List<AudioAsset>,
    val title: String,
    val description: String,
    val presentationUrl: Uri,
    val publishTime: String,
)

data class AudioAsset(
    val target: String,
    val isStreamLive: Boolean,
    val format: String,
    val bitrate: Int,
    val fileSize: Long,
    val url: Uri,
)
