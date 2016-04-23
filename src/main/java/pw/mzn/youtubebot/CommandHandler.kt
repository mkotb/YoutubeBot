package pw.mzn.youtubebot

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import pro.zackpollard.telegrambot.api.chat.CallbackQuery
import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.message.Message
import pro.zackpollard.telegrambot.api.chat.message.content.TextContent
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.event.Listener
import pro.zackpollard.telegrambot.api.event.chat.CallbackQueryReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.CommandMessageReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.TextMessageReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.*
import java.io.File
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CommandHandler(val instance: YoutubeBot): Listener {
    private val userSearch = ConcurrentHashMap<Long, List<CachedYoutubeVideo>>()
    private val inlineList = IdList<CommandSession>()
    private val playlistSessions = IdList<PlaylistSession>()
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
                     "favourite songs. Give it a try, send any query!")
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
            var validVideo = preconditionVideo(link, chat, true)
            var playlistVideoCount = preconditionPlaylist(link, chat, true)

            if (!validVideo && playlistVideoCount == -1L) {
                chat.sendMessage("Neither the playlist nor the video in that link could be found!")
                return
            }

            if (validVideo && playlistVideoCount == -1L) {
                matchesPlaylist = false
            }

            if (!validVideo && playlistVideoCount != -1L) {
                matchesVideo = false
            }

            if (validVideo && playlistVideoCount != -1L) {
                var videoMatch = videoMatcher.group(1)
                playlistMatcher = instance.playlistRegex.matcher(link)
                playlistMatcher.lookingAt()
                var sessionId = inlineList.add(CommandSession(videoMatch, playlistMatcher.group(2), chat,
                        userId, message, playlistVideoCount))

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
            if (preconditionVideo(link, chat, false)) {
                sendVideo(chat, link, true, message, userId)
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
        regex.matches()

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

    fun preconditionVideo(link: String, chat: Chat, silent: Boolean): Boolean {
        var search = instance.youtube.videos().list("contentDetails")
        var regex = instance.videoRegex.matcher(link)
        regex.matches()

        search.id = regex.group(1)
        search.fields = "items(contentDetails/duration)"
        search.key = instance.youtubeKey
        var response = search.execute()

        if (response.items.isEmpty()) {
            if (!silent) {
                chat.sendMessage("Unable to find any Youtube video by that ID!")
            }
            return false
        }

        if (instance.parse8601Duration(response.items[0].contentDetails.duration) > 3600L) {
            if (!silent) {
                chat.sendMessage("This bot is unable to process videos longer than 1 hour! Sorry!")
            }
            return false
        }

        return true
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
        removePlaylistSession(chatId)
        removeInlineSession(userId)
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
    }

    override fun onTextMessageReceived(event: TextMessageReceivedEvent?) {
        println("received a message") // debug
        var entry = playlistSessions.map.entries.filter { e -> e.value.chatId.equals(event!!.chat.id) }
                .firstOrNull()

        if (entry != null) {
            processPlaylistMessage(event, entry)
            return
        }

        if (userSearch.containsKey(event!!.message.sender.id)) {
            processSearchSelection(event)
            return
        }

        var query = event.content.content

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

        sendVideo(event.chat, "https://www.youtube.com/watch?v=${selected.videoId}", false, event.message, userId)
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

    fun processSelectionInline(callback: CallbackQuery, data: String) {
        var sessionId: Int

        try {
            sessionId = data.split(".")[1].toInt()
        } catch (ignored: Exception) {
            println("not an int after . $data")
            return // r00d for returning bad data
        }

        var session = inlineList.get(sessionId) ?: return // stop returning bad data

        if (session.userId != callback.from.id) {
            println("session which belongs to ${session.userId} doesn't match sender ${callback.from.id}")
            return // ensure session belongs to this user
        }

        callback.answer("Selecting...", false)

        if (data.startsWith("v.")) {
            sendVideo(session.chat, "https://www.youtube.com/watch?v=${session.videoMatch}", true, session.originalMessage, session.userId)
        } else {
            sendPlaylist(session.chat, "https://www.youtube.com/playlist?list=${session.playlistMatch}", null, session.userId, session.playlistVideos)
        }

        inlineList.remove(session)
    }

    fun processPlaylistInline(callback: CallbackQuery, data: String) {
        var sessionId: Int

        if (data.split(".").size < 3) {
            return
        }

        try {
            sessionId = data.split(".")[2].toInt()
        } catch (ignored: Exception) {
            println("not an int after second . $data")
            return // r00d for returning bad data
        }

        var session = playlistSessions.get(sessionId) ?: return // stop returning bad data
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

        // sn & sh move their next data processing to onMessageReceived
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

    fun sendVideo(chat: Chat, link: String, linkSent: Boolean, originalQuery: Message?, userId: Long) {
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
        var video = instance.downloadVideo(regex.group(1))
        var md = SendableTextMessage.builder()
                .message(descriptionFor(video, linkSent))
                .parseMode(ParseMode.MARKDOWN)

        if (originalQuery != null) {
            md.replyTo(originalQuery)
        }

        var markup = chat.sendMessage(md.build())
        chat.sendMessage(video.sendable().replyTo(markup).build())
        timeoutCache.invalidate(userId)
        video.file.delete()
        File("${video.id}.info.json").delete()
    }

    fun sendPlaylist(chat: Chat, link: String, options: PlaylistOptions?, userId: Long, itemCount: Long) {
        if (chat is Chat && options == null) {
            var id = playlistSessions.add(PlaylistSession(chat.id, PlaylistOptions(), chat, link, "N/A", userId, itemCount))
            var selectionKeyboard = InlineKeyboardMarkup.builder()
                    .addRow(InlineKeyboardButton.builder().text("Selection").callbackData("pl.sn.$id").build(),
                            InlineKeyboardButton.builder().text("Search").callbackData("pl.sh.$id").build())

            if (itemCount <= 50) {
                selectionKeyboard.addRow(InlineKeyboardButton.builder().text("All Videos").callbackData("pl.av.$id").build())
            }

            var response = SendableTextMessage.builder()
                    .message("How would you like to select the videos from this playlist?")
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
            option.videoSelection = IntRange(0, itemCount.toInt()).toMutableList()
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
                                  val userId: Long, val originalMessage: Message, val playlistVideos: Long)

private data class PlaylistSession(val chatId: String, val options: PlaylistOptions = PlaylistOptions(),
                                   val chat: Chat, val link: String, var selecting: String = "N/A",
                                   val userId: Long, val videoCount: Long)

private data class CachedYoutubeVideo(val videoId: String, val title: String)