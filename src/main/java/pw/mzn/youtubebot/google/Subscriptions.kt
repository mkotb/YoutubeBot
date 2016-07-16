/*
 * Copyright (c) 2016, Mazen Kotb, mazenkotb@gmail.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package pw.mzn.youtubebot.google

import com.google.api.client.googleapis.batch.BatchRequest
import com.google.api.client.googleapis.batch.json.JsonBatchCallback
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.http.HttpHeaders
import com.google.api.client.util.DateTime
import com.google.api.services.youtube.model.ChannelListResponse
import com.google.api.services.youtube.model.PlaylistItemListResponse
import com.google.common.collect.Lists
import pro.zackpollard.telegrambot.api.chat.message.send.ParseMode
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardButton
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardMarkup
import pw.mzn.youtubebot.YoutubeBot
import java.util.*
import java.util.concurrent.TimeUnit

class SubscriptionsTask(val instance: YoutubeBot, val timer: Timer): TimerTask() {
    override fun run() {
        var youtube = instance.youtube
        var channels = instance.dataManager.channels.map { e -> e.channelId }
        var batch = youtube.batch()
        var secondaryBatch = youtube.batch()
        var channelsList = Lists.partition(channels, 5)

        channelsList.forEach { e -> run() {
            var channelList = youtube.channels().list("id,contentDetails")

            channelList.key = instance.googleKeys[instance.nextKeyIndex()]
            channelList.id = e.joinToString(",")
            channelList.fields = "items(id,contentDetails/relatedPlaylists/uploads)"

            channelList.queue(batch, ChannelCallback(instance, channelList.key, secondaryBatch))
        } }

        println("batching ${batch.size()} requests")

        if (batch.size() >= 1) {
            try {
                batch.execute()
            } catch (e: Exception) {
                e.printStackTrace() // continue execution
            }
        }

        println("batching ${secondaryBatch.size()} requests")

        if (secondaryBatch.size() >= 1) {
            try {
                secondaryBatch.execute()
            } catch (e: Exception) {
                e.printStackTrace() // continue exec
            }
        }

        println("checked for new videos")
        timer.schedule(SubscriptionsTask(instance, timer), TimeUnit.MINUTES.toMillis(30L))
    }
}

class ChannelCallback(val instance: YoutubeBot, val key: String, val batch: BatchRequest): JsonBatchCallback<ChannelListResponse>() {
    override fun onSuccess(response: ChannelListResponse?, p1: HttpHeaders?) {
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
                        var markup = InlineKeyboardMarkup.builder()
                                .addRow(InlineKeyboardButton.builder()
                                        .text("⬇️ Download")
                                        .url("https://telegram.me/${instance.bot.botUsername}?start=${vid.videoId}")
                                        .build())
                                .build()

                        channel?.subscribed?.forEach { e ->
                            instance.bot.getChat(e).sendMessage(SendableTextMessage.builder()
                                    .message("*${channel.channelName} has uploaded a new video!*\n_${vid.title}_\n" +
                                            "[Watch here](https://www.youtube.com/watch?v=${vid.videoId})")
                                    .replyMarkup(markup)
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