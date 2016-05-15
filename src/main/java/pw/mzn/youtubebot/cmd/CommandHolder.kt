package pw.mzn.youtubebot.cmd

import pw.mzn.youtubebot.YoutubeBot

class CommandHolder(val instance: YoutubeBot) {
    val playlist = PlaylistCommandHolder(instance)
    val video = VideoCommandHolder(instance)
    val subscription = SubscriptionCommandHandler(instance)
}