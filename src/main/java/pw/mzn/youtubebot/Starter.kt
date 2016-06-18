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
        if (request.queryParams["state"].equals(state)) {
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
        }
    })

    println("auth at $authorizeUrl")
    httpServer.start(true)
}
