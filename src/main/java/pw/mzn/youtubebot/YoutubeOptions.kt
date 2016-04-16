package pw.mzn.youtubebot

import java.util.*

data class PlaylistOptions(var allVideos: Boolean = false, var videoSelection: MutableList<Int> = ArrayList(),
                           var matchRegex: String = "null")