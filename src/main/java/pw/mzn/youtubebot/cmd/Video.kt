package pw.mzn.youtubebot.cmd

import com.google.common.cache.CacheBuilder
import pro.zackpollard.telegrambot.api.chat.CallbackQuery
import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.message.Message
import pro.zackpollard.telegrambot.api.chat.message.content.AudioContent
import pro.zackpollard.telegrambot.api.chat.message.content.TextContent
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.event.chat.message.TextMessageReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.*
import pw.mzn.youtubebot.IdList
import pw.mzn.youtubebot.Track
import pw.mzn.youtubebot.YoutubeBot
import pw.mzn.youtubebot.extra.*
import java.io.File
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.schedule

class VideoCommandHolder(val instance: YoutubeBot) {
    val videoSessions = IdList<VideoSession>()
    val matchingStore = ConcurrentHashMap<Long, MatchSession>()
    val videoSearch = ConcurrentHashMap<Long, SearchSession>()
    val trackStore = ConcurrentHashMap<Long, TrackSession>()
    val titleCache = CacheBuilder.newBuilder()
            .concurrencyLevel(5)
            .expireAfterWrite(8, TimeUnit.HOURS)
            .build<String, String>() // key = video id, val = title
    val pendingInline = CacheBuilder.newBuilder()
            .concurrencyLevel(5)
            .expireAfterWrite(2, TimeUnit.HOURS)
            .build<Long, Any>()

    fun sendVideo(chat: Chat, link: String, linkSent: Boolean, originalQuery: Message?, userId: Long,
                  optionz: VideoOptions?, duration: Long, title: String, ignoreSaved: Boolean, editMessageId: Long?) {
        var session = VideoSession(instance, chat.id, link, VideoOptions(0, duration), chat, linkSent, userId, originalQuery, duration)
        var regex = instance.videoRegex.matcher(link)
        regex.matches()
        var videoId = regex.group(1)
        var savedMatches = instance.dataManager.videosBy(videoId)

        if (savedMatches.isNotEmpty() && !ignoreSaved) {
            var matchSession = MatchSession(session, videoId, IdList<String>())
            matchingStore.put(userId, matchSession)
            var keyboardBuilder = InlineKeyboardMarkup.builder()
            var text = "I found some song presets which matches your query, please select the one which matches your search best"

            matchSession.selections.map.put(99, "Customize")
            keyboardBuilder.addRow(InlineKeyboardButton.builder().text("Customize").callbackData("m.99").build())
            savedMatches.forEach { e -> keyboardBuilder.addRow(InlineKeyboardButton.builder().text(e.formattedName(instance))
                    .callbackData("m.${matchSession.selections.add(e.formattedName(instance))}").build()) }

            if (editMessageId == null) {
                matchSession.messageId = chat.sendMessage(SendableTextMessage.builder()
                        .replyTo(originalQuery!!)
                        .message(text)
                        .replyMarkup(keyboardBuilder.build())
                        .build()).messageId
            } else {
                instance.bot.editMessageText(chat.id, editMessageId, text,
                        ParseMode.NONE, true, keyboardBuilder.build())
            }
            return
        }

        var search = instance.searchTrack(title).toMutableList()

        if (optionz == null && !search.isEmpty()) {
            var track = search[0]
            var replyKeyboard = InlineKeyboardMarkup.builder()
                    .addRow(InlineKeyboardButton.builder().text("Yes").callbackData("lf.y").build(),
                            InlineKeyboardButton.builder().text("No").callbackData("lf.n").build())
                    .build()

            if (editMessageId != null) {
                instance.bot.editMessageText(chat.id, editMessageId, "Searching DB for song match...",
                        ParseMode.NONE, false, null)
                session.botMessageId = editMessageId
            } else {
                session.botMessageId = chat.sendMessage(SendableTextMessage.builder()
                        .replyTo(originalQuery!!)
                        .message("Searching DB for song match...")
                        .build()).messageId
            }

            instance.bot.editMessageText(chat.id, session.botMessageId, "Is this song ${track.name} by ${track.artist}?",
                    ParseMode.NONE, false, replyKeyboard)
            trackStore.put(userId, TrackSession(session, track))

            return
        }

        if (optionz == null) {
            var id = videoSessions.add(session)
            var messageId = editMessageId

            if (messageId == null) {
                messageId = chat.sendMessage("Initializing...").messageId
            }

            initCustomization(id, originalQuery, chat, messageId)
            return
        }

        Thread(Runnable() {
            var options = optionz

            instance.commandHandler.timeoutCache.put(userId, Object())
            instance.bot.editMessageText(chat.id, editMessageId, "Downloading video and extracting audio " +
                    "(Depending on duration of video, this may take a while)", ParseMode.NONE, true, null)
            var video = instance.downloadVideo(options, videoId)

            if (!"N/A".equals(options.customTitle)) {
                video.customTitle = options.customTitle
            }

            if (!"N/A".equals(options.customPerformer)) {
                video.customPerformer = options.customPerformer
            }

            sendProcessedVideo(video, originalQuery, chat, userId, linkSent, options, editMessageId)
        }, "YoutubeBot $videoId Thread").start()
    }

    fun sendProcessedVideo(video: YoutubeVideo, originalQuery: Message?, chat: Chat,
                           userId: Long, linkSent: Boolean, options: VideoOptions?, editMessageId: Long?) {
        var audio = video.sendable()

        if (originalQuery != null) {
            audio.replyTo(originalQuery)
        }

        var audioMessage = chat.sendMessage(audio.build())
        var fileId = (audioMessage.content as AudioContent).content.fileId
        var inlineKeyboard: InlineKeyboard? = null

        if (pendingInline.asMap().containsKey(userId)) {
            inlineKeyboard = InlineKeyboardMarkup.builder().addRow(InlineKeyboardButton.builder()
                    .text("Send in Original Chat").switchInlineQuery("afid:$fileId").build()).build()
        }

        if (editMessageId != null) {
            instance.bot.editMessageText(chat.id, editMessageId, descriptionFor(video, linkSent), ParseMode.MARKDOWN, true, inlineKeyboard)
        } else {
            var md = SendableTextMessage.builder()
                    .message(descriptionFor(video, linkSent))
                    .replyTo(audioMessage)
                    .disableWebPagePreview(true)
                    .parseMode(ParseMode.MARKDOWN)

            if (inlineKeyboard != null) {
                md.replyMarkup(inlineKeyboard)
            }

            chat.sendMessage(md.build())
        }

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

            video.customLength = options.endTime - options.startTime
            video.fileId = fileId
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

    fun initCustomization(id: Int, originalQuery: Message?, chat: Chat, botMessageId: Long) {
        instance.bot.editMessageText(chat.id, botMessageId,
                "How would you like to customize your video?", ParseMode.NONE, false, videoKeyboardFor(id))

        videoSessions.get(id)!!.botMessageId = botMessageId // next step is processVideoInline()
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
                    editDelayed("Please enter a valid time", entry.key, session.chatId,
                            session.botMessageId, 2)
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
                editDelayed("Please enter a valid number!", entry.key, session.chatId,
                        session.botMessageId, 2)
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
                editDelayed("Please enter a valid title!", entry.key, session.chatId,
                        session.botMessageId, 2)
                return
            }

            session.options.customTitle = title
            editDelayed("Changes applied!", entry.key, session.chatId,
                    session.botMessageId, 1)
        } else if ("pr".equals(selecting)) {
            var performer = message.trim()

            if ("".equals(performer)) {
                editDelayed("Please enter a valid performer!", entry.key, session.chatId,
                        session.botMessageId, 2)
                return
            }

            session.options.customPerformer = performer
            editDelayed("Changes applied!", entry.key, session.chatId,
                    session.botMessageId, 1)
        }

        session.selecting = "N/A" // reset back to processVideoInline()
        instance.bot.editMessageReplyMarkup(session.chatId, session.botMessageId, videoKeyboardFor(entry.key))
    }

    private fun editDelayed(firstMessage: String, sessionId: Int, chatId: String, editId: Long, time: Int) {
        instance.bot.editMessageText(chatId, editId, firstMessage, ParseMode.MARKDOWN, false, null)

        Timer().schedule(time.toLong() * 1000) {
            instance.bot.editMessageText(chatId, editId, "How would you like to customize your video?",
                    ParseMode.MARKDOWN, true, videoKeyboardFor(sessionId))
        }
    }

    fun processSearchInline(id: Int, userId: Long, callback: CallbackQuery) {
        var session = videoSearch[userId]

        if (id == 9) {
            instance.bot.editMessageText(session!!.chat.id, session.botMessageId, "Cancelled search!", ParseMode.MARKDOWN,
                    true, null)
            instance.commandHandler.flushSessions(userId, session.chat.id)
            return
        }

        var selected = session!!.idList.get(id)

        videoSearch.remove(userId)

        if (selected == null) {
            callback.answer("That selection was not an option!", true)
            return
        }

        var link = "https://www.youtube.com/watch?v=${selected.videoId}"
        var duration = instance.preconditionVideo(link, session.chat, false)

        if (duration == -1L) {
            return
        }

        sendVideo(session.chat, link, false, session.originalQuery, userId, null, duration,
                titleCache.asMap()[selected.videoId]!!, false, session.botMessageId)
    }

    fun checkCache(chat: Chat, query: String, userId: Long, originalMessage: Message): Boolean {
        var session = VideoSession(instance, chat.id, "https://youtube.com/watch?v=0t2tjNqGyJI", VideoOptions(),
                chat, false, userId, originalMessage, 0L)
        var savedMatches = instance.dataManager.videos
                .filter { video -> video.customTitle!!.contains(query) || query.contains(video.customTitle!!) }
                .sortedBy { video -> query.contains(video.customTitle!!) && query.contains(video.customPerformer!!)}

        if (savedMatches.isNotEmpty()) {
            var matchSession = MatchSession(session, "0t2tjNqGyJI", IdList<String>())
            matchingStore.put(userId, matchSession)
            var keyboardBuilder = InlineKeyboardMarkup.builder()

            matchSession.selections.map.put(99, "Search")
            keyboardBuilder.addRow(InlineKeyboardButton.builder().text("Customize").callbackData("m.99").build())
            savedMatches.forEach { e -> keyboardBuilder.addRow(InlineKeyboardButton.builder().text(e.formattedName(instance))
                    .callbackData("m.${matchSession.selections.add(e.formattedName(instance))}").build()) }

            matchSession.messageId = chat.sendMessage(SendableTextMessage.builder()
                    .replyTo(originalMessage)
                    .message("I found some song presets which matches your query, please select the one which matches your search best")
                    .replyMarkup(keyboardBuilder.build())
                    .build()).messageId
            return true
        }

        return false
    }

    fun processSearch(chat: Chat, query: String, userId: Long, originalMessage: Message) {
        println("searching for $query")
        var response = instance.searchVideo(query)

        if (response.isEmpty()) {
            chat.sendMessage("No videos were found by that query!")
            return
        }

        var keyboard = InlineKeyboardMarkup.builder()
        var max = response.size - 1

        if (max > 3) {
            max = 3
        }

        var cachedVids = IdList<CachedYoutubeVideo>()

        for (i in 0..max) {
            var entry = response[i]
            var title = entry.title

            keyboard.addRow(InlineKeyboardButton.builder()
                    .text(title)
                    .callbackData("s.${cachedVids.add(entry)}").build())
        }

        keyboard.addRow(InlineKeyboardButton.builder().text("Cancel").callbackData("s.9").build())
        var message = SendableTextMessage.builder()
                .message("Select a Video")
                .replyMarkup(keyboard.build())
                .replyTo(originalMessage)
                .build()

        videoSearch.put(userId, SearchSession(cachedVids, chat.sendMessage(message).messageId, chat,
                originalMessage))
    }

    fun processMatchInline(matchSession: MatchSession, userId: Long, selection: String, query: CallbackQuery) {
        var session = matchSession.videoSession
        var matches = instance.dataManager.videos
        var content = selection

        if ("Customize".equals(content)) {
            sendVideo(session.chat, session.link, session.linkSent, session.originalQuery, session.userId,
                    null, session.duration, titleCache.asMap()[session.videoId]!!, true, session.botMessageId)
            matchingStore.remove(userId)
            return
        }

        if ("Search".equals(content)) {
            processSearch(session.chat, (session.originalQuery!!.content as TextContent).content,
                    userId, session.originalQuery!!)
            matchingStore.remove(userId)
            return
        }

        var matched = matches.filter { e -> e.formattedName(instance).equals(content) }.firstOrNull()

        if (matched == null) {
            query.answer("That is not a valid selection! Please try again", true)
            return
        }

        session.videoId = matched.id
        matchSession.videoId = matched.id
        instance.bot.editMessageText(session.chat.id, matchSession.messageId, "Using selected preset", ParseMode.NONE, false, null)
        sendProcessedVideo(matched, session.originalQuery, session.chat, session.userId, session.linkSent,
                session.options, matchSession.messageId)
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
                    session.options, session.duration, titleCache.asMap()[session.videoId]!!, true, session.botMessageId)
            videoSessions.remove(session)
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
                    callback.answer("Please send your thumbnail!", true)
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

    fun processTrackMessage(event: TextMessageReceivedEvent) {
        var content = (event.message.content as TextContent).content
        var session = trackStore[event.chat.id.toLong()]!!
        var track = session.track

        if ("a".equals(session.stage)) {
            session.track = Track("n/a", content, "n/a")
            session.stage = "n"
            instance.bot.editMessageText(session.videoSession.chatId, session.videoSession.botMessageId,
                    "What is the name of this song?", ParseMode.NONE, false, null)
        } else if ("n".equals(session.stage)) {
            track.name = content
            track.coverUrl = instance.searchImage("$content ${track.artist} cover")

            var options = session.videoSession.options

            options.customTitle = track.name
            options.customPerformer = track.artist

            if (!"".equals(track.coverUrl)) {
                options.thumbnailUrl = track.coverUrl
                options.thumbnail = true
                session.videoSession.thumbnail = track.coverUrl
            }

            var id = videoSessions.add(session.videoSession)
            initCustomization(id, session.videoSession.originalQuery, session.videoSession.chat,
                    session.videoSession.botMessageId)
            trackStore.remove(event.chat.id.toLong())
        }
    }
}