package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * xan - CSV toolkit command.
 *
 * Port of just-bash `xan` command. Provides 15 core subcommands for CSV data
 * manipulation. Uses an inline CSV parser (no moonblade dependency).
 *
 * Subcommands:
 *   cat, count, head, tail, select, filter, map, sort, dedup,
 *   stats, frequency, agg, rename, search, view
 */
object XanCommand : Command {
    override val name = "xan"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (args.isEmpty() || hasHelpFlag(args)) {
            return showHelp(
                "xan", "CSV toolkit for data manipulation",
                "xan <COMMAND> [OPTIONS] [FILE]",
                description = listOf(
                    "COMMANDS:",
                    "  cat      Concatenate CSV files",
                    "  count    Count rows",
                    "  head     Show first N rows",
                    "  tail     Show last N rows",
                    "  select   Select columns",
                    "  filter   Filter rows by expression",
                    "  map      Add/modify columns",
                    "  sort     Sort by columns",
                    "  dedup    Deduplicate rows",
                    "  stats    Column statistics",
                    "  frequency  Frequency table",
                    "  agg      Group and aggregate",
                    "  rename   Rename columns",
                    "  search   Search for pattern",
                    "  view     Pretty-print as table",
                ),
            )
        }

        val subcommand = args[0]
        val subArgs = args.drop(1)

        if (hasHelpFlag(subArgs)) {
            return subHelp(subcommand)
        }

        return when (subcommand) {
            "cat" -> cmdCat(subArgs, ctx)
            "count" -> cmdCount(subArgs, ctx)
            "head" -> cmdHead(subArgs, ctx)
            "tail" -> cmdTail(subArgs, ctx)
            "select" -> cmdSelect(subArgs, ctx)
            "filter" -> cmdFilter(subArgs, ctx)
            "map" -> cmdMap(subArgs, ctx)
            "sort" -> cmdSort(subArgs, ctx)
            "dedup" -> cmdDedup(subArgs, ctx)
            "stats" -> cmdStats(subArgs, ctx)
            "freq", "frequency" -> cmdFrequency(subArgs, ctx)
            "agg" -> cmdAgg(subArgs, ctx)
            "rename" -> cmdRename(subArgs, ctx)
            "search" -> cmdSearch(subArgs, ctx)
            "view" -> cmdView(subArgs, ctx)
            else -> ExecResult(
                stderr = "xan: unknown command '$subcommand'\nRun 'xan --help' for usage.\n",
                exitCode = 1,
            )
        }
    }

    // ── subcommand help ────────────────────────────────────────────────────

    private fun subHelp(sub: String): ExecResult = when (sub) {
        "cat" -> showHelp("xan cat", "Concatenate CSV files", "xan cat [OPTIONS] FILES...")
        "count" -> showHelp("xan count", "Count rows", "xan count [FILE]")
        "head" -> showHelp("xan head", "Show first N rows", "xan head [-n N] [FILE]",
            options = listOf("-n N    Number of rows (default 10)"))
        "tail" -> showHelp("xan tail", "Show last N rows", "xan tail [-n N] [FILE]",
            options = listOf("-n N    Number of rows (default 10)"))
        "select" -> showHelp("xan select", "Select columns", "xan select COLS [FILE]")
        "filter" -> showHelp("xan filter", "Filter rows", "xan filter COL OP VALUE [FILE]",
            options = listOf("Ops: ==, !=, >, <, >=, <=, contains, starts_with, ends_with, is_empty, is_not_empty"))
        "map" -> showHelp("xan map", "Add/modify column", "xan map COL EXPR [FILE]")
        "sort" -> showHelp("xan sort", "Sort by columns", "xan sort [-r] [-n] COL1,COL2 [FILE]")
        "dedup" -> showHelp("xan dedup", "Deduplicate rows", "xan dedup [-s COLS] [FILE]")
        "stats" -> showHelp("xan stats", "Column statistics", "xan stats [-s COLS] [FILE]")
        "frequency", "freq" -> showHelp("xan frequency", "Frequency table", "xan frequency [-l N] COL [FILE]")
        "agg" -> showHelp("xan agg", "Group and aggregate", "xan agg -g GROUP_COL -s AGG_COL FUNC [FILE]",
            options = listOf("-g COL    Group-by column", "-s COL    Aggregate column", "FUNC: sum, avg, count, min, max"))
        "rename" -> showHelp("xan rename", "Rename columns", "xan rename OLD NEW [FILE]")
        "search" -> showHelp("xan search", "Search for pattern", "xan search [-i] [-v] PATTERN [FILE]",
            options = listOf("-i        Ignore case", "-v        Invert match", "-s COLS  Search only these columns"))
        "view" -> showHelp("xan view", "Pretty-print as table", "xan view [-n N] [FILE]")
        else -> showHelp("xan", "CSV toolkit", "xan <COMMAND> [OPTIONS] [FILE]")
    }

    // ── inline CSV parser ───────────────────────────────────────────────────

    /**
     * Parse CSV text into headers and rows. Each row is a List<String> (one
     * entry per column). Handles basic double-quote quoting; no escaping of
     * quotes within quotes.
     */
    private fun parseCsv(text: String): Pair<List<String>, List<List<String>>> {
        val lines = text.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return Pair(emptyList(), emptyList())

        val allRows = lines.map { parseCsvLine(it) }
        if (allRows.isEmpty()) return Pair(emptyList(), emptyList())

        val headers = allRows[0]
        val data = allRows.drop(1)
        return Pair(headers, data)
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuotes -> {
                    inQuotes = true
                }
                ch == '"' && inQuotes -> {
                    // If next char is also quote, it's an escaped quote
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++ // skip the escaped quote
                    } else {
                        inQuotes = false
                    }
                }
                ch == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> {
                    current.append(ch)
                }
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    /** Format rows back to CSV text. */
    private fun formatCsv(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append(headers.joinToString(",") { csvEscape(it) }).append("\n")
        for (row in rows) {
            sb.append(row.joinToString(",") { csvEscape(it) }).append("\n")
        }
        return sb.toString()
    }

    private fun csvEscape(value: String): String {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"${value.replace("\"", "\"\"")}\""
        }
        return value
    }

    /** Read CSV input from stdin or file argument. */
    private fun readCsvInput(args: List<String>, ctx: CommandContext): Triple<List<String>, List<List<String>>, ExecResult?> {
        val file = args.firstOrNull { !it.startsWith("-") }
        val input: String
        if (file == null || file == "-") {
            input = ctx.stdin.toString(Charsets.UTF_8)
        } else {
            try {
                val path = ctx.fs.resolvePath(ctx.cwd, file)
                input = ctx.fs.readFile(path)
            } catch (_: Exception) {
                return Triple(emptyList(), emptyList(), ExecResult(
                    stderr = "xan: $file: No such file or directory\n",
                    exitCode = 1,
                ))
            }
        }
        // Check --no-headers flag
        if (args.contains("--no-headers")) {
            val lines = input.trim().lines().filter { it.isNotBlank() }
            val rows = lines.map { parseCsvLine(it) }
            // Generate synthetic headers
            val maxCols = rows.maxOfOrNull { it.size } ?: 0
            val headers = (0 until maxCols).map { "c$it" }
            return Triple(headers, rows, null)
        }
        val (headers, data) = parseCsv(input)
        return Triple(headers, data, null)
    }

    // ── 1. cat ──────────────────────────────────────────────────────────────

    private fun cmdCat(args: List<String>, ctx: CommandContext): ExecResult {
        val (headers, data, error) = readCsvInput(args.filter { it != "--no-headers" }, ctx)
        if (error != null) return error
        return ExecResult(stdout = formatCsv(headers, data))
    }

    // ── 2. count ────────────────────────────────────────────────────────────

    private fun cmdCount(args: List<String>, ctx: CommandContext): ExecResult {
        val (_, data, error) = readCsvInput(args, ctx)
        if (error != null) return error
        return ExecResult(stdout = "${data.size}\n")
    }

    // ── 3. head ─────────────────────────────────────────────────────────────

    private fun cmdHead(args: List<String>, ctx: CommandContext): ExecResult {
        var n = 10
        val filtered = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            if ((args[i] == "-n" || args[i] == "-l") && i + 1 < args.size) {
                n = args[++i].toIntOrNull() ?: 10
            } else {
                filtered.add(args[i])
            }
            i++
        }
        val (headers, data, error) = readCsvInput(filtered, ctx)
        if (error != null) return error
        return ExecResult(stdout = formatCsv(headers, data.take(n)))
    }

    // ── 4. tail ─────────────────────────────────────────────────────────────

    private fun cmdTail(args: List<String>, ctx: CommandContext): ExecResult {
        var n = 10
        val filtered = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            if ((args[i] == "-n" || args[i] == "-l") && i + 1 < args.size) {
                n = args[++i].toIntOrNull() ?: 10
            } else {
                filtered.add(args[i])
            }
            i++
        }
        val (headers, data, error) = readCsvInput(filtered, ctx)
        if (error != null) return error
        return ExecResult(stdout = formatCsv(headers, data.takeLast(n)))
    }

    // ── 5. select ───────────────────────────────────────────────────────────

    private fun cmdSelect(args: List<String>, ctx: CommandContext): ExecResult {
        val positional = args.filter { !it.startsWith("-") }
        if (positional.isEmpty()) {
            return ExecResult(stderr = "xan select: no columns specified\n", exitCode = 1)
        }
        val colSpec = positional[0]
        val fileArgs = positional.drop(1)
        val (headers, data, error) = readCsvInput(fileArgs, ctx)
        if (error != null) return error

        val selectedCols = colSpec.split(",").map { it.trim() }
        val indices = mutableListOf<Int>()

        for (sel in selectedCols) {
            val idx = sel.toIntOrNull()
            if (idx != null) {
                if (idx in headers.indices) indices.add(idx)
            } else {
                val hIdx = headers.indexOf(sel)
                if (hIdx >= 0) indices.add(hIdx)
            }
        }
        if (indices.isEmpty()) {
            return ExecResult(stderr = "xan select: no matching columns\n", exitCode = 1)
        }

        val newHeaders = indices.map { headers[it] }
        val newData = data.map { row -> indices.map { idx -> if (idx < row.size) row[idx] else "" } }
        return ExecResult(stdout = formatCsv(newHeaders, newData))
    }

    // ── 6. filter ───────────────────────────────────────────────────────────

    private fun cmdFilter(args: List<String>, ctx: CommandContext): ExecResult {
        var invert = false
        val filtered = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-v" || args[i] == "--invert" -> invert = true
                else -> filtered.add(args[i])
            }
            i++
        }

        val positional = filtered.filter { !it.startsWith("-") }
        if (positional.size < 3) {
            return ExecResult(
                stderr = "xan filter: usage: xan filter [-v] COL OP VALUE [FILE]\n",
                exitCode = 1,
            )
        }
        val col = positional[0]
        val op = positional[1]
        val value = positional[2]
        val fileArgs = positional.drop(3)

        val (headers, data, error) = readCsvInput(fileArgs, ctx)
        if (error != null) return error

        val colIdx = headers.indexOf(col)
        if (colIdx < 0) {
            return ExecResult(stderr = "xan filter: column '$col' not found\n", exitCode = 1)
        }

        val filteredRows = data.filter { row ->
            val cell = if (colIdx < row.size) row[colIdx] else ""
            val matches = evalFilterOp(cell, op, value)
            if (invert) !matches else matches
        }

        return ExecResult(stdout = formatCsv(headers, filteredRows))
    }

    private fun evalFilterOp(cell: String, op: String, value: String): Boolean = when (op) {
        "==" -> cell == value
        "!=" -> cell != value
        ">" -> (cell.toDoubleOrNull() ?: 0.0) > (value.toDoubleOrNull() ?: 0.0)
        "<" -> (cell.toDoubleOrNull() ?: 0.0) < (value.toDoubleOrNull() ?: 0.0)
        ">=" -> (cell.toDoubleOrNull() ?: 0.0) >= (value.toDoubleOrNull() ?: 0.0)
        "<=" -> (cell.toDoubleOrNull() ?: 0.0) <= (value.toDoubleOrNull() ?: 0.0)
        "contains" -> cell.contains(value)
        "starts_with" -> cell.startsWith(value)
        "ends_with" -> cell.endsWith(value)
        "is_empty" -> cell.isEmpty()
        "is_not_empty" -> cell.isNotEmpty()
        else -> false
    }

    // ── 7. map ──────────────────────────────────────────────────────────────

    private fun cmdMap(args: List<String>, ctx: CommandContext): ExecResult {
        val positional = args.filter { !it.startsWith("-") }
        if (positional.size < 2) {
            return ExecResult(
                stderr = "xan map: usage: xan map COL EXPR [FILE]\n",
                exitCode = 1,
            )
        }
        val col = positional[0]
        val expr = positional[1]
        val fileArgs = positional.drop(2)

        val (headers, data, error) = readCsvInput(fileArgs, ctx)
        if (error != null) return error

        // Determine if column exists (modify) or is new (add)
        val existingIdx = headers.indexOf(col)
        val newHeaders = if (existingIdx >= 0) {
            headers.toList()
        } else {
            headers + col
        }

        val newData = data.map { row ->
            val newRow = row.toMutableList()
            // Ensure row has enough columns
            while (newRow.size < newHeaders.size) newRow.add("")

            val evaluated = evalMapExpr(expr, row, headers)
            if (existingIdx >= 0) {
                newRow[existingIdx] = evaluated
            } else {
                if (newRow.size <= newHeaders.size - 1) {
                    newRow.add(evaluated)
                } else {
                    newRow[newHeaders.size - 1] = evaluated
                }
            }
            // Trim to header count
            newRow.take(newHeaders.size)
        }

        return ExecResult(stdout = formatCsv(newHeaders, newData))
    }

    private fun evalMapExpr(expr: String, row: List<String>, headers: List<String>): String {
        // {COL} references another column
        if (expr.startsWith("{") && expr.endsWith("}") && !expr.contains("+")) {
            val refCol = expr.removeSurrounding("{", "}")
            val idx = headers.indexOf(refCol)
            return if (idx >= 0 && idx < row.size) row[idx] else ""
        }
        // Concatenation with +
        if (expr.contains("+")) {
            val parts = expr.split("+").map { it.trim() }
            return parts.joinToString("") { part ->
                if (part.startsWith("{") && part.endsWith("}")) {
                    val refCol = part.removeSurrounding("{", "}")
                    val idx = headers.indexOf(refCol)
                    if (idx >= 0 && idx < row.size) row[idx] else ""
                } else {
                    // String literal: strip surrounding quotes
                    part.removeSurrounding("\"").removeSurrounding("'")
                }
            }
        }
        // String literal
        return expr.removeSurrounding("\"").removeSurrounding("'")
    }

    // ── 8. sort ─────────────────────────────────────────────────────────────

    private fun cmdSort(args: List<String>, ctx: CommandContext): ExecResult {
        var reverse = false
        var numeric = false
        val filtered = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-r" || args[i] == "--reverse" -> reverse = true
                args[i] == "-n" || args[i] == "--numeric" -> numeric = true
                else -> filtered.add(args[i])
            }
            i++
        }

        val positional = filtered.filter { !it.startsWith("-") }
        val colSpec = positional.firstOrNull() ?: ""
        val fileArgs = if (colSpec.isNotEmpty()) positional.drop(1) else positional

        val (headers, data, error) = readCsvInput(fileArgs, ctx)
        if (error != null) return error

        val sortCols = if (colSpec.isNotEmpty()) colSpec.split(",").map { it.trim() } else headers.take(1)
        val sortIndices = sortCols.map { col -> headers.indexOf(col).takeIf { it >= 0 } ?: 0 }

        val sorted = data.sortedWith(Comparator { a, b ->
            for (idx in sortIndices) {
                val va = if (idx < a.size) a[idx] else ""
                val vb = if (idx < b.size) b[idx] else ""
                val cmp = if (numeric) {
                    (va.toDoubleOrNull() ?: 0.0).compareTo(vb.toDoubleOrNull() ?: 0.0)
                } else {
                    va.compareTo(vb)
                }
                if (cmp != 0) return@Comparator if (reverse) -cmp else cmp
            }
            0
        })

        return ExecResult(stdout = formatCsv(headers, sorted))
    }

    // ── 9. dedup ────────────────────────────────────────────────────────────

    private fun cmdDedup(args: List<String>, ctx: CommandContext): ExecResult {
        var dedupCols: List<String>? = null
        val filtered = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                (args[i] == "-s" || args[i] == "--select") && i + 1 < args.size -> {
                    dedupCols = args[++i].split(",").map { it.trim() }
                }
                else -> filtered.add(args[i])
            }
            i++
        }

        val (headers, data, error) = readCsvInput(filtered, ctx)
        if (error != null) return error

        val indices = dedupCols?.map { col -> headers.indexOf(col).takeIf { it >= 0 } ?: -1 }?.filter { it >= 0 }

        val seen = mutableSetOf<String>()
        val deduped = data.filter { row ->
            val key = if (indices != null && indices.isNotEmpty()) {
                indices.joinToString("|") { idx -> if (idx < row.size) row[idx] else "" }
            } else {
                row.joinToString("|")
            }
            if (seen.contains(key)) false else { seen.add(key); true }
        }

        return ExecResult(stdout = formatCsv(headers, deduped))
    }

    // ── 10. stats ───────────────────────────────────────────────────────────

    private fun cmdStats(args: List<String>, ctx: CommandContext): ExecResult {
        var selectCols: List<String>? = null
        val filtered = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                (args[i] == "-s" || args[i] == "--select") && i + 1 < args.size -> {
                    selectCols = args[++i].split(",").map { it.trim() }
                }
                else -> filtered.add(args[i])
            }
            i++
        }

        val (headers, data, error) = readCsvInput(filtered, ctx)
        if (error != null) return error

        val targetCols = selectCols ?: headers
        val statsHeaders = listOf("field", "type", "count", "min", "max", "mean", "stddev", "nulls")
        val results = mutableListOf<List<String>>()

        for (col in targetCols) {
            val colIdx = headers.indexOf(col)
            if (colIdx < 0) continue

            val values = data.mapNotNull { row ->
                if (colIdx < row.size) {
                    val v = row[colIdx]
                    if (v.isEmpty()) null else v.toDoubleOrNull()
                } else null
            }
            val nulls = data.size - values.size
            val count = values.size

            if (count == 0) {
                results.add(listOf(col, "String", "0", "", "", "", "", nulls.toString()))
                continue
            }

            val minVal = values.minOrNull() ?: 0.0
            val maxVal = values.maxOrNull() ?: 0.0
            val sum = values.sum()
            val mean = sum / count
            val variance = values.map { (it - mean) * (it - mean) }.sum() / count
            val stddev = sqrt(variance)

            val isNumeric = values.size == count

            results.add(listOf(
                col,
                if (isNumeric) "Number" else "String",
                count.toString(),
                if (isNumeric) formatNum(minVal) else "",
                if (isNumeric) formatNum(maxVal) else "",
                if (isNumeric) formatNum(mean) else "",
                if (isNumeric) formatNum(stddev) else "",
                nulls.toString(),
            ))
        }

        return ExecResult(stdout = formatCsv(statsHeaders, results))
    }

    private fun formatNum(d: Double): String {
        if (d == d.toLong().toDouble()) return d.toLong().toString()
        return "%.4f".format(d)
    }

    // ── 11. frequency ───────────────────────────────────────────────────────

    private fun cmdFrequency(args: List<String>, ctx: CommandContext): ExecResult {
        var limit = 10
        val filtered = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                (args[i] == "-l" || args[i] == "--limit") && i + 1 < args.size -> {
                    limit = args[++i].toIntOrNull() ?: 10
                }
                else -> filtered.add(args[i])
            }
            i++
        }

        val positional = filtered.filter { !it.startsWith("-") }
        if (positional.isEmpty()) {
            return ExecResult(stderr = "xan frequency: no column specified\n", exitCode = 1)
        }
        val col = positional[0]
        val fileArgs = positional.drop(1)

        val (headers, data, error) = readCsvInput(fileArgs, ctx)
        if (error != null) return error

        val colIdx = headers.indexOf(col)
        if (colIdx < 0) {
            return ExecResult(stderr = "xan frequency: column '$col' not found\n", exitCode = 1)
        }

        val counts = LinkedHashMap<String, Int>()
        for (row in data) {
            val value = if (colIdx < row.size) row[colIdx] else ""
            val key = value.ifEmpty { "<empty>" }
            counts[key] = (counts[key] ?: 0) + 1
        }

        val sorted = counts.entries.sortedByDescending { it.value }.take(limit)
        val freqHeaders = listOf("field", "value", "count")
        val freqData = sorted.map { (value, count) ->
            listOf(col, value, count.toString())
        }

        return ExecResult(stdout = formatCsv(freqHeaders, freqData))
    }

    // ── 12. agg ─────────────────────────────────────────────────────────────

    private fun cmdAgg(args: List<String>, ctx: CommandContext): ExecResult {
        var groupCol: String? = null
        var aggCol: String? = null
        var func: String? = null
        val fileArgs = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            when {
                (args[i] == "-g" || args[i] == "--groupby") && i + 1 < args.size -> {
                    groupCol = args[++i]
                }
                (args[i] == "-s" || args[i] == "--select") && i + 1 < args.size -> {
                    aggCol = args[++i]
                }
                !args[i].startsWith("-") -> {
                    when {
                        groupCol == null -> groupCol = args[i]
                        aggCol == null -> aggCol = args[i]
                        func == null -> func = args[i]
                        else -> fileArgs.add(args[i])
                    }
                }
            }
            i++
        }

        if (groupCol == null || aggCol == null || func == null) {
            return ExecResult(
                stderr = "xan agg: usage: xan agg -g GROUP_COL -s AGG_COL FUNC [FILE]\n",
                exitCode = 1,
            )
        }

        val group = groupCol
        val agg = aggCol
        val funName = func

        val (headers, data, error) = readCsvInput(fileArgs, ctx)
        if (error != null) return error

        val groupIdx = headers.indexOf(group)
        val aggIdx = headers.indexOf(agg)
        if (groupIdx < 0) return ExecResult(stderr = "xan agg: column '$group' not found\n", exitCode = 1)
        if (aggIdx < 0) return ExecResult(stderr = "xan agg: column '$agg' not found\n", exitCode = 1)

        // Group data
        val groups = LinkedHashMap<String, MutableList<List<String>>>()
        for (row in data) {
            val key = if (groupIdx < row.size) row[groupIdx] else ""
            groups.getOrPut(key) { mutableListOf() }.add(row)
        }

        val outHeaders = listOf(group, "${funName}_$agg")
        val outData = mutableListOf<List<String>>()

        for ((key, groupRows) in groups) {
            val aggValues = groupRows.mapNotNull { row ->
                if (aggIdx < row.size) row[aggIdx].toDoubleOrNull() else null
            }
            val result = when (funName) {
                "sum" -> aggValues.sum()
                "avg" -> if (aggValues.isNotEmpty()) aggValues.sum() / aggValues.size else 0.0
                "count" -> aggValues.size.toDouble()
                "min" -> aggValues.minOrNull() ?: 0.0
                "max" -> aggValues.maxOrNull() ?: 0.0
                else -> aggValues.sum()
            }
            outData.add(listOf(key, formatNum(result)))
        }

        return ExecResult(stdout = formatCsv(outHeaders, outData))
    }

    // ── 13. rename ──────────────────────────────────────────────────────────

    private fun cmdRename(args: List<String>, ctx: CommandContext): ExecResult {
        val positional = args.filter { !it.startsWith("-") }
        if (positional.size < 2) {
            return ExecResult(
                stderr = "xan rename: usage: xan rename OLD NEW [FILE]\n",
                exitCode = 1,
            )
        }
        val oldName = positional[0]
        val newName = positional[1]
        val fileArgs = positional.drop(2)

        val (headers, data, error) = readCsvInput(fileArgs, ctx)
        if (error != null) return error

        val idx = headers.indexOf(oldName)
        if (idx < 0) {
            return ExecResult(stderr = "xan rename: column '$oldName' not found\n", exitCode = 1)
        }
        val newHeaders = headers.toMutableList()
        newHeaders[idx] = newName

        return ExecResult(stdout = formatCsv(newHeaders, data))
    }

    // ── 14. search ──────────────────────────────────────────────────────────

    private fun cmdSearch(args: List<String>, ctx: CommandContext): ExecResult {
        var ignoreCase = false
        var invert = false
        var selectCols: List<String>? = null
        val filtered = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-i" || args[i] == "--ignore-case" -> ignoreCase = true
                args[i] == "-v" || args[i] == "--invert" -> invert = true
                (args[i] == "-s" || args[i] == "--select") && i + 1 < args.size -> {
                    selectCols = args[++i].split(",").map { it.trim() }
                }
                else -> filtered.add(args[i])
            }
            i++
        }

        val positional = filtered.filter { !it.startsWith("-") }
        if (positional.isEmpty()) {
            return ExecResult(stderr = "xan search: no pattern specified\n", exitCode = 1)
        }
        val pattern = positional[0]
        val fileArgs = positional.drop(1)

        val (headers, data, error) = readCsvInput(fileArgs, ctx)
        if (error != null) return error

        val searchIndices = if (selectCols != null) {
            selectCols.mapNotNull { col -> headers.indexOf(col).takeIf { it >= 0 } }
        } else {
            headers.indices.toList()
        }

        val regex = try {
            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        } catch (_: Exception) {
            return ExecResult(stderr = "xan search: invalid regex pattern '$pattern'\n", exitCode = 1)
        }

        val matched = data.filter { row ->
            val matches = searchIndices.any { idx ->
                val cell = if (idx < row.size) row[idx] else ""
                regex.containsMatchIn(cell)
            }
            if (invert) !matches else matches
        }

        return ExecResult(stdout = formatCsv(headers, matched))
    }

    // ── 15. view ────────────────────────────────────────────────────────────

    private fun cmdView(args: List<String>, ctx: CommandContext): ExecResult {
        var n = 0 // 0 means all
        val filtered = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                (args[i] == "-n" || args[i] == "--limit") && i + 1 < args.size -> {
                    n = args[++i].toIntOrNull() ?: 0
                }
                else -> filtered.add(args[i])
            }
            i++
        }

        val (headers, data, error) = readCsvInput(filtered, ctx)
        if (error != null) return error

        val rows = if (n > 0) data.take(n) else data

        if (rows.isEmpty() && headers.isEmpty()) {
            return ExecResult(stdout = "(empty)\n")
        }

        // Calculate column widths
        val widths = headers.map { it.length }.toMutableList()
        for (row in rows) {
            for (j in headers.indices) {
                val cell = if (j < row.size) row[j] else ""
                widths[j] = max(widths[j], cell.length)
            }
        }

        val sb = StringBuilder()
        val border = "─"

        // Top border
        sb.append("┌")
        for (j in widths.indices) {
            if (j > 0) sb.append("┬")
            sb.append(border.repeat(widths[j] + 2))
        }
        sb.append("┐\n")

        // Header
        sb.append("│")
        for (j in headers.indices) {
            if (j > 0) sb.append("│")
            sb.append(" ${headers[j]}${" ".repeat(widths[j] - headers[j].length + 1)}")
        }
        sb.append("│\n")

        // Separator
        sb.append("├")
        for (j in widths.indices) {
            if (j > 0) sb.append("┼")
            sb.append(border.repeat(widths[j] + 2))
        }
        sb.append("┤\n")

        // Data rows
        for (row in rows) {
            sb.append("│")
            for (j in headers.indices) {
                if (j > 0) sb.append("│")
                val cell = if (j < row.size) row[j] else ""
                sb.append(" ${cell}${" ".repeat(widths[j] - cell.length + 1)}")
            }
            sb.append("│\n")
        }

        // Bottom border
        sb.append("└")
        for (j in widths.indices) {
            if (j > 0) sb.append("┴")
            sb.append(border.repeat(widths[j] + 2))
        }
        sb.append("┘\n")

        return ExecResult(stdout = sb.toString())
    }
}