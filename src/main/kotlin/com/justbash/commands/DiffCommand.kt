package com.justbash.commands

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.github.difflib.patch.Patch
import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult

/**
 * diff command backed by java-diff-utils (io.github.java-diff-utils).
 * Compares two files line by line and produces unified diff output.
 *
 * Port of just-bash `diff/diff.ts`.
 */
object DiffCommand : Command {
    override val name = "diff"

    private val defs = mapOf(
        "unified" to Args.Def(short = "u", long = "unified", type = "boolean"),
        "brief" to Args.Def(short = "q", long = "brief", type = "boolean"),
        "reportSame" to Args.Def(short = "s", long = "report-identical-files", type = "boolean"),
        "ignoreCase" to Args.Def(short = "i", long = "ignore-case", type = "boolean"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "diff", "compare files line by line",
                "diff [OPTION]... FILE1 FILE2",
                listOf(
                    "-u, --unified             output unified diff format (default)",
                    "-q, --brief               report only whether files differ",
                    "-s, --report-identical-files  report when files are the same",
                    "-i, --ignore-case          ignore case differences",
                    "    --help                display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("diff", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val brief = parsed.flags.bool("brief")
        val reportSame = parsed.flags.bool("reportSame")
        val ignoreCase = parsed.flags.bool("ignoreCase")
        val files = parsed.positional

        if (files.size < 2) {
            return ExecResult(stderr = "diff: missing operand\n", exitCode = 2)
        }

        val (f1, f2) = files[0] to files[1]

        val c1: String
        val c2: String
        try {
            c1 = if (f1 == "-") ctx.stdin.toString(Charsets.UTF_8) else ctx.fs.readFile(ctx.fs.resolvePath(ctx.cwd, f1))
        } catch (e: Exception) {
            return ExecResult(stderr = "diff: $f1: No such file or directory\n", exitCode = 2)
        }
        try {
            c2 = if (f2 == "-") ctx.stdin.toString(Charsets.UTF_8) else ctx.fs.readFile(ctx.fs.resolvePath(ctx.cwd, f2))
        } catch (e: Exception) {
            return ExecResult(stderr = "diff: $f2: No such file or directory\n", exitCode = 2)
        }

        var t1 = c1; var t2 = c2
        if (ignoreCase) { t1 = t1.lowercase(); t2 = t2.lowercase() }

        if (t1 == t2) {
            if (reportSame) return ExecResult(stdout = "Files $f1 and $f2 are identical\n", exitCode = 0)
            return ExecResult(stdout = "", exitCode = 0)
        }

        if (brief) {
            return ExecResult(stdout = "Files $f1 and $f2 differ\n", exitCode = 1)
        }

        // Generate unified diff
        val lines1 = c1.split("\n").dropLastWhile { it.isEmpty() }
        val lines2 = c2.split("\n").dropLastWhile { it.isEmpty() }
        val patch: Patch<String> = DiffUtils.diff(lines1, lines2)
        val unified = UnifiedDiffUtils.generateUnifiedDiff(f1, f2, lines1, patch, 3)

        return ExecResult(stdout = unified.joinToString("\n") + "\n", exitCode = 1)
    }
}