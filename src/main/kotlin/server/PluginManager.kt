package server

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import java.nio.file.Files
import java.util.jar.JarFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

object PluginManager {

    private val queue: Queue<PluginCandidate> = ConcurrentLinkedQueue()

    val verifyer = EventHandlerPriorityTool

    fun nextCandidate(): PluginCandidate? = queue.poll()

    fun peekCandidate(): PluginCandidate? = queue.peek()

    fun candidates(): Array<PluginCandidate?> = queue.toTypedArray()

    fun queueSize(): Int = queue.size

    init {
        OverLord.log.info("Plugin Manager has been initiated.")
    }

    data class PluginCandidate(
        val file: Path,
        val name: String? = null,
        val mainClass: String? = null
    )

    fun scanDirectory(path: Path, recursive: Boolean) {
        if (!path.isDirectory() || !path.exists()) {
            OverLord.log.warn("This plugin's path is either not a directory, or doesn't exist.", path)
            return
        }
        Files.newDirectoryStream(path) {p -> p.toString().endsWith(".jar")}.use {
            for (path in it) {
                val parsed = parseJarForYml(path)
                queue.add(PluginCandidate(path, parsed.first, parsed.second))
            OverLord.log.info("Found plugin %s, adding to queue", parsed.first)

            }
        }
        if (recursive) {
            Files.list(path).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .forEach { sub -> scanDirectory(sub, true) }
            }
        }
    }

    fun scanDirectory(path: String, recursive: Boolean) {
        scanDirectory(Path.of(path), recursive)
    }

    private fun parseJarForYml(jarPath: Path): Pair<String?, String?> {
        try {
            JarFile(jarPath.toFile()).use { jar ->
                val entry = jar.getJarEntry("plugin.yml") ?: return Pair(null, null)
                jar.getInputStream(entry).use { input ->
                    val reader = BufferedReader(InputStreamReader(input))
                    var name: String? = null
                    var main: String? = null
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (name == null && trimmed.startsWith("name:")) {
                            name = trimmed.substringAfter("name:").trim().removeSurrounding("\"")
                        }
                        if (main == null && trimmed.startsWith("main:")) {
                            main = trimmed.substringAfter("main:").trim().removeSurrounding("\"")
                        }
                        if (name != null && main != null) break
                    }
                    return Pair(name, main)
                }
            }
        } catch (_: Exception) {
            return Pair(null, null)
        }
    }

    fun loadPlugin(pluginCandidate: PluginCandidate) {
        OverLord.log.info("Plugin %s is being loaded", pluginCandidate.name)
        val start = System.nanoTime()
        OverLord.instance.server.pluginManager.loadPlugin(pluginCandidate.file.toFile())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        OverLord.log.info("Plugin %s loaded in %sms", pluginCandidate.name, elapsedMs)
    }

    fun verifyPlugin(candidate: PluginCandidate) {

    }

}

