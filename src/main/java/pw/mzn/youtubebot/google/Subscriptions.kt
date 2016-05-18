package pw.mzn.youtubebot.google

import com.google.api.client.googleapis.batch.json.JsonBatchCallback
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.http.HttpHeaders
import com.google.api.client.util.DateTime
import com.google.api.services.youtube.model.ChannelListResponse
import com.google.api.services.youtube.model.PlaylistItemListResponse
import com.google.common.collect.Lists
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pw.mzn.youtubebot.YoutubeBot
import java.util.*
import java.util.concurrent.TimeUnit

class SubscriptionsTask(val instance: YoutubeBot, val timer: Timer): TimerTask() {
    override fun run() {
        var youtube = instance.youtube
        var channels = instance.dataManager.channels.map { e -> e.channelId }
        var batch = youtube.batch()
        var channelsList = Lists.partition(channels, 5)

        channelsList.forEach { e -> run() {
            var channelList = youtube.channels().list("id,contentDetails")

            channelList.key = instance.googleKeys[instance.nextKeyIndex()]
            channelList.id = e.joinToString(",")
            channelList.fields = "items(id,contentDetails/relatedPlaylists/uploads)"

            channelList.queue(batch, ChannelCallback(instance, channelList.key))
        } }

        println("batching ${batch.size()} requests")

        if (batch.size() >= 1) {
            try {
                batch.execute()
            } catch (e: Exception) {
                e.printStackTrace() // continue execution
            }
        }

        println("checked for new videos")
        timer.schedule(SubscriptionsTask(instance, timer), TimeUnit.MINUTES.toMillis(30L)) // faire, aller, voir, avoir, etre
    }
}

class ChannelCallback(val instance: YoutubeBot, val key: String): JsonBatchCallback<ChannelListResponse>() {
    override fun onSuccess(response: ChannelListResponse?, p1: HttpHeaders?) {
        var batch = instance.youtube.batch()
        var callback = SubscriptionCallback(instance)

        response?.items?.forEach { e -> run() {
            var uploadsList = instance.youtube.playlistItems().list("id,snippet")

            uploadsList.key = key
            uploadsList.playlistId = e.contentDetails.relatedPlaylists.uploads
            uploadsList.fields = "items(id,snippet/publishedAt,snippet/resourceId/videoId," +
                    "snippet/playlistId,snippet/title,snippet/channelId)"

            uploadsList.execute().items
            uploadsList.queue(batch, callback)
        }}

        println("batched ${batch.size()} queries")

        if (batch.size() >= 1) {
            try {
                batch.execute()
            } catch (e: Exception) {
                e.printStackTrace() // continue execution
            }
        }
    }

    override fun onFailure(p0: GoogleJsonError?, p1: HttpHeaders?) {
        throw UnsupportedOperationException()
    }
}

data class SubscriptionPlaylistVid(val videoId: String, val published: DateTime, val title: String,
                                   val playlistId: String, val channelId: String)

class SubscriptionCallback(val instance: YoutubeBot): JsonBatchCallback<PlaylistItemListResponse>() {
    override fun onSuccess(res: PlaylistItemListResponse, p1: HttpHeaders?) {
        res.items.map { e -> SubscriptionPlaylistVid(e.snippet.resourceId.videoId, e.snippet.publishedAt, e.snippet.title,
                e.snippet.playlistId, e.snippet.channelId)}
                .filter { e -> Date(e.published.value).after(Date(System.currentTimeMillis() -
                        TimeUnit.MINUTES.toMillis(35L))) }
                .forEach { vid ->
                    run() {
                        var channel = instance.dataManager.channelBy(vid.channelId)

                        channel?.subscribed?.forEach { e ->
                            instance.bot.getChat(e).sendMessage(SendableTextMessage.builder()
                                    .message("*${channel.channelName} has uploaded a new video!*\n_${vid.title}_\n" +
                                            "[Watch here](https://www.youtube.com/watch?v=${vid.videoId})")
                                    .parseMode(ParseMode.MARKDOWN)
                                    .build())
                        }
                    }
                }
    }

    override fun onFailure(p0: GoogleJsonError?, p1: HttpHeaders?) {
        throw UnsupportedOperationException()
    }
}