package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.CommandExecOptions
import com.justbash.ExecResult

/**
 * Port of just-bash commands: bash, split, tee, time, tree, which, whoami,
 * xargs, html-to-markdown.
 */

// ---------------------------------------------------------------------------
// bash
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `bash/bash.ts`.
 *
 * Execute a bash script string or file.
 * - `bash -c 'script'` → execute script
 * - `bash script.sh` → execute file
 * - `bash` (no args) → read from stdin
 */
object BashCommand : Command {
    override val name = "bash"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "bash", "execute shell commands or scripts",
                "bash [OPTIONS] [SCRIPT_FILE] [ARGUMENTS...]",
                options = listOf(
                    "-c COMMAND  execute COMMAND string",
                    "    --help  display this help and exit",
                ),
            )
        }

        // Handle -c flag
        if (args.isNotEmpty() && args[0] == "-c" && args.size >= 2) {
            val command = args[1]
            val scriptName = args.getOrElse(2) { "bash" }
            val scriptArgs = args.drop(3)
            return executeScript(command, scriptName, scriptArgs, ctx)
        }

        // No arguments - read script from stdin
        if (args.isEmpty()) {
            val stdinText = String(ctx.stdin, Charsets.UTF_8)
            if (stdinText.isNotBlank()) {
                return executeScript(stdinText, "bash", emptyList(), ctx)
            }
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        // Read and execute script file
        val scriptPath = args[0]
        val scriptArgs = args.drop(1)

        return try {
            val fullPath = ctx.fs.resolvePath(ctx.cwd, scriptPath)
            val scriptContent = ctx.fs.readFile(fullPath)
            executeScript(scriptContent, scriptPath, scriptArgs, ctx)
        } catch (_: Exception) {
            ExecResult(stdout = "", stderr = "bash: $scriptPath: No such file or directory\n", exitCode = 127)
        }
    }

    private suspend fun executeScript(
        script: String,
        scriptName: String,
        scriptArgs: List<String>,
        ctx: CommandContext,
    ): ExecResult {
        if (ctx.exec == null) {
            return ExecResult(stdout = "", stderr = "bash: internal error: exec function not available\n", exitCode = 1)
        }

        // Build positional environment
        val positionalEnv = mutableMapOf<String, String>()
        positionalEnv["0"] = scriptName
        positionalEnv["#"] = scriptArgs.size.toString()
        positionalEnv["@"] = scriptArgs.joinToString(" ")
        positionalEnv["*"] = scriptArgs.joinToString(" ")
        for ((i, arg) in scriptArgs.withIndex()) {
            positionalEnv[(i + 1).toString()] = arg
        }

        // Skip shebang line if present
        var scriptToRun = script
        if (scriptToRun.startsWith("#!")) {
            val firstNewline = scriptToRun.indexOf('\n')
            if (firstNewline != -1) {
                scriptToRun = scriptToRun.substring(firstNewline + 1)
            }
        }

        return ctx.exec!!(
            scriptToRun,
            CommandExecOptions(
                env = positionalEnv,
                cwd = ctx.cwd,
                stdin = latin1FromBytes(ctx.stdin),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// sh
// ---------------------------------------------------------------------------

/**
 * sh is an alias for bash (POSIX shell).
 */
object ShCommand : Command {
    override val name = "sh"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "sh", "execute shell commands or scripts (POSIX shell)",
                "sh [OPTIONS] [SCRIPT_FILE] [ARGUMENTS...]",
                options = listOf(
                    "-c COMMAND  execute COMMAND string",
                    "    --help  display this help and exit",
                ),
            )
        }

        if (args.isNotEmpty() && args[0] == "-c" && args.size >= 2) {
            val command = args[1]
            val scriptName = args.getOrElse(2) { "sh" }
            val scriptArgs = args.drop(3)
            return executeScriptInternal(command, scriptName, scriptArgs, ctx)
        }

        if (args.isEmpty()) {
            val stdinText = String(ctx.stdin, Charsets.UTF_8)
            if (stdinText.isNotBlank()) {
                return executeScriptInternal(stdinText, "sh", emptyList(), ctx)
            }
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        val scriptPath = args[0]
        val scriptArgs = args.drop(1)

        return try {
            val fullPath = ctx.fs.resolvePath(ctx.cwd, scriptPath)
            val scriptContent = ctx.fs.readFile(fullPath)
            executeScriptInternal(scriptContent, scriptPath, scriptArgs, ctx)
        } catch (_: Exception) {
            ExecResult(stdout = "", stderr = "sh: $scriptPath: No such file or directory\n", exitCode = 127)
        }
    }

    private suspend fun executeScriptInternal(
        script: String,
        scriptName: String,
        scriptArgs: List<String>,
        ctx: CommandContext,
    ): ExecResult {
        if (ctx.exec == null) {
            return ExecResult(stdout = "", stderr = "sh: internal error: exec function not available\n", exitCode = 1)
        }

        val positionalEnv = mutableMapOf<String, String>()
        positionalEnv["0"] = scriptName
        positionalEnv["#"] = scriptArgs.size.toString()
        positionalEnv["@"] = scriptArgs.joinToString(" ")
        positionalEnv["*"] = scriptArgs.joinToString(" ")
        for ((i, arg) in scriptArgs.withIndex()) {
            positionalEnv[(i + 1).toString()] = arg
        }

        var scriptToRun = script
        if (scriptToRun.startsWith("#!")) {
            val firstNewline = scriptToRun.indexOf('\n')
            if (firstNewline != -1) {
                scriptToRun = scriptToRun.substring(firstNewline + 1)
            }
        }

        return ctx.exec!!(
            scriptToRun,
            CommandExecOptions(
                env = positionalEnv,
                cwd = ctx.cwd,
                stdin = latin1FromBytes(ctx.stdin),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// split
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `split/split.ts`.
 *
 * Split a file into pieces.
 * `split [FILE] [PREFIX]`
 * `-l N` → lines per file. `-b N` → bytes per file. `-a N` → suffix length.
 * `-d` → numeric suffixes.
 */
object SplitCommand : Command {
    override val name = "split"

    private val MAX_OUTPUT_FILES = 100_000

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "split", "split a file into pieces",
                "split [OPTION]... [FILE [PREFIX]]",
                options = listOf(
                    "-l N         Put N lines per output file",
                    "-b SIZE      Put SIZE bytes per output file (K, M, G suffixes)",
                    "-n CHUNKS    Split into CHUNKS equal-sized files",
                    "-d           Use numeric suffixes (00, 01, ...) instead of alphabetic",
                    "-a LENGTH    Use suffixes of length LENGTH (default: 2)",
                    "--additional-suffix=SUFFIX  Append SUFFIX to file names",
                ),
            )
        }

        var mode = "lines"
        var lines = 1000
        var bytes = 0
        var chunks = 0
        var useNumericSuffix = false
        var suffixLength = 2
        var additionalSuffix = ""
        val positional = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-l" && i + 1 < args.size -> {
                    val n = parsePositiveSafeInteger(args[i + 1])
                    if (n == null) return ExecResult(stderr = "split: invalid number of lines: '${args[i + 1]}'\n", exitCode = 1)
                    mode = "lines"; lines = n; i += 2
                }
                arg.matches(Regex("^-l\\d+$")) -> {
                    val n = parsePositiveSafeInteger(arg.substring(2))
                    if (n == null) return ExecResult(stderr = "split: invalid number of lines: '${arg.substring(2)}'\n", exitCode = 1)
                    mode = "lines"; lines = n; i++
                }
                arg == "-b" && i + 1 < args.size -> {
                    val sz = parseSize(args[i + 1])
                    if (sz == null) return ExecResult(stderr = "split: invalid number of bytes: '${args[i + 1]}'\n", exitCode = 1)
                    mode = "bytes"; bytes = sz; i += 2
                }
                arg.matches(Regex("^-b\\d+[KMGTPEZY]?B?$")) -> {
                    val sz = parseSize(arg.substring(2))
                    if (sz == null) return ExecResult(stderr = "split: invalid number of bytes: '${arg.substring(2)}'\n", exitCode = 1)
                    mode = "bytes"; bytes = sz; i++
                }
                arg == "-n" && i + 1 < args.size -> {
                    val n = parsePositiveSafeInteger(args[i + 1])
                    if (n == null) return ExecResult(stderr = "split: invalid number of chunks: '${args[i + 1]}'\n", exitCode = 1)
                    mode = "chunks"; chunks = n; i += 2
                }
                arg.matches(Regex("^-n\\d+$")) -> {
                    val n = parsePositiveSafeInteger(arg.substring(2))
                    if (n == null) return ExecResult(stderr = "split: invalid number of chunks: '${arg.substring(2)}'\n", exitCode = 1)
                    mode = "chunks"; chunks = n; i++
                }
                arg == "-a" && i + 1 < args.size -> {
                    val n = parsePositiveSafeInteger(args[i + 1])
                    if (n == null) return ExecResult(stderr = "split: invalid suffix length: '${args[i + 1]}'\n", exitCode = 1)
                    suffixLength = n; i += 2
                }
                arg.matches(Regex("^-a\\d+$")) -> {
                    val n = parsePositiveSafeInteger(arg.substring(2))
                    if (n == null) return ExecResult(stderr = "split: invalid suffix length: '${arg.substring(2)}'\n", exitCode = 1)
                    suffixLength = n; i++
                }
                arg == "-d" || arg == "--numeric-suffixes" -> {
                    useNumericSuffix = true; i++
                }
                arg.startsWith("--additional-suffix=") -> {
                    additionalSuffix = arg.substring("--additional-suffix=".length); i++
                }
                arg == "--additional-suffix" && i + 1 < args.size -> {
                    additionalSuffix = args[i + 1]; i += 2
                }
                arg == "--" -> {
                    positional.addAll(args.subList(i + 1, args.size)); break
                }
                arg.startsWith("-") && arg != "-" -> {
                    return unknownOption("split", arg)
                }
                else -> {
                    positional.add(arg); i++
                }
            }
        }

        var inputFile = "-"
        var prefix = "x"
        if (positional.isNotEmpty()) { inputFile = positional[0] }
        if (positional.size >= 2) { prefix = positional[1] }
        if (positional.size > 2) {
            return ExecResult(stderr = "split: extra operand '${positional[2]}'\n", exitCode = 1)
        }

        // Read input content as byte array
        val content: ByteArray = if (inputFile == "-") {
            ctx.stdin
        } else {
            val inputPath = ctx.fs.resolvePath(ctx.cwd, inputFile)
            try {
                ctx.fs.readFileBuffer(inputPath)
            } catch (_: Exception) {
                return ExecResult(stderr = "split: $inputFile: No such file or directory\n", exitCode = 1)
            }
        }

        if (content.isEmpty()) {
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        if (mode == "chunks" && chunks > MAX_OUTPUT_FILES) {
            return ExecResult(stderr = "split: too many output files ($chunks), limit is $MAX_OUTPUT_FILES\n", exitCode = 1)
        }

        // Split content
        val outputChunks: List<ByteArray> = when (mode) {
            "lines" -> splitByLines(content, lines)
            "bytes" -> splitByBytes(content, bytes)
            "chunks" -> splitIntoChunks(content, chunks)
            else -> splitByLines(content, lines)
        }

        if (outputChunks.size > MAX_OUTPUT_FILES) {
            return ExecResult(stderr = "split: too many output files (${outputChunks.size}), limit is $MAX_OUTPUT_FILES\n", exitCode = 1)
        }

        val capacity = suffixCapacity(useNumericSuffix, suffixLength)
        if (outputChunks.size > capacity) {
            return ExecResult(stderr = "split: output file suffixes exhausted at $capacity files\n", exitCode = 1)
        }

        for ((chunkIndex, chunk) in outputChunks.withIndex()) {
            val suffix = generateSuffix(chunkIndex, useNumericSuffix, suffixLength)
            val filename = "$prefix$suffix$additionalSuffix"
            val path = ctx.fs.resolvePath(ctx.cwd, filename)
            ctx.fs.writeFile(path, chunk)
        }

        return ExecResult(stdout = "", stderr = "", exitCode = 0)
    }

    private fun splitByLines(content: ByteArray, linesPerFile: Int): List<ByteArray> {
        val chunks = ArrayList<ByteArray>()
        var start = 0
        var lineCount = 0
        for (i in content.indices) {
            if (content[i] != 0x0A.toByte()) continue
            lineCount++
            if (lineCount == linesPerFile) {
                chunks.add(content.copyOfRange(start, i + 1))
                start = i + 1
                lineCount = 0
            }
        }
        if (start < content.size) chunks.add(content.copyOfRange(start, content.size))
        return chunks
    }

    private fun splitByBytes(content: ByteArray, bytesPerFile: Int): List<ByteArray> {
        val chunks = ArrayList<ByteArray>()
        var i = 0
        while (i < content.size) {
            val end = (i + bytesPerFile).coerceAtMost(content.size)
            chunks.add(content.copyOfRange(i, end))
            i = end
        }
        return chunks
    }

    private fun splitIntoChunks(content: ByteArray, numChunks: Int): List<ByteArray> {
        val chunks = ArrayList<ByteArray>()
        val bytesPerChunk = (content.size + numChunks - 1) / numChunks
        for (i in 0 until numChunks) {
            val start = i * bytesPerChunk
            val end = (start + bytesPerChunk).coerceAtMost(content.size)
            if (start < end) chunks.add(content.copyOfRange(start, end))
        }
        return chunks
    }

    private fun generateSuffix(index: Int, numeric: Boolean, length: Int): String {
        return if (numeric) {
            index.toString().padStart(length, '0')
        } else {
            val chars = "abcdefghijklmnopqrstuvwxyz"
            val sb = StringBuilder(length)
            var remaining = index
            for (i in length - 1 downTo 0) {
                sb.insert(0, chars[remaining % 26])
                remaining /= 26
            }
            sb.toString()
        }
    }

    private fun suffixCapacity(numeric: Boolean, length: Int): Int {
        val c = if (numeric) {
            var v = 1
            repeat(length) { v *= 10 }
            v
        } else {
            var v = 1
            repeat(length) { v *= 26 }
            v
        }
        return if (c > 0) c else Int.MAX_VALUE
    }
}

// ---------------------------------------------------------------------------
// tee
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `tee/tee.ts`.
 *
 * Read stdin and write to files and stdout.
 * `tee [FILE]...` → copy stdin to files and stdout.
 * `-a` → append mode. `-i` → ignore interrupts.
 */
object TeeCommand : Command {
    override val name = "tee"

    private val defs = mapOf(
        "append" to Args.Def(short = "a", long = "append", type = "boolean"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "tee", "read from stdin and write to stdout and files",
                "tee [OPTION]... [FILE]...",
                options = listOf(
                    "-a, --append     append to the given FILEs, do not overwrite",
                    "    --help       display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("tee", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val append = parsed.flags.bool("append")
        val files = parsed.positional
        val content = latin1FromBytes(ctx.stdin)

        var stderr = ""
        var exitCode = 0

        for (file in files) {
            try {
                val filePath = ctx.fs.resolvePath(ctx.cwd, file)
                if (append) {
                    ctx.fs.appendFile(filePath, content)
                } else {
                    ctx.fs.writeFile(filePath, content)
                }
            } catch (_: Exception) {
                stderr += "tee: $file: No such file or directory\n"
                exitCode = 1
            }
        }

        return ExecResult(stdout = content, stderr = stderr, exitCode = exitCode)
    }
}

// ---------------------------------------------------------------------------
// time
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `time/time.ts`.
 *
 * Measure command execution time.
 * `time COMMAND [ARGS]...` → execute command and print timing.
 */
object TimeCommand : Command {
    override val name = "time"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        var format = "%e %M"
        var outputFile: String? = null
        var appendMode = false
        var posixFormat = false
        var i = 0

        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-f" || arg == "--format" -> {
                    i++
                    if (i >= args.size) return ExecResult(stderr = "time: missing argument to '-f'\n", exitCode = 1)
                    format = args[i]; i++
                }
                arg == "-o" || arg == "--output" -> {
                    i++
                    if (i >= args.size) return ExecResult(stderr = "time: missing argument to '-o'\n", exitCode = 1)
                    outputFile = args[i]; i++
                }
                arg == "-a" || arg == "--append" -> { appendMode = true; i++ }
                arg == "-v" || arg == "--verbose" -> {
                    format = "Command being timed: %C\nElapsed (wall clock) time: %e seconds\nMaximum resident set size (kbytes): %M"
                    i++
                }
                arg == "-p" || arg == "--portability" -> { posixFormat = true; i++ }
                arg == "--" -> { i++; break }
                arg.startsWith("-") -> { i++ }
                else -> break
            }
        }

        val commandArgs = args.subList(i, args.size)
        if (commandArgs.isEmpty()) {
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        val displayCommand = commandArgs.joinToString(" ")
        val startTime = System.currentTimeMillis()

        var result: ExecResult
        try {
            if (ctx.exec == null) {
                return ExecResult(stderr = "time: exec not available\n", exitCode = 1)
            }
            result = ctx.exec!!(
                commandArgs[0],
                CommandExecOptions(
                    env = ctx.env.toMap(),
                    cwd = ctx.cwd,
                    stdin = latin1FromBytes(ctx.stdin),
                    args = commandArgs.drop(1),
                ),
            )
        } catch (e: Exception) {
            result = ExecResult(
                stdout = "",
                stderr = "time: ${e.message}\n",
                exitCode = 127,
            )
        }

        val endTime = System.currentTimeMillis()
        val elapsedSeconds = (endTime - startTime) / 1000.0

        val timingOutput: String = if (posixFormat) {
            "real %.2f\nuser 0.00\nsys 0.00\n".format(elapsedSeconds)
        } else {
            var out = format
                .replace("%e", "%.2f".format(elapsedSeconds))
                .replace("%E", formatElapsedTime(elapsedSeconds))
                .replace("%M", "0")
                .replace("%S", "0.00")
                .replace("%U", "0.00")
                .replace("%P", "0%")
                .replace("%C", displayCommand)
            if (!out.endsWith("\n")) out += "\n"
            out
        }

        if (outputFile != null) {
            try {
                val filePath = ctx.fs.resolvePath(ctx.cwd, outputFile)
                if (appendMode && ctx.fs.exists(filePath)) {
                    val existing = ctx.fs.readFile(filePath)
                    ctx.fs.writeFile(filePath, existing + timingOutput)
                } else {
                    ctx.fs.writeFile(filePath, timingOutput)
                }
            } catch (e: Exception) {
                return ExecResult(
                    stdout = result.stdout,
                    stderr = result.stderr + "time: cannot write to '$outputFile': ${e.message}\n",
                    exitCode = result.exitCode,
                )
            }
        } else {
            result = ExecResult(
                stdout = result.stdout,
                stderr = result.stderr + timingOutput,
                exitCode = result.exitCode,
            )
        }

        return result
    }

    private fun formatElapsedTime(seconds: Double): String {
        val hours = (seconds / 3600).toInt()
        val minutes = ((seconds % 3600) / 60).toInt()
        val secs = seconds % 60
        return if (hours > 0) {
            "%d:%02d:%05.2f".format(hours, minutes, secs)
        } else {
            "%d:%05.2f".format(minutes, secs)
        }
    }
}

// ---------------------------------------------------------------------------
// tree
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `tree/tree.ts`.
 *
 * List contents of directories in a tree-like format.
 * `tree [DIR]...` → show directory tree.
 * `-L N` → max depth. `-a` → show hidden files. `-d` → directories only.
 */
object TreeCommand : Command {
    override val name = "tree"

    private val defs = mapOf(
        "showHidden" to Args.Def(short = "a", type = "boolean"),
        "directoriesOnly" to Args.Def(short = "d", type = "boolean"),
        "fullPath" to Args.Def(short = "f", type = "boolean"),
        "maxDepth" to Args.Def(short = "L", type = "number"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "tree", "list contents of directories in a tree-like format",
                "tree [OPTION]... [DIRECTORY]...",
                options = listOf(
                    "-a          include hidden files",
                    "-d          list directories only",
                    "-L LEVEL    limit depth of directory tree",
                    "-f          print full path prefix for each file",
                    "    --help  display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("tree", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val showHidden = parsed.flags.bool("showHidden")
        val directoriesOnly = parsed.flags.bool("directoriesOnly")
        val fullPath = parsed.flags.bool("fullPath")
        val maxDepth = parsed.flags.number("maxDepth")

        val directories = parsed.positional.toMutableList()
        if (directories.isEmpty()) {
            directories.add(".")
        }

        var stdout = ""
        var stderr = ""
        var dirCount = 0
        var fileCount = 0

        for (dir in directories) {
            val result = buildTree(ctx, ctx.fs.resolvePath(ctx.cwd, dir), dir, showHidden, directoriesOnly, fullPath, maxDepth, "", 0)
            stdout += result.output
            stderr += result.stderr
            dirCount += result.dirCount
            fileCount += result.fileCount
        }

        stdout += "\n$dirCount director${if (dirCount == 1) "y" else "ies"}"
        if (!directoriesOnly) {
            stdout += ", $fileCount file${if (fileCount == 1) "" else "s"}"
        }
        stdout += "\n"

        return ExecResult(stdout = stdout, stderr = stderr, exitCode = if (stderr.isEmpty()) 0 else 1)
    }

    private data class TreeResult(
        val output: String,
        val stderr: String,
        val dirCount: Int,
        val fileCount: Int,
    )

    private suspend fun buildTree(
        ctx: CommandContext,
        fullPathActual: String,
        displayName: String,
        showHidden: Boolean,
        directoriesOnly: Boolean,
        fullPath: Boolean,
        maxDepth: Int?,
        prefix: String,
        depth: Int,
    ): TreeResult {
        try {
            val stat = ctx.fs.stat(fullPathActual)
            if (!stat.isDirectory) {
                return TreeResult("$displayName\n", "", 0, 1)
            }
        } catch (_: Exception) {
            return TreeResult("", "tree: $displayName: No such file or directory\n", 0, 0)
        }

        var output = "$displayName\n"

        if (maxDepth != null && depth >= maxDepth) {
            return TreeResult(output, "", 0, 0)
        }

        try {
            val entries = ctx.fs.readdirWithFileTypes(fullPathActual)
            val filtered = entries.filter { e ->
                if (!showHidden && e.name.startsWith(".")) return@filter false
                if (directoriesOnly && !e.isDirectory) return@filter false
                true
            }.sortedBy { it.name }

            var dirCount = 0
            var fileCount = 0

            for ((idx, entry) in filtered.withIndex()) {
                val isLast = idx == filtered.size - 1
                val connector = if (isLast) "`-- " else "|-- "
                val childPrefix = prefix + if (isLast) "    " else "|   "
                val entryPath = if (fullPathActual == "/") "/${entry.name}" else "$fullPathActual/${entry.name}"
                val entryDisplay = if (fullPath) entryPath else entry.name

                if (entry.isDirectory) {
                    dirCount++
                    output += "$prefix$connector$entryDisplay\n"
                    if (maxDepth == null || depth + 1 < maxDepth) {
                        val sub = buildTree(ctx, entryPath, entryDisplay, showHidden, directoriesOnly, fullPath, maxDepth, childPrefix, depth + 1)
                        output += sub.output
                        dirCount += sub.dirCount
                        fileCount += sub.fileCount
                    }
                } else {
                    fileCount++
                    output += "$prefix$connector$entryDisplay\n"
                }
            }

            return TreeResult(output, "", dirCount, fileCount)
        } catch (_: Exception) {
            return TreeResult(output, "tree: $displayName: Permission denied\n", 0, 0)
        }
    }
}

// ---------------------------------------------------------------------------
// which
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `which/which.ts`.
 *
 * Locate a command.
 * `which COMMAND...` → show path of command.
 * Uses PATH lookup to find command location.
 */
object WhichCommand : Command {
    override val name = "which"

    private val defs = mapOf(
        "showAll" to Args.Def(short = "a", type = "boolean"),
        "silent" to Args.Def(short = "s", type = "boolean"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "which", "locate a command",
                "which [-as] program ...",
                options = listOf(
                    "-a         List all instances of executables found",
                    "-s         No output, just return 0 if found, 1 if not",
                    "--help     display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("which", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val showAll = parsed.flags.bool("showAll")
        val silent = parsed.flags.bool("silent")
        val names = parsed.positional

        if (names.isEmpty()) {
            return ExecResult(stdout = "", stderr = "", exitCode = 1)
        }

        val pathEnv = ctx.env["PATH"] ?: "/usr/bin:/bin"
        val pathDirs = pathEnv.split(":")

        var stdout = ""
        var allFound = true

        for (name in names) {
            var found = false
            for (dir in pathDirs) {
                if (dir.isEmpty()) continue
                val fullPath = ctx.fs.resolvePath(dir, name)
                if (ctx.fs.exists(fullPath)) {
                    found = true
                    if (!silent) {
                        stdout += "$fullPath\n"
                    }
                    if (!showAll) break
                }
            }
            if (!found) allFound = false
        }

        return ExecResult(stdout = stdout, stderr = "", exitCode = if (allFound) 0 else 1)
    }
}

// ---------------------------------------------------------------------------
// whoami
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `whoami/whoami.ts`.
 *
 * Print effective user name.
 */
object WhoamiCommand : Command {
    override val name = "whoami"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        // In sandboxed environment, return the system user name
        val userName = System.getProperty("user.name") ?: "user"
        return ExecResult(stdout = "$userName\n", stderr = "", exitCode = 0)
    }
}

// ---------------------------------------------------------------------------
// xargs
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `xargs/xargs.ts`.
 *
 * Build and execute command lines from stdin.
 * `xargs [COMMAND]...` → execute command with args from stdin.
 * `-n N` → max args per command. `-I REPLSTR` → replace string.
 * `-0` → null-delimited input. `-r` → no-run-if-empty. `-t` → verbose.
 */
object XargsCommand : Command {
    override val name = "xargs"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "xargs", "build and execute command lines from standard input",
                "xargs [OPTION]... [COMMAND [INITIAL-ARGS]]",
                options = listOf(
                    "-I REPLACE   replace occurrences of REPLACE with input",
                    "-d DELIM     use DELIM as input delimiter",
                    "-n NUM       use at most NUM arguments per command line",
                    "-P NUM       run at most NUM processes at a time",
                    "-0, --null   items are separated by null, not whitespace",
                    "-t, --verbose  print commands before executing",
                    "-r, --no-run-if-empty  do not run command if input is empty",
                    "    --help   display this help and exit",
                ),
            )
        }

        var replaceStr: String? = null
        var delimiter: String? = null
        var maxArgs: Int? = null
        var nullSeparator = false
        var verbose = false
        var noRunIfEmpty = false
        var commandStart = 0

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-I" && i + 1 < args.size -> {
                    replaceStr = args[++i]; commandStart = i + 1
                }
                arg == "-d" && i + 1 < args.size -> {
                    val delimArg = args[++i]
                    delimiter = delimArg
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\r", "\r")
                        .replace("\\0", "\u0000")
                        .replace("\\\\", "\\")
                    commandStart = i + 1
                }
                arg == "-n" && i + 1 < args.size -> {
                    val v = args[++i].toIntOrNull()
                    if (v == null || v < 1) return ExecResult(stderr = "xargs: invalid number for -n: '${args[i]}'\n", exitCode = 1)
                    maxArgs = v; commandStart = i + 1
                }
                arg == "-P" && i + 1 < args.size -> {
                    // Accepted for compatibility; execution is serialized.
                    val v = args[++i].toIntOrNull()
                    if (v == null || v < 0) return ExecResult(stderr = "xargs: invalid number for -P: '${args[i]}'\n", exitCode = 1)
                    commandStart = i + 1
                }
                arg == "-0" || arg == "--null" -> { nullSeparator = true; commandStart = i + 1 }
                arg == "-t" || arg == "--verbose" -> { verbose = true; commandStart = i + 1 }
                arg == "-r" || arg == "--no-run-if-empty" -> { noRunIfEmpty = true; commandStart = i + 1 }
                arg.startsWith("--") -> return unknownOption("xargs", arg)
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.substring(1)) {
                        if (c !in "0tr") return unknownOption("xargs", "-$c")
                    }
                    if ('0' in arg) nullSeparator = true
                    if ('t' in arg) verbose = true
                    if ('r' in arg) noRunIfEmpty = true
                    commandStart = i + 1
                }
                else -> { commandStart = i; break }
            }
            i++
        }

        val command = args.subList(commandStart, args.size).toMutableList()
        if (command.isEmpty()) {
            command.add("echo")
        }

        val stdinText = String(ctx.stdin, Charsets.UTF_8)

        val items: List<String> = when {
            nullSeparator -> splitNullDelimited(stdinText)
            delimiter != null -> {
                val delim = delimiter
                val input = stdinText.trimEnd('\n')
                if (delim.isEmpty()) return ExecResult(stderr = "xargs: delimiter must not be empty\n", exitCode = 1)
                input.split(delim).filter { it.isNotEmpty() }
            }
            else -> stdinText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        }

        if (items.isEmpty()) {
            if (noRunIfEmpty) {
                return ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        val stdoutChunks = ArrayList<String>()
        val stderrChunks = ArrayList<String>()
        var exitCode = 0

        if (replaceStr != null) {
            val repl = replaceStr
            if (repl.isEmpty()) {
                return ExecResult(stderr = "xargs: replacement string must not be empty\n", exitCode = 1)
            }
            for (item in items) {
                val cmdArgs = command.map { c -> c.replace(repl, item) }
                val result = executeCommand(cmdArgs, verbose, ctx, stdoutChunks, stderrChunks)
                if (result.exitCode != 0) exitCode = result.exitCode
            }
        } else if (maxArgs != null) {
            var j = 0
            while (j < items.size) {
                val batch = items.subList(j, (j + maxArgs).coerceAtMost(items.size))
                val cmdArgs = command + batch
                val result = executeCommand(cmdArgs, verbose, ctx, stdoutChunks, stderrChunks)
                if (result.exitCode != 0) exitCode = result.exitCode
                j += maxArgs
            }
        } else {
            val cmdArgs = command + items
            val result = executeCommand(cmdArgs, verbose, ctx, stdoutChunks, stderrChunks)
            exitCode = result.exitCode
        }

        return ExecResult(
            stdout = stdoutChunks.joinToString(""),
            stderr = stderrChunks.joinToString(""),
            exitCode = exitCode,
        )
    }

    private fun splitNullDelimited(input: String): List<String> {
        return input.split("\u0000").filter { it.isNotEmpty() }
    }

    private suspend fun executeCommand(
        cmdArgs: List<String>,
        verbose: Boolean,
        ctx: CommandContext,
        stdoutChunks: MutableList<String>,
        stderrChunks: MutableList<String>,
    ): ExecResult {
        if (verbose) {
            stderrChunks.add("${cmdArgs.joinToString(" ")}\n")
        }

        if (ctx.exec == null) {
            val cmdLine = cmdArgs.joinToString(" ")
            stdoutChunks.add("$cmdLine\n")
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        return ctx.exec!!(
            cmdArgs[0],
            CommandExecOptions(
                cwd = ctx.cwd,
                args = cmdArgs.drop(1),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// html-to-markdown
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `html-to-markdown/html-to-markdown.ts`.
 *
 * Convert HTML to Markdown using regex-based approach.
 * `html-to-markdown [FILE]...` → convert HTML to markdown.
 */
object HtmlToMarkdownCommand : Command {
    override val name = "html-to-markdown"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "html-to-markdown", "convert HTML to Markdown (BashEnv extension)",
                "html-to-markdown [OPTION]... [FILE]",
                options = listOf(
                    "-b, --bullet=CHAR     bullet character for unordered lists (-, +, or *)",
                    "-c, --code=FENCE      fence style for code blocks (``` or ~~~)",
                    "-r, --hr=STRING       string for horizontal rules (default: ---)",
                    "    --heading-style=STYLE",
                    "                      heading style: 'atx' for # headings (default),",
                    "                      'setext' for underlined headings (h1/h2 only)",
                    "    --help            display this help and exit",
                ),
            )
        }

        var bullet = "-"
        var codeFence = "```"
        var hr = "---"
        var headingStyle = "atx"
        val files = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-b" || arg == "--bullet" -> {
                    bullet = args.getOrElse(++i) { "-" }
                }
                arg.startsWith("--bullet=") -> { bullet = arg.substring(9) }
                arg == "-c" || arg == "--code" -> {
                    codeFence = args.getOrElse(++i) { "```" }
                }
                arg.startsWith("--code=") -> { codeFence = arg.substring(7) }
                arg == "-r" || arg == "--hr" -> {
                    hr = args.getOrElse(++i) { "---" }
                }
                arg.startsWith("--hr=") -> { hr = arg.substring(5) }
                arg.startsWith("--heading-style=") -> {
                    val style = arg.substring(16)
                    if (style == "setext" || style == "atx") {
                        headingStyle = style
                    }
                }
                arg == "-" -> { files.add("-") }
                arg.startsWith("--") -> return unknownOption("html-to-markdown", arg)
                arg.startsWith("-") -> return unknownOption("html-to-markdown", arg)
                else -> { files.add(arg) }
            }
            i++
        }

        if (bullet !in listOf("-", "+", "*")) {
            return ExecResult(stderr = "html-to-markdown: invalid bullet marker\n", exitCode = 1)
        }
        if (codeFence != "```" && codeFence != "~~~") {
            return ExecResult(stderr = "html-to-markdown: invalid code fence\n", exitCode = 1)
        }

        // Get input
        val input: String = if (files.isEmpty() || (files.size == 1 && files[0] == "-")) {
            String(ctx.stdin, Charsets.UTF_8)
        } else {
            val filePath = ctx.fs.resolvePath(ctx.cwd, files[0])
            try {
                ctx.fs.readFile(filePath)
            } catch (_: Exception) {
                return ExecResult(stderr = "html-to-markdown: ${files[0]}: No such file or directory\n", exitCode = 1)
            }
        }

        if (input.isBlank()) {
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        try {
            val markdown = convertHtmlToMarkdown(input, bullet, codeFence, hr, headingStyle)
            return ExecResult(stdout = "$markdown\n", stderr = "", exitCode = 0)
        } catch (e: Exception) {
            return ExecResult(stderr = "html-to-markdown: conversion error: ${e.message}\n", exitCode = 1)
        }
    }

    private fun convertHtmlToMarkdown(
        html: String,
        bullet: String,
        codeFence: String,
        hr: String,
        headingStyle: String,
    ): String {
        var result = html

        // Remove script, style, footer elements
        result = result.replace(Regex("<script[^>]*>.*?</script>", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("<style[^>]*>.*?</style>", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("<footer[^>]*>.*?</footer>", RegexOption.IGNORE_CASE), "")

        // Remove HTML comments
        result = result.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        // Convert headings
        for (level in 1..6) {
            val tag = "h$level"
            if (headingStyle == "setext" && level <= 2) {
                // setext-style headings
                val underline = if (level == 1) "=".repeat(3) else "-".repeat(3)
                result = result.replace(
                    Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.IGNORE_CASE),
                ) { m ->
                    val text = stripTags(m.groupValues[1]).trim()
                    if (text.isEmpty()) "" else "$text\n$underline\n"
                }
            } else {
                // atx-style headings
                val prefix = "#".repeat(level)
                result = result.replace(
                    Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.IGNORE_CASE),
                ) { m ->
                    val text = stripTags(m.groupValues[1]).trim()
                    if (text.isEmpty()) "" else "$prefix $text\n"
                }
            }
        }

        // Convert <p> to paragraphs
        result = result.replace(Regex("</?p[^>]*>", RegexOption.IGNORE_CASE)) { "" }
        // Collapse multiple newlines caused by paragraph removal
        result = result.replace(Regex("\n{3,}"), "\n\n")

        // Convert <br> to newline
        result = result.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")

        // Convert <hr> to horizontal rule
        result = result.replace(Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE), "$hr\n")

        // Convert <b> and <strong> to **
        result = result.replace(Regex("<(/?)b>", RegexOption.IGNORE_CASE), "**")
        result = result.replace(Regex("<(/?)strong>", RegexOption.IGNORE_CASE), "**")

        // Convert <i> and <em> to _
        result = result.replace(Regex("<(/?)i>", RegexOption.IGNORE_CASE), "_")
        result = result.replace(Regex("<(/?)em>", RegexOption.IGNORE_CASE), "_")

        // Convert <a href="..."> to [...](...)
        result = result.replace(
            Regex("<a\\s+[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", RegexOption.IGNORE_CASE),
        ) { m ->
            val href = m.groupValues[1].trim()
            val text = stripTags(m.groupValues[2]).trim()
            "[$text]($href)"
        }
        result = result.replace(
            Regex("<a\\s+[^>]*href='([^']*)'[^>]*>(.*?)</a>", RegexOption.IGNORE_CASE),
        ) { m ->
            val href = m.groupValues[1].trim()
            val text = stripTags(m.groupValues[2]).trim()
            "[$text]($href)"
        }

        // Convert <img src="..." alt="..."> to ![alt](src)
        result = result.replace(
            Regex("<img\\s+[^>]*src=\"([^\"]*)\"[^>]*alt=\"([^\"]*)\"[^>]*/?>", RegexOption.IGNORE_CASE),
        ) { m ->
            val src = m.groupValues[1].trim()
            val alt = m.groupValues[2].trim()
            "![$alt]($src)"
        }
        result = result.replace(
            Regex("<img\\s+[^>]*src=\"([^\"]*)\"[^>]*/?>", RegexOption.IGNORE_CASE),
        ) { m ->
            val src = m.groupValues[1].trim()
            "![]($src)"
        }

        // Convert <code> to `inline code`
        result = result.replace(Regex("<code[^>]*>(.*?)</code>", RegexOption.IGNORE_CASE)) { m ->
            val text = stripTags(m.groupValues[1])
            "`$text`"
        }

        // Convert <pre><code> or <pre> to fenced code blocks
        result = result.replace(
            Regex("<pre><code[^>]*>(.*?)</code></pre>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
        ) { m ->
            val text = stripTags(m.groupValues[1]).trim()
            "\n$codeFence\n$text\n$codeFence\n"
        }
        result = result.replace(
            Regex("<pre[^>]*>(.*?)</pre>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
        ) { m ->
            val text = stripTags(m.groupValues[1]).trim()
            "\n$codeFence\n$text\n$codeFence\n"
        }

        // Convert <ul> and <ol> with <li>
        val liList = ArrayList<String>()
        result = result.replace(
            Regex("<li[^>]*>(.*?)</li>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
        ) { m ->
            val idx = liList.size
            liList.add(stripTags(m.groupValues[1]).trim())
            "\u0000LI$idx\u0000"
        }

        result = result.replace(
            Regex("<ul[^>]*>(.*?)</ul>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
        ) { m ->
            val content = m.groupValues[1]
            val items = Regex("\u0000LI(\\d+)\u0000").findAll(content).map { liList[it.groupValues[1].toInt()] }.toList()
            items.joinToString("\n") { "$bullet $it" } + "\n"
        }

        var olCounter = 0
        result = result.replace(
            Regex("<ol[^>]*>(.*?)</ol>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
        ) { m ->
            val content = m.groupValues[1]
            val items = Regex("\u0000LI(\\d+)\u0000").findAll(content).map { liList[it.groupValues[1].toInt()] }.toList()
            olCounter++
            items.joinToString("\n") { "${olCounter}. $it" } + "\n"
        }

        // Convert <blockquote> to >
        result = result.replace(
            Regex("<blockquote[^>]*>(.*?)</blockquote>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
        ) { m ->
            val text = stripTags(m.groupValues[1]).trim()
            text.lines().joinToString("\n") { "> $it" } + "\n"
        }

        // Strip remaining HTML tags
        result = result.replace(Regex("<[^>]*>"), "")

        // Decode common HTML entities
        result = result
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toInt(16).toChar().toString() }
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toInt().toChar().toString() }

        // Clean up: collapse blank lines
        result = result.replace(Regex("\n{3,}"), "\n\n")
        result = result.trim()

        return result
    }

    private fun stripTags(text: String): String {
        return text.replace(Regex("<[^>]*>"), "")
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/**
 * Parse a size string like "10K", "1M", "2G" into bytes.
 */
private fun parseSize(sizeStr: String): Int? {
    val match = Regex("^(\\d+)([KMGTPEZY]?)(B?)$", RegexOption.IGNORE_CASE).find(sizeStr)
        ?: return null
    val num = match.groupValues[1].toIntOrNull() ?: return null
    if (num < 1) return null
    val suffix = match.groupValues[2].uppercase()
    val multiplier = when (suffix) {
        "" -> 1
        "K" -> 1024
        "M" -> 1024 * 1024
        "G" -> 1024 * 1024 * 1024
        else -> return null
    }
    val result = num.toLong() * multiplier
    return if (result > Int.MAX_VALUE) Int.MAX_VALUE else result.toInt()
}

private fun parsePositiveSafeInteger(value: String): Int? {
    if (!Regex("^[0-9]+$").matches(value)) return null
    val v = value.toIntOrNull() ?: return null
    return if (v >= 1) v else null
}