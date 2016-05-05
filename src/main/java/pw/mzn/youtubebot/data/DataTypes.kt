package pw.mzn.youtubebot.data

import java.util.*

data class SavedChannel(val channelId: String, val channelName: String, val subscribed: ArrayList<Long>) // long = user ids