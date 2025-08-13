package server

import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class PluginLogger(private val plugin: JavaPlugin, debugEnabled: Boolean = false) {
    private val logger: Logger = plugin.logger

    init {
        val logsDir = File(plugin.dataFolder, "logs")
        if (!logsDir.exists()) logsDir.mkdirs()

        // Pattern supports %u and %g (unique id / generation)
        val pattern = File(logsDir, "${plugin.name}-%u-%g.log").path
        val limit = 5 * 1024 * 1024 // 5 MB per file
        val count = 3 // keep 3 rotated files

        val fh = FileHandler(pattern, limit, count, true)
        fh.formatter = object : Formatter() {
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
        fh.level = Level.ALL

        logger.addHandler(fh)
        logger.level = if (debugEnabled) Level.ALL else Level.INFO
    }

    fun info(msg: String, vararg args: Any?) {
        logger.log(Level.INFO, format(msg, *args))
    }

    fun warn(msg: String, vararg args: Any?) {
        logger.log(Level.WARNING, format(msg, *args))
    }

    fun error(msg: String, throwable: Throwable? = null, vararg args: Any?) {
        if (throwable != null) {
            val record = java.util.logging.LogRecord(Level.SEVERE, format(msg, *args))
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
}