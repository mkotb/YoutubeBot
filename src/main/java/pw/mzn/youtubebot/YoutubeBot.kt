package pw.mzn.youtubebot

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.SearchListResponse
import com.mashape.unirest.http.Unirest
import pro.zackpollard.telegrambot.api.TelegramBot
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Period
import java.util.*
import java.util.regex.Pattern
import kotlin.properties.Delegates
import kotlin.system.exitProcess

class YoutubeBot(val key: String, val youtubeKey: String) {
    val playlistRegex = Pattern.compile("^.*(youtu\\.be\\/|list=)([^#&?]*).*")
    val videoRegex = Pattern.compile("^(?:https?:\\/\\/)?(?:www\\.)?(?:youtube\\.com|youtu\\.be)\\/watch\\?v=([^&]+)")
    val executable = File("youtube-dl")
    var bot: TelegramBot by Delegates.notNull()
    var youtube: YouTube by Delegates.notNull()

    fun init() {
        downloadExecutable()

        bot = TelegramBot.login(key)
        bot.eventsManager.register(CommandHandler(this))
        bot.startUpdates(false)

        youtube = YouTube.Builder(NetHttpTransport(), JacksonFactory(), HttpRequestInitializer {  })
                .setApplicationName("ayylmaoproj") // don't ask
                .build()

        println("Logged into Telegram!")
    }

    private fun downloadExecutable() {
        println("Downloading executable...")
        var response = Unirest.get("https://yt-dl.org/latest/youtube-dl").asBinary()

        if (response.status != 200) {
            println("Unable to fetch youtube-dl binary! Shutting down...")
            exitProcess(127)
        }

        if (executable.exists()) {
            executable.delete()
        }

        Files.copy(response.body, Paths.get("youtube-dl"))
        executable.setExecutable(true)
        println("Finished downloading youtube-dl executable")
    }

    private fun addSplit(list: List<Any>, splitter: String): String {
        var builder = StringBuilder()

        list.forEach { e -> builder.append(e.toString()).append(splitter) }

        return builder.toString()
    }

    fun search(query: String): SearchListResponse {
        var search = youtube.search().list("id,snippet")

        search.key = youtubeKey
        search.q = query
        search.type = "video"
        search.videoDuration = "short"
        search.fields = "items(id/kind,id/videoId,snippet/title,snippet/thumbnails/default/url,snippet/channelTitle)"
        search.maxResults = 10

        return search.execute()
    }

    fun downloadVideo(id: String): YoutubeVideo {
        println("Downloading $id...")
        ProcessBuilder().command("./youtube-dl", "--write-info-json",
                "--id", "-x", "--audio-format", "mp3", "--audio-quality", "0",
                "https://www.youtube.com/watch?v=$id")
                .start()
                .waitFor()
        println("Finished downloading $id!")

        return YoutubeVideo(id, File("$id.mp3")).fetchMetadata()
    }

    fun downloadPlaylist(options: PlaylistOptions, id: String): YoutubePlaylist {
        var folder = File(id)

        if (folder.exists()) {
            folder.delete()
        }

        folder.mkdirs()

        var commandBuilder = LinkedList<String>(Arrays.asList("./../youtube-dl", "--yes-playlist",
                "--write-info-json", "--id", "--audio-format", "mp3", "--audio-quality", "0", "-x"))

        if (!"null".equals(options.matchRegex)) {
            commandBuilder.add("--match-title")
            commandBuilder.add("'" + options.matchRegex.replace("'", "\'") + "'") // single quotes to avoid escaping
        }

        if (!options.allVideos) {
            commandBuilder.add("--playlist-items")
            commandBuilder.add(addSplit(options.videoSelection, ","))
        }

        commandBuilder.add("https://www.youtube.com/playlist?list=$id")

        ProcessBuilder().command(commandBuilder)
                .directory(folder)
                .start()
                .waitFor()

        // because I'm lazy
        var playlist = YoutubePlaylist(id)

        for (f in folder.listFiles({dir, name -> name.endsWith(".mp3")})) {
            playlist.videoList.add(YoutubeVideo(f.nameWithoutExtension, f, playlist).fetchMetadata())
        }

        return playlist
    }

    fun parse8601Duration(i: String): Long {
        var parts = i.split("T")
        var days = 0

        if (!"P".equals(parts[0])) {
            days = Period.parse(parts[0]).days
        }

        var indices = arrayOf(arrayOf("H", 3600), arrayOf("M", 60), arrayOf("S", 1))
        var parse = parts[1]
        var seconds = 0L

        for (ind in indices) {
            var position = parse.indexOf(ind[0].toString())

            if (position != -1) {
                var value = parse.substring(0, position)
                seconds += value.toInt() * ind[1].toString().toInt()
                parse = parse.substring(value.length + 1)
            }
        }

        return days * 86400 + seconds
    }
}

/************************
        TODO List
 - Inline video searching
 - Add suggestions to send
   thumbnails
 ************************/