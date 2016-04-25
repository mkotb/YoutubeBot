package pw.mzn.youtubebot

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.SearchListResponse
import com.mashape.unirest.http.Unirest
import pro.zackpollard.telegrambot.api.TelegramBot
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Period
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.properties.Delegates
import kotlin.system.exitProcess

class YoutubeBot(val key: String, val youtubeKey: String) {
    val executor = Executors.newFixedThreadPool(2)
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

    fun downloadVideo(options: VideoOptions, id: String): YoutubeVideo {
        return executor.submit(VideoCallable(id, options, this)).get()
    }

    fun downloadPlaylist(options: PlaylistOptions, id: String): YoutubePlaylist {
        return executor.submit(PlaylistCallable(options, id, this)).get()
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

    fun addSplit(list: List<Any>, splitter: String): String {
        var builder = StringBuilder()

        list.forEach { e -> builder.append(e.toString()).append(splitter) }
        var str = builder.toString()

        return str.substring(0, str.length - 1)
    }

    fun formatTime(time: Long): String {
        var hours = TimeUnit.SECONDS.toHours(time)
        var minutes = TimeUnit.SECONDS.toMinutes(time) - (hours * 60)
        var seconds = time - (hours * 3600) - (minutes * 60)

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun parseDuration(inp: String): Long {
        var timesGeneral = arrayOf(arrayOf(TimeUnit.SECONDS),
                arrayOf(TimeUnit.MINUTES, TimeUnit.SECONDS),
                arrayOf(TimeUnit.HOURS, TimeUnit.MINUTES, TimeUnit.SECONDS))
        var input = inp.split(":")
        var top = input.size - 1
        var time = 0L

        if (top > 2) {
            top = 2
        }

        var times = timesGeneral[top]

        for (i in 0..top) {
            var unit = times[i]
            var parsed: Long

            try {
                parsed = input[i].toInt().toLong()
            } catch (ignored: Exception) {
                continue
            }

            time += unit.toSeconds(parsed)
        }

        return time
    }
}

class VideoCallable(val id: String, val options: VideoOptions, val instance: YoutubeBot): Callable<YoutubeVideo> {
    override fun call(): YoutubeVideo {
        println("Downloading $id...")
        var commandBuilder = LinkedList<String>(Arrays.asList("./youtube-dl", "-v", "--yes-playlist",
                "--write-info-json", "--id", "--audio-format", "mp3", "--audio-quality", "0", "-x"))
        var postProcessArgs = LinkedList<String>()

        if (options.crop) {
            postProcessArgs.add("-ss ${options.startTime} -to ${options.endTime}")
        }

        if (!postProcessArgs.isEmpty()) {
            commandBuilder.add("--postprocessor-args")
            commandBuilder.add("${instance.addSplit(postProcessArgs, " ").replace("'", "\'")}")
        }

        commandBuilder.add("https://www.youtube.com/watch?v=$id")
        println(instance.addSplit(commandBuilder, " ") + " is executing")

        var process = ProcessBuilder().command(commandBuilder)
                .redirectErrorStream(true)
                .start()

        process.waitFor()
        InputStreamReader(process.inputStream).readLines().forEach { e -> println(e) }
        println("Finished downloading $id!")

        if (options.speed != 1.0) {
            println("Setting speed to ${options.speed}...")
            var filterArg: String

            if (options.speed < 0.5) {
                filterArg = "atempo=0.5" // i'm sorry but dat is too slow
            } else if (options.speed > 2.0) { // GOOTTTAAA GOOO FASSTT
                var builder = StringBuilder()
                builder.append("\"")

                var iterations = Math.floor(options.speed / 2.0).toInt()
                var extra = options.speed % 2.0

                for (i in 0..(iterations - 2)) {
                    builder.append("atempo=2.0,")
                }

                builder.append("atempo=2.0")

                if (extra != 0.0) {
                    builder.append(",atempo=$extra")
                }

                filterArg = builder.toString()
            } else {
                filterArg = "atempo=0.5"
            }

            Files.move(Paths.get("$id.mp3"), Paths.get("$id.old.mp3"))
            process = ProcessBuilder().command("/usr/bin/ffmpeg", "-i", "$id.old.mp3",
                    "-filter:a ", filterArg, "-vn", "$id.mp3")
                    .redirectErrorStream(true)
                    .start()
            process.waitFor()
            InputStreamReader(process.inputStream).readLines().forEach { e -> println(e) }
            println(filterArg)
            println("finished updating speed!")
            File("$id.old.mp3").delete()
        }

        return YoutubeVideo(id, File("$id.mp3")).fetchMetadata()
    }
}

class PlaylistCallable(val options: PlaylistOptions, val id: String, val instance: YoutubeBot): Callable<YoutubePlaylist> {
    override fun call(): YoutubePlaylist {
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

        if (!options.allVideos && !options.videoSelection.isEmpty()) {
            commandBuilder.add("--playlist-items")
            commandBuilder.add(instance.addSplit(options.videoSelection, ","))
        }

        commandBuilder.add("https://www.youtube.com/playlist?list=$id")

        println(instance.addSplit(commandBuilder, " ") + " is executing")

        ProcessBuilder().command(commandBuilder)
                .directory(folder)
                .redirectErrorStream(true)
                .start()
                .waitFor()

        // because I'm lazy
        var playlist = YoutubePlaylist(id)

        for (f in folder.listFiles({dir, name -> name.endsWith(".mp3")})) {
            playlist.videoList.add(YoutubeVideo(f.nameWithoutExtension, f, playlist).fetchMetadata())
        }

        return playlist
    }
}

/************************
        TODO List
 - Inline video searching
 - Add suggestions to send
   thumbnails
 ************************/