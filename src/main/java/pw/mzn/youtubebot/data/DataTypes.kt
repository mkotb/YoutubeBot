package pw.mzn.youtubebot.data

data class SavedChannel(val channelId: String, val channelName: String, val subscribed: MutableList<Long>) // long = user ids