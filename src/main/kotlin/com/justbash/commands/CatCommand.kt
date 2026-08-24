package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult

/**
 * Port of just-bash `cat/cat.ts`.
 */
object CatCommand : Command {
    override val name = "cat"

    private val defs = mapOf(
        "number" to Args.Def(short = "n", long = "number"),
        "numberNonblank" to Args.Def(short = "b", long = "number-nonblank"),
        "showEnds" to Args.Def(short = "E", long = "show-ends"),
        "showTabs" to Args.Def(short = "T", long = "show-tabs"),
        "showNonprinting" to Args.Def(short = "v", long = "show-nonprinting"),
        "showAll" to Args.Def(short = "A", long = "show-all"),
        "squeeze" to Args.Def(short = "s", long = "squeeze-blank"),
        "vE" to Args.Def(short = "e"),
        "vT" to Args.Def(short = "t"),
        "ignored" to Args.Def(short = "u"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "cat", "concatenate files and print on the standard output",
                "cat [OPTION]... [FILE]...",
                listOf(
                    "-A, --show-all         equivalent to -vET",
                    "-b, --number-nonblank  number nonempty output lines, overrides -n",
                    "-e                     equivalent to -vE",
                    "-E, --show-ends        display $ at end of each line",
                    "-n, --number           number all output lines",
                    "-s, --squeeze-blank    suppress repeated empty output lines",
                    "-t                     equivalent to -vT",
                    "-T, --show-tabs        display TAB characters as ^I",
                    "-u                     (ignored)",
                    "-v, --show-nonprinting use ^ and M- notation, except for LFD and TAB",
                    "    --help             display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("cat", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok
        val f = parsed.flags

        val showEnds = f.bool("showEnds") || f.bool("showAll") || f.bool("vE")
        val showTabs = f.bool("showTabs") || f.bool("showAll") || f.bool("vT")
        val showNonprinting = f.bool("showNonprinting") || f.bool("showAll") || f.bool("vE") || f.bool("vT")
        val squeeze = f.bool("squeeze")
        val numberNonblank = f.bool("numberNonblank")
        val numberAll = f.bool("number") && !numberNonblank

        val files = parsed.positional

        val inputs = if (files.isEmpty()) listOf("-") else files
        var stderr = ""
        var exitCode = 0

        val transform = showEnds || showTabs || showNonprinting || squeeze || numberAll || numberNonblank

        val stream = StringBuilder()
        for (file in inputs) {
            try {
                val content = if (file == "-") {
                    ctx.stdin
                } else {
                    ctx.fs.readFileBuffer(ctx.fs.resolvePath(ctx.cwd, file))
                }
                // latin1 view: one char per byte for byte-clean handling
                stream.append(latin1FromBytes(content))
            } catch (_: Exception) {
                stderr += "cat: $file: No such file or directory\n"
                exitCode = 1
            }
        }

        val stdout = if (!transform) {
            stream.toString()
        } else {
            formatCat(stream.toString(), numberAll, numberNonblank, showEnds, showTabs, showNonprinting, squeeze)
        }

        return ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }

    private fun showNonprintingByte(byte: Int): String {
        return if (byte >= 32) {
            when {
                byte < 127 -> byte.toChar().toString()
                byte == 127 -> "^?"
                else -> {
                    val c = byte - 128
                    if (c >= 32) {
                        if (c == 127) "M-^?" else "M-" + c.toChar()
                    } else {
                        "M-^" + (c + 64).toChar()
                    }
                }
            }
        } else {
            "^" + (byte + 64).toChar()
        }
    }

    private fun transformLine(line: String, showTabs: Boolean, showNonprinting: Boolean): String {
        if (!showNonprinting && !showTabs) return line
        val out = StringBuilder()
        for (i in line.indices) {
            val b = line[i].code
            when {
                b == 9 -> out.append(if (showTabs) "^I" else "\t")
                showNonprinting -> out.append(showNonprintingByte(b))
                else -> out.append(line[i])
            }
        }
        return out.toString()
    }

    private fun formatCat(
        stream: String,
        numberAll: Boolean,
        numberNonblank: Boolean,
        showEnds: Boolean,
        showTabs: Boolean,
        showNonprinting: Boolean,
        squeeze: Boolean,
    ): String {
        val parts = stream.split("\n")
        val out = StringBuilder()
        var lineNo = 1
        var prevBlank = false

        for (i in parts.indices) {
            val isLast = i == parts.size - 1
            val terminated = !isLast
            val content = parts[i]

            if (isLast && content.isEmpty()) break

            val blank = content.isEmpty()

            if (squeeze && blank && prevBlank) {
                continue
            }
            prevBlank = blank

            var prefix = ""
            if (numberAll || (numberNonblank && !blank)) {
                prefix = lineNo.toString().padStart(6) + "\t"
                lineNo++
            }

            out.append(prefix).append(transformLine(content, showTabs, showNonprinting))
            if (terminated) {
                out.append(if (showEnds) "$\n" else "\n")
            }
        }
        return out.toString()
    }
}
