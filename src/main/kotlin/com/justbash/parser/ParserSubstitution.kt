package com.justbash.parser

import com.justbash.ast.CommandSubstitutionPart
import com.justbash.ast.ProcessSubstitutionPart
import com.justbash.ast.ScriptNode

fun interface ParserFactory {
    fun create(): Parser
}

fun interface ErrorFn {
    fun report(message: String): Nothing
}

data class HeredocDelimiterResult(
    val delim: String, val endPos: Int, val quoted: Boolean,
    val unclosedQuote: Char?, val unclosedSubstitution: Boolean,
)

fun isDollarDparenSubshell(value: String, start: Int): Boolean {
    val len = value.length
    var pos = start + 3
    var depth = 2
    var inSQ = false
    var inDQ = false
    while (pos < len && depth > 0) {
        val c = value[pos]
        if (inSQ) { if (c == '\'') inSQ = false; pos++; continue }
        if (inDQ) { if (c == '\\') { pos += 2; continue }; if (c == '"') inDQ = false; pos++; continue }
        when (c) {
            '\'' -> { inSQ = true; pos++; continue }
            '"' -> { inDQ = true; pos++; continue }
            '\\' -> { pos += 2; continue }
        }
        if (c == '(') { depth++; pos++; continue }
        if (c == ')') {
            depth--
            if (depth == 1) {
                if (pos + 1 < len && value[pos + 1] == ')') return false
                return true
            }
            if (depth == 0) return false
            pos++; continue
        }
        if (depth == 1) {
            if (c == '|' && pos + 1 < len && value[pos + 1] == '|') return true
            if (c == '&' && pos + 1 < len && value[pos + 1] == '&') return true
            if (c == '|' && (pos + 1 >= len || value[pos + 1] != '|')) return true
        }
        pos++
    }
    return false
}

fun readHeredocDelimiter(value: String, pos: Int): HeredocDelimiterResult {
    val delim = StringBuilder()
    var i = pos
    var sd = 0
    var quoted = false
    var uq: Char? = null
    fun we(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == ';' || c == '&' || c == '|' || c == '<' || c == '>' || c == '(' || c == ')'
    while (i < value.length) {
        val c = value[i]
        if (c == '\'') {
            val n = sd > 0; if (!n) quoted = true; if (n) delim.append(c); i++
            while (i < value.length && value[i] != '\'') { delim.append(value[i]); i++ }
            if (i < value.length) { if (n) delim.append(value[i]); i++ } else uq = c
            continue
        }
        if (c == '"') {
            val n = sd > 0; if (!n) quoted = true; if (n) delim.append(c); i++
            while (i < value.length && value[i] != '"') {
                if (value[i] == '\\' && i + 1 < value.length) {
                    if (n) { delim.append(value[i]).append(value[i + 1]); i += 2; continue }
                    val e = value[i + 1]
                    if (e == '$' || e == '`' || e == '"' || e == '\\' || e == '\n') { i += 2; if (e != '\n') delim.append(e); continue }
                }
                delim.append(value[i]); i++
            }
            if (i < value.length) { if (n) delim.append(value[i]); i++ } else uq = c
            continue
        }
        if (c == '\\' && i + 1 < value.length) {
            if (sd > 0) { delim.append(value, i, i + 2); i += 2; continue }
            quoted = true; val e = value[i + 1]; if (e != '\n') delim.append(e); i += 2; continue
        }
        if (sd == 0 && (c == '$' || c == '<' || c == '>') && i + 1 < value.length && value[i + 1] == '(') {
            delim.append(c).append('('); sd = 1; i += 2; continue
        }
        if (sd == 0 && we(c)) break
        if (sd > 0) { if (c == '(') sd++; else if (c == ')') sd-- }
        delim.append(c); i++
    }
    return HeredocDelimiterResult(delim.toString(), i, quoted, uq, sd > 0)
}

private fun skipHeredocBodies(value: String, nlIdx: Int, heredocs: List<Pair<String, Boolean>>): Int {
    var ls = nlIdx + 1
    for ((delim, stripTabs) in heredocs) {
        while (true) {
            if (ls >= value.length) return value.length
            var le = value.indexOf('\n', ls); if (le == -1) le = value.length
            var line = value.substring(ls, le); if (stripTabs) line = line.trimStart('\t')
            if (line == delim) { ls = le + 1; break }
            if (le >= value.length) return value.length
            ls = le + 1
        }
    }
    return minOf(ls, value.length)
}

private fun findSubstitutionBodyEnd(value: String, cmdStart: Int, error: ErrorFn): Int {
    var depth = 1; var i = cmdStart
    var inSQ = false; var inDQ = false; var cd = 0; var icp = false
    var wb = StringBuilder(); val ph = mutableListOf<Pair<String, Boolean>>(); var ad = 0
    fun isL(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'
    while (i < value.length && depth > 0) {
        val c = value[i]
        if (inSQ) { if (c == '\'') inSQ = false }
        else if (inDQ) { if (c == '\\' && i + 1 < value.length) i++; else if (c == '"') inDQ = false }
        else {
            if (c == '(' && i + 1 < value.length && value[i + 1] == '(') ad++
            else if (c == ')' && i + 1 < value.length && value[i + 1] == ')' && ad > 0) ad--
            if (ad == 0 && c == '<' && i + 1 < value.length && value[i + 1] == '<' && (i + 2 >= value.length || value[i + 2] != '<')) {
                var p = i + 2; var st = false
                if (p < value.length && value[p] == '-') { st = true; p++ }
                while (p < value.length && (value[p] == ' ' || value[p] == '\t')) p++
                val r = readHeredocDelimiter(value, p)
                if (r.unclosedQuote != null) error.report("unexpected EOF while looking for matching `'${r.unclosedQuote}'")
                if (r.unclosedSubstitution) error.report("unexpected EOF while looking for matching `)'")
                if (r.delim.isNotEmpty()) { ph.add(r.delim to st); wb = StringBuilder(); i = r.endPos; continue }
            }
            if (c == '\n' && ph.isNotEmpty()) { val resume = skipHeredocBodies(value, i, ph); ph.clear(); wb = StringBuilder(); i = resume; continue }
            if (c == '\'') { inSQ = true; wb = StringBuilder() }
            else if (c == '"') { inDQ = true; wb = StringBuilder() }
            else if (c == '\\' && i + 1 < value.length) { i++; wb = StringBuilder() }
            else if (isL(c)) wb.append(c)
            else {
                val ws = wb.toString()
                if (ws == "case") { cd++; icp = false }
                else if (ws == "in" && cd > 0) icp = true
                else if (ws == "esac" && cd > 0) { cd--; icp = false }
                wb = StringBuilder()
                if (c == '(') { if (i > 0 && value[i - 1] == '$') depth++; else if (!icp) depth++ }
                else if (c == ')') { if (icp) icp = false; else depth-- }
                else if (c == ';') { if (cd > 0 && i + 1 < value.length && value[i + 1] == ';') icp = true }
            }
        }
        if (depth > 0) i++
    }
    if (depth > 0) error.report("unexpected EOF while looking for matching `)'")
    return i
}

fun parseCommandSubstitutionFromString(value: String, start: Int, createParser: ParserFactory, error: ErrorFn): Pair<CommandSubstitutionPart, Int> {
    val cs = start + 2; val i = findSubstitutionBodyEnd(value, cs, error)
    val body = createParser.create().parse(value.substring(cs, i))
    return CommandSubstitutionPart(body, false) to (i + 1)
}

fun parseProcessSubstitutionFromString(value: String, start: Int, createParser: ParserFactory, error: ErrorFn): Pair<ProcessSubstitutionPart, Int> {
    val dir = if (value[start] == '<') "input" else "output"
    val bs = start + 2; val be = findSubstitutionBodyEnd(value, bs, error)
    val body = createParser.create().parse(value.substring(bs, be))
    return ProcessSubstitutionPart(body, dir) to (be + 1)
}

fun parseBacktickSubstitutionFromString(value: String, start: Int, inDQ: Boolean, createParser: ParserFactory, error: ErrorFn): Pair<CommandSubstitutionPart, Int> {
    val cs = start + 1; var i = cs; val cmd = StringBuilder()
    while (i < value.length && value[i] != '`') {
        if (value[i] == '\\') {
            val n = if (i + 1 < value.length) value[i + 1] else '\u0000'
            val sp = n == '$' || n == '`' || n == '\\' || n == '\n' || (inDQ && n == '"')
            if (sp) { if (n != '\n') cmd.append(n); i += 2 } else { cmd.append(value[i]); i++ }
        } else { cmd.append(value[i]); i++ }
    }
    if (i >= value.length) error.report("unexpected EOF while looking for matching `'")
    val body = createParser.create().parse(cmd.toString())
    return CommandSubstitutionPart(body, true) to (i + 1)
}