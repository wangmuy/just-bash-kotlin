package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import io.jawk.Awk
import io.jawk.util.AwkSettings

/**
 * AWK command backed by Jawk (io.jawk:jawk), a pure-Java AWK interpreter.
 *
 * Port of just-bash `awk/awk2.ts`. Supports:
 *   awk `-F` FS, `-v` VAR=VAL, `'PROGRAM'`, FILE...
 */
object AwkCommand : Command {
    override val name = "awk"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "awk", "pattern scanning and text processing language",
                "awk [OPTIONS] 'PROGRAM' [FILE...]",
                listOf(
                    "-F FS      use FS as field separator",
                    "-v VAR=VAL assign VAL to variable VAR",
                    "    --help display this help and exit",
                ),
            )
        }

        var fs: String? = null
        val vars = LinkedHashMap<String, String>()
        var programIdx = 0

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--" -> { programIdx = i + 1; break }
                arg == "-F" && i + 1 < args.size -> { fs = args[++i]; programIdx = i + 1 }
                arg.startsWith("-F") && arg.length > 2 -> { fs = arg.substring(2); programIdx = i + 1 }
                arg == "-v" && i + 1 < args.size -> {
                    val spec = args[++i]
                    val eq = spec.indexOf('=')
                    if (eq > 0) vars[spec.substring(0, eq)] = spec.substring(eq + 1)
                    programIdx = i + 1
                }
                arg.startsWith("-v") && arg.length > 2 -> {
                    val spec = arg.substring(2)
                    val eq = spec.indexOf('=')
                    if (eq > 0) vars[spec.substring(0, eq)] = spec.substring(eq + 1)
                    programIdx = i + 1
                }
                else -> { programIdx = i; break }
            }
            i++
        }

        val remaining = args.drop(programIdx)
        if (remaining.isEmpty()) {
            return ExecResult(stderr = "awk: missing program\n", exitCode = 2)
        }

        val program = remaining.first()
        val files = remaining.drop(1)

        try {
            val settings = AwkSettings()
            if (fs != null) settings.fieldSeparator = fs
            for ((key, value) in vars) {
                settings.putVariable(key, value)
            }

            val awk = Awk(settings)

            // Read input: from files or stdin
            val input = if (files.isEmpty() || (files.size == 1 && files[0] == "-")) {
                ctx.stdin.toString(Charsets.UTF_8)
            } else {
                val sb = StringBuilder()
                for (file in files) {
                    val path = ctx.fs.resolvePath(ctx.cwd, file)
                    try {
                        sb.append(ctx.fs.readFile(path))
                    } catch (e: Exception) {
                        // awk silently treats missing files as empty
                    }
                }
                sb.toString()
            }

            val result = awk.script(program).input(input).execute()

            return ExecResult(stdout = result, stderr = "", exitCode = 0)
        } catch (e: Exception) {
            return ExecResult(stderr = "awk: ${e.message}\n", exitCode = 2)
        }
    }
}