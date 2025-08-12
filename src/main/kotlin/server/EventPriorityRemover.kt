package server

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * Simple report structure:
 * - entriesFound: places where a priority element was present (class,
 *   method, parameter, annotation default, etc.)
 * - modifiedClasses: classes that were changed during sanitization
 */
data class PriorityFinding(
    val className: String,
    val location: String, // e.g. "method: onJoin", "class-annotation", "param-0"
    val annotationDesc: String
)

data class SanitizeReport(
    val jarPath: Path,
    val findings: MutableList<PriorityFinding> = mutableListOf(),
    val modifiedClasses: MutableSet<String> = mutableSetOf()
)

/**
 * Modes:
 * - SCAN: just report occurrences without writing changes
 * - SANITIZE: remove the "priority" enum element from @EventHandler
 *   annotations and write a new JAR (atomic replace)
 */
enum class Mode { SCAN, SANITIZE }

object EventHandlerPriorityTool {
    private const val EVENT_HANDLER_DESC = "Lorg/bukkit/event/EventHandler;"
    private const val EVENT_PRIORITY_DESC = "Lorg/bukkit/event/EventPriority;"

    /**
     * Process a jarPath in the given mode. Returns a report with findings
     * and which classes were modified (if SANITIZE).
     *
     * Behavior:
     * - SCAN: inspects all .class files, records any annotation elements
     *   named "priority" that point to EventPriority. Does not change file.
     * - SANITIZE: same as scan, but writes a temp JAR and replaces the
     *   original if any change was made. Removes the "priority" element
     *   from EventHandler annotations.
     *
     * Note: rewriting invalidates JAR signatures.
     */
    fun processJar(jarPath: Path, mode: Mode): SanitizeReport {
        val report = SanitizeReport(jarPath)
        val tmp = if (mode == Mode.SANITIZE) Files.createTempFile("san-", ".jar") else null

        JarFile(jarPath.toFile()).use { jar ->
            if (mode == Mode.SANITIZE) {
                JarOutputStream(Files.newOutputStream(tmp!!)).use { out ->
                    walkJar(jar, out, mode, report)
                }
            } else {
                walkJar(jar, null, mode, report)
            }
        }

        if (mode == Mode.SANITIZE) {
            val tmpPath = tmp!!
            if (report.modifiedClasses.isNotEmpty()) {
                // Replace original atomically where possible
                Files.move(
                    tmpPath, jarPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } else {
                Files.deleteIfExists(tmpPath)
            }
        }

        return report
    }

    /**
     * Walk jar entries. If jarOut is non-null we write entries there;
     * otherwise we only scan.
     */
    private fun walkJar(
        jar: JarFile,
        jarOut: JarOutputStream?,
        mode: Mode,
        report: SanitizeReport
    ) {
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            val outEntry = JarEntry(name)
            outEntry.time = entry.time
            // only put entry when writing
            jarOut?.putNextEntry(outEntry)

            if (name.endsWith(".class")) {
                jar.getInputStream(entry).use { ins ->
                    val bytes = ins.readBytes()
                    val (modifiedBytes, classModified, foundList) =
                        analyzeAndMaybeSanitizeClass(bytes, mode)

                    for (f in foundList) {
                        report.findings.add(
                            PriorityFinding(f.className, f.location, f.annotationDesc)
                        )
                    }
                    if (classModified) {
                        report.modifiedClasses.add(
                            foundList.firstOrNull()
                                ?.className
                                ?: name.removeSuffix(".class").replace('/', '.')
                        )
                    }

                    // Write only when jarOut is present (SANITIZE mode)
                    if (mode == Mode.SANITIZE && jarOut != null) {
                        if (classModified) jarOut.write(modifiedBytes)
                        else jarOut.write(bytes)
                    }
                }
            } else {
                // copy non-class resources only when writing
                jar.getInputStream(entry).use { ins ->
                    jarOut?.let { out -> ins.copyTo(out) }
                }
            }

            jarOut?.closeEntry()
        }
    }

    /**
     * Returns a triple:
     *  - resultingBytes (modified or original)
     *  - classModified flag
     *  - list of internal findings (class name, location, desc)
     */
    private fun analyzeAndMaybeSanitizeClass(
        classBytes: ByteArray,
        mode: Mode
    ): Triple<ByteArray, Boolean, List<PriorityFinding>> {
        val findings = mutableListOf<PriorityFinding>()
        val cr = ClassReader(classBytes)
        val cw = ClassWriter(cr, ClassWriter.COMPUTE_MAXS)

        var classModified = false
        // internal holder for current class name
        var currentClassInternalName = "<unknown>"

        val cv = object : ClassVisitor(Opcodes.ASM9, cw) {
            override fun visit(
                version: Int, access: Int, name: String?, signature: String?,
                superName: String?, interfaces: Array<out String>?
            ) {
                if (name != null) currentClassInternalName = name
                super.visit(version, access, name, signature, superName, interfaces)
            }

            override fun visitAnnotation(desc: String?, visible: Boolean)
                    : AnnotationVisitor? {
                val av = super.visitAnnotation(desc, visible)
                if (desc == EVENT_HANDLER_DESC) {
                    // wrap to detect and possibly drop "priority"
                    return wrapVisitor(av, currentClassInternalName, "class-annotation",
                        desc, mode) { removed -> if (removed) classModified = true }
                }
                return av
            }

            override fun visitMethod(
                access: Int, name: String?, descriptor: String?,
                signature: String?, exceptions: Array<out String>?
            ): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature,
                    exceptions)
                return object : MethodVisitor(Opcodes.ASM9, mv) {
                    override fun visitAnnotation(desc: String?, visible: Boolean)
                            : AnnotationVisitor? {
                        val av = super.visitAnnotation(desc, visible)
                        if (desc == EVENT_HANDLER_DESC) {
                            val location = "method:${name ?: "<anon>"}"
                            return wrapVisitor(av, currentClassInternalName, location,
                                desc, mode) { removed -> if (removed) classModified = true }
                        }
                        return av
                    }

                    override fun visitParameterAnnotation(
                        parameter: Int, desc: String?, visible: Boolean
                    ): AnnotationVisitor? {
                        val av = super.visitParameterAnnotation(parameter, desc, visible)
                        if (desc == EVENT_HANDLER_DESC) {
                            val location = "param:$parameter:method:${name ?: "<anon>"}"
                            return wrapVisitor(av, currentClassInternalName, location,
                                desc, mode) { removed -> if (removed) classModified = true }
                        }
                        return av
                    }

                    override fun visitAnnotationDefault(): AnnotationVisitor? {
                        val av = super.visitAnnotationDefault()
                        // default values on annotations can contain enum defaults
                        return wrapVisitor(av, currentClassInternalName,
                            "annotation-default", EVENT_HANDLER_DESC, mode) {
                                removed -> if (removed) classModified = true
                        }
                    }
                }
            }
        }

        cr.accept(cv, 0)

        val modifiedBytes = cw.toByteArray()
        // In SCAN mode we still populated findings via wrapVisitor side-effects,
        // but we didn't change class byte array. We detect modifications by
        // comparing bytes; in SANITIZE mode we write modifiedBytes only when
        // a change actually happened (tracked via classModified).
        return Triple(
            if (mode == Mode.SANITIZE && classModified) modifiedBytes else classBytes,
            classModified,
            findingsFromClassReader(cr, findings)
        )
    }

    /**
     * Helper to create and return a wrapped AnnotationVisitor that:
     * - detects visitEnum(name == "priority" && descriptor == EventPriority)
     *   -> records a finding
     * - in SANITIZE mode: drops (omits) that enum element
     * - in SCAN mode: forwards everything unchanged
     *
     * The callback onRemoved is invoked when we removed a priority element;
     * used to mark the class as modified.
     */
    private fun wrapVisitor(
        av: AnnotationVisitor?,
        classInternalName: String,
        location: String,
        annotationDesc: String,
        mode: Mode,
        onRemoved: (Boolean) -> Unit
    ): AnnotationVisitor? {
        if (av == null) return null

        return object : AnnotationVisitor(Opcodes.ASM9, av) {
            var removed = false

            override fun visitEnum(name: String?, descriptor: String?, value: String?) {
                // We consider "priority" element that is EventPriority enum
                if (name == "priority" && descriptor == EVENT_PRIORITY_DESC) {
                    // record finding (we cannot access outer findings list here,
                    // so we'll use a side effect callback via a lightweight approach:
                    // We record via a small static collector (below) or by producing
                    // a finding list after ClassReader — simpler approach:
                    SimpleFindingCollector.add(
                        classInternalName, location, annotationDesc
                    )

                    if (mode == Mode.SANITIZE) {
                        // Drop the enum element: do not call super.visitEnum -> omitted
                        removed = true
                        onRemoved(true)
                        return
                    } else {
                        // SCAN: keep it
                        super.visitEnum(name, descriptor, value)
                        return
                    }
                }
                super.visitEnum(name, descriptor, value)
            }

            override fun visit(name: String?, value: Any?) {
                super.visit(name, value)
            }

            override fun visitAnnotation(name: String?, descriptor: String?)
                    : AnnotationVisitor? {
                val nested = super.visitAnnotation(name, descriptor)

                return wrapVisitor(nested, classInternalName, location, descriptor
                    ?: annotationDesc, mode, onRemoved)
            }

            override fun visitArray(name: String?): AnnotationVisitor? {
                val arr = super.visitArray(name)
                return wrapVisitor(arr, classInternalName, location, annotationDesc,
                    mode, onRemoved)
            }
        }
    }

    /**
     * Build findings list from the SimpleFindingCollector and the className
     * list we accumulated during visitation.
     *
     * This is a small helper since the inner visitors cannot directly modify
     * the local findings list in analyzeAndMaybeSanitizeClass easily.
     */
    private fun findingsFromClassReader(
        cr: ClassReader,
        existing: MutableList<PriorityFinding>
    ): List<PriorityFinding> {
        val cls = cr.className // internal name like org/foo/Bar
        val dotted = cls.replace('/', '.')
        val list = SimpleFindingCollector.drain().map {
            PriorityFinding(dotted, it.location, it.annotationDesc)
        }
        existing.addAll(list)
        return existing
    }
}

/**
 * Tiny in-memory collector used to collect findings from many nested
 * AnnotationVisitor closures. Not multi-threaded in this example. For
 * multithreaded use, thread-safety or different strategy is needed.
 */
private object SimpleFindingCollector {
    data class Item(val location: String, val annotationDesc: String)
    private val map = mutableMapOf<String, MutableList<Item>>()

    fun add(classInternalName: String, location: String, annotationDesc: String) {
        val list = map.computeIfAbsent(classInternalName) { mutableListOf() }
        list.add(Item(location, annotationDesc))
    }

    fun drain(): List<ItemWithClass> {
        val out = mutableListOf<ItemWithClass>()
        for ((cls, items) in map) {
            for (it in items) out.add(ItemWithClass(cls, it.location, it.annotationDesc))
        }
        map.clear()
        return out
    }

    data class ItemWithClass(val classInternalName: String, val location: String, val annotationDesc: String)
}
