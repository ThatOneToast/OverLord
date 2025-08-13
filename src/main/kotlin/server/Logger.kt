package server

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.Executors
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class PluginLogger(private val plugin: JavaPlugin, debugEnabled: Boolean = false) {
    private val logger: Logger = plugin.logger
    private val logsDir: File = File(plugin.dataFolder, "logs")

    private val mainHandler: FileHandler
    private val agentLogger: Logger
    private val agentHandler: FileHandler
    private val networkLogger: Logger
    private val networkHandler: FileHandler

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

        agentLogger = Logger.getLogger("${plugin.name}.agent")
        agentLogger.useParentHandlers = false
        agentLogger.level = Level.ALL
        agentLogger.addHandler(agentHandler)

        // ----- network logger (centralized for all plugin NetLog writes) -----
        networkLogger = Logger.getLogger("OverLord.network")
        networkLogger.useParentHandlers = false
        networkLogger.level = Level.ALL

        val netPattern = File(logsDir, "network-%u-%g.log").path
        val netLimit = 10 * 1024 * 1024
        val netCount = 5
        networkHandler = FileHandler(netPattern, netLimit, netCount, true)
        networkHandler.formatter = object : Formatter() {
            override fun format(record: LogRecord): String {
                val json = record.message ?: return "\n"
                return try {
                    val elem = JsonParser.parseString(json)
                    val pretty = GsonBuilder().setPrettyPrinting().create().toJson(elem)
                    // append newline so each event ends cleanly in the file
                    pretty + "\n"
                } catch (ex: Throwable) {
                    // on parse failure, fall back to raw JSON line
                    json + "\n"
                }
            }
        }
        networkHandler.level = Level.ALL
        networkLogger.addHandler(networkHandler)

        // forward parsed JSON lines into agent pipeline (async)
        val forwarder = NetworkForwardingHandler()
        forwarder.level = Level.ALL
        networkLogger.addHandler(forwarder)
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
        val networkLogPath = File(logsDir, "network-0-0.log").path
        // If this looks like a network-related origin, hint at network logs.
        val isNetwork = origin.contains("Network", ignoreCase = true) ||
                args.contains("http", ignoreCase = true) || args.contains("socket", ignoreCase = true)

        val consoleMsg = if (isNetwork) {
            "[AGENT] Detected API request by %s; origin=%s; see network logs: %s and agent logs: %s"
                .format(pluginName, origin, networkLogPath, agentLogPath)
        } else {
            "[AGENT] Detected forbidden call by %s; origin=%s; see agent logs: %s"
                .format(pluginName, origin, agentLogPath)
        }

        logger.log(Level.WARNING, consoleMsg)

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

    // ----- Network helper API for OverLord internals -----
    /**
     * Emit a raw JSON line into the central network logger. NetLog
     * from plugins also writes to the same logger name ("OverLord.network"),
     * so everything ends up in these handlers.
     */
    fun networkLogJson(jsonLine: String) {
        try {
            networkLogger.info(jsonLine)
        } catch (ex: Throwable) {
            logger.log(Level.WARNING, "Failed networkLogJson: ${ex.message}")
        }
    }

    /**
     * Convenience: build a small JSON event and emit.
     */
    fun networkLogEvent(
        kind: String,
        target: String,
        method: String?,
        headersJson: String?,
        bodyB64: String?,
        stackB64: String?
    ) {
        val ts = Instant.now().toString()
        val json = buildString {
            append("{")
            append("\"ts\":\"").append(escape(ts)).append("\",")
            append("\"kind\":\"").append(escape(kind)).append("\",")
            append("\"target\":\"").append(escape(target)).append("\",")
            append("\"method\":\"").append(escape(method)).append("\",")
            if (!headersJson.isNullOrEmpty()) {
                append("\"headers\":").append(headersJson).append(",")
            }
            if (!bodyB64.isNullOrEmpty()) {
                append("\"body_b64\":\"").append(escape(bodyB64)).append("\",")
            }
            if (!stackB64.isNullOrEmpty()) {
                append("\"stack_b64\":\"").append(escape(stackB64)).append("\"")
            }
            append("}")
        }
        networkLogJson(json)
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

    // -------------------- network forwarder handler ---------------------
    private inner class NetworkForwardingHandler : Handler() {
        private val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "overlord-net-forwarder").apply { isDaemon = true }
        }

        override fun publish(record: LogRecord?) {
            if (record == null) return
            val msg = record.message ?: return

            exec.submit {
                try {
                    val kind = extractJson(msg, "kind")
                    val target = extractJson(msg, "target")

                    // stack: prefer plain "stack", fallback to "stack_b64"
                    var stackText = extractJson(msg, "stack")
                    if (stackText.isBlank()) {
                        val b64 = extractJson(msg, "stack_b64")
                        if (b64.isNotBlank()) {
                            try {
                                val dec = Base64.getDecoder().decode(b64)
                                stackText = String(dec, Charsets.UTF_8)
                            } catch (_: Throwable) { stackText = "" }
                        }
                    }

                    // body: prefer "body" (plain), else "body_b64"
                    var bodyText = extractJson(msg, "body")
                    if (bodyText.isBlank()) {
                        val b64 = extractJson(msg, "body_b64")
                        if (b64.isNotBlank()) {
                            try {
                                val dec = Base64.getDecoder().decode(b64)
                                bodyText = String(dec, Charsets.UTF_8)
                            } catch (_: Throwable) {
                                bodyText = "[binary]"
                            }
                        }
                    }

                    val pluginName = detectPluginFromStack(stackText)
                    val originBase = kind.ifBlank { "NetworkMonitor" }
                    val origin = "$originBase:${target.ifBlank { "unknown" }}"

                    val argsShort = if (msg.length <= 4096) msg
                    else msg.substring(0, 4096) + "...(truncated)"

                    // If we can attribute this to a plugin and it looks like a network API,
                    // record an agent incident (keeps details in agent logs) and write a
                    // short console warning pointing admins to the network logs.
                    if (!pluginName.isNullOrBlank()) {
                        val netKinds = setOf("http_request", "http_response", "socket_connect", "open_connection")
                        if (kind in netKinds) {
                            try {
                                // record into AgentBridge so it appears in agent lists/counts
                                server.agent.AgentBridge.record(origin, pluginName, stackText, argsShort)
                            } catch (_: Throwable) {}
                            try {
                                val networkLogPath = File(logsDir, "network-0-0.log").path
                                logger.log(
                                    Level.WARNING,
                                    "[AGENT] Detected API request by %s; origin=%s; see network logs: %s".format(
                                        pluginName, origin, networkLogPath
                                    )
                                )
                            } catch (_: Throwable) {}
                        }
                    }

                } catch (_: Throwable) {}
            }
        }

        override fun flush() {}
        override fun close() { try { exec.shutdownNow() } catch (_: Throwable) {} }

        private fun extractJson(json: String, key: String): String {
            val re =
                "\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"".toRegex()
            val m = re.find(json) ?: return ""
            return m.groupValues[1]
        }
    }

    private fun detectPluginFromStack(stackText: String?): String? {
        if (stackText.isNullOrBlank()) return null

        // 1) Try registry fast-path
        for (line in stackText.lines()) {
            val cls = line.substringBefore("(").substringBeforeLast(".").trim()
            if (cls.isBlank()) continue
            val owner = server.agent.AgentBridge.findPluginForClass(cls)
            if (!owner.isNullOrBlank()) return owner
        }

        // 2) Fallback: try classloader lookup but skip OverLord itself
        val plugins = Bukkit.getPluginManager().plugins
        val selfName = plugin.name // OverLord plugin name
        for (line in stackText.lines()) {
            val cls = line.substringBefore("(").substringBeforeLast(".").trim()
            if (cls.isBlank()) continue
            for (p in plugins) {
                try {
                    if (p.name == selfName) continue // avoid claiming OverLord
                    val loader = p.javaClass.classLoader
                    Class.forName(cls, false, loader)
                    return p.name
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    private fun escape(s: String?): String {
        if (s == null) return ""
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}