package server

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap


class TomlConfig(private val file: File) {
    val data = ConcurrentHashMap<String, Any>()
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        data.clear()
        if (!file.exists()) {
            loaded = true
            return
        }

        var currentSection: String? = null

        file.forEachLine { raw ->
            var line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            val commentIndexHash = line.indexOf('#').takeIf { it >= 0 } ?: Int.MAX_VALUE
            val commentIndexSemi = line.indexOf(';').takeIf { it >= 0 } ?: Int.MAX_VALUE
            val commentIndex = minOf(commentIndexHash, commentIndexSemi)
            if (commentIndex != Int.MAX_VALUE) line = line.take(commentIndex).trim()
            if (line.isEmpty()) return@forEachLine

            // section header [name]
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim().takeIf { it.isNotEmpty() }
                return@forEachLine
            }

            // key = value
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEachLine
            val key = line.take(eq).trim()
            val rawVal = line.substring(eq + 1).trim()
            val fullKey = if (currentSection != null) "$currentSection.$key" else key
            parseValue(rawVal)?.let { data[fullKey] = it }
        }

        loaded = true
    }

    fun reload() = load()

    @Synchronized
    @Throws(IOException::class)
    fun save() {
        val sections = linkedMapOf<String, MutableMap<String, Any?>>()
        val top = linkedMapOf<String, Any?>()

        for ((k, v) in data) {
            val dot = k.indexOf('.')
            if (dot > 0) {
                val sec = k.substring(0, dot)
                val sub = k.substring(dot + 1)
                sections.computeIfAbsent(sec) { linkedMapOf() }[sub] = v
            } else {
                top[k] = v
            }
        }

        val sb = StringBuilder()
        // top-level keys
        for ((k, v) in top) {
            sb.append(k).append(" = ").append(serializeValue(v)).append('\n')
        }
        if (top.isNotEmpty() && sections.isNotEmpty()) sb.append('\n')
        // sections
        for ((sec, map) in sections) {
            sb.append("[").append(sec).append("]\n")
            for ((k, v) in map) {
                sb.append(k).append(" = ").append(serializeValue(v)).append('\n')
            }
            sb.append('\n')
        }

        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    fun contains(key: String): Boolean = data.containsKey(key)

    inline fun <reified T> get(key: String, default: T? = null): T? {
        ensureLoaded()
        val v = data[key] ?: return default
        return castOrNull(v, T::class.java) ?: default
    }

    fun set(key: String, value: Any) {
        ensureLoaded()
        data[key] = value
    }

    fun remove(key: String): Any? = data.remove(key)

    fun keys(): Set<String> {
        ensureLoaded()
        return data.keys
    }

    fun ensureLoaded() {
        if (!loaded) load()
    }

    private fun parseValue(raw: String): Any? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        // quoted string
        if ((s.startsWith("\"") && s.endsWith("\"")) ||
            (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length - 1).replace("\\\"", "\"")
                .replace("\\'", "'").replace("\\n", "\n")
        }
        if (s.equals("true", ignoreCase = true)) return true
        if (s.equals("false", ignoreCase = true)) return false

        val intVal = s.toLongOrNull()
        if (intVal != null) {
            // prefer Int when in range
            return if (intVal in Int.MIN_VALUE..Int.MAX_VALUE) intVal.toInt() else intVal
        }

        val dbl = s.toDoubleOrNull()
        if (dbl != null) return dbl

        return s
    }

    private fun serializeValue(v: Any?): String = when (v) {
        null -> "\"\""
        is String -> "\"" + v.replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Boolean -> v.toString()
        is Int, is Long, is Short -> v.toString()
        is Float, is Double -> {
            val s = (v as Number).toString()

            if (!s.contains('.') && !s.contains('e', ignoreCase = true)) "$s.0" else s
        }
        else -> "\"" + v.toString().replace("\"", "\\\"") + "\""
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> castOrNull(value: Any, cls: Class<T>): T? {
        return when {
            cls.isInstance(value) -> value as T
            cls == String::class.java -> value.toString() as T
            cls == Int::class.java || cls == Integer::class.java ->
                when (value) {
                    is Number -> value.toInt() as T
                    is String -> value.toIntOrNull() as T?
                    else -> null
                }
            cls == Long::class.java || cls == java.lang.Long::class.java ->
                when (value) {
                    is Number -> value.toLong() as T
                    is String -> value.toLongOrNull() as T?
                    else -> null
                }
            cls == Double::class.java || cls == java.lang.Double::class.java ->
                when (value) {
                    is Number -> value.toDouble() as T
                    is String -> value.toDoubleOrNull() as T?
                    else -> null
                }
            cls == Boolean::class.java || cls == java.lang.Boolean::class.java ->
                when (value) {
                    is Boolean -> value as T
                    is String -> value.toBoolean() as T
                    else -> null
                }
            else -> null
        }
    }
}