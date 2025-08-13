package server.agent

import server.OverLord
import org.objectweb.asm.*
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.jar.JarFile

data class Incident(
    val ts: Instant,
    val origin: String,
    val detectedPlugin: String?,
    val frames: String,
    val args: String
)

data class ForbiddenSignature(val typeRegex: Pattern, val methodRegex: Pattern, val desc: String? = null) {
    constructor(typeRegex: String, methodRegex: String, desc: String? = null)
            : this(Pattern.compile(typeRegex), Pattern.compile(methodRegex), desc)
}

data class ForbiddenAnnotationSignature(
    val annotationTypeRegex: Pattern,
    val enumTypeRegex: Pattern? = null,
    val paramName: String? = null,
    val desc: String? = null
) {
    constructor(
        annotationTypeRegex: String,
        enumTypeRegex: String? = null,
        paramName: String? = null,
        desc: String? = null
    ) : this(
        Pattern.compile(annotationTypeRegex),
        enumTypeRegex?.let { Pattern.compile(it) },
        paramName,
        desc
    )
}

data class ForbiddenFieldSignature(
    val ownerTypeRegex: Pattern,
    val fieldNameRegex: Pattern? = null,
    val desc: String? = null
) {
    constructor(ownerTypeRegex: String, fieldNameRegex: String? = null,
                desc: String? = null)
            : this(Pattern.compile(ownerTypeRegex),
        fieldNameRegex?.let { Pattern.compile(it) },
        desc)
}

data class Match(
    val classContainingCall: String,
    val methodContainingCall: String,
    val owner: String, // invoked owner (dot form)
    val methodName: String,
    val methodDesc: String
)

data class ForbiddenScanResult(
    val file: File,
    val matches: List<Match>,
    val suspiciousStrings: List<String>
)

@Suppress("unused")
object AgentBridge {
    private const val ASM_API = Opcodes.ASM9
    private val INCIDENTS = ConcurrentLinkedQueue<Incident>()
    private val COUNTS = ConcurrentHashMap<String, AtomicInteger>()

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "overlord-agent-writer").apply { isDaemon = true }
    }

    private val forbiddenSignatures = CopyOnWriteArrayList<ForbiddenSignature>()
    private val forbiddenAnnotationSignatures = CopyOnWriteArrayList<ForbiddenAnnotationSignature>()
    private val forbiddenFieldSignatures = CopyOnWriteArrayList<ForbiddenFieldSignature>()

    // additional keyword heuristics (will flag LDC strings)
    private val suspiciousKeywords = listOf(
        "forceload", "setChunkForceLoaded", "ChunkProviderServer", "PlayerChunkMap",
        "addTicket", "ChunkTicketManager", "chunkticket", "loadChunk", "provideChunk"
    )



    fun addForbiddenSignature(typeRegex: String, methodRegex: String, description: String? = null) =
        forbiddenSignatures.add(ForbiddenSignature(typeRegex, methodRegex, description))


    fun clearForbiddenSignatures() {
        forbiddenSignatures.clear()
    }

    fun addForbiddenAnnotationSignature(
        annotationTypeRegex: String,
        enumTypeRegex: String? = null,
        paramName: String? = null,
        description: String? = null
    ) = forbiddenAnnotationSignatures.add(ForbiddenAnnotationSignature(annotationTypeRegex, enumTypeRegex, paramName, description))


    fun clearForbiddenAnnotationSignatures() {
        forbiddenAnnotationSignatures.clear()
    }


    fun addForbiddenFieldSignature(ownerTypeRegex: String, fieldNameRegex: String? = null, description: String? = null)  =
        forbiddenFieldSignatures.add(ForbiddenFieldSignature(ownerTypeRegex, fieldNameRegex, description))


    fun clearForbiddenFieldSignatures() {
        forbiddenFieldSignatures.clear()
    }

    /**
     * Scan a plugin jar file for forbidden invocations and suspicious strings.
     * Returns a ForbiddenScanResult listing matches.
     */
    fun scanJarForForbidden(file: File): ForbiddenScanResult {
        val matches = ArrayList<Match>()
        val suspiciousStrings = ArrayList<String>()

        if (!file.exists() || !file.isFile) {
            return ForbiddenScanResult(file, matches, suspiciousStrings)
        }

        try {
            JarFile(file).use { jf ->
                val entries = jf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name.endsWith(".class")) {
                        jf.getInputStream(entry).use { ins ->
                            scanClassStream(ins, matches)
                        }
                    } else if (name.endsWith(".yml", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true)) {
                        // scan resource files for suspicious keywords too
                        try {
                            jf.getInputStream(entry).use { ris ->
                                val bytes = ris.readBytes()
                                val s = try { String(bytes, StandardCharsets.UTF_8) } catch (_: Throwable) { "" }
                                for (kw in suspiciousKeywords) {
                                    if (s.contains(kw, ignoreCase = true)) {
                                        suspiciousStrings.add("resource:$name contains '$kw'")
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (ex: Throwable) {
            // fail-safe: if scanning fails, don't produce false negatives; log and return empty matches
            try {
                OverLord.log.error("AgentBridge: failed to scan jar ${file.name}: %s", ex)
            } catch (_: Throwable) {}
        }

        return ForbiddenScanResult(file, matches.toList(), suspiciousStrings.toList())
    }

    /**
     * Convenience: true if jar does NOT contain forbidden matches.
     * If forbidden matches are found, record an incident and return false.
     */
    fun isJarAllowed(file: File, quarantineOnFail: Boolean = true): Boolean {
        val result = scanJarForForbidden(file)
        val allowed = result.matches.isEmpty() && result.suspiciousStrings.isEmpty()
        if (!allowed) {
            // Build a compact description
            val pluginName = extractPluginNameFromJar(file) ?: file.name
            val origin = "Static-scan($pluginName)"
            val frames = buildString {
                append("Matches:\n")
                for (m in result.matches) {
                    append("  in ${m.classContainingCall}#${m.methodContainingCall}: invoked ${m.owner}.${m.methodName}${m.methodDesc}\n")
                }
                if (result.suspiciousStrings.isNotEmpty()) {
                    append("Suspicious strings:\n")
                    for (s in result.suspiciousStrings) append("  $s\n")
                }
            }
            val args = "jar=${file.name}"

            record(origin, pluginName, frames, args)

            if (quarantineOnFail) {
                try {
                    val q = quarantineJar(file)
                    OverLord.log.warn("Quarantined plugin jar %s -> %s due to forbidden API usage", file.name, q.name)
                } catch (ex: Throwable) {
                    OverLord.log.warn("Failed to quarantine %s: %s", file.name, ex.message ?: ex.toString())
                }
            } else {
                OverLord.log.warn("Plugin %s flagged by static scan and will not be loaded (see agent logs).", file.name)
            }
        }
        return allowed
    }

    /**
     * Move jar to dataFolder/quarantine/<timestamp>-originalName.jar and return dest File.
     */
    fun quarantineJar(file: File): File {
        val pluginsDir = file.parentFile ?: File(".")
        val quarantineDir = File(pluginsDir, "quarantine")
        if (!quarantineDir.exists()) quarantineDir.mkdirs()
        val dest = File(quarantineDir, "${System.currentTimeMillis()}-${file.name}")
        Files.move(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return dest
    }


    fun record(origin: String, detectedPlugin: String?, frames: String, args: String) {
        val inc = Incident(Instant.now(), origin, detectedPlugin, frames, args)
        INCIDENTS.add(inc)
        detectedPlugin?.let {
            COUNTS.computeIfAbsent(it) { AtomicInteger(0) }.incrementAndGet()
        }

        try {
            OverLord.log.agentIncident(origin, detectedPlugin, args, frames)
            return
        } catch (_: Throwable) {}

        // Append a compact log asynchronously
        writer.submit {
            try {
                val f = File("plugins", "overlord-snitch.log")
                val out = StringBuilder()
                    .append(inc.ts).append(" ORIGIN=").append(origin)
                    .append(" PLUGIN=").append(detectedPlugin ?: "UNKNOWN")
                    .append(" ARGS=").append(args).append('\n')
                    .append(frames).append("\n\n")
                    .toString()
                if (!f.parentFile.exists()) f.parentFile.mkdirs()
                Files.write(f.toPath(), out.toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } catch (_: Throwable) {}
        }
    }

    fun recentIncidents(limit: Int = 20): List<Incident> {
        val list = INCIDENTS.toList()
        return if (list.size <= limit) list else list.takeLast(limit)
    }

    fun topOffenders(limit: Int = 10): List<Pair<String, Int>> {
        return COUNTS.entries
            .map { it.key to it.value.get() }
            .sortedByDescending { it.second }
            .take(limit)
    }

    fun shutdownWriter() {
        writer.shutdownNow()
    }


    private fun scanClassStream(ins: InputStream, matchesOut: MutableList<Match>) {
        try {
            val cr = ClassReader(ins.readBytes())
            val classNameRef = arrayOf<String?>(null)
            val suspiciousCollector = ArrayList<String>()

            val cv = object : ClassVisitor(ASM_API) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String?,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?
                ) {
                    classNameRef[0] = name?.replace('/', '.')
                    super.visit(version, access, name, signature, superName, interfaces)
                }

                override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor {
                    val av = super.visitAnnotation(desc, visible)
                    return object : AnnotationVisitor(ASM_API, av) {
                        override fun visitEnum(name: String?, descriptor: String?, value: String?) {
                            try {
                                val annotationDot = desc?.removePrefix("L")
                                    ?.removeSuffix(";")?.replace('/', '.') ?: return
                                val enumDot = descriptor?.removePrefix("L")
                                    ?.removeSuffix(";")?.replace('/', '.') ?: return

                                for (sig in forbiddenAnnotationSignatures) {
                                    if (sig.annotationTypeRegex.matcher(annotationDot).find()
                                        && (sig.enumTypeRegex == null
                                                || sig.enumTypeRegex.matcher(enumDot).find())
                                        && (sig.paramName == null || sig.paramName == name)
                                    ) {
                                        matchesOut.add(
                                            Match(
                                                classNameRef[0] ?: "unknown",
                                                "<class>",
                                                annotationDot,
                                                "$name=$value",
                                                enumDot
                                            )
                                        )
                                    }
                                }
                            } catch (_: Throwable) {}
                            super.visitEnum(name, descriptor, value)
                        }
                    }
                }

                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor {
                    val methodName = name ?: "<init?>"
                    val parentClass = classNameRef[0] ?: "unknown"
                    var lastEnumLoad: Pair<String, String>? = null

                    return object : MethodVisitor(ASM_API) {
                        override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor {
                            val av = super.visitAnnotation(desc, visible)
                            return object : AnnotationVisitor(ASM_API, av) {
                                override fun visitEnum(name: String?, descriptor: String?,
                                                       value: String?) {
                                    try {
                                        val annotationDot = desc?.removePrefix("L")
                                            ?.removeSuffix(";")?.replace('/', '.') ?: return
                                        val enumDot = descriptor?.removePrefix("L")
                                            ?.removeSuffix(";")?.replace('/', '.') ?: return

                                        for (sig in forbiddenAnnotationSignatures) {
                                            if (sig.annotationTypeRegex.matcher(annotationDot).find()
                                                && (sig.enumTypeRegex == null
                                                        || sig.enumTypeRegex.matcher(enumDot).find())
                                                && (sig.paramName == null || sig.paramName == name)
                                            ) {
                                                matchesOut.add(
                                                    Match(
                                                        parentClass,
                                                        methodName,
                                                        annotationDot,
                                                        "$name=$value",
                                                        enumDot
                                                    )
                                                )
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                    super.visitEnum(name, descriptor, value)
                                }
                            }
                        }

                        override fun visitFieldInsn(opcode: Int, owner: String, fname: String,
                                                    fdesc: String) {
                            try {
                                if (opcode == Opcodes.GETSTATIC) {
                                    val ownerDot = owner.replace('/', '.')
                                    for (sig in forbiddenFieldSignatures) {
                                        if (sig.ownerTypeRegex.matcher(ownerDot).find()
                                            && (sig.fieldNameRegex == null
                                                    || sig.fieldNameRegex.matcher(fname).find())
                                        ) {
                                            matchesOut.add(
                                                Match(parentClass, methodName, ownerDot, fname,
                                                    fdesc)
                                            )
                                        }
                                    }
                                    lastEnumLoad = owner.replace('/', '.') to fname
                                }
                            } catch (_: Throwable) {}
                            super.visitFieldInsn(opcode, owner, fname, fdesc)
                        }

                        override fun visitMethodInsn(opcode: Int, owner: String, mName: String,
                                                     mDesc: String, itf: Boolean) {
                            try {
                                val ownerDot = owner.replace('/', '.')
                                for (sig in forbiddenSignatures) {
                                    if (sig.typeRegex.matcher(ownerDot).find()
                                        && sig.methodRegex.matcher(mName).find()
                                    ) {
                                        matchesOut.add(
                                            Match(parentClass, methodName, ownerDot, mName, mDesc)
                                        )
                                    }
                                }

                                // catch enum.valueOf calls on the enum class itself
                                for (fSig in forbiddenFieldSignatures) {
                                    if (fSig.ownerTypeRegex.matcher(ownerDot).find()
                                        && mName == "valueOf"
                                    ) {
                                        matchesOut.add(
                                            Match(parentClass, methodName, ownerDot, mName, mDesc)
                                        )
                                    }
                                }
                            } catch (_: Throwable) {}
                            lastEnumLoad = null
                            super.visitMethodInsn(opcode, owner, mName, mDesc, itf)
                        }

                        override fun visitLdcInsn(value: Any?) {
                            try {
                                if (value is String) {
                                    for (kw in suspiciousKeywords) {
                                        if (value.contains(kw, ignoreCase = true)) {
                                            suspiciousCollector.add(
                                                "const '$value' contains '$kw' in " +
                                                        "$parentClass.$methodName"
                                            )
                                        }
                                    }
                                    if (value.contains('.')) {
                                        for (sig in forbiddenSignatures) {
                                            try {
                                                if (sig.typeRegex.matcher(value).matches()) {
                                                    suspiciousCollector.add(
                                                        "const '$value' matches forbidden " +
                                                                "type pattern in $parentClass.$methodName"
                                                    )
                                                }
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}
                            super.visitLdcInsn(value)
                        }
                    }
                }
            }

            cr.accept(cv, ClassReader.SKIP_FRAMES)
            for (s in suspiciousCollector) {
                matchesOut.add(
                    Match(classNameRef[0] ?: "unknown", "<const>", "STRING_HINT", s, "")
                )
            }
        } catch (ex: Throwable) {
            // swallow; scanning is best-effort
        }
    }

    private fun extractPluginNameFromJar(file: File): String? {
        try {
            JarFile(file).use { jf ->
                val entry = jf.getEntry("plugin.yml") ?: return null
                jf.getInputStream(entry).use { ins ->
                    val text = ins.readBytes().toString(StandardCharsets.UTF_8)
                    // naive YAML parse for "name: "
                    val lines = text.lines()
                    for (ln in lines) {
                        val t = ln.trim()
                        if (t.startsWith("name:")) {
                            return t.substringAfter("name:").trim().trim('"', '\'')
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }
}