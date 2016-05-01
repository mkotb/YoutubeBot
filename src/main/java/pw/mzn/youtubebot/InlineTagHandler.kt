package pw.mzn.youtubebot

import pro.zackpollard.telegrambot.api.chat.inline.send.InlineQueryResponse
import pro.zackpollard.telegrambot.api.chat.inline.send.content.InputTextMessageContent
import pro.zackpollard.telegrambot.api.chat.inline.send.results.InlineQueryResult
import pro.zackpollard.telegrambot.api.chat.inline.send.results.InlineQueryResultArticle
import pro.zackpollard.telegrambot.api.event.Listener
import pro.zackpollard.telegrambot.api.event.chat.inline.InlineQueryReceivedEvent
import java.net.URL
import java.util.*

class InlineTagHandler(val instance: YoutubeBot): Listener {
    /*override fun onInlineQueryReceived(event: InlineQueryReceivedEvent?) {
        var query = event!!.query
        println("i'm called")
        var response = instance.searchVideo(query.query)
        println("i'm searching")
        var videos = ArrayList<InlineQueryResult>(response.size)
        var idCounter = 1

        response.forEach { e -> run {
            videos.add(InlineQueryResultArticle.builder()
                    .id(idCounter++.toString()) // useless
                    .thumbUrl(URL(e.thumb))
                    .title(e.title)
                    .url(URL("https://www.youtube.com/watch?v=${e.videoId}"))
                    .description(e.description)
                    .inputMessageContent(InputTextMessageContent.builder()
                            .messageText("https://telegram.me/YoutubeMusic_Bot?start=${e.videoId}").build())
                    .build())
        } }

        query.answer(instance.bot, InlineQueryResponse.builder().results(videos).build())
        println("i answer")
    }*/
}