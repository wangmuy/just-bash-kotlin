package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult

/**
 * Port of just-bash `uniq/uniq.ts`.
 */
object UniqCommand : Command {
    override val name = "uniq"

    private val defs = mapOf(
        "count" to Args.Def(short = "c", long = "count"),
        "duplicatesOnly" to Args.Def(short = "d", long = "repeated"),
        "uniqueOnly" to Args.Def(short = "u", long = "unique"),
        "ignoreCase" to Args.Def(short = "i", long = "ignore-case"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "uniq", "report or omit repeated lines",
                "uniq [OPTION]... [INPUT [OUTPUT]]",
                listOf(
                    "-c, --count        prefix lines by the number of occurrences",
                    "-d, --repeated     only print duplicate lines",
                    "-i, --ignore-case  ignore case when comparing",
                    "-u, --unique       only print unique lines",
                    "    --help         display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("uniq", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val count = parsed.flags.bool("count")
        val duplicatesOnly = parsed.flags.bool("duplicatesOnly")
        val uniqueOnly = parsed.flags.bool("uniqueOnly")
        val ignoreCase = parsed.flags.bool("ignoreCase")
        val files = parsed.positional

        val content = if (files.isEmpty()) {
            if (ignoreCase) ctx.stdin.toString(Charsets.UTF_8) else latin1FromBytes(ctx.stdin)
        } else {
            val filePath = ctx.fs.resolvePath(ctx.cwd, files[0])
            try {
                if (ignoreCase) ctx.fs.readFile(filePath) else latin1FromBytes(ctx.fs.readFileBuffer(filePath))
            } catch (_: Exception) {
                return ExecResult(stdout = "", stderr = "uniq: ${files[0]}: No such file or directory\n", exitCode = 1)
            }
        }

        val lines = content.split("\n").toMutableList()
        if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.size - 1)

        if (lines.isEmpty()) {
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        data class Group(val line: String, val cnt: Int)

        val result = ArrayList<Group>()
        var currentLine = lines[0]
        var currentCount = 1

        val compareLines: (String, String) -> Boolean = { a, b ->
            if (ignoreCase) a.lowercase() == b.lowercase() else a == b
        }

        for (i in 1 until lines.size) {
            if (compareLines(lines[i], currentLine)) {
                currentCount++
            } else {
                result.add(Group(currentLine, currentCount))
                currentLine = lines[i]
                currentCount = 1
            }
        }
        result.add(Group(currentLine, currentCount))

        var filtered: List<Group> = result
        if (duplicatesOnly) filtered = result.filter { it.cnt > 1 }
        else if (uniqueOnly) filtered = result.filter { it.cnt == 1 }

        val sb = StringBuilder()
        for (group in filtered) {
            if (count) {
                sb.append(group.cnt.toString().padStart(4)).append(' ').append(group.line).append('\n')
            } else {
                sb.append(group.line).append('\n')
            }
        }

        return ExecResult(stdout = sb.toString(), stderr = "", exitCode = 0)
    }
}