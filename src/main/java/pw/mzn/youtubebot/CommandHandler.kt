package pw.mzn.youtubebot

import pro.zackpollard.telegrambot.api.chat.CallbackQuery
import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.inline.send.InlineQueryResponse
import pro.zackpollard.telegrambot.api.chat.inline.send.results.InlineQueryResult
import pro.zackpollard.telegrambot.api.chat.inline.send.results.InlineQueryResultArticle
import pro.zackpollard.telegrambot.api.chat.message.content.TextContent
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.event.Listener
import pro.zackpollard.telegrambot.api.event.chat.CallbackQueryReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.inline.InlineCallbackQueryReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.inline.InlineQueryReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.CommandMessageReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.MessageReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardButton
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardMarkup
import java.io.File
import java.net.URL
import java.text.NumberFormat
import java.util.*

class CommandHandler(val instance: YoutubeBot): Listener {
    private val inlineList = IdList<CommandSession>()
    private val playlistSessions = IdList<PlaylistSession>()

    override fun onCommandMessageReceived(event: CommandMessageReceivedEvent?) {
        Thread() { processCommand(event) }.start()
    }

    fun processCommand(event: CommandMessageReceivedEvent?) {
        if ("download".equals(event!!.command)) {
            if (event.args.size < 1) {
                event.chat.sendMessage("give me")
                return
            }

            flushSessions(event.message.sender.id, event.chat.id)

            var link = event.args[0]
            var videoMatcher = instance.videoRegex.matcher(link)
            var playlistMatcher = instance.playlistRegex.matcher(link)
            var matchesVideo = videoMatcher.find()
            var matchesPlaylist = playlistMatcher.find()

            if (!matchesVideo && !matchesPlaylist) {
                var response = instance.search(event.argsString)
                var result = response.items[0]

                if (result != null && "youtube#video".equals(result.id.kind)) {
                    sendVideo(event.chat, "https://www.youtube.com/watch?v=${result.id.videoId}", false)
                } else {
                    event.chat.sendMessage("No videos were found by that query!")
                }
            }

            if (matchesVideo && matchesPlaylist) {
                var videoMatch = videoMatcher.group(1)
                playlistMatcher = instance.playlistRegex.matcher(link)
                playlistMatcher.lookingAt()
                var sessionId = inlineList.add(CommandSession(videoMatch, playlistMatcher.group(2), event.chat,
                        event.message.sender.id))

                val selectionKeyboard = InlineKeyboardMarkup.builder()
                        .addRow(InlineKeyboardButton.builder().text("Playlist").callbackData("p.$sessionId").build(),
                                InlineKeyboardButton.builder().text("Video").callbackData("v.$sessionId").build())
                        .build()
                var response = SendableTextMessage.builder()
                        .message("That link matches both a playlist and a video, which of those would you" +
                                " like to download?")
                        .replyMarkup(selectionKeyboard)
                        .build()

                event.chat.sendMessage(response) // next process of new data is in processSelectionInline()
                return
            }

            if (matchesVideo) {
                // check video length
                var search = instance.youtube.videos().list("contentDetails")
                var regex = instance.videoRegex.matcher(link)
                regex.matches()

                search.id = regex.group(1)
                search.fields = "items(contentDetails/duration)"
                search.key = instance.youtubeKey
                var response = search.execute()

                if (response.items.isEmpty()) {
                    event.chat.sendMessage("Unable to find any Youtube video by that ID!")
                    return
                }

                if (instance.parse8601Duration(response.items[0].contentDetails.duration) > 1800L) {
                    event.chat.sendMessage("This bot is unable to process videos longer than 30 minutes! Sorry!")
                    return
                }

                sendVideo(event.chat, link, true)
                return
            }

            if (matchesPlaylist) {
                // attempt to find said playlist
                var search = instance.youtube.playlists().list("id")
                var regex = instance.playlistRegex.matcher(link)
                regex.matches()

                search.id = regex.group(regex.groupCount())
                search.fields = "items(id)"
                search.key = instance.youtubeKey
                var response = search.execute()

                if (response.items.isEmpty()) {
                    event.chat.sendMessage("Unable to find any playlists by that ID!")
                    return
                }

                sendPlaylist(event.chat, link, null)
                return
            }
        }
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

    override fun onMessageReceived(event: MessageReceivedEvent?) {
        println("received a message") // debug
        var entry = playlistSessions.map.entries.filter { e -> e.value.chatId.equals(event!!.chat.id) }
                .firstOrNull() ?: return
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
                try {
                    var int = e.toInt()

                    if (int < 20) // not allow malicious selections
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
            sendPlaylist(session.chat, session.link, session.options)
        } else if ("sh".equals(session.selecting)) {
            session.options.matchRegex = content.content
            sendPlaylist(session.chat, session.link, session.options)
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
            sendVideo(session.chat, "https://www.youtube.com/watch?v=${session.videoMatch}", true)
        } else {
            sendPlaylist(session.chat, "https://www.youtube.com/playlist?list=${session.playlistMatch}", null)
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
            sendPlaylist(session.chat, session.link, session.options)
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

    override fun onInlineQueryReceived(event: InlineQueryReceivedEvent?) {
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
    }

    fun sendVideo(chat: Chat, link: String, linkSent: Boolean) {
        chat.sendMessage("Downloading video and extracting audio (Depending on duration of video, this may take a while)")
        var regex = instance.videoRegex.matcher(link)
        regex.matches()
        var video = instance.downloadVideo(regex.group(1))

        chat.sendMessage(SendableTextMessage.builder()
                .message(descriptionFor(video, linkSent))
                .parseMode(ParseMode.MARKDOWN)
                .build())
        chat.sendMessage(video.sendable())
        video.file.delete()
        File("${video.id}.info.json").delete()
    }

    fun sendPlaylist(chat: Chat, link: String, options: PlaylistOptions?) {
        if (chat is Chat && options == null) {
            var id = playlistSessions.add(PlaylistSession(chat.id, PlaylistOptions(), chat, link))
            var selectionKeyboard = InlineKeyboardMarkup.builder()
                    .addRow(InlineKeyboardButton.builder().text("Selection").callbackData("pl.sn.$id").build(),
                            InlineKeyboardButton.builder().text("Search").callbackData("pl.sh.$id").build())
                    .addRow(InlineKeyboardButton.builder().text("All Videos").callbackData("pl.av.$id").build())
                    .build()
            var response = SendableTextMessage.builder()
                    .message("How would you like to select the videos from this playlist?")
                    .replyMarkup(selectionKeyboard)
                    .build()

            chat.sendMessage(response) // next process of new data is in processPlaylistInline()
            return
        }

        var option = options

        if (option == null) {
            option = PlaylistOptions(true)
        }

        chat.sendMessage("Downloading all videos and extracting their audio... This will take a while.")
        var regex = instance.playlistRegex.matcher(link)
        regex.matches()
        var playlist = instance.downloadPlaylist(option, regex.group(regex.groupCount()))

        chat.sendMessage("Finished processing! Sending ${playlist.videoList.size} videos...")

        for (video in playlist.videoList) {
            chat.sendMessage(SendableTextMessage.builder()
                    .message(descriptionFor(video, true))
                    .parseMode(ParseMode.MARKDOWN)
                    .build())
            chat.sendMessage(video.sendable())
        }

        chat.sendMessage("Finished sending playlist!")
        playlist.folder.delete() // bye bye
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
                                  val userId: Long)

private data class PlaylistSession(val chatId: String, val options: PlaylistOptions = PlaylistOptions(),
                                   val chat: Chat, val link: String, var selecting: String = "N/A")