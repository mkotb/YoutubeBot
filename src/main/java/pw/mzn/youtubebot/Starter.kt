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
package pw.mzn.youtubebot

import com.wrapper.spotify.Api
import org.wasabi.app.AppConfiguration
import org.wasabi.app.AppServer
import java.io.File

fun main(args: Array<String>) {
    if ("setup".equals(args[0])) {
        setup(args[1], args[2], args[3])
        return
    }

    YoutubeBot(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]).init()
}

fun setup(redirect: String, clientId: String, clientSecret: String) {
    var api = Api.builder()
            .redirectURI(redirect)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .build()
    var state = System.currentTimeMillis().toString()
    var authorizeUrl = api.createAuthorizeURL(emptyList(), state)
    var httpServer = AppServer(AppConfiguration(80))

    httpServer.get("/start", {
        try {
            var code = request.queryParams["code"]
            var credentials = api.authorizationCodeGrant(code).build().get()
            var expiryTimestamp = System.currentTimeMillis() + (credentials.expiresIn * 1000)
            var expiryFile = File("spotify-refresh.timestamp")

            if (!expiryFile.exists())
                expiryFile.createNewFile()

            expiryFile.writeText(expiryTimestamp.toString())
            println("access ${credentials.accessToken}")
            println("refresh ${credentials.refreshToken}")
            println("expires at $expiryTimestamp")

            response.redirect("https://google.com/")
        } catch (e: Exception) {
            e.printStackTrace() //ok
        }
    })

    println("auth at $authorizeUrl")
    httpServer.start(true)
}
