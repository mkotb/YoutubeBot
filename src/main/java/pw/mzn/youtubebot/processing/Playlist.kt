package pw.mzn.youtubebot.processing

import com.mashape.unirest.http.Unirest
import pw.mzn.youtubebot.YoutubeBot
import pw.mzn.youtubebot.extra.PlaylistOptions
import pw.mzn.youtubebot.extra.YoutubePlaylist
import pw.mzn.youtubebot.extra.YoutubeVideo
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.Callable

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

        // lets have some fun
        for (video in playlist.videoList) {
            var lastFm = instance.searchTrack(video.metadata.name).toList()

            if (lastFm.isEmpty()) {
                continue
            }

            var track = lastFm[0]

            video.customTitle = track.name
            video.customPerformer = track.artist

            if (!"".equals(track.coverUrl)) {
                var thumb = File("${video.id}.jpg")
                var res = Unirest.get(track.coverUrl).asBinary()

                if (thumb.exists())
                    thumb.delete()

                Files.copy(res.body, Paths.get(thumb.name))
                instance.setThumbnail(video.id, folder)
            }
        }

        return playlist
    }
}