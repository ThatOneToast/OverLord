package server

import org.bukkit.plugin.java.JavaPlugin
import server.Watchdog.DiscordEmbedBuilder
import server.agent.AgentBridge
import server.brigadier.CommandRegistrar
import server.packet.PacketListenerInjector
import server.resourcepack.PackCommand
import server.resourcepack.PackManager
import server.resourcepack.PackUpdateListener
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

        log.info("OverLord-Agent: Adding forbidden signatures.")

        // ---------------------------- METHOD SIGNATURES  ----------------------------
        AgentBridge.addForbiddenSignature(
            ".*CraftWorld.*",
            "setChunkForceLoaded",
            "Bukkit API force-load"
        )
        AgentBridge.addForbiddenSignature(
            "net\\.minecraft\\.server\\..*ChunkProviderServer.*",
            "loadChunk|getChunk.*",
            "NMS chunk providers"
        )
        AgentBridge.addForbiddenSignature(
            ".*PlayerChunkMap.*",
            "addTicket|removeTicket",
            "PlayerChunkMap tickets"
        )
        AgentBridge.addForbiddenSignature(
            "org\\.bukkit\\.World",
            "setChunkForceLoaded",
            "Bukkit API force-load"
        )

        // ---------------------------- ANNOTATION SIGNATURES  ----------------------------
        AgentBridge.addForbiddenAnnotationSignature(
            "org\\.bukkit\\.event\\.EventHandler",
            "org\\.bukkit\\.event\\.EventPriority",
            "priority",
            "Bukkit API - Event priority (annotation)"
        )

        // ---------------------------- FIELD SIGNATURES  ----------------------------
        AgentBridge.addForbiddenFieldSignature(
            "org\\.bukkit\\.event\\.EventPriority",
            null,
            "Bukkit API - Event priority (enum constant usage)"
        )


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

            var canLoad = true

            if (!AgentBridge.isJarAllowed(candidate.file.toFile(), true)) {
                canLoad = false
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

        PacketListenerInjector.register()

        packManager = PackManager(this)
        PackUpdateListener.register()
        CommandRegistrar.getRegistrar().registerCommand(PackCommand())


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

        packManager.disable()
        AgentBridge.shutdownWriter()

        log.shutdown()

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

        @JvmStatic
        lateinit var packManager: PackManager



    }
}

