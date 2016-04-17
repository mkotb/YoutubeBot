package pw.mzn.youtubebot

import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.inline.send.InlineQueryResponse
import pro.zackpollard.telegrambot.api.chat.inline.send.results.InlineQueryResult
import pro.zackpollard.telegrambot.api.chat.inline.send.results.InlineQueryResultArticle
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.event.Listener
import pro.zackpollard.telegrambot.api.event.chat.inline.InlineCallbackQueryReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.inline.InlineQueryReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.CommandMessageReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardButton
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardMarkup
import java.io.File
import java.net.URL
import java.text.NumberFormat
import java.time.Duration
import java.util.*

class CommandHandler(val instance: YoutubeBot): Listener {
    override fun onCommandMessageReceived(event: CommandMessageReceivedEvent?) {
        Thread() { processCommand(event) }.start()
    }

    fun processCommand(event: CommandMessageReceivedEvent?) {
        if ("download".equals(event!!.command)) {
            if (event.args.size < 1) {
                event.chat.sendMessage("give me")
                return
            }

            var link = event.args[0] // i'd be surprised if we made it here
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
                videoMatcher.matches()
                playlistMatcher.matches()

                val selectionKeyboard = InlineKeyboardMarkup.builder()
                        .addRow(InlineKeyboardButton.builder().text("Playlist").callbackData("p.${playlistMatcher.group(2)}").build(),
                                InlineKeyboardButton.builder().text("Video").callbackData("v.${videoMatcher.group(1)}").build())
                        .build()
                var response = SendableTextMessage.builder()
                        .message("That link matches both a playlist and a video, which of those would you" +
                                " like to download?")
                        .replyMarkup(selectionKeyboard)
                        .build()

                event.chat.sendMessage(response)
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

                if (Duration.parse(response.items[0].contentDetails.duration).seconds > 1800L) { // god bless you java 8 (ISO 8601)
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

                sendPlaylist(event.chat, link)
                return
            }
        }
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

    fun sendPlaylist(chat: Chat, link: String) {
        chat.sendMessage("Downloading all videos and extracting their audio... This will take a while.")
        var regex = instance.playlistRegex.matcher(link)
        regex.matches()
        var playlist = instance.downloadPlaylist(PlaylistOptions(true), regex.group(regex.groupCount()))

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