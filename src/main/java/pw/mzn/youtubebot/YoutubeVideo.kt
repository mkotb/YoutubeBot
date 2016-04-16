package pw.mzn.youtubebot

import org.json.JSONObject
import pro.zackpollard.telegrambot.api.chat.message.send.InputFile
import pro.zackpollard.telegrambot.api.chat.message.send.SendableAudioMessage
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.properties.Delegates

data class YoutubeVideo(val id: String, val file: File, val owningPlaylist: YoutubePlaylist? = null) {
    var metadata: VideoMetadata by Delegates.notNull()

    fun fetchMetadata(): YoutubeVideo {
        var mdJson = JSONObject(Files.readAllLines(Paths.get(file.absolutePath.replace("$id.mp3", "$id.info.json")))
                .joinToString(""))

        metadata = VideoMetadata(mdJson.getString("fulltitle"), mdJson.getInt("duration"),
                mdJson.getString("thumbnail"), mdJson.getLong("view_count"), mdJson.getInt("like_count"),
                mdJson.getInt("dislike_count"), mdJson.getString("uploader"), mdJson.getString("webpage_url"),
                this)
        return this
    }

    fun sendable(): SendableAudioMessage {
        return SendableAudioMessage.builder()
                .audio(InputFile(file))
                .title(metadata.name)
                .performer(metadata.uploader)
                .duration(metadata.duration)
                .build()
    }
}

data class VideoMetadata(val name: String, val duration: Int, val thumbnailLink: String,
                         val viewCount: Long, val likes: Int, val dislikes: Int,
                         val uploader: String, val url: String, val video: YoutubeVideo)