package pw.mzn.youtubebot

import com.google.common.cache.CacheBuilder
import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.GroupChat
import pro.zackpollard.telegrambot.api.chat.SuperGroupChat
import pro.zackpollard.telegrambot.api.chat.message.Message
import pro.zackpollard.telegrambot.api.chat.message.content.TextContent
import pro.zackpollard.telegrambot.api.chat.message.send.*
import pro.zackpollard.telegrambot.api.event.Listener
import pro.zackpollard.telegrambot.api.event.chat.message.CommandMessageReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.TextMessageReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.*
import java.io.File
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CommandHandler(val instance: YoutubeBot): Listener {
    val thumbnails = ConcurrentHashMap<String, String>() // key = video id, val = link
    val userSearch = ConcurrentHashMap<Long, List<CachedYoutubeVideo>>()
    val trackStore = ConcurrentHashMap<Long, TrackSession>()
    val inlineList = IdList<CommandSession>()
    val playlistSessions = IdList<PlaylistSession>()
    val videoSessions = IdList<VideoSession>()
    val timeoutCache = CacheBuilder.newBuilder()
            .concurrencyLevel(5)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build<Long, Any?>()
    val titleCache = CacheBuilder.newBuilder()
            .concurrencyLevel(5)
            .expireAfterWrite(8, TimeUnit.HOURS)
            .build<String, String>() // key = video id, val = title

    fun sendVideo(chat: Chat, link: String, linkSent: Boolean, originalQuery: Message?, userId: Long,
                  optionz: VideoOptions?, duration: Long, title: String) {
        var search = instance.searchTrack(title).toMutableList()

        if (((chat is GroupChat) || optionz == null) && !search.isEmpty()) {
            var track = search[0]
            var session = VideoSession(instance, chat.id, link, VideoOptions(0, duration), chat, linkSent, userId, originalQuery, duration)
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
            var id = videoSessions.add(VideoSession(instance, chat.id, link, VideoOptions(0, duration), chat, linkSent, userId, originalQuery, duration))
            initCustomization(id, originalQuery, chat)
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
                .disableWebPagePreview(true)
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

    fun processSearchSelection(event: TextMessageReceivedEvent) {
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

        if ("Cancel".equals(event.content.content)) {
            flushSessions(userId, event.chat.id)
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

        sendVideo(event.chat, link, false, event.message, userId, null, duration, titleCache.asMap()[selected.videoId]!!)
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
                sendVideo(chat, link, true, message, userId, null, duration, titleCache.asMap()[videoMatcher.group(1)]!!)
            }

            return
        }

        if (matchesPlaylist) {
            // attempt to find said playlist
            var count = instance.preconditionPlaylist(link, chat, false)

            if (count == -1L) {
                return
            }

            sendPlaylist(chat, link, null, userId, count)
            return
        }
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

        userSearch.put(userId, cachedVids)
        var message = SendableTextMessage.builder()
                .message("Select a Video")
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
}