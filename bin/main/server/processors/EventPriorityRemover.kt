package server.processors

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import server.ClassSanitizer
import server.Finding
import server.Mode
import server.ProcessorResult

/**
 * Example sanitizer that removes the 'priority' element from
 * @EventHandler annotations (descriptor: Lorg/bukkit/event/EventHandler;).
 *
 * It records findings whenever it sees priority and removes the enum
 * element when mode==SANITIZE.
 */
class EventPriorityRemover : ClassSanitizer {
    override val id: String = "remove-eventhandler-priority"
    private val eventHandlerDesc = "Lorg/bukkit/event/EventHandler;"
    private val eventPriorityDesc = "Lorg/bukkit/event/EventPriority;"

    override fun process(classBytes: ByteArray, mode: Mode): ProcessorResult {
        val findings = mutableListOf<Finding>()
        val cr = ClassReader(classBytes)
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)

        var classInternalName = "<unknown>"
        var classModified = false

        val cv = object : ClassVisitor(Opcodes.ASM9, cw) {
            override fun visit(
                version: Int, access: Int, name: String?, signature: String?,
                superName: String?, interfaces: Array<out String>?
            ) {
                if (name != null) classInternalName = name
                super.visit(version, access, name, signature, superName, interfaces)
            }

            override fun visitAnnotation(desc: String?, visible: Boolean)
                    : AnnotationVisitor? {
                val av = super.visitAnnotation(desc, visible)
                return if (desc == eventHandlerDesc) {
                    wrap(av, "class-annotation")
                } else av
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
                        return if (desc == eventHandlerDesc) {
                            wrap(av, "method:${name ?: "<anon>"}")
                        } else av
                    }

                    override fun visitParameterAnnotation(
                        parameter: Int, desc: String?, visible: Boolean
                    ): AnnotationVisitor? {
                        val av = super.visitParameterAnnotation(parameter, desc, visible)
                        return if (desc == eventHandlerDesc) {
                            wrap(av, "param:$parameter:method:${name ?: "<anon>"}")
                        } else av
                    }

                    override fun visitAnnotationDefault(): AnnotationVisitor? {
                        val av = super.visitAnnotationDefault()
                        return if (av != null) wrap(av, "annotation-default") else av
                    }
                }
            }

            private fun wrap(av: AnnotationVisitor?, location: String)
                    : AnnotationVisitor? {
                if (av == null) return null
                return object : AnnotationVisitor(Opcodes.ASM9, av) {
                    override fun visitEnum(
                        name: String?, descriptor: String?, value: String?
                    ) {
                        if (name == "priority" && descriptor == eventPriorityDesc) {
                            // record finding
                            findings.add(
                                Finding(
                                    id, classInternalName.replace('/', '.'),
                                    location, "EventHandler.priority uses $value"
                                )
                            )
                            if (mode == Mode.SANITIZE) {
                                classModified = true
                                // drop element by not calling super.visitEnum
                                return
                            } else {
                                super.visitEnum(name, descriptor, value)
                                return
                            }
                        }
                        super.visitEnum(name, descriptor, value)
                    }

                    override fun visitAnnotation(name: String?, descriptor: String?)
                            : AnnotationVisitor? {
                        val nested = super.visitAnnotation(name, descriptor)
                        return wrap(nested, location)
                    }

                    override fun visitArray(name: String?): AnnotationVisitor? {
                        val arr = super.visitArray(name)
                        return wrap(arr, location)
                    }
                }
            }
        }

        cr.accept(cv, 0)
        val outBytes = if (classModified) cw.toByteArray() else classBytes
        return ProcessorResult(outBytes, classModified, findings)
    }
}