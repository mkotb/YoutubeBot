package pw.mzn.youtubebot.handler

import pro.zackpollard.telegrambot.api.chat.CallbackQuery
import pro.zackpollard.telegrambot.api.chat.message.send.InputFile
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendablePhotoMessage
import pro.zackpollard.telegrambot.api.event.Listener
import pro.zackpollard.telegrambot.api.event.chat.CallbackQueryReceivedEvent
import pw.mzn.youtubebot.IdList
import pw.mzn.youtubebot.YoutubeBot
import java.net.URL

class InlineHandler(val instance: YoutubeBot): Listener {
    val handler = instance.commandHandler

    override fun onCallbackQueryReceivedEvent(event: CallbackQueryReceivedEvent?) {
        var callback = event!!.callbackQuery
        var data = callback.data
        var from = callback.from.id

        if (data.split(".").size < 2) {
            return
        }

        if (data.startsWith("v.") || data.startsWith("p.")) { // validate
            processSelectionInline(callback, data)
            return
        }

        if (data.startsWith("pl.")) {
            instance.command.playlist.processPlaylistInline(callback, data)
            return
        }

        if (data.startsWith("vd.")) {
            instance.command.video.processVideoInline(callback, data)
            return
        }

        if (data.startsWith("lf.") && instance.command.video.trackStore.containsKey(from)) {
            processTrackInline(callback, data)
            return
        }

        if (data.startsWith("m.") && instance.command.video.matchingStore.containsKey(from)) {
            var session = instance.command.video.matchingStore[from]!!

            instance.command.video.processMatchInline(session, from,
                    session.selections.get(data.split(".")[1].toInt())!!, callback)
            return
        }

        if (data.startsWith("s.") && instance.command.video.videoSearch.containsKey(from)) {
            instance.command.video.processSearchInline(data.split(".")[1].toInt(), from, callback)
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
            var vidHand = instance.command.video
            vidHand.sendVideo(session.chat, "https://www.youtube.com/watch?v=${session.videoMatch}", true, session.originalMessage, session.userId, null, session.duration,
                    vidHand.titleCache.asMap()[session.videoMatch]!!, false, null)
        } else {
            instance.command.playlist.sendPlaylist(session.chat, "https://www.youtube.com/playlist?list=${session.playlistMatch}", null, session.userId, session.playlistVideos)
        }

        handler.inlineList.remove(session)
    }

    fun processTrackInline(callback: CallbackQuery, data: String) {
        if (data.split(".").size != 2) {
            return
        }

        var video = instance.command.video
        var selected = "y".equals(data.split(".")[1])
        var trackSession = video.trackStore[callback.from.id]!!
        var options = trackSession.videoSession.options
        var track = trackSession.track

        if (selected) {
            var message = "Set the title, performer"
            options.customTitle = track.name
            options.customPerformer = track.artist
            var imageUrl = track.coverUrl

            if (!"".equals(imageUrl)) {
                options.thumbnailUrl = imageUrl
                options.thumbnail = true
                trackSession.videoSession.thumbnail = imageUrl
                message += ", and thumbnail for you! Here is a preview of the thumbnail:"
                trackSession.videoSession.chat.sendMessage(SendablePhotoMessage.builder()
                        .photo(InputFile(URL(imageUrl)))
                        .build())
                println(imageUrl)
            } else {
                message += " for you!"
            }

            instance.bot.editMessageText(trackSession.videoSession.chatId, trackSession.videoSession.botMessageId,
                    message, ParseMode.NONE, false, null)
        }

        var id = video.videoSessions.add(trackSession.videoSession)
        video.initCustomization(id, trackSession.videoSession.originalQuery, trackSession.videoSession.chat, trackSession.videoSession.botMessageId)
    }
}