package server.agent


import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import server.OverLord
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class NetworkInjector {
    companion object {
        // helper classes to inject (must be compiled into OverLord JAR)
        private val HELPER_CLASSES = listOf(
            "server/agent/netmon/NetLog.class",
            $$"server/agent/netmon/NetLog$LoggingInputStream.class",
            $$"server/agent/netmon/NetLog$LoggingOutputStream.class"
        )
    }

    /**
     * Instrument [orig] in-place: write a temp instrumented jar in the same dir and
     * replace the original atomically (if supported). Returns true on success.
     *
     * - backupOriginal: copy original to plugins/instrument-backups/<ts>-name.jar
     *   before replacing (best-effort).
     */
    fun instrumentJar(orig: File, backupOriginal: Boolean = true): Boolean {
        if (!orig.exists() || !orig.isFile) return false
        val parent = orig.parentFile ?: return false

        val tmp = File.createTempFile("overlord-net-instr-", ".jar", parent)
        var jf: JarFile? = null
        try {
            jf = JarFile(orig)
            JarOutputStream(Files.newOutputStream(tmp.toPath())).use { jos ->
                val entries = jf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val name = e.name
                    jos.putNextEntry(JarEntry(name))
                    if (name.endsWith(".class")) {
                        val bytes = jf.getInputStream(e).readBytes()
                        val out = transformClass(bytes)
                        jos.write(out)
                    } else {
                        jf.getInputStream(e).use { it.copyTo(jos) }
                    }
                    jos.closeEntry()
                }

                // inject helper classes only if missing in the plugin jar
                for (path in HELPER_CLASSES) {
                    if (jf.getEntry(path) == null) {
                        val resource = "/$path"
                        val helperBytes = this::class.java
                            .getResourceAsStream(resource)?.readBytes()
                            ?: throw IllegalStateException(
                                "helper $resource not found in OverLord jar"
                            )
                        jos.putNextEntry(JarEntry(path))
                        jos.write(helperBytes)
                        jos.closeEntry()
                    }
                }
            }

            // best-effort backup of the original jar
            if (backupOriginal) {
                try {
                    val backupDir = File(parent, "instrument-backups")
                    if (!backupDir.exists()) backupDir.mkdirs()
                    val backup = File(
                        backupDir,
                        "${System.currentTimeMillis()}-${orig.name}"
                    )
                    Files.copy(
                        orig.toPath(),
                        backup.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (ex: Throwable) {
                    OverLord.log.warn(
                        "NetworkInjector: failed to backup %s: %s",
                        orig.name,
                        ex.message ?: ex
                    )
                }
            }

            // attempt atomic replace, fallback to non-atomic move
            try {
                Files.move(
                    tmp.toPath(),
                    orig.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (ame: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), orig.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            OverLord.log.info("Instrumented and replaced jar: %s", orig.name)
            return true
        } catch (ex: Throwable) {
            OverLord.log.error("NetworkInjector: failed to instrument %s", ex, orig.name)
            if (tmp.exists()) tmp.delete()
            return false
        } finally {
            try { jf?.close() } catch (_: Throwable) {}
        }
    }

    private fun transformClass(inBytes: ByteArray): ByteArray {
        val cr = ClassReader(inBytes)
        val cw = ClassWriter(cr, ClassWriter.COMPUTE_MAXS)
        val cv = NetworkRewriterClassVisitor(Opcodes.ASM9, cw)
        cr.accept(cv, 0)
        return cw.toByteArray()
    }

    private class NetworkRewriterClassVisitor(api: Int, cv: ClassVisitor) : ClassVisitor(api, cv) {
        private var currentClass: String? = null

        override fun visit(version: Int, access: Int, name: String?,
                           signature: String?, superName: String?,
                           interfaces: Array<out String>?) {
            currentClass = name
            super.visit(version, access, name, signature, superName, interfaces)
        }

        override fun visitMethod(access: Int, name: String?, descriptor: String?,
                                 signature: String?, exceptions: Array<out String>?): MethodVisitor {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            // do not rewrite the helper package itself (we inject it)
            if (currentClass != null && currentClass!!.startsWith("server/agent/netmon")) {
                return mv
            }

            return object : MethodVisitor(api, mv) {
                override fun visitMethodInsn(opcode: Int, owner: String, mName: String,
                                             mDesc: String, itf: Boolean) {
                    // url.openConnection() => NetLog.openConnection(URL)
                    if (owner == "java/net/URL" && mName == "openConnection"
                        && mDesc == "()Ljava/net/URLConnection;") {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "server/agent/netmon/NetLog",
                            "openConnection", "(Ljava/net/URL;)Ljava/net/URLConnection;", false)
                        return
                    }

                    // url.openStream() => NetLog.openStream(URL)
                    if (owner == "java/net/URL" && mName == "openStream"
                        && mDesc == "()Ljava/io/InputStream;") {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "server/agent/netmon/NetLog",
                            "openStream", "(Ljava/net/URL;)Ljava/io/InputStream;", false)
                        return
                    }

                    // conn.getInputStream() => NetLog.getLoggedInputStream(URLConnection)
                    if (owner == "java/net/URLConnection" && mName == "getInputStream"
                        && mDesc == "()Ljava/io/InputStream;") {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "server/agent/netmon/NetLog",
                            "getLoggedInputStream", "(Ljava/net/URLConnection;)Ljava/io/InputStream;", false)
                        return
                    }

                    // conn.getOutputStream() => NetLog.getLoggedOutputStream(URLConnection)
                    if (owner == "java/net/URLConnection" && mName == "getOutputStream"
                        && mDesc == "()Ljava/io/OutputStream;") {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "server/agent/netmon/NetLog",
                            "getLoggedOutputStream", "(Ljava/net/URLConnection;)Ljava/io/OutputStream;", false)
                        return
                    }

                    // socket.connect(addr) => NetLog.socketConnect(Socket, SocketAddress)
                    if (owner == "java/net/Socket" && mName == "connect"
                        && mDesc == "(Ljava/net/SocketAddress;)V") {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "server/agent/netmon/NetLog",
                            "socketConnect", "(Ljava/net/Socket;Ljava/net/SocketAddress;)V", false)
                        return
                    }

                    super.visitMethodInsn(opcode, owner, mName, mDesc, itf)
                }
            }
        }
    }
}