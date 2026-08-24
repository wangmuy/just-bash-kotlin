package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult

/**
 * Port of just-bash `head/head-tail-shared.ts`, `head/head.ts`, and
 * `tail/tail.ts`.
 */
internal object HeadTailShared {

    data class Options(
        var lines: Int = 10,
        var bytes: Int? = null,
        var quiet: Boolean = false,
        var verbose: Boolean = false,
        var fromLine: Boolean = false,
        val files: MutableList<String> = ArrayList(),
    )

    sealed class ParseResult {
        data class Ok(val options: Options) : ParseResult()
        data class Err(val error: ExecResult) : ParseResult()
    }

    fun parse(args: List<String>, cmdName: String): ParseResult {
        var lines = 10
        var bytes: Int? = null
        var quiet = false
        var verbose = false
        var fromLine = false
        val files = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-n" && i + 1 < args.size -> {
                    val nextArg = args[++i]
                    if (cmdName == "tail" && nextArg.startsWith("+")) {
                        fromLine = true
                        lines = nextArg.substring(1).toIntOrNull() ?: 0
                    } else {
                        lines = nextArg.toIntOrNull() ?: 0
                    }
                }
                cmdName == "tail" && arg.startsWith("-n+") -> {
                    fromLine = true
                    lines = arg.substring(3).toIntOrNull() ?: 0
                }
                arg.startsWith("-n") -> {
                    lines = arg.substring(2).toIntOrNull() ?: 0
                }
                arg == "-c" && i + 1 < args.size -> {
                    bytes = args[++i].toIntOrNull()
                }
                arg.startsWith("-c") -> {
                    bytes = arg.substring(2).toIntOrNull()
                }
                arg.startsWith("--bytes=") -> {
                    bytes = arg.substring(8).toIntOrNull()
                }
                arg.startsWith("--lines=") -> {
                    lines = arg.substring(8).toIntOrNull() ?: 0
                }
                arg == "-q" || arg == "--quiet" || arg == "--silent" -> quiet = true
                arg == "-v" || arg == "--verbose" -> verbose = true
                Regex("^-\\d+$").matches(arg) -> {
                    lines = arg.substring(1).toIntOrNull() ?: 0
                }
                arg.startsWith("--") -> return ParseResult.Err(unknownOption(cmdName, arg))
                arg.startsWith("-") && arg != "-" -> return ParseResult.Err(unknownOption(cmdName, arg))
                else -> files.add(arg)
            }
            i++
        }

        if (bytes != null && (bytes!! < 0)) {
            return ParseResult.Err(ExecResult(stdout = "", stderr = "$cmdName: invalid number of bytes\n", exitCode = 1))
        }
        if (lines < 0) {
            return ParseResult.Err(ExecResult(stdout = "", stderr = "$cmdName: invalid number of lines\n", exitCode = 1))
        }

        return ParseResult.Ok(Options(lines, bytes, quiet, verbose, fromLine, files))
    }

    fun process(
        ctx: CommandContext,
        options: Options,
        cmdName: String,
        contentProcessor: (String) -> String,
    ): ExecResult {
        val quiet = options.quiet
        val verbose = options.verbose
        val files = options.files

        if (files.isEmpty()) {
            return ExecResult(stdout = contentProcessor(latin1FromBytes(ctx.stdin)), stderr = "", exitCode = 0)
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode = 0

        val showHeaders = verbose || (!quiet && files.size > 1)

        var filesProcessed = 0
        for (file in files) {
            try {
                val filePath = ctx.fs.resolvePath(ctx.cwd, file)
                ctx.fs.stat(filePath)
                val content = latin1FromBytes(ctx.fs.readFileBuffer(filePath))

                if (showHeaders) {
                    if (filesProcessed > 0) stdout.append("\n")
                    stdout.append("==> $file <==\n")
                }
                stdout.append(contentProcessor(content))
                filesProcessed++
            } catch (_: Exception) {
                stderr.append("$cmdName: $file: No such file or directory\n")
                exitCode = 1
            }
        }

        return ExecResult(stdout = stdout.toString(), stderr = stderr.toString(), exitCode = exitCode)
    }

    fun getHead(content: String, lines: Int, bytes: Int?): String {
        if (bytes != null) return content.substring(0, bytes.coerceAtMost(content.length))
        if (lines == 0) return ""

        var pos = 0
        var lineCount = 0
        val len = content.length

        while (pos < len && lineCount < lines) {
            val nextNewline = content.indexOf('\n', pos)
            if (nextNewline == -1) return content
            lineCount++
            pos = nextNewline + 1
        }
        return if (pos > 0) content.substring(0, pos) else ""
    }

    fun getTail(content: String, lines: Int, bytes: Int?, fromLine: Boolean): String {
        if (bytes != null) {
            if (bytes == 0) return ""
            val start = if (bytes >= content.length) 0 else content.length - bytes
            return content.substring(start)
        }

        val len = content.length
        if (len == 0) return ""

        if (fromLine) {
            var pos = 0
            var lineCount = 1
            while (pos < len && lineCount < lines) {
                val nextNewline = content.indexOf('\n', pos)
                if (nextNewline == -1) return ""
                lineCount++
                pos = nextNewline + 1
            }
            return content.substring(pos)
        }

        if (lines == 0) return ""

        var pos = len - 1
        if (content[pos] == '\n') pos--

        var lineCount = 0
        while (pos >= 0 && lineCount < lines) {
            if (content[pos] == '\n') {
                lineCount++
                if (lineCount == lines) {
                    pos++
                    break
                }
            }
            pos--
        }

        if (pos < 0) pos = 0
        return content.substring(pos)
    }
}

object HeadCommand : Command {
    override val name = "head"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "head", "output the first part of files",
                "head [OPTION]... [FILE]...",
                listOf(
                    "-c, --bytes=NUM    print the first NUM bytes",
                    "-n, --lines=NUM    print the first NUM lines (default 10)",
                    "-q, --quiet        never print headers giving file names",
                    "-v, --verbose      always print headers giving file names",
                    "    --help         display this help and exit",
                ),
            )
        }

        val parsed = HeadTailShared.parse(args, "head")
        if (parsed is HeadTailShared.ParseResult.Err) return parsed.error
        parsed as HeadTailShared.ParseResult.Ok
        val options = parsed.options

        return HeadTailShared.process(ctx, options, "head") { content ->
            HeadTailShared.getHead(content, options.lines, options.bytes)
        }
    }
}

object TailCommand : Command {
    override val name = "tail"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "tail", "output the last part of files",
                "tail [OPTION]... [FILE]...",
                listOf(
                    "-c, --bytes=NUM    print the last NUM bytes",
                    "-n, --lines=NUM    print the last NUM lines (default 10)",
                    "-n +NUM            print starting from line NUM",
                    "-q, --quiet        never print headers giving file names",
                    "-v, --verbose      always print headers giving file names",
                    "    --help         display this help and exit",
                ),
            )
        }

        val parsed = HeadTailShared.parse(args, "tail")
        if (parsed is HeadTailShared.ParseResult.Err) return parsed.error
        parsed as HeadTailShared.ParseResult.Ok
        val options = parsed.options

        return HeadTailShared.process(ctx, options, "tail") { content ->
            HeadTailShared.getTail(content, options.lines, options.bytes, options.fromLine)
        }
    }
}
