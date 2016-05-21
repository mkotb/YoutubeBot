package pw.mzn.youtubebot.cmd

import com.google.common.cache.CacheBuilder
import pro.zackpollard.telegrambot.api.chat.CallbackQuery
import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.GroupChat
import pro.zackpollard.telegrambot.api.chat.message.Message
import pro.zackpollard.telegrambot.api.chat.message.content.AudioContent
import pro.zackpollard.telegrambot.api.chat.message.content.TextContent
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.event.chat.message.TextMessageReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.*
import pw.mzn.youtubebot.IdList
import pw.mzn.youtubebot.YoutubeBot
import pw.mzn.youtubebot.extra.*
import java.io.File
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class VideoCommandHolder(val instance: YoutubeBot) {
    val videoSessions = IdList<VideoSession>()
    val matchingStore = ConcurrentHashMap<Long, MatchSession>()
    val videoSearch = ConcurrentHashMap<Long, List<CachedYoutubeVideo>>()
    val trackStore = ConcurrentHashMap<Long, TrackSession>()
    val titleCache = CacheBuilder.newBuilder()
            .concurrencyLevel(5)
            .expireAfterWrite(8, TimeUnit.HOURS)
            .build<String, String>() // key = video id, val = title

    fun sendVideo(chat: Chat, link: String, linkSent: Boolean, originalQuery: Message?, userId: Long,
                  optionz: VideoOptions?, duration: Long, title: String, ignoreSaved: Boolean) {
        var session = VideoSession(instance, chat.id, link, VideoOptions(0, duration), chat, linkSent, userId, originalQuery, duration)
        var regex = instance.videoRegex.matcher(link)
        regex.matches()
        var videoId = regex.group(1)
        var savedMatches = instance.dataManager.videosBy(videoId)

        if (savedMatches.isNotEmpty() && !ignoreSaved) {
            matchingStore.put(userId, MatchSession(session, videoId))
            var keyboardBuilder = ReplyKeyboardMarkup.builder()

            keyboardBuilder.selective(true)
            keyboardBuilder.addRow(KeyboardButton.builder().text("Do not use preset").build())
            savedMatches.forEach { e -> keyboardBuilder.addRow(KeyboardButton.builder().text(e.formattedName(instance)).build()) }

            chat.sendMessage(SendableTextMessage.builder()
                    .replyTo(originalQuery!!)
                    .message("I found some song presets which matches your query, please select the one which matches your search best")
                    .replyMarkup(keyboardBuilder.build())
                    .build())
            return
        }

        var search = instance.searchTrack(title).toMutableList()

        if (((chat is GroupChat) || optionz == null) && !search.isEmpty()) {
            var track = search[0]
            var replyKeyboard = InlineKeyboardMarkup.builder()
                    .addRow(InlineKeyboardButton.builder().text("Yes").callbackData("lf.y").build(),
                            InlineKeyboardButton.builder().text("No").callbackData("lf.n").build())
                    .build()

            chat.sendMessage(SendableTextMessage.builder()
                    .replyTo(originalQuery!!)
                    .message("Searching DB for song match...")
                    .replyMarkup(ReplyKeyboardHide.builder().selective(true).build())
                    .build())
            chat.sendMessage(SendableTextMessage.builder()
                    .message("Is this song ${track.name} by ${track.artist}?")
                    .replyMarkup(replyKeyboard).build())
            trackStore.put(userId, TrackSession(session, track))

            return
        }

        if ((chat !is GroupChat) && optionz == null) {
            var id = videoSessions.add(session)
            initCustomization(id, originalQuery, chat)
            return
        }

        Thread(Runnable() {
            var options = optionz ?: VideoOptions()

            instance.commandHandler.timeoutCache.put(userId, Object())
            var reply = SendableTextMessage.builder()
                    .message("Downloading video and extracting audio (Depending on duration of video, " +
                            "this may take a while)")
            var hide = ReplyKeyboardHide.builder()

            if (originalQuery != null) {
                hide.selective(true)
                reply.replyTo(originalQuery)
            }

            reply.replyMarkup(hide.build())
            chat.sendMessage(reply.build())
            var video = instance.downloadVideo(options, videoId)

            if (!"N/A".equals(options.customTitle)) {
                video.customTitle = options.customTitle
            }

            if (!"N/A".equals(options.customPerformer)) {
                video.customPerformer = options.customPerformer
            }

            sendProcessedVideo(video, originalQuery, chat, userId, linkSent, options)
        }, "YoutubeBot $videoId Thread").start()
    }

    fun sendProcessedVideo(video: YoutubeVideo, originalQuery: Message?, chat: Chat,
                           userId: Long, linkSent: Boolean, options: VideoOptions?) {
        var audio = video.sendable()

        if (originalQuery != null) {
            audio.replyTo(originalQuery)
        }

        var audioMessage = chat.sendMessage(audio.build())
        var md = SendableTextMessage.builder()
                .message(descriptionFor(video, linkSent))
                .replyTo(audioMessage)
                .disableWebPagePreview(true)
                .parseMode(ParseMode.MARKDOWN)


        chat.sendMessage(md.build())
        instance.commandHandler.timeoutCache.invalidate(userId)
        video.file.delete()

        File("${video.id}.info.json").delete()
        removeVideoSession(chat.id)

        if (options != null) {
            if (options.speed != 1.0) { // nobody likes nightcore
                return
            }

            if (!video.metadata.name.toLowerCase().contains(options.customPerformer.toLowerCase())) { // quality control
                return
            }

            if (!video.metadata.name.toLowerCase().contains(options.customTitle.toLowerCase())) { // quality control
                return
            }

            video.fileId = (audioMessage.content as AudioContent).content.fileId
            instance.dataManager.videos.add(video)
            instance.dataManager.saveToFile()
        }
    }

    fun descriptionFor(video: YoutubeVideo, linkSent: Boolean): String {
        var metadata = video.metadata
        var messageBuilder = StringBuilder("*${metadata.name}*\n")

        messageBuilder.append("*Uploaded by* _${metadata.uploader}_\n")
        messageBuilder.append("*Views:* ${NumberFormat.getInstance().format(metadata.viewCount)}\n")
        messageBuilder.append("👍 ${NumberFormat.getInstance().format(metadata.likes)}\n")
        messageBuilder.append("👎 ${NumberFormat.getInstance().format(metadata.dislikes)}\n")

        if (!linkSent) {
            messageBuilder.append("[Watch here!](${metadata.url})\n")
        }

        return messageBuilder.toString()
    }

    fun initCustomization(id: Int, originalQuery: Message?, chat: Chat) {
        var reply = SendableTextMessage.builder()
                .message("Initializing...")
        var hide = ReplyKeyboardHide.builder()

        if (originalQuery != null) {
            hide.selective(true)
            reply.replyTo(originalQuery)
        }

        reply.replyMarkup(hide.build())
        chat.sendMessage(reply.build())
        var response = SendableTextMessage.builder()
                .message("How would you like to customize your video?")
                .replyMarkup(videoKeyboardFor(id))
                .build()

        videoSessions.get(id)!!.botMessageId = chat.sendMessage(response).messageId // next step is processVideoInline()
    }

    fun processVideoMessage(event: TextMessageReceivedEvent?,
                            entry: MutableMap.MutableEntry<Int, VideoSession>) {
        var session = entry.value
        var content = event!!.message.content

        if (content !is TextContent) {
            return
        }

        var message = content.content
        var selecting = session.selecting

        if ("N/A".equals(selecting)) {
            return
        }

        if (selecting.endsWith("t")) {
            var timestamp = instance.parseDuration(message)

            if (selecting.startsWith("s")) {
                var high = (session.options.endTime - 2)

                if (timestamp > high) {
                    timestamp = high
                }

                session.options.startTime = timestamp
            } else if (selecting.startsWith("e")) {
                if (timestamp == 0L) {
                    event.chat.sendMessage("Please enter a valid time!")
                    return
                }

                var low = (session.options.startTime + 2)

                if (timestamp < low) {
                    timestamp = low
                }

                session.options.endTime = timestamp
            }

            session.options.crop = true
        } else if ("s".equals(selecting)) {
            var speed: Double

            try {
                speed = message.toDouble()
            } catch (e: Exception) {
                event.chat.sendMessage("Please enter a valid number!")
                return
            }

            if (speed < 0.5) {
                speed = 0.5 // sorry not sorry
            }

            if (speed > 10.0) {
                speed = 10.0 // sorry not sorry
            }

            session.options.speed = speed
        } else if ("te".equals(selecting)) {
            var title = message.trim()

            if ("".equals(title)) {
                event.chat.sendMessage("Please enter a valid title!")
                return
            }

            session.options.customTitle = title
            event.chat.sendMessage(SendableTextMessage.builder()
                    .replyTo(event.message)
                    .message("Updated!").build())
        } else if ("pr".equals(selecting)) {
            var performer = message.trim()

            if ("".equals(performer)) {
                event.chat.sendMessage("Please enter a valid title!")
                return
            }

            session.options.customPerformer = performer
            event.chat.sendMessage(SendableTextMessage.builder()
                    .replyTo(event.message)
                    .message("Updated!").build())
        }

        session.selecting = "N/A" // reset back to processVideoInline()
        instance.bot.editMessageReplyMarkup(session.chatId, session.botMessageId, videoKeyboardFor(entry.key))
    }

    fun processSearchSelection(event: TextMessageReceivedEvent) {
        var userId = event.message.sender.id
        var videos = videoSearch[userId]
        var selected = videos!!.filter { e -> e.title.equals(event.content.content) }.firstOrNull()

        videoSearch.remove(userId)

        if (selected == null) {
            event.chat.sendMessage(SendableTextMessage.builder()
                    .message("That selection was not an option!")
                    .replyMarkup(ReplyKeyboardHide.builder().selective(true).build())
                    .replyTo(event.message).build())
            return
        }

        if ("Cancel".equals(event.content.content)) {
            instance.commandHandler.flushSessions(userId, event.chat.id)
            event.chat.sendMessage(SendableTextMessage.builder()
                    .message("Cancelled!")
                    .replyMarkup(ReplyKeyboardHide.builder().selective(true).build()).build())
            return
        }

        var link = "https://www.youtube.com/watch?v=${selected.videoId}"
        var duration = instance.preconditionVideo(link, event.chat, false)

        if (duration == -1L) {
            return
        }

        sendVideo(event.chat, link, false, event.message, userId, null, duration, titleCache.asMap()[selected.videoId]!!, false)
    }

    fun processSearch(chat: Chat, query: String, userId: Long, originalMessage: Message) {
        chat.sendMessage("Searching for video...")
        var response = instance.searchVideo(query)

        if (response.isEmpty()) {
            chat.sendMessage("No videos were found by that query!")
            return
        }

        var keyboard = ReplyKeyboardMarkup.builder()
        var max = response.size - 1

        if (max > 5) {
            max = 5
        }

        var cachedVids = ArrayList<CachedYoutubeVideo>()

        for (i in 0..max) {
            var entry = response[i]
            var title = entry.title

            cachedVids.add(entry)
            keyboard.addRow(KeyboardButton.builder().text(title).build())
        }

        keyboard.addRow(KeyboardButton.builder().text("Cancel").build())
        keyboard.selective(true)

        videoSearch.put(userId, cachedVids)
        var message = SendableTextMessage.builder()
                .message("Select a Video")
                .replyMarkup(keyboard.build())
                .replyTo(originalMessage)
                .build()

        chat.sendMessage(message)
    }

    fun processMatchSelection(event: TextMessageReceivedEvent) {
        var userId = event.message.sender.id
        var matchSession = matchingStore[userId]!!
        var videoId = matchSession.videoId
        var session = matchSession.videoSession
        var matches = instance.dataManager.videosBy(videoId)
        var content = event.content.content

        if ("Do not use preset".equals(content)) {
            sendVideo(session.chat, session.link, session.linkSent, session.originalQuery, session.userId,
                    session.options, session.duration, titleCache.asMap()[session.videoId]!!, true)
            matchingStore.remove(userId)
            return
        }

        var matched = matches.filter { e -> e.formattedName(instance).equals(content) }.firstOrNull()

        if (matched == null) {
            event.chat.sendMessage("That is not a valid selection! Please try again")
            return
        }

        event.chat.sendMessage("Using preset...")
        sendProcessedVideo(matched, session.originalQuery, session.chat, session.userId, session.linkSent,
                session.options)
    }

    fun removeVideoSession(chatId: String) {
        var videoSession = videoSessions.map.entries.filter { e -> e.value.chatId.equals(chatId) }
                .firstOrNull() ?: return
        videoSessions.remove(videoSession.value)
    }

    fun videoKeyboardFor(id: Int): InlineKeyboard {
        var session = videoSessions.get(id) ?: return InlineKeyboardMarkup.builder().build()

        return InlineKeyboardMarkup.builder()
                .addRow(InlineKeyboardButton.builder().text("Start Time ${instance.formatTime(session.options.startTime)}")
                        .callbackData("vd.st.$id").build(),
                        InlineKeyboardButton.builder().text("End Time ${instance.formatTime(session.options.endTime)}")
                                .callbackData("vd.et.$id").build())
                .addRow(InlineKeyboardButton.builder().text("Speed ${session.options.speed}x")
                        .callbackData("vd.s.$id").build(),
                        InlineKeyboardButton.builder().text("Thumbnail ${friendlyBoolean(session.options.thumbnail)}")
                                .callbackData("vd.tn.$id").build())
                .addRow(InlineKeyboardButton.builder().text("Change Title")
                        .callbackData("vd.te.$id").build(),
                        InlineKeyboardButton.builder().text("Change Performer")
                                .callbackData("vd.pr.$id").build())
                .addRow(InlineKeyboardButton.builder().text("Cancel")
                        .callbackData("vd.c.$id").build(),
                        InlineKeyboardButton.builder().text("Send to Processing...")
                                .callbackData("vd.p.$id").build())
                .build()
    }

    fun friendlyBoolean(bool: Boolean): String {
        if (bool) {
            return "Yes"
        } else {
            return "No"
        }
    }

    fun processVideoInline(callback: CallbackQuery, data: String) {
        var session = instance.inlineHandler.fetchSession(data, 2, 3, videoSessions) ?: return // stop returning bad data
        var selection = data.split(".")[1]

        if ("p".equals(selection)) { // exit route
            callback.answer("Sending to processing queue...", false)
            session.options.thumbnailUrl = session.thumbnail
            sendVideo(session.chat, session.link, session.linkSent, session.originalQuery, session.userId,
                    session.options, session.duration, titleCache.asMap()[session.videoId]!!, true)
            return
        }

        if ("c".equals(selection)) {
            callback.answer("Cancelling operation...", false)
            instance.commandHandler.flushSessions(session.userId, session.chatId)
            return
        }

        if ("tn".equals(selection)) {
            if (data.split(".").size == 4) {
                var selectedThumbnail = data.split(".")[3]

                if ("c".equals(selectedThumbnail)) {
                    session.pendingImage = true
                    session.chat.sendMessage("Please send your thumbnail")
                }

                if ("d".equals(selectedThumbnail)) {
                    session.options.thumbnail = true
                    session.thumbnail = instance.commandHandler.thumbnails[session.videoId]!!
                }

                instance.bot.editMessageText(session.chatId, session.botMessageId, "How would you like to customize your video?",
                        ParseMode.MARKDOWN, true, videoKeyboardFor(data.split(".")[2].toInt()))
                return
            }

            if (session.options.thumbnail) {
                callback.answer("Disabling thumbnail...", false)
                var thumbnail = File("${session.videoId}.jpg")

                if (thumbnail.exists()) {
                    thumbnail.delete()
                }

                session.options.thumbnail = false
                instance.bot.editMessageReplyMarkup(session.chatId, session.botMessageId,
                        videoKeyboardFor(data.split(".")[2].toInt()))
            } else {
                callback.answer("Please answer the following question accordingly...", false)
                var replyKeyboard = InlineKeyboardMarkup.builder()
                        .addRow(InlineKeyboardButton.builder().text("Custom").callbackData("$data.c").build(),
                                InlineKeyboardButton.builder().text("Default").callbackData("$data.d").build()).build()
                instance.bot.editMessageText(session.chatId, session.botMessageId, "Which type of thumbnail would you like to use?",
                        ParseMode.MARKDOWN, true, replyKeyboard)
            }
            return
        }

        // all steps below go to processVideoMessage

        if ("st".equals(selection)) {
            callback.answer("Please enter the start time.\nExample: 3:15 for 3 minutes and 15 minutes in", false)
        } else if ("et".equals(selection)) {
            callback.answer("Please enter the end time.\nExample: 3:15 for 3 minutes and 15 minutes in", false)
        } else if ("s".equals(selection)) {
            callback.answer("Please enter the speed.\nExample: 2.5 for 2.5 times the speed", false)
        } else if ("te".equals(selection)) {
            callback.answer("Please enter your new title", false)
        } else if ("pr".equals(selection)) {
            callback.answer("Please enter your new performer", false)
        } else {
            callback.answer("not a selection", true)
        }

        session.selecting = selection
    }
}