package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult

/**
 * Port of just-bash `echo/echo.ts`.
 */
object EchoCommand : Command {
    override val name = "echo"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "echo", "display a line of text",
                "echo [OPTION]... [STRING]...",
                listOf(
                    "-n     do not output the trailing newline",
                    "-e     enable interpretation of backslash escapes",
                    "-E     disable interpretation of backslash escapes (default)",
                    "    --help     display this help and exit",
                ),
            )
        }

        var noNewline = false
        var interpretEscapes = ctx.xpgEcho
        var startIndex = 0

        while (startIndex < args.size) {
            val arg = args[startIndex]
            when (arg) {
                "-n" -> { noNewline = true; startIndex++ }
                "-e" -> { interpretEscapes = true; startIndex++ }
                "-E" -> { interpretEscapes = false; startIndex++ }
                "-ne", "-en" -> { noNewline = true; interpretEscapes = true; startIndex++ }
                else -> break
            }
        }

        var output = args.subList(startIndex, args.size).joinToString(" ")

        if (interpretEscapes) {
            val result = processEscapes(output)
            output = result.output
            if (result.stop) {
                return ExecResult(stdout = output, stderr = "", exitCode = 0)
            }
        }

        if (!noNewline) {
            output += "\n"
        }

        return ExecResult(stdout = output, stderr = "", exitCode = 0)
    }

    private data class EscapeResult(val output: String, val stop: Boolean)

    private fun processEscapes(input: String): EscapeResult {
        val result = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\') {
                if (i + 1 >= input.length) {
                    result.append('\\')
                    break
                }
                val next = input[i + 1]
                when (next) {
                    '\\' -> { result.append('\\'); i += 2 }
                    'n' -> { result.append('\n'); i += 2 }
                    't' -> { result.append('\t'); i += 2 }
                    'r' -> { result.append('\r'); i += 2 }
                    'a' -> { result.append('\u0007'); i += 2 }
                    'b' -> { result.append('\b'); i += 2 }
                    'f' -> { result.append('\u000C'); i += 2 }
                    'v' -> { result.append('\u000B'); i += 2 }
                    'e', 'E' -> { result.append('\u001B'); i += 2 }
                    'c' -> return EscapeResult(result.toString(), true)
                    '0' -> {
                        var j = i + 2
                        val octal = StringBuilder()
                        while (j < input.length && j < i + 5 && input[j] in '0'..'7') {
                            octal.append(input[j])
                            j++
                        }
                        if (octal.isEmpty()) {
                            result.append('\u0000')
                        } else {
                            val code = octal.toString().toInt(8) % 256
                            result.append(code.toChar())
                        }
                        i = j
                    }
                    'x' -> {
                        var j = i + 2
                        val hex = StringBuilder()
                        while (j < input.length && j < i + 4 && isHex(input[j])) {
                            hex.append(input[j])
                            j++
                        }
                        if (hex.isEmpty()) {
                            result.append("\\x")
                            i += 2
                        } else {
                            val code = hex.toString().toInt(16)
                            result.append(code.toChar())
                            i = j
                        }
                    }
                    'u' -> {
                        var j = i + 2
                        val hex = StringBuilder()
                        while (j < input.length && j < i + 6 && isHex(input[j])) {
                            hex.append(input[j])
                            j++
                        }
                        if (hex.isEmpty()) {
                            result.append("\\u")
                            i += 2
                        } else {
                            val code = hex.toString().toInt(16)
                            result.append(code.toChar())
                            i = j
                        }
                    }
                    'U' -> {
                        var j = i + 2
                        val hex = StringBuilder()
                        while (j < input.length && j < i + 10 && isHex(input[j])) {
                            hex.append(input[j])
                            j++
                        }
                        if (hex.isEmpty()) {
                            result.append("\\U")
                            i += 2
                        } else {
                            val code = hex.toString().toInt(16)
                            result.append(if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else "\\U$hex")
                            i = j
                        }
                    }
                    else -> { result.append('\\').append(next); i += 2 }
                }
            } else {
                result.append(c)
                i++
            }
        }
        return EscapeResult(result.toString(), false)
    }

    private fun isHex(c: Char): Boolean =
        (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
}

object PwdCommand : Command {
    override val name = "pwd"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        var usePhysical = false
        for (arg in args) {
            when (arg) {
                "-P" -> usePhysical = true
                "-L" -> usePhysical = false
                "--" -> break
                else -> if (arg.startsWith("-")) { /* ignore */ }
            }
        }

        var pwd = ctx.cwd
        if (usePhysical) {
            pwd = try {
                ctx.fs.realpath(ctx.cwd)
            } catch (_: Exception) {
                ctx.cwd
            }
        }
        return ExecResult(stdout = "$pwd\n", stderr = "", exitCode = 0)
    }
}

object TrueCommand : Command {
    override val name = "true"
    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult =
        ExecResult(stdout = "", stderr = "", exitCode = 0)
}

object FalseCommand : Command {
    override val name = "false"
    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult =
        ExecResult(stdout = "", stderr = "", exitCode = 1)
}

object BasenameCommand : Command {
    override val name = "basename"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "basename", "strip directory and suffix from filenames",
                "basename NAME [SUFFIX]\nbasename OPTION... NAME...",
                listOf(
                    "-a, --multiple   support multiple arguments",
                    "-s, --suffix=SUFFIX  remove a trailing SUFFIX",
                    "    --help       display this help and exit",
                ),
            )
        }

        var multiple = false
        var suffix = ""
        val names = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-a" || arg == "--multiple" -> multiple = true
                arg == "-s" && i + 1 < args.size -> { suffix = args[++i]; multiple = true }
                arg.startsWith("--suffix=") -> { suffix = arg.substring(9); multiple = true }
                !arg.startsWith("-") -> names.add(arg)
            }
            i++
        }

        if (names.isEmpty()) {
            return ExecResult(stdout = "", stderr = "basename: missing operand\n", exitCode = 1)
        }

        if (!multiple && names.size >= 2) {
            suffix = names.removeAt(names.size - 1)
        }

        val results = ArrayList<String>()
        for (name in names) {
            val cleanName = name.replace(Regex("/+$"), "")
            var base = cleanName.split("/").lastOrNull() ?: cleanName
            if (suffix.isNotEmpty() && base.endsWith(suffix)) {
                base = base.substring(0, base.length - suffix.length)
            }
            results.add(base)
        }

        return ExecResult(stdout = results.joinToString("\n") + "\n", stderr = "", exitCode = 0)
    }
}

object DirnameCommand : Command {
    override val name = "dirname"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "dirname", "strip last component from file name",
                "dirname [OPTION] NAME...",
                listOf("    --help       display this help and exit"),
            )
        }

        val names = args.filter { !it.startsWith("-") }
        if (names.isEmpty()) {
            return ExecResult(stdout = "", stderr = "dirname: missing operand\n", exitCode = 1)
        }

        val results = ArrayList<String>()
        for (name in names) {
            val cleanName = if (name == "/") "/" else name.replace(Regex("/+$"), "")
            val lastSlash = cleanName.lastIndexOf("/")
            when {
                lastSlash == -1 -> results.add(".")
                lastSlash == 0 -> results.add("/")
                else -> results.add(cleanName.substring(0, lastSlash))
            }
        }
        return ExecResult(stdout = results.joinToString("\n") + "\n", stderr = "", exitCode = 0)
    }
}
