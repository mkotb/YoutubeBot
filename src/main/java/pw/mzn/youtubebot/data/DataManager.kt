package pw.mzn.youtubebot.data

import com.google.api.client.auth.oauth2.StoredCredential
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

class DataManager {
    val dataFile = File("data.json")
    val channels = LinkedList<SavedChannel>()
    val credentials = HashMap<String, StoredCredential>()

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

        Files.write(Paths.get(dataFile.absolutePath), obj.toString().toByteArray())
    }

    fun channelBy(id: String): SavedChannel? {
        return channels.filter { e -> id.equals(e.channelId) }.firstOrNull()
    }
}