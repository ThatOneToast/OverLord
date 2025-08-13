package server

import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.EnumSet
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class PluginLogger(private val plugin: JavaPlugin, debugEnabled: Boolean = false) {
    private val logger: Logger = plugin.logger

    private val logsDir: File = File(plugin.dataFolder, "logs")
    private val mainHandler: FileHandler
    private val agentLogger: Logger
    private val agentHandler: FileHandler

    init {
        if (!logsDir.exists()) logsDir.mkdirs()

        // Main rotating log: pluginName-%u-%g.log
        val mainPattern = File(logsDir, "${plugin.name}-%u-%g.log").path
        val mainLimit = 5 * 1024 * 1024 // 5 MB per file
        val mainCount = 3
        mainHandler = FileHandler(mainPattern, mainLimit, mainCount, true)
        mainHandler.formatter = object : Formatter() {
            private val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            override fun format(record: LogRecord): String {
                val t = Instant.ofEpochMilli(record.millis)
                    .atZone(ZoneId.systemDefault()).format(dtf)
                val level = record.level.name
                val source = record.sourceClassName?.let {
                    val cls = it.substringAfterLast('.')
                    val method = record.sourceMethodName ?: ""
                    if (method.isNotEmpty()) "$cls.$method" else cls
                } ?: plugin.name
                val msg = formatMessage(record)
                val thrown = record.thrown?.let { th ->
                    val sw = java.io.StringWriter()
                    th.printStackTrace(java.io.PrintWriter(sw))
                    "\n$sw"
                } ?: ""
                return "[$t] [$level] [$source] $msg$thrown\n"
            }
        }
        mainHandler.level = Level.ALL
        logger.addHandler(mainHandler)
        logger.level = if (debugEnabled) Level.ALL else Level.INFO

        // Agent logger: separate rotating files for agent data
        val agentPattern = File(logsDir, "agent-%u-%g.log").path
        val agentLimit = 10 * 1024 * 1024 // 10 MB per file
        val agentCount = 5
        agentHandler = FileHandler(agentPattern, agentLimit, agentCount, true)
        agentHandler.formatter = object : Formatter() {
            private val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            override fun format(record: LogRecord): String {
                val t = Instant.ofEpochMilli(record.millis)
                    .atZone(ZoneId.systemDefault()).format(dtf)
                val level = record.level.name
                val msg = formatMessage(record)
                val thrown = record.thrown?.let { th ->
                    val sw = java.io.StringWriter()
                    th.printStackTrace(java.io.PrintWriter(sw))
                    "\n$sw"
                } ?: ""
                return "[$t] [$level] $msg$thrown\n"
            }
        }
        agentHandler.level = Level.ALL

        // Create a dedicated logger for agent incidents to avoid polluting the main plugin logger
        agentLogger = Logger.getLogger("${plugin.name}.agent")
        // Avoid inheriting parent handlers that would duplicate to console; rely on our handler
        agentLogger.useParentHandlers = false
        agentLogger.level = Level.ALL
        agentLogger.addHandler(agentHandler)
    }

    // ----- Normal logging helpers -----
    fun info(msg: String, vararg args: Any?) {
        logger.log(Level.INFO, format(msg, *args))
    }

    fun warn(msg: String, vararg args: Any?) {
        logger.log(Level.WARNING, format(msg, *args))
    }

    fun error(msg: String, throwable: Throwable? = null, vararg args: Any?) {
        if (throwable != null) {
            val record = LogRecord(Level.SEVERE, format(msg, *args))
            record.thrown = throwable
            logger.log(record)
        } else {
            logger.log(Level.SEVERE, format(msg, *args))
        }
    }

    fun debug(msg: String, vararg args: Any?) {
        // Only log debug if logger accepts fine-level messages
        if (logger.level.intValue() <= Level.FINE.intValue()) {
            logger.log(Level.FINE, format(msg, *args))
        }
    }

    private fun format(msg: String, vararg args: Any?): String =
        if (args.isEmpty()) msg else String.format(msg, *args)

    /**
     * Record an agent-detected incident. This writes a compact warning to the normal plugin log
     * pointing admins to the agent log, and writes the full detail (origin, args, stack frames)
     * to the separate agent log files.
     *
     * Parameters:
     *  - origin: textual origin (e.g., "org.bukkit.craftbukkit.vX_Y_CraftWorld setChunkForceLoaded")
     *  - detectedPlugin: plugin name if known (nullable)
     *  - args: short string representation of method args (e.g., "world=world,x=0,z=0,flag=true")
     *  - frames: full stack frames (multi-line)
     */
    fun agentIncident(origin: String, detectedPlugin: String?, args: String, frames: String) {
        val pluginName = detectedPlugin ?: "UNKNOWN"
        // warn in the normal log so ops see it in console / combined logs
        val agentLogPath = File(logsDir, "agent-0-0.log").path // human hint; rotation will pick others
        logger.log(
            Level.WARNING,
            "[AGENT] Detected forbidden call by %s; origin=%s; see agent logs: %s".format(
                pluginName, origin, agentLogPath
            )
        )

        // Write full details to the agent logger (persisted separately)
        val sb = StringBuilder(1024)
        sb.append("Origin: ").append(origin).append('\n')
        sb.append("Detected plugin: ").append(pluginName).append('\n')
        sb.append("Args: ").append(args).append('\n')
        sb.append("Frames:\n").append(frames).append('\n')
        agentLogger.log(Level.INFO, sb.toString())
    }

    /**
     * Convenience wrapper when the agent has minimal info.
     */
    fun agentIncidentSimple(origin: String, detectedPlugin: String?) {
        agentIncident(origin, detectedPlugin, "", "")
    }

    /**
     * Close file handlers cleanly. Call on plugin disable.
     */
    fun shutdown() {
        try {
            logger.removeHandler(mainHandler)
            mainHandler.flush()
            mainHandler.close()
        } catch (_: Throwable) {}
        try {
            agentLogger.removeHandler(agentHandler)
            agentHandler.flush()
            agentHandler.close()
        } catch (_: Throwable) {}
    }
}
