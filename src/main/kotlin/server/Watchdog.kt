package server

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.GameMode
import org.bukkit.scheduler.BukkitRunnable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object Watchdog {

    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private const val WEBHOOK_URL = "https://discord.com/api/webhooks/1404905055315820615/m30LgogCKNiUA6kdSa8T-7XJ7H0Vyk3QGlSCwWtHuCVGuFO3P9PQI0Fab5yT6wayykfd"
    private const val FACELESS_ID = "<@1021466844999200891>"
    private const val TOAST_ID = "<@885368325486559253>"

    val gamemodeTask = object : BukkitRunnable() {
        override fun run() {
            OverLord.instance.server.onlinePlayers.forEach { player ->
                run {
                    if (player.gameMode != GameMode.SURVIVAL) {
                        OverLord.log.warn("Player %s was in gamemode %s", player.name, player.gameMode)
                        player.gameMode = GameMode.SURVIVAL
                        sendDiscordWebhook("Rule breaking has occurred\n **Rule Breaking: Gamemode Change**\n$FACELESS_ID $TOAST_ID")
                        player.sendMessage(
                            MiniMessage.miniMessage().deserialize(
                                "<red>You are not allowed to change your gamemode. This incident has been reported. Await punishment"
                            )
                        )
                    }
                }
            }
        }
    }

    fun sendDiscordWebhook(content: String, username: String? = null, avatarUrl: String? = null) {
        val uri = try {
            URI.create(WEBHOOK_URL)
        } catch (ex: Exception) {
            OverLord.log.error("Invalid hardcoded webhook URL", ex)
            return
        }

        val json = buildString {
            append("{")
            append("\"content\":${jsonEscape(content)}")
            username?.let { append(",\"username\":${jsonEscape(it)}") }
            avatarUrl?.let { append(",\"avatar_url\":${jsonEscape(it)}") }
            append("}")
        }

        val req = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()

        OverLord.httpClient
            .sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .whenComplete { resp, ex ->
                if (ex != null) {
                    OverLord.log.error("Failed to send Discord webhook", ex)
                    return@whenComplete
                }

                val code = resp.statusCode()
                if (code !in 200..299) {
                    OverLord.log.warn("Discord webhook returned HTTP %s - %s", code.toString(), resp.body())
                } else {
                    OverLord.log.debug("Discord webhook sent ok (HTTP %s)".format(code))
                }
            }
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

}