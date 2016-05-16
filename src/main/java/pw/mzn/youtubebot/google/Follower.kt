package pw.mzn.youtubebot.google

import com.google.api.client.auth.oauth2.Credential
import pw.mzn.youtubebot.YoutubeBot
import pw.mzn.youtubebot.data.SavedChannel
import java.util.*
import java.util.concurrent.TimeUnit

class Follower(val instance: YoutubeBot) {
    val timer = Timer()

    init {
        FollowerTask(this).run()
    }

    fun checkup(cred: Credential, chatId: String) {
        var request = instance.youtube.subscriptions().list("id,snippet")
                .setMine(true).setOauthToken(cred.accessToken)
        var response = request.execute()

        response.items.forEach { e -> run {
            var channelId = e.snippet.channelId
            var savedChannel = instance.dataManager.channelBy(channelId)

            if (savedChannel == null) {
                var following = ArrayList<Long>()

                following.add(chatId.toLong())
                instance.dataManager.channels.add(SavedChannel(channelId,
                        e.snippet.channelTitle, following))
                instance.dataManager.saveToFile()

                instance.bot.getChat(chatId).sendMessage("Updated! Successfully subscribed to ${e.snippet.channelTitle}")
                return@run
            }

            if (!savedChannel.subscribed.contains(chatId.toLong())) {
                savedChannel.subscribed.add(chatId.toLong())
                instance.bot.getChat(chatId).sendMessage("Updated! Successfully subscribed to ${e.snippet.channelTitle}")
            }

            instance.dataManager.saveToFile()
        } }
    }
}

class FollowerTask(val owner: Follower): TimerTask() {
    override fun run() {
        var creds = owner.instance.youtubeUserAuth.codeFlow.credentialDataStore

        if (creds != null) {
            creds.keySet().forEach { key -> owner.checkup(owner.instance.youtubeUserAuth.credFrom(creds.get(key)), key) }
        }

        owner.timer.schedule(FollowerTask(owner), TimeUnit.HOURS.toMillis(6L))
    }
}