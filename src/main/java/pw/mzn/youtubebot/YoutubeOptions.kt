package pw.mzn.youtubebot

import java.util.*

data class PlaylistOptions(var allVideos: Boolean = false, var videoSelection: MutableList<Int> = ArrayList(),
                           var matchRegex: String = "null")

data class VideoOptions(var startTime: Long = -1, var endTime: Long = -1, var crop: Boolean = false,
                        var speed: Double = 1.0, var thumbnail: Boolean = false, var thumbnailUrl: String = "N/A",
                        var customPerformer: String = "N/A", var customTitle: String = "N/A")