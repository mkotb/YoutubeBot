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
package pw.mzn.youtubebot.handler

import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.event.Listener
import pro.zackpollard.telegrambot.api.event.chat.message.PhotoMessageReceivedEvent
import pw.mzn.youtubebot.extra.VideoSession
import pw.mzn.youtubebot.YoutubeBot
import java.io.File

class PhotoHandler(val instance: YoutubeBot): Listener {
    override fun onPhotoMessageReceived(event: PhotoMessageReceivedEvent?) {
        var videoEntry = instance.command.video.videoSessions.map.entries.filter { e -> e.value.chatId.equals(event!!.chat.id) }
                .firstOrNull()

        if (videoEntry != null) {
            processVideoThumbnail(event!!, videoEntry)
            return
        }
    }

    fun processVideoThumbnail(event: PhotoMessageReceivedEvent,
                              entry: MutableMap.MutableEntry<Int, VideoSession>) {
        var session = entry.value

        if (!session.pendingImage) {
            return
        }

        var content = event.content.content

        if (content.size < 1) {
            return
        }

        content[content.size - 1].downloadFile(instance.bot, File("${session.videoId}.jpg"))
        event.chat.sendMessage(SendableTextMessage.builder()
                .replyTo(event.message)
                .message("Updated!").build())

        session.pendingImage = false
        session.thumbnail = "N/A"
        session.options.thumbnailUrl = "N/A"
        session.options.thumbnail = true

        instance.bot.editMessageReplyMarkup(session.chatId, session.botMessageId, instance.command.video.videoKeyboardFor(entry.key))
    }
}