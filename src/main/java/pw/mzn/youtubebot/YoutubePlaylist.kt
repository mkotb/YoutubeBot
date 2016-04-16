package pw.mzn.youtubebot

import java.io.File
import java.util.*

class YoutubePlaylist(val id: String) {
    val videoList: MutableList<YoutubeVideo> = LinkedList()
    val folder = File(id)
}