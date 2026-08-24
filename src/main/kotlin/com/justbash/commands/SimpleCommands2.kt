package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import com.justbash.fs.PathUtils

/**
 * Round-four ports of simple just-bash commands: `alias`/`unalias`, `clear`,
 * `cut`, `du`, `expand`, `fold`, `history`, `hostname`, and `nl`.
 *
 * Sources:
 *   - just-bash `src/commands/alias/alias.ts`
 *   - just-bash `src/commands/clear/clear.ts`
 *   - just-bash `src/commands/cut/cut.ts`
 *   - just-bash `src/commands/du/du.ts`
 *   - just-bash `src/commands/expand/expand.ts`
 *   - just-bash `src/commands/fold/fold.ts`
 *   - just-bash `src/commands/history/history.ts`
 *   - just-bash `src/commands/hostname/hostname.ts`
 *   - just-bash `src/commands/nl/nl.ts`
 */

// ============================================================================
// Shared helpers
// ============================================================================

/** Decode input bytes as UTF-8 text, returning empty string on failure. */
private fun decodeUtf8(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)

/** UTF-8 byte length of a string. */
private fun utf8ByteLength(s: String): Int = s.toByteArray(Charsets.UTF_8).size

/**
 * Read a file into a String (text) or return null when it is missing / not a
 * readable file. Mirrors the TS convention of `null` on read failure.
 */
private fun readFileText(ctx: CommandContext, file: String): String? = try {
    ctx.fs.readFile(ctx.fs.resolvePath(ctx.cwd, file))
} catch (_: Exception) {
    null
}

/**
 * Split [content] into lines, dropping the final empty element produced by a
 * trailing newline. Returns a pair of (lines, hasTrailingNewline).
 */
private fun splitLines(content: String): Pair<List<String>, Boolean> {
    if (content.isEmpty()) return emptyList<String>() to false
    val lines = content.split("\n")
    val hasTrailingNewline = content.endsWith("\n") && lines.last().isEmpty()
    return if (hasTrailingNewline) lines.dropLast(1) to true else lines to false
}

// ============================================================================
// 1. alias / unalias
// ============================================================================

private const val ALIAS_PREFIX = "BASH_ALIAS_"

object AliasCommand : Command {
    override val name = "alias"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "alias", "define or display aliases",
                "alias [name[=value] ...]",
                listOf("    --help display this help and exit"),
            )
        }

        // No arguments: list all aliases
        if (args.isEmpty()) {
            val sb = StringBuilder()
            for ((key, value) in ctx.env) {
                if (key.startsWith(ALIAS_PREFIX)) {
                    val aliasName = key.substring(ALIAS_PREFIX.length)
                    sb.append("alias ").append(aliasName).append("='").append(value).append("'\n")
                }
            }
            return ExecResult(stdout = sb.toString(), stderr = "", exitCode = 0)
        }

        // Skip "--" option separator (POSIX standard)
        val processArgs = if (args[0] == "--") args.subList(1, args.size) else args
        for (arg in processArgs) {
            val eqIdx = arg.indexOf('=')
            if (eqIdx == -1) {
                // Show single alias
                val key = ALIAS_PREFIX + arg
                val value = ctx.env[key]
                if (value != null) {
                    return ExecResult(
                        stdout = "alias $arg='$value'\n",
                        stderr = "",
                        exitCode = 0,
                    )
                }
                return ExecResult(
                    stdout = "",
                    stderr = "alias: $arg: not found\n",
                    exitCode = 1,
                )
            }
            // Set alias
            val name = arg.substring(0, eqIdx)
            var value = arg.substring(eqIdx + 1)
            // Remove surrounding quotes if present
            if ((value.startsWith("'") && value.endsWith("'")) ||
                (value.startsWith("\"") && value.endsWith("\""))
            ) {
                value = value.substring(1, value.length - 1)
            }
            ctx.env[ALIAS_PREFIX + name] = value
        }

        return ExecResult(stdout = "", stderr = "", exitCode = 0)
    }
}

object UnaliasCommand : Command {
    override val name = "unalias"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "unalias", "remove alias definitions",
                "unalias name [name ...]",
                listOf(
                    "-a      remove all aliases",
                    "    --help display this help and exit",
                ),
            )
        }

        if (args.isEmpty()) {
            return ExecResult(
                stdout = "",
                stderr = "unalias: usage: unalias [-a] name [name ...]\n",
                exitCode = 1,
            )
        }

        // Handle -a to remove all aliases
        if (args[0] == "-a") {
            val keys = ctx.env.keys.filter { it.startsWith(ALIAS_PREFIX) }.toList()
            for (key in keys) ctx.env.remove(key)
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        // Skip "--" option separator
        val processArgs = if (args[0] == "--") args.subList(1, args.size) else args

        var anyError = false
        val stderr = StringBuilder()
        for (aliasName in processArgs) {
            val key = ALIAS_PREFIX + aliasName
            if (ctx.env.containsKey(key)) {
                ctx.env.remove(key)
            } else {
                stderr.append("unalias: ").append(aliasName).append(": not found\n")
                anyError = true
            }
        }

        return ExecResult(stdout = "", stderr = stderr.toString(), exitCode = if (anyError) 1 else 0)
    }
}

// ============================================================================
// 2. clear
// ============================================================================

object ClearCommand : Command {
    override val name = "clear"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "clear", "clear the terminal screen",
                "clear [OPTIONS]",
                listOf("    --help display this help and exit"),
            )
        }

        System.out.print("\u001b[2J\u001b[H")
        System.out.flush()
        return ExecResult(stdout = "", stderr = "", exitCode = 0)
    }
}

// ============================================================================
// 3. cut
// ============================================================================

private data class CutRange(val start: Int, val end: Int?) // end == null means rest of line

private fun parseCutRange(spec: String): List<CutRange> {
    val ranges = ArrayList<CutRange>()
    for (part in spec.split(",")) {
        if (part.contains("-")) {
            val dash = part.indexOf('-')
            val startStr = part.substring(0, dash)
            val endStr = part.substring(dash + 1)
            val start = if (startStr.isEmpty()) 1 else startStr.toIntOrNull()
            val end = if (endStr.isEmpty()) null else endStr.toIntOrNull()
            if (start == null || start < 1 || (end != null && end < start)) {
                throw IllegalArgumentException("cut: invalid range")
            }
            ranges.add(CutRange(start, end))
        } else {
            val num = part.toIntOrNull()
            if (num == null || num < 1) {
                throw IllegalArgumentException("cut: invalid range")
            }
            ranges.add(CutRange(num, num))
        }
    }
    return ranges
}

private fun extractByRanges(items: List<String>, ranges: List<CutRange>): List<String> {
    val result = ArrayList<String>()
    val selectedIndices = HashSet<Int>()
    for (range in ranges) {
        val start = range.start - 1
        val end = range.end ?: items.size
        var i = start
        while (i < end && i < items.size) {
            if (i >= 0 && selectedIndices.add(i)) {
                result.add(items[i])
            }
            i++
        }
    }
    return result
}

object CutCommand : Command {
    override val name = "cut"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "cut", "remove sections from each line of files",
                "cut [OPTION]... [FILE]...",
                listOf(
                    "-c LIST              select only these characters",
                    "-d DELIM             use DELIM instead of TAB for field delimiter",
                    "-f LIST              select only these fields",
                    "-s, --only-delimited  do not print lines without delimiters",
                    "    --help           display this help and exit",
                ),
            )
        }

        var delimiter = "\t"
        var fieldSpec: String? = null
        var charSpec: String? = null
        var suppressNoDelim = false
        val files = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-d" -> {
                    delimiter = if (i + 1 < args.size) args[i + 1] else "\t"
                    if (i + 1 < args.size) i++
                }
                arg.startsWith("-d") && arg.length > 2 -> delimiter = arg.substring(2)
                arg == "-f" -> {
                    fieldSpec = if (i + 1 < args.size) args[++i] else null
                }
                arg.startsWith("-f") && arg.length > 2 -> fieldSpec = arg.substring(2)
                arg == "-c" -> {
                    charSpec = if (i + 1 < args.size) args[++i] else null
                }
                arg.startsWith("-c") && arg.length > 2 -> charSpec = arg.substring(2)
                arg == "-s" || arg == "--only-delimited" -> suppressNoDelim = true
                arg.startsWith("--") -> return unknownOption("cut", arg)
                arg.startsWith("-") && arg != "-" -> {
                    // Combined short options like -sf1 (only 's', 'd', 'f', 'c' allowed)
                    for (c in arg.substring(1)) {
                        if (c == 's') suppressNoDelim = true
                        else if (c != 'd' && c != 'f' && c != 'c') {
                            return unknownOption("cut", arg)
                        }
                    }
                }
                else -> files.add(arg)
            }
            i++
        }

        if (fieldSpec == null && charSpec == null) {
            return ExecResult(
                stdout = "",
                stderr = "cut: you must specify a list of bytes, characters, or fields\n",
                exitCode = 1,
            )
        }

        // Read content from files or stdin
        val content: String = if (files.isEmpty()) {
            decodeUtf8(ctx.stdin)
        } else {
            val sb = StringBuilder()
            for (file in files) {
                val text = readFileText(ctx, file)
                if (text == null) {
                    return ExecResult(
                        stdout = "",
                        stderr = "cut: $file: No such file or directory\n",
                        exitCode = 1,
                    )
                }
                sb.append(text)
            }
            sb.toString()
        }

        val ranges = try {
            parseCutRange(fieldSpec ?: charSpec ?: "1")
        } catch (e: IllegalArgumentException) {
            return ExecResult(stdout = "", stderr = "cut: invalid range\n", exitCode = 1)
        }

        val (lines, _) = splitLines(content)

        val output = StringBuilder()
        for (line in lines) {
            if (charSpec != null) {
                // Character mode: split by Unicode code point
                val cps = line.codePoints().toArray()
                val selected = ArrayList<String>()
                for (range in ranges) {
                    val start = range.start - 1
                    val end = range.end ?: cps.size
                    for (idx in start until minOf(end, cps.size)) {
                        if (idx >= 0 && idx < cps.size) {
                            selected.add(String(Character.toChars(cps[idx])))
                        }
                    }
                }
                output.append(selected.joinToString("")).append('\n')
            } else {
                // Field mode
                if (suppressNoDelim && !line.contains(delimiter)) continue
                val fields = line.split(delimiter)
                val selected = extractByRanges(fields, ranges)
                output.append(selected.joinToString(delimiter)).append('\n')
            }
        }

        return ExecResult(stdout = output.toString(), stderr = "", exitCode = 0)
    }
}

// ============================================================================
// 4. du
// ============================================================================

private fun formatDuSize(bytes: Long, humanReadable: Boolean): String {
    if (!humanReadable) {
        return (Math.ceil(bytes / 1024.0).toLong().coerceAtLeast(1)).toString()
    }
    return when {
        bytes < 1024 -> "$bytes"
        bytes < 1024L * 1024 -> String.format("%.1fK", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1fM", bytes / (1024.0 * 1024))
        else -> String.format("%.1fG", bytes / (1024.0 * 1024 * 1024))
    }
}

object DuCommand : Command {
    override val name = "du"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "du", "estimate file space usage",
                "du [OPTION]... [FILE]...",
                listOf(
                    "-a          write counts for all files, not just directories",
                    "-h          print sizes in human readable format",
                    "-s          display only a total for each argument",
                    "-c          produce a grand total",
                    "--max-depth=N  print total for directory only if N or fewer levels deep",
                    "    --help  display this help and exit",
                ),
            )
        }

        val parsed = parseArgs(
            "du", args,
            mapOf(
                "allFiles" to Args.Def(short = "a", type = "boolean"),
                "humanReadable" to Args.Def(short = "h", type = "boolean"),
                "summarize" to Args.Def(short = "s", type = "boolean"),
                "grandTotal" to Args.Def(short = "c", type = "boolean"),
                "maxDepth" to Args.Def(long = "max-depth", type = "number"),
            ),
        )
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        val ok = parsed as Args.ParseOutcome.Ok

        val allFiles = ok.flags.bool("allFiles")
        val humanReadable = ok.flags.bool("humanReadable")
        val summarize = ok.flags.bool("summarize")
        val grandTotal = ok.flags.bool("grandTotal")
        val maxDepth = ok.flags.number("maxDepth")

        val targets = ok.positional.toMutableList()
        if (targets.isEmpty()) targets.add(".")

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var grandTotalSize = 0L
        var exitCode = 0

        for (target in targets) {
            val fullPath = ctx.fs.resolvePath(ctx.cwd, target)
            try {
                val stat = ctx.fs.stat(fullPath)
                val (output, totalSize) = duCalc(
                    ctx, fullPath, target, stat.isDirectory,
                    allFiles, humanReadable, summarize, maxDepth,
                )
                stdout.append(output)
                grandTotalSize += totalSize
            } catch (_: Exception) {
                stderr.append("du: cannot access '$target': No such file or directory\n")
                exitCode = 1
            }
        }

        if (grandTotal && targets.isNotEmpty()) {
            stdout.append(formatDuSize(grandTotalSize, humanReadable)).append("\ttotal\n")
        }

        return ExecResult(stdout = stdout.toString(), stderr = stderr.toString(), exitCode = exitCode)
    }

    /**
     * Compute sizes for [targetPath]. Returns (output lines, total size).
     *
     * For a file: total is its size; output only when `-a` and not `-s`.
     * For a directory: recurse, aggregating child sizes; then emit the summary
     * line for the directory itself (respecting `-s` and `--max-depth`).
     */
    private fun duCalc(
        ctx: CommandContext,
        fullPath: String,
        displayPath: String,
        isDirectory: Boolean,
        allFiles: Boolean,
        humanReadable: Boolean,
        summarize: Boolean,
        maxDepth: Int?,
        depth: Int = 0,
    ): Pair<String, Long> {
        if (!isDirectory) {
            val size = ctx.fs.stat(fullPath).size
            val output = if (allFiles && !summarize) {
                formatDuSize(size, humanReadable) + "\t" + displayPath + "\n"
            } else {
                ""
            }
            return output to size
        }

        // Directory: recurse into children
        val children = ctx.fs.readdirWithFileTypes(fullPath)
        var total = 0L
        val output = StringBuilder()
        val dirOutput = StringBuilder()

        for (child in children.sortedBy { it.name }) {
            val childPath = PathUtils.joinPath(fullPath, child.name)
            val childDisplay = if (displayPath == ".") {
                child.name
            } else {
                "$displayPath/${child.name}"
            }
            val childStat = try {
                ctx.fs.stat(childPath)
            } catch (_: Exception) {
                null
            } ?: continue

            if (childStat.isDirectory) {
                val (childOut, childSize) = duCalc(
                    ctx, childPath, childDisplay, true,
                    allFiles, humanReadable, summarize, maxDepth, depth + 1,
                )
                total += childSize
                if (childOut.isNotEmpty()) dirOutput.append(childOut)
            } else {
                total += childStat.size
                if (allFiles && !summarize) {
                    output.append(formatDuSize(childStat.size, humanReadable)).append("\t").append(childDisplay).append("\n")
                }
            }
        }

        // Append the subdirectory output, then the summary for this directory.
        output.append(dirOutput)

        val showThisDirectory = !summarize && (maxDepth == null || depth <= maxDepth)
        if (showThisDirectory || summarize) {
            // In summarize mode, only emit the top-level target.
            if (!(summarize && depth > 0)) {
                output.append(formatDuSize(total, humanReadable)).append("\t").append(displayPath).append("\n")
            }
        }

        return output.toString() to total
    }
}

// ============================================================================
// 5. expand
// ============================================================================

private data class ExpandOptions(
    val tabStops: List<Int>,
    val leadingOnly: Boolean,
)

/** Width of a tab stop at the given (0-based) column. */
private fun getTabWidth(column: Int, tabStops: List<Int>): Int {
    if (tabStops.size == 1) {
        val tabWidth = tabStops[0]
        return tabWidth - (column % tabWidth)
    }
    for (stop in tabStops) {
        if (stop > column) return stop - column
    }
    if (tabStops.size >= 2) {
        val lastInterval = tabStops[tabStops.size - 1] - tabStops[tabStops.size - 2]
        val lastStop = tabStops[tabStops.size - 1]
        val stopsAfterLast = (column - lastStop) / lastInterval + 1
        val nextStop = lastStop + stopsAfterLast * lastInterval
        return nextStop - column
    }
    return 1
}

private fun expandLine(line: String, options: ExpandOptions): String {
    val sb = StringBuilder()
    var column = 0
    var inLeadingWhitespace = true

    for (ch in line) {
        if (ch == '\t') {
            if (options.leadingOnly && !inLeadingWhitespace) {
                sb.append(ch)
                column++
            } else {
                val spaces = getTabWidth(column, options.tabStops)
                sb.append(" ".repeat(spaces))
                column += spaces
            }
        } else {
            if (ch != ' ' && ch != '\t') inLeadingWhitespace = false
            sb.append(ch)
            column++
        }
    }
    return sb.toString()
}

private fun parseTabStops(spec: String): List<Int>? {
    val parts = spec.split(",").map { it.trim() }
    val stops = ArrayList<Int>()
    for (part in parts) {
        if (!part.matches(Regex("\\d+"))) return null
        val num = part.toIntOrNull() ?: return null
        if (num < 1) return null
        stops.add(num)
    }
    for (i in 1 until stops.size) {
        if (stops[i] <= stops[i - 1]) return null
    }
    return stops
}

private fun processExpandContent(content: String, options: ExpandOptions): String {
    if (content.isEmpty()) return ""
    val (lines, hasTrailingNewline) = splitLines(content)
    val expanded = lines.map { expandLine(it, options) }
    return expanded.joinToString("\n") + (if (hasTrailingNewline) "\n" else "")
}

object ExpandCommand : Command {
    override val name = "expand"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "expand", "convert tabs to spaces",
                "expand [OPTION]... [FILE]...",
                listOf(
                    "-t N        Use N spaces per tab (default: 8)",
                    "-t LIST     Use comma-separated list of tab stops",
                    "-i          Only convert leading tabs on each line",
                    "    --help  display this help and exit",
                ),
            )
        }

        var options = ExpandOptions(tabStops = listOf(8), leadingOnly = false)
        val files = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-t" && i + 1 < args.size -> {
                    val stops = parseTabStops(args[i + 1])
                    if (stops == null) {
                        return ExecResult(
                            stdout = "", stderr = "expand: invalid tab size: '${args[i + 1]}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(tabStops = stops)
                    i += 2
                }
                arg.startsWith("-t") && arg.length > 2 -> {
                    val stops = parseTabStops(arg.substring(2))
                    if (stops == null) {
                        return ExecResult(
                            stdout = "", stderr = "expand: invalid tab size: '${arg.substring(2)}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(tabStops = stops)
                    i++
                }
                arg == "--tabs" && i + 1 < args.size -> {
                    val stops = parseTabStops(args[i + 1])
                    if (stops == null) {
                        return ExecResult(
                            stdout = "", stderr = "expand: invalid tab size: '${args[i + 1]}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(tabStops = stops)
                    i += 2
                }
                arg.startsWith("--tabs=") -> {
                    val stops = parseTabStops(arg.substring(7))
                    if (stops == null) {
                        return ExecResult(
                            stdout = "", stderr = "expand: invalid tab size: '${arg.substring(7)}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(tabStops = stops)
                    i++
                }
                arg == "-i" || arg == "--initial" -> {
                    options = options.copy(leadingOnly = true)
                    i++
                }
                arg == "--" -> {
                    files.addAll(args.subList(i + 1, args.size))
                    break
                }
                arg.startsWith("-") && arg != "-" -> return unknownOption("expand", arg)
                else -> {
                    files.add(arg)
                    i++
                }
            }
        }

        var output = ""
        if (files.isEmpty()) {
            output = processExpandContent(decodeUtf8(ctx.stdin), options)
        } else {
            for (file in files) {
                val content = readFileText(ctx, file)
                if (content == null) {
                    return ExecResult(
                        stdout = output,
                        stderr = "expand: $file: No such file or directory\n",
                        exitCode = 1,
                    )
                }
                output += processExpandContent(content, options)
            }
        }

        return ExecResult(stdout = output, stderr = "", exitCode = 0)
    }
}

// ============================================================================
// 6. fold
// ============================================================================

private data class FoldOptions(
    val width: Int,
    val breakAtSpaces: Boolean,
    val countBytes: Boolean,
)

private fun getCharWidth(ch: Char, currentColumn: Int, countBytes: Boolean): Int {
    if (countBytes) {
        return ch.toString().toByteArray(Charsets.UTF_8).size
    }
    if (ch == '\t') return 8 - (currentColumn % 8)
    if (ch == '\b') return -1
    return 1
}

private fun foldLine(line: String, options: FoldOptions): String {
    if (line.isEmpty()) return line

    val result = ArrayList<String>()
    var currentLine = StringBuilder()
    var currentColumn = 0
    var lastSpaceIndex = -1
    var lastSpaceColumn = 0

    // Iterate by code point so wrapping never splits a surrogate pair.
    var offset = 0
    while (offset < line.length) {
        val cp = line.codePointAt(offset)
        val ch = String(Character.toChars(cp))
        val charWidth = getCharWidth(ch[0], currentColumn, options.countBytes)

        if (currentColumn + charWidth > options.width && currentLine.isNotEmpty()) {
            if (options.breakAtSpaces && lastSpaceIndex >= 0) {
                // Break at last space
                result.add(currentLine.substring(0, lastSpaceIndex + 1))
                currentLine = StringBuilder(currentLine.substring(lastSpaceIndex + 1)).append(ch)
                currentColumn = currentColumn - lastSpaceColumn - 1 + charWidth
                lastSpaceIndex = -1
                lastSpaceColumn = 0
            } else {
                // Break at current position
                result.add(currentLine.toString())
                currentLine = StringBuilder(ch)
                currentColumn = charWidth
                lastSpaceIndex = -1
                lastSpaceColumn = 0
            }
        } else {
            currentLine.append(ch)
            currentColumn += charWidth
            if (ch == " " || ch == "\t") {
                lastSpaceIndex = currentLine.length - 1
                lastSpaceColumn = currentColumn - charWidth
            }
        }
        offset += Character.charCount(cp)
    }

    if (currentLine.isNotEmpty()) result.add(currentLine.toString())
    return result.joinToString("\n")
}

private fun processFoldContent(content: String, options: FoldOptions): String {
    if (content.isEmpty()) return ""
    val (lines, hasTrailingNewline) = splitLines(content)
    val folded = lines.map { foldLine(it, options) }
    return folded.joinToString("\n") + (if (hasTrailingNewline) "\n" else "")
}

object FoldCommand : Command {
    override val name = "fold"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "fold", "wrap each input line to fit in specified width",
                "fold [OPTION]... [FILE]...",
                listOf(
                    "-w WIDTH    Use WIDTH columns instead of 80",
                    "-s          Break at spaces",
                    "-b          Count bytes rather than columns",
                    "    --help  display this help and exit",
                ),
            )
        }

        var options = FoldOptions(width = 80, breakAtSpaces = false, countBytes = false)
        val files = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-w" && i + 1 < args.size -> {
                    val width = args[i + 1].toIntOrNull()
                    if (width == null || width < 1) {
                        return ExecResult(
                            stdout = "", stderr = "fold: invalid number of columns: '${args[i + 1]}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(width = width)
                    i += 2
                }
                arg.startsWith("-w") && arg.length > 2 -> {
                    val width = arg.substring(2).toIntOrNull()
                    if (width == null || width < 1) {
                        return ExecResult(
                            stdout = "", stderr = "fold: invalid number of columns: '${arg.substring(2)}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(width = width)
                    i++
                }
                arg == "-s" -> { options = options.copy(breakAtSpaces = true); i++ }
                arg == "-b" -> { options = options.copy(countBytes = true); i++ }
                arg == "-bs" || arg == "-sb" -> {
                    options = options.copy(breakAtSpaces = true, countBytes = true)
                    i++
                }
                arg.matches(Regex("^-[sb]+w\\d+$")) -> {
                    if (arg.contains('s')) options = options.copy(breakAtSpaces = true)
                    if (arg.contains('b')) options = options.copy(countBytes = true)
                    val widthPart = arg.replace(Regex("^-[sb]+w"), "")
                    val width = widthPart.toIntOrNull()
                    if (width == null || width < 1) {
                        return ExecResult(
                            stdout = "", stderr = "fold: invalid number of columns: '$widthPart'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(width = width)
                    i++
                }
                arg.matches(Regex("^-[sb]+w$")) && i + 1 < args.size -> {
                    if (arg.contains('s')) options = options.copy(breakAtSpaces = true)
                    if (arg.contains('b')) options = options.copy(countBytes = true)
                    val width = args[i + 1].toIntOrNull()
                    if (width == null || width < 1) {
                        return ExecResult(
                            stdout = "", stderr = "fold: invalid number of columns: '${args[i + 1]}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(width = width)
                    i += 2
                }
                arg == "--" -> {
                    files.addAll(args.subList(i + 1, args.size))
                    break
                }
                arg.startsWith("-") && arg != "-" -> {
                    // Combined short flags like -sb
                    var hasUnknown = false
                    for (flag in arg.substring(1)) {
                        when (flag) {
                            's' -> options = options.copy(breakAtSpaces = true)
                            'b' -> options = options.copy(countBytes = true)
                            else -> { hasUnknown = true; break }
                        }
                    }
                    if (hasUnknown) return unknownOption("fold", arg)
                    i++
                }
                else -> {
                    files.add(arg)
                    i++
                }
            }
        }

        var output = ""
        if (files.isEmpty()) {
            output = processFoldContent(decodeUtf8(ctx.stdin), options)
        } else {
            for (file in files) {
                val content = readFileText(ctx, file)
                if (content == null) {
                    return ExecResult(
                        stdout = output,
                        stderr = "fold: $file: No such file or directory\n",
                        exitCode = 1,
                    )
                }
                output += processFoldContent(content, options)
            }
        }

        return ExecResult(stdout = output, stderr = "", exitCode = 0)
    }
}

// ============================================================================
// 7. history
// ============================================================================

private const val HISTORY_KEY = "BASH_HISTORY"

object HistoryCommand : Command {
    override val name = "history"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "history", "display command history",
                "history [n]",
                listOf(
                    "-c      clear the history list",
                    "    --help display this help and exit",
                ),
            )
        }

        // Parse JSON from environment
        val historyStr = ctx.env[HISTORY_KEY] ?: "[]"
        val history = parseHistoryJson(historyStr)

        if (args.isNotEmpty() && args[0] == "-c") {
            ctx.env[HISTORY_KEY] = "[]"
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        var count = history.size
        if (args.isNotEmpty() && args[0].matches(Regex("\\d+"))) {
            count = minOf(args[0].toInt(), history.size)
        }

        val start = history.size - count
        val sb = StringBuilder()
        for (i in start until history.size) {
            val lineNum = (i + 1).toString().padStart(5, ' ')
            sb.append(lineNum).append("  ").append(history[i]).append('\n')
        }

        return ExecResult(stdout = sb.toString(), stderr = "", exitCode = 0)
    }

    /**
     * Parse a JSON array of strings. This mirrors the TS `JSON.parse` loosely
     * enough for the history use case, tolerating a simple `["a","b"]` shape.
     * Uses a per-element regex scan over the array body so that embedded
     * escapes and commas inside quoted strings are handled correctly.
     */
    private fun parseHistoryJson(s: String): List<String> {
        val trimmed = s.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        if (!trimmed.startsWith("[")) return emptyList()
        val result = ArrayList<String>()
        // Match each string literal in the array body.
        val stringLiteral = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
        var searchFrom = 0
        while (true) {
            val m = stringLiteral.find(trimmed, searchFrom) ?: break
            var value = m.groupValues[1]
            // Unescape the common JSON escapes.
            value = value
                .replace("\\\\", "\u0000")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\u0000", "\\")
            result.add(value)
            searchFrom = m.range.last + 1
        }
        return result
    }
}

// ============================================================================
// 8. hostname
// ============================================================================

object HostnameCommand : Command {
    override val name = "hostname"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        val hostname = try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            "localhost"
        }
        return ExecResult(stdout = "$hostname\n", stderr = "", exitCode = 0)
    }
}

// ============================================================================
// 9. nl
// ============================================================================

private data class NlOptions(
    val bodyStyle: Char,       // 'a' | 't' | 'n'
    val numberFormat: String,  // "ln" | "rn" | "rz"
    val width: Int,
    val separator: String,
    val startNumber: Int,
    val increment: Int,
)

private fun formatLineNumber(num: Int, format: String, width: Int): String {
    val numStr = num.toString()
    return when (format) {
        "ln" -> numStr.padEnd(width)
        "rn" -> numStr.padStart(width)
        "rz" -> numStr.padStart(width, '0')
        else -> numStr.padEnd(width)
    }
}

private fun shouldNumber(line: String, style: Char): Boolean = when (style) {
    'a' -> true
    't' -> line.trim().isNotEmpty()
    'n' -> false
    else -> true
}

private data class NlProcessResult(val output: String, val nextNumber: Int)

private fun processNlContent(content: String, options: NlOptions, currentNumber: Int): NlProcessResult {
    if (content.isEmpty()) return NlProcessResult("", currentNumber)

    val (lines, hasTrailingNewline) = splitLines(content)
    val resultLines = ArrayList<String>()
    var lineNumber = currentNumber

    for (line in lines) {
        if (shouldNumber(line, options.bodyStyle)) {
            val formattedNum = formatLineNumber(lineNumber, options.numberFormat, options.width)
            resultLines.add("$formattedNum${options.separator}$line")
            lineNumber += options.increment
        } else {
            val padding = " ".repeat(options.width)
            resultLines.add("$padding${options.separator}$line")
        }
    }

    val output = resultLines.joinToString("\n") + (if (hasTrailingNewline) "\n" else "")
    return NlProcessResult(output, lineNumber)
}

object NlCommand : Command {
    override val name = "nl"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "nl", "number lines of files",
                "nl [OPTION]... [FILE]...",
                listOf(
                    "-b STYLE     Body numbering style: a (all), t (non-empty), n (none)",
                    "-n FORMAT    Number format: ln (left), rn (right), rz (right zeros)",
                    "-w WIDTH     Number width (default: 6)",
                    "-s SEP       Separator after number (default: TAB)",
                    "-v START     Starting line number (default: 1)",
                    "-i INCR      Line number increment (default: 1)",
                    "    --help   display this help and exit",
                ),
            )
        }

        var options = NlOptions(
            bodyStyle = 't',
            numberFormat = "rn",
            width = 6,
            separator = "\t",
            startNumber = 1,
            increment = 1,
        )
        val files = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-b" && i + 1 < args.size -> {
                    val style = args[i + 1]
                    if (style != "a" && style != "t" && style != "n") {
                        return ExecResult(
                            stdout = "", stderr = "nl: invalid body numbering style: '$style'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(bodyStyle = style[0])
                    i += 2
                }
                arg.startsWith("-b") && arg.length > 2 -> {
                    val style = arg.substring(2)
                    if (style != "a" && style != "t" && style != "n") {
                        return ExecResult(
                            stdout = "", stderr = "nl: invalid body numbering style: '$style'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(bodyStyle = style[0])
                    i++
                }
                arg == "-n" && i + 1 < args.size -> {
                    val format = args[i + 1]
                    if (format != "ln" && format != "rn" && format != "rz") {
                        return ExecResult(
                            stdout = "", stderr = "nl: invalid line numbering format: '$format'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(numberFormat = format)
                    i += 2
                }
                arg.startsWith("-n") && arg.length > 2 -> {
                    val format = arg.substring(2)
                    if (format != "ln" && format != "rn" && format != "rz") {
                        return ExecResult(
                            stdout = "", stderr = "nl: invalid line numbering format: '$format'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(numberFormat = format)
                    i++
                }
                arg == "-w" && i + 1 < args.size -> {
                    val width = args[i + 1].toIntOrNull()
                    if (width == null || width < 1) {
                        return ExecResult(
                            stdout = "", stderr = "nl: invalid line number field width: '${args[i + 1]}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(width = width)
                    i += 2
                }
                arg.startsWith("-w") && arg.length > 2 -> {
                    val width = arg.substring(2).toIntOrNull()
                    if (width == null || width < 1) {
                        return ExecResult(
                            stdout = "", stderr = "nl: invalid line number field width: '${arg.substring(2)}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(width = width)
                    i++
                }
                arg == "-s" && i + 1 < args.size -> {
                    options = options.copy(separator = args[i + 1])
                    i += 2
                }
                arg.startsWith("-s") && arg.length > 2 -> {
                    options = options.copy(separator = arg.substring(2))
                    i++
                }
                arg == "-v" && i + 1 < args.size -> {
                    val start = args[i + 1].toIntOrNull()
                    if (start == null) {
                        return ExecResult(
                            stdout = "", stderr = "nl: invalid starting line number: '${args[i + 1]}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(startNumber = start)
                    i += 2
                }
                arg.startsWith("-v") && arg.length > 2 -> {
                    val start = arg.substring(2).toIntOrNull()
                    if (start == null) {
                        return ExecResult(
                            stdout = "", stderr = "nl: invalid starting line number: '${arg.substring(2)}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(startNumber = start)
                    i++
                }
                arg == "-i" && i + 1 < args.size -> {
                    val incr = args[i + 1].toIntOrNull()
                    if (incr == null) {
                        return ExecResult(
                            stdout = "", stderr = "nl: invalid line number increment: '${args[i + 1]}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(increment = incr)
                    i += 2
                }
                arg.startsWith("-i") && arg.length > 2 -> {
                    val incr = arg.substring(2).toIntOrNull()
                    if (incr == null) {
                        return ExecResult(
                            stdout = "", stderr = "nl: invalid line number increment: '${arg.substring(2)}'\n", exitCode = 1,
                        )
                    }
                    options = options.copy(increment = incr)
                    i++
                }
                arg == "--" -> {
                    files.addAll(args.subList(i + 1, args.size))
                    break
                }
                arg.startsWith("-") && arg != "-" -> return unknownOption("nl", arg)
                else -> {
                    files.add(arg)
                    i++
                }
            }
        }

        var output = ""
        var lineNumber = options.startNumber

        if (files.isEmpty()) {
            val result = processNlContent(decodeUtf8(ctx.stdin), options, lineNumber)
            output = result.output
        } else {
            for (file in files) {
                val content = readFileText(ctx, file)
                if (content == null) {
                    return ExecResult(
                        stdout = output,
                        stderr = "nl: $file: No such file or directory\n",
                        exitCode = 1,
                    )
                }
                val result = processNlContent(content, options, lineNumber)
                output += result.output
                lineNumber = result.nextNumber
            }
        }

        return ExecResult(stdout = output, stderr = "", exitCode = 0)
    }
}
