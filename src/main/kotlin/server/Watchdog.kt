package server

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.GameMode
import org.bukkit.scheduler.BukkitRunnable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Locale

object Watchdog {

    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private const val WEBHOOK_URL = "https://discord.com/api/webhooks/1404905055315820615/m30LgogCKNiUA6kdSa8T-7XJ7H0Vyk3QGlSCwWtHuCVGuFO3P9PQI0Fab5yT6wayykfd"
    private const val FACELESS_ID = "1021466844999200891"
    private const val TOAST_ID = "885368325486559253"
    val modIds = listOf(FACELESS_ID, TOAST_ID)
    val mentionText = modIds.joinToString(" ") { id -> "<@$id>" }

    val gamemodeTask = object : BukkitRunnable() {
        override fun run() {
            OverLord.instance.server.onlinePlayers.forEach { player ->
                run {
                    if (player.gameMode != GameMode.SURVIVAL) {
                        OverLord.log.warn("Player %s was in gamemode %s", player.name, player.gameMode)
                        player.gameMode = GameMode.SURVIVAL

                        val message = DiscordEmbedBuilder()
                            .title("Rule Breaker")
                            .description("Offender: ${player.name}\nRule broken: Gamemode Change")
                            .addField("Moderation Pings", mentionText)
                            .color(0xFF6600)
                            .footer("OverLord Watchdog")

                        sendDiscordWebhook(message.buildPayload(modIds))
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

    val operatorTask = object : BukkitRunnable() {
        override fun run() {
            OverLord.instance.server.onlinePlayers.forEach { player ->
                run {
                    if (player.isOp) {
                        OverLord.log.warn("Player %s had operator", player.name)
                        player.isOp = false

                        val message = DiscordEmbedBuilder()
                            .title("Rule Breaker")
                            .description("Offender: ${player.name}\nRule broken: Operator Gained")
                            .addField("Moderation Pings", mentionText)
                            .color(0xFF6600)
                            .footer("OverLord Watchdog")

                        sendDiscordWebhook(message.buildPayload(modIds))
                    }
                }
            }
        }
    }

    fun sendDiscordWebhook(jsonPayload: String) {
        val uri = try { URI.create(WEBHOOK_URL) }
        catch (ex: Exception) { OverLord.log.error("Invalid webhook URL", ex); return }

        val req = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build()

        OverLord.httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .whenComplete { resp, ex ->
                if (ex != null) OverLord.log.error("Failed to send webhook", ex)
                else OverLord.log.info("Webhook HTTP ${resp.statusCode()} - ${resp.body().takeIf { it.isNotBlank() } ?: "<empty>"}")
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

    class DiscordEmbedBuilder {
        private var title: String? = null
        private var description: String? = null
        private var color: Int? = null
        private var timestamp: Instant? = null
        private var footerText: String? = null
        private var footerIconUrl: String? = null
        private val fields = mutableListOf<Field>()

        data class Field(val name: String, val value: String, val inline: Boolean = false)

        fun title(t: String) = apply { title = t }
        fun description(d: String) = apply { description = d }
        fun color(hexRgb: Int) = apply { color = hexRgb and 0xFFFFFF }
        fun timestamp(inst: Instant) = apply { timestamp = inst }
        fun footer(text: String, iconUrl: String? = null) = apply {
            footerText = text
            footerIconUrl = iconUrl
        }
        fun addField(name: String, value: String, inline: Boolean = false) = apply {
            fields.add(Field(name, value, inline))
        }

        // Build a complete webhook JSON body; mentionUserIds are the numeric IDs to allow pinging
        // optional top-level content (string) can include mentions like "<@123...>"
        fun buildPayload(mentionUserIds: List<String> = emptyList(), content: String? = null): String {
            val ts = timestamp ?: Instant.now()
            return buildString {
                append("{")
                content?.let { append("\"content\":${jsonEscape(it)},") }

                append("\"embeds\":[{")
                title?.let { append("\"title\":${jsonEscape(it)},") }
                description?.let { append("\"description\":${jsonEscape(it)},") }

                append("\"timestamp\":${jsonEscape(ts.toString())},")
                color?.let { append("\"color\":$it,") }

                if (fields.isNotEmpty()) {
                    append("\"fields\":[")
                    append(fields.joinToString(",") { f ->
                        buildString {
                            append("{")
                            append("\"name\":${jsonEscape(f.name)},")
                            append("\"value\":${jsonEscape(f.value)},")
                            append("\"inline\":${f.inline}")
                            append("}")
                        }
                    })
                    append("],")
                }

                if (footerText != null) {
                    append("\"footer\":{")
                    append("\"text\":${jsonEscape(footerText!!)}")
                    footerIconUrl?.let { append(",\"icon_url\":${jsonEscape(it)}") }
                    append("},")
                }

                // remove trailing comma if present, then close embed array/object
                if (this.last() == ',') setLength(length - 1)
                append("}]")

                if (mentionUserIds.isNotEmpty()) {
                    append(",\"allowed_mentions\":")
                    append(buildAllowedMentionsJson(mentionUserIds))
                }

                append("}")
            }
        }

        private fun buildAllowedMentionsJson(userIds: List<String>): String {
            val users = userIds.joinToString(",") { jsonEscape(it) }
            return "{\"parse\":[],\"users\":[$users]}"
        }

        // Simple JSON escaper for string values
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
                    else -> if (ch.code < 0x20) sb.append("\\u%04x".format(Locale.ROOT, ch.code)) else sb.append(ch)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}