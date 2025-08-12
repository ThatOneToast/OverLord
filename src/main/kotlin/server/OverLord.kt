package server

import org.bukkit.plugin.java.JavaPlugin
import server.processors.EventPriorityRemover

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

            val report = jarSanitizer.processJar(candidate.file, Mode.SCAN)
            if (report.findings.isNotEmpty()) {
                log.warn("Plugin %s has %s possible rule breakers.", candidate.name, report.findings.size)
                log.warn("Attempting to sanitize %s", candidate.name)
                val report = jarSanitizer.processJar(candidate.file, Mode.SANITIZE)
                if (report.findings.size != report.modifiedClasses.size) {
                    log.warn("Plugin %s has mismatched findings and solutions. Plugin will not be loaded.", candidate.name)
                    log.debug("Plugin %s, report: %s", candidate.name, report)
                } else {
                    val finalReport = jarSanitizer.processJar(candidate.file, Mode.SCAN)
                    if (finalReport.findings.isNotEmpty()) {
                        log.warn("Plugin %s still has %s possible rule breakers", candidate.name, finalReport.findings.size)
                        log.warn("Plugin %s will not be loaded", candidate.name)
                        log.debug("Plugin %s report: %s ", candidate.name, finalReport)
                    } else {
                        canLoad = true
                    }
                }
            }

            if (canLoad) {
                PluginManager.loadPlugin(candidate)
            }
        }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        log.info("Plugin loading and sanitization took %sms", elapsedMs)
    }



    override fun onEnable() {
        // Plugin startup logic
    }

    override fun onDisable() {
        // Plugin shutdown logic
    }


    companion object {
        @JvmStatic
        lateinit var instance: OverLord

        @JvmStatic
        lateinit var log: PluginLogger


    }
}

