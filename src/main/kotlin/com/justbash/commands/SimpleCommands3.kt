package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult

/**
 * Ports of nine simple core utilities:
 *
 * - `column`  (column/column.ts)
 * - `comm`    (comm/comm.ts)
 * - `file`    (file/file.ts)
 * - `join`    (join/join.ts)
 * - `od`      (od/od.ts)
 * - `paste`   (paste/paste.ts)
 * - `rev`     (rev/rev.ts)
 * - `strings` (strings/strings.ts)
 * - `tac`     (tac/tac.ts)
 */

// ---------------------------------------------------------------------------
// column
// ---------------------------------------------------------------------------

object ColumnCommand : Command {
    override val name = "column"

    private val defs = mapOf(
        "table" to Args.Def(short = "t", long = "table"),
        "separator" to Args.Def(short = "s", type = "string"),
        "outputSep" to Args.Def(short = "o", type = "string"),
        "width" to Args.Def(short = "c", type = "number", default = 80),
        "noMerge" to Args.Def(short = "n", type = "boolean"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "column", "columnate lists",
                "column [OPTION]... [FILE]...",
                listOf(
                    "-t           Create a table (determine columns from input)",
                    "-s SEP       Input field delimiter (default: whitespace)",
                    "-o SEP       Output field delimiter (default: two spaces)",
                    "-c WIDTH     Output width for fill mode (default: 80)",
                    "-n           Don't merge multiple adjacent delimiters",
                    "    --help   display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("column", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val f = parsed.flags
        val separator = f.string("separator")
        val noMerge = f.bool("noMerge")
        val table = f.bool("table")
        val width = f.number("width") ?: 80
        val outputSep = f.string("outputSep") ?: "  "
        val files = parsed.positional

        if (width <= 0) {
            return ExecResult(stderr = "column: invalid width: $width\n", exitCode = 1)
        }

        var content: String
        if (files.isEmpty()) {
            content = ctx.stdin.toString(Charsets.UTF_8)
        } else {
            val parts = StringBuilder()
            for (file in files) {
                if (file == "-") {
                    parts.append(ctx.stdin.toString(Charsets.UTF_8))
                } else {
                    try {
                        parts.append(ctx.fs.readFile(ctx.fs.resolvePath(ctx.cwd, file)))
                    } catch (_: Exception) {
                        return ExecResult(stderr = "column: $file: No such file or directory\n", exitCode = 1)
                    }
                }
            }
            content = parts.toString()
        }

        if (content.isEmpty() || content.trim().isEmpty()) {
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        val lines = content.split("\n").toMutableList()
        val hasTrailingNewline = content.endsWith("\n") && lines.isNotEmpty() && lines.last() == ""
        if (hasTrailingNewline) lines.removeAt(lines.size - 1)

        val nonEmptyLines = lines.filter { it.trim().isNotEmpty() }

        val output = if (table) {
            val rows = nonEmptyLines.map { splitFields(it, separator, noMerge) }
            formatTable(rows, outputSep)
        } else {
            val items = ArrayList<String>()
            for (line in nonEmptyLines) {
                items.addAll(splitFields(line, separator, noMerge))
            }
            formatFill(items, width, outputSep)
        }

        val stdout = if (output.isEmpty()) "" else output + "\n"
        return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
    }

    private fun splitFields(line: String, separator: String?, noMerge: Boolean): List<String> {
        if (separator != null) {
            val parts = line.split(separator)
            return if (noMerge) parts else parts.filter { it.isNotEmpty() }
        }
        if (noMerge) {
            return line.split(Regex("[ \t]"))
        }
        return line.split(Regex("[ \t]+")).filter { it.isNotEmpty() }
    }

    private fun calculateColumnWidths(rows: List<List<String>>): IntArray {
        val widths = ArrayList<Int>()
        for (row in rows) {
            for (i in row.indices) {
                val w = row[i].length
                while (widths.size <= i) widths.add(0)
                if (w > widths[i]) widths[i] = w
            }
        }
        return widths.toIntArray()
    }

    private fun formatTable(rows: List<List<String>>, outputSep: String): String {
        if (rows.isEmpty()) return ""
        val widths = calculateColumnWidths(rows)
        val sb = StringBuilder()
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            if (rowIndex > 0) sb.append('\n')
            for (i in row.indices) {
                if (i > 0) sb.append(outputSep)
                sb.append(row[i])
                if (i < row.size - 1) sb.append(" ".repeat(widths[i] - row[i].length))
            }
        }
        return sb.toString()
    }

    private fun formatFill(items: List<String>, width: Int, outputSep: String): String {
        if (items.isEmpty()) return ""
        var maxItemWidth = 0
        for (item in items) maxItemWidth = maxOf(maxItemWidth, item.length)
        val sepWidth = outputSep.length
        val columnWidth = maxItemWidth + sepWidth
        val numColumns = maxOf(1, (width + sepWidth) / columnWidth)
        val numRows = (items.size + numColumns - 1) / numColumns
        val sb = StringBuilder()
        for (row in 0 until numRows) {
            if (row > 0) sb.append('\n')
            var emittedCell = false
            for (col in 0 until numColumns) {
                val index = col * numRows + row
                if (index < items.size) {
                    val isLastInRow = col == numColumns - 1 || (col + 1) * numRows + row >= items.size
                    if (emittedCell) sb.append(outputSep)
                    sb.append(items[index])
                    if (!isLastInRow) sb.append(" ".repeat(maxItemWidth - items[index].length))
                    emittedCell = true
                }
            }
        }
        return sb.toString()
    }
}

// ---------------------------------------------------------------------------
// comm
// ---------------------------------------------------------------------------

object CommCommand : Command {
    override val name = "comm"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "comm", "compare two sorted files line by line",
                "comm [OPTION]... FILE1 FILE2",
                listOf(
                    "-1             suppress column 1 (lines unique to FILE1)",
                    "-2             suppress column 2 (lines unique to FILE2)",
                    "-3             suppress column 3 (lines that appear in both files)",
                    "    --help     display this help and exit",
                ),
            )
        }

        var suppress1 = false
        var suppress2 = false
        var suppress3 = false
        val files = ArrayList<String>()

        for (arg in args) {
            when {
                arg == "-1" -> suppress1 = true
                arg == "-2" -> suppress2 = true
                arg == "-3" -> suppress3 = true
                arg == "-12" || arg == "-21" -> { suppress1 = true; suppress2 = true }
                arg == "-13" || arg == "-31" -> { suppress1 = true; suppress3 = true }
                arg == "-23" || arg == "-32" -> { suppress2 = true; suppress3 = true }
                arg == "-123" || arg == "-132" || arg == "-213" || arg == "-231" || arg == "-312" || arg == "-321" -> {
                    suppress1 = true; suppress2 = true; suppress3 = true
                }
                arg.startsWith("-") && arg != "-" -> return unknownOption("comm", arg)
                else -> files.add(arg)
            }
        }

        if (files.size != 2) {
            return ExecResult(
                stderr = "comm: missing operand\nTry 'comm --help' for more information.\n",
                exitCode = 1,
            )
        }

        val readFile: (String) -> String? = { file ->
            if (file == "-") {
                ctx.stdin.toString(Charsets.UTF_8)
            } else {
                try {
                    ctx.fs.readFile(ctx.fs.resolvePath(ctx.cwd, file))
                } catch (_: Exception) {
                    null
                }
            }
        }

        val content1 = readFile(files[0]) ?: return ExecResult(
            stderr = "comm: ${files[0]}: No such file or directory\n", exitCode = 1,
        )
        val content2 = readFile(files[1]) ?: return ExecResult(
            stderr = "comm: ${files[1]}: No such file or directory\n", exitCode = 1,
        )

        val lines1 = content1.split("\n").toMutableList()
        val lines2 = content2.split("\n").toMutableList()
        if (lines1.isNotEmpty() && lines1.last() == "") lines1.removeAt(lines1.size - 1)
        if (lines2.isNotEmpty() && lines2.last() == "") lines2.removeAt(lines2.size - 1)

        val col2Prefix = if (suppress1) "" else "\t"
        val col3Prefix = (if (suppress1) "" else "\t") + (if (suppress2) "" else "\t")

        val output = StringBuilder()
        var i = 0
        var j = 0
        while (i < lines1.size || j < lines2.size) {
            when {
                i >= lines1.size -> {
                    if (!suppress2) output.append(col2Prefix).append(lines2[j]).append('\n')
                    j++
                }
                j >= lines2.size -> {
                    if (!suppress1) output.append(lines1[i]).append('\n')
                    i++
                }
                lines1[i] < lines2[j] -> {
                    if (!suppress1) output.append(lines1[i]).append('\n')
                    i++
                }
                lines1[i] > lines2[j] -> {
                    if (!suppress2) output.append(col2Prefix).append(lines2[j]).append('\n')
                    j++
                }
                else -> {
                    if (!suppress3) output.append(col3Prefix).append(lines1[i]).append('\n')
                    i++
                    j++
                }
            }
        }

        return ExecResult(stdout = output.toString(), stderr = "", exitCode = 0)
    }
}

// ---------------------------------------------------------------------------
// file
// ---------------------------------------------------------------------------

object FileCommand : Command {
    override val name = "file"

    private data class FileType(val description: String, val mime: String)

    private val extensionTypes = mapOf(
        ".js" to FileType("JavaScript source", "text/javascript"),
        ".py" to FileType("Python script", "text/x-python"),
        ".rb" to FileType("Ruby script", "text/x-ruby"),
        ".c" to FileType("C source", "text/x-c"),
        ".h" to FileType("C header", "text/x-c"),
        ".cpp" to FileType("C++ source", "text/x-c++"),
        ".java" to FileType("Java source", "text/x-java"),
        ".sh" to FileType("Bourne-Again shell script", "text/x-shellscript"),
        ".bash" to FileType("Bourne-Again shell script", "text/x-shellscript"),
        ".json" to FileType("JSON data", "application/json"),
        ".yaml" to FileType("YAML data", "text/yaml"),
        ".yml" to FileType("YAML data", "text/yaml"),
        ".xml" to FileType("XML document", "application/xml"),
        ".csv" to FileType("CSV text", "text/csv"),
        ".html" to FileType("HTML document", "text/html"),
        ".css" to FileType("CSS stylesheet", "text/css"),
        ".md" to FileType("Markdown document", "text/markdown"),
        ".txt" to FileType("ASCII text", "text/plain"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "file", "determine file type",
                "file [OPTION]... FILE...",
                listOf(
                    "-b, --brief          do not prepend filenames to output",
                    "-i, --mime           output MIME type strings",
                    "-L, --dereference    follow symlinks",
                    "    --help           display this help and exit",
                ),
            )
        }

        var brief = false
        var mimeMode = false
        var dereference = false
        val files = ArrayList<String>()

        for (arg in args) {
            if (arg.startsWith("--")) {
                when (arg) {
                    "--brief" -> brief = true
                    "--mime", "--mime-type" -> mimeMode = true
                    "--dereference" -> dereference = true
                    else -> return unknownOption("file", arg)
                }
            } else if (arg.startsWith("-") && arg != "-") {
                for (c in arg.substring(1)) {
                    when (c) {
                        'b' -> brief = true
                        'i' -> mimeMode = true
                        'L' -> dereference = true
                        else -> return unknownOption("file", "-$c")
                    }
                }
            } else {
                files.add(arg)
            }
        }

        if (files.isEmpty()) {
            return ExecResult(stderr = "Usage: file [-bLi] FILE...\n", exitCode = 1)
        }

        val output = StringBuilder()
        var exitCode = 0
        for (file in files) {
            try {
                val path = ctx.fs.resolvePath(ctx.cwd, file)
                val stats = if (dereference) ctx.fs.stat(path) else ctx.fs.lstat(path)

                if (stats.isSymbolicLink) {
                    val target = ctx.fs.readlink(path)
                    val result = if (mimeMode) "inode/symlink" else "symbolic link to $target"
                    output.append(if (brief) "$result\n" else "$file: $result\n")
                    continue
                }

                if (stats.isDirectory) {
                    val result = if (mimeMode) "inode/directory" else "directory"
                    output.append(if (brief) "$result\n" else "$file: $result\n")
                    continue
                }

                val buffer = ctx.fs.readFileBuffer(path)
                val fileType = detectFileType(file, buffer)
                val result = if (mimeMode) fileType.mime else fileType.description
                output.append(if (brief) "$result\n" else "$file: $result\n")
            } catch (_: Exception) {
                output.append(
                    if (brief) "cannot open\n" else "$file: cannot open (No such file or directory)\n",
                )
                exitCode = 1
            }
        }

        return ExecResult(stdout = output.toString(), stderr = "", exitCode = exitCode)
    }

    private fun detectFileType(filename: String, buffer: ByteArray): FileType {
        if (buffer.isEmpty()) return FileType("empty", "inode/x-empty")

        val magic = detectMagicType(filename, buffer)
        if (magic != null) return magic

        val content = buffer.toString(Charsets.UTF_8)
        return detectTextType(content, filename)
    }

    private fun detectMagicType(filename: String, buffer: ByteArray): FileType? {
        fun startsWith(prefix: ByteArray): Boolean =
            buffer.size >= prefix.size && prefix.indices.all { buffer[it] == prefix[it] }

        val ext = getExtension(filename)

        if (startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) {
            return FileType("PNG image data", "image/png")
        }
        if (startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) {
            return FileType("JPEG image data", "image/jpeg")
        }
        if (startsWith("GIF87a".toByteArray()) || startsWith("GIF89a".toByteArray())) {
            return FileType("GIF image data", "image/gif")
        }
        if (startsWith("%PDF".toByteArray())) {
            return FileType("PDF document", "application/pdf")
        }
        if (startsWith(byteArrayOf(0x1F, 0x8B.toByte()))) {
            return FileType("gzip compressed data", "application/gzip")
        }
        if (startsWith("PK\u0003\u0004".toByteArray())) {
            return FileType("Zip archive data", "application/zip")
        }
        if (startsWith(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))) {
            return FileType("ELF executable", "application/x-executable")
        }
        if (startsWith(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))) {
            return FileType("Java class file", "application/java-vm")
        }
        if (startsWith("SQLite format 3".toByteArray())) {
            return FileType("SQLite 3.x database", "application/vnd.sqlite3")
        }

        // Skip probeContentType for known text extensions so shebang and
        // extension-based text detection runs instead.
        if (ext in extensionTypes) return null

        val probed = try {
            java.nio.file.Files.probeContentType(java.nio.file.Paths.get(filename))
        } catch (_: Exception) {
            null
        }
        if (probed != null && probed != "text/plain") {
            val desc = when {
                probed.startsWith("image/") -> "$ext image data"
                probed.startsWith("audio/") -> "$ext audio"
                probed.startsWith("video/") -> "$ext video"
                else -> "$ext data"
            }
            return FileType(desc, probed)
        }
        return null
    }

    private fun detectTextType(content: String, filename: String): FileType {
        if (content.startsWith("#!")) {
            val firstLine = content.split("\n")[0]
            return when {
                firstLine.contains("python") -> FileType("Python script, ASCII text executable", "text/x-python")
                firstLine.contains("node") || firstLine.contains("bun") || firstLine.contains("deno") ->
                    FileType("JavaScript script, ASCII text executable", "text/javascript")
                firstLine.contains("bash") -> FileType("Bourne-Again shell script, ASCII text executable", "text/x-shellscript")
                firstLine.contains("sh") -> FileType("POSIX shell script, ASCII text executable", "text/x-shellscript")
                firstLine.contains("ruby") -> FileType("Ruby script, ASCII text executable", "text/x-ruby")
                else -> FileType("script, ASCII text executable", "text/plain")
            }
        }

        val trimmed = content.trimStart()
        if (trimmed.startsWith("<?xml")) return FileType("XML document", "application/xml")
        if (trimmed.startsWith("<!DOCTYPE html") || trimmed.lowercase().startsWith("<html")) {
            return FileType("HTML document", "text/html")
        }

        val hasCRLF = content.contains("\r\n")
        val hasCR = content.contains("\r") && !hasCRLF
        val lineEnding = when {
            hasCRLF -> ", with CRLF line terminators"
            hasCR -> ", with CR line terminators"
            else -> ""
        }

        val ext = getExtension(filename)
        val extType = if (ext.isNotEmpty()) extensionTypes[ext] else null
        if (extType != null) {
            if (extType.mime.startsWith("text/") && lineEnding.isNotEmpty()) {
                return FileType(extType.description + lineEnding, extType.mime)
            }
            return extType
        }

        var hasUnicode = false
        val limit = minOf(content.length, 8192)
        for (i in 0 until limit) {
            if (content[i].code > 127) {
                hasUnicode = true
                break
            }
        }
        if (hasUnicode) return FileType("UTF-8 Unicode text$lineEnding", "text/plain; charset=utf-8")
        return FileType("ASCII text$lineEnding", "text/plain")
    }

    private fun getExtension(filename: String): String {
        val base = filename.substringAfterLast('/', filename)
        if (base.startsWith(".") && base.indexOf('.', 1) == -1) return base
        val dotIndex = base.lastIndexOf('.')
        if (dotIndex == -1 || dotIndex == 0) return ""
        return base.substring(dotIndex).lowercase()
    }
}

// ---------------------------------------------------------------------------
// join
// ---------------------------------------------------------------------------

object JoinCommand : Command {
    override val name = "join"

    private data class ParsedLine(val fields: List<String>, val joinKey: String)

    private data class Options(
        var field1: Int = 1,
        var field2: Int = 1,
        var separator: String? = null,
        val printUnpairable: MutableSet<Int> = HashSet(),
        val onlyUnpairable: MutableSet<Int> = HashSet(),
        var emptyString: String = "",
        var outputFormat: List<Pair<Int, Int>>? = null,
        var ignoreCase: Boolean = false,
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "join", "join lines of two files on a common field",
                "join [OPTION]... FILE1 FILE2",
                listOf(
                    "-1 FIELD     Join on this FIELD of file 1 (default: 1)",
                    "-2 FIELD     Join on this FIELD of file 2 (default: 1)",
                    "-t CHAR      Use CHAR as input and output field separator",
                    "-a FILENUM   Also print unpairable lines from file FILENUM (1 or 2)",
                    "-v FILENUM   Like -a but only output unpairable lines",
                    "-e STRING    Replace missing fields with STRING",
                    "-o FORMAT    Output format (comma-separated list of FILENUM.FIELD)",
                    "-i           Ignore case when comparing fields",
                    "    --help   display this help and exit",
                ),
            )
        }

        val options = Options()
        val files = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-1" && i + 1 < args.size -> {
                    val field = args[i + 1].toIntOrNull()
                    if (field == null || field < 1) {
                        return ExecResult(stderr = "join: invalid field number: '${args[i + 1]}'\n", exitCode = 1)
                    }
                    options.field1 = field
                    i += 2
                }
                arg == "-2" && i + 1 < args.size -> {
                    val field = args[i + 1].toIntOrNull()
                    if (field == null || field < 1) {
                        return ExecResult(stderr = "join: invalid field number: '${args[i + 1]}'\n", exitCode = 1)
                    }
                    options.field2 = field
                    i += 2
                }
                (arg == "-t" || arg == "--field-separator") && i + 1 < args.size -> {
                    options.separator = args[i + 1]
                    i += 2
                }
                arg.startsWith("-t") && arg.length > 2 -> {
                    options.separator = arg.substring(2)
                    i++
                }
                arg == "-a" && i + 1 < args.size -> {
                    val fileNum = args[i + 1].toIntOrNull()
                    if (fileNum != 1 && fileNum != 2) {
                        return ExecResult(stderr = "join: invalid file number: '${args[i + 1]}'\n", exitCode = 1)
                    }
                    options.printUnpairable.add(fileNum)
                    i += 2
                }
                Regex("^-a[12]$").matches(arg) -> {
                    options.printUnpairable.add(arg[2].digitToInt())
                    i++
                }
                arg == "-v" && i + 1 < args.size -> {
                    val fileNum = args[i + 1].toIntOrNull()
                    if (fileNum != 1 && fileNum != 2) {
                        return ExecResult(stderr = "join: invalid file number: '${args[i + 1]}'\n", exitCode = 1)
                    }
                    options.onlyUnpairable.add(fileNum)
                    i += 2
                }
                Regex("^-v[12]$").matches(arg) -> {
                    options.onlyUnpairable.add(arg[2].digitToInt())
                    i++
                }
                arg == "-e" && i + 1 < args.size -> {
                    options.emptyString = args[i + 1]
                    i += 2
                }
                arg == "-o" && i + 1 < args.size -> {
                    val format = parseOutputFormat(args[i + 1])
                    if (format == null) {
                        return ExecResult(stderr = "join: invalid field spec: '${args[i + 1]}'\n", exitCode = 1)
                    }
                    options.outputFormat = format
                    i += 2
                }
                arg == "-i" || arg == "--ignore-case" -> {
                    options.ignoreCase = true
                    i++
                }
                arg == "--" -> {
                    files.addAll(args.subList(i + 1, args.size))
                    i = args.size
                }
                arg.startsWith("-") && arg != "-" -> return unknownOption("join", arg)
                else -> {
                    files.add(arg)
                    i++
                }
            }
        }

        if (files.size != 2) {
            return ExecResult(
                stderr = if (files.size < 2) "join: missing file operand\n" else "join: extra operand\n",
                exitCode = 1,
            )
        }

        val contents = ArrayList<String>()
        for (file in files) {
            if (file == "-") {
                contents.add(ctx.stdin.toString(Charsets.UTF_8))
            } else {
                try {
                    contents.add(ctx.fs.readFile(ctx.fs.resolvePath(ctx.cwd, file)))
                } catch (_: Exception) {
                    return ExecResult(stderr = "join: $file: No such file or directory\n", exitCode = 1)
                }
            }
        }

        val lines1 = parseLines(contents[0], options.field1, options)
        val lines2 = parseLines(contents[1], options.field2, options)

        val index2 = HashMap<String, MutableList<ParsedLine>>()
        for (line in lines2) {
            index2.getOrPut(line.joinKey) { ArrayList() }.add(line)
        }

        val output = ArrayList<String>()
        val matchedKeys2 = HashSet<String>()

        for (line1 in lines1) {
            val matches = index2[line1.joinKey]
            if (matches != null && matches.isNotEmpty()) {
                matchedKeys2.add(line1.joinKey)
                if (options.onlyUnpairable.isEmpty()) {
                    for (line2 in matches) {
                        output.add(formatOutputLine(line1, line2, options))
                    }
                }
            } else {
                if (options.printUnpairable.contains(1) || options.onlyUnpairable.contains(1)) {
                    output.add(formatOutputLine(line1, null, options))
                }
            }
        }

        if (options.printUnpairable.contains(2) || options.onlyUnpairable.contains(2)) {
            for (line2 in lines2) {
                if (!matchedKeys2.contains(line2.joinKey)) {
                    output.add(formatOutputLine(null, line2, options))
                }
            }
        }

        val stdout = if (output.isEmpty()) "" else output.joinToString("\n") + "\n"
        return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
    }

    private fun splitLine(line: String, separator: String?): List<String> {
        return if (separator != null) {
            line.split(separator)
        } else {
            line.split(Regex("[ \t]+")).filter { it.isNotEmpty() }
        }
    }

    private fun parseLine(line: String, separator: String?, joinField: Int, options: Options): ParsedLine {
        val fields = splitLine(line, separator)
        var joinKey = fields.getOrNull(joinField - 1) ?: ""
        if (options.ignoreCase) joinKey = joinKey.lowercase()
        return ParsedLine(fields, joinKey)
    }

    private fun parseLines(content: String, joinField: Int, options: Options): List<ParsedLine> {
        val lines = content.split("\n").toMutableList()
        if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.size - 1)
        return lines.filter { it.isNotEmpty() }.map { parseLine(it, options.separator, joinField, options) }
    }

    private fun parseOutputFormat(format: String): List<Pair<Int, Int>>? {
        val result = ArrayList<Pair<Int, Int>>()
        for (part in format.split(",")) {
            val m = Regex("^(\\d+)\\.(\\d+)$").find(part.trim()) ?: return null
            val file = m.groupValues[1].toIntOrNull() ?: return null
            val field = m.groupValues[2].toIntOrNull() ?: return null
            if (file != 1 && file != 2) return null
            result.add(file to field)
        }
        return result
    }

    private fun formatOutputLine(line1: ParsedLine?, line2: ParsedLine?, options: Options): String {
        val sep = options.separator ?: " "
        val outputFormat = options.outputFormat
        val fmt = outputFormat // capture as local val for smart cast
        if (fmt != null) {
            val parts = ArrayList<String>()
            for ((file, field) in fmt) {
                val line = if (file == 1) line1 else line2
                when {
                    line != null && field == 0 -> parts.add(line.joinKey)
                    line != null && line.fields.getOrNull(field - 1) != null -> parts.add(line.fields[field - 1])
                    else -> parts.add(options.emptyString)
                }
            }
            return parts.joinToString(sep)
        }

        val parts = ArrayList<String>()
        val joinField = line1?.joinKey ?: line2?.joinKey ?: ""
        parts.add(joinField)
        if (line1 != null) {
            for (idx in line1.fields.indices) {
                if (idx != options.field1 - 1) parts.add(line1.fields[idx])
            }
        }
        if (line2 != null) {
            for (idx in line2.fields.indices) {
                if (idx != options.field2 - 1) parts.add(line2.fields[idx])
            }
        }
        return parts.joinToString(sep)
    }
}

// ---------------------------------------------------------------------------
// od
// ---------------------------------------------------------------------------

object OdCommand : Command {
    override val name = "od"

    private enum class OutputFormat { OCTAL, HEX, CHAR }

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "od", "dump files in octal and other formats",
                "od [OPTION]... [FILE]...",
                listOf(
                    "-A n           no address",
                    "-t o1          octal bytes",
                    "-t x1          hex bytes",
                    "-t c           characters",
                    "-N N           limit bytes",
                    "    --help     display this help and exit",
                ),
            )
        }

        var addressMode = "octal"
        val outputFormats = ArrayList<OutputFormat>()
        val fileArgs = ArrayList<String>()
        var limitBytes: Int? = null

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-c" -> {
                    outputFormats.add(OutputFormat.CHAR)
                    i++
                }
                arg == "-An" || (arg == "-A" && i + 1 < args.size && args[i + 1] == "n") -> {
                    addressMode = "none"
                    i += if (arg == "-A") 2 else 1
                }
                arg == "-A" && i + 1 < args.size -> {
                    i += 2
                }
                arg == "-t" && i + 1 < args.size -> {
                    val format = args[i + 1]
                    when {
                        format == "x1" -> outputFormats.add(OutputFormat.HEX)
                        format == "c" -> outputFormats.add(OutputFormat.CHAR)
                        format.startsWith("o") -> outputFormats.add(OutputFormat.OCTAL)
                    }
                    i += 2
                }
                arg == "-N" && i + 1 < args.size -> {
                    limitBytes = args[i + 1].toIntOrNull()
                    i += 2
                }
                arg.startsWith("-N") && arg.length > 2 -> {
                    limitBytes = arg.substring(2).toIntOrNull()
                    i++
                }
                !arg.startsWith("-") || arg == "-" -> {
                    fileArgs.add(arg)
                    i++
                }
                else -> i++
            }
        }

        if (outputFormats.isEmpty()) outputFormats.add(OutputFormat.OCTAL)

        val operands = if (fileArgs.isEmpty()) listOf("-") else fileArgs
        val inputBytes = ArrayList<Byte>()
        for (operand in operands) {
            if (operand == "-") {
                for (b in ctx.stdin) inputBytes.add(b)
            } else {
                val filePath = ctx.fs.resolvePath(ctx.cwd, operand)
                try {
                    val buf = ctx.fs.readFileBuffer(filePath)
                    for (b in buf) inputBytes.add(b)
                } catch (_: Exception) {
                    return ExecResult(stderr = "od: $operand: No such file or directory\n", exitCode = 1)
                }
            }
        }

        var data = inputBytes.toByteArray()
        if (limitBytes != null && limitBytes < data.size) {
            data = data.copyOf(limitBytes)
        }

        val hasCharFormat = outputFormats.contains(OutputFormat.CHAR)
        val bytesPerLine = 16

        val output = StringBuilder()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + bytesPerLine, data.size)
            for (formatIdx in outputFormats.indices) {
                val format = outputFormats[formatIdx]
                val slice = data.sliceArray(offset until end)
                val formatted = when (format) {
                    OutputFormat.CHAR -> slice.joinToString("") { formatCharByte(it) }
                    OutputFormat.HEX -> slice.joinToString("") { formatHexByte(it, hasCharFormat) }
                    OutputFormat.OCTAL -> slice.joinToString("") { formatOctalByte(it) }
                }
                val prefix = when {
                    formatIdx == 0 && addressMode != "none" -> offset.toString(8).padStart(7, '0') + " "
                    formatIdx > 0 && addressMode != "none" -> "        "
                    else -> ""
                }
                output.append(prefix).append(formatted).append('\n')
            }
            offset += bytesPerLine
        }

        if (addressMode != "none" && data.isNotEmpty()) {
            output.append(data.size.toString(8).padStart(7, '0')).append('\n')
        }

        return ExecResult(stdout = output.toString(), stderr = "", exitCode = 0)
    }

    private fun formatCharByte(code: Byte): String {
        val c = code.toInt() and 0xFF
        return when (c) {
            0 -> "  \\0"
            7 -> "  \\a"
            8 -> "  \\b"
            9 -> "  \\t"
            10 -> "  \\n"
            11 -> "  \\v"
            12 -> "  \\f"
            13 -> "  \\r"
            in 32..126 -> "   " + c.toChar()
            else -> " " + c.toString(8).padStart(3, '0')
        }
    }

    private fun formatHexByte(code: Byte, hasCharFormat: Boolean): String {
        val c = code.toInt() and 0xFF
        val hex = c.toString(16).padStart(2, '0')
        return if (hasCharFormat) "  $hex" else " $hex"
    }

    private fun formatOctalByte(code: Byte): String {
        val c = code.toInt() and 0xFF
        return " " + c.toString(8).padStart(3, '0')
    }
}

// ---------------------------------------------------------------------------
// paste
// ---------------------------------------------------------------------------

object PasteCommand : Command {
    override val name = "paste"

    private val defs = mapOf(
        "delimiter" to Args.Def(short = "d", long = "delimiters", type = "string", default = "\t"),
        "serial" to Args.Def(short = "s", long = "serial", type = "boolean"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "paste", "merge lines of files",
                "paste [OPTION]... [FILE]...",
                listOf(
                    "-d, --delimiters=LIST   reuse characters from LIST instead of TABs",
                    "-s, --serial            paste one file at a time instead of in parallel",
                    "    --help              display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("paste", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val delimiter = parsed.flags.string("delimiter") ?: "\t"
        val serial = parsed.flags.bool("serial")
        val files = parsed.positional

        if (files.isEmpty()) {
            return ExecResult(stderr = "usage: paste [-s] [-d delimiters] file ...\n", exitCode = 1)
        }

        val stdinText = ctx.stdin.toString(Charsets.UTF_8)
        val stdinLines = if (stdinText.isEmpty()) listOf("") else {
            val lines = stdinText.split("\n").toMutableList()
            if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.size - 1)
            lines
        }

        val stdinCount = files.count { it == "-" }

        val fileContents = ArrayList<List<String>?>()
        var stdinIndex = 0

        for (file in files) {
            if (file == "-") {
                val thisStdinLines = ArrayList<String>()
                var k = stdinIndex
                while (k < stdinLines.size) {
                    thisStdinLines.add(stdinLines[k])
                    k += stdinCount
                }
                fileContents.add(thisStdinLines)
                stdinIndex++
            } else {
                try {
                    val content = ctx.fs.readFile(ctx.fs.resolvePath(ctx.cwd, file))
                    val lines = content.split("\n").toMutableList()
                    if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.size - 1)
                    fileContents.add(lines)
                } catch (_: Exception) {
                    return ExecResult(stderr = "paste: $file: No such file or directory\n", exitCode = 1)
                }
            }
        }

        val output = StringBuilder()

        if (serial) {
            for (lines in fileContents) {
                if (lines != null) {
                    output.append(joinWithDelimiters(lines, delimiter)).append('\n')
                }
            }
        } else {
            var maxLines = 0
            for (content in fileContents) {
                maxLines = maxOf(maxLines, content?.size ?: 0)
            }
            for (lineIdx in 0 until maxLines) {
                val lineParts = ArrayList<String>()
                for (lines in fileContents) {
                    lineParts.add(lines?.getOrNull(lineIdx) ?: "")
                }
                output.append(joinWithDelimiters(lineParts, delimiter)).append('\n')
            }
        }

        return ExecResult(stdout = output.toString(), stderr = "", exitCode = 0)
    }

    private fun joinWithDelimiters(parts: List<String>, delimiters: String): String {
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return parts[0]
        if (delimiters.isEmpty()) return parts.joinToString("")
        var result = parts[0]
        for (i in 1 until parts.size) {
            val delimIdx = (i - 1) % delimiters.length
            result += delimiters[delimIdx] + parts[i]
        }
        return result
    }
}

// ---------------------------------------------------------------------------
// rev
// ---------------------------------------------------------------------------

object RevCommand : Command {
    override val name = "rev"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "rev", "reverse lines characterwise",
                "rev [file ...]",
                listOf(
                    "    --help     display this help and exit",
                ),
            )
        }

        val files = ArrayList<String>()
        for (arg in args) {
            if (arg == "--") {
                val idx = args.indexOf(arg)
                files.addAll(args.subList(idx + 1, args.size))
                break
            } else if (arg.startsWith("-") && arg != "-") {
                return unknownOption("rev", arg)
            } else {
                files.add(arg)
            }
        }

        var output = ""

        val processContent: (String) -> String = { content ->
            val lines = content.split("\n").toMutableList()
            val hasTrailingNewline = content.endsWith("\n") && lines.isNotEmpty() && lines.last() == ""
            if (hasTrailingNewline) lines.removeAt(lines.size - 1)
            val reversed = lines.map { it.reversed() }
            reversed.joinToString("\n") + (if (hasTrailingNewline) "\n" else "")
        }

        if (files.isEmpty()) {
            val input = ctx.stdin.toString(Charsets.UTF_8)
            output = processContent(input)
        } else {
            for (file in files) {
                if (file == "-") {
                    val input = ctx.stdin.toString(Charsets.UTF_8)
                    output += processContent(input)
                } else {
                    try {
                        val content = ctx.fs.readFile(ctx.fs.resolvePath(ctx.cwd, file))
                        output += processContent(content)
                    } catch (_: Exception) {
                        return ExecResult(stderr = "rev: $file: No such file or directory\n", exitCode = 1)
                    }
                }
            }
        }

        return ExecResult(stdout = output, stderr = "", exitCode = 0)
    }
}

// ---------------------------------------------------------------------------
// strings
// ---------------------------------------------------------------------------

object StringsCommand : Command {
    override val name = "strings"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "strings", "print the sequences of printable characters in files",
                "strings [OPTION]... [FILE]...",
                listOf(
                    "-n MIN       Print sequences of at least MIN characters (default: 4)",
                    "-t FORMAT    Print offset before each string (o=octal, x=hex, d=decimal)",
                    "-a           Scan the entire file (default behavior)",
                    "    --help   display this help and exit",
                ),
            )
        }

        var minLength = 4
        var offsetFormat: String? = null
        val files = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-n" && i + 1 < args.size -> {
                    val min = args[i + 1].toIntOrNull()
                    if (min == null || min < 1) {
                        return ExecResult(stderr = "strings: invalid minimum string length: '${args[i + 1]}'\n", exitCode = 1)
                    }
                    minLength = min
                    i += 2
                }
                Regex("^-n\\d+$").matches(arg) -> {
                    val min = arg.substring(2).toIntOrNull()
                    if (min == null || min < 1) {
                        return ExecResult(stderr = "strings: invalid minimum string length: '${arg.substring(2)}'\n", exitCode = 1)
                    }
                    minLength = min
                    i++
                }
                Regex("^-\\d+$").matches(arg) -> {
                    val min = arg.substring(1).toIntOrNull()
                    if (min == null || min < 1) {
                        return ExecResult(stderr = "strings: invalid minimum string length: '${arg.substring(1)}'\n", exitCode = 1)
                    }
                    minLength = min
                    i++
                }
                arg == "-t" && i + 1 < args.size -> {
                    val format = args[i + 1]
                    if (format != "o" && format != "x" && format != "d") {
                        return ExecResult(stderr = "strings: invalid radix: '$format'\n", exitCode = 1)
                    }
                    offsetFormat = format
                    i += 2
                }
                arg.startsWith("-t") && arg.length == 3 -> {
                    val format = arg.substring(2)
                    if (format != "o" && format != "x" && format != "d") {
                        return ExecResult(stderr = "strings: invalid radix: '$format'\n", exitCode = 1)
                    }
                    offsetFormat = format
                    i++
                }
                arg == "-a" || arg == "--all" -> {
                    i++
                }
                arg == "-" -> {
                    files.add(arg)
                    i++
                }
                arg == "-e" && i + 1 < args.size -> {
                    val encoding = args[i + 1]
                    if (encoding != "s" && encoding != "S") {
                        return ExecResult(stderr = "strings: invalid encoding: '$encoding'\n", exitCode = 1)
                    }
                    i += 2
                }
                arg.startsWith("-e") && arg.length == 3 -> {
                    val encoding = arg.substring(2)
                    if (encoding != "s" && encoding != "S") {
                        return ExecResult(stderr = "strings: invalid encoding: '$encoding'\n", exitCode = 1)
                    }
                    i++
                }
                arg == "--" -> {
                    files.addAll(args.subList(i + 1, args.size))
                    i = args.size
                }
                arg.startsWith("-") && arg != "-" -> return unknownOption("strings", arg)
                else -> {
                    files.add(arg)
                    i++
                }
            }
        }

        var output = ""

        if (files.isEmpty()) {
            val strings = extractStrings(ctx.stdin, minLength, offsetFormat)
            output = if (strings.isNotEmpty()) strings.joinToString("\n") + "\n" else ""
        } else {
            for (file in files) {
                val buffer: ByteArray = if (file == "-") {
                    ctx.stdin
                } else {
                    try {
                        ctx.fs.readFileBuffer(ctx.fs.resolvePath(ctx.cwd, file))
                    } catch (_: Exception) {
                        return ExecResult(stderr = "strings: $file: No such file or directory\n", exitCode = 1)
                    }
                }
                val strings = extractStrings(buffer, minLength, offsetFormat)
                if (strings.isNotEmpty()) {
                    output += strings.joinToString("\n") + "\n"
                }
            }
        }

        return ExecResult(stdout = output, stderr = "", exitCode = 0)
    }

    private fun isPrintable(byte: Int): Boolean = (byte in 32..126) || byte == 9

    private fun formatOffset(offset: Int, format: String?): String {
        return when (format) {
            "o" -> offset.toString(8).padStart(7, ' ') + " "
            "x" -> offset.toString(16).padStart(7, ' ') + " "
            "d" -> offset.toString(10).padStart(7, ' ') + " "
            else -> ""
        }
    }

    private fun extractStrings(data: ByteArray, minLength: Int, offsetFormat: String?): List<String> {
        val results = ArrayList<String>()
        var currentLength = 0
        var stringStart = 0

        for (idx in data.indices) {
            val byte = data[idx].toInt() and 0xFF
            if (isPrintable(byte)) {
                if (currentLength == 0) stringStart = idx
                currentLength++
            } else {
                if (currentLength >= minLength) {
                    val prefix = formatOffset(stringStart, offsetFormat)
                    val run = data.sliceArray(stringStart until idx).toString(Charsets.UTF_8)
                    results.add(prefix + run)
                }
                currentLength = 0
            }
        }

        if (currentLength >= minLength) {
            val prefix = formatOffset(stringStart, offsetFormat)
            val run = data.sliceArray(stringStart until data.size).toString(Charsets.UTF_8)
            results.add(prefix + run)
        }

        return results
    }
}

// ---------------------------------------------------------------------------
// tac
// ---------------------------------------------------------------------------

object TacCommand : Command {
    override val name = "tac"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "tac", "concatenate and print files in reverse",
                "tac [OPTION]... [FILE]...",
                listOf(
                    "    --help     display this help and exit",
                ),
            )
        }

        val files = args.filter { it != "--help" }

        if (files.isEmpty() || files[0] == "-") {
            val content = latin1FromBytes(ctx.stdin)
            return ExecResult(stdout = reverseLines(content), stderr = "", exitCode = 0)
        }

        val file = files[0]
        try {
            val content = ctx.fs.readFile(ctx.fs.resolvePath(ctx.cwd, file))
            return ExecResult(stdout = reverseLines(content), stderr = "", exitCode = 0)
        } catch (_: Exception) {
            return ExecResult(stderr = "tac: $file: No such file or directory\n", exitCode = 1)
        }
    }

    private fun reverseLines(content: String): String {
        val lines = content.split("\n").toMutableList()
        if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.size - 1)
        lines.reverse()
        return if (lines.isNotEmpty()) lines.joinToString("\n") + "\n" else ""
    }
}
