package pw.mzn.youtubebot

import pro.zackpollard.telegrambot.api.chat.CallbackQuery
import pro.zackpollard.telegrambot.api.chat.message.send.InputFile
import pro.zackpollard.telegrambot.api.chat.message.send.SendablePhotoMessage
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.event.Listener
import pro.zackpollard.telegrambot.api.event.chat.CallbackQueryReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.KeyboardButton
import pro.zackpollard.telegrambot.api.keyboards.ReplyKeyboardMarkup
import java.io.File

class InlineHandler(val instance: YoutubeBot): Listener {
    var handler = instance.commandHandler

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

        if (data.startsWith("lf.") && handler.trackStore.containsKey(callback.from.id)) {
            processTrackInline(callback, data)
        }
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
        var session = fetchSession(data, 1, 2, handler.inlineList) ?: return // stop returning bad data

        if (session.userId != callback.from.id) {
            println("session which belongs to ${session.userId} doesn't match sender ${callback.from.id}")
            return // ensure session belongs to this user
        }

        callback.answer("Selecting...", false)

        if (data.startsWith("v.")) {
            handler.sendVideo(session.chat, "https://www.youtube.com/watch?v=${session.videoMatch}", true, session.originalMessage, session.userId, null, session.duration,
                    handler.titleCache.asMap()[session.videoMatch]!!)
        } else {
            handler.sendPlaylist(session.chat, "https://www.youtube.com/playlist?list=${session.playlistMatch}", null, session.userId, session.playlistVideos)
        }

        handler.inlineList.remove(session)
    }

    fun processVideoInline(callback: CallbackQuery, data: String) {
        var session = fetchSession(data, 2, 3, handler.videoSessions) ?: return // stop returning bad data
        var selection = data.split(".")[1]

        if ("p".equals(selection)) { // exit route
            callback.answer("Sending to processing queue...", false)
            session.options.thumbnailUrl = session.thumbnail
            handler.sendVideo(session.chat, session.link, session.linkSent, session.originalQuery, session.userId,
                    session.options, session.duration, handler.titleCache.asMap()[session.videoId]!!)
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
        var session = fetchSession(data, 2, 3, handler.playlistSessions) ?: return // stop returning bad data
        var selection = data.split(".")[1]

        if ("av".equals(selection)) {
            callback.answer("Selecting...", false)
            session.options.allVideos = true
            handler.sendPlaylist(session.chat, session.link, session.options, session.userId, session.videoCount)
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

    fun processTrackInline(callback: CallbackQuery, data: String) {
        if (data.split(".").size != 2) {
            return
        }

        var selected = "y".equals(data.split(".")[1])
        var trackSession = handler.trackStore[callback.from.id]!!
        var options = trackSession.videoSession.options
        var track = trackSession.track

        if (selected) {
            var message = "Set the title, performer"
            options.customTitle = track.name
            options.customPerformer = track.artist
            var imageUrl = track.coverUrl

            if (!"".equals(imageUrl)) {
                options.thumbnail = true
                options.thumbnailUrl = imageUrl
                message += ", and thumbnail for you! Here is a preview of the thumbnail:"
                trackSession.videoSession.chat.sendMessage(message)
                trackSession.videoSession.chat.sendMessage(SendablePhotoMessage.builder()
                        .photo(InputFile(imageUrl))
                        .build())
            } else {
                message += " for you!"
                trackSession.videoSession.chat.sendMessage(message)
            }
        }

        var id = handler.videoSessions.add(trackSession.videoSession)
        handler.initCustomization(id, trackSession.videoSession.originalQuery, trackSession.videoSession.chat)
    }
}