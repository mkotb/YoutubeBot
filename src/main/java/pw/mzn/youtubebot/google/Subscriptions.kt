package pw.mzn.youtubebot.google

import com.google.api.client.util.DateTime
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pw.mzn.youtubebot.YoutubeBot
import java.util.*
import java.util.concurrent.TimeUnit

class SubscriptionsTask(val instance: YoutubeBot, val timer: Timer): TimerTask() {
    override fun run() {
        var playlists = HashMap<String, String>()
        var youtube = instance.youtube
        var channelList = youtube.channels().list("id,contentDetails")

        channelList.key = instance.googleKeys[instance.nextKeyIndex()]
        channelList.id = instance.dataManager.channels
                .map { e -> e.channelId }
                .joinToString(",")
        channelList.fields = "items(id,contentDetails/relatedPlaylists/uploads)"

        channelList.execute().items.forEach { e -> playlists.put(e.contentDetails.relatedPlaylists.uploads, e.id); println(e.id) }
        var uploadsList = youtube.playlistItems().list("id,snippet")

        uploadsList.key = channelList.key
        uploadsList.id = playlists.keys.joinToString(",")
        uploadsList.fields = "items(id,snippet/publishedAt,snippet/resourceId/videoId," +
                "snippet/playlistId,snippet/title)"

        uploadsList.execute().items
                .map { e -> println(e.snippet.resourceId.videoId); SubscriptionPlaylistVid(e.snippet.resourceId.videoId, e.snippet.publishedAt,
                        e.snippet.title, e.snippet.playlistId)}
                .filter { e -> Date(e.published.value).after(Date(System.currentTimeMillis() -
                        TimeUnit.MINUTES.toMillis(35L))) }
                .forEach { vid ->
                    run() {
                        var channel = instance.dataManager.channelBy(playlists[vid.playlistId]!!)
                        println("sending!!11!")

                        channel?.subscribed?.forEach { e ->
                            instance.bot.getChat(e).sendMessage(SendableTextMessage.builder()
                                    .message("*${channel.channelName} has uploaded a new video!*\n${vid.title}\n" +
                                            "[Watch here](https://www.youtube.com/watch?v=${vid.videoId})\n" +
                                            "[Download](https://telegram.me/YoutubeMusic_Bot?start=${vid.videoId})")
                                    .parseMode(ParseMode.MARKDOWN)
                                    .disableWebPagePreview(true)
                                    .build())
                        }
                    }
                }

        println("checked for new videos")
        timer.schedule(SubscriptionsTask(instance, timer), TimeUnit.MINUTES.toMillis(30L))
    }
}

data class SubscriptionPlaylistVid(val videoId: String, val published: DateTime, val title: String,
                                   val playlistId: String)