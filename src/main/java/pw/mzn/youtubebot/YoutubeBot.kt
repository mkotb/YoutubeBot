package pw.mzn.youtubebot

import com.mashape.unirest.http.Unirest
import pro.zackpollard.telegrambot.api.TelegramBot
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern
import kotlin.properties.Delegates
import kotlin.system.exitProcess

class YoutubeBot(val key: String) {
    val playlistRegex = Pattern.compile("^.*(youtu\\.be\\/|list=)([^#&?]*).*")
    val videoRegex = Pattern.compile("^(?:https?:\\/\\/)?(?:www\\.)?(?:youtube\\.com|youtu\\.be)\\/watch\\?v=([^&]+)")
    val executable = File("youtube-dl")
    var bot: TelegramBot by Delegates.notNull()

    fun init() {
        downloadExecutable()
        bot = TelegramBot.login(key)
        bot.eventsManager.register(CommandHandler(this))
        bot.startUpdates(false)
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

        var commandBuilder = LinkedList<String>(Arrays.asList("./youtube-dl", "--yes-playlist",
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

        ProcessBuilder().command(commandBuilder.toString())
                .directory(folder)
                .start()
                .waitFor()

        // because I'm lazy
        var playlist = YoutubePlaylist(id)

        for (f in folder.listFiles({dir, name -> name.endsWith(".info.json")})) {
            playlist.videoList.add(YoutubeVideo(f.nameWithoutExtension,
                    File(f.parentFile, f.nameWithoutExtension + ".mp3"),
                    playlist).fetchMetadata())
        }

        return playlist
    }
}

/************************
        TODO List
 - Playlist selection
   (regex included)
 - Add suggestions to send
   thumbnails

 ************************/