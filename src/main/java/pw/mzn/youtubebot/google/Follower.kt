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
            println("going through ${e.snippet.title}")
            var channelId = e.snippet.channelId
            var savedChannel = instance.dataManager.channelBy(channelId)

            if (savedChannel == null) {
                println("it's null")
                var following = ArrayList<Long>()

                following.add(chatId.toLong())
                instance.dataManager.channels.add(SavedChannel(channelId,
                        e.snippet.title, following))
                println("made new channel, that's nice")
                instance.dataManager.saveToFile()

                instance.bot.getChat(chatId).sendMessage("Updated! Successfully subscribed to ${e.snippet.title}")
                println("created $channelId, saved to file and did all the stuffs")
                return@run
            } else if (!savedChannel.subscribed.contains(chatId.toLong())) {
                savedChannel.subscribed.add(chatId.toLong())
                println("added to existing")
                instance.bot.getChat(chatId).sendMessage("Updated! Successfully subscribed to ${e.snippet.title}")
            } else {
                println("they're already subscribed, apparently")
            }

            instance.dataManager.saveToFile()
            println("saved to file")
        } }

        if (response.pageInfo.resultsPerPage * times < response.pageInfo.totalResults + left)
            checkup(cred, chatId, response.nextPageToken, response.pageInfo.totalResults + left, times + 1) // mfw that many subscriptions
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