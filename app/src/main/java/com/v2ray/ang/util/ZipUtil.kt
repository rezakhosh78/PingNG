package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ZipUtil {
    private const val BUFFER_SIZE = 4096

    data class ExtractionLimits(
        val maxArchiveBytes: Long = 64L * 1024 * 1024,
        val maxEntries: Int = 10_000,
        val maxEntryBytes: Long = 64L * 1024 * 1024,
        val maxTotalBytes: Long = 128L * 1024 * 1024,
        val maxCompressionRatio: Double = 1_000.0,
    )

    private data class ValidatedEntry(val entry: ZipEntry, val output: File)

    /**
     * Zip the contents of a folder.
     *
     * @param folderPath The path to the folder to zip.
     * @param outputZipFilePath The path to the output zip file.
     * @return True if the operation is successful, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    fun zipFromFolder(folderPath: String, outputZipFilePath: String): Boolean {
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            if (folderPath.isEmpty() || outputZipFilePath.isEmpty()) {
                return false
            }

            val filesToCompress = ArrayList<String>()
            val directory = File(folderPath)
            if (directory.isDirectory) {
                directory.listFiles()?.forEach {
                    if (it.isFile) {
                        filesToCompress.add(it.absolutePath)
                    }
                }
            }
            if (filesToCompress.isEmpty()) {
                return false
            }

            val zos = ZipOutputStream(FileOutputStream(outputZipFilePath))

            filesToCompress.forEach { file ->
                val ze = ZipEntry(File(file).name)
                zos.putNextEntry(ze)
                val inputStream = FileInputStream(file)
                while (true) {
                    val len = inputStream.read(buffer)
                    if (len <= 0) break
                    zos.write(buffer, 0, len)
                }

                inputStream.close()
            }

            zos.closeEntry()
            zos.close()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to zip folder", e)
            return false
        }
        return true
    }

    /**
     * Unzip the contents of a zip file to a folder.
     *
     * @param zipFile The zip file to unzip.
     * @param destDirectory The destination directory.
     * @return True if the operation is successful, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    fun unzipToFolder(zipFile: File, destDirectory: String): Boolean {
        try {
            extractArchive(zipFile, File(destDirectory))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to unzip file", e)
            return false
        }
        return true
    }

    /** Validates the full archive before writing any extracted file. */
    @Throws(IOException::class)
    fun extractArchive(
        zipFile: File,
        destDirectory: File,
        limits: ExtractionLimits = ExtractionLimits(),
    ) {
        validateLimits(limits)
        if (!zipFile.isFile) throw ZipException("Archive does not exist")
        if (zipFile.length() > limits.maxArchiveBytes) throw ZipException("Archive is too large")

        val root = destDirectory.canonicalFile
        val rootPath = root.path.trimEnd(File.separatorChar)
        val rootPrefix = if (rootPath.isEmpty()) File.separator else "$rootPath${File.separator}"

        ZipFile(zipFile).use { archive ->
            val entries = archive.entries().asSequence().toList()
            if (entries.size > limits.maxEntries) throw ZipException("Archive has too many entries")

            val seenPaths = HashSet<String>()
            var declaredTotalBytes = 0L
            val validated = entries.map { entry ->
                val safeName = normalizeEntryName(entry.name)
                val output = File(root, safeName).canonicalFile
                if (output.path != rootPath && !output.path.startsWith(rootPrefix)) {
                    throw ZipException("Archive entry escapes destination")
                }
                if (output.path == rootPath) throw ZipException("Archive entry has an empty path")
                if (!seenPaths.add(output.path)) throw ZipException("Archive contains duplicate destinations")

                if (entry.size > limits.maxEntryBytes) throw ZipException("Archive entry is too large")
                if (entry.size > 0L) {
                    if (entry.compressedSize <= 0L) throw ZipException("Archive entry has an invalid compressed size")
                    val ratio = entry.size.toDouble() / entry.compressedSize.toDouble()
                    if (!ratio.isFinite() || ratio > limits.maxCompressionRatio) {
                        throw ZipException("Archive entry compression ratio is too high")
                    }
                }
                if (entry.size > 0L) {
                    if (entry.size > limits.maxTotalBytes - declaredTotalBytes) {
                        throw ZipException("Archive expands beyond its size limit")
                    }
                    declaredTotalBytes += entry.size
                }
                ValidatedEntry(entry, output)
            }

            val files = validated.filterNot { it.entry.isDirectory }.map { it.output.path }.toSet()
            validated.forEach { candidate ->
                var parent = candidate.output.parentFile
                while (parent != null && parent.path != rootPath) {
                    if (parent.path in files) throw ZipException("Archive entry is nested below a file")
                    parent = parent.parentFile
                }
            }

            // Keep rejection paths above side-effect free: a malicious archive never creates
            // the destination directory or any partial extracted files.
            if (!root.exists() && !root.mkdirs()) throw IOException("Could not create destination")
            if (!root.isDirectory) throw IOException("Destination is not a directory")
            val canonicalRoot = root.canonicalFile
            val canonicalRootPrefix = "${canonicalRoot.path.trimEnd(File.separatorChar)}${File.separator}"
            var actualTotalBytes = 0L
            validated.forEach { item ->
                val destination = item.output.canonicalFile
                if (!destination.path.startsWith(canonicalRootPrefix)) {
                    throw ZipException("Archive entry escapes destination")
                }
                if (item.entry.isDirectory) {
                    if (destination.exists() && !destination.isDirectory) {
                        throw ZipException("Archive directory conflicts with a file")
                    }
                    if (!destination.exists() && !destination.mkdirs()) {
                        throw IOException("Could not create extracted directory")
                    }
                } else {
                    val parent = destination.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw IOException("Could not create extracted file directory")
                    }
                    if (destination.isDirectory) throw ZipException("Archive file conflicts with a directory")
                    archive.getInputStream(item.entry).use { input ->
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count.toLong() > limits.maxEntryBytes - entryBytes ||
                                    count.toLong() > limits.maxTotalBytes - actualTotalBytes
                                ) {
                                    throw ZipException("Archive expands beyond its size limit")
                                }
                                entryBytes += count
                                actualTotalBytes += count
                                output.write(buffer, 0, count)
                            }
                            if (item.entry.size >= 0L && entryBytes != item.entry.size) {
                                throw ZipException("Archive entry size does not match its metadata")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun validateLimits(limits: ExtractionLimits) {
        if (limits.maxArchiveBytes < 0L || limits.maxEntries < 0 || limits.maxEntryBytes < 0L ||
            limits.maxTotalBytes < 0L || !limits.maxCompressionRatio.isFinite() || limits.maxCompressionRatio < 0.0
        ) {
            throw ZipException("Invalid archive extraction limits")
        }
    }

    private fun normalizeEntryName(name: String): String {
        val normalizedSeparators = name.replace('\\', '/')
        if (normalizedSeparators.isBlank() || normalizedSeparators.startsWith('/') ||
            Regex("^[A-Za-z]:").containsMatchIn(normalizedSeparators) || '\u0000' in normalizedSeparators
        ) {
            throw ZipException("Archive entry has an invalid path")
        }
        val segments = normalizedSeparators.split('/')
        if (segments.any { it == ".." }) throw ZipException("Archive entry escapes destination")
        val safeSegments = segments.filter { it.isNotEmpty() && it != "." }
        if (safeSegments.isEmpty()) throw ZipException("Archive entry has an empty path")
        return safeSegments.joinToString(File.separator)
    }

}
