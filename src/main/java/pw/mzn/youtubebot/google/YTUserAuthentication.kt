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
import com.google.api.client.auth.oauth2.StoredCredential
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.store.MemoryDataStoreFactory
import com.google.common.cache.CacheBuilder
import org.wasabi.app.AppConfiguration
import org.wasabi.app.AppServer
import pro.zackpollard.telegrambot.api.event.chat.message.CommandMessageReceivedEvent
import pw.mzn.youtubebot.YoutubeBot
import java.util.concurrent.TimeUnit

class YTUserAuthentication(val instance: YoutubeBot, val clientId: String, val clientSecret: String) {
    val codes = CacheBuilder.newBuilder().concurrencyLevel(5)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build<String, String>()
    val codeFlow = GoogleAuthorizationCodeFlow.Builder(NetHttpTransport(), JacksonFactory(), clientId, clientSecret,
            listOf("https://www.googleapis.com/auth/youtube.readonly"))
            .setCredentialDataStore(MemoryDataStoreFactory.getDefaultInstance().getDataStore("ytdlauth"))
            .build()
    val httpServer = AppServer(AppConfiguration(80))

    init {
        instance.dataManager.credentials.forEach { e -> codeFlow.credentialDataStore.set(e.key, e.value) }

        /*httpServer.get("/start", {
            codes.put(request.queryParams["state"], request.queryParams["code"])
            response.redirect("https://telegram.me/${instance.bot.botUsername}?start=login-${request.queryParams["state"]}")
        })

        httpServer.start(false)*/
    }

    fun processLogin(event: CommandMessageReceivedEvent) {
        var chatId = event.chat.id
        var credential = codeFlow.loadCredential(chatId)

        if (credential == null) {
            var authUrl = codeFlow.newAuthorizationUrl()
                    .setRedirectUri("http://chinese.food.internal.is/start")
                    .setAccessType("offline")
            authUrl.state = event.chat.id
            var rawUrl = authUrl.build()

            event.chat.sendMessage("Open this link to sign into Youtube first:\n$rawUrl")
        } else {
            event.chat.sendMessage("You're already logged in!")
        }
    }

    fun processAuth(chatId: String, event: CommandMessageReceivedEvent) {
        var code = codes.asMap()[event.chat.id] ?: return
        var tokenRequest = codeFlow.newTokenRequest(code)

        tokenRequest.redirectUri = "http://chinese.food.internal.is/start"
        var credential = codeFlow.createAndStoreCredential(tokenRequest.execute(), chatId)
        event.chat.sendMessage("Successfully logged in!\nFetching subscriptions from there...")

        updateData()
        instance.follower.checkup(credential, chatId)
    }

    fun processLogout(event: CommandMessageReceivedEvent) {
        var chatId = event.chat.id
        var credential = codeFlow.loadCredential(chatId)

        if (credential == null) {
            event.chat.sendMessage("You aren't logged in!")
        } else {
            // TODO revoke key
            codeFlow.credentialDataStore.delete(chatId)
            updateData()
            event.chat.sendMessage("Successfully logged out!")
        }
    }

    fun updateData() {
        instance.dataManager.credentials.clear()
        codeFlow.credentialDataStore?.keySet()?.forEach { key -> instance.dataManager.credentials.put(key,
                codeFlow.credentialDataStore.get(key)) }
        instance.dataManager.saveToFile()
    }

    fun credFrom(stored: StoredCredential): Credential {
        return GoogleCredential.Builder().setClientSecrets(clientId, clientSecret)
                .setTransport(NetHttpTransport())
                .setJsonFactory(JacksonFactory()).build()
                .setRefreshToken(stored.refreshToken)
                .setAccessToken(stored.accessToken)
                .setExpirationTimeMilliseconds(stored.expirationTimeMilliseconds)
    }
}