package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

// ============================================================================
// 1. sleep command
// ============================================================================

object SleepCommand : Command {
    override val name = "sleep"

    private val DURATION_RE = Regex("""^(-?\d+\.?\d*)([smhd])?$""")

    /** Maximum sleep duration: 1 hour (prevents indefinite blocking). */
    private const val MAX_SLEEP_MS: Long = 3_600_000

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "sleep", "delay for a specified amount of time",
                "sleep NUMBER[SUFFIX]",
                listOf("    --help display this help and exit"),
                listOf(
                    "Pause for NUMBER seconds. SUFFIX may be:",
                    "  s - seconds (default)",
                    "  m - minutes",
                    "  h - hours",
                    "  d - days",
                    "",
                    "NUMBER may be a decimal number.",
                ),
            )
        }

        if (args.isEmpty()) {
            return ExecResult(
                stdout = "",
                stderr = "sleep: missing operand\n",
                exitCode = 1,
            )
        }

        var totalMs = 0L
        for (arg in args) {
            val ms = parseDuration(arg) ?: return ExecResult(
                stdout = "",
                stderr = "sleep: invalid time interval '$arg'\n",
                exitCode = 1,
            )
            totalMs += ms
        }

        if (totalMs > MAX_SLEEP_MS) {
            totalMs = MAX_SLEEP_MS
        }

        if (totalMs > 0) {
            delay(totalMs)
        }

        return ExecResult(stdout = "", stderr = "", exitCode = 0)
    }

    private fun parseDuration(arg: String): Long? {
        val match = DURATION_RE.matchEntire(arg) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val suffix = match.groupValues[2].ifEmpty { "s" }

        val ms = when (suffix) {
            "s" -> value * 1000.0
            "m" -> value * 60 * 1000.0
            "h" -> value * 60 * 60 * 1000.0
            "d" -> value * 24 * 60 * 60 * 1000.0
            else -> return null
        }

        return ms.toLong().coerceAtLeast(0)
    }
}

// ============================================================================
// 2. stat command
// ============================================================================

object StatCommand : Command {
    override val name = "stat"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "stat", "display file or file system status",
                "stat [OPTION]... FILE...",
                listOf(
                    "-c FORMAT   use the specified FORMAT instead of the default",
                    "    --help  display this help and exit",
                ),
            )
        }

        val parsed = parseArgs(
            "stat", args,
            mapOf("format" to Args.Def(short = "c", type = "string")),
        )
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        val ok = parsed as Args.ParseOutcome.Ok
        val format = ok.flags.string("format")
        val files = ok.positional

        if (files.isEmpty()) {
            return ExecResult(
                stdout = "",
                stderr = "stat: missing operand\n",
                exitCode = 1,
            )
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var hasError = false

        for (file in files) {
            val fullPath = ctx.fs.resolvePath(ctx.cwd, file)

            try {
                val stat = ctx.fs.stat(fullPath)

                if (format != null) {
                    val modeOctal = stat.mode.toString(8)
                    val modeStr = formatMode(stat.mode, stat.isDirectory)
                    val replacements = mapOf(
                        "%n" to file,
                        "%N" to "'$file'",
                        "%s" to stat.size.toString(),
                        "%F" to if (stat.isDirectory) "directory" else "regular file",
                        "%a" to modeOctal,
                        "%A" to modeStr,
                        "%u" to "1000",
                        "%U" to "user",
                        "%g" to "1000",
                        "%G" to "group",
                    )
                    val output = Regex("%[nNsFaAuUgG]").replace(format) { mr ->
                        replacements[mr.value] ?: mr.value
                    }
                    stdout.append(output).append('\n')
                } else {
                    // Default format: "  File: ...\n  Size: ...\nAccess: ...\nModify: ...\n"
                    val modeOctal = stat.mode.toString(8).padStart(4, '0')
                    val modeStr = formatMode(stat.mode, stat.isDirectory)
                    val blocks = Math.ceil(stat.size / 512.0).toLong()
                    val identity = stat.identity ?: "0"
                    val isoTime = stat.mtime.toString() // ISO-8601 format

                    // Full format including device, inode, links, access/change times
                    stdout.append("  File: $file\n")
                    stdout.append(
                        "  Size: ${stat.size}\t\tBlocks: $blocks\t\tIO Block: 4096  " +
                        "${if (stat.isDirectory) "directory" else "regular file"}\n"
                    )
                    stdout.append("Device: 0h/0d\tInode: $identity\tLinks: 1\n")
                    stdout.append("Access: ($modeOctal/$modeStr)  Uid: (1000/  user)   Gid: (1000/  group)\n")
                    stdout.append("Access: $isoTime\n")
                    stdout.append("Modify: $isoTime\n")
                    stdout.append("Change: $isoTime\n")
                }
            } catch (e: Exception) {
                stderr.append("stat: cannot stat '$file': No such file or directory\n")
                hasError = true
            }
        }

        return ExecResult(
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            exitCode = if (hasError) 1 else 0,
        )
    }

    private fun formatMode(mode: Int, isDirectory: Boolean): String {
        val typeChar = if (isDirectory) "d" else "-"
        val perms = buildString {
            append(if ((mode and 0x400) != 0) "r" else "-")
            append(if ((mode and 0x200) != 0) "w" else "-")
            append(if ((mode and 0x100) != 0) "x" else "-")
            append(if ((mode and 0x040) != 0) "r" else "-")
            append(if ((mode and 0x020) != 0) "w" else "-")
            append(if ((mode and 0x010) != 0) "x" else "-")
            append(if ((mode and 0x004) != 0) "r" else "-")
            append(if ((mode and 0x002) != 0) "w" else "-")
            append(if ((mode and 0x001) != 0) "x" else "-")
        }
        return typeChar + perms
    }
}

// ============================================================================
// 3. date command
// ============================================================================

object DateCommand : Command {
    override val name = "date"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "date", "display the current time in the given FORMAT",
                "date [OPTION]... [+FORMAT]",
                listOf(
                    "-d, --date=STRING   display time described by STRING, not 'now'",
                    "-u, --utc           print Coordinated Universal Time (UTC)",
                    "-I, --iso-8601      output date/time in ISO 8601 format",
                    "-R, --rfc-email     output RFC 5322 date format",
                    "    --help          display this help and exit",
                ),
            )
        }

        var utc = false
        var dateStr: String? = null
        var fmt: String? = null
        var iso = false
        var rfc = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-u" || a == "--utc" -> utc = true
                a == "-d" || a == "--date" -> {
                    i++
                    dateStr = if (i < args.size) args[i] else ""
                }
                a.startsWith("--date=") -> dateStr = a.substring(7)
                a == "-I" || a == "--iso-8601" -> iso = true
                a == "-R" || a == "--rfc-email" -> rfc = true
                a.startsWith("+") -> fmt = a.substring(1)
                a.startsWith("--") -> return unknownOption("date", a)
                a.startsWith("-") -> {
                    for (c in a.substring(1)) {
                        when (c) {
                            'u' -> utc = true
                            'I' -> iso = true
                            'R' -> rfc = true
                            else -> return unknownOption("date", "-$c")
                        }
                    }
                }
            }
            i++
        }

        val zone = if (utc) ZoneId.of("UTC") else ZoneId.of("UTC")
        val date = if (dateStr != null) parseDate(dateStr) else ZonedDateTime.now(zone)

        if (date == null) {
            return ExecResult(
                stdout = "",
                stderr = "date: invalid date '$dateStr'\n",
                exitCode = 1,
            )
        }

        val zonedDate = date.withZoneSameInstant(zone)
        val ts = zonedDate.toEpochSecond()

        val out = when {
            fmt != null -> formatStrftime(fmt, ts, zone)
            iso -> formatStrftime("%Y-%m-%dT%H:%M:%S%z", ts, zone)
            rfc -> formatStrftime("%a, %d %b %Y %H:%M:%S %z", ts, zone)
            else -> formatStrftime("%a %b %e %H:%M:%S %Z %Y", ts, zone)
        }

        return ExecResult(stdout = "$out\n", stderr = "", exitCode = 0)
    }

    private fun parseDate(s: String): ZonedDateTime? {
        // @unix-timestamp
        if (s.startsWith("@")) {
            val suffix = s.substring(1)
            if (!Regex("^-?\\d+$").matches(suffix)) return null
            val seconds = suffix.toLongOrNull() ?: return null
            return try {
                ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.of("UTC"))
            } catch (_: Exception) {
                null
            }
        }
        val l = s.lowercase().trim()
        if (l == "now" || l == "today") return ZonedDateTime.now(ZoneId.of("UTC"))
        if (l == "yesterday") return ZonedDateTime.now(ZoneId.of("UTC")).minusDays(1)
        if (l == "tomorrow") return ZonedDateTime.now(ZoneId.of("UTC")).plusDays(1)
        // Try ISO parse
        return try {
            val instant = Instant.parse(s)
            ZonedDateTime.ofInstant(instant, ZoneId.of("UTC"))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Convert a strftime format string to a Java DateTimeFormatter and format
     * the given epoch-second timestamp.
     *
     * Supported specifiers:
     *   %Y %m %d %H %M %S %s %z %Z %a %A %b %B %w %j %U %W %x %X %F %T %R %r %c %D %n %t %%
     *
     * Unsupported specifiers (passed through unchanged):
     *   %p, %e, %k, %l, %y, %C, %G, %g, %V, %u, etc.
     */
    private fun formatStrftime(format: String, epochSeconds: Long, zone: ZoneId): String {
        val dt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), zone)
        val sb = StringBuilder()
        var idx = 0
        while (idx < format.length) {
            val c = format[idx]
            if (c == '%' && idx + 1 < format.length) {
                val spec = format[idx + 1]
                idx += 2
                when (spec) {
                    // Year
                    'Y' -> sb.append(dt.year.toString().padStart(4, '0'))
                    'y' -> sb.append(dt.year.toString().takeLast(2).padStart(2, '0'))
                    // Month
                    'm' -> sb.append(dt.monthValue.toString().padStart(2, '0'))
                    'b', 'h' -> sb.append(dt.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH))
                    'B' -> sb.append(dt.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH))
                    // Day
                    'd' -> sb.append(dt.dayOfMonth.toString().padStart(2, '0'))
                    'e' -> sb.append(dt.dayOfMonth.toString().padStart(2, ' '))
                    // Hour
                    'H' -> sb.append(dt.hour.toString().padStart(2, '0'))
                    'I' -> {
                        val h12 = dt.hour % 12
                        sb.append(if (h12 == 0) "12" else h12.toString().padStart(2, '0'))
                    }
                    'k' -> sb.append(dt.hour.toString().padStart(2, ' '))
                    'l' -> {
                        val h12 = dt.hour % 12
                        sb.append(if (h12 == 0) "12" else h12.toString().padStart(2, ' '))
                    }
                    // Minute, Second
                    'M' -> sb.append(dt.minute.toString().padStart(2, '0'))
                    'S' -> sb.append(dt.second.toString().padStart(2, '0'))
                    // Unix timestamp
                    's' -> sb.append(epochSeconds.toString())
                    // Timezone
                    'z' -> sb.append(dt.format(DateTimeFormatter.ofPattern("xx")))
                    'Z' -> sb.append(zone.id)
                    // Weekday
                    'a' -> sb.append(dt.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH))
                    'A' -> sb.append(dt.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH))
                    'w' -> sb.append(dt.dayOfWeek.value % 7) // Sunday=0
                    'u' -> sb.append(dt.dayOfWeek.value.toString()) // Monday=1..7
                    // Day of year
                    'j' -> sb.append(dt.dayOfYear.toString().padStart(3, '0'))
                    // Week numbers (approximate)
                    'U' -> {
                        // Week number with Sunday as first day
                        val doy = dt.dayOfYear
                        val dow = dt.dayOfWeek.value % 7 // Sunday=0
                        val jan1Dow = dt.withDayOfYear(1).dayOfWeek.value % 7
                        val weekNum = (doy + jan1Dow - dow) / 7
                        sb.append(weekNum.toString().padStart(2, '0'))
                    }
                    'W' -> {
                        // Week number with Monday as first day
                        val doy = dt.dayOfYear
                        val dow = dt.dayOfWeek.value // Monday=1..7
                        val jan1Dow = dt.withDayOfYear(1).dayOfWeek.value
                        val weekNum = (doy + jan1Dow - dow) / 7
                        sb.append(weekNum.toString().padStart(2, '0'))
                    }
                    // Date formats
                    'F' -> sb.append(dt.format(DateTimeFormatter.ISO_LOCAL_DATE)) // %Y-%m-%d
                    'D' -> sb.append(dt.format(DateTimeFormatter.ofPattern("MM/dd/yy")))
                    'x' -> sb.append(dt.format(DateTimeFormatter.ofPattern("MM/dd/yy")))
                    // Time formats
                    'T' -> sb.append(dt.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                    'R' -> sb.append(dt.format(DateTimeFormatter.ofPattern("HH:mm")))
                    'X' -> sb.append(dt.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                    // 12-hour time
                    'r' -> sb.append(dt.format(DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH)))
                    // Combined
                    'c' -> sb.append(dt.format(DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy", Locale.ENGLISH)))
                    // Literals
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '%' -> sb.append('%')
                    // Pass through unrecognized specifiers
                    else -> { sb.append('%'); sb.append(spec) }
                }
            } else {
                sb.append(c)
                idx++
            }
        }
        return sb.toString()
    }
}

// ============================================================================
// 4. md5sum, sha1sum, sha256sum commands
// ============================================================================

/**
 * Shared implementation for checksum commands (md5sum, sha1sum, sha256sum).
 */
sealed class ChecksumCommand(
    override val name: String,
    private val algorithm: String,
    private val summary: String,
) : Command {

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                name, summary,
                "$name [OPTION]... [FILE]...",
                listOf(
                    "-c, --check    read checksums from FILEs and check them",
                    "    --tag      create a BSD-style checksum",
                    "    --help     display this help and exit",
                ),
            )
        }

        var check = false
        var tag = false
        val files = ArrayList<String>()

        for (arg in args) {
            when {
                arg == "-c" || arg == "--check" -> check = true
                arg == "--tag" -> tag = true
                arg == "-b" || arg == "-t" || arg == "--binary" || arg == "--text" -> { /* ignored */ }
                arg.startsWith("-") && arg != "-" -> return unknownOption(name, arg)
                else -> files.add(arg)
            }
        }

        if (files.isEmpty()) files.add("-")

        if (check) {
            return executeCheck(files, ctx)
        }

        return executeHash(files, ctx, tag)
    }

    private fun executeHash(files: List<String>, ctx: CommandContext, tag: Boolean = false): ExecResult {
        val output = StringBuilder()
        var exitCode = 0

        for (file in files) {
            val content = readBinary(file, ctx)
            if (content == null) {
                output.append("$name: $file: No such file or directory\n")
                exitCode = 1
                continue
            }
            val hash = computeHash(content)
            if (tag) {
                output.append("${algorithmNameForTag()} ($file) = $hash\n")
            } else {
                output.append("$hash  $file\n")
            }
        }

        return ExecResult(stdout = output.toString(), stderr = "", exitCode = exitCode)
    }

    /** BSD-style tag name, e.g. "MD5", "SHA1", "SHA256". */
    private fun algorithmNameForTag(): String = algorithm.replace("-", "")

    private fun executeCheck(files: List<String>, ctx: CommandContext): ExecResult {
        var failed = 0
        val output = StringBuilder()

        for (file in files) {
            val content = if (file == "-") {
                ctx.stdin.toString(Charsets.UTF_8)
            } else {
                try {
                    ctx.fs.readFile(ctx.fs.resolvePath(ctx.cwd, file))
                } catch (_: Exception) {
                    return ExecResult(
                        stdout = "",
                        stderr = "$name: $file: No such file or directory\n",
                        exitCode = 1,
                    )
                }
            }

            for (line in content.lines()) {
                val match = Regex("^([a-fA-F0-9]+)\\s+[* ]?(.+)$").find(line) ?: continue
                val (expectedHash, targetFile) = match.destructured

                val fileContent = readBinary(targetFile, ctx)
                if (fileContent == null) {
                    output.append("$targetFile: FAILED open or read\n")
                    failed++
                    continue
                }
                val actualHash = computeHash(fileContent)
                val ok = actualHash.equals(expectedHash, ignoreCase = true)
                output.append("$targetFile: ${if (ok) "OK" else "FAILED"}\n")
                if (!ok) failed++
            }
        }

        if (failed > 0) {
            output.append(
                "$name: WARNING: $failed computed checksum${if (failed > 1) "s" else ""} did NOT match\n"
            )
        }

        return ExecResult(
            stdout = output.toString(),
            stderr = "",
            exitCode = if (failed > 0) 1 else 0,
        )
    }

    private fun readBinary(file: String, ctx: CommandContext): ByteArray? {
        if (file == "-") {
            return ctx.stdin
        }
        return try {
            ctx.fs.readFileBuffer(ctx.fs.resolvePath(ctx.cwd, file))
        } catch (_: Exception) {
            null
        }
    }

    private fun computeHash(data: ByteArray): String {
        val md = MessageDigest.getInstance(algorithm)
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

object Md5sumCommand : ChecksumCommand("md5sum", "MD5", "compute MD5 message digest")
object Sha1sumCommand : ChecksumCommand("sha1sum", "SHA-1", "compute SHA1 message digest")
object Sha256sumCommand : ChecksumCommand("sha256sum", "SHA-256", "compute SHA256 message digest")