package server.agent.netmon

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.logging.Logger

object NetLog {
    private val LOG_FILE = File("plugins", "overlord-network.log")
    private const val MAX_CAPTURE = 4 * 1024
    private val writeLock = Any()
    private val JUL_NET_LOGGER: Logger = Logger.getLogger("OverLord.network")

    // ---------------- public helpers (invoked by rewritten plugin bytecode) ------------

    @JvmStatic
    fun openConnection(url: URL): URLConnection {
        val conn = url.openConnection()
        try {
            writeSimple("open_connection", url.toString(), null, captureStack())
        } catch (_: Throwable) {}
        return conn
    }

    @JvmStatic
    fun openStream(url: URL): InputStream {
        val inStream = try {
            url.openStream()
        } catch (ex: Throwable) {
            throw ex
        }
        return try {
            LoggingInputStream(inStream, url.toString(), "GET", emptyMap())
        } catch (_: Throwable) {
            inStream
        }
    }

    @JvmStatic
    fun getLoggedInputStream(conn: URLConnection): InputStream {
        val inStream = try {
            conn.getInputStream()
        } catch (ex: Throwable) {
            throw ex
        }
        val target = extractUrl(conn)
        val method = extractMethod(conn)
        val props = extractRequestProperties(conn)
        return try {
            LoggingInputStream(inStream, target, method, props)
        } catch (_: Throwable) {
            inStream
        }
    }

    @JvmStatic
    fun getLoggedOutputStream(conn: URLConnection): OutputStream {
        val out = try {
            conn.getOutputStream()
        } catch (ex: Throwable) {
            throw ex
        }
        val target = extractUrl(conn)
        val method = extractMethod(conn)
        val props = extractRequestProperties(conn)
        return try {
            LoggingOutputStream(out, target, method, props)
        } catch (_: Throwable) {
            out
        }
    }

    @JvmStatic
    fun socketConnect(sock: Socket, addr: SocketAddress?) {
        sock.connect(addr)
        try {
            writeSimple("socket_connect", addr?.toString() ?: "", null, captureStack())
        } catch (_: Throwable) {}
    }

    // --------------------- internal helpers --------------------------------------------

    private fun extractUrl(conn: URLConnection): String {
        return try {
            if (conn is HttpURLConnection) return conn.url?.toString() ?: ""
            val f = URLConnection::class.java.getDeclaredField("url")
            f.isAccessible = true
            val u = f.get(conn) as? URL
            u?.toString() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun extractMethod(conn: URLConnection): String {
        return try {
            if (conn is HttpURLConnection) return conn.requestMethod ?: "UNKNOWN"
            val m = conn::class.java.getMethod("getRequestMethod")
            val res = m.invoke(conn) as? String
            res ?: "UNKNOWN"
        } catch (_: Throwable) {
            "UNKNOWN"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractRequestProperties(conn: URLConnection): Map<String, List<String>> {
        return try {
            if (conn is HttpURLConnection) return conn.requestProperties ?: emptyMap()
            val m = conn::class.java.getMethod("getRequestProperties")
            val o = m.invoke(conn)
            o as? Map<String, List<String>> ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun captureStack(): String {
        val st = Thread.currentThread().stackTrace
        val sb = StringBuilder()
        // skip getStackTrace + captureStack + NetLog call frames
        for (i in 3 until st.size.coerceAtMost(32)) {
            sb.append(st[i].toString()).append('\n')
        }
        return sb.toString()
    }

    private fun writeLine(line: String) {
        JUL_NET_LOGGER.info(line)


        try {
            if (!LOG_FILE.parentFile.exists()) LOG_FILE.parentFile.mkdirs()
            synchronized(writeLock) {
                FileWriter(LOG_FILE, true).use { fw ->
                    fw.write(line)
                    fw.write("\n")
                }
            }
        } catch (_: Throwable) {}
    }

    private fun writeSimple(kind: String, target: String, method: String?, stack: String) {
        try {
            val ts = Instant.now().toString()
            val json = buildString {
                append("{")
                append("\"ts\":\"").append(escape(ts)).append("\",")
                append("\"kind\":\"").append(escape(kind)).append("\",")
                append("\"target\":\"").append(escape(target)).append("\",")
                append("\"method\":\"").append(escape(method)).append("\",")
                append("\"stack\":\"").append(escape(stack)).append("\"")
                append("}")
            }
            writeLine(json)
        } catch (_: Throwable) {}
    }

    private fun emit(
        kind: String,
        target: String,
        method: String,
        headers: Map<String, List<String>>,
        body: ByteArray
    ) {
        try {
            val ts = Instant.now().toString()
            val hdrJson = headersToJson(headers)
            val crop = if (body.size <= MAX_CAPTURE) body else body.copyOf(MAX_CAPTURE)
            val isPrintable = looksLikeText(crop)
            val bodyField = if (isPrintable) {
                "\"body\":\"${escape(String(crop, StandardCharsets.UTF_8))}\""
            } else {
                val bodyB64 = Base64.getEncoder().encodeToString(crop)
                "\"body_b64\":\"${escape(bodyB64)}\""
            }
            val truncated = if (body.size > MAX_CAPTURE) ",\"body_truncated\":true" else ""

            val stack = captureStack()

            val json = buildString {
                append("{")
                append("\"ts\":\"").append(escape(ts)).append("\",")
                append("\"kind\":\"").append(escape(kind)).append("\",")
                append("\"target\":\"").append(escape(target)).append("\",")
                append("\"method\":\"").append(escape(method)).append("\",")
                append("\"headers\":").append(hdrJson).append(",")
                append(bodyField)
                append(truncated).append(",")
                append("\"stack\":\"").append(escape(stack)).append("\"")
                append("}")
            }
            writeLine(json)
        } catch (_: Throwable) {}
    }

    private fun headersToJson(headers: Map<String, List<String>>): String {
        val sb = StringBuilder()
        sb.append("{")
        var first = true
        for ((k, v) in headers) {
            if (!first) sb.append(",")
            sb.append('"').append(escape(k)).append("\":\"")
            val masked = maskValues(k, v).joinToString(",")
            sb.append(escape(masked)).append('"')
            first = false
        }
        sb.append("}")
        return sb.toString()
    }

    private fun maskValues(key: String?, values: List<String>): List<String> {
        if (key == null) return values.map { it }
        val lk = key.lowercase(Locale.ROOT)
        if (lk.contains("auth") || lk.contains("cookie") || lk.contains("token")
            || lk.contains("password") || lk.contains("authorization")
        ) {
            return listOf("[REDACTED]")
        }
        return values.map { it }
    }

    private fun escape(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format("\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        // decode as UTF-8 and count printable characters
        val s = try {
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            return false
        }
        if (s.isEmpty()) return true
        var printable = 0
        for (c in s) {
            if (c == '\n' || c == '\r' || c == '\t' || c >= ' ') printable++
        }
        return printable.toDouble() / s.length >= 0.95
    }

    // ---------------- wrapper stream types ------------------------------------------

    class LoggingInputStream(
        input: InputStream,
        private val target: String,
        private val method: String,
        private val headers: Map<String, List<String>>
    ) : FilterInputStream(input) {
        private val buf = ByteArrayOutputStream()
        @Volatile
        private var emitted = false

        private fun ensureEmit() {
            if (emitted) return
            emitted = true
            try {
                emit("http_response", target, method, headers, buf.toByteArray())
            } catch (_: Throwable) {}
        }

        @Throws(java.io.IOException::class)
        override fun read(): Int {
            val v = super.read()
            if (v != -1 && buf.size() < MAX_CAPTURE) buf.write(v)
            if (v == -1) {
                // EOF -> emit captured response once
                ensureEmit()
            }
            return v
        }

        @Throws(java.io.IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0 && buf.size() < MAX_CAPTURE) {
                val toWrite = kotlin.math.min(n, MAX_CAPTURE - buf.size())
                buf.write(b, off, toWrite)
            }
            if (n == -1) {
                // EOF -> emit captured response once
                ensureEmit()
            }
            return n
        }

        @Throws(java.io.IOException::class)
        override fun close() {
            try {
                super.close()
            } finally {
                // close is fallback if caller never reached EOF
                ensureEmit()
            }
        }
    }

    class LoggingOutputStream(
        out: OutputStream,
        private val target: String,
        private val method: String,
        private val headers: Map<String, List<String>>
    ) : FilterOutputStream(out) {
        private val buf = ByteArrayOutputStream()

        @Throws(java.io.IOException::class)
        override fun write(b: Int) {
            if (buf.size() < MAX_CAPTURE) buf.write(b)
            super.write(b)
        }

        @Throws(java.io.IOException::class)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (buf.size() < MAX_CAPTURE) {
                val toWrite = kotlin.math.min(len, MAX_CAPTURE - buf.size())
                buf.write(b, off, toWrite)
            }
            super.write(b, off, len)
        }

        @Throws(java.io.IOException::class)
        override fun close() {
            try {
                super.close()
            } finally {
                try {
                    emit("http_request", target, method, headers, buf.toByteArray())
                } catch (_: Throwable) {}
            }
        }
    }
}