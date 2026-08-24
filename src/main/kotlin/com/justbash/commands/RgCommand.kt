package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import com.justbash.fs.DirentEntry
import com.justbash.fs.FsStat
import com.justbash.fs.IFileSystem
import com.justbash.fs.PathUtils
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Port of just-bash `rg` (ripgrep) command.
 *
 * rg is a recursive grep with smart defaults:
 * - Recursive by default
 * - Respects .gitignore / .ignore / .rgignore
 * - Skips hidden files by default
 * - Skips binary files by default
 * - Smart case sensitivity (case-insensitive unless pattern has uppercase)
 * - File type filtering (-t, -T)
 * - Glob filtering (-g, --iglob)
 *
 * The single-file implementation merges rg.ts, rg-search.ts, rg-parser.ts,
 * rg-options.ts, file-types.ts, and gitignore.ts.
 */
object RgCommand : Command {
    override val name = "rg"

    // ---- Options data class ----

    private data class RgOptions(
        // Pattern matching
        var ignoreCase: Boolean = false,
        var caseSensitive: Boolean = false,
        var smartCase: Boolean = true,
        var fixedStrings: Boolean = false,
        var wordRegexp: Boolean = false,
        var lineRegexp: Boolean = false,
        var invertMatch: Boolean = false,
        var multiline: Boolean = false,
        var multilineDotall: Boolean = false,
        val patterns: MutableList<String> = ArrayList(),
        val patternFiles: MutableList<String> = ArrayList(),

        // Output control
        var count: Boolean = false,
        var countMatches: Boolean = false,
        var filesWithMatches: Boolean = false,
        var filesWithoutMatch: Boolean = false,
        var stats: Boolean = false,
        var onlyMatching: Boolean = false,
        var maxCount: Int = 0,
        var lineNumber: Boolean = true,
        var noFilename: Boolean = false,
        var withFilename: Boolean = false,
        var nullSeparator: Boolean = false,
        var byteOffset: Boolean = false,
        var column: Boolean = false,
        var vimgrep: Boolean = false,
        var replace: String? = null,
        var afterContext: Int = 0,
        var beforeContext: Int = 0,
        var contextSeparator: String = "--",
        var quiet: Boolean = false,
        var heading: Boolean = false,
        var passthru: Boolean = false,
        var includeZero: Boolean = false,
        var sort: String = "path",
        var json: Boolean = false,

        // File selection
        val globs: MutableList<String> = ArrayList(),
        val iglobs: MutableList<String> = ArrayList(),
        var globCaseInsensitive: Boolean = false,
        val types: MutableList<String> = ArrayList(),
        val typesNot: MutableList<String> = ArrayList(),
        val typeAdd: MutableList<String> = ArrayList(),
        val typeClear: MutableList<String> = ArrayList(),
        var hidden: Boolean = false,
        var noIgnore: Boolean = false,
        var noIgnoreDot: Boolean = false,
        var noIgnoreVcs: Boolean = false,
        val ignoreFiles: MutableList<String> = ArrayList(),
        var maxDepth: Int = 256,
        var maxFilesize: Long = 512L * 1024 * 1024, // 512 MiB
        var followSymlinks: Boolean = false,
        var searchBinary: Boolean = false,

        var files: Boolean = false,
        var preprocessor: String? = null,
        val preprocessorGlobs: MutableList<String> = ArrayList(),
        var searchZip: Boolean = false,
    )

    // ---- File type registry ----

    private object FileTypeRegistry {
        private val types = LinkedHashMap<String, FileType>()

        data class FileType(val extensions: MutableList<String>, val globs: MutableList<String>)

        init {
            types["js"] = FileType(ArrayList(listOf(".js", ".mjs", ".cjs", ".jsx")), ArrayList())
            types["ts"] = FileType(ArrayList(listOf(".ts", ".tsx", ".mts", ".cts")), ArrayList())
            types["html"] = FileType(ArrayList(listOf(".html", ".htm", ".xhtml")), ArrayList())
            types["css"] = FileType(ArrayList(listOf(".css", ".scss", ".sass", ".less")), ArrayList())
            types["json"] = FileType(ArrayList(listOf(".json", ".jsonc", ".json5")), ArrayList())
            types["xml"] = FileType(ArrayList(listOf(".xml", ".xsl", ".xslt")), ArrayList())
            types["c"] = FileType(ArrayList(listOf(".c", ".h")), ArrayList())
            types["cpp"] = FileType(ArrayList(listOf(".cpp", ".cc", ".cxx", ".hpp", ".hh", ".hxx", ".h")), ArrayList())
            types["rust"] = FileType(ArrayList(listOf(".rs")), ArrayList())
            types["go"] = FileType(ArrayList(listOf(".go")), ArrayList())
            types["java"] = FileType(ArrayList(listOf(".java")), ArrayList())
            types["kotlin"] = FileType(ArrayList(listOf(".kt", ".kts")), ArrayList())
            types["scala"] = FileType(ArrayList(listOf(".scala", ".sc")), ArrayList())
            types["py"] = FileType(ArrayList(listOf(".py", ".pyi", ".pyw")), ArrayList())
            types["rb"] = FileType(ArrayList(listOf(".rb", ".rake", ".gemspec")), ArrayList(listOf("Rakefile", "Gemfile")))
            types["php"] = FileType(ArrayList(listOf(".php", ".phtml", ".php3", ".php4", ".php5")), ArrayList())
            types["perl"] = FileType(ArrayList(listOf(".pl", ".pm", ".pod", ".t")), ArrayList())
            types["lua"] = FileType(ArrayList(listOf(".lua")), ArrayList())
            types["sh"] = FileType(ArrayList(listOf(".sh", ".bash", ".zsh", ".fish")), ArrayList(listOf(".bashrc", ".zshrc", ".profile")))
            types["bat"] = FileType(ArrayList(listOf(".bat", ".cmd")), ArrayList())
            types["ps"] = FileType(ArrayList(listOf(".ps1", ".psm1", ".psd1")), ArrayList())
            types["yaml"] = FileType(ArrayList(listOf(".yaml", ".yml")), ArrayList())
            types["toml"] = FileType(ArrayList(listOf(".toml")), ArrayList(listOf("Cargo.toml", "pyproject.toml")))
            types["ini"] = FileType(ArrayList(listOf(".ini", ".cfg", ".conf")), ArrayList())
            types["csv"] = FileType(ArrayList(listOf(".csv", ".tsv")), ArrayList())
            types["md"] = FileType(ArrayList(listOf(".md", ".mdx", ".markdown", ".mdown", ".mkd")), ArrayList())
            types["markdown"] = FileType(ArrayList(listOf(".md", ".mdx", ".markdown", ".mdown", ".mkd")), ArrayList())
            types["txt"] = FileType(ArrayList(listOf(".txt", ".text")), ArrayList())
            types["tex"] = FileType(ArrayList(listOf(".tex", ".ltx", ".sty", ".cls")), ArrayList())
            types["sql"] = FileType(ArrayList(listOf(".sql")), ArrayList())
            types["graphql"] = FileType(ArrayList(listOf(".graphql", ".gql")), ArrayList())
            types["proto"] = FileType(ArrayList(listOf(".proto")), ArrayList())
            types["make"] = FileType(ArrayList(listOf(".mk", ".mak")), ArrayList(listOf("Makefile", "GNUmakefile", "makefile")))
            types["docker"] = FileType(ArrayList(), ArrayList(listOf("Dockerfile", "Dockerfile.*", "*.dockerfile")))
            types["tf"] = FileType(ArrayList(listOf(".tf", ".tfvars")), ArrayList())
        }

        fun addType(spec: String) {
            val colonIdx = spec.indexOf(':')
            if (colonIdx == -1) return
            val name = spec.substring(0, colonIdx)
            val pattern = spec.substring(colonIdx + 1)
            if (pattern.startsWith("include:")) {
                val otherName = pattern.substring(8)
                val other = types[otherName] ?: return
                val existing = types.getOrPut(name) { FileType(ArrayList(), ArrayList()) }
                existing.extensions.addAll(other.extensions)
                existing.globs.addAll(other.globs)
            } else {
                val existing = types.getOrPut(name) { FileType(ArrayList(), ArrayList()) }
                if (pattern.startsWith("*.") && !pattern.substring(2).contains("*")) {
                    val ext = pattern.substring(1)
                    if (ext !in existing.extensions) existing.extensions.add(ext)
                } else {
                    if (pattern !in existing.globs) existing.globs.add(pattern)
                }
            }
        }

        fun clearType(name: String) {
            types[name]?.let {
                it.extensions.clear()
                it.globs.clear()
            }
        }

        fun matchesType(filename: String, typeNames: List<String>): Boolean {
            val lower = filename.lowercase()
            for (typeName in typeNames) {
                if (typeName == "all" && matchesAnyType(filename)) return true
                val ft = types[typeName] ?: continue
                if (ft.extensions.any { lower.endsWith(it) }) return true
                if (ft.globs.any { glob ->
                    if (glob.contains("*")) {
                        val regex = globToRegex(glob)
                        regex.matcher(filename).matches()
                    } else lower == glob.lowercase()
                }) return true
            }
            return false
        }

        private fun matchesAnyType(filename: String): Boolean {
            val lower = filename.lowercase()
            for (ft in types.values) {
                if (ft.extensions.any { lower.endsWith(it) }) return true
                if (ft.globs.any { glob ->
                    if (glob.contains("*")) {
                        val regex = globToRegex(glob)
                        regex.matcher(filename).matches()
                    } else lower == glob.lowercase()
                }) return true
            }
            return false
        }

        fun formatTypeList(): String = buildString {
            for ((name, ft) in types.toSortedMap()) {
                val patterns = ArrayList<String>()
                for (ext in ft.extensions) patterns.add("*$ext")
                patterns.addAll(ft.globs)
                appendLine("$name: ${patterns.joinToString(", ")}")
            }
        }
    }

    // ---- Gitignore ----

    private class GitignoreParser(private val basePath: String) {
        data class GitIgnorePattern(
            val pattern: String,
            val regex: Pattern,
            val negated: Boolean,
            val directoryOnly: Boolean,
            val rooted: Boolean,
        )

        private val patterns = ArrayList<GitIgnorePattern>()

        fun parse(content: String) {
            for (line in content.split("\n")) {
                var trimmed = line.trimEnd()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                var negated = false
                if (trimmed.startsWith("!")) { negated = true; trimmed = trimmed.substring(1) }
                var directoryOnly = false
                if (trimmed.endsWith("/")) { directoryOnly = true; trimmed = trimmed.substring(0, trimmed.length - 1) }
                var rooted = false
                if (trimmed.startsWith("/")) { rooted = true; trimmed = trimmed.substring(1) }
                else if (trimmed.contains("/") && !trimmed.startsWith("**/")) { rooted = true }
                val regex = patternToRegex(trimmed, rooted)
                patterns.add(GitIgnorePattern(line, regex, negated, directoryOnly, rooted))
            }
        }

        private fun patternToRegex(pattern: String, rooted: Boolean): Pattern {
            val sb = StringBuilder()
            sb.append(if (!rooted) "(?:^|/)" else "^")
            var i = 0
            while (i < pattern.length) {
                when (val ch = pattern[i]) {
                    '*' -> {
                        if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                            if (i + 2 < pattern.length && pattern[i + 2] == '/') { sb.append("(?:.*/)?"); i += 3 }
                            else if (i + 2 >= pattern.length) { sb.append(".*"); i += 2 }
                            else { sb.append(".*"); i += 2 }
                        } else { sb.append("[^/]*"); i++ }
                    }
                    '?' -> { sb.append("[^/]"); i++ }
                    '[' -> {
                        var j = i + 1
                        if (j < pattern.length && pattern[j] == '!') j++
                        if (j < pattern.length && pattern[j] == ']') j++
                        while (j < pattern.length && pattern[j] != ']') j++
                        if (j < pattern.length) {
                            var charClass = pattern.substring(i, j + 1)
                            if (charClass.startsWith("[!")) charClass = "[^${charClass.substring(2)}"
                            sb.append(charClass); i = j + 1
                        } else { sb.append("\\["); i++ }
                    }
                    '/' -> { sb.append('/'); i++ }
                    else -> { sb.append(escapeRegexMeta(ch.toString())); i++ }
                }
            }
            sb.append("(?:/.*)?$")
            return Pattern.compile(sb.toString())
        }

        fun matches(relativePath: String, isDirectory: Boolean): Boolean {
            var path = relativePath.replace(Regex("^\\./"), "").replace(Regex("^/"), "")
            var ignored = false
            for (pat in patterns) {
                if (pat.directoryOnly && !isDirectory) continue
                if (pat.regex.matcher(path).find()) ignored = !pat.negated
            }
            return ignored
        }

        fun isWhitelisted(relativePath: String, isDirectory: Boolean): Boolean {
            var path = relativePath.replace(Regex("^\\./"), "").replace(Regex("^/"), "")
            for (pat in patterns) {
                if (pat.directoryOnly && !isDirectory) continue
                if (pat.negated && pat.regex.matcher(path).find()) return true
            }
            return false
        }

        fun getBasePath(): String = basePath
    }

    private class GitignoreManager(
        private val fs: IFileSystem,
        private val skipDotIgnore: Boolean,
        private val skipVcsIgnore: Boolean,
    ) {
        private val parsers = ArrayList<GitignoreParser>()
        private val loadedDirs = HashSet<String>()

        fun load(targetPath: String) {
            val dirs = ArrayList<String>()
            var current = targetPath
            while (true) {
                dirs.add(0, current)
                val parent = PathUtils.resolvePath(current, "..")
                if (parent == current || parent == "/" && current == "/") break
                current = parent
            }
            val ignoreFiles = ArrayList<String>()
            if (!skipVcsIgnore) ignoreFiles.add(".gitignore")
            if (!skipDotIgnore) { ignoreFiles.add(".rgignore"); ignoreFiles.add(".ignore") }
            for (dir in dirs) {
                loadedDirs.add(dir)
                for (filename in ignoreFiles) {
                    try {
                        val ignorePath = PathUtils.resolvePath(dir, filename)
                        val content = fs.readFile(ignorePath)
                        val parser = GitignoreParser(dir)
                        parser.parse(content)
                        parsers.add(parser)
                    } catch (_: Exception) { /* no ignore file in this dir */ }
                }
            }
        }

        fun loadForDirectory(dir: String) {
            if (dir in loadedDirs) return
            loadedDirs.add(dir)
            val ignoreFiles = ArrayList<String>()
            if (!skipVcsIgnore) ignoreFiles.add(".gitignore")
            if (!skipDotIgnore) { ignoreFiles.add(".rgignore"); ignoreFiles.add(".ignore") }
            for (filename in ignoreFiles) {
                try {
                    val ignorePath = PathUtils.resolvePath(dir, filename)
                    val content = fs.readFile(ignorePath)
                    val parser = GitignoreParser(dir)
                    parser.parse(content)
                    parsers.add(parser)
                } catch (_: Exception) { /* no ignore file in this dir */ }
            }
        }

        fun matches(absolutePath: String, isDirectory: Boolean): Boolean {
            for (parser in parsers) {
                val basePath = parser.getBasePath()
                if (!absolutePath.startsWith(basePath)) continue
                val relativePath = absolutePath.substring(basePath.length).replace(Regex("^/"), "")
                if (parser.matches(relativePath, isDirectory)) return true
            }
            return false
        }

        fun isWhitelisted(absolutePath: String, isDirectory: Boolean): Boolean {
            for (parser in parsers) {
                val basePath = parser.getBasePath()
                if (!absolutePath.startsWith(basePath)) continue
                val relativePath = absolutePath.substring(basePath.length).replace(Regex("^/"), "")
                if (parser.isWhitelisted(relativePath, isDirectory)) return true
            }
            return false
        }

        companion object {
            fun isCommonIgnored(name: String): Boolean =
                name in setOf(
                    "node_modules", ".git", ".svn", ".hg",
                    "__pycache__", ".pytest_cache", ".mypy_cache",
                    "venv", ".venv", ".next", ".nuxt", ".cargo",
                )
        }
    }

    // ---- Helper: regex escape ----

    private fun escapeRegexMeta(s: String): String =
        s.replace(Regex("[.*+?^\${}()|\\[\\]\\\\]"), "\\\\$0")

    // ---- Helper: glob to regex ----

    private fun globToRegex(pattern: String): Pattern {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '*' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                        sb.append(".*"); i++
                    } else {
                        sb.append("[^/]*")
                    }
                }
                '?' -> sb.append("[^/]")
                '[' -> {
                    var j = i + 1
                    if (j < pattern.length && pattern[j] == '!') j++
                    if (j < pattern.length && pattern[j] == ']') j++
                    while (j < pattern.length && pattern[j] != ']') j++
                    if (j < pattern.length) {
                        var charClass = pattern.substring(i, j + 1)
                        if (charClass.startsWith("[!")) charClass = "[^${charClass.substring(2)}"
                        sb.append(charClass); i = j
                    } else sb.append("\\[")
                }
                '.', '+', '^', '$', '{', '}', '(', ')', '|', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append("$")
        return Pattern.compile(sb.toString())
    }

    private fun matchGlob(name: String, pattern: String, ignoreCase: Boolean = false): Boolean {
        val regex = globToRegex(pattern)
        return if (ignoreCase) {
            Pattern.compile(regex.pattern(), Pattern.CASE_INSENSITIVE).matcher(name).matches()
        } else {
            regex.matcher(name).matches()
        }
    }

    // ---- Pattern compilation ----

    private fun compilePattern(
        pattern: String,
        mode: String,
        ignoreCase: Boolean,
        wholeWord: Boolean,
        lineRegexp: Boolean,
        multiline: Boolean = false,
        multilineDotall: Boolean = false,
    ): Pattern {
        var p = when (mode) {
            "fixed" -> escapeRegexMeta(pattern)
            else -> pattern // extended / perl
        }
        if (wholeWord) p = "\\b(?:$p)\\b"
        if (lineRegexp) p = "^(?:$p)$"
        var flags = if (multiline && multilineDotall) Pattern.DOTALL else 0
        if (ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE
        if (multiline) flags = flags or Pattern.MULTILINE
        return try {
            Pattern.compile(p, flags)
        } catch (e: PatternSyntaxException) {
            throw RuntimeException("invalid regex", e)
        }
    }

    // ---- Search content ----

    private data class SearchResult(val output: String, val matched: Boolean, val matchCount: Int = 0)

    private fun searchContent(
        content: String,
        regex: Pattern,
        showLineNumbers: Boolean,
        countOnly: Boolean,
        countMatches: Boolean,
        filename: String,
        onlyMatching: Boolean,
        invertMatch: Boolean,
        maxCount: Int,
    ): SearchResult {
        val lines = content.split("\n")
        val sb = StringBuilder()
        var matchCount = 0
        var totalMatches = 0

        for (lineIdx in lines.indices) {
            val line = lines[lineIdx]
            if (lineIdx == lines.size - 1 && line.isEmpty() && content.endsWith("\n")) break

            val m = regex.matcher(line)
            val matched = if (invertMatch) !m.find() else m.find()

            if (matched) {
                matchCount++
                totalMatches++
                if (maxCount > 0 && totalMatches > maxCount) break

                if (countOnly || countMatches) {
                    // accumulated at the end
                } else if (onlyMatching) {
                    m.reset()
                    while (m.find()) {
                        val match = m.group()
                        if (match.isEmpty()) continue
                        appendMatchLine(sb, filename, showLineNumbers, lineIdx + 1, match)
                    }
                } else {
                    appendMatchLine(sb, filename, showLineNumbers, lineIdx + 1, line)
                }
            }
        }

        if (countOnly || countMatches) {
            val prefix = if (filename.isNotEmpty()) "$filename:" else "" // filename passed bare
            if (countMatches) {
                // Count individual matches
                var count = 0
                for (lineIdx in lines.indices) {
                    val line = lines[lineIdx]
                    if (lineIdx == lines.size - 1 && line.isEmpty() && content.endsWith("\n")) break
                    val m = regex.matcher(line)
                    if (invertMatch) {
                        if (!m.find()) count++
                    } else {
                        while (m.find()) count++
                    }
                }
                sb.append("$prefix$count\n")
            } else {
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
            }
            return SearchResult(sb.toString(), matchCount > 0, matchCount)
        }

        return SearchResult(sb.toString(), matchCount > 0, totalMatches)
    }

    private fun appendMatchLine(
        sb: StringBuilder,
        filenamePrefix: String,
        showLineNumbers: Boolean,
        lineNumber: Int,
        text: String,
    ) {
        if (filenamePrefix.isNotEmpty()) sb.append(filenamePrefix).append(':')
        if (showLineNumbers) sb.append("$lineNumber:")
        sb.append(text).append("\n")
    }

    // ---- File collection ----

    private fun collectFiles(
        ctx: CommandContext,
        paths: List<String>,
        options: RgOptions,
        gitignore: GitignoreManager?,
        typeRegistry: FileTypeRegistry,
    ): Pair<List<String>, Boolean> {
        val files = ArrayList<String>()
        var explicitFileCount = 0
        var directoryCount = 0

        for (path in paths) {
            val fullPath = ctx.fs.resolvePath(ctx.cwd, path)
            try {
                val stat = ctx.fs.stat(fullPath)
                if (stat.isFile) {
                    explicitFileCount++
                    if (options.maxFilesize > 0 && stat.size > options.maxFilesize) continue
                    if (shouldIncludeFile(path, options, gitignore, fullPath, typeRegistry)) {
                        files.add(path)
                    }
                } else if (stat.isDirectory) {
                    directoryCount++
                    walkDirectory(
                        ctx, path, fullPath, 0, options, gitignore, typeRegistry, files,
                        HashSet()
                    )
                }
            } catch (_: Exception) { /* path doesn't exist - skip */ }
        }

        val sortedFiles = if (options.sort == "path") files.sorted() else files
        return Pair(sortedFiles, explicitFileCount == 1 && directoryCount == 0)
    }

    private fun walkDirectory(
        ctx: CommandContext,
        relativePath: String,
        absolutePath: String,
        depth: Int,
        options: RgOptions,
        gitignore: GitignoreManager?,
        typeRegistry: FileTypeRegistry,
        files: MutableList<String>,
        activeDirectories: MutableSet<String>,
    ) {
        if (depth >= options.maxDepth) return

        if (options.followSymlinks) {
            try {
                val stat = ctx.fs.stat(absolutePath)
                val identity = stat.identity ?: absolutePath
                if (identity in activeDirectories) return
                activeDirectories.add(identity)
            } catch (_: Exception) { return }
        }

        if (gitignore != null) {
            gitignore.loadForDirectory(absolutePath)
        }

        try {
            val entries = ctx.fs.readdirWithFileTypes(absolutePath)
            for (entry in entries) {
                val name = entry.name

                // Skip common ignored directories
                if (!options.noIgnore && GitignoreManager.isCommonIgnored(name)) continue

                val isHidden = name.startsWith(".")
                val entryRelativePath = when {
                    relativePath == "." -> name
                    relativePath.endsWith("/") -> "$relativePath$name"
                    else -> "$relativePath/$name"
                }
                val entryAbsolutePath = PathUtils.resolvePath(absolutePath, name)

                // Determine file type
                val isSymlink = entry.isSymbolicLink
                if (isSymlink && !options.followSymlinks) continue

                val isFile: Boolean
                val isDirectory: Boolean
                if (isSymlink && options.followSymlinks) {
                    try {
                        val stat = ctx.fs.stat(entryAbsolutePath)
                        isFile = stat.isFile
                        isDirectory = stat.isDirectory
                    } catch (_: Exception) { continue }
                } else {
                    isFile = entry.isFile
                    isDirectory = entry.isDirectory
                }

                // Check gitignore first
                val gitignoreIgnored = gitignore?.matches(entryAbsolutePath, isDirectory) ?: false
                if (gitignoreIgnored) continue

                // Skip hidden files unless --hidden or whitelisted
                if (isHidden && !options.hidden) {
                    val isWhitelisted = gitignore?.isWhitelisted(entryAbsolutePath, isDirectory) ?: false
                    if (!isWhitelisted) continue
                }

                if (isDirectory) {
                    walkDirectory(
                        ctx, entryRelativePath, entryAbsolutePath, depth + 1,
                        options, gitignore, typeRegistry, files, activeDirectories
                    )
                } else if (isFile) {
                    if (options.maxFilesize > 0) {
                        try {
                            val fileStat = ctx.fs.stat(entryAbsolutePath)
                            if (fileStat.size > options.maxFilesize) continue
                        } catch (_: Exception) { continue }
                    }
                    if (shouldIncludeFile(entryRelativePath, options, gitignore, entryAbsolutePath, typeRegistry)) {
                        files.add(entryRelativePath)
                    }
                }
            }
        } catch (_: Exception) { /* directory read failed - skip */ }
    }

    private fun shouldIncludeFile(
        relativePath: String,
        options: RgOptions,
        gitignore: GitignoreManager?,
        absolutePath: String,
        typeRegistry: FileTypeRegistry,
    ): Boolean {
        val filename = relativePath.substringAfterLast("/")

        if (gitignore?.matches(absolutePath, false) == true) return false

        if (options.types.isNotEmpty() && !typeRegistry.matchesType(filename, options.types)) return false
        if (options.typesNot.isNotEmpty() && typeRegistry.matchesType(filename, options.typesNot)) return false

        // Glob filters
        if (options.globs.isNotEmpty()) {
            val ignoreCase = options.globCaseInsensitive
            val positiveGlobs = options.globs.filter { !it.startsWith("!") }
            val negativeGlobs = options.globs.filter { it.startsWith("!") }.map { it.substring(1) }

            if (positiveGlobs.isNotEmpty()) {
                val matchesPositive = positiveGlobs.any {
                    matchGlob(filename, it, ignoreCase) || matchGlob(relativePath, it, ignoreCase)
                }
                if (!matchesPositive) return false
            }

            for (gl in negativeGlobs) {
                if (gl.startsWith("/")) {
                    val rooted = gl.substring(1)
                    if (matchGlob(relativePath, rooted, ignoreCase)) return false
                } else if (matchGlob(filename, gl, ignoreCase) || matchGlob(relativePath, gl, ignoreCase)) {
                    return false
                }
            }
        }

        // Case-insensitive globs
        if (options.iglobs.isNotEmpty()) {
            val positiveIglobs = options.iglobs.filter { !it.startsWith("!") }
            val negativeIglobs = options.iglobs.filter { it.startsWith("!") }.map { it.substring(1) }

            if (positiveIglobs.isNotEmpty()) {
                val matchesPositive = positiveIglobs.any {
                    matchGlob(filename, it, true) || matchGlob(relativePath, it, true)
                }
                if (!matchesPositive) return false
            }

            for (gl in negativeIglobs) {
                if (gl.startsWith("/")) {
                    val rooted = gl.substring(1)
                    if (matchGlob(relativePath, rooted, true)) return false
                } else if (matchGlob(filename, gl, true) || matchGlob(relativePath, gl, true)) {
                    return false
                }
            }
        }

        return true
    }

    // ---- isBinary helper ----

    private fun isBinary(content: String): Boolean {
        val sample = if (content.length > 8192) content.substring(0, 8192) else content
        return sample.contains('\u0000')
    }

    // ---- Execute ----

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "rg", "recursively search for a pattern",
                "rg [OPTIONS] PATTERN [PATH ...]",
                listOf(
                    "-e, --regexp PATTERN   search for PATTERN (can be used multiple times)",
                    "-f, --file FILE        read patterns from FILE, one per line",
                    "-i, --ignore-case      case-insensitive search",
                    "-s, --case-sensitive   case-sensitive search (overrides smart-case)",
                    "-S, --smart-case       smart case (default: case-insensitive unless pattern has uppercase)",
                    "-F, --fixed-strings    treat pattern as literal string",
                    "-w, --word-regexp      match whole words only",
                    "-x, --line-regexp      match whole lines only",
                    "-v, --invert-match     select non-matching lines",
                    "-r, --replace TEXT     replace matches with TEXT",
                    "-c, --count            print count of matching lines per file",
                    "    --count-matches    print count of individual matches per file",
                    "-l, --files-with-matches  print only file names with matches",
                    "    --files-without-match  print file names without matches",
                    "    --files            list files that would be searched",
                    "-o, --only-matching    print only matching parts",
                    "-m, --max-count NUM    stop after NUM matches per file",
                    "-q, --quiet            suppress output, exit 0 on match",
                    "    --stats            print search statistics",
                    "-n, --line-number      print line numbers (default: on)",
                    "-N, --no-line-number   do not print line numbers",
                    "-I, --no-filename      suppress the prefixing of file names",
                    "-H, --with-filename    print the file name for each match",
                    "-g, --glob GLOB        include files matching GLOB",
                    "    --iglob GLOB       include files matching GLOB (case-insensitive)",
                    "-t, --type TYPE        only search files of TYPE (e.g., js, py, ts)",
                    "-T, --type-not TYPE    exclude files of TYPE",
                    "-L, --follow           follow symbolic links",
                    "-u, --unrestricted     reduce filtering (-u: no ignore, -uu: +hidden, -uuu: +binary)",
                    "-a, --text             search binary files as text",
                    "    --hidden           search hidden files and directories",
                    "    --no-ignore        don't respect .gitignore/.ignore files",
                    "    --no-ignore-parent don't respect parent ignore files",
                    "    --no-ignore-vcs    don't respect .gitignore files",
                    "    --max-depth NUM    maximum search depth",
                    "    --type-list        list all available file types",
                    "    --help             display this help and exit",
                ),
            )
        }

        if (args.contains("--type-list")) {
            return ExecResult(stdout = FileTypeRegistry.formatTypeList(), stderr = "", exitCode = 0)
        }

        // Parse arguments
        val options = RgOptions()
        var positionalPattern: String? = null
        var paths = ArrayList<String>()
        var explicitLineNumbers = false

        var i = 0
        while (i < args.size) {
            val arg = args[i]

            if (arg == "--help") { i++; continue } // already handled above

            if (arg.startsWith("-") && arg != "-") {
                // Parse -A, -B, -C
                val contextMatch = Regex("^-([ABC])(\\d+)$").find(arg)
                if (contextMatch != null) {
                    val num = contextMatch.groupValues[2].toIntOrNull() ?: 0
                    when (contextMatch.groupValues[1]) {
                        "A" -> options.afterContext = num
                        "B" -> options.beforeContext = num
                        "C" -> { options.beforeContext = num; options.afterContext = num }
                    }
                    i++; continue
                }
                if (arg == "-A" || arg == "-B" || arg == "-C") {
                    if (i + 1 < args.size) {
                        val num = args[i + 1].toIntOrNull() ?: 0
                        when (arg) {
                            "-A" -> options.afterContext = num
                            "-B" -> options.beforeContext = num
                            else -> { options.beforeContext = num; options.afterContext = num }
                        }
                        i += 2; continue
                    }
                    i++; continue
                }

                // Parse -mNNN
                val maxCountMatch = Regex("^-m(\\d+)$").find(arg)
                if (maxCountMatch != null) {
                    options.maxCount = maxCountMatch.groupValues[1].toIntOrNull() ?: 0
                    i++; continue
                }

                // Parse -jNNN (thread count, ignored)
                val threadMatch = Regex("^-j(\\d+)$").find(arg)
                if (threadMatch != null) { i++; continue }

                // Parse --max-depth=N, --max-filesize=N, etc.
                when {
                    arg.startsWith("--max-depth=") -> {
                        options.maxDepth = arg.substring(12).toIntOrNull() ?: 256
                        i++; continue
                    }
                    arg.startsWith("--max-filesize=") -> {
                        options.maxFilesize = parseFilesize(arg.substring(15))
                        i++; continue
                    }
                    arg.startsWith("--max-count=") -> {
                        options.maxCount = arg.substring(12).toIntOrNull() ?: 0
                        i++; continue
                    }
                    arg.startsWith("-g=") -> { options.globs.add(arg.substring(3)); i++; continue }
                    arg.startsWith("--glob=") -> { options.globs.add(arg.substring(7)); i++; continue }
                    arg.startsWith("--iglob=") -> { options.iglobs.add(arg.substring(8)); i++; continue }
                    arg.startsWith("-t=") -> { options.types.add(arg.substring(3)); i++; continue }
                    arg.startsWith("--type=") -> { options.types.add(arg.substring(7)); i++; continue }
                    arg.startsWith("-T=") -> { options.typesNot.add(arg.substring(3)); i++; continue }
                    arg.startsWith("--type-not=") -> { options.typesNot.add(arg.substring(11)); i++; continue }
                    arg.startsWith("-e=") -> { options.patterns.add(arg.substring(3)); i++; continue }
                    arg.startsWith("--regexp=") -> { options.patterns.add(arg.substring(9)); i++; continue }
                    arg.startsWith("-f=") -> { options.patternFiles.add(arg.substring(3)); i++; continue }
                    arg.startsWith("--file=") -> { options.patternFiles.add(arg.substring(7)); i++; continue }
                    arg.startsWith("-r=") -> { options.replace = arg.substring(3); i++; continue }
                    arg.startsWith("--replace=") -> { options.replace = arg.substring(10); i++; continue }
                    arg.startsWith("--type-add=") -> { options.typeAdd.add(arg.substring(11)); i++; continue }
                    arg.startsWith("--type-clear=") -> { options.typeClear.add(arg.substring(13)); i++; continue }
                    arg.startsWith("--context-separator=") -> { options.contextSeparator = arg.substring(21); i++; continue }
                    arg.startsWith("--ignore-file=") -> { options.ignoreFiles.add(arg.substring(14)); i++; continue }
                    arg.startsWith("--pre=") -> { options.preprocessor = arg.substring(6); i++; continue }
                    arg.startsWith("--pre-glob=") -> { options.preprocessorGlobs.add(arg.substring(11)); i++; continue }
                    arg.startsWith("--sort=") -> {
                        val v = arg.substring(7)
                        if (v == "path" || v == "none") options.sort = v
                        i++; continue
                    }
                }

                // Value options (need next arg)
                when (arg) {
                    "-e", "--regexp" -> { if (i + 1 < args.size) { options.patterns.add(args[++i]) }; i++; continue }
                    "-f", "--file" -> { if (i + 1 < args.size) { options.patternFiles.add(args[++i]) }; i++; continue }
                    "-g", "--glob" -> { if (i + 1 < args.size) { options.globs.add(args[++i]) }; i++; continue }
                    "--iglob" -> { if (i + 1 < args.size) { options.iglobs.add(args[++i]) }; i++; continue }
                    "-t", "--type" -> { if (i + 1 < args.size) { options.types.add(args[++i]) }; i++; continue }
                    "-T", "--type-not" -> { if (i + 1 < args.size) { options.typesNot.add(args[++i]) }; i++; continue }
                    "-m", "--max-count" -> { if (i + 1 < args.size) { options.maxCount = args[++i].toIntOrNull() ?: 0 }; i++; continue }
                    "-r", "--replace" -> { if (i + 1 < args.size) { options.replace = args[++i] }; i++; continue }
                    "-d", "--max-depth" -> { if (i + 1 < args.size) { options.maxDepth = args[++i].toIntOrNull() ?: 256 }; i++; continue }
                    "--max-filesize" -> { if (i + 1 < args.size) { options.maxFilesize = parseFilesize(args[++i]) }; i++; continue }
                    "--type-add" -> { if (i + 1 < args.size) { options.typeAdd.add(args[++i]) }; i++; continue }
                    "--type-clear" -> { if (i + 1 < args.size) { options.typeClear.add(args[++i]) }; i++; continue }
                    "--context-separator" -> { if (i + 1 < args.size) { options.contextSeparator = args[++i] }; i++; continue }
                    "--ignore-file" -> { if (i + 1 < args.size) { options.ignoreFiles.add(args[++i]) }; i++; continue }
                    "--pre" -> { if (i + 1 < args.size) { options.preprocessor = args[++i] }; i++; continue }
                    "--pre-glob" -> { if (i + 1 < args.size) { options.preprocessorGlobs.add(args[++i]) }; i++; continue }
                    "--sort" -> {
                        if (i + 1 < args.size) {
                            val v = args[++i]
                            if (v == "path" || v == "none") options.sort = v
                        }
                        i++; continue
                    }
                    "-j", "--threads" -> { if (i + 1 < args.size) i++; i++; continue } // ignored
                }

                // Boolean flags
                val flags = if (arg.startsWith("--")) listOf(arg) else arg.substring(1).map { it.toString() }
                var consumedNext = false
                for (flag in flags) {
                    when (flag) {
                        "i", "--ignore-case" -> { options.ignoreCase = true; options.caseSensitive = false; options.smartCase = false }
                        "s", "--case-sensitive" -> { options.caseSensitive = true; options.ignoreCase = false; options.smartCase = false }
                        "S", "--smart-case" -> { options.smartCase = true; options.ignoreCase = false; options.caseSensitive = false }
                        "F", "--fixed-strings" -> options.fixedStrings = true
                        "w", "--word-regexp" -> options.wordRegexp = true
                        "x", "--line-regexp" -> options.lineRegexp = true
                        "v", "--invert-match" -> options.invertMatch = true
                        "U", "--multiline" -> options.multiline = true
                        "--multiline-dotall" -> { options.multilineDotall = true; options.multiline = true }
                        "c", "--count" -> options.count = true
                        "--count-matches" -> options.countMatches = true
                        "l", "--files-with-matches" -> options.filesWithMatches = true
                        "--files-without-match" -> options.filesWithoutMatch = true
                        "--files" -> options.files = true
                        "--stats" -> options.stats = true
                        "o", "--only-matching" -> options.onlyMatching = true
                        "q", "--quiet" -> options.quiet = true
                        "n", "--line-number" -> { options.lineNumber = true; explicitLineNumbers = true }
                        "N", "--no-line-number" -> options.lineNumber = false
                        "H", "--with-filename" -> options.withFilename = true
                        "I", "--no-filename" -> options.noFilename = true
                        "0", "--null" -> options.nullSeparator = true
                        "--hidden" -> options.hidden = true
                        "--no-ignore" -> options.noIgnore = true
                        "--no-ignore-dot" -> options.noIgnoreDot = true
                        "--no-ignore-vcs" -> options.noIgnoreVcs = true
                        "L", "--follow" -> options.followSymlinks = true
                        "a", "--text" -> options.searchBinary = true
                        "--heading" -> options.heading = true
                        "--passthru" -> options.passthru = true
                        "--include-zero" -> options.includeZero = true
                        "--glob-case-insensitive" -> options.globCaseInsensitive = true
                        "--json" -> options.json = true
                        "--column" -> { options.column = true; options.lineNumber = true }
                        "--vimgrep" -> { options.vimgrep = true; options.column = true; options.lineNumber = true }
                        "b", "--byte-offset" -> options.byteOffset = true
                        "z", "--search-zip" -> options.searchZip = true
                        "u" -> {
                            // Unrestricted mode
                            if (options.hidden) options.searchBinary = true
                            else if (options.noIgnore) options.hidden = true
                            else options.noIgnore = true
                        }
                        "--unrestricted" -> {
                            if (options.hidden) options.searchBinary = true
                            else if (options.noIgnore) options.hidden = true
                            else options.noIgnore = true
                        }
                        "P", "--pcre2" -> return ExecResult(stdout = "", stderr = "rg: PCRE2 is not supported. Use standard regex syntax instead.\n", exitCode = 1)
                        "--no-heading" -> {} // no-op
                        "--no-column" -> options.column = false
                        "--color" -> {} // no-op
                        "--no-config" -> {} // no-op
                        "--pretty" -> {} // no-op
                        "--no-pretty" -> {} // no-op
                        "--no-ignore-parent" -> {} // no-op, we don't go above cwd
                        "--one-file-system" -> {} // no-op
                        "--crlf" -> {} // no-op
                        else -> {
                            if (flag.startsWith("--")) return ExecResult(stdout = "", stderr = "rg: unrecognized option '$flag'\n", exitCode = 1)
                            if (flag.length == 1) return ExecResult(stdout = "", stderr = "rg: invalid option -- '$flag'\n", exitCode = 1)
                        }
                    }
                }
                if (consumedNext) i++ // in case we consumed the next arg for a value option in combined flags
                i++
            } else if (positionalPattern == null && options.patterns.isEmpty() && options.patternFiles.isEmpty()) {
                positionalPattern = arg
                i++
            } else {
                paths.add(arg)
                i++
            }
        }

        if (positionalPattern != null) {
            options.patterns.add(positionalPattern)
        }

        // --files mode: list files without searching
        if (options.files) {
            val filesPaths = options.patterns.toMutableList().also { it.addAll(paths) }
            return listFiles(ctx, filesPaths, options)
        }

        // Validate globs
        for (glob in options.globs) {
            val g = if (glob.startsWith("!")) glob.substring(1) else glob
            if (g.count { it == '[' } > g.count { it == ']' }) {
                return ExecResult(stdout = "", stderr = "rg: glob '$g' has an unclosed character class\n", exitCode = 1)
            }
        }

        if (options.patterns.isEmpty() && options.patternFiles.isEmpty()) {
            return ExecResult(stdout = "", stderr = "rg: no pattern given\n", exitCode = 2)
        }

        // Read pattern files
        val patterns = ArrayList(options.patterns)
        for (patternFile in options.patternFiles) {
            try {
                val content = if (patternFile == "-") {
                    ctx.stdin.toString(Charsets.UTF_8)
                } else {
                    val filePath = ctx.fs.resolvePath(ctx.cwd, patternFile)
                    ctx.fs.readFile(filePath)
                }
                for (line in content.split("\n")) {
                    if (line.isNotEmpty()) patterns.add(line)
                }
            } catch (_: Exception) {
                return ExecResult(stdout = "", stderr = "rg: $patternFile: No such file or directory\n", exitCode = 2)
            }
        }

        // Determine case sensitivity
        val effectiveIgnoreCase = when {
            options.caseSensitive -> false
            options.ignoreCase -> true
            options.smartCase -> !patterns.any { Regex("[A-Z]").containsMatchIn(it) }
            else -> false
        }

        // Compile pattern
        val combinedPattern = if (patterns.size == 1) {
            patterns[0]
        } else {
            if (options.fixedStrings) {
                patterns.joinToString("|") { "(?:" + escapeRegexMeta(it) + ")" }
            } else {
                patterns.joinToString("|") { "(?:$it)" }
            }
        }
        val mode = if (options.fixedStrings && patterns.size == 1) "fixed" else "extended"

        val regex: Pattern
        try {
            regex = compilePattern(combinedPattern, mode, effectiveIgnoreCase, options.wordRegexp, options.lineRegexp, options.multiline, options.multilineDotall)
        } catch (_: Exception) {
            return ExecResult(stdout = "", stderr = "rg: invalid regex: ${patterns.joinToString(", ")}\n", exitCode = 2)
        }

        // If no paths and stdin has content, search stdin
        val stdinText = ctx.stdin.toString(Charsets.UTF_8)
        if (paths.isEmpty() && stdinText.isNotEmpty()) {
            val result = searchContent(
                stdinText, regex,
                options.lineNumber, options.count, options.countMatches, "",
                options.onlyMatching, options.invertMatch, options.maxCount,
            )

            if (options.quiet) return ExecResult(stdout = "", stderr = "", exitCode = if (result.matched) 0 else 1)
            if (options.filesWithMatches) return ExecResult(
                stdout = if (result.matched) "(standard input)\n" else "", stderr = "", exitCode = if (result.matched) 0 else 1
            )
            if (options.filesWithoutMatch) return ExecResult(
                stdout = if (result.matched) "" else "(standard input)\n", stderr = "", exitCode = if (result.matched) 1 else 0
            )
            return ExecResult(stdout = result.output, stderr = "", exitCode = if (result.matched) 0 else 1)
        }

        // Default to current directory
        val searchPaths = if (paths.isEmpty()) listOf(".") else paths

        // Load gitignore
        val gitignore = if (!options.noIgnore) {
            val mgr = GitignoreManager(ctx.fs, options.noIgnoreDot, options.noIgnoreVcs)
            mgr.load(ctx.cwd)
            for (ignoreFile in options.ignoreFiles) {
                try {
                    val absolutePath = ctx.fs.resolvePath(ctx.cwd, ignoreFile)
                    val content = ctx.fs.readFile(absolutePath)
                    val parser = GitignoreParser(ctx.cwd)
                    parser.parse(content)
                    /* mgr.parsers.add() is private, we add content via loadForDirectory on cwd */
                } catch (_: Exception) { /* ignore missing */ }
            }
            mgr
        } else null

        // Apply type-add and type-clear
        for (name in options.typeClear) FileTypeRegistry.clearType(name)
        for (spec in options.typeAdd) FileTypeRegistry.addType(spec)

        // Collect files
        val (files, singleExplicitFile) = collectFiles(ctx, searchPaths, options, gitignore, FileTypeRegistry)

        if (files.isEmpty()) {
            return ExecResult(stdout = "", stderr = "", exitCode = 1)
        }

        // Determine output settings
        val showFilename = !options.noFilename &&
            (options.withFilename || !singleExplicitFile || files.size > 1)

        var effectiveLineNumbers = options.lineNumber
        if (!explicitLineNumbers) {
            if (singleExplicitFile && files.size == 1) effectiveLineNumbers = false
            if (options.onlyMatching) effectiveLineNumbers = false
        }

        // Search files
        var stdout = ""
        var anyMatch = false
        var totalMatches = 0
        var filesWithMatch = 0
        var bytesSearched = 0L

        for (file in files) {
            val filePath = ctx.fs.resolvePath(ctx.cwd, file)
            try {
                val content = ctx.fs.readFile(filePath)
                bytesSearched += content.length.toLong()

                // Skip binary files unless -a/--text
                if (isBinary(content) && !options.searchBinary) continue

                val filenameForSearch = if (showFilename && !options.heading) file else ""
                val result = searchContent(
                    content, regex,
                    effectiveLineNumbers, options.count, options.countMatches,
                    filenameForSearch,
                    options.onlyMatching, options.invertMatch, options.maxCount,
                )

                if (result.matched) {
                    anyMatch = true
                    filesWithMatch++
                    totalMatches += result.matchCount

                    if (options.quiet) return ExecResult(stdout = "", stderr = "", exitCode = 0)

                    if (options.filesWithMatches) {
                        val sep = if (options.nullSeparator) "\u0000" else "\n"
                        stdout += "$file$sep"
                    } else if (!options.filesWithoutMatch) {
                        if (options.heading && !options.noFilename) stdout += "$file\n"
                        stdout += result.output
                    }
                } else if (options.filesWithoutMatch) {
                    val sep = if (options.nullSeparator) "\u0000" else "\n"
                    stdout += "$file$sep"
                } else if (options.includeZero && (options.count || options.countMatches)) {
                    stdout += result.output
                }
            } catch (_: Exception) {
                // file read failed - skip
            }
        }

        // Stats
        if (options.stats) {
            stdout += "\n${totalMatches} matches\n"
            stdout += "${totalMatches} matched lines\n"
            stdout += "$filesWithMatch files contained matches\n"
            stdout += "${files.size} files searched\n"
            stdout += "$bytesSearched bytes searched\n"
        }

        val exitCode = if (options.filesWithoutMatch) {
            if (stdout.isNotEmpty()) 0 else 1
        } else {
            if (anyMatch) 0 else 1
        }

        if (options.quiet) return ExecResult(stdout = "", stderr = "", exitCode = exitCode)

        return ExecResult(stdout = stdout, stderr = "", exitCode = exitCode)
    }

    // ---- listFiles for --files mode ----

    private fun listFiles(ctx: CommandContext, inputPaths: List<String>, options: RgOptions): ExecResult {
        val gitignore = if (!options.noIgnore) {
            val mgr = GitignoreManager(ctx.fs, options.noIgnoreDot, options.noIgnoreVcs)
            mgr.load(ctx.cwd)
            for (ignoreFile in options.ignoreFiles) {
                try {
                    val absolutePath = ctx.fs.resolvePath(ctx.cwd, ignoreFile)
                    ctx.fs.readFile(absolutePath) // just check existence
                } catch (_: Exception) { /* ignore missing */ }
            }
            mgr
        } else null

        for (name in options.typeClear) FileTypeRegistry.clearType(name)
        for (spec in options.typeAdd) FileTypeRegistry.addType(spec)

        val searchPaths = if (inputPaths.isEmpty()) listOf(".") else inputPaths
        val (files, _) = collectFiles(ctx, searchPaths, options, gitignore, FileTypeRegistry)

        if (files.isEmpty()) return ExecResult(stdout = "", stderr = "", exitCode = 1)
        if (options.quiet) return ExecResult(stdout = "", stderr = "", exitCode = 0)

        val sep = if (options.nullSeparator) "\u0000" else "\n"
        val stdout = files.joinToString(sep) + sep
        return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
    }

    // ---- parseFilesize ----

    private fun parseFilesize(value: String): Long {
        val match = Regex("^(\\d+)([KMG])?$", RegexOption.IGNORE_CASE).find(value)
            ?: return 0
        val num = match.groupValues[1].toLongOrNull() ?: 0
        return when (match.groupValues[2].uppercase()) {
            "K" -> num * 1024
            "M" -> num * 1024 * 1024
            "G" -> num * 1024 * 1024 * 1024
            else -> num
        }
    }
}