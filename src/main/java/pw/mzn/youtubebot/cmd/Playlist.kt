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
package pw.mzn.youtubebot.cmd

import pro.zackpollard.telegrambot.api.chat.CallbackQuery
import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.message.content.TextContent
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.event.chat.message.TextMessageReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardButton
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardMarkup
import pro.zackpollard.telegrambot.api.keyboards.ReplyKeyboardHide
import pw.mzn.youtubebot.IdList
import pw.mzn.youtubebot.YoutubeBot
import pw.mzn.youtubebot.extra.PlaylistOptions
import pw.mzn.youtubebot.extra.PlaylistSession
import java.util.*

class PlaylistCommandHolder(val instance: YoutubeBot) {
    val playlistSessions = IdList<PlaylistSession>()

    fun processPlaylistMessage(event: TextMessageReceivedEvent?,
                               entry: MutableMap.MutableEntry<Int, PlaylistSession>) {
        var session = entry.value
        var content = event!!.message.content

        if (content !is TextContent) {
            return
        }

        if ("N/A".equals(session.selecting)) {
            return
        }

        if ("sn".equals(session.selecting)) {
            var selection = ArrayList<Int>()
            var numbers = content.content.split(" ")

            for(e in numbers) {
                if (selection.size == 50) {
                    break // no more than 50 selected videos
                }

                try {
                    var int = e.toInt()

                    if (int <= session.videoCount && int > 0) // not allow malicious selections
                        selection.add(int)
                } catch (ignored: Exception) {
                }
            }

            if (selection.isEmpty()) {
                event.chat.sendMessage("Selecting no videos...")
                removePlaylistSession(event.chat.id)
                return
            }

            session.options.videoSelection = selection
            sendPlaylist(session.chat, session.link, session.options, session.userId, session.videoCount)
        } else if ("sh".equals(session.selecting)) {
            session.options.matchRegex = content.content
            sendPlaylist(session.chat, session.link, session.options, session.userId, session.videoCount)
        }
    }

    fun sendPlaylist(chat: Chat, link: String, options: PlaylistOptions?, userId: Long, itemCount: Long) {
        if (options == null) {
            var id = playlistSessions.add(PlaylistSession(chat.id, PlaylistOptions(), chat, link, "N/A", userId, itemCount))
            var message = "How would you like to select the videos from this playlist?"
            var selectionKeyboard = InlineKeyboardMarkup.builder()
                    .addRow(InlineKeyboardButton.builder().text("Selection").callbackData("pl.sn.$id").build(),
                            InlineKeyboardButton.builder().text("Search").callbackData("pl.sh.$id").build())

            if (itemCount <= 50) {
                selectionKeyboard.addRow(InlineKeyboardButton.builder().text("All Videos").callbackData("pl.av.$id").build())
            } else {
                message += " Also note that you cannot select all the videos at this time because your playlist is greater than 50 videos long"
            }

            var response = SendableTextMessage.builder()
                    .message(message)
                    .replyMarkup(selectionKeyboard.build())
                    .build()

            chat.sendMessage(response) // next process of new data is in processPlaylistInline()
            return
        }

        var option = options

        instance.commandHandler.timeoutCache.put(userId, Object())
        chat.sendMessage(SendableTextMessage.builder()
                .message("Downloading all videos and extracting their audio... This will take a while.")
                .replyMarkup(ReplyKeyboardHide.builder().selective(true).build())
                .build())
        var regex = instance.playlistRegex.matcher(link)
        regex.matches()

        if (option.allVideos && itemCount > 10) {
            option.allVideos = false
            option.videoSelection = IntRange(1, itemCount.toInt()).toMutableList()
        }

        var playlist = instance.downloadPlaylist(option, regex.group(regex.groupCount()))

        chat.sendMessage("Finished processing! Sending ${playlist.videoList.size} videos...")

        for (video in playlist.videoList) {
            var md = chat.sendMessage(SendableTextMessage.builder()
                    .message(instance.command.video.descriptionFor(video, true))
                    .parseMode(ParseMode.MARKDOWN)
                    .build())
            chat.sendMessage(video.sendable().replyTo(md).build())
        }

        chat.sendMessage("Finished sending playlist!")
        instance.commandHandler.timeoutCache.invalidate(userId)
        playlist.folder.deleteRecursively() // bye bye
        removePlaylistSession(chat.id)
    }

    fun removePlaylistSession(chatId: String) {
        var playlistSession = playlistSessions.map.entries.filter { e -> e.value.chatId.equals(chatId) }
                .firstOrNull() ?: return
        playlistSessions.remove(playlistSession.value)
    }

    fun processPlaylistInline(callback: CallbackQuery, data: String) {
        var session = instance.inlineHandler.fetchSession(data, 2, 3, playlistSessions) ?: return // stop returning bad data
        var selection = data.split(".")[1]

        if ("av".equals(selection)) {
            callback.answer("Selecting...", false)
            session.options.allVideos = true
            sendPlaylist(session.chat, session.link, session.options, session.userId, session.videoCount)
        } else if ("sn".equals(selection)) {
            callback.answer("Please enter the numbers of the videos in the playlist you wish to select, separated by a space\n\n" +
                    "Such as: 1 4 6 7", false)
            session.selecting = selection
        } else if ("sh".equals(selection)) {
            callback.answer("Please enter the match title you want to search with (regex will work)", false)
            session.selecting = selection
        }

        // sn & sh move their next data processing to processPlaylistMessage
    }
}