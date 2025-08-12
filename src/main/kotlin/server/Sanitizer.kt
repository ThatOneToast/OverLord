package server


import org.objectweb.asm.ClassReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * Modes:
 * - SCAN: just report occurrences without writing changes
 * - SANITIZE: remove the "priority" enum element from @EventHandler
 *   annotations and write a new JAR (atomic replace)
 */
enum class Mode { SCAN, SANITIZE }


/**
 * Generic finding produced by a ClassSanitizer.
 */
data class Finding(
    val processorId: String,
    val className: String,
    val location: String, // e.g. "method:onJoin", "class-annotation"
    val message: String
)

/**
 * Result for a single class after running a processor.
 */
data class ProcessorResult(
    val bytes: ByteArray,
    val modified: Boolean,
    val findings: List<Finding>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProcessorResult

        if (modified != other.modified) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (findings != other.findings) return false

        return true
    }

    override fun hashCode(): Int {
        var result = modified.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + findings.hashCode()
        return result
    }
}

/**
 * Implement this to add a new rule/sanitizer for class bytes.
 */
interface ClassSanitizer {
    val id: String
    /**
     * Process the given class file bytes. Return potentially modified bytes,
     * whether any change occurred, and any findings (for SCAN and SANITIZE).
     */
    fun process(classBytes: ByteArray, mode: Mode): ProcessorResult
}

/**
 * Orchestrates applying a list of ClassSanitizer instances to every class
 * in a JAR (scan or sanitize mode). Returns a report of findings and which
 * classes were changed.
 */
class JarSanitizer(private val processors: List<ClassSanitizer>) {
    data class Report(
        val jarPath: Path,
        val findings: List<Finding>,
        val modifiedClasses: Set<String>
    )

    /**
     * Process the given jarPath in SCAN or SANITIZE mode. In SANITIZE mode
     * writes a temporary JAR and atomically replaces the original if any
     * classes were modified.
     */
    fun processJar(jarPath: Path, mode: Mode): Report {
        val findings = mutableListOf<Finding>()
        val modifiedClasses = mutableSetOf<String>()
        val temp = if (mode == Mode.SANITIZE) Files.createTempFile("san-", ".jar") else null

        JarFile(jarPath.toFile()).use { jar ->
            if (mode == Mode.SANITIZE) {
                JarOutputStream(Files.newOutputStream(temp!!)).use { out ->
                    walkJar(jar, out, mode, findings, modifiedClasses)
                }
            } else {
                walkJar(jar, null, mode, findings, modifiedClasses)
            }
        }

        if (mode == Mode.SANITIZE) {
            val tmpPath = temp!!
            if (modifiedClasses.isNotEmpty()) {
                Files.move(
                    tmpPath, jarPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } else {
                Files.deleteIfExists(tmpPath)
            }
        }

        return Report(jarPath, findings.toList(), modifiedClasses)
    }

    private fun walkJar(
        jar: JarFile,
        jarOut: JarOutputStream?,
        mode: Mode,
        findings: MutableList<Finding>,
        modifiedClasses: MutableSet<String>
    ) {
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            val outEntry = JarEntry(name)
            outEntry.time = entry.time
            jarOut?.putNextEntry(outEntry)

            if (name.endsWith(".class")) {
                jar.getInputStream(entry).use { ins ->
                    var bytes = ins.readBytes()
                    var classWasModified = false

                    // Run each processor sequentially; feed the output bytes
                    // of the previous into the next (pipeline).
                    for (proc in processors) {
                        val res = proc.process(bytes, mode)
                        if (res.modified) classWasModified = true
                        if (res.findings.isNotEmpty()) findings.addAll(res.findings)
                        bytes = res.bytes
                    }

                    if (classWasModified) {
                        // record dotted class name for report
                        val cr = ClassReader(bytes)
                        modifiedClasses.add(cr.className.replace('/', '.'))
                    }

                    if (mode == Mode.SANITIZE && jarOut != null) {
                        jarOut.write(bytes)
                    }
                }
            } else {
                // resource entry: copy only when writing
                jar.getInputStream(entry).use { ins ->
                    jarOut?.let { out -> ins.copyTo(out) }
                }
            }

            jarOut?.closeEntry()
        }
    }
}

