package pw.mzn.youtubebot.cmd

import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage
import pro.zackpollard.telegrambot.api.event.chat.message.CommandMessageReceivedEvent
import pro.zackpollard.telegrambot.api.event.chat.message.TextMessageReceivedEvent
import pro.zackpollard.telegrambot.api.keyboards.KeyboardButton
import pro.zackpollard.telegrambot.api.keyboards.ReplyKeyboardHide
import pro.zackpollard.telegrambot.api.keyboards.ReplyKeyboardMarkup
import pw.mzn.youtubebot.YoutubeBot
import pw.mzn.youtubebot.data.SavedChannel
import pw.mzn.youtubebot.extra.CachedYoutubeChannel
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class SubscriptionCommandHandler(val instance: YoutubeBot) { // sub, unsub
    val channelSearch = ConcurrentHashMap<Long, List<CachedYoutubeChannel>>()
    val unsubscribeList = ArrayList<Long>()

    fun subscribe(event: CommandMessageReceivedEvent) {
        if (event.args.size < 1) {
            event.chat.sendMessage("Please provide me a channel to subscribe to as an argument")
            return
        }

        event.chat.sendMessage("Searching for channel...")
        var channelz = instance.searchChannel(event.args[0]) // original, hence the z
        var channels = channelz.map { e -> e.channelTitle }.toMutableList()
        var tempList = ArrayList<KeyboardButton>()
        var keyboard = ReplyKeyboardMarkup.builder().selective(true)

        if (channels.isEmpty()) {
            event.chat.sendMessage("No channels were found by the name ${event.args[0]}")
            return
        }

        channels.add("Cancel")

        for (channel in channels) {
            tempList.add(KeyboardButton.builder().text(channel).build())

            if (tempList.size == 2) {
                keyboard.addRow(tempList)
                tempList.clear()
            }
        }

        if (tempList.isNotEmpty()) {
            keyboard.addRow(tempList)
        }


        channelSearch.put(event.chat.id.toLong(), channelz)
        event.chat.sendMessage(SendableTextMessage.builder()
                .message("Please select one of the following or cancel...")
                .replyMarkup(keyboard.build())
                .replyTo(event.message)
                .build())
    }

    fun unsubscribe(event: CommandMessageReceivedEvent) {
        if (event.args.size < 1) {
            event.chat.sendMessage("Please provide me a channel to unsubscribe to as an argument")
            return
        }

        var dataManager = instance.dataManager

        if ("all".equals(event.args[0].toLowerCase())) {
            dataManager.channels.filter { e -> e.subscribed.contains(event.chat.id.toLong()) }
                    .forEach { e -> run {
                        e.subscribed.remove(event.chat.id.toLong())
                        instance.validateChannel(e)
                    } }

            event.chat.sendMessage("Successfully unsubscribed from all channels!")
            return
        }

        var matched = dataManager.channels.filter { e -> e.channelName.toLowerCase().contains(event.argsString.toLowerCase())}
        matched = matched.filter { e -> e.subscribed.contains(event.chat.id.toLong()) }

        if (matched.isEmpty()) {
            event.chat.sendMessage("No matches for subscribed channels were found!")
            return
        }

        if (matched.size == 1) {
            var channel = matched[0]
            channel.subscribed.remove(event.chat.id.toLong())
            instance.validateChannel(channel)
            event.chat.sendMessage("Successfully unsubscribed from ${channel.channelName}")
            return
        }

        var keyboard = ReplyKeyboardMarkup.builder().selective(true)

        matched.forEach { e -> keyboard.addRow(KeyboardButton.builder().text(e.channelName).build()) }

        keyboard.addRow(KeyboardButton.builder().text("Cancel").build())
        unsubscribeList.add(event.chat.id.toLong())
        event.chat.sendMessage(SendableTextMessage.builder()
                .message("Please select one of the following or cancel...")
                .replyMarkup(keyboard.build())
                .replyTo(event.message)
                .build())
    }

    fun processChannelSelection(event: TextMessageReceivedEvent) {
        var message = event.content.content
        var channels = channelSearch[event.chat.id.toLong()]
        var matchingChannel = channels!!.filter { e -> e.channelTitle.equals(message) }
                .firstOrNull()
        channelSearch.remove(event.chat.id.toLong())

        if (matchingChannel == null) {
            event.chat.sendMessage("Cancelled!")
            return
        }

        var channel = instance.dataManager.channelBy(matchingChannel.channelId)

        if (channel == null) {
            channel = SavedChannel(matchingChannel.channelId, matchingChannel.channelTitle, ArrayList<Long>())
            instance.dataManager.channels.add(channel)
        }

        channel.subscribed.add(event.chat.id.toLong())
        instance.dataManager.saveToFile()
        event.chat.sendMessage(SendableTextMessage.builder()
                .message("Successfully subscribed to $message")
                .replyMarkup(ReplyKeyboardHide.builder().selective(true).build())
                .replyTo(event.message)
                .build())
    }

    fun processUnsubscribeSelection(event: TextMessageReceivedEvent) {
        var message = event.content.content
        var matched = instance.dataManager.channels.filter { e -> e.channelName.equals(message) &&
                e.subscribed.contains(event.chat.id.toLong()) }

        if (matched.isEmpty()) {
            event.chat.sendMessage("Cancelled!")
            return
        }

        var channel = matched[0]
        channel.subscribed.remove(event.chat.id.toLong())
        instance.validateChannel(channel)
        event.chat.sendMessage(SendableTextMessage.builder()
                .message("Successfully unsubscribed from ${channel.channelName}")
                .replyMarkup(ReplyKeyboardHide.builder().selective(true).build())
                .replyTo(event.message)
                .build())
        unsubscribeList.remove(event.chat.id.toLong())
    }
}