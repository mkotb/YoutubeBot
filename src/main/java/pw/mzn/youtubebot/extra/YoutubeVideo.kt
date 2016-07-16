/*
 * Copyright (c) 2016, Mazen Kotb, mazenkotb@gmail.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package pw.mzn.youtubebot.extra

import org.json.JSONObject
import pro.zackpollard.telegrambot.api.chat.message.send.InputFile
import pro.zackpollard.telegrambot.api.chat.message.send.SendableAudioMessage
import pw.mzn.youtubebot.YoutubeBot
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.properties.Delegates

data class YoutubeVideo(val id: String, val file: File, val owningPlaylist: YoutubePlaylist? = null,
                        var fileId: String? = null) {
    var metadata: VideoMetadata by Delegates.notNull()
    var customTitle: String? = null
    var customPerformer: String? = null
    var customLength: Long? = null

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
                .duration(metadata.duration)

        if (fileId != null) {
            builder.audio(InputFile(fileId))
        } else {
            builder.audio(InputFile(file))
        }

        if (customTitle != null)
            builder.title(customTitle)

        if (customPerformer != null)
            builder.performer(customPerformer)

        return builder
    }

    fun formattedName(instance: YoutubeBot): String {
        return "$customPerformer - $customTitle " +
                "[${instance.formatTime(customLength!!)}]"
    }
}

data class VideoMetadata(val name: String, val duration: Int, val thumbnailLink: String,
                         val viewCount: Long, val likes: Int, val dislikes: Int,
                         val uploader: String, val url: String, val video: YoutubeVideo)