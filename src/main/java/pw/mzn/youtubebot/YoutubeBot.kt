package pw.mzn.youtubebot

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.SearchListResponse
import com.mashape.unirest.http.Unirest
import org.json.JSONArray
import org.json.JSONObject
import pro.zackpollard.telegrambot.api.TelegramBot
import pro.zackpollard.telegrambot.api.chat.Chat
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

class YoutubeBot(val key: String, val youtubeKey: String, val lastFmKey: String) {
    val executor = Executors.newFixedThreadPool(2)
    val titleRegex = Pattern.compile("(\\[|\\()(.*?)(\\]|\\))")
    val playlistRegex = Pattern.compile("^.*(youtu\\.be\\/|list=)([^#&?]*).*")
    val videoRegex = Pattern.compile("^(?:https?:\\/\\/)?(?:www\\.)?(?:youtube\\.com|youtu\\.be)\\/watch\\?v=([^&]+)")
    val executable = File("youtube-dl")
    val commandHandler = CommandHandler(this)
    var bot: TelegramBot by Delegates.notNull()
    var youtube: YouTube by Delegates.notNull()

    fun init() {
        downloadExecutable()

        bot = TelegramBot.login(key)
        bot.eventsManager.register(commandHandler)
        bot.eventsManager.register(InlineHandler(this))
        bot.eventsManager.register(PhotoHandler(this))
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

    fun searchVideo(query: String): SearchListResponse {
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

    fun preconditionPlaylist(link: String, chat: Chat, silent: Boolean): Long {
        var search = youtube.playlists().list("id,contentDetails")
        var regex = playlistRegex.matcher(link)
        regex.lookingAt()

        search.id = regex.group(regex.groupCount())
        search.fields = "items(id, contentDetails/itemCount)"
        search.key = youtubeKey
        var response = search.execute()

        if (response.items.isEmpty()) {
            if (!silent) {
                chat.sendMessage("Unable to find any playlists by that ID!")
            }
            return -1
        }

        return response.items[0].contentDetails.itemCount
    }

    fun preconditionVideo(link: String, chat: Chat, silent: Boolean): Long {
        var search = youtube.videos().list("contentDetails,snippet")
        var regex = videoRegex.matcher(link)
        regex.lookingAt()

        search.id = regex.group(1)
        search.fields = "items(contentDetails/duration, snippet/thumbnails/medium/url, snippet/title)"
        search.key = youtubeKey
        var response = search.execute()

        if (response.items.isEmpty()) {
            if (!silent) {
                chat.sendMessage("Unable to find any Youtube video by that ID!")
            }
            return -1L
        }

        var item = response.items[0]

        var duration = parse8601Duration(item.contentDetails.duration)

        if (duration > 3600L) {
            if (!silent) {
                chat.sendMessage("This bot is unable to process videos longer than 1 hour! Sorry!")
            }
            return -1L
        }

        if (!commandHandler.thumbnails.containsKey(search.id)) {
            commandHandler.thumbnails.put(search.id, item.snippet.thumbnails.medium.url)
        }

        if (!commandHandler.titleCache.asMap().containsKey(search.id)) {
            commandHandler.titleCache.put(search.id, item.snippet.title)
        }

        return duration
    }

    fun searchTrack(title: String): MutableCollection<Track> {
        var response = Unirest.get("https://ws.audioscrobbler.com/2.0/")
                .queryString("method", "track.search")
                .queryString("track", cleanTitle(title))
                .queryString("api_key", lastFmKey)
                .queryString("format", "json")
                .asJson()

        if (response.status != 200) {
            return ArrayList()
        }

        var matches = response.body.`object`
                .getJSONObject("results")
                .getJSONObject("trackmatches")
                .getJSONArray("track")
        var list = ArrayList<Track>(matches.length())

        matches.forEach { e -> run {
            if (e is JSONObject) {
                list.add(Track(e.getString("name"), e.getString("artist"),
                        albumCover(e.getString("mbid"), e.getJSONArray("image"))))
            }
        } }

        return list
    }

    private fun albumCover(mbid: String, backup: JSONArray): String {
        var trackInfo = Unirest.get("https://ws.audioscrobbler.com/2.0/")
                .queryString("method", "track.getInfo")
                .queryString("mbid", mbid)
                .queryString("api_key", lastFmKey)
                .queryString("format", "json")
                .asJson().body.`object`
                .getJSONObject("track")

        if (!trackInfo.has("album")) {
            return coverFrom(backup)
        }

        var albumMbid = trackInfo.getJSONObject("album")
                .getString("mbid")

        return coverFrom(Unirest.get("https://ws.audioscrobbler.com/2.0/")
                .queryString("method", "album.getInfo")
                .queryString("mbid", albumMbid)
                .queryString("api_key", lastFmKey)
                .queryString("format", "json")
                .asJson().body.`object`
                .getJSONObject("album")
                .getJSONArray("image"))
    }

    private fun coverFrom(obj: JSONArray): String {
        var cover = ""

        obj.forEach { e -> if (e is JSONObject && "extralarge".equals(e.getString("size"))) {
            cover = e.getString("#text")
        } }

        return cover
    }

    fun cleanTitle(title: String): String {
        return title.replace(titleRegex.toRegex(), "").trim()
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
        println("Finished downloading $id!")

        if (options.speed != 1.0) {
            println("Setting speed to ${options.speed}...")
            var filterArg: String

            if (options.speed < 0.5) {
                filterArg = "atempo=0.5" // i'm sorry but dat is too slow
            } else if (options.speed > 2.0) { // GOOTTTAAA GOOO FASSTT
                var builder = StringBuilder()
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
                filterArg = "atempo=${options.speed}"
            }

            Files.move(Paths.get("$id.mp3"), Paths.get("$id.old.mp3"))
            process = ProcessBuilder().command("/usr/bin/ffmpeg", "-i", "$id.old.mp3",
                    "-filter:a ", filterArg, "-vn", "$id.mp3")
                    .redirectErrorStream(true)
                    .start()
            process.waitFor()
            println("finished updating speed!")
            File("$id.old.mp3").delete()
        }

        if (options.thumbnail) {
            println("Setting thumbnail...")
            if (!"N/A".equals(options.thumbnailUrl)) {
                var res = Unirest.get(options.thumbnailUrl).asBinary()
                Files.copy(res.body, Paths.get("$id.jpg"))
            }

            //Files.move(Paths.get("$id.mp3"), Paths.get("$id.old.mp3"))
            process = ProcessBuilder().command("/usr/bin/lame", "--ti", "$id.jpg", "$id.mp3")
                    .redirectErrorStream(true)
                    .start()
            process.waitFor()
            InputStreamReader(process.inputStream).forEachLine { e -> println(e) }
            println("finished setting thumbnail")
            File("$id.mp3").delete()
            Files.move(Paths.get("$id.mp3.mp3"), Paths.get("$id.mp3"))
            File("$id.jpg").delete()
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

data class Track(val name: String, val artist: String, val coverUrl: String)

/************************
        TODO List
 - (Big one) Allow users to link the bot to a playlist and have all their
   downloaded videos be added to the playlist, and use the bot as a "player"
   being able to index through the playlist and select the song they want.
   Then the bot will return the song (or songs, maybe allow things like shuffling
   or full playthrough) accordingly though telegram cache (i.e save the upload IDs of songs)
 ************************/