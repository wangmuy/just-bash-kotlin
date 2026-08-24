package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * gzip / gunzip / zcat commands backed by the JDK's built-in GZIP streams.
 *
 * Port of just-bash `gzip/gzip.ts` (subset: compress/decompress to files or
 * stdout, and piped stdin handling).
 */
object GzipCommand : Command {
    override val name = "gzip"

    enum class Mode { GZIP, GUNZIP, ZCAT }

    private val defs = mapOf(
        "stdout" to Args.Def(short = "c", long = "stdout", type = "boolean"),
        "toStdout" to Args.Def(long = "to-stdout", type = "boolean"),
        "decompress" to Args.Def(short = "d", long = "decompress", type = "boolean"),
        "uncompress" to Args.Def(long = "uncompress", type = "boolean"),
        "force" to Args.Def(short = "f", long = "force", type = "boolean"),
        "keep" to Args.Def(short = "k", long = "keep", type = "boolean"),
        "quiet" to Args.Def(short = "q", long = "quiet", type = "boolean"),
        "verbose" to Args.Def(short = "v", long = "verbose", type = "boolean"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult =
        executeAs(Mode.GZIP, args, ctx)

    suspend fun executeAs(mode: Mode, args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) return helpFor(mode)

        val parsed = parseArgs(nameFor(mode), args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val flags = parsed.flags
        val files = if (parsed.positional.isEmpty()) listOf("-") else parsed.positional

        val decompress = mode == Mode.GUNZIP || mode == Mode.ZCAT ||
            flags.bool("decompress") || flags.bool("uncompress")
        val toStdout = mode == Mode.ZCAT || flags.bool("stdout") || flags.bool("toStdout")

        var out = ""
        var err = ""
        var exit = 0
        for (file in files) {
            val r = processFile(ctx, file, flags, nameFor(mode), decompress, toStdout)
            out += r.stdout
            err += r.stderr
            if (r.exitCode != 0) exit = r.exitCode
        }

        // stdoutKind marks binary (compressed) output so the pipeline layer
        // preserves raw bytes rather than UTF-8 re-encoding them.
        return ExecResult(stdout = out, stderr = err, exitCode = exit, stdoutKind = "binary")
    }

    private fun nameFor(mode: Mode): String = when (mode) {
        Mode.GZIP -> "gzip"
        Mode.GUNZIP -> "gunzip"
        Mode.ZCAT -> "zcat"
    }

    private fun helpFor(mode: Mode): ExecResult = when (mode) {
        Mode.GZIP -> showHelp(
            "gzip", "compress or expand files",
            "gzip [OPTION]... [FILE]...",
            listOf(
                "-c, --stdout      write to standard output, keep original files",
                "-d, --decompress  decompress",
                "-f, --force       force overwrite of output file",
                "-k, --keep        keep (don't delete) input files",
                "-q, --quiet       suppress all warnings",
                "-v, --verbose     verbose mode",
                "    --help        display this help and exit",
            ),
        )
        Mode.GUNZIP -> showHelp(
            "gunzip", "decompress files",
            "gunzip [OPTION]... [FILE]...",
            listOf(
                "-c, --stdout      write to standard output, keep original files",
                "-f, --force       force overwrite of output file",
                "-k, --keep        keep (don't delete) input files",
                "-q, --quiet       suppress all warnings",
                "-v, --verbose     verbose mode",
                "    --help        display this help and exit",
            ),
        )
        Mode.ZCAT -> showHelp(
            "zcat", "decompress files to stdout",
            "zcat [OPTION]... [FILE]...",
            listOf(
                "-f, --force       force; read compressed data even from a terminal",
                "-q, --quiet       suppress all warnings",
                "-v, --verbose     verbose mode",
                "    --help        display this help and exit",
            ),
        )
    }

    private fun processFile(
        ctx: CommandContext,
        file: String,
        flags: Args.Flags,
        cmdName: String,
        decompress: Boolean,
        toStdout: Boolean,
    ): ExecResult {
        // --- stdin ---
        if (file == "-" || file.isEmpty()) {
            val input = ctx.stdin
            return if (decompress) {
                if (!isGzip(input)) {
                    if (flags.bool("quiet")) return ExecResult(exitCode = 1)
                    return ExecResult(stderr = "$cmdName: stdin: not in gzip format\n", exitCode = 1)
                }
                try {
                    val decoded = gunzip(input)
                    ExecResult(stdout = latin1FromBytes(decoded), exitCode = 0, stdoutKind = "binary")
                } catch (e: Exception) {
                    ExecResult(stderr = "$cmdName: stdin: ${e.message ?: "unknown error"}\n", exitCode = 1)
                }
            } else {
                try {
                    val encoded = gzip(input)
                    ExecResult(stdout = latin1FromBytes(encoded), exitCode = 0, stdoutKind = "binary")
                } catch (e: Exception) {
                    ExecResult(stderr = "$cmdName: stdin: ${e.message ?: "unknown error"}\n", exitCode = 1)
                }
            }
        }

        // --- file ---
        val inputPath = ctx.fs.resolvePath(ctx.cwd, file)
        if (!ctx.fs.exists(inputPath)) {
            return ExecResult(stderr = "$cmdName: $file: No such file or directory\n", exitCode = 1)
        }
        val stat = try { ctx.fs.stat(inputPath) } catch (e: Exception) {
            return ExecResult(stderr = "$cmdName: $file: No such file or directory\n", exitCode = 1)
        }
        if (stat.isDirectory) {
            if (flags.bool("quiet")) return ExecResult(exitCode = 1)
            return ExecResult(stderr = "$cmdName: $file: is a directory -- ignored\n", exitCode = 1)
        }

        val input = ctx.fs.readFileBuffer(inputPath)

        if (decompress) {
            if (!file.endsWith(".gz")) {
                if (flags.bool("quiet")) return ExecResult(exitCode = 1)
                return ExecResult(stderr = "$cmdName: $file: unknown suffix -- ignored\n", exitCode = 1)
            }
            if (!isGzip(input)) {
                if (flags.bool("quiet")) return ExecResult(exitCode = 1)
                return ExecResult(stderr = "$cmdName: $file: not in gzip format\n", exitCode = 1)
            }

            val decoded = try {
                gunzip(input)
            } catch (e: Exception) {
                return ExecResult(stderr = "$cmdName: $file: ${e.message ?: "unknown error"}\n", exitCode = 1)
            }

            if (toStdout) {
                return ExecResult(stdout = latin1FromBytes(decoded), exitCode = 0, stdoutKind = "binary")
            }

            val outputPath = inputPath.removeSuffix(".gz")
            if (!flags.bool("force") && ctx.fs.exists(outputPath)) {
                return ExecResult(stderr = "$cmdName: $outputPath already exists; not overwritten\n", exitCode = 1)
            }

            ctx.fs.writeFile(outputPath, decoded)
            if (!flags.bool("keep")) ctx.fs.rm(inputPath)

            if (flags.bool("verbose")) {
                val ratio = if (input.isNotEmpty()) ((1 - input.size.toDouble() / decoded.size) * 100.0) else 0.0
                return ExecResult(stderr = "$file:\t${"%.1f".format(ratio)}% -- replaced with ${outputPath.substringAfterLast("/")}\n", exitCode = 0)
            }
            return ExecResult(exitCode = 0)
        }

        // --- compression ---
        if (file.endsWith(".gz")) {
            if (flags.bool("quiet")) return ExecResult(exitCode = 1)
            return ExecResult(stderr = "$cmdName: $file already has .gz suffix -- unchanged\n", exitCode = 1)
        }

        val encoded = try {
            gzip(input)
        } catch (e: Exception) {
            return ExecResult(stderr = "$cmdName: $file: ${e.message ?: "unknown error"}\n", exitCode = 1)
        }

        if (toStdout) {
            return ExecResult(stdout = latin1FromBytes(encoded), exitCode = 0, stdoutKind = "binary")
        }

        val outputPath = "$inputPath.gz"
        if (!flags.bool("force") && ctx.fs.exists(outputPath)) {
            return ExecResult(stderr = "$cmdName: $outputPath already exists; not overwritten\n", exitCode = 1)
        }

        ctx.fs.writeFile(outputPath, encoded)
        if (!flags.bool("keep")) ctx.fs.rm(inputPath)

        if (flags.bool("verbose")) {
            val ratio = if (input.isNotEmpty()) ((1 - encoded.size.toDouble() / input.size) * 100.0) else 0.0
            return ExecResult(stderr = "$file:\t${"%.1f".format(ratio)}% -- replaced with ${outputPath.substringAfterLast("/")}\n", exitCode = 0)
        }
        return ExecResult(exitCode = 0)
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0x1F && bytes[1].toInt() and 0xFF == 0x8B

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private fun gunzip(input: ByteArray): ByteArray {
        val gis = GZIPInputStream(ByteArrayInputStream(input))
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n: Int
        while (true) {
            n = gis.read(buf)
            if (n == -1) break
            bos.write(buf, 0, n)
        }
        gis.close()
        return bos.toByteArray()
    }
}

object GunzipCommand : Command {
    override val name = "gunzip"
    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult =
        GzipCommand.executeAs(GzipCommand.Mode.GUNZIP, args, ctx)
}

object ZcatCommand : Command {
    override val name = "zcat"
    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult =
        GzipCommand.executeAs(GzipCommand.Mode.ZCAT, args, ctx)
}
