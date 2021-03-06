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

import com.google.common.cache.CacheBuilder
import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.inline.send.InlineQueryResponse
import pro.zackpollard.telegrambot.api.chat.inline.send.content.InputTextMessageContent
import pro.zackpollard.telegrambot.api.chat.inline.send.results.InlineQueryResult
import pro.zackpollard.telegrambot.api.chat.inline.send.results.InlineQueryResultArticle
import pro.zackpollard.telegrambot.api.chat.inline.send.results.InlineQueryResultCachedAudio
import pro.zackpollard.telegrambot.api.chat.message.Message
import pro.zackpollard.telegrambot.api.chat.message.send.*
import pro.zackpollard.telegrambot.api.event.Listener
import pro.zackpollard.telegrambot.api.event.chat.inline.InlineQueryReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.CommandMessageReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.TextMessageReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.*
import pw.mzn.youtubebot.*
import pw.mzn.youtubebot.extra.*
import java.net.URL
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CommandHandler(val instance: YoutubeBot): Listener {
    val thumbnails = ConcurrentHashMap<String, String>() // key = video id, val = link
    val inlineList = IdList<CommandSession>()
    val timeoutCache = CacheBuilder.newBuilder()
            .concurrencyLevel(5)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build<Long, Any?>()

    override fun onCommandMessageReceived(event: CommandMessageReceivedEvent?) {
        Thread() { processCommand(event) }.start()
    }

    override fun onInlineQueryReceived(event: InlineQueryReceivedEvent?) {
        Thread() { run {
            var query = event!!.query

            if (query.query.startsWith("afid:")) {
                var audioFileId = query.query.split(":")[1]
                query.answer(instance.bot, InlineQueryResponse.builder()
                        .results(InlineQueryResultCachedAudio.builder()
                                .id("1")
                                .audioFileId(audioFileId)
                                .build()).is_personal(false).build())
                return@run
            }

            var linkMatcher = instance.videoRegex.matcher(query.query)

            if (linkMatcher.lookingAt()) {
                var id = linkMatcher.group(1)
                instance.preconditionVideo(query.query, null, false)
                var title = instance.command.video.titleCache.asMap()[id]

                query.answer(instance.bot, InlineQueryResponse.builder()
                        .results(InlineQueryResultArticle.builder()
                                .id("1") // useless
                                .title(title)
                                .url(URL("https://www.youtube.com/watch?v=$id"))
                                .inputMessageContent(InputTextMessageContent.builder()
                                        .messageText("[Click here to download $title](https://telegram.me/${instance.bot.botUsername}?start=$id)")
                                        .parseMode(ParseMode.MARKDOWN)
                                        .build()).build())
                                .is_personal(true)
                                .switch_pm_text("Click to Download!")
                                .switch_pm_parameter("inline-l:https://www.youtube.com/watch?v=$id").build())
                return@run
            }

            var response = instance.searchVideo(query.query)
            var videos = ArrayList<InlineQueryResult>(response.size)
            var idCounter = 1

            if (response.size >= 4) {
                response = response.subList(0, 4)
            }

            response.forEach { e -> run {
                var cached = instance.dataManager.videosBy(e.videoId)

                if (cached.isNotEmpty()) {
                    videos.add(InlineQueryResultCachedAudio.builder()
                            .id(idCounter++.toString())
                            .audioFileId(cached[0].fileId)
                            .build())
                } else {
                    var article = InlineQueryResultArticle.builder()
                            .id(idCounter++.toString()) // useless
                            .title(e.title.replace(" - YouTube", ""))
                            .url(URL("https://www.youtube.com/watch?v=${e.videoId}"))
                            .inputMessageContent(InputTextMessageContent.builder()
                                    .messageText("[Click here to download ${e.title}](https://telegram.me/${instance.bot.botUsername}?start=${e.videoId})")
                                    .parseMode(ParseMode.MARKDOWN)
                                    .build())

                    if (!"null".equals(e.thumb)) {
                        article.thumbUrl(URL(e.thumb)).thumbHeight(300).thumbWidth(300)
                    }

                    if (!"null".equals(e.description)) {
                        article.description(e.description)
                    }

                    videos.add(article.build())
                }
            } }

            query.answer(instance.bot, InlineQueryResponse.builder().results(videos)
                    .is_personal(true)
                    .switch_pm_text("Click to Download")
                    .switch_pm_parameter("inline:${query.query}").build())
        } }.start()
    }

    fun processCommand(event: CommandMessageReceivedEvent?) {
        if ("download".equals(event!!.command)) {
            if (event.args.size < 1) {
                event.chat.sendMessage("give me")
                return
            }

            if (timeoutCache.asMap().containsKey(event.message.sender.id)) {
                event.chat.sendMessage("I can only process one task at a time per user, sorry!\n" +
                        "If your task is long, after 10 minutes from submission you are able to submit another task.")
                return
            }

            processInput(event.argsString, event.chat, event.message)
            return
        }

         if ("start".equals(event.command)) {
             if (event.args.size > 0) {
                 if (event.args[0].startsWith("inline:")) {
                     instance.command.video.pendingInline.put(event.message.sender.id, Any())
                     instance.command.video.processSearch(event.chat, event.args[0].replaceFirst("inline:", ""), event.message.sender.id,
                             event.message)
                     return
                 }

                 if (event.args[0].startsWith("inline-l:https://www.youtube.com/watch?v=")) {
                     processInput(event.args[0].replaceFirst("inline-l:", ""), event.chat, event.message)
                     return
                 }

                 if (event.args[0].startsWith("login")) {
                     instance.youtubeUserAuth.processAuth(event.args[0].replace("login-", ""), event)
                     return
                 }

                 processInput("https://www.youtube.com/watch?v=${event.args[0]}", event.chat, event.message)
                 return
             }

             event.chat.sendMessage("Hi! Welcome to the YouTube Downloader Bot, thanks for checking it out! " +
                     "The premise of this bot is simple; you give it a video, it sends you it's audio.\n" +
                     "You can throw at it links from YouTube, or give it a search query such as " +
                     "\"Stressed Out Tomsize Remix\" and it'll respond to you as soon as it can.\n It also works " +
                     "with playlists! You can make the bot download your favourite podcasts on YouTube or your " +
                     "favourite songs. Give it a try, send any query!\n\nContact @MazenK for support")
             return
         }

        if ("subscribe".equals(event.command)) {
            instance.command.subscription.subscribe(event)
        }

        if ("unsubscribe".equals(event.command)) {
            instance.command.subscription.unsubscribe(event)
        }

        if ("login".equals(event.command)) {
            instance.youtubeUserAuth.processLogin(event)
        }

        if ("logout".equals(event.command)) {
            instance.youtubeUserAuth.processLogout(event)
        }
    }

    override fun onTextMessageReceived(event: TextMessageReceivedEvent?) {
        println("received a message") // debug
        var video = instance.command.video
        var playlist = instance.command.playlist
        var subscription = instance.command.subscription
        var playlistEntry = playlist.playlistSessions.map.entries.filter { e -> e.value.chatId.equals(event!!.chat.id) }
                .firstOrNull()
        var videoEntry = video.videoSessions.map.entries.filter { e -> e.value.chatId.equals(event!!.chat.id) }
                .firstOrNull()

        if (playlistEntry != null) {
            playlist.processPlaylistMessage(event, playlistEntry)
            return
        }

        if (videoEntry != null) {
            video.processVideoMessage(event, videoEntry)
            return
        }

        if (subscription.channelSearch.containsKey(event!!.chat.id.toLong())) {
            subscription.processChannelSelection(event)
            return
        }

        if (video.trackStore.containsKey(event.chat.id.toLong())) {
            video.processTrackMessage(event)
            return
        }

        if (subscription.unsubscribeList.contains(event.chat.id.toLong())) {
            subscription.processUnsubscribeSelection(event)
            return
        }

        var query = event.content.content
        var tag = instance.bot.botUsername

        if (query.contains("@$tag") && !query.startsWith("@$tag")) {
            return // probably just a mention, not a search
        }

        if (query.startsWith("@$tag")) { // group chats
            query = query.replace("@$tag ", "")
        }

        processInput(query,event.chat, event.message)
    }

    fun processInput(input: String, chat: Chat, message: Message) {
        var userId = message.sender.id
        var link = input.split(" ")[0]
        var videoMatcher = instance.videoRegex.matcher(link)
        var playlistMatcher = instance.playlistRegex.matcher(link)
        var matchesVideo = videoMatcher.find()
        var matchesPlaylist = playlistMatcher.find() && link.contains("playlist")

        flushSessions(userId, chat.id)

        if (!matchesVideo && !matchesPlaylist) {
            if (!instance.command.video.checkCache(chat, input, userId, message)) {
                instance.command.video.processSearch(chat, input, userId, message)
            }

            return
        }

        if (matchesVideo && matchesPlaylist) {
            var videoDuration = instance.preconditionVideo(link, chat, true)
            var playlistVideoCount = instance.preconditionPlaylist(link, chat, true)

            if (videoDuration == -1L && playlistVideoCount == -1L) {
                chat.sendMessage("Neither the playlist nor the video in that link could be found!")
                return
            }

            if (videoDuration != -1L && playlistVideoCount == -1L) {
                matchesPlaylist = false
            }

            if (videoDuration == -1L && playlistVideoCount != -1L) {
                matchesVideo = false
            }

            if (videoDuration != -1L && playlistVideoCount != -1L) {
                var videoMatch = videoMatcher.group(1)
                playlistMatcher = instance.playlistRegex.matcher(link)
                playlistMatcher.lookingAt()
                var sessionId = inlineList.add(CommandSession(videoMatch, playlistMatcher.group(2), chat,
                        userId, message, playlistVideoCount, videoDuration))

                val selectionKeyboard = InlineKeyboardMarkup.builder()
                        .addRow(InlineKeyboardButton.builder().text("Playlist").callbackData("p.$sessionId").build(),
                                InlineKeyboardButton.builder().text("Video").callbackData("v.$sessionId").build())
                        .build()
                var response = SendableTextMessage.builder()
                        .message("That link matches both a playlist and a video, which of those would you" +
                                " like to download?")
                        .replyMarkup(selectionKeyboard)
                        .build()

                chat.sendMessage(response) // next process of new data is in processSelectionInline()
                return
            }
        }

        if (matchesVideo) {
            // check video length
            var duration = instance.preconditionVideo(link, chat, false)

            if (duration != -1L) {
                var videoHolder = instance.command.video
                videoHolder.sendVideo(chat, link, true, message, userId, null, duration, videoHolder.titleCache.asMap()[videoMatcher.group(1)]!!, false, null)
            }

            return
        }

        if (matchesPlaylist) {
            // attempt to find said playlist
            var count = instance.preconditionPlaylist(link, chat, false)

            if (count == -1L) {
                return
            }

            instance.command.playlist.sendPlaylist(chat, link, null, userId, count)
            return
        }
    }

    fun flushSessions(userId: Long, chatId: String) {
        instance.command.video.removeVideoSession(chatId)
        instance.command.playlist.removePlaylistSession(chatId)
        removeInlineSession(userId)
    }

    fun removeInlineSession(userId: Long) {
        var inlineSession = inlineList.map.entries.filter { e -> e.value.userId == userId }
                .firstOrNull() ?: return
        inlineList.remove(inlineSession.value)
    }
}