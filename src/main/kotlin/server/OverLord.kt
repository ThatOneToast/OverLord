package server

import org.bukkit.plugin.java.JavaPlugin
import server.Watchdog.DiscordEmbedBuilder
import server.processors.EventPriorityRemover
import java.io.File
import java.net.http.HttpClient
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists

class OverLord : JavaPlugin() {



    override fun onLoad() {
        instance = this

        log = PluginLogger(this, debugEnabled = true)
        log.info("OverLord Loaded, logger enabled.")

        val pluginsFolder = this.dataFolder.absolutePath + "/plugins"
        if (!Path(pluginsFolder).exists()) {
            Files.createDirectory(Path(pluginsFolder))
        }
        log.info("Scanning %s directory", pluginsFolder)
        PluginManager.scanDirectory(pluginsFolder, false)

        val configFile = File(this.dataFolder.absolutePath + "/config.toml")
        if (!configFile.exists()) {
            configFile.createNewFile()
        }
        stateConfig = TomlConfig(configFile)

        val start = System.nanoTime()

        log.info("Starting plugin loading and sanitization process.")
        while (PluginManager.peekCandidate() != null) {
            val candidate = try {
                requireNotNull(PluginManager.nextCandidate())
            } catch (e: IllegalArgumentException) {
                log.error("Peeked non-null but nextCandidate returned null", e)
                break
            }

            var canLoad = false

            val processors = listOf(EventPriorityRemover())
            val jarSanitizer = JarSanitizer(processors)

            val before = jarSanitizer.processJar(candidate.file, Mode.SCAN)
            if (before.findings.isNotEmpty()) {
                log.warn("Plugin %s has %s possible rule breakers.", candidate.name, before.findings.size)
                log.warn("Attempting to sanitize %s", candidate.name)

                val sanitizeReport = jarSanitizer.processJar(candidate.file, Mode.SANITIZE)
                log.debug("Sanitize modified classes: %s".format(sanitizeReport.modifiedClasses.size))

                val after = jarSanitizer.processJar(candidate.file, Mode.SCAN)
                if (after.findings.isNotEmpty()) {
                    log.warn("Plugin %s still has %s possible rule breakers after sanitization; will not be loaded.",
                        candidate.name, after.findings.size)
                    log.debug("Plugin %s final report: %s", candidate.name, after)
                } else {
                    canLoad = true
                }
            } else {
                canLoad = true
            }

            if (canLoad) {
                PluginManager.loadPlugin(candidate)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                log.info("Plugin %s loading and sanitization took %sms", candidate.name, elapsedMs)
            }
        }


    }



    override fun onEnable() {
        // Plugin startup logic

        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        Watchdog.gamemodeTask.runTaskTimer(this, 0L, 20L)
        Watchdog.operatorTask.runTaskTimer(this, 0L, 20L)

        val message = DiscordEmbedBuilder()
            .title("Server Status")
            .description("Availability: Online ✅")
            .color(0x0FF00)
            .footer("OverLord Watchdog")

        Watchdog.sendDiscordWebhook(message.buildPayload())
    }

    override fun onDisable() {
        // Plugin shutdown logic

        Watchdog.gamemodeTask.cancel()
        Watchdog.operatorTask.cancel()

        val message = DiscordEmbedBuilder()
            .title("Server Status")
            .description("Availability: Offline ❌")
            .color(0xFF6600)
            .footer("OverLord Watchdog")

        Watchdog.sendDiscordWebhook(message.buildPayload())


    }


    companion object {
        @JvmStatic
        lateinit var instance: OverLord

        @JvmStatic
        lateinit var log: PluginLogger

        @JvmStatic
        lateinit var httpClient: HttpClient

        @JvmStatic
        lateinit var stateConfig: TomlConfig



    }
}

