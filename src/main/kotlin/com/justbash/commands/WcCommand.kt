package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult

/**
 * Port of just-bash `wc/wc.ts`.
 */
object WcCommand : Command {
    override val name = "wc"

    private val defs = mapOf(
        "lines" to Args.Def(short = "l", long = "lines"),
        "words" to Args.Def(short = "w", long = "words"),
        "bytes" to Args.Def(short = "c", long = "bytes"),
        "chars" to Args.Def(short = "m", long = "chars"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "wc", "print newline, word, and byte counts for each file",
                "wc [OPTION]... [FILE]...",
                listOf(
                    "-c, --bytes      print the byte counts",
                    "-m, --chars      print the character counts",
                    "-l, --lines      print the newline counts",
                    "-w, --words      print the word counts",
                    "    --help       display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("wc", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        var showLines = parsed.flags.bool("lines")
        var showWords = parsed.flags.bool("words")
        var showBytes = parsed.flags.bool("bytes")
        val showChars = parsed.flags.bool("chars")
        val files = parsed.positional

        if (!showLines && !showWords && !showBytes && !showChars) {
            showLines = true
            showWords = true
            showBytes = true
        }
        val showThird = showBytes || showChars

        // Read files
        if (files.isEmpty()) {
            val stats = countStats(ctx.stdin, showChars)
            return ExecResult(
                stdout = formatStats(stats, showLines, showWords, showThird, "", 0) + "\n",
                stderr = "",
                exitCode = 0,
            )
        }

        data class Entry(var filename: String, var lines: Long, var words: Long, var third: Long)

        val allStats = ArrayList<Entry>()
        var totalLines = 0L
        var totalWords = 0L
        var totalThird = 0L
        var stderr = ""
        var exitCode = 0

        for (file in files) {
            try {
                val content = ctx.fs.readFileBuffer(ctx.fs.resolvePath(ctx.cwd, file))
                val stats = countStats(content, showChars)
                totalLines += stats.lines
                totalWords += stats.words
                totalThird += stats.third
                allStats.add(Entry(file, stats.lines, stats.words, stats.third))
            } catch (_: Exception) {
                stderr += "wc: $file: No such file or directory\n"
                exitCode = 1
            }
        }

        val maxLines = if (files.size > 1) totalLines else allStats.maxOfOrNull { it.lines } ?: 0
        val maxWords = if (files.size > 1) totalWords else allStats.maxOfOrNull { it.words } ?: 0
        val maxThird = if (files.size > 1) totalThird else allStats.maxOfOrNull { it.third } ?: 0

        var maxWidth = if (files.size > 1) 3 else 0
        if (showLines) maxWidth = maxOf(maxWidth, maxLines.toString().length)
        if (showWords) maxWidth = maxOf(maxWidth, maxWords.toString().length)
        if (showThird) maxWidth = maxOf(maxWidth, maxThird.toString().length)

        val sb = StringBuilder()
        for (entry in allStats) {
            sb.append(formatStats(Stat(entry.lines, entry.words, entry.third), showLines, showWords, showThird, entry.filename, maxWidth))
                .append("\n")
        }
        if (files.size > 1) {
            sb.append(formatStats(Stat(totalLines, totalWords, totalThird), showLines, showWords, showThird, "total", maxWidth))
                .append("\n")
        }

        return ExecResult(stdout = sb.toString(), stderr = stderr, exitCode = exitCode)
    }

    private data class Stat(val lines: Long, val words: Long, val third: Long)

    private fun countStats(bytes: ByteArray, countCodepoints: Boolean): Stat {
        // latin1 view for byte-clean count of lines/words
        val text = latin1FromBytes(bytes)
        val len = text.length
        val third = if (countCodepoints) {
            bytes.toString(Charsets.UTF_8).codePointCount(0, bytes.toString(Charsets.UTF_8).length).toLong()
        } else {
            len.toLong()
        }
        var lines = 0L
        var words = 0L
        var inWord = false

        for (i in 0 until len) {
            val c = text[i]
            when {
                c == '\n' -> {
                    lines++
                    if (inWord) { words++; inWord = false }
                }
                c == ' ' || c == '\t' || c == '\r' -> {
                    if (inWord) { words++; inWord = false }
                }
                else -> inWord = true
            }
        }
        if (inWord) words++

        return Stat(lines, words, third)
    }

    private fun formatStats(
        stats: Stat,
        showLines: Boolean,
        showWords: Boolean,
        showThird: Boolean,
        filename: String,
        minWidth: Int,
    ): String {
        val values = ArrayList<String>()
        if (showLines) values.add(stats.lines.toString().padStart(minWidth))
        if (showWords) values.add(stats.words.toString().padStart(minWidth))
        if (showThird) values.add(stats.third.toString().padStart(minWidth))

        var result = values.joinToString(" ")
        if (filename.isNotEmpty()) {
            result += " $filename"
        }
        return result
    }
}
