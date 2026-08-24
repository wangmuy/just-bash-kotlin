package com.justbash.parser

import com.justbash.ast.*

fun skipArithWhitespace(input: String, pos: Int): Int {
    var p = pos
    while (p < input.length) {
        if (input[p] == '\\' && p + 1 < input.length && input[p + 1] == '\n') { p += 2; continue }
        if (input[p].isWhitespace()) { p++; continue }
        break
    }
    return p
}

val ARITH_ASSIGN_OPS = listOf("=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", "&=", "|=", "^=")

fun parseArithNumber(str: String): Long {
    if (str.contains('#')) {
        val parts = str.split("#", limit = 2)
        if (parts.size != 2) return Long.MIN_VALUE
        val (bs, ns) = parts
        if (!bs.matches(Regex("^\\d+$")) || ns.isEmpty()) return Long.MIN_VALUE
        val base = bs.toLong()
        if (base < 2 || base > 64) return Long.MIN_VALUE
        var result = 0L
        for (ch in ns) {
            val dv = when {
                ch in '0'..'9' -> ch - '0'
                ch in 'a'..'z' -> ch - 'a' + 10
                ch in 'A'..'Z' -> ch - 'A' + if (base <= 36) 10 else 36
                ch == '@' -> 62
                ch == '_' -> 63
                else -> return Long.MIN_VALUE
            }
            if (dv.toLong() >= base) return Long.MIN_VALUE
            result = result * base + dv
            if (result > Long.MAX_VALUE / 2) return Long.MAX_VALUE
        }
        return result
    }
    if (str.startsWith("0x") || str.startsWith("0X")) return str.substring(2).toLongOrNull(16) ?: Long.MIN_VALUE
    if (str.startsWith("0") && str.length > 1 && str.matches(Regex("^[0-9]+$"))) {
        if (str.contains('8') || str.contains('9')) return Long.MIN_VALUE
        return str.toLongOrNull(8) ?: Long.MIN_VALUE
    }
    return str.toLongOrNull(10) ?: Long.MIN_VALUE
}

fun parseNestedArithmetic(
    parseArithExpr: (Parser, String, Int) -> Pair<ArithExpr, Int>,
    p: Parser, input: String, currentPos: Int,
): Pair<ArithExpr, Int>? {
    if (!input.startsWith("$((", currentPos)) return null
    var pos = currentPos + 3; var depth = 1
    val es = pos
    while (pos < input.length - 1 && depth > 0) {
        if (input[pos] == '(' && input[pos + 1] == '(') { depth++; pos += 2 }
        else if (input[pos] == ')' && input[pos + 1] == ')') { depth--; if (depth > 0) pos += 2 }
        else pos++
    }
    val nested = input.substring(es, pos)
    val (expr, _) = parseArithExpr(p, nested, 0)
    pos += 2
    return ArithNestedNode(expr) to pos
}

fun parseAnsiCQuoting(input: String, currentPos: Int): Pair<ArithExpr, Int>? {
    if (!input.startsWith("\$'", currentPos)) return null
    var pos = currentPos + 2
    val content = StringBuilder()
    while (pos < input.length && input[pos] != '\'') {
        if (input[pos] == '\\' && pos + 1 < input.length) {
            when (val n = input[pos + 1]) {
                'n' -> content.append('\n'); 't' -> content.append('\t'); 'r' -> content.append('\r')
                '\\' -> content.append('\\'); '\'' -> content.append('\''); else -> content.append(n)
            }
            pos += 2
        } else { content.append(input[pos]); pos++ }
    }
    if (pos < input.length && input[pos] == '\'') pos++
    val nv = content.toString().toLongOrNull(10) ?: 0L
    return ArithNumberNode(nv) to pos
}

fun parseLocalizationQuoting(input: String, currentPos: Int): Pair<ArithExpr, Int>? {
    if (!input.startsWith("\$\"", currentPos)) return null
    var pos = currentPos + 2
    val content = StringBuilder()
    while (pos < input.length && input[pos] != '"') {
        if (input[pos] == '\\' && pos + 1 < input.length) { content.append(input[pos + 1]); pos += 2 }
        else { content.append(input[pos]); pos++ }
    }
    if (pos < input.length && input[pos] == '"') pos++
    val nv = content.toString().toLongOrNull(10) ?: 0L
    return ArithNumberNode(nv) to pos
}