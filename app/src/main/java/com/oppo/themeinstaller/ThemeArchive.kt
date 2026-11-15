package com.oppo.themeinstaller

import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class ThemeInfo(
    val name: String = "Unknown theme",
    val description: String = "",
    val author: String = "",
    val version: String = "",
    val components: String = "",
    val resolutions: String = ""
)

data class InspectionResult(
    val valid: Boolean,
    val message: String,
    val info: ThemeInfo,
    val previewFile: File?
)

object ThemeArchive {
    private const val MAX_ENTRIES = 500
    private const val MAX_TOTAL_UNCOMPRESSED = 50L * 1024L * 1024L
    private const val MAX_SINGLE_FILE = 20L * 1024L * 1024L

    fun inspect(file: File): InspectionResult {
        require(file.isFile && file.length() > 0) { "Selected file is empty." }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= MAX_ENTRIES) { "Archive has too many entries." }
            var total = 0L
            entries.forEach { entry ->
                val normalized = entry.name.replace('\\', '/')
                require(!normalized.startsWith("/") && !normalized.split("/").contains("..")) {
                    "Unsafe archive path found."
                }
                if (!entry.isDirectory) {
                    require(entry.size <= MAX_SINGLE_FILE || entry.size < 0) { "An archive entry is too large." }
                    if (entry.size > 0) total += entry.size
                }
            }
            require(total <= MAX_TOTAL_UNCOMPRESSED) { "Archive expands beyond the allowed size." }
            val xmlEntry = zip.getEntry("themeInfo.xml") ?: error("themeInfo.xml is missing.")
            val xml = zip.getInputStream(xmlEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            require(xml.length <= 1_000_000) { "Theme metadata is too large." }
            val info = parseInfo(xml)
            val previewEntry = entries.firstOrNull {
                !it.isDirectory && it.name.startsWith("picture/") &&
                    it.name.substringAfterLast('/').matches(Regex("(?i).+\\.(jpg|jpeg|png|webp)"))
            }
            val preview = previewEntry?.let { entry ->
                val out = File.createTempFile("theme_preview_", ".${entry.name.substringAfterLast('.').lowercase()}")
                zip.getInputStream(zip.getEntry(entry.name)).use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            copied += n
                            require(copied <= MAX_SINGLE_FILE) { "Preview exceeds size limit." }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                out
            }
            val hasLockscreen = entries.any { it.name == "lockscreen/lockstyle" }
            val components = if (info.components.isBlank() && hasLockscreen) "lockscreen" else info.components
            return InspectionResult(
                valid = true,
                message = "Valid ZIP theme package. Installation compatibility is not confirmed.",
                info = info.copy(components = components),
                previewFile = preview
            )
        }
    }

    fun extractPreview(file: File, cacheDir: File): File? {
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.startsWith("picture/") &&
                    it.name.substringAfterLast('/').matches(Regex("(?i).+\\.(jpg|jpeg|png|webp)"))
            } ?: return null
            val out = File.createTempFile("oppo_preview_", ".${entry.name.substringAfterLast('.').lowercase()}", cacheDir)
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var count = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= MAX_SINGLE_FILE) { "Preview exceeds size limit." }
                        output.write(buffer, 0, n)
                    }
                }
            }
            return out
        }
    }

    private fun parseInfo(xml: String): ThemeInfo {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE declarations are not allowed." }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        fun value(tag: String): String = doc.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
        val packageNodes = doc.getElementsByTagName("package")
        val packages = (0 until packageNodes.length).mapNotNull {
            packageNodes.item(it).attributes?.getNamedItem("name")?.nodeValue
        }
        val resolutions = doc.getElementsByTagName("resolution")
        val resolutionText = (0 until resolutions.length).map { resolutions.item(it).textContent.trim() }
            .filter { it.isNotBlank() }.joinToString(", ")
        return ThemeInfo(
            name = value("Summary").ifBlank { "Unknown theme" },
            description = value("Description"),
            author = value("Author"),
            version = value("VersionName"),
            components = packages.joinToString(", "),
            resolutions = resolutionText
        )
    }
}
