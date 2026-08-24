package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Port of just-bash `grep/grep.ts`.
 *
 * Regex semantics: the TypeScript original compiles BRE/ERE to JavaScript's
 * regex engine (which has richer, PCRE-like syntax). On the JVM we compile to
 * [java.util.regex.Pattern]. We faithfully translate POSIX BRE to ERE (groups,
 * alternation, intervals, anchor/asterisk literal rules) exactly as the
 * original's `escapeRegexForBasicGrep`, then compile as a Java regex.
 * Differences (documented): Java regex lacks some PCRE extensions used under
 * `-P` (e.g. `\K`, lookbehind in some forms); `-P` is accepted but may reject
 * patterns Java cannot express. Word boundaries in `-w` use `\b`.
 */
object GrepCommand : Command {
    override val name = "grep"

    private const val STDIN_FILENAME = "(standard input)"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        var ignoreCase = false
        var showLineNumbers = false
        var invertMatch = false
        var countOnly = false
        var filesWithMatches = false
        var filesWithoutMatch = false
        var recursive = false
        var wholeWord = false
        var lineRegexp = false
        var extendedRegex = false
        var perlRegex = false
        var fixedStrings = false
        var onlyMatching = false
        var noFilename = false
        var quietMode = false
        var maxCount = 0
        var beforeContext = 0
        var afterContext = 0
        val includePatterns = ArrayList<String>()
        val excludePatterns = ArrayList<String>()
        val excludeDirPatterns = ArrayList<String>()
        var pattern: String? = null
        val patternFiles = ArrayList<String>()
        val operands = ArrayList<String>()
        var parseOptions = true

        var i = 0
        while (i < args.size) {
            val arg = args[i]

            if (parseOptions && arg == "--") { parseOptions = false; i++; continue }
            if (parseOptions && arg == "--help") {
                return showHelp(
                    "grep", "print lines that match patterns",
                    "grep [OPTION]... PATTERN [FILE]...",
                    listOf(
                        "-E, --extended-regexp    PATTERN is an extended regular expression",
                        "-P, --perl-regexp        PATTERN is a Perl regular expression",
                        "-F, --fixed-strings      PATTERN is a set of newline-separated strings",
                        "-i, --ignore-case        ignore case distinctions",
                        "-v, --invert-match       select non-matching lines",
                        "-w, --word-regexp        match only whole words",
                        "-x, --line-regexp        match only whole lines",
                        "-c, --count              print only a count of matching lines",
                        "-l, --files-with-matches print only names of files with matches",
                        "-L, --files-without-match print names of files with no matches",
                        "-m NUM, --max-count=NUM  stop after NUM matches",
                        "-n, --line-number        print line number with output lines",
                        "-h, --no-filename        suppress the file name prefix on output",
                        "-o, --only-matching      show only nonempty parts of lines that match",
                        "-q, --quiet, --silent    suppress all normal output",
                        "-r, -R, --recursive      search directories recursively",
                        "-e PATTERN               use PATTERN for matching",
                        "-f FILE, --file=FILE     obtain patterns from FILE, one per line",
                        "    --include=GLOB       search only files matching GLOB",
                        "    --exclude=GLOB       skip files matching GLOB",
                        "    --exclude-dir=DIR    skip directories matching DIR",
                        "    --help               display this help and exit",
                    ),
                )
            }

            if (parseOptions && arg.startsWith("-") && arg != "-") {
                when {
                    arg == "-e" && i + 1 < args.size -> { pattern = args[++i]; i++; continue }
                    arg.startsWith("--file=") -> { patternFiles.add(arg.substring(7)); i++; continue }
                    arg.startsWith("--include=") -> { includePatterns.add(arg.substring(10)); i++; continue }
                    arg.startsWith("--exclude=") -> { excludePatterns.add(arg.substring(10)); i++; continue }
                    arg.startsWith("--exclude-dir=") -> { excludeDirPatterns.add(arg.substring(14)); i++; continue }
                    arg.startsWith("--max-count=") -> { maxCount = arg.substring(12).toIntOrNull() ?: 0; i++; continue }
                    arg == "-m" && i + 1 < args.size -> { maxCount = args[++i].toIntOrNull() ?: 0; i++; continue }
                    Regex("^-m(\\d+)$").find(arg) != null -> {
                        maxCount = Regex("^-m(\\d+)$").find(arg)!!.groupValues[1].toIntOrNull() ?: 0
                        i++
                        continue
                    }
                    arg == "-A" || arg == "-B" || arg == "-C" -> {
                        if (i + 1 < args.size) {
                            val num = args[++i].toIntOrNull() ?: 0
                            when (arg) {
                                "-A" -> afterContext = num
                                "-B" -> beforeContext = num
                                else -> { beforeContext = num; afterContext = num }
                            }
                        }
                        i++
                        continue
                    }
                }

                val flags = if (arg.startsWith("--")) listOf(arg) else arg.substring(1).map { it.toString() }

                var f = 0
                var broke = false
                while (f < flags.size) {
                    val flag = flags[f]
                    when {
                        flag == "f" || flag == "--file" -> {
                            val attached = if (flag == "f") flags.subList(f + 1, flags.size).joinToString("") else ""
                            if (attached.isNotEmpty()) {
                                patternFiles.add(attached)
                            } else if (i + 1 < args.size) {
                                patternFiles.add(args[++i])
                            } else {
                                return ExecResult(
                                    stdout = "",
                                    stderr = if (flag == "f") "grep: option requires an argument -- 'f'\n" else "grep: option '--file' requires an argument\n",
                                    exitCode = 2,
                                )
                            }
                            broke = true
                        }
                        flag == "i" || flag == "--ignore-case" -> ignoreCase = true
                        flag == "n" || flag == "--line-number" -> showLineNumbers = true
                        flag == "v" || flag == "--invert-match" -> invertMatch = true
                        flag == "c" || flag == "--count" -> countOnly = true
                        flag == "l" || flag == "--files-with-matches" -> filesWithMatches = true
                        flag == "L" || flag == "--files-without-match" -> filesWithoutMatch = true
                        flag == "r" || flag == "R" || flag == "--recursive" -> recursive = true
                        flag == "w" || flag == "--word-regexp" -> wholeWord = true
                        flag == "x" || flag == "--line-regexp" -> lineRegexp = true
                        flag == "E" || flag == "--extended-regexp" -> extendedRegex = true
                        flag == "P" || flag == "--perl-regexp" -> perlRegex = true
                        flag == "F" || flag == "--fixed-strings" -> fixedStrings = true
                        flag == "o" || flag == "--only-matching" -> onlyMatching = true
                        flag == "h" || flag == "--no-filename" -> noFilename = true
                        flag == "q" || flag == "--quiet" || flag == "--silent" -> quietMode = true
                        flag.startsWith("--") -> return unknownOption("grep", flag)
                        flag.length == 1 -> return unknownOption("grep", "-$flag")
                    }
                    if (broke) break
                    f++
                }
                i++
            } else {
                operands.add(arg)
                i++
            }
        }

        // First operand is the pattern when no -e/-f was given
        if (pattern == null && patternFiles.isEmpty()) {
            pattern = if (operands.isNotEmpty()) operands.removeAt(0) else null
            if (pattern == null) {
                return ExecResult(stdout = "", stderr = "grep: missing pattern\n", exitCode = 2)
            }
        }
        val files = operands

        // Collect patterns
        val patterns = if (pattern == null) ArrayList<String>() else ArrayList(splitPatternOperand(pattern!!))

        var stdinUsedForPatterns = false
        for (patternFile in patternFiles) {
            val content: String
            when {
                patternFile == "" -> {
                    return ExecResult(stdout = "", stderr = "grep: : No such file or directory\n", exitCode = 2)
                }
                patternFile == "-" -> {
                    content = if (stdinUsedForPatterns) "" else ctx.stdin.toString(Charsets.UTF_8)
                    stdinUsedForPatterns = true
                }
                else -> {
                    try {
                        val path = ctx.fs.resolvePath(ctx.cwd, patternFile)
                        val stat = ctx.fs.stat(path)
                        if (stat.isDirectory) {
                            return ExecResult(stdout = "", stderr = "grep: $patternFile: Is a directory\n", exitCode = 2)
                        }
                        content = ctx.fs.readFile(path)
                    } catch (_: Exception) {
                        return ExecResult(stdout = "", stderr = "grep: $patternFile: No such file or directory\n", exitCode = 2)
                    }
                }
            }
            patterns.addAll(splitPatternFile(content))
        }

        if (patterns.isEmpty() && !invertMatch && !filesWithoutMatch) {
            return ExecResult(stdout = "", stderr = "", exitCode = 1)
        }

        val regexMode = when {
            fixedStrings -> "fixed"
            extendedRegex -> "extended"
            perlRegex -> "perl"
            else -> "basic"
        }

        if (regexMode == "perl" && patterns.toSet().size > 1) {
            return ExecResult(stdout = "", stderr = "grep: the -P option only supports a single pattern\n", exitCode = 2)
        }

        // Compile each pattern individually to catch syntax errors
        if (patterns.size > 1 && regexMode != "fixed") {
            for (p in patterns) {
                try {
                    compilePattern(p, regexMode, false, false, false)
                } catch (_: Exception) {
                    return ExecResult(stdout = "", stderr = "grep: invalid regular expression: $p\n", exitCode = 2)
                }
            }
        }

        val combinedPattern: String
        val combinedMode: String
        if (patterns.isEmpty()) {
            combinedPattern = ".^" // never matches
            combinedMode = "extended"
        } else if (patterns.size == 1) {
            combinedPattern = patterns[0]
            combinedMode = regexMode
        } else {
            combinedPattern = combinePatterns(patterns, regexMode)
            combinedMode = if (regexMode == "fixed" || regexMode == "basic") "extended" else regexMode
        }

        val regex: Pattern
        try {
            regex = compilePattern(combinedPattern, combinedMode, ignoreCase, wholeWord, lineRegexp)
        } catch (_: Exception) {
            return ExecResult(stdout = "", stderr = "grep: invalid regular expression: ${patterns.joinToString("\n")}\n", exitCode = 2)
        }

        // No files: read from stdin
        if (files.isEmpty()) {
            val input = if (stdinUsedForPatterns) "" else ctx.stdin.toString(Charsets.UTF_8)
            val result = searchContent(
                input, regex,
                showLineNumbers, countOnly, "", onlyMatching,
                invertMatch, beforeContext, afterContext, maxCount,
            )
            if (quietMode) {
                return ExecResult(stdout = "", stderr = "", exitCode = if (result.matched) 0 else 1)
            }
            return ExecResult(stdout = result.output, stderr = "", exitCode = if (result.matched) 0 else 1)
        }

        var stdout = ""
        var stderr = ""
        var anyMatch = false
        var anyError = false

        // Expand files (glob + recursion) — simplified: literal paths and simple globs
        val filesToSearch = ArrayList<FileEntry>()
        var stdinConsumed = false
        var hasFileTarget = false

        for (file in files) {
            if (file == "-") {
                filesToSearch.add(FileEntry(STDIN_FILENAME, isFile = true, isStdin = true, stdinAtEof = stdinConsumed))
                stdinConsumed = true
                continue
            }
            hasFileTarget = true
            if (file.contains("*") || file.contains("?") || file.contains("[")) {
                filesToSearch.addAll(expandGlobPattern(file, ctx))
            } else if (recursive) {
                filesToSearch.addAll(expandRecursive(file, ctx, includePatterns, excludePatterns, excludeDirPatterns))
            } else {
                filesToSearch.add(FileEntry(file))
            }
        }

        val showFilename = (filesToSearch.size > 1 || (recursive && hasFileTarget)) && !noFilename

        for (fileEntry in filesToSearch) {
            val file = fileEntry.path
            val basename = file.split("/").lastOrNull() ?: file

            // exclude/include (non-recursive)
            if (excludePatterns.isNotEmpty() && !recursive && !fileEntry.isStdin) {
                if (excludePatterns.any { matchGlob(basename, it) }) continue
            }
            if (includePatterns.isNotEmpty() && !recursive && !fileEntry.isStdin) {
                if (!includePatterns.any { matchGlob(basename, it) }) continue
            }

            val content: String
            try {
                content = if (fileEntry.isStdin) {
                    if (fileEntry.stdinAtEof) "" else ctx.stdin.toString(Charsets.UTF_8)
                } else {
                    val filePath = ctx.fs.resolvePath(ctx.cwd, file)
                    var isDirectory = false
                    if (!fileEntry.isFile) {
                        isDirectory = ctx.fs.stat(filePath).isDirectory
                    }
                    if (isDirectory) {
                        if (!recursive) {
                            stderr += "grep: $file: Is a directory\n"
                            continue
                        }
                        continue
                    }
                    ctx.fs.readFile(filePath)
                }
            } catch (_: Exception) {
                stderr += "grep: $file: No such file or directory\n"
                anyError = true
                continue
            }

            val result = searchContent(
                content, regex,
                showLineNumbers, countOnly, if (showFilename) file else "", onlyMatching,
                invertMatch, beforeContext, afterContext, maxCount,
            )

            if (result.matched) {
                anyMatch = true
                if (quietMode) return ExecResult(stdout = "", stderr = "", exitCode = 0)
                if (filesWithMatches) stdout += "$file\n"
                else if (!filesWithoutMatch) stdout += result.output
            } else {
                if (filesWithoutMatch) stdout += "$file\n"
                else if (countOnly && !filesWithMatches) stdout += result.output
            }
        }

        val exitCode = if (anyError) 2 else if (anyMatch) 0 else 1

        if (quietMode) return ExecResult(stdout = "", stderr = "", exitCode = exitCode)

        return ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }

    // ---- helpers ----

    private data class FileEntry(
        val path: String,
        val isFile: Boolean = true,
        val isStdin: Boolean = false,
        val stdinAtEof: Boolean = false,
    )

    private data class SearchResult(val output: String, val matched: Boolean)

    private fun splitPatternOperand(value: String): List<String> = value.split("\n")

    private fun splitPatternFile(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split("\n").toMutableList()
        if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.size - 1)
        return lines
    }

    private fun escapeRegexMeta(s: String): String =
        s.replace(Regex("[.*+?^\${}()|\\[\\]\\\\]"), "\\\\\$0")

    private fun combinePatterns(patterns: List<String>, mode: String): String {
        if (patterns.size == 1) return patterns[0]
        return when (mode) {
            "fixed" -> patterns.joinToString("|") { "(?:" + escapeRegexMeta(it) + ")" }
            "basic" -> patterns.joinToString("\\|")
            else -> patterns.joinToString("|") { "(?:$it)" }
        }
    }

    /**
     * Translate POSIX BRE to ERE (mirrors `escapeRegexForBasicGrep`).
     */
    private fun breToEre(str: String): String {
        val out = StringBuilder()
        var i = 0
        var atPatternStart = true

        while (i < str.length) {
            val ch = str[i]

            if (ch == '[') {
                out.append(ch); i++
                if (i < str.length && (str[i] == '^' || str[i] == '!')) { out.append(str[i]); i++ }
                if (i < str.length && str[i] == ']') { out.append(str[i]); i++ }
                while (i < str.length && str[i] != ']') {
                    if (str[i] == '\\' && i + 1 < str.length) { out.append(str[i]); out.append(str[i + 1]); i += 2 }
                    else { out.append(str[i]); i++ }
                }
                if (i < str.length && str[i] == ']') { out.append(str[i]); i++ }
                atPatternStart = false
                continue
            }

            if (ch == '\\' && i + 1 < str.length) {
                val next = str[i + 1]
                when (next) {
                    '|' -> { out.append('|'); i += 2; atPatternStart = true; continue }
                    '(' -> { out.append('('); i += 2; atPatternStart = true; continue }
                    ')' -> { out.append(')'); i += 2; atPatternStart = false; continue }
                    '{' -> {
                        val remaining = str.substring(i)
                        val m = Regex("^\\\\\\{(\\d+)(,(\\d*)?)?\\\\\\}").find(remaining)
                        if (m != null) {
                            val min = m.groupValues[1]
                            val hasComma = m.groupValues[2].isNotEmpty()
                            val max = m.groupValues[3]
                            out.append(if (hasComma) "{$min,$max}" else "{$min}")
                            i += m.value.length
                        } else {
                            out.append("\\{"); i += 2
                        }
                        atPatternStart = false
                        continue
                    }
                    '}' -> { out.append("\\}"); i += 2; atPatternStart = false; continue }
                    else -> { out.append(ch); out.append(next); i += 2; atPatternStart = false; continue }
                }
            }

            if (ch == '*' && atPatternStart) {
                out.append("\\*"); i++; continue
            }
            if (ch == '^') {
                if (atPatternStart) { out.append('^'); i++; continue }
                out.append("\\^"); i++; continue
            }
            if (ch == '$') {
                val atEnd = i == str.length - 1
                val beforeGroupEnd = i + 2 < str.length && str[i + 1] == '\\' && str[i + 2] == ')'
                out.append(if (atEnd || beforeGroupEnd) "$" else "\\$")
                i++
                atPatternStart = false
                continue
            }
            if (ch == '+' || ch == '?' || ch == '|' || ch == '(' || ch == ')' || ch == '{' || ch == '}') {
                out.append('\\').append(ch)
            } else {
                out.append(ch)
            }
            i++
            atPatternStart = false
        }

        return out.toString()
    }

    private fun compilePattern(
        pattern: String,
        mode: String,
        ignoreCase: Boolean,
        wholeWord: Boolean,
        lineRegexp: Boolean,
    ): Pattern {
        var p = when (mode) {
            "fixed" -> escapeRegexMeta(pattern)
            "basic" -> breToEre(pattern)
            else -> pattern // extended / perl
        }
        if (wholeWord) p = "\\b(?:$p)\\b"
        if (lineRegexp) p = "^(?:$p)$"
        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE else 0
        return try {
            Pattern.compile(p, flags)
        } catch (e: PatternSyntaxException) {
            throw RuntimeException("invalid regex", e)
        }
    }

    private fun searchContent(
        content: String,
        regex: Pattern,
        showLineNumbers: Boolean,
        countOnly: Boolean,
        filename: String,
        onlyMatching: Boolean,
        invertMatch: Boolean,
        beforeContext: Int,
        afterContext: Int,
        maxCount: Int,
    ): SearchResult {
        val lines = content.split("\n")
        val sb = StringBuilder()
        var matchCount = 0

        for (lineIdx in lines.indices) {
            val line = lines[lineIdx]
            // line containing the trailing delimiter after final newline
            if (lineIdx == lines.size - 1 && line.isEmpty() && content.endsWith("\n")) break

            val m = regex.matcher(line)
            val matched = if (invertMatch) !m.find() else m.find()

            if (matched) {
                matchCount++
                if (maxCount > 0 && matchCount > maxCount) break

                if (countOnly) {
                    // handled at caller for per-file counts; produce nothing here
                } else if (onlyMatching) {
                    m.reset()
                    while (m.find()) {
                        val match = m.group()
                        if (match.isEmpty()) continue
                        appendMatchLine(sb, if (filename.isNotEmpty()) "$filename:" else "", showLineNumbers, lineIdx + 1, match, countOnly)
                    }
                } else {
                    appendMatchLine(sb, if (filename.isNotEmpty()) "$filename:" else "", showLineNumbers, lineIdx + 1, line, countOnly)
                }
            }
        }

        if (countOnly) {
            val prefix = if (filename.isNotEmpty()) "$filename:" else ""
            // Count matching lines
            var count = 0
            for (lineIdx in lines.indices) {
                val line = lines[lineIdx]
                if (lineIdx == lines.size - 1 && line.isEmpty() && content.endsWith("\n")) break
                val m = regex.matcher(line)
                val matched = if (invertMatch) !m.find() else m.find()
                if (matched) count++
            }
            sb.append("$prefix$count\n")
            return SearchResult(sb.toString(), matchCount > 0)
        }

        return SearchResult(sb.toString(), matchCount > 0)
    }

    private fun appendMatchLine(
        sb: StringBuilder,
        filenamePrefix: String,
        showLineNumbers: Boolean,
        lineNumber: Int,
        text: String,
        countOnly: Boolean,
    ) {
        sb.append(filenamePrefix)
        if (showLineNumbers) sb.append("$lineNumber:")
        sb.append(text).append("\n")
    }

    // ---- glob expansion (simplified but faithful where it matters) ----

    private fun matchGlob(name: String, pattern: String): Boolean {
        var clean = pattern
        if ((clean.startsWith("\"") && clean.endsWith("\"")) || (clean.startsWith("'") && clean.endsWith("'"))) {
            clean = clean.substring(1, clean.length - 1)
        }
        val regex = globToRegex(clean)
        return regex.matcher(name).matches()
    }

    private fun globToRegex(pattern: String): Pattern {
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
        return Pattern.compile(sb.toString())
    }

    private fun expandGlobPattern(pattern: String, ctx: CommandContext): List<FileEntry> {
        val lastSlash = pattern.lastIndexOf("/")
        val dirPath: String
        val globPart: String
        if (lastSlash == -1) {
            dirPath = ctx.cwd
            globPart = pattern
        } else {
            dirPath = pattern.substring(0, lastSlash).ifEmpty { "/" }
            globPart = pattern.substring(lastSlash + 1)
        }
        val fullDirPath = ctx.fs.resolvePath(ctx.cwd, dirPath)
        val result = ArrayList<FileEntry>()
        try {
            val entries = ctx.fs.readdirWithFileTypes(fullDirPath)
            for (entry in entries) {
                if (matchGlob(entry.name, globPart)) {
                    val fullPath = if (lastSlash == -1) entry.name else "$dirPath/${entry.name}"
                    result.add(FileEntry(fullPath, isFile = entry.isFile))
                }
            }
        } catch (_: Exception) {
            // dir doesn't exist -> empty
        }
        return result.sortedBy { it.path }
    }

    private fun expandRecursive(
        path: String,
        ctx: CommandContext,
        includePatterns: List<String>,
        excludePatterns: List<String>,
        excludeDirPatterns: List<String>,
    ): List<FileEntry> {
        val result = ArrayList<FileEntry>()
        expandRecursiveInner(path, ctx, includePatterns, excludePatterns, excludeDirPatterns, result, 0)
        return result
    }

    private fun expandRecursiveInner(
        path: String,
        ctx: CommandContext,
        includePatterns: List<String>,
        excludePatterns: List<String>,
        excludeDirPatterns: List<String>,
        result: MutableList<FileEntry>,
        depth: Int,
    ) {
        if (depth >= 256) return
        val fullPath = ctx.fs.resolvePath(ctx.cwd, path)
        try {
            val stat = ctx.fs.stat(fullPath)
            if (stat.isFile) {
                val basename = path.split("/").lastOrNull() ?: path
                if (excludePatterns.any { matchGlob(basename, it) }) return
                if (includePatterns.isNotEmpty() && !includePatterns.any { matchGlob(basename, it) }) return
                result.add(FileEntry(path, isFile = true))
                return
            }
            if (!stat.isDirectory) return

            val dirName = path.split("/").lastOrNull() ?: path
            if (excludeDirPatterns.any { matchGlob(dirName, it) }) return

            val entries = ctx.fs.readdirWithFileTypes(fullPath)
            for (entry in entries) {
                if (entry.name.startsWith(".")) continue
                val entryPath = if (path == ".") entry.name else "$path/${entry.name}"
                expandRecursiveInner(entryPath, ctx, includePatterns, excludePatterns, excludeDirPatterns, result, depth + 1)
            }
        } catch (_: Exception) {
            // ignore
        }
    }
}
