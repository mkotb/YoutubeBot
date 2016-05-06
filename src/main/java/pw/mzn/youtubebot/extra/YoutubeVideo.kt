package pw.mzn.youtubebot.extra

import org.json.JSONObject
import pro.zackpollard.telegrambot.api.chat.message.send.InputFile
import pro.zackpollard.telegrambot.api.chat.message.send.SendableAudioMessage
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.properties.Delegates

data class YoutubeVideo(val id: String, val file: File, val owningPlaylist: YoutubePlaylist? = null) {
    var metadata: VideoMetadata by Delegates.notNull()
    var customTitle: String? = null
    var customPerformer: String? = null

    fun fetchMetadata(): YoutubeVideo {
        var mdJson = JSONObject(Files.readAllLines(Paths.get(file.absolutePath.replace("$id.mp3", "$id.info.json")))
                .joinToString(""))
        var likeCount = 0
        var dislikeCount = 0

        if (mdJson.get("like_count") is Int) {
            likeCount = mdJson.getInt("like_count")
        }

        if (mdJson.get("dislike_count") is Int) {
            dislikeCount = mdJson.getInt("dislike_count")
        }

        metadata = VideoMetadata(mdJson.getString("fulltitle"), mdJson.getInt("duration"),
                mdJson.getString("thumbnail"), mdJson.getLong("view_count"), likeCount,
                dislikeCount, mdJson.getString("uploader"), mdJson.getString("webpage_url"),
                this)
        return this
    }

    fun sendable(): SendableAudioMessage.SendableAudioMessageBuilder {
        var builder = SendableAudioMessage.builder()
                .title(metadata.name)
                .performer(metadata.uploader)
                .audio(InputFile(file))
                .duration(metadata.duration)

        if (customTitle != null)
            builder.title(customTitle)

        if (customPerformer != null)
            builder.performer(customPerformer)

        return builder
    }
}

data class VideoMetadata(val name: String, val duration: Int, val thumbnailLink: String,
                         val viewCount: Long, val likes: Int, val dislikes: Int,
                         val uploader: String, val url: String, val video: YoutubeVideo)