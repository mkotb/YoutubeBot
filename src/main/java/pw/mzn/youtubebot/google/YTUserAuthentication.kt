package pw.mzn.youtubebot.google

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.auth.oauth2.StoredCredential
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import pro.zackpollard.telegrambot.api.event.chat.message.CommandMessageReceivedEvent
import pw.mzn.youtubebot.YoutubeBot
import java.net.URL
import java.util.regex.Pattern
import kotlin.properties.Delegates

class YTUserAuthentication(val instance: YoutubeBot, val clientId: String, val clientSecret: String) {
    val codeFlow = GoogleAuthorizationCodeFlow(NetHttpTransport(), JacksonFactory(), clientId, clientSecret, listOf("https://www.googleapis.com/auth/youtube.readonly"))

    init {
        instance.dataManager.credentials.forEach { e -> codeFlow.credentialDataStore.set(e.key, e.value) }
    }

    fun processLogin(event: CommandMessageReceivedEvent) {
        var chatId = event.chat.id
        var credential = codeFlow.loadCredential(chatId)

        if (credential == null) {
            var authUrl = codeFlow.newAuthorizationUrl()
                    .setRedirectUri("https://telegram.me/YoutubeMusic_Bot?start=login-")
            authUrl.state = event.chat.id
            var rawUrl = authUrl.build()

            event.chat.sendMessage("Open this link to sign into Youtube first:\n$rawUrl")
        } else {
            event.chat.sendMessage("You're already logged in!")
        }
    }

    fun processAuth(raw: String, event: CommandMessageReceivedEvent) {
        println(raw)
        return
        /*var tokenRequest = codeFlow.newTokenRequest(code)

        tokenRequest.redirectUri = "https://telegram.me/YoutubeMusic_Bot?start=login-"
        var credential = codeFlow.createAndStoreCredential(tokenRequest.execute(), chatId)
        event.chat.sendMessage("Successfully logged in!\nFetching subscriptions from there...")

        updateData()
        instance.follower.checkup(credential, chatId)*/
    }

    fun processLogout(event: CommandMessageReceivedEvent) {
        var chatId = event.chat.id
        var credential = codeFlow.loadCredential(chatId)

        if (credential == null) {
            event.chat.sendMessage("You aren't logged in!")
        } else {
            codeFlow.credentialDataStore.delete(chatId)
            updateData()
            event.chat.sendMessage("Successfully logged out!")
        }
    }

    fun updateData() {
        instance.dataManager.credentials.clear()
        codeFlow.credentialDataStore.keySet().forEach { key -> instance.dataManager.credentials.put(key,
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