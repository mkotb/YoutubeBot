/*
 * Copyright (c) 2016, Mazen Kotb, mazenkotb@gmail.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package pw.mzn.youtubebot.processing

import com.mashape.unirest.http.Unirest
import pw.mzn.youtubebot.YoutubeBot
import pw.mzn.youtubebot.extra.VideoOptions
import pw.mzn.youtubebot.extra.YoutubeVideo
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.Callable

class VideoCallable(val id: String, val options: VideoOptions, val instance: YoutubeBot): Callable<YoutubeVideo> {
    override fun call(): YoutubeVideo {
        try {
            return process()
        } catch (e: Exception) {
            e.printStackTrace()
            return YoutubeVideo(id, File("null"))
        }
    }

    fun process(): YoutubeVideo {
        println("Downloading $id...")
        var commandBuilder = LinkedList<String>(Arrays.asList("./youtube-dl", "-v", "--yes-playlist",
                "--write-info-json", "--id", "--audio-format", "mp3", "--audio-quality", "0", "-x"))
        var postProcessArgs = LinkedList<String>()

        if (options.crop) {
            postProcessArgs.add("-ss ${options.startTime} -to ${options.endTime}")
        }

        if (!postProcessArgs.isEmpty()) {
            commandBuilder.add("--postprocessor-args")
            commandBuilder.add("\"${instance.addSplit(postProcessArgs, " ").replace("'", "\'")}\"")
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
            process = ProcessBuilder().command("/usr/bin/env", "ffmpeg", "-i", "$id.old.mp3",
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
                var file = File("$id.jpg")
                var res = Unirest.get(options.thumbnailUrl).asBinary()

                if (file.exists())
                    file.delete()

                Files.copy(res.body, Paths.get("$id.jpg"))
            }

            instance.setThumbnail(id, File(File("").absolutePath))
        }

        return YoutubeVideo(id, File("$id.mp3")).fetchMetadata()
    }
}