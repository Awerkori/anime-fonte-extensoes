package eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull

object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String = when (decoder) {
        is JsonDecoder -> decoder.decodeJsonElement().let { if (it is JsonNull) "" else it.toString().trim('"') }
        else -> decoder.decodeString()
    }
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
data class GetAppDetailsResponse(
    @SerialName("SINGSALT") val singsalt: String = "",
    @SerialName("ARRAYPADRAO") val arrayPadrao: String = "",
)

@Serializable
data class GetHomeVideosResponse(
    @SerialName("most_viewed") val mostViewed: List<MostViewed> = emptyList(),
    @SerialName("latest_video") val latestVideo: List<LatestVideo> = emptyList(),
    @SerialName("latest_video_dub") val latestVideoDub: List<LatestVideo> = emptyList(),
    @SerialName("all_video_cat") val allVideoCat: List<AllVideoCat> = emptyList(),
) {
    @Serializable
    data class MostViewed(
        val cid: String = "",
        @SerialName("category_name") val categoryName: String = "",
        val genero: String = "",
        val sinopse: String = "",
        @SerialName("category_image") val categoryImage: String = "",
        val tid: String = "",
        @SerialName("is_temporada") val isTemporada: Boolean = false,
    )

    @Serializable
    data class LatestVideo(
        val id: String = "",
        @SerialName("video_title") val videoTitle: String = "",
        @SerialName("video_thumbnail_b") val videoThumbnailB: String = "",
        @SerialName("category_name") val categoryName: String = "",
        @Serializable(with = FlexibleStringSerializer::class) @SerialName("cat_id") val catId: String = "",
    )

    @Serializable
    data class AllVideoCat(
        val cid: String = "",
        @SerialName("category_name") val categoryName: String = "",
        val sinopse: String = "",
        @SerialName("category_image") val categoryImage: String = "",
        val tid: String = "",
    )
}

@Serializable
data class CategoryFullItemDto(
    @Serializable(with = FlexibleStringSerializer::class) val cid: String = "",
    @SerialName("category_image") val categoryImage: String = "",
    @SerialName("category_image_thumb") val categoryImageThumb: String = "",
)

@Serializable
data class SearchVideoItemDto(
    val cid: String = "",
    @SerialName("category_name") val categoryName: String = "",
    val genero: String = "",
    val sinopse: String = "",
    @SerialName("audio_type") val audioType: String = "",
    @SerialName("category_image") val categoryImage: String = "",
    @SerialName("category_image_thumb") val categoryImageThumb: String = "",
    @SerialName("is_temporada") val isTemporada: Boolean = false,
    val tid: String = "",
    @SerialName("temp_name") val tempName: String = "",
)

@Serializable
data class SingleVideoItemDto(
    @Serializable(with = FlexibleStringSerializer::class) val id: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("video_id") val videoId: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("rel_vid") val relVid: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("cat_id") val catId: String = "",
    @SerialName("category_name") val categoryName: String = "",
    @SerialName("video_title") val videoTitle: String = "",
    @SerialName("video_description") val videoDescription: String = "",
    @SerialName("video_thumbnail_b") val videoThumbnailB: String = "",
    @SerialName("video_thumbnail_s") val videoThumbnailS: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("video_url_fhd") val videoUrlFhd: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("video_url_sd") val videoUrlSd: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("video_url") val videoUrl: String = "",
    @SerialName("video_type") val videoType: String = "",
    @SerialName("video_type_fhd") val videoTypeFhd: String = "",
    @SerialName("video_ep") val videoEp: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("temp_id") val tempId: String = "",
    @SerialName("temp_name") val tempName: String = "",
    @SerialName("temp_image") val tempImage: String = "",
    @SerialName("temp") val seasons: List<SeasonDto> = emptyList(),
)

@Serializable
data class SeasonDto(
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("temp_id") val tempId: String = "",
    @SerialName("temp_name") val tempName: String = "",
)

@Serializable
data class VideoByCatItemDto(
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("id") val id: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("video_id") val videoId: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("rel_vid") val relVid: String = "",
    @SerialName("video_title") val videoTitle: String = "",
    @SerialName("video_ep") val videoEp: String = "",
    @SerialName("video_type") val videoType: String = "",
    @SerialName("video_type_fhd") val videoTypeFhd: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("video_url") val videoUrl: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("temp_id") val tempId: String = "",
    @SerialName("temp_name") val tempName: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("cat_id") val catId: String = "",
)
