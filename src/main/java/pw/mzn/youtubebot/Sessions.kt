package pw.mzn.youtubebot

import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.message.Message

data class CommandSession(val videoMatch: String, val playlistMatch: String, val chat: Chat,
                          val userId: Long, val originalMessage: Message, val playlistVideos: Long,
                          val duration: Long)

data class PlaylistSession(val chatId: String, val options: PlaylistOptions = PlaylistOptions(),
                           val chat: Chat, val link: String, var selecting: String = "N/A",
                           val userId: Long, val videoCount: Long)

data class VideoSession(val instance: YoutubeBot, val chatId: String, val link: String, val options: VideoOptions = VideoOptions(),
                        val chat: Chat, val linkSent: Boolean, val userId: Long,
                        val originalQuery: Message?, val duration: Long, var thumbnail: String = "N/A", var selecting: String = "N/A",
                        var botMessageId: Long = -1L, var pendingImage: Boolean = false) {
    val videoId: String

    init {
        var regex = instance.videoRegex.matcher(link)
        regex.matches()
        videoId = regex.group(1)
    }
}

data class CachedYoutubeVideo(val videoId: String, val title: String)
