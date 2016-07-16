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

import com.wrapper.spotify.models.PlaylistTrack
import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.message.Message
import pw.mzn.youtubebot.IdList
import pw.mzn.youtubebot.Track
import pw.mzn.youtubebot.YoutubeBot

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
    var videoId: String

    init {
        var regex = instance.videoRegex.matcher(link)
        regex.matches()
        videoId = regex.group(1)
    }
}

data class SpotifyDownloadSession(val chat: Chat, val tracks: MutableList<PlaylistTrack>)
data class TrackSession(val videoSession: VideoSession, var track: Track, var stage: String = "i")
data class MatchSession(val videoSession: VideoSession, var videoId: String, val selections: IdList<String>, var messageId: Long = -1)
data class SearchSession(val idList: IdList<CachedYoutubeVideo>, val botMessageId: Long, val chat: Chat, val originalQuery: Message)

data class CachedYoutubeVideo(val videoId: String, val title: String, val thumb: String, val description: String)
data class CachedYoutubeChannel(val channelId: String, val channelTitle: String)
