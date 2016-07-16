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
package pw.mzn.youtubebot.data

import com.google.api.client.auth.oauth2.StoredCredential
import com.wrapper.spotify.models.*
import org.json.JSONArray
import org.json.JSONObject
import pw.mzn.youtubebot.YoutubeBot
import pw.mzn.youtubebot.extra.SpotifyDownloadSession
import pw.mzn.youtubebot.extra.VideoMetadata
import pw.mzn.youtubebot.extra.YoutubeVideo
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

class DataManager(val instance: YoutubeBot) {
    val dataFile = File("data.json")
    val channels = LinkedList<SavedChannel>()
    val credentials = HashMap<String, StoredCredential>()
    val videos = LinkedList<YoutubeVideo>()
    val spotifySessions = LinkedList<SpotifyDownloadSession>()

    fun loadFromFile() {
        if (!dataFile.exists()) {
            dataFile.createNewFile()
            return // no need to read, nothing there
        }

        var obj = JSONObject(Files.readAllLines(Paths.get(dataFile.absolutePath)).joinToString("\n"))

        if (obj.has("channels")) {
            obj.getJSONArray("channels").forEach { e -> run {
                if (e is JSONObject) {
                    var subscribed = e.getJSONArray("subscribed")
                    var subscribedList = ArrayList<Long>(subscribed.length())

                    subscribed.forEach { e -> if (e is Int) { subscribedList.add(e.toLong()) } }
                    channels.add(SavedChannel(e.getString("id"), e.getString("name"), subscribedList))
                }
            } }
        }

        if (obj.has("creds")) {
            obj.getJSONArray("creds").forEach { e -> run {
                if (e is JSONObject) {
                    credentials.put(e.getString("id"), StoredCredential().setAccessToken(e.getString("access_token"))
                            .setRefreshToken(e.getString("refresh_token"))
                            .setExpirationTimeMilliseconds(e.getLong("expiration_time")))
                }
            } }
        }

        if (obj.has("cached_videos")) {
            obj.getJSONArray("cached_videos").forEach { e -> run {
                if (e is JSONObject) {
                    var video = YoutubeVideo(e.getString("id"), File("dummy.mp3"), null, e.getString("telegram_file_id"))
                    var metaJson = e.getJSONObject("metadata")

                    video.customTitle = safeGet(e, "custom_title")
                    video.customPerformer = safeGet(e, "custom_performer")
                    video.metadata = VideoMetadata(metaJson.getString("name"), metaJson.getInt("duration"),
                            metaJson.getString("thumbnail_link"), metaJson.getLong("view_count"), metaJson.getInt("likes"),
                            metaJson.getInt("dislikes"), metaJson.getString("uploader"), metaJson.getString("url"), video)

                    if (e.has("custom_length"))
                        video.customLength = e.getLong("custom_length")

                    videos.add(video)
                }
            } }
        }

        if (obj.has("spotify_dl_sessions")) {
            obj.getJSONArray("spotify_dl_sessions").forEach { e -> run {
                if (e is JSONObject) {
                    if (!obj.has("id") || !obj.has("tracks"))
                        return@run

                    var chat = instance.bot.getChat(obj.getString("id"))
                    var tracks = ArrayList<PlaylistTrack>()

                    e.getJSONArray("tracks").forEach { e -> run {
                        if (e is JSONObject) {
                            var holder = PlaylistTrack()
                            var track = Track()
                            var artist = SimpleArtist()
                            var album = SimpleAlbum()
                            var image = Image()

                            image.url = e.getString("cover")
                            album.images = mutableListOf(image)
                            artist.name = e.getString("artist")

                            track.name = e.getString("name")
                            track.artists = mutableListOf(artist)
                            track.album = album

                            holder.track = track
                            tracks.add(holder)
                        }
                    } }

                    spotifySessions.add(SpotifyDownloadSession(chat, tracks))
                }
            } }
        }
    }

    private fun safeGet(e: JSONObject, index: String): String? {
        if (e.has(index)) {
            return e.getString(index)
        }

        return null
    }

    fun saveToFile() {
        if (!dataFile.exists()) {
            dataFile.createNewFile()
        }

        var obj = JSONObject()

        if (channels.isNotEmpty()) {
            var converted = JSONArray()

            channels.forEach { e -> converted.put(JSONObject().put("id", e.channelId)
                                             .put("name", e.channelName)
                                            .put("subscribed", e.subscribed.toList())) }
            obj.put("channels", converted)
        }

        if (credentials.isNotEmpty()) {
            var converted = JSONArray()

            credentials.forEach { e -> converted.put(JSONObject().put("id", e.key).put("access_token", e.value.accessToken)
                                                                 .put("refresh_token", e.value.refreshToken)
                                                                 .put("expiration_time", e.value.expirationTimeMilliseconds)) }
            obj.put("creds", converted)
        }

        if (videos.isNotEmpty()) {
            var converted = JSONArray()

            videos.forEach { e -> run {
                converted.put(JSONObject().put("id", e.id)
                        .put("telegram_file_id", e.fileId).put("custom_title", e.customTitle)
                        .put("custom_length", e.customLength).put("custom_performer", e.customPerformer)
                        .put("metadata", JSONObject().put("name", e.metadata.name)
                                                     .put("duration", e.metadata.duration)
                                                     .put("thumbnail_link", e.metadata.thumbnailLink)
                                                     .put("view_count", e.metadata.viewCount)
                                                     .put("likes", e.metadata.likes)
                                                     .put("dislikes", e.metadata.dislikes)
                                                     .put("uploader", e.metadata.uploader)
                                                     .put("url", e.metadata.url)))
            } }

            obj.put("cached_videos", converted)
        }

        var spotifySessions = instance.spotifyHandler.queue.toList()

        if (spotifySessions.isNotEmpty()) {
            var converted = JSONArray()

            spotifySessions.forEach { e -> run {
                var tracksConverted = JSONArray()

                e.tracks.forEach { e -> tracksConverted.put(JSONObject()
                        .put("name", e.track.name)
                        .put("artist", e.track.artists[0].name)
                        .put("cover", e.track.album.images[0].url)) }

                converted.put(JSONObject().put("id", e.chat.id)
                        .put("tracks", tracksConverted))
            } }

            obj.put("spotify_dl_sessions", converted)
        }

        Files.write(Paths.get(dataFile.absolutePath), obj.toString().toByteArray())
    }

    fun channelBy(id: String): SavedChannel? {
        return channels.filter { e -> id.equals(e.channelId) }.firstOrNull()
    }

    fun videosBy(id: String): List<YoutubeVideo> {
        return videos.filter { e -> id.equals(e.id) }
    }
}