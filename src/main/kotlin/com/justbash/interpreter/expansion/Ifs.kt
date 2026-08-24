package com.justbash.interpreter.expansion

private const val DEFAULT_IFS = " \t\n"
private const val IFS_WHITESPACE = " \t\n"
fun getIfs(env: MutableMap<String, String>): String = env["IFS"] ?: DEFAULT_IFS
fun isIfsEmpty(env: MutableMap<String, String>): Boolean = env["IFS"] == ""
fun isIfsWhitespaceOnly(env: MutableMap<String, String>): Boolean { val ifs = getIfs(env); if (ifs == "") return true; for (ch in ifs) if (ch != ' ' && ch != '\t' && ch != '\n') return false; return true }
fun buildIfsCharClassPattern(ifs: String): String { var hasDash = false; val parts = ArrayList<String>(); for (c in ifs) { when { c == '-' -> hasDash = true; c in "\\^`$.*+?()[]{}|" -> parts.add("\\$c"); c == '\t' -> parts.add("\\t"); c == '\n' -> parts.add("\\n"); else -> parts.add(c.toString()) } }; if (hasDash) parts.add("\\-"); return parts.joinToString("") }
fun getIfsSeparator(env: MutableMap<String, String>): String { val ifs = env["IFS"] ?: return " "; return if (ifs.isEmpty()) "" else ifs[0].toString() }
private fun isIfsWhitespace(ch: Char): Boolean = ch in IFS_WHITESPACE
private data class IfsCategories(val whitespace: Set<Char>, val nonWhitespace: Set<Char>)
private fun categorizeIfs(ifs: String): IfsCategories { val ws = LinkedHashSet<Char>(); val nws = LinkedHashSet<Char>(); for (ch in ifs) if (isIfsWhitespace(ch)) ws.add(ch) else nws.add(ch); return IfsCategories(ws, nws) }
data class IfsExpansionSplitResult(val words: List<String>, val hadLeadingDelimiter: Boolean, val hadTrailingDelimiter: Boolean)
private fun assertIfsPushAllowed(length: Int, maxArrayElements: Int) { if (length >= maxArrayElements) throw ExecutionLimitError("array element limit exceeded ($maxArrayElements)", "array_elements") }
fun splitByIfsForExpansionEx(value: String, ifs: String, maxArrayElements: Int): IfsExpansionSplitResult {
    if (ifs == "") { if (value.isNotEmpty()) assertIfsPushAllowed(0, maxArrayElements); return IfsExpansionSplitResult(if (value.isNotEmpty()) listOf(value) else emptyList(), false, false) }
    if (value == "") return IfsExpansionSplitResult(emptyList(), false, false)
    val (whitespace, nonWhitespace) = categorizeIfs(ifs); val words = ArrayList<String>(); var pos = 0; var hadLeading = false; var hadTrailing = false
    val ls = pos; while (pos < value.length && value[pos] in whitespace) pos++; if (pos > ls) hadLeading = true
    if (pos >= value.length) return IfsExpansionSplitResult(emptyList(), true, true)
    if (value[pos] in nonWhitespace) { assertIfsPushAllowed(words.size, maxArrayElements); words.add(""); pos++; while (pos < value.length && value[pos] in whitespace) pos++ }
    while (pos < value.length) { val ws = pos; while (pos < value.length) { val ch = value[pos]; if (ch in whitespace || ch in nonWhitespace) break; pos++ }; assertIfsPushAllowed(words.size, maxArrayElements); words.add(value.substring(ws, pos)); if (pos >= value.length) { hadTrailing = false; break }; val bdp = pos; while (pos < value.length && value[pos] in whitespace) pos++; if (pos < value.length && value[pos] in nonWhitespace) { pos++; while (pos < value.length && value[pos] in whitespace) pos++; while (pos < value.length && value[pos] in nonWhitespace) { assertIfsPushAllowed(words.size, maxArrayElements); words.add(""); pos++; while (pos < value.length && value[pos] in whitespace) pos++ } }; if (pos >= value.length && pos > bdp) hadTrailing = true }
    return IfsExpansionSplitResult(words, hadLeading, hadTrailing)
}
fun splitByIfsForExpansion(value: String, ifs: String, maxArrayElements: Int): List<String> = splitByIfsForExpansionEx(value, ifs, maxArrayElements).words
