package pw.mzn.youtubebot

import pro.zackpollard.telegrambot.api.chat.Chat
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.event.Listener
import pro.zackpollard.telegrambot.api.event.chat.message.CommandMessageReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardButton
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardMarkup
import java.io.File
import java.text.NumberFormat

class CommandHandler(val instance: YoutubeBot): Listener {
    override fun onCommandMessageReceived(event: CommandMessageReceivedEvent?) {
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
                event.chat.sendMessage("Please provide a correct Youtube Video link!")
                return
            }

            if (matchesVideo && matchesPlaylist) {
                videoMatcher.matches()
                playlistMatcher.matches()
                val selectionKeyboard = InlineKeyboardMarkup.builder()
                        .addRow(InlineKeyboardButton.builder().text("Playlist")
                                                     .callbackData("p.${playlistMatcher.group(playlistMatcher.groupCount())}")
                                                     .build(),
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
                sendVideo(event.chat, link)
                return
            }

            if (matchesPlaylist) {
                sendPlaylist(event.chat, link)
                return
            }
        }
    }

    fun sendVideo(chat: Chat, link: String) {
        chat.sendMessage("Downloading video and extracting audio (Depending on duration of video, this may take a while)")
        var regex = instance.videoRegex.matcher(link)
        regex.matches()
        var video = instance.downloadVideo(regex.group(1))

        chat.sendMessage(SendableTextMessage.builder()
                .message(descriptionFor(video))
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
                    .message(descriptionFor(video))
                    .parseMode(ParseMode.MARKDOWN)
                    .build())
            chat.sendMessage(video.sendable())
        }

        chat.sendMessage("Finished sending playlist!")
        playlist.folder.delete() // bye bye
    }

    fun descriptionFor(video: YoutubeVideo): String {
        var metadata = video.metadata
        var messageBuilder = StringBuilder("*${metadata.name}*\n")

        messageBuilder.append("*Uploaded by* _${metadata.uploader}_\n")
        messageBuilder.append("*Views:* ${NumberFormat.getInstance().format(metadata.viewCount)}\n")
        messageBuilder.append("👍 ${NumberFormat.getInstance().format(metadata.likes)}\n")
        messageBuilder.append("👎 ${NumberFormat.getInstance().format(metadata.dislikes)}\n")

        return messageBuilder.toString()
    }
}