package pw.mzn.youtubebot.handler

import pw.mzn.youtubebot.YoutubeBot
import pw.mzn.youtubebot.extra.SpotifyDownloadSession
import pw.mzn.youtubebot.extra.VideoOptions
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.schedule
import kotlin.system.exitProcess

class SpotifyDownloadHandler(val instance: YoutubeBot) {
    val queue = ConcurrentLinkedQueue<SpotifyDownloadSession>()

    init {
        Thread() {
            run()
        }.start()

        refreshRun(Timer())
    }

    fun refreshRun(timer: Timer) {
        var refreshFile = File("spotify-refresh.timestamp")

        if (!refreshFile.exists()) {
            println("Could not find the refresh stamp! Shutting down..")
            exitProcess(0)
        }

        var time = refreshFile.readText().toLong()

        timer.schedule(System.currentTimeMillis() - time - 60000) {
            var spotify = instance.spotify
            var refreshCred = spotify.refreshAccessToken().build().get()

            refreshFile.writeText("${System.currentTimeMillis() + (refreshCred.expiresIn * 1000)}")
            println("refreshed spotify token")
            refreshRun(timer)
        }
    }

    fun run() {
        while (true) {
            if (queue.isNotEmpty()) {
                var youtube = instance.youtube
                var current = queue.peek()

                current.chat.sendMessage("Starting download of your playlist...")
                current.tracks.forEach { e -> run() {
                    var track = e.track
                    var name = track.name
                    var artist = track.artists[0].name
                    var videos = instance.searchVideo("$artist $name")
                    var mapped = videos.associateBy { e -> e.videoId }
                    var search = youtube.videos().list("id,snippet")

                    search.id = videos.map { e -> e.videoId }.joinToString(",")
                    search.fields = "items(id,snippet/channelTitle)"
                    search.key = instance.youtubeKey

                    videos = search.execute().items
                            .associate { e -> Pair(e.snippet.channelTitle, mapped[e.id]) }
                            .filter { e -> !e.key.toLowerCase().contains("vevo") &&
                                    !e.value!!.title.toLowerCase().contains("music video") }
                            .values.map { e -> e!! }
                            .toList()

                    if (videos.isEmpty()) {
                        current.chat.sendMessage("Could not find a suitable video for $name by $artist")
                        return@run
                    }

                    var cover = track.album.images[0].url
                    var options = VideoOptions(0, 0, false, 1.0, !"".equals(cover), cover, artist, name)
                    var video = instance.downloadVideo(options, videos[0].videoId)

                    video.customPerformer = artist
                    video.customTitle = name

                    instance.command.video.sendProcessedVideo(video, null, current.chat, current.chat.id.toLong(), true,
                            options, null)
                } }

                queue.poll()
            }
        }
    }
}