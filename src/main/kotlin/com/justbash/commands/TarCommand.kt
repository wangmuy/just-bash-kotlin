package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import com.justbash.fs.PathUtils
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant

/**
 * tar command backed by Apache Commons Compress.
 *
 * Supports creating (-c), extracting (-x), and listing (-t) tar archives with
 * optional gzip compression (-z) via Commons Compress Gzip streams.
 *
 * Port of just-bash `tar/tar.ts` (subset: no bzip2/xz/zstd, no append/update).
 */
object TarCommand : Command {
    override val name = "tar"

    private const val DEFAULT_SIZE = 0L

    // ------------------------------------------------------------------
    // Options
    // ------------------------------------------------------------------

    private data class Options(
        var create: Boolean = false,
        var extract: Boolean = false,
        var list: Boolean = false,
        var file: String? = null,
        var gzip: Boolean = false,
        var verbose: Boolean = false,
        var toStdout: Boolean = false,
        var directory: String? = null,
    )

    private data class ParseResult(
        val options: Options,
        val files: List<String>,
    )

    private data class ParseError(val result: ExecResult) : Exception()

    private fun parseOptions(args: List<String>): ParseResult {
        val options = Options()
        val files = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]

            // End of options (classic tar also supports "--")
            if (arg == "--") {
                i++
                while (i < args.size) { files.add(args[i]); i++ }
                break
            }

            if (arg.startsWith("--")) {
                i = parseLongOption(arg, args, i, options)
            } else if (arg.startsWith("-") && arg != "-" && arg.length > 1 && !arg[1].isDigit()) {
                i = parseCombinedShort(arg, args, i, options)
            } else if (arg.startsWith("-") && arg.length > 1 && arg[1].isDigit()) {
                // Negative-number-looking operand, treat as a file name
                files.add(arg)
            } else {
                files.add(arg)
            }
            i++
        }

        return ParseResult(options, files)
    }

    private fun parseLongOption(
        arg: String, args: List<String>, i: Int, options: Options,
    ): Int {
        var idx = i
        when {
            arg == "--create" -> options.create = true
            arg == "--extract" || arg == "--get" -> options.extract = true
            arg == "--list" -> options.list = true
            arg == "--gzip" || arg == "--gunzip" -> options.gzip = true
            arg == "--verbose" -> options.verbose = true
            arg == "--to-stdout" -> options.toStdout = true
            arg == "--file" -> { idx++; options.file = requireValue(args, idx, "f") }
            arg.startsWith("--file=") -> options.file = arg.substring(7)
            arg == "--directory" -> { idx++; options.directory = requireValue(args, idx, "C") }
            arg.startsWith("--directory=") -> options.directory = arg.substring(12)
            else -> throw ParseError(unknownOption("tar", arg))
        }
        return idx
    }

    private fun requireValue(args: List<String>, idx: Int, name: String): String {
        if (idx >= args.size) {
            throw ParseError(ExecResult(stderr = "tar: option requires an argument -- '$name'\n", exitCode = 2))
        }
        return args[idx]
    }

    private fun parseCombinedShort(
        arg: String, args: List<String>, i: Int, options: Options,
    ): Int {
        var idx = i
        var j = 1
        while (j < arg.length) {
            when (val c = arg[j]) {
                'c' -> options.create = true
                'x' -> options.extract = true
                't' -> options.list = true
                'z' -> options.gzip = true
                'v' -> options.verbose = true
                'O' -> options.toStdout = true
                'f', 'C' -> {
                    val name = if (c == 'f') "f" else "C"
                    if (j < arg.length - 1) {
                        val value = arg.substring(j + 1)
                        if (c == 'f') options.file = value else options.directory = value
                        return idx
                    }
                    idx++
                    val value = requireValue(args, idx, name)
                    if (c == 'f') options.file = value else options.directory = value
                    return idx
                }
                else -> throw ParseError(unknownOption("tar", "-$c"))
            }
            j++
        }
        return idx
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun stdinBytes(ctx: CommandContext): ByteArray = ctx.stdin

    private fun readArchiveBytes(ctx: CommandContext, file: String?): ByteArray {
        return if (file != null && file != "-") {
            val path = ctx.fs.resolvePath(ctx.cwd, file)
            ctx.fs.readFileBuffer(path)
        } else {
            stdinBytes(ctx)
        }
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0x1F && bytes[1].toInt() and 0xFF == 0x8B

    /** Open a tar input stream, transparently gunzipping when needed. */
    private fun openTarInput(bytes: ByteArray, gzip: Boolean): TarArchiveInputStream {
        var input = ByteArrayInputStream(bytes)
        // Auto-detect gzip magic even when -z was not passed, matching GNU tar.
        val wantGzip = gzip || isGzip(bytes)
        if (wantGzip) {
            input = ByteArrayInputStream(bytes)
        }
        return if (wantGzip) {
            TarArchiveInputStream(GzipCompressorInputStream(input))
        } else {
            TarArchiveInputStream(input)
        }
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    private fun collectEntries(
        ctx: CommandContext,
        basePath: String,
        relativePath: String,
        entries: MutableList<EntrySpec>,
    ): MutableList<String> {
        val errors = ArrayList<String>()
        val fullPath = ctx.fs.resolvePath(basePath, relativePath)

        val stat = try {
            ctx.fs.stat(fullPath)
        } catch (e: Exception) {
            errors.add("tar: ${relativePath}: ${e.message ?: "unknown error"}")
            return errors
        }

        when {
            stat.isDirectory -> {
                val name = if (relativePath.isEmpty()) relativePath else relativePath.removeSuffix("/")
                entries.add(
                    EntrySpec(
                        name = name,
                        isDirectory = true,
                        mode = stat.mode,
                        mtime = stat.mtime,
                    ),
                )
                for (item in ctx.fs.readdir(fullPath)) {
                    val childRel = if (name.isEmpty()) item else "$name/$item"
                    errors.addAll(collectEntries(ctx, basePath, childRel, entries))
                }
            }
            stat.isFile -> {
                val content = ctx.fs.readFileBuffer(fullPath)
                entries.add(
                    EntrySpec(
                        name = relativePath,
                        content = content,
                        mode = stat.mode,
                        mtime = stat.mtime,
                    ),
                )
            }
            stat.isSymbolicLink -> {
                val target = ctx.fs.readlink(fullPath)
                entries.add(
                    EntrySpec(
                        name = relativePath,
                        isSymlink = true,
                        linkTarget = target,
                        mode = stat.mode,
                        mtime = stat.mtime,
                    ),
                )
            }
        }
        return errors
    }

    private fun createArchive(ctx: CommandContext, options: Options, files: List<String>): ExecResult {
        if (files.isEmpty()) {
            return ExecResult(stderr = "tar: Cowardly refusing to create an empty archive\n", exitCode = 2)
        }

        val workDir = options.directory?.let { ctx.fs.resolvePath(ctx.cwd, it) } ?: ctx.cwd

        val entries = ArrayList<EntrySpec>()
        val errors = ArrayList<String>()
        val verboseOut = StringBuilder()

        for (file in files) {
            val before = entries.size
            errors.addAll(collectEntries(ctx, workDir, file, entries))
            if (options.verbose) {
                for (e in entries.subList(before, entries.size)) {
                    verboseOut.append(e.name)
                    if (e.isDirectory && !e.name.endsWith("/")) verboseOut.append("/")
                    verboseOut.append("\n")
                }
            }
        }

        if (entries.isEmpty() && errors.isNotEmpty()) {
            return ExecResult(stderr = errors.joinToString("\n") + "\n", exitCode = 2)
        }

        val archiveBytes: ByteArray = try {
            buildTarBytes(entries, options.gzip)
        } catch (e: Exception) {
            return ExecResult(stderr = "tar: error creating archive: ${e.message ?: "unknown error"}\n", exitCode = 2)
        }

        var stdout = ""
        if (options.file != null && options.file != "-") {
            val archivePath = ctx.fs.resolvePath(ctx.cwd, options.file!!)
            try {
                ctx.fs.writeFile(archivePath, archiveBytes)
            } catch (e: Exception) {
                return ExecResult(stderr = "tar: ${options.file}: ${e.message ?: "unknown error"}\n", exitCode = 2)
            }
        } else {
            stdout = latin1FromBytes(archiveBytes)
        }

        val stderr = StringBuilder()
        stderr.append(verboseOut)
        if (errors.isNotEmpty()) stderr.append(errors.joinToString("\n")).append("\n")

        return ExecResult(
            stdout = stdout,
            stderr = stderr.toString(),
            exitCode = if (errors.isNotEmpty()) 2 else 0,
            stdoutKind = if (stdout.isNotEmpty()) "bytes" else "text",
        )
    }

    private fun buildTarBytes(entries: List<EntrySpec>, gzip: Boolean): ByteArray {
        val raw = buildTarBytesInternal(entries)
        return if (gzip) {
            val bos = ByteArrayOutputStream()
            GzipCompressorOutputStream(bos).use { it.write(raw) }
            bos.toByteArray()
        } else {
            raw
        }
    }

    private fun buildTarBytesInternal(entries: List<EntrySpec>): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(bos).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (entry in entries) {
                val name = if (entry.isDirectory && !entry.name.endsWith("/")) "${entry.name}/" else entry.name
                val tarEntry = TarArchiveEntry(name)
                tarEntry.mode = entry.mode
                tarEntry.modTime = java.util.Date.from(entry.mtime)
                when {
                    entry.isDirectory -> { /* mode already set as directory */ }
                    entry.isSymlink -> {
                        tarEntry.linkName = entry.linkTarget ?: ""
                    }
                    else -> {
                        tarEntry.size = entry.content?.size?.toLong() ?: 0L
                    }
                }
                out.putArchiveEntry(tarEntry)
                if (!entry.isDirectory && !entry.isSymlink && entry.content != null) {
                    out.write(entry.content)
                }
                out.closeArchiveEntry()
            }
        }
        return bos.toByteArray()
    }

    // ------------------------------------------------------------------
    // Extract / List
    // ------------------------------------------------------------------

    private fun listArchive(ctx: CommandContext, options: Options, files: List<String>): ExecResult {
        val bytes = try {
            readArchiveBytes(ctx, options.file)
        } catch (e: Exception) {
            return ExecResult(stderr = "tar: ${options.file ?: "-"}: Cannot open: No such file or directory\n", exitCode = 2)
        }

        val out = StringBuilder()
        try {
            openTarInput(bytes, options.gzip).use { tis ->
                var entry: TarArchiveEntry? = tis.nextEntry
                while (entry != null) {
                    if (files.isEmpty() || matchesName(entry.name, files)) {
                        val displayName = entry.name.removeSuffix("/")
                        if (options.verbose) {
                            out.append(formatVerboseLine(entry)).append("\n")
                        } else {
                            out.append(displayName).append("\n")
                        }
                    }
                    entry = tis.nextEntry
                }
            }
        } catch (e: Exception) {
            return ExecResult(stderr = "tar: ${e.message ?: "unknown error"}\n", exitCode = 2)
        }

        return ExecResult(stdout = out.toString(), stderr = "", exitCode = 0)
    }

    private fun matchesName(entryName: String, files: List<String>): Boolean {
        val name = entryName.removeSuffix("/")
        for (f in files) {
            val fn = f.removeSuffix("/")
            if (name == fn || name.startsWith("$fn/")) return true
        }
        return false
    }

    private fun formatVerboseLine(entry: TarArchiveEntry): String {
        val mode = formatMode(entry)
        val size = entry.size.toString().padStart(8, ' ')
        val date = formatDate(entry.modTime)
        val name = entry.name.removeSuffix("/")
        return if (entry.isSymbolicLink) {
            "$mode $size $date $name -> ${entry.linkName}"
        } else {
            "$mode $size $date $name"
        }
    }

    private fun formatMode(entry: TarArchiveEntry): String {
        val typeChar = when {
            entry.isDirectory -> 'd'
            entry.isSymbolicLink -> 'l'
            else -> '-'
        }
        return typeChar + chmodFormat(entry.mode)
    }

    private fun chmodFormat(mode: Int): String {
        val perm = mode and 0xFFF
        val sb = StringBuilder(9)
        val rwx = arrayOf("---", "--x", "-w-", "-wx", "r--", "r-x", "rw-", "rwx")
        sb.append(rwx[(perm shr 6) and 0x7])
        sb.append(rwx[(perm shr 3) and 0x7])
        sb.append(rwx[perm and 0x7])
        return sb.toString()
    }

    private fun formatDate(date: java.util.Date): String {
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val cal = java.util.Calendar.getInstance()
        cal.time = date
        val month = months[cal.get(java.util.Calendar.MONTH)]
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, ' ')
        val hours = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mins = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        return "$month $day $hours:$mins"
    }

    private fun extractArchive(ctx: CommandContext, options: Options, files: List<String>): ExecResult {
        val bytes = try {
            readArchiveBytes(ctx, options.file)
        } catch (e: Exception) {
            return ExecResult(stderr = "tar: ${options.file ?: "-"}: Cannot open: No such file or directory\n", exitCode = 2)
        }

        val workDir = options.directory?.let { ctx.fs.resolvePath(ctx.cwd, it) } ?: ctx.cwd
        if (options.directory != null && !options.toStdout) {
            try { ctx.fs.mkdir(workDir, com.justbash.fs.MkdirOptions(recursive = true)) } catch (_: Exception) {}
        }

        val errors = ArrayList<String>()
        val verboseOut = StringBuilder()
        val stdoutContent = StringBuilder()
        val stdoutBytes = ByteArrayOutputStream()

        try {
            openTarInput(bytes, options.gzip).use { tis ->
                var entry: TarArchiveEntry? = tis.nextEntry
                while (entry != null) {
                    val safeName = sanitize(entry.name)
                    if (safeName != null) {
                        val name = safeName
                        val displayName = name.removeSuffix("/")

                        if (files.isEmpty() || matchesName(name, files)) {
                            when {
                                entry.isDirectory -> {
                                    if (!options.toStdout) {
                                        try {
                                            ctx.fs.mkdir(ctx.fs.resolvePath(workDir, name), com.justbash.fs.MkdirOptions(recursive = true))
                                        } catch (_: Exception) {}
                                    }
                                    if (options.verbose) verboseOut.append(displayName).append("\n")
                                }
                                entry.isSymbolicLink -> {
                                    if (options.toStdout) {
                                        if (options.verbose) verboseOut.append(displayName).append("\n")
                                    } else {
                                        try {
                                            val target = entry.linkName
                                            if (target.startsWith("/") || hasParentTraversal(target)) {
                                                errors.add("tar: $displayName: unsafe symlink target")
                                            } else {
                                                ctx.fs.symlink(target, ctx.fs.resolvePath(workDir, name))
                                            }
                                        } catch (e: Exception) {
                                            errors.add("tar: $displayName: ${e.message ?: "unknown error"}")
                                        }
                                        if (options.verbose) verboseOut.append(displayName).append("\n")
                                    }
                                }
                                else -> {
                                    // Regular file
                                    val content = readEntryContent(tis, entry)
                                    if (options.toStdout) {
                                        stdoutBytes.write(content)
                                        if (options.verbose) verboseOut.append(displayName).append("\n")
                                    } else {
                                        try {
                                            val targetPath = ctx.fs.resolvePath(workDir, name)
                                            ctx.fs.writeFile(targetPath, content)
                                            if (options.verbose) verboseOut.append(displayName).append("\n")
                                        } catch (e: Exception) {
                                            errors.add("tar: $displayName: ${e.message ?: "unknown error"}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    entry = tis.nextEntry
                }
            }
        } catch (e: Exception) {
            return ExecResult(stderr = "tar: ${e.message ?: "unknown error"}\n", exitCode = 2)
        }

        val finalStdout = if (options.toStdout) latin1FromBytes(stdoutBytes.toByteArray()) else stdoutContent.toString()
        val stderr = StringBuilder()
        stderr.append(verboseOut)
        if (errors.isNotEmpty()) stderr.append(errors.joinToString("\n")).append("\n")

        return ExecResult(stdout = finalStdout, stderr = stderr.toString(), exitCode = if (errors.isNotEmpty()) 2 else 0)
    }

    private fun readEntryContent(tis: TarArchiveInputStream, entry: TarArchiveEntry): ByteArray {
        val size = entry.size.toInt()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var read: Int
        var total = 0
        while (true) {
            read = tis.read(buf)
            if (read == -1) break
            bos.write(buf, 0, read)
            total += read
            if (size > 0 && total >= size) break
        }
        if (size == 0 && bos.size() == 0) {
            // zero-length file
            return ByteArray(0)
        }
        return bos.toByteArray()
    }

    private fun sanitize(path: String): String? {
        var p = path.replace(Regex("^/+"), "")
        if (p.isEmpty()) return null
        // Tar entries are read from TarArchiveInputStream which resolves them to
        // names without ".."; guard against any residual traversal anyway.
        if (hasParentTraversal(p)) return null
        return p
    }

    private fun hasParentTraversal(path: String): Boolean =
        path.split("/").any { it == ".." }

    // ------------------------------------------------------------------
    // Entry spec (creation)
    // ------------------------------------------------------------------

    private data class EntrySpec(
        val name: String,
        val content: ByteArray? = null,
        val mode: Int = PathUtils.DEFAULT_FILE_MODE,
        val mtime: Instant = Instant.now(),
        val isDirectory: Boolean = false,
        val isSymlink: Boolean = false,
        val linkTarget: String? = null,
    )

    // ------------------------------------------------------------------
    // Execute
    // ------------------------------------------------------------------

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) return showHelp(
            "tar", "manipulate tape archives",
            "tar [options] [file...]",
            listOf(
                "-c, --create           create a new archive",
                "-x, --extract          extract files from an archive",
                "-t, --list             list contents of an archive",
                "-f, --file=ARCHIVE     use archive file ARCHIVE",
                "-z, --gzip             filter archive through gzip",
                "-v, --verbose          verbosely list files processed",
                "-O, --to-stdout        extract files to standard output",
                "-C, --directory=DIR    change to directory DIR before performing operations",
                "    --help             display this help and exit",
            ),
        )

        val parsed = try {
            parseOptions(args)
        } catch (e: ParseError) {
            return e.result
        }

        val (options, files) = parsed

        val opCount = listOf(options.create, options.extract, options.list).count { it }
        if (opCount == 0) {
            return ExecResult(stderr = "tar: You must specify one of -c, -x, or -t\n", exitCode = 2)
        }
        if (opCount > 1) {
            return ExecResult(stderr = "tar: You may not specify more than one of -c, -x, or -t\n", exitCode = 2)
        }

        return when {
            options.create -> createArchive(ctx, options, files)
            options.extract -> extractArchive(ctx, options, files)
            else -> listArchive(ctx, options, files)
        }
    }
}
