package server

import org.bukkit.plugin.java.JavaPlugin
import server.processors.EventPriorityRemover
import java.net.http.HttpClient
import java.time.Duration

class OverLord : JavaPlugin() {



    override fun onLoad() {
        instance = this

        log = PluginLogger(this, debugEnabled = true)
        log.info("OverLord Loaded, logger enabled.")

        val pluginsFolder = this.dataFolder.absolutePath + "/plugins"
        log.info("Scanning %s directory", pluginsFolder)
        PluginManager.scanDirectory(pluginsFolder, false)


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

        Watchdog.sendDiscordWebhook("The server is now Online")
    }

    override fun onDisable() {
        // Plugin shutdown logic
        Watchdog.sendDiscordWebhook("The server is now Offline")
    }


    companion object {
        @JvmStatic
        lateinit var instance: OverLord

        @JvmStatic
        lateinit var log: PluginLogger

        @JvmStatic
        lateinit var httpClient: HttpClient



    }
}

