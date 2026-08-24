package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult

/**
 * Port of just-bash `sort/sort.ts`, `sort/comparator.ts`, `sort/parser.ts`,
 * and `sort/types.ts`.
 */

// ---- Types (sort/types.ts) ----

data class KeySpec(
    var startField: Int = 1,
    var startChar: Int? = null,
    var endField: Int? = null,
    var endChar: Int? = null,
    var numeric: Boolean? = null,
    var reverse: Boolean? = null,
    var ignoreCase: Boolean? = null,
    var ignoreLeading: Boolean? = null,
    var humanNumeric: Boolean? = null,
    var versionSort: Boolean? = null,
    var dictionaryOrder: Boolean? = null,
    var monthSort: Boolean? = null,
)

data class SortOptions(
    var reverse: Boolean = false,
    var numeric: Boolean = false,
    var unique: Boolean = false,
    var ignoreCase: Boolean = false,
    var humanNumeric: Boolean = false,
    var versionSort: Boolean = false,
    var dictionaryOrder: Boolean = false,
    var monthSort: Boolean = false,
    var ignoreLeadingBlanks: Boolean = false,
    var stable: Boolean = false,
    var checkOnly: Boolean = false,
    var outputFile: String? = null,
    val keys: MutableList<KeySpec> = ArrayList(),
    var fieldDelimiter: String? = null,
)

// ---- Parser (sort/parser.ts) ----

internal fun parseKeySpec(spec: String): KeySpec? {
    val result = KeySpec()

    var modifierStr = ""
    var mainSpec = spec

    val modifierMatch = Regex("([bdfhMnrV]+)$").find(mainSpec)
    if (modifierMatch != null) {
        modifierStr = modifierMatch.groupValues[1]
        mainSpec = mainSpec.substring(0, mainSpec.length - modifierStr.length)
    }

    if (modifierStr.contains('n')) result.numeric = true
    if (modifierStr.contains('r')) result.reverse = true
    if (modifierStr.contains('f')) result.ignoreCase = true
    if (modifierStr.contains('b')) result.ignoreLeading = true
    if (modifierStr.contains('h')) result.humanNumeric = true
    if (modifierStr.contains('V')) result.versionSort = true
    if (modifierStr.contains('d')) result.dictionaryOrder = true
    if (modifierStr.contains('M')) result.monthSort = true

    val parts = mainSpec.split(",")
    if (parts.isEmpty() || parts[0] == "") return null

    val startParts = parts[0].split(".")
    val startField = startParts[0].toIntOrNull()
    if (startField == null || startField < 1) return null
    result.startField = startField

    if (startParts.size > 1 && startParts[1].isNotEmpty()) {
        val startChar = startParts[1].toIntOrNull()
        if (startChar != null && startChar >= 1) result.startChar = startChar
    }

    if (parts.size > 1 && parts[1].isNotEmpty()) {
        var endPart = parts[1]
        val endModifierMatch = Regex("([bdfhMnrV]+)$").find(endPart)
        if (endModifierMatch != null) {
            val endModifiers = endModifierMatch.groupValues[1]
            if (endModifiers.contains('n')) result.numeric = true
            if (endModifiers.contains('r')) result.reverse = true
            if (endModifiers.contains('f')) result.ignoreCase = true
            if (endModifiers.contains('b')) result.ignoreLeading = true
            if (endModifiers.contains('h')) result.humanNumeric = true
            if (endModifiers.contains('V')) result.versionSort = true
            if (endModifiers.contains('d')) result.dictionaryOrder = true
            if (endModifiers.contains('M')) result.monthSort = true
            endPart = endPart.substring(0, endPart.length - endModifiers.length)
        }

        val endParts = endPart.split(".")
        if (endParts[0].isNotEmpty()) {
            val endField = endParts[0].toIntOrNull()
            if (endField != null && endField >= 1) result.endField = endField
        }
        if (endParts.size > 1 && endParts[1].isNotEmpty()) {
            val endChar = endParts[1].toIntOrNull()
            if (endChar != null && endChar >= 1) result.endChar = endChar
        }
    }

    return result
}

// ---- Comparator (sort/comparator.ts) ----

private val SIZE_SUFFIXES = mapOf(
    "" to 1L, "k" to 1024L, "m" to 1024L * 1024L,
    "g" to 1024L * 1024L * 1024L, "t" to 1024L * 1024L * 1024L * 1024L,
    "p" to 1024L * 1024L * 1024L * 1024L * 1024L,
    "e" to 1024L * 1024L * 1024L * 1024L * 1024L * 1024L,
)

private val MONTHS = mapOf(
    "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4,
    "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8,
    "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
)

private fun parseHumanSize(s: String): Long {
    val trimmed = s.trim()
    val match = Regex("^([+-]?\\d*\\.?\\d+)\\s*([kmgtpeKMGTPE])?[iI]?[bB]?$").find(trimmed)
    if (match != null) {
        val num = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val suffix = (match.groupValues[2].ifEmpty { "" }).lowercase()
        val multiplier = SIZE_SUFFIXES[suffix] ?: 1L
        return (num * multiplier).toLong()
    }
    return trimmed.toDoubleOrNull()?.toLong() ?: 0L
}

private fun parseMonth(s: String): Int {
    val trimmed = s.trim().lowercase().take(3)
    return MONTHS[trimmed] ?: 0
}

private fun compareVersions(a: String, b: String): Int {
    val partsA = a.split(Regex("(\\d+)"))
    val partsB = b.split(Regex("(\\d+)"))
    val maxLen = maxOf(partsA.size, partsB.size)

    for (idx in 0 until maxLen) {
        val partA = partsA.getOrElse(idx) { "" }
        val partB = partsB.getOrElse(idx) { "" }
        val numA = partA.toIntOrNull()
        val numB = partB.toIntOrNull()

        if (numA != null && numB != null) {
            if (numA != numB) return numA - numB
        } else {
            if (partA != partB) return partA.compareTo(partB)
        }
    }
    return 0
}

private fun toDictionaryOrder(s: String): String = s.replace(Regex("[^a-zA-Z0-9\\s]"), "")

private fun extractKeyValue(line: String, key: KeySpec, delimiter: String?): String {
    val splitPattern = if (delimiter != null) Regex(Regex.escape(delimiter)) else Regex("\\s+")
    val fields = line.split(splitPattern)

    val startFieldIdx = key.startField - 1
    if (startFieldIdx >= fields.size) return ""

    val endField = key.endField
    val startChar = key.startChar
    val endChar = key.endChar

    if (endField == null) {
        var field = fields.getOrElse(startFieldIdx) { "" }
        if (startChar != null) {
            field = field.substring(minOf(startChar - 1, field.length))
        }
        if (key.ignoreLeading == true) {
            field = field.trimStart()
        }
        return field
    }

    val endFieldIdx = minOf(endField - 1, fields.size - 1)
    val result = StringBuilder()
    for (fi in startFieldIdx..endFieldIdx) {
        if (fi >= fields.size) break
        var field = fields[fi]
        if (fi == startFieldIdx && startChar != null) {
            field = field.substring(minOf(startChar - 1, field.length))
        }
        if (fi == endFieldIdx && endChar != null) {
            val endIdx = if (fi == startFieldIdx && startChar != null) {
                endChar - startChar + 1
            } else {
                endChar
            }
            field = field.substring(0, minOf(endIdx, field.length))
        }
        if (fi > startFieldIdx) {
            result.append(delimiter ?: " ")
        }
        result.append(field)
    }
    var r = result.toString()
    if (key.ignoreLeading == true) r = r.trimStart()
    return r
}

private data class CompareOptions(
    val numeric: Boolean? = null,
    val ignoreCase: Boolean? = null,
    val humanNumeric: Boolean? = null,
    val versionSort: Boolean? = null,
    val dictionaryOrder: Boolean? = null,
    val monthSort: Boolean? = null,
)

private fun compareValues(a: String, b: String, opts: CompareOptions): Int {
    var valA = a
    var valB = b

    if (opts.dictionaryOrder == true) {
        valA = toDictionaryOrder(valA)
        valB = toDictionaryOrder(valB)
    }
    if (opts.ignoreCase == true) {
        valA = valA.lowercase()
        valB = valB.lowercase()
    }
    if (opts.monthSort == true) {
        return parseMonth(valA) - parseMonth(valB)
    }
    if (opts.humanNumeric == true) {
        return (parseHumanSize(valA) - parseHumanSize(valB)).toInt()
    }
    if (opts.versionSort == true) {
        return compareVersions(valA, valB)
    }
    if (opts.numeric == true) {
        val numA = valA.toDoubleOrNull() ?: 0.0
        val numB = valB.toDoubleOrNull() ?: 0.0
        return numA.compareTo(numB)
    }
    return valA.compareTo(valB)
}

internal fun createComparator(options: SortOptions): Comparator<String> {
    val globalNumeric = options.numeric
    val globalIgnoreCase = options.ignoreCase
    val globalReverse = options.reverse
    val globalHumanNumeric = options.humanNumeric
    val globalVersionSort = options.versionSort
    val globalDictionaryOrder = options.dictionaryOrder
    val globalMonthSort = options.monthSort
    val globalIgnoreLeadingBlanks = options.ignoreLeadingBlanks
    val globalStable = options.stable
    val keys = options.keys
    val fieldDelimiter = options.fieldDelimiter

    return Comparator { a, b ->
        var lineA = a
        var lineB = b

        if (globalIgnoreLeadingBlanks) {
            lineA = lineA.trimStart()
            lineB = lineB.trimStart()
        }

        if (keys.isEmpty()) {
            val opts = CompareOptions(
                numeric = globalNumeric,
                ignoreCase = globalIgnoreCase,
                humanNumeric = globalHumanNumeric,
                versionSort = globalVersionSort,
                dictionaryOrder = globalDictionaryOrder,
                monthSort = globalMonthSort,
            )
            val result = compareValues(lineA, lineB, opts)
            if (result != 0) {
                return@Comparator if (globalReverse) -result else result
            }
            if (!globalStable) {
                val tie = a.compareTo(b)
                return@Comparator if (globalReverse) -tie else tie
            }
            return@Comparator 0
        }

        for (key in keys) {
            var valA = extractKeyValue(lineA, key, fieldDelimiter)
            var valB = extractKeyValue(lineB, key, fieldDelimiter)

            if (key.ignoreLeading == true) {
                valA = valA.trimStart()
                valB = valB.trimStart()
            }

            val opts = CompareOptions(
                numeric = key.numeric ?: globalNumeric,
                ignoreCase = key.ignoreCase ?: globalIgnoreCase,
                humanNumeric = key.humanNumeric ?: globalHumanNumeric,
                versionSort = key.versionSort ?: globalVersionSort,
                dictionaryOrder = key.dictionaryOrder ?: globalDictionaryOrder,
                monthSort = key.monthSort ?: globalMonthSort,
            )
            val useReverse = key.reverse ?: globalReverse

            val result = compareValues(valA, valB, opts)
            if (result != 0) {
                return@Comparator if (useReverse) -result else result
            }
        }

        if (!globalStable) {
            val tie = a.compareTo(b)
            return@Comparator if (globalReverse) -tie else tie
        }
        0
    }
}

internal fun filterUnique(lines: List<String>, options: SortOptions): List<String> {
    if (options.keys.isEmpty()) {
        if (options.ignoreCase) {
            val seen = HashSet<String>()
            return lines.filter { seen.add(it.lowercase()) }
        }
        return lines.distinct()
    }

    val key = options.keys[0]
    val seen = HashSet<String>()
    return lines.filter { line ->
        var keyVal = extractKeyValue(line, key, options.fieldDelimiter)
        if (key.ignoreCase ?: options.ignoreCase) {
            keyVal = keyVal.lowercase()
        }
        seen.add(keyVal)
    }
}

// ---- The sort command itself (sort/sort.ts) ----

object SortCommand : Command {
    override val name = "sort"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "sort", "sort lines of text files",
                "sort [OPTION]... [FILE]...",
                listOf(
                    "-b, --ignore-leading-blanks  ignore leading blanks",
                    "-d, --dictionary-order  consider only blanks and alphanumeric characters",
                    "-f, --ignore-case    fold lower case to upper case characters",
                    "-h, --human-numeric-sort  compare human readable numbers (e.g., 2K 1G)",
                    "-M, --month-sort     compare (unknown) < 'JAN' < ... < 'DEC'",
                    "-n, --numeric-sort   compare according to string numerical value",
                    "-r, --reverse        reverse the result of comparisons",
                    "-V, --version-sort   natural sort of (version) numbers within text",
                    "-c, --check          check for sorted input; do not sort",
                    "-o, --output=FILE    write result to FILE instead of stdout",
                    "-s, --stable         stabilize sort by disabling last-resort comparison",
                    "-u, --unique         output only unique lines",
                    "-k, --key=KEYDEF     sort via a key; KEYDEF gives location and type",
                    "-t, --field-separator=SEP  use SEP as field separator",
                    "    --help           display this help and exit",
                ),
            )
        }

        val options = SortOptions()
        val files = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-r" || arg == "--reverse" -> options.reverse = true
                arg == "-n" || arg == "--numeric-sort" -> options.numeric = true
                arg == "-u" || arg == "--unique" -> options.unique = true
                arg == "-f" || arg == "--ignore-case" -> options.ignoreCase = true
                arg == "-h" || arg == "--human-numeric-sort" -> options.humanNumeric = true
                arg == "-V" || arg == "--version-sort" -> options.versionSort = true
                arg == "-d" || arg == "--dictionary-order" -> options.dictionaryOrder = true
                arg == "-M" || arg == "--month-sort" -> options.monthSort = true
                arg == "-b" || arg == "--ignore-leading-blanks" -> options.ignoreLeadingBlanks = true
                arg == "-s" || arg == "--stable" -> options.stable = true
                arg == "-c" || arg == "--check" -> options.checkOnly = true
                arg == "-o" || arg == "--output" -> options.outputFile = args.getOrNull(++i)
                arg.startsWith("-o") -> options.outputFile = arg.substring(2).ifEmpty { null }
                arg.startsWith("--output=") -> options.outputFile = arg.substring(9).ifEmpty { null }
                arg == "-t" || arg == "--field-separator" -> options.fieldDelimiter = args.getOrNull(++i)
                arg.startsWith("-t") -> options.fieldDelimiter = arg.substring(2).ifEmpty { null }
                arg.startsWith("--field-separator=") -> options.fieldDelimiter = arg.substring(18).ifEmpty { null }
                arg == "-k" || arg == "--key" -> {
                    val keyArg = args.getOrNull(++i)
                    if (keyArg != null) {
                        parseKeySpec(keyArg)?.let { options.keys.add(it) }
                    }
                }
                arg.startsWith("-k") -> {
                    parseKeySpec(arg.substring(2))?.let { options.keys.add(it) }
                }
                arg.startsWith("--key=") -> {
                    parseKeySpec(arg.substring(6))?.let { options.keys.add(it) }
                }
                arg.startsWith("--") -> return unknownOption("sort", arg)
                arg.startsWith("-") && !arg.startsWith("--") -> {
                    var hasUnknown = false
                    for (c in arg.substring(1)) {
                        when (c) {
                            'r' -> options.reverse = true
                            'n' -> options.numeric = true
                            'u' -> options.unique = true
                            'f' -> options.ignoreCase = true
                            'h' -> options.humanNumeric = true
                            'V' -> options.versionSort = true
                            'd' -> options.dictionaryOrder = true
                            'M' -> options.monthSort = true
                            'b' -> options.ignoreLeadingBlanks = true
                            's' -> options.stable = true
                            'c' -> options.checkOnly = true
                            else -> { hasUnknown = true; break }
                        }
                    }
                    if (hasUnknown) return unknownOption("sort", arg)
                }
                else -> files.add(arg)
            }
            i++
        }

        // Read content
        val content = if (files.isEmpty()) {
            ctx.stdin.toString(Charsets.UTF_8)
        } else {
            val sb = StringBuilder()
            for (file in files) {
                try {
                    sb.append(ctx.fs.readFile(ctx.fs.resolvePath(ctx.cwd, file)))
                } catch (_: Exception) {
                    return ExecResult(stdout = "", stderr = "sort: $file: No such file or directory\n", exitCode = 2)
                }
            }
            sb.toString()
        }

        var lines = content.split("\n").toMutableList()
        if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.size - 1)

        val comparator = createComparator(options)

        if (options.checkOnly) {
            val checkFile = files.firstOrNull() ?: "-"
            for (idx in 1 until lines.size) {
                if (comparator.compare(lines[idx - 1], lines[idx]) > 0) {
                    return ExecResult(
                        stdout = "",
                        stderr = "sort: $checkFile:${idx + 1}: disorder: ${lines[idx]}\n",
                        exitCode = 1,
                    )
                }
            }
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        lines.sortWith(comparator)

        if (options.unique) {
            lines = filterUnique(lines, options).toMutableList()
        }

        val output = if (lines.isNotEmpty()) lines.joinToString("\n") + "\n" else ""

        if (options.outputFile != null) {
            val outPath = ctx.fs.resolvePath(ctx.cwd, options.outputFile!!)
            ctx.fs.writeFile(outPath, output)
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        return ExecResult(stdout = output, stderr = "", exitCode = 0)
    }
}