package pw.mzn.youtubebot

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTube
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.mashape.unirest.http.Unirest
import com.wrapper.spotify.Api
import org.json.JSONArray
import org.json.JSONObject
import pro.zackpollard.telegrambot.api.TelegramBot
import pro.zackpollard.telegrambot.api.chat.Chat
import pw.mzn.youtubebot.cmd.CommandHolder
import pw.mzn.youtubebot.data.DataManager
import pw.mzn.youtubebot.data.SavedChannel
import pw.mzn.youtubebot.extra.*
import pw.mzn.youtubebot.google.Follower
import pw.mzn.youtubebot.google.FollowerTask
import pw.mzn.youtubebot.google.SubscriptionsTask
import pw.mzn.youtubebot.google.YTUserAuthentication
import pw.mzn.youtubebot.handler.CommandHandler
import pw.mzn.youtubebot.handler.InlineHandler
import pw.mzn.youtubebot.handler.PhotoHandler
import pw.mzn.youtubebot.handler.SpotifyDownloadHandler
import pw.mzn.youtubebot.processing.PlaylistCallable
import pw.mzn.youtubebot.processing.VideoCallable
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Period
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.properties.Delegates
import kotlin.system.exitProcess

class YoutubeBot(val key: String, val youtubeKey: String, youtubeClientId: String, youtubeClientSecret: String,
                 spotifyClientId: String, spotifyClientSecret: String, spotifyToken: String, spotifyRefresh: String) {
    val executor = Executors.newFixedThreadPool(2, ThreadFactoryBuilder().setUncaughtExceptionHandler { thread, throwable ->
        println("there was boo boo"); throwable.printStackTrace() }.build())
    val spotifyPlaylistUriRegex = Pattern.compile("^spotify:user:.+:playlist:(.{22})$")
    val spotifyPlaylistUrlRegex = Pattern.compile("https:\\/\\/open\\.spotify\\.com\\/user\\/(.+)\\/playlist\\/(.{22})")
    val titleRegex = Pattern.compile("/((?:\\(|\\[)(?!.*remix).*(?:\\)|\\]))/ig")
    val playlistRegex = Pattern.compile("^.*(youtu\\.be\\/|list=)([^#&?]*).*")
    val videoRegex = Pattern.compile("^(?:https?:\\/\\/)?(?:www\\.)?(?:youtube\\.com|youtu\\.be)\\/watch\\?v=([^&]+)")
    val dataManager = DataManager()
    val executable = File("youtube-dl")
    val googleKeys = Files.readAllLines(Paths.get("gm_keys"))
    val commandHandler = CommandHandler(this)
    val command = CommandHolder(this)
    val inlineHandler = InlineHandler(this)
    val youtubeUserAuth = YTUserAuthentication(this, youtubeClientId, youtubeClientSecret)
    val follower = Follower(this)
    var bot: TelegramBot by Delegates.notNull()
    var youtube: YouTube by Delegates.notNull()
    var spotify = Api.builder()
            .clientId(spotifyClientId)
            .clientSecret(spotifyClientSecret)
            .accessToken(spotifyToken)
            .refreshToken(spotifyRefresh)
            .build()
    var spotifyHandler: SpotifyDownloadHandler by Delegates.notNull()
    var keyIndex = 0

    fun init() {
        downloadExecutable()

        bot = TelegramBot.login(key)
        bot.eventsManager.register(commandHandler)
        bot.eventsManager.register(inlineHandler)
        bot.eventsManager.register(PhotoHandler(this))
        bot.startUpdates(false)

        youtube = YouTube.Builder(NetHttpTransport(), JacksonFactory(), HttpRequestInitializer {  })
                .setApplicationName("ayylmaoproj") // don't ask
                .build()
        spotifyHandler = SpotifyDownloadHandler(this)

        FollowerTask(follower).run()

        println("Logged into Telegram!")

        SubscriptionsTask(this, Timer()).run()
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

    fun searchChannel(query: String): List<CachedYoutubeChannel> {
        var search = youtube.search().list("id,snippet")

        search.q = query
        search.key = youtubeKey
        search.type = "channel"
        search.fields = "items(id,snippet/title)"
        search.maxResults = 4

        var response = search.execute().items
        var items = ArrayList<CachedYoutubeChannel>(response.size)

        response.forEach { e -> items.add(CachedYoutubeChannel(e.id.channelId, e.snippet.title)) }

        return items
    }

    fun validateChannel(channel: SavedChannel) {
        dataManager.saveToFile()
    }

    fun searchVideo(query: String): List<CachedYoutubeVideo> {
        var videos = ArrayList<CachedYoutubeVideo>(4)
        var response = Unirest.get("https://www.googleapis.com/customsearch/v1")
                .queryString("q", query)
                .queryString("key", googleKeys[keyIndex])
                .queryString("cx", "000917504380048684589:konlxv5xaaw")
                .queryString("siteSearch", "youtube.com")
                .queryString("num", 2)
                .asJson().body.`object`
        var array = JSONArray()

        if (response.has("items")) {
            array = response.getJSONArray("items")
        }

        array.forEach { e ->
            if (e is JSONObject) {
                var matcher = videoRegex.matcher(e.getString("link"))

                if (matcher.matches()) {
                    var pagemap = e.getJSONObject("pagemap")
                    var thumbnail = "null"

                    if (pagemap.has("cse_thumnail")) {
                        var thumb = pagemap.getJSONObject("cse_thumbnail")

                        if (thumb.has("thumb")) {
                            thumbnail = thumb.getString("thumb")
                        }
                    }

                    var description = "null"

                    if (pagemap.has("videoobject")) {
                        var video = pagemap.getJSONArray("videoobject").getJSONObject(0)

                        if (video.has("description")) {
                            description = video.getString("description")
                        }
                    }

                    videos.add(CachedYoutubeVideo(matcher.group(1), e.getString("title"), thumbnail, description))
                }
            }
        }

        return videos
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

    fun preconditionVideo(link: String, chat: Chat?, silent: Boolean): Long {
        var search = youtube.videos().list("contentDetails,snippet")
        var regex = videoRegex.matcher(link)
        regex.lookingAt()

        search.id = regex.group(1)
        search.fields = "items(contentDetails/duration, snippet/thumbnails/medium/url, snippet/title)"
        search.key = youtubeKey
        var response = search.execute()

        if (response.items.isEmpty()) {
            if (!silent) {
                chat!!.sendMessage("Unable to find any Youtube video by that ID!")
            }
            return -1L
        }

        var item = response.items[0]

        var duration = parse8601Duration(item.contentDetails.duration)

        if (duration > 3600L) {
            if (!silent) {
                chat!!.sendMessage("This bot is unable to process videos longer than 1 hour! Sorry!")
            }
            return -1L
        }

        if (!commandHandler.thumbnails.containsKey(search.id)) {
            commandHandler.thumbnails.put(search.id, item.snippet.thumbnails.medium.url)
        }

        if (!command.video.titleCache.asMap().containsKey(search.id)) {
            command.video.titleCache.put(search.id, item.snippet.title)
        }

        return duration
    }

    fun searchTrack(titl: String): MutableCollection<Track> {
        var title = cleanTitle(titl)
        println("searching for $title")
        var api = Api.DEFAULT_API
        var list = LinkedList<Track>()
        api.searchTracks(title).market("US").build().get().items.forEach { e -> list.add(Track(e.name, e.artists[0].name, e.album.images[0].url)) }

        if (list.isEmpty()) {
            // add one by personal parse
            var split = title.split("-")

            if (split.size == 1) {
                return list
            }

            var artist = split[0].trim()
            var name = split[1].trim()

            list.add(Track(name, artist, searchImage("$name $artist cover")))
        }

        return list
    }

    fun searchImage(query: String): String {
        return (Unirest.get("https://www.googleapis.com/customsearch/v1")
                .queryString("q", query)
                .queryString("key", googleKeys[nextKeyIndex()])
                .queryString("searchType", "image")
                .queryString("imgSize", "large")
                .queryString("cx", "000917504380048684589:konlxv5xaaw")
                .queryString("num", "2").asJson()
                .body.`object`
                .getJSONArray("items")[0] as JSONObject).getString("link")
    }

    fun cleanTitle(titl: String): String {
        var title = titl
        var progressingTitle = title
        var bracketIndex = title.indexOf('[')

        if (bracketIndex == -1) {
            bracketIndex = title.indexOf('(')
        }

        while (bracketIndex != -1) {
            var endBracketIndex = progressingTitle.indexOf(']', bracketIndex)

            if (endBracketIndex == -1) {
                endBracketIndex = progressingTitle.indexOf(')', bracketIndex)
            }

            if (endBracketIndex == -1) {
                break // rip
            }

            var contents = progressingTitle.substring(bracketIndex, endBracketIndex + 1)
            progressingTitle = title.substring(endBracketIndex, title.length)
            bracketIndex = progressingTitle.indexOf('[')

            if (bracketIndex == -1) {
                bracketIndex = progressingTitle.indexOf('(')
            }

            if (contents.toLowerCase().contains("remix")) {
                println("continuing, contents=$contents")
                continue
            }

            println("replacing $contents. before: $title")
            title = title.replace(contents, "")
            println("after: $title")
        }

        var index = title.toLowerCase().indexOf("lyrics")

        while (index != -1) {
            title = title.replace(title.substring(index, index + 6), "")
            index = title.toLowerCase().indexOf("lyrics")
        }

        title = title.replace("HD", "")

        return title.trim()
    }

    fun nextKeyIndex(): Int {
        var toReturn = keyIndex++

        if (keyIndex == googleKeys.size) {
            keyIndex = 0
        }

        return toReturn
    }

    fun setThumbnail(id: String, directory: File) {
        var process = ProcessBuilder().command("/usr/bin/lame", "--ti", "--", "$id.jpg", "$id.mp3")
                .redirectErrorStream(true)
                .directory(directory)
                .start()
        process.waitFor()
        println(InputStreamReader(process.inputStream).readLines().joinToString("\n"))
        println("finished setting thumbnail")
        File("$id.mp3").delete()
        Files.move(Paths.get("$id.mp3.mp3"), Paths.get("$id.mp3"))
        File("$id.jpg").delete()
    }
}

data class Track(var name: String, var artist: String, var coverUrl: String)