package pw.mzn.youtubebot

import com.google.common.cache.CacheBuilder
import pro.zackpollard.telegrambot.api.chat.CallbackQuery
import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.GroupChat
import pro.zackpollard.telegrambot.api.chat.SuperGroupChat
import pro.zackpollard.telegrambot.api.chat.message.Message
import pro.zackpollard.telegrambot.api.chat.message.content.TextContent
import pro.zackpollard.telegrambot.api.chat.message.send.InputFile
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendableDocumentMessage
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.event.Listener
import pro.zackpollard.telegrambot.api.event.chat.CallbackQueryReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.CommandMessageReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.PhotoMessageReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.TextMessageReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.*
import java.io.File
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CommandHandler(val instance: YoutubeBot): Listener {
    private val thumbnails = ConcurrentHashMap<String, String>() // key = video id, val = link
    private val userSearch = ConcurrentHashMap<Long, List<CachedYoutubeVideo>>()
    private val inlineList = IdList<CommandSession>()
    private val playlistSessions = IdList<PlaylistSession>()
    private val videoSessions = IdList<VideoSession>()
    private val timeoutCache = CacheBuilder.newBuilder()
            .concurrencyLevel(5)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build<Long, Any?>()

    override fun onCommandMessageReceived(event: CommandMessageReceivedEvent?) {
        Thread() { processCommand(event) }.start()
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
             event.chat.sendMessage("Hi! Welcome to the YouTube Downloader Bot, thanks for checking it out! " +
                     "The premise of this bot is simple; you give it a video, it sends you it's audio.\n" +
                     "You can throw at it links from YouTube, or give it a search query such as " +
                     "\"Stressed Out Tomize Remix\" and it'll respond to you as soon as it can.\n It also works " +
                     "with playlists! You can make the bot download your favourite podcasts on YouTube or your " +
                     "favourite songs. Give it a try, send any query!\n\nContact @MazenK for support")
             return
         }
    }

    fun processInput(input: String, chat: Chat, message: Message) {
        var userId = message.sender.id
        var link = input.split(" ")[0]
        var videoMatcher = instance.videoRegex.matcher(link)
        var playlistMatcher = instance.playlistRegex.matcher(link)
        var matchesVideo = videoMatcher.find()
        var matchesPlaylist = playlistMatcher.find()

        flushSessions(userId, chat.id)

        if (!matchesVideo && !matchesPlaylist) {
            processSearch(chat, input, userId, message)
            return
        }

        if (matchesVideo && matchesPlaylist) {
            var videoDuration = preconditionVideo(link, chat, true)
            var playlistVideoCount = preconditionPlaylist(link, chat, true)

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
            var duration = preconditionVideo(link, chat, false)

            if (duration != -1L) {
                sendVideo(chat, link, true, message, userId, null, duration)
            }

            return
        }

        if (matchesPlaylist) {
            // attempt to find said playlist
            var count = preconditionPlaylist(link, chat, false)

            if (count == -1L) {
                return
            }

            sendPlaylist(chat, link, null, userId, count)
            return
        }
    }

    fun preconditionPlaylist(link: String, chat: Chat, silent: Boolean): Long {
        var search = instance.youtube.playlists().list("id,contentDetails")
        var regex = instance.playlistRegex.matcher(link)
        regex.lookingAt()

        search.id = regex.group(regex.groupCount())
        search.fields = "items(id, contentDetails/itemCount)"
        search.key = instance.youtubeKey
        var response = search.execute()

        if (response.items.isEmpty()) {
            if (!silent) {
                chat.sendMessage("Unable to find any playlists by that ID!")
            }
            return -1
        }

        return response.items[0].contentDetails.itemCount
    }

    fun preconditionVideo(link: String, chat: Chat, silent: Boolean): Long {
        var search = instance.youtube.videos().list("contentDetails,snippet")
        var regex = instance.videoRegex.matcher(link)
        regex.lookingAt()

        search.id = regex.group(1)
        search.fields = "items(contentDetails/duration, snippet/thumbnails/medium/url)"
        search.key = instance.youtubeKey
        var response = search.execute()

        if (response.items.isEmpty()) {
            if (!silent) {
                chat.sendMessage("Unable to find any Youtube video by that ID!")
            }
            return -1L
        }

        var duration = instance.parse8601Duration(response.items[0].contentDetails.duration)

        if (duration > 3600L) {
            if (!silent) {
                chat.sendMessage("This bot is unable to process videos longer than 1 hour! Sorry!")
            }
            return -1L
        }

        if (!thumbnails.containsKey(search.id))
            thumbnails.put(search.id, response.items[0].snippet.thumbnails.medium.url)

        return duration
    }

    fun processSearch(chat: Chat, query: String, userId: Long, originalMessage: Message) {
        var response = instance.search(query)

        if (response.items == null || response.items.isEmpty()) {
            chat.sendMessage("No videos were found by that query!")
            return
        }

        var keyboard = ReplyKeyboardMarkup.builder()
        var max = response.items.size

        if (max > 5) {
            max = 5
        }

        var cachedVids = ArrayList<CachedYoutubeVideo>()

        for (i in 0..max) {
            var entry = response.items[i]
            var title = entry.snippet.title + " by ${entry.snippet.channelTitle}"

            cachedVids.add(CachedYoutubeVideo(entry.id.videoId, title))
            keyboard.addRow(KeyboardButton.builder().text(title).build())
        }

        keyboard.selective(true)

        userSearch.put(userId, cachedVids)
        var message = SendableTextMessage.builder()
                .message("Please select the one of the following")
                .replyMarkup(keyboard.build())
                .replyTo(originalMessage)
                .build()

        chat.sendMessage(message)
    }

    fun flushSessions(userId: Long, chatId: String) {
        removeVideoSession(chatId)
        removePlaylistSession(chatId)
        removeInlineSession(userId)
    }

    fun removeVideoSession(chatId: String) {
        var videoSession = videoSessions.map.entries.filter { e -> e.value.chatId.equals(chatId) }
                .firstOrNull() ?: return
        videoSessions.remove(videoSession.value)
    }

    fun removePlaylistSession(chatId: String) {
        var playlistSession = playlistSessions.map.entries.filter { e -> e.value.chatId.equals(chatId) }
                .firstOrNull() ?: return
        playlistSessions.remove(playlistSession.value)
    }

    fun removeInlineSession(userId: Long) {
        var inlineSession = inlineList.map.entries.filter { e -> e.value.userId == userId }
                .firstOrNull() ?: return
        inlineList.remove(inlineSession.value)
    }

    override fun onCallbackQueryReceivedEvent(event: CallbackQueryReceivedEvent?) {
        var callback = event!!.callbackQuery
        var data = callback.data

        if (data.split(".").size < 2) {
            return
        }

        if (data.startsWith("v.") || data.startsWith("p.")) { // validate
            processSelectionInline(callback, data)
            return
        }

        if (data.startsWith("pl.")) {
            processPlaylistInline(callback, data)
            return
        }

        if (data.startsWith("vd.")) {
            processVideoInline(callback, data)
            return
        }
    }


    override fun onPhotoMessageReceived(event: PhotoMessageReceivedEvent?) {
        var videoEntry = videoSessions.map.entries.filter { e -> e.value.chatId.equals(event!!.chat.id) }
                .firstOrNull()

        if (videoEntry != null) {
            processVideoThumbnail(event!!, videoEntry)
            return
        }
    }

    private fun processVideoThumbnail(event: PhotoMessageReceivedEvent,
                              entry: MutableMap.MutableEntry<Int, VideoSession>) {
        var session = entry.value

        if (!session.pendingImage) {
            return
        }

        var content = event.content.content

        if (content.size < 1) {
            return
        }

        content[0].downloadFile(instance.bot, File("${session.videoId}.jpg"))
        event.chat.sendMessage(SendableTextMessage.builder()
                .replyTo(event.message)
                .message("Updated!").build())

        session.pendingImage = false
        session.options.thumbnailUrl = "N/A"
        session.options.thumbnail = true

        instance.bot.editMessageReplyMarkup(session.chatId, session.botMessageId, videoKeyboardFor(entry.key))
    }

    override fun onTextMessageReceived(event: TextMessageReceivedEvent?) {
        println("received a message") // debug
        var playlistEntry = playlistSessions.map.entries.filter { e -> e.value.chatId.equals(event!!.chat.id) }
                .firstOrNull()
        var videoEntry = videoSessions.map.entries.filter { e -> e.value.chatId.equals(event!!.chat.id) }
                .firstOrNull()

        if (playlistEntry != null) {
            processPlaylistMessage(event, playlistEntry)
            return
        }

        if (videoEntry != null) {
            processVideoMessage(event, videoEntry)
            return
        }

        if (userSearch.containsKey(event!!.message.sender.id)) {
            processSearchSelection(event)
            return
        }

        var query = event.content.content

        if (query.contains("@YTDL_Bot") && !query.startsWith("@YTDL_Bot")) {
            return // probably just a mention, not a search
        }

        if (query.startsWith("@YTDL_Bot")) { // group chats
            query = query.replace("@YTDL_Bot ", "")
        }

        processInput(query,event.chat, event.message)
    }

    private fun processSearchSelection(event: TextMessageReceivedEvent) {
        var userId = event.message.sender.id
        var videos = userSearch[userId]
        var selected = videos!!.filter { e -> e.title.equals(event.content.content) }.firstOrNull()

        userSearch.remove(userId)

        if (selected == null) {
            event.chat.sendMessage(SendableTextMessage.builder()
                    .message("That selection was not an option!")
                    .replyMarkup(ReplyKeyboardHide.builder().selective(true).build())
                    .replyTo(event.message).build())
            return
        }

        var link = "https://www.youtube.com/watch?v=${selected.videoId}"
        var duration = preconditionVideo(link, event.chat, false)

        if (duration == -1L) {
            return
        }

        sendVideo(event.chat, link, false, event.message, userId, null, duration)
    }

    private fun processPlaylistMessage(event: TextMessageReceivedEvent?,
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

    private fun processVideoMessage(event: TextMessageReceivedEvent?,
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
        } else if ("tn".equals(selecting)) {
            if ("Custom".equals(message)) {
                session.pendingImage = true
                event.chat.sendMessage(SendableTextMessage.builder()
                        .replyTo(event.message)
                        .replyMarkup(ReplyKeyboardHide.builder().selective(true).build())
                        .message("Please send your thumbnail...").build())
            } else if ("Default".equals(message)) {
                session.options.thumbnail = true
                session.thumbnail = thumbnails[session.videoId]!!
                event.chat.sendMessage(SendableTextMessage.builder()
                        .replyTo(event.message)
                        .replyMarkup(ReplyKeyboardHide.builder().selective(true).build())
                        .message("Updated!").build())
            } else {
                event.chat.sendMessage("Please use the keyboard to continue...")
                return
            }
        }

        session.selecting = "N/A" // reset back to processVideoInline()
        instance.bot.editMessageReplyMarkup(session.chatId, session.botMessageId, videoKeyboardFor(entry.key))
    }

    fun <T> fetchSession(data: String, index: Int, minSize: Int, list: IdList<T>): T? {
        var sessionId: Int

        if (data.split(".").size < minSize) {
            return null
        }

        try {
            sessionId = data.split(".")[index].toInt()
        } catch (ignored: Exception) {
            println("not an int after . $data")
            return null // r00d for returning bad data
        }

        return list.get(sessionId)
    }

    fun processSelectionInline(callback: CallbackQuery, data: String) {
        var session = fetchSession(data, 1, 2, inlineList) ?: return // stop returning bad data

        if (session.userId != callback.from.id) {
            println("session which belongs to ${session.userId} doesn't match sender ${callback.from.id}")
            return // ensure session belongs to this user
        }

        callback.answer("Selecting...", false)

        if (data.startsWith("v.")) {
            sendVideo(session.chat, "https://www.youtube.com/watch?v=${session.videoMatch}", true, session.originalMessage, session.userId, null, session.duration)
        } else {
            sendPlaylist(session.chat, "https://www.youtube.com/playlist?list=${session.playlistMatch}", null, session.userId, session.playlistVideos)
        }

        inlineList.remove(session)
    }

    fun processVideoInline(callback: CallbackQuery, data: String) {
        var session = fetchSession(data, 2, 3, videoSessions) ?: return // stop returning bad data
        var selection = data.split(".")[1]

        if ("p".equals(selection)) { // exit route
            callback.answer("Sending to processing queue...", false)
            session.options.thumbnailUrl = session.thumbnail
            sendVideo(session.chat, session.link, session.linkSent, session.originalQuery, session.userId, session.options, session.duration)
            return
        }

        if ("tn".equals(selection)) {
            if (session.options.thumbnail) {
                callback.answer("Disabling thumbnail...", false)
                var thumbnail = File("${session.videoId}.jpg")

                if (thumbnail.exists()) {
                    thumbnail.delete()
                }

                session.options.thumbnail = false
            } else {
                callback.answer("Please answer the following question accordingly...", false)
                var replyKeyboard = ReplyKeyboardMarkup.builder()
                        .addRow(KeyboardButton.builder().text("Custom").build(),
                                KeyboardButton.builder().text("Default").build()).build()
                session.chat.sendMessage(SendableTextMessage.builder()
                        .message("Which type of thumbnail would you like to use?")
                        .replyMarkup(replyKeyboard).build())
                session.selecting = selection
            }
            /*session.options.thumbnail = !session.options.thumbnail
            instance.bot.editMessageReplyMarkup(session.chatId, session.botMessageId, videoKeyboardFor(data.split(".")[2].toInt()))
            callback.answer("Updated...", false)*/
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

    fun processPlaylistInline(callback: CallbackQuery, data: String) {
        var session = fetchSession(data, 2, 3, playlistSessions) ?: return // stop returning bad data
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

    // disable until viable method of this working is found
    /*override fun onInlineQueryReceived(event: InlineQueryReceivedEvent?) {
        var query = event!!.query
        var response = instance.search(query.query)
        var videos = ArrayList<InlineQueryResult>(response.size)

        response.items.forEach { e -> run {
            videos.add(InlineQueryResultArticle.builder()
                    .thumbUrl(URL(e.snippet.thumbnails.default.url))
                    .title(e.snippet.title)
                    .messageText("Uploaded by ${e.snippet.channelTitle}")
                    .build())
        } }

        query.answer(instance.bot, InlineQueryResponse.builder().results(videos).build())
    }

    override fun onInlineCallbackQueryReceivedEvent(event: InlineCallbackQueryReceivedEvent?) {
        var query = event!!.callbackQuery

        // TODO respond properly
    }*/

    private fun videoKeyboardFor(id: Int): InlineKeyboard {
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
                .addRow(InlineKeyboardButton.builder().text("Send to Processing...")
                                             .callbackData("vd.p.$id").build())
                .build()
    }

    private fun friendlyBoolean(bool: Boolean): String {
        if (bool) {
            return "Yes"
        } else {
            return "No"
        }
    }

    fun sendVideo(chat: Chat, link: String, linkSent: Boolean, originalQuery: Message?, userId: Long,
                  optionz: VideoOptions?, duration: Long) {
        if ((chat !is GroupChat && chat !is SuperGroupChat) && optionz == null) {
            var id = videoSessions.add(VideoSession(instance, chat.id, link, VideoOptions(0, duration), chat, linkSent, userId, originalQuery, duration))
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
            return
        }

        var options = optionz ?: VideoOptions()

        timeoutCache.put(userId, Object())
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
        var regex = instance.videoRegex.matcher(link)
        regex.matches()
        var video = instance.downloadVideo(options, regex.group(1))
        var md = SendableTextMessage.builder()
                .message(descriptionFor(video, linkSent))
                .parseMode(ParseMode.MARKDOWN)

        if (originalQuery != null) {
            md.replyTo(originalQuery)
        }

        var markup = chat.sendMessage(md.build())
        var audio = video.sendable().replyTo(markup)

        if (!"N/A".equals(options.customTitle)) {
            audio.title(options.customTitle)
        }

        if (!"N/A".equals(options.customPerformer)) {
            audio.performer(options.customPerformer)
        }

        chat.sendMessage(audio.build())
        timeoutCache.invalidate(userId)
        video.file.delete()

        File("${video.id}.info.json").delete()
        removeVideoSession(chat.id)
    }

    fun sendPlaylist(chat: Chat, link: String, options: PlaylistOptions?, userId: Long, itemCount: Long) {
        if ((chat !is GroupChat && chat !is SuperGroupChat) && options == null) {
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

        if (option == null) {
            option = PlaylistOptions(true)
        }

        timeoutCache.put(userId, Object())
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
                    .message(descriptionFor(video, true))
                    .parseMode(ParseMode.MARKDOWN)
                    .build())
            chat.sendMessage(video.sendable().replyTo(md).build())
        }

        chat.sendMessage("Finished sending playlist!")
        timeoutCache.invalidate(userId)
        playlist.folder.deleteRecursively() // bye bye
        removePlaylistSession(chat.id)
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
}

private data class CommandSession(val videoMatch: String, val playlistMatch: String, val chat: Chat,
                                  val userId: Long, val originalMessage: Message, val playlistVideos: Long,
                                  val duration: Long)

private data class PlaylistSession(val chatId: String, val options: PlaylistOptions = PlaylistOptions(),
                                   val chat: Chat, val link: String, var selecting: String = "N/A",
                                   val userId: Long, val videoCount: Long)

private data class VideoSession(val instance: YoutubeBot, val chatId: String, val link: String, val options: VideoOptions = VideoOptions(),
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

private data class CachedYoutubeVideo(val videoId: String, val title: String)
