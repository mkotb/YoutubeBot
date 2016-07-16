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

import com.google.api.client.auth.oauth2.Credential
import pw.mzn.youtubebot.YoutubeBot
import pw.mzn.youtubebot.data.SavedChannel
import java.util.*
import java.util.concurrent.TimeUnit

class Follower(val instance: YoutubeBot) {
    val timer = Timer()

    fun checkup(cred: Credential, chatId: String) {
        checkup(cred, chatId, null, 0, 1)
    }

    fun checkup(cred: Credential, chatId: String, token: String?, left: Int, times: Int) {
        var request = instance.youtube.subscriptions().list("id,snippet")
                .setMine(true)
                .setOauthToken(cred.accessToken)
                .setMaxResults(50L)

        if (token != null)
            request.pageToken = token

        println("created request")
        var response = request.execute()
        println("executed")

        response.items.forEach { e -> run {
            var channelId = e.snippet.resourceId.channelId
            var savedChannel = instance.dataManager.channelBy(channelId)

            if (savedChannel == null) {
                var following = ArrayList<Long>()

                following.add(chatId.toLong())
                instance.dataManager.channels.add(SavedChannel(channelId,
                        e.snippet.title, following))
                instance.dataManager.saveToFile()

                instance.bot.getChat(chatId).sendMessage("Updated! Successfully subscribed to ${e.snippet.title}")
                return@run
            } else if (!savedChannel.subscribed.contains(chatId.toLong())) {
                savedChannel.subscribed.add(chatId.toLong())
                instance.bot.getChat(chatId).sendMessage("Updated! Successfully subscribed to ${e.snippet.title}")
            }

            instance.dataManager.saveToFile()
        } }

        if (response.pageInfo.resultsPerPage * times < response.pageInfo.totalResults + left)
            checkup(cred, chatId, response.nextPageToken, response.pageInfo.totalResults + left, times + 1) // mfw that many subscriptions
    }
}

class FollowerTask(val owner: Follower): TimerTask() {
    override fun run() {
        try {
            var creds = owner.instance.youtubeUserAuth.codeFlow.credentialDataStore

            if (creds != null) {
                creds.keySet().forEach { key -> owner.checkup(owner.instance.youtubeUserAuth.credFrom(creds.get(key)), key) }
            }
        } catch (ignored: Exception) {
        }

        owner.timer.schedule(FollowerTask(owner), TimeUnit.HOURS.toMillis(6L))
    }
}