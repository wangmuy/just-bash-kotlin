package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import com.justbash.fs.DirentEntry
import java.time.Instant

/**
 * Port of just-bash `ls/ls.ts` (plain output; colors are always disabled in
 * this port, matching the "colors off" instruction).
 */
object LsCommand : Command {
    override val name = "ls"

    private val defs = mapOf(
        "showAll" to Args.Def(short = "a", long = "all"),
        "showAlmostAll" to Args.Def(short = "A", long = "almost-all"),
        "longFormat" to Args.Def(short = "l"),
        "humanReadable" to Args.Def(short = "h", long = "human-readable"),
        "recursive" to Args.Def(short = "R", long = "recursive"),
        "reverse" to Args.Def(short = "r", long = "reverse"),
        "sortBySize" to Args.Def(short = "S"),
        "classifyFiles" to Args.Def(short = "F", long = "classify"),
        "directoryOnly" to Args.Def(short = "d", long = "directory"),
        "sortByTime" to Args.Def(short = "t"),
        "onePerLine" to Args.Def(short = "1"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "ls", "list directory contents",
                "ls [OPTION]... [FILE]...",
                listOf(
                    "-a, --all            do not ignore entries starting with .",
                    "-A, --almost-all     do not list . and ..",
                    "-d, --directory      list directories themselves, not their contents",
                    "-F, --classify       append indicator (one of */=>@) to entries",
                    "-h, --human-readable with -l, print sizes like 1K 234M 2G etc.",
                    "-l                   use a long listing format",
                    "-r, --reverse        reverse order while sorting",
                    "-R, --recursive      list subdirectories recursively",
                    "-S                   sort by file size, largest first",
                    "-t                   sort by time, newest first",
                    "-1                   list one file per line",
                    "    --help           display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("ls", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val showAll = parsed.flags.bool("showAll")
        val showAlmostAll = parsed.flags.bool("showAlmostAll")
        val longFormat = parsed.flags.bool("longFormat")
        val humanReadable = parsed.flags.bool("humanReadable")
        val recursive = parsed.flags.bool("recursive")
        val reverse = parsed.flags.bool("reverse")
        val sortBySize = parsed.flags.bool("sortBySize")
        val classifyFiles = parsed.flags.bool("classifyFiles")
        val directoryOnly = parsed.flags.bool("directoryOnly")

        val paths = parsed.positional.toMutableList()
        if (paths.isEmpty()) paths.add(".")

        var stdout = ""
        var stderr = ""
        var exitCode = 0

        for (i in paths.indices) {
            val path = paths[i]

            if (i > 0 && stdout.isNotEmpty() && !stdout.endsWith("\n\n")) {
                stdout += "\n"
            }

            if (directoryOnly) {
                val fullPath = ctx.fs.resolvePath(ctx.cwd, path)
                try {
                    val stat = ctx.fs.stat(fullPath)
                    val suffix = if (classifyFiles) classifySuffix(ctx.fs.lstat(fullPath)) else if (stat.isDirectory) "/" else ""
                    if (longFormat) {
                        val mode = if (stat.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                        val size = stat.size
                        val sizeStr = if (humanReadable) formatHumanSize(size).padStart(5) else size.toString().padStart(5)
                        val dateStr = formatDate(stat.mtime)
                        stdout += "$mode 1 user user $sizeStr $dateStr $path$suffix\n"
                    } else {
                        stdout += "$path$suffix\n"
                    }
                } catch (_: Exception) {
                    stderr += "ls: cannot access '$path': No such file or directory\n"
                    exitCode = 2
                }
                continue
            }

            if (path.contains("*") || path.contains("?") || path.contains("[")) {
                val result = listGlob(path, ctx, showAll, showAlmostAll, longFormat, reverse, humanReadable, sortBySize, classifyFiles)
                stdout += result.stdout
                stderr += result.stderr
                if (result.exitCode != 0) exitCode = result.exitCode
            } else {
                val result = listPath(path, ctx, showAll, showAlmostAll, longFormat, recursive, paths.size > 1, reverse, humanReadable, sortBySize, classifyFiles)
                stdout += result.stdout
                stderr += result.stderr
                if (result.exitCode != 0) exitCode = result.exitCode
            }
        }

        return ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }

    private fun formatHumanSize(bytes: Long): String {
        if (bytes < 1024) return bytes.toString()
        if (bytes < 1024L * 1024L) {
            val k = bytes / 1024.0
            return if (k < 10) String.format("%.1fK", k) else "${Math.round(k)}K"
        }
        if (bytes < 1024L * 1024L * 1024L) {
            val m = bytes / (1024.0 * 1024.0)
            return if (m < 10) String.format("%.1fM", m) else "${Math.round(m)}M"
        }
        val g = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (g < 10) String.format("%.1fG", g) else "${Math.round(g)}G"
    }

    private val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun formatDate(instant: Instant): String {
        val zdt = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        val month = months[zdt.monthValue - 1]
        val day = zdt.dayOfMonth.toString().padStart(2, ' ')
        val sixMonthsAgo = Instant.now().minusSeconds(180L * 24 * 60 * 60)
        return if (instant.isAfter(sixMonthsAgo)) {
            val hh = zdt.hour.toString().padStart(2, '0')
            val mm = zdt.minute.toString().padStart(2, '0')
            "$month $day $hh:$mm"
        } else {
            "$month $day  ${zdt.year}"
        }
    }

    private fun classifySuffix(stat: com.justbash.fs.FsStat): String = when {
        stat.isDirectory -> "/"
        stat.isSymbolicLink -> "@"
        (stat.mode and 0x1C0) != 0 -> "*" // 0o111 == any execute bit
        else -> ""
    }

    private fun listGlob(
        pattern: String,
        ctx: CommandContext,
        showAll: Boolean,
        showAlmostAll: Boolean,
        longFormat: Boolean,
        reverse: Boolean,
        humanReadable: Boolean,
        sortBySize: Boolean,
        classifyFiles: Boolean,
    ): ExecResult {
        val showHidden = showAll || showAlmostAll
        val allPaths = ctx.fs.getAllPaths()
        val basePath = ctx.fs.resolvePath(ctx.cwd, ".")

        val matches = ArrayList<String>()
        for (p in allPaths) {
            val isWithinBase = p == basePath || basePath == "/" || p.startsWith("$basePath/")
            val relativePath = if (isWithinBase) {
                p.removePrefix(if (basePath == "/") "/" else "$basePath/")
            } else p

            if (globMatch(relativePath, pattern) || globMatch(p, pattern)) {
                val basename = relativePath.split("/").lastOrNull() ?: relativePath
                if (!showHidden && basename.startsWith(".")) continue
                matches.add(relativePath.ifEmpty { p })
            }
        }

        if (matches.isEmpty()) {
            return ExecResult(stdout = "", stderr = "ls: $pattern: No such file or directory\n", exitCode = 2)
        }

        if (sortBySize) {
            matches.sortWith(Comparator { a, b ->
                val sizeA = try { ctx.fs.stat(ctx.fs.resolvePath(ctx.cwd, a)).size } catch (_: Exception) { 0L }
                val sizeB = try { ctx.fs.stat(ctx.fs.resolvePath(ctx.cwd, b)).size } catch (_: Exception) { 0L }
                sizeB.compareTo(sizeA)
            })
        } else {
            matches.sort()
        }
        if (reverse) matches.reverse()

        if (longFormat) {
            val lines = ArrayList<String>()
            for (match in matches) {
                val fullPath = ctx.fs.resolvePath(ctx.cwd, match)
                try {
                    val stat = ctx.fs.stat(fullPath)
                    val mode = if (stat.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                    val suffix = if (classifyFiles) classifySuffix(ctx.fs.lstat(fullPath)) else if (stat.isDirectory) "/" else ""
                    val sizeStr = if (humanReadable) formatHumanSize(stat.size).padStart(5) else stat.size.toString().padStart(5)
                    lines.add("$mode 1 user user $sizeStr ${formatDate(stat.mtime)} $match$suffix")
                } catch (_: Exception) {
                    lines.add("-rw-r--r-- 1 user user     0 Jan  1 00:00 $match")
                }
            }
            return ExecResult(stdout = joinLines(lines), stderr = "", exitCode = 0)
        }

        if (classifyFiles) {
            val classified = ArrayList<String>()
            for (match in matches) {
                try {
                    val stat = ctx.fs.lstat(ctx.fs.resolvePath(ctx.cwd, match))
                    classified.add("$match${classifySuffix(stat)}")
                } catch (_: Exception) {
                    classified.add(match)
                }
            }
            return ExecResult(stdout = joinLines(classified), stderr = "", exitCode = 0)
        }

        return ExecResult(stdout = joinLines(matches), stderr = "", exitCode = 0)
    }

    private fun listPath(
        path: String,
        ctx: CommandContext,
        showAll: Boolean,
        showAlmostAll: Boolean,
        longFormat: Boolean,
        recursive: Boolean,
        showHeader: Boolean,
        reverse: Boolean,
        humanReadable: Boolean,
        sortBySize: Boolean,
        classifyFiles: Boolean,
    ): ExecResult {
        val showHidden = showAll || showAlmostAll
        val fullPath = ctx.fs.resolvePath(ctx.cwd, path)

        val stat = try {
            ctx.fs.stat(fullPath)
        } catch (_: Exception) {
            return ExecResult(stdout = "", stderr = "ls: $path: No such file or directory\n", exitCode = 2)
        }

        if (!stat.isDirectory) {
            val fileSuffix = if (classifyFiles) classifySuffix(ctx.fs.lstat(fullPath)) else ""
            if (longFormat) {
                val sizeStr = if (humanReadable) formatHumanSize(stat.size).padStart(5) else stat.size.toString().padStart(5)
                return ExecResult(stdout = "-rw-r--r-- 1 user user $sizeStr ${formatDate(stat.mtime)} $path$fileSuffix\n", stderr = "", exitCode = 0)
            }
            return ExecResult(stdout = "$path$fileSuffix\n", stderr = "", exitCode = 0)
        }

        var entries = ctx.fs.readdir(fullPath).toMutableList()

        if (!showHidden) {
            entries = entries.filter { !it.startsWith(".") }.toMutableList()
        }

        if (sortBySize) {
            entries.sortWith(Comparator { a, b ->
                val sizeA = try { ctx.fs.stat(ctx.fs.resolvePath(ctx.cwd, if (fullPath == "/") "/$a" else "$fullPath/$a")).size } catch (_: Exception) { 0L }
                val sizeB = try { ctx.fs.stat(ctx.fs.resolvePath(ctx.cwd, if (fullPath == "/") "/$b" else "$fullPath/$b")).size } catch (_: Exception) { 0L }
                sizeB.compareTo(sizeA)
            })
        } else {
            entries.sort()
        }

        if (showAll) {
            entries.add(0, "..")
            entries.add(0, ".")
        }

        if (reverse) entries.reverse()

        var stdout = ""
        var stderr = ""
        var exitCode = 0

        if (recursive || showHeader) {
            stdout += "$path:\n"
        }

        if (longFormat) {
            stdout += "total ${entries.size}\n"

            val specialEntries = entries.filter { it == "." || it == ".." }
            val regularEntries = entries.filter { it != "." && it != ".." }

            for (entry in specialEntries) {
                stdout += "drwxr-xr-x 1 user user     0 Jan  1 00:00 $entry\n"
            }

            for (entry in regularEntries) {
                val entryPath = if (fullPath == "/") "/$entry" else "$fullPath/$entry"
                try {
                    val entryStat = ctx.fs.stat(entryPath)
                    val mode = if (entryStat.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                    val suffix = if (classifyFiles) classifySuffix(ctx.fs.lstat(entryPath)) else if (entryStat.isDirectory) "/" else ""
                    val sizeStr = if (humanReadable) formatHumanSize(entryStat.size).padStart(5) else entryStat.size.toString().padStart(5)
                    stdout += "$mode 1 user user $sizeStr ${formatDate(entryStat.mtime)} $entry$suffix\n"
                } catch (_: Exception) {
                    stdout += "-rw-r--r-- 1 user user     0 Jan  1 00:00 $entry\n"
                }
            }
        } else if (classifyFiles) {
            val classified = ArrayList<String>()
            val specialEntries = entries.filter { it == "." || it == ".." }
            val regularEntries = entries.filter { it != "." && it != ".." }
            for (entry in specialEntries) classified.add("$entry/")
            for (entry in regularEntries) {
                val entryPath = if (fullPath == "/") "/$entry" else "$fullPath/$entry"
                try {
                    val entryStat = ctx.fs.lstat(entryPath)
                    classified.add("$entry${classifySuffix(entryStat)}")
                } catch (_: Exception) {
                    classified.add(entry)
                }
            }
            stdout += joinLines(classified)
        } else {
            stdout += joinLines(entries)
        }

        if (recursive) {
            val filteredEntries = entries.filter { it != "." && it != ".." }
            val dirEntries = ArrayList<String>()
            for (entry in filteredEntries) {
                val entryPath = if (fullPath == "/") "/$entry" else "$fullPath/$entry"
                try {
                    if (ctx.fs.stat(entryPath).isDirectory) dirEntries.add(entry)
                } catch (_: Exception) {
                    // skip
                }
            }
            dirEntries.sort()
            if (reverse) dirEntries.reverse()

            val subResults = ArrayList<Pair<String, ExecResult>>()
            for (dir in dirEntries) {
                val subPath = if (path == ".") "./$dir" else "$path/$dir"
                subResults.add(Pair(dir, listPath(subPath, ctx, showAll, showAlmostAll, longFormat, recursive, false, reverse, humanReadable, sortBySize, classifyFiles)))
            }
            subResults.sortWith(Comparator { a, b -> a.first.compareTo(b.first) })
            if (reverse) subResults.reverse()

            for ((_, result) in subResults) {
                stdout += "\n"
                stdout += result.stdout
                stderr += result.stderr
                if (result.exitCode != 0) exitCode = result.exitCode
            }
        }

        return ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }

    private fun joinLines(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        return lines.joinToString("\n") + "\n"
    }

    private fun globMatch(name: String, pattern: String): Boolean {
        val regex = globToRegex(pattern)
        return regex.matcher(name).matches()
    }

    private fun globToRegex(pattern: String): java.util.regex.Pattern {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                '[' -> {
                    var j = i + 1
                    while (j < pattern.length && pattern[j] != ']') j++
                    sb.append(pattern.substring(i, j + 1))
                    i = j
                }
                '.', '+', '^', '$', '{', '}', '(', ')', '|', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append("$")
        return java.util.regex.Pattern.compile(sb.toString())
    }
}