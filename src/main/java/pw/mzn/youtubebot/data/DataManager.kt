package pw.mzn.youtubebot.data

import com.google.api.client.auth.oauth2.StoredCredential
import org.json.JSONArray
import org.json.JSONObject
import pw.mzn.youtubebot.extra.VideoMetadata
import pw.mzn.youtubebot.extra.YoutubeVideo
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

class DataManager {
    val dataFile = File("data.json")
    val channels = LinkedList<SavedChannel>()
    val credentials = HashMap<String, StoredCredential>()
    val videos = LinkedList<YoutubeVideo>()

    init {
        loadFromFile()
    }

    fun loadFromFile() {
        if (!dataFile.exists()) {
            dataFile.createNewFile()
            return // no need to read, nothing there
        }

        var obj = JSONObject(Files.readAllLines(Paths.get(dataFile.absolutePath)).joinToString(""))

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

        Files.write(Paths.get(dataFile.absolutePath), obj.toString().toByteArray())
    }

    fun channelBy(id: String): SavedChannel? {
        return channels.filter { e -> id.equals(e.channelId) }.firstOrNull()
    }

    fun videosBy(id: String): List<YoutubeVideo> {
        return videos.filter { e -> id.equals(e.id) }
    }
}