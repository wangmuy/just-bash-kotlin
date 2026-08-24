package com.justbash.parser

import com.justbash.ast.*

fun quoteRemoveEscapes(value: String): String {
    var quote: Char? = null
    val r = StringBuilder(); var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch == '\\' && quote == '"' && i + 1 < value.length) { r.append(ch).append(value[i + 1]); i++; i++; continue }
        if (ch == '\'' || ch == '"') { if (quote == ch) quote = null else if (quote == null) quote = ch; r.append(ch); i++; continue }
        if (ch == '\\' && quote == null && i + 1 < value.length) {
            val e = value[i + 1]
            r.append(if (e == '$' || e == '`') "\\$e" else e.toString())
            i++; i++; continue
        }
        r.append(ch); i++
    }
    return r.toString()
}

private fun decodeUtf8(bytes: IntArray): String {
    val r = StringBuilder(); var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i]
        if (b0 < 0x80) { r.append(b0.toChar()); i++; continue }
        if ((b0 and 0xE0) == 0xC0) {
            if (i + 1 < bytes.size && (bytes[i + 1] and 0xC0) == 0x80 && b0 >= 0xC2) {
                val cp = ((b0 and 0x1F) shl 6) or (bytes[i + 1] and 0x3F); r.append(cp.toChar()); i += 2; continue
            }
            r.append(b0.toChar()); i++; continue
        }
        if ((b0 and 0xF0) == 0xE0) {
            if (i + 2 < bytes.size && (bytes[i + 1] and 0xC0) == 0x80 && (bytes[i + 2] and 0xC0) == 0x80) {
                if (b0 == 0xE0 && bytes[i + 1] < 0xA0) { r.append(b0.toChar()); i++; continue }
                val cp = ((b0 and 0x0F) shl 12) or ((bytes[i + 1] and 0x3F) shl 6) or (bytes[i + 2] and 0x3F)
                if (cp in 0xD800..0xDFFF) { r.append(b0.toChar()); i++; continue }
                r.append(cp.toChar()); i += 3; continue
            }
            r.append(b0.toChar()); i++; continue
        }
        if ((b0 and 0xF8) == 0xF0 && b0 <= 0xF4) {
            if (i + 3 < bytes.size && (bytes[i + 1] and 0xC0) == 0x80 && (bytes[i + 2] and 0xC0) == 0x80 && (bytes[i + 3] and 0xC0) == 0x80) {
                if (b0 == 0xF0 && bytes[i + 1] < 0x90) { r.append(b0.toChar()); i++; continue }
                val cp = ((b0 and 0x07) shl 18) or ((bytes[i + 1] and 0x3F) shl 12) or ((bytes[i + 2] and 0x3F) shl 6) or (bytes[i + 3] and 0x3F)
                if (cp > 0x10FFFF) { r.append(b0.toChar()); i++; continue }
                r.append(String(Character.toChars(cp))); i += 4; continue
            }
            r.append(b0.toChar()); i++; continue
        }
        r.append(b0.toChar()); i++
    }
    return r.toString()
}

fun findTildeEnd(value: String, start: Int): Int {
    var i = start + 1
    while (i < value.length && (value[i] in 'a'..'z' || value[i] in 'A'..'Z' || value[i] in '0'..'9' || value[i] == '_' || value[i] == '-')) i++
    return i
}

fun findMatchingBracket(value: String, start: Int, open: Char, close: Char): Int {
    var depth = 1; var i = start + 1
    while (i < value.length && depth > 0) {
        if (value[i] == open) depth++; else if (value[i] == close) depth--
        if (depth > 0) i++
    }
    return if (depth == 0) i else -1
}

fun findParameterOperationEnd(value: String, start: Int): Int {
    var i = start; var depth = 1
    while (i < value.length && depth > 0) {
        val ch = value[i]
        if (ch == '\\' && i + 1 < value.length) { i += 2; continue }
        if (ch == '\'') { val ci = value.indexOf('\'', i + 1); if (ci != -1) { i = ci + 1; continue } }
        if (ch == '"') {
            i++
            while (i < value.length && value[i] != '"') { if (value[i] == '\\' && i + 1 < value.length) i += 2 else i++ }
            if (i < value.length) i++; continue
        }
        if (ch == '{') depth++; else if (ch == '}') depth--
        if (depth > 0) i++
    }
    return i
}

fun findPatternEnd(value: String, start: Int): Int {
    var i = start; var consumed = false
    while (i < value.length) {
        val ch = value[i]
        if ((ch == '/' && consumed) || ch == '}') break
        if (ch == '\'') { val ci = value.indexOf('\'', i + 1); if (ci != -1) { i = ci + 1; consumed = true; continue } }
        if (ch == '"') {
            i++
            while (i < value.length && value[i] != '"') { if (value[i] == '\\' && i + 1 < value.length) i += 2 else i++ }
            if (i < value.length) i++; consumed = true; continue
        }
        if (ch == '\\') { i += 2; consumed = true } else { i++; consumed = true }
    }
    return i
}

fun parseGlobPattern(value: String, start: Int): Pair<String, Int> {
    var i = start; val pattern = StringBuilder()
    while (i < value.length) {
        val ch = value[i]
        when (ch) {
            '*', '?' -> { pattern.append(ch); i++ }
            '[' -> {
                val ci = findCharacterClassEnd(value, i)
                if (ci == -1) { pattern.append(ch); i++ } else { pattern.append(value, i, ci + 1); i = ci + 1 }
            }
            else -> break
        }
    }
    return pattern.toString() to i
}

private fun findCharacterClassEnd(value: String, start: Int): Int {
    var i = start + 1
    if (i < value.length && value[i] == '^') i++
    if (i < value.length && value[i] == ']') i++
    while (i < value.length) {
        val ch = value[i]
        if (ch == '\\' && i + 1 < value.length) {
            if (value[i + 1] == '"' || value[i + 1] == '\'') return -1
            i += 2; continue
        }
        if (ch == ']') return i
        if (ch == '"' || ch == '$' || ch == '`') return -1
        if (ch == '\'') { val cq = value.indexOf('\'', i + 1); if (cq != -1) { i = cq + 1; continue } }
        if (ch == '[' && i + 1 < value.length && value[i + 1] == ':') {
            val cp = value.indexOf(":]", i + 2); if (cp != -1) { i = cp + 2; continue }
        }
        if (ch == '[' && i + 1 < value.length && (value[i + 1] == '.' || value[i + 1] == '=')) {
            val cc = value[i + 1]; val csq = "$cc]"; val cp = value.indexOf(csq, i + 2); if (cp != -1) { i = cp + 2; continue }
        }
        i++
    }
    return -1
}

fun parseAnsiCQuoted(value: String, start: Int): Pair<WordPart, Int> {
    val r = StringBuilder(); var i = start
    while (i < value.length && value[i] != '\'') {
        val ch = value[i]
        if (ch == '\\' && i + 1 < value.length) {
            when (val n = value[i + 1]) {
                'n' -> { r.append('\n'); i += 2 }; 't' -> { r.append('\t'); i += 2 }; 'r' -> { r.append('\r'); i += 2 }
                '\\' -> { r.append('\\'); i += 2 }; '\'' -> { r.append('\''); i += 2 }; '"' -> { r.append('"'); i += 2 }
                'a' -> { r.append('\u0007'); i += 2 }; 'b' -> { r.append('\b'); i += 2 }
                'e', 'E' -> { r.append('\u001B'); i += 2 }; 'f' -> { r.append('\u000C'); i += 2 }; 'v' -> { r.append('\u000B'); i += 2 }
                'x' -> {
                    val bytes = mutableListOf<Int>(); var j = i
                    while (j + 1 < value.length && value[j] == '\\' && value[j + 1] == 'x') {
                        val hex = if (j + 3 < value.length) value.substring(j + 2, j + 4) else ""
                        val code = hex.toIntOrNull(16)
                        if (code != null && hex.isNotEmpty()) { bytes.add(code); j += 2 + hex.length } else break
                    }
                    if (bytes.isNotEmpty()) { r.append(decodeUtf8(bytes.toIntArray())); i = j } else { r.append("\\x"); i += 2 }
                }
                'u' -> {
                    val hex = if (i + 5 < value.length) value.substring(i + 2, i + 6) else ""
                    val code = hex.toIntOrNull(16)
                    if (code != null) { r.append(code.toChar()); i += 6 } else { r.append("\\u"); i += 2 }
                }
                'c' -> {
                    if (i + 2 < value.length) { r.append((value[i + 2].code and 0x1F).toChar()); i += 3 } else { r.append("\\c"); i += 2 }
                }
                in '0'..'7' -> {
                    var oct = ""; var j = i + 1
                    while (j < value.length && j < i + 4 && value[j] in '0'..'7') { oct += value[j]; j++ }
                    r.append((oct.toIntOrNull(8) ?: 0).toChar()); i = j
                }
                else -> { r.append(ch); i++ }
            }
        } else { r.append(ch); i++ }
    }
    if (i < value.length && value[i] == '\'') i++
    return LiteralPart(r.toString()) to i
}

fun parseArithExprFromString(p: Parser, str: String): ArithmeticExpressionNode {
    val trimmed = str.trim()
    if (trimmed.isEmpty()) return ArithmeticExpressionNode(ArithNumberNode(0L))
    return ArithmeticParser.parseArithmeticExpression(p, trimmed)
}

private fun splitBraceItems(inner: String): List<String> {
    val items = mutableListOf<String>(); var cur = StringBuilder(); var depth = 0
    for (c in inner) {
        when {
            c == '{' -> { depth++; cur.append(c) }
            c == '}' -> { depth--; cur.append(c) }
            c == ',' && depth == 0 -> { items.add(cur.toString()); cur = StringBuilder() }
            else -> cur.append(c)
        }
    }
    items.add(cur.toString()); return items
}

fun tryParseBraceExpansion(
    p: Parser, value: String, start: Int,
    parseWordPartsFn: ((Parser, String, Boolean, Boolean, Boolean) -> List<WordPart>)? = null,
): Pair<WordPart, Int>? {
    val ci = findMatchingBracket(value, start, '{', '}'); if (ci == -1) return null
    val inner = value.substring(start + 1, ci)
    val rm = Regex("^(-?\\d+)\\.\\.(-?\\d+)(?:\\.\\.(-?\\d+))?$").find(inner)
    if (rm != null) {
        val s1 = rm.groupValues[1]; val s2 = rm.groupValues[2]; val s3 = rm.groupValues[3]
        return BraceExpansionPart(mutableListOf(BraceRangeItem(s1.toIntOrNull() ?: s1, s2.toIntOrNull() ?: s2, s3.toIntOrNull().takeIf { s3.isNotEmpty() }, s1, s2))) to ci + 1
    }
    val cm = Regex("^([a-zA-Z])\\.\\.([a-zA-Z])(?:\\.\\.(-?\\d+))?$").find(inner)
    if (cm != null) {
        val s1 = cm.groupValues[1]; val s2 = cm.groupValues[2]; val s3 = cm.groupValues[3]
        return BraceExpansionPart(mutableListOf(BraceRangeItem(s1, s2, s3.toIntOrNull().takeIf { s3.isNotEmpty() }))) to ci + 1
    }
    if (inner.contains(',') && parseWordPartsFn != null) {
        val items = splitBraceItems(inner).map { s ->
            p.withDepth { BraceWordItem(WordNode(parseWordPartsFn(p, s, false, false, false).toMutableList())) }
        }
        return BraceExpansionPart(items.toMutableList()) to ci + 1
    }
    if (inner.contains(',')) {
        val items = splitBraceItems(inner).map { s -> BraceWordItem(WordNode(mutableListOf(LiteralPart(s)))) }
        return BraceExpansionPart(items.toMutableList()) to ci + 1
    }
    return null
}

fun wordToString(word: WordNode): String {
    val r = StringBuilder()
    for (part in word.parts) {
        when (part) {
            is LiteralPart -> r.append(part.value)
            is SingleQuotedPart -> r.append("'${part.value}'")
            is EscapedPart -> r.append(part.value)
            is DoubleQuotedPart -> {
                r.append('"')
                for (inner in part.parts) when (inner) {
                    is LiteralPart -> r.append(inner.value)
                    is EscapedPart -> r.append(inner.value)
                    is ParameterExpansionPart -> r.append("\${${inner.parameter}}")
                    else -> {}
                }
                r.append('"')
            }
            is ParameterExpansionPart -> r.append("\${${part.parameter}}")
            is GlobPart -> r.append(part.pattern)
            is TildeExpansionPart -> r.append("~${part.user ?: ""}")
            is BraceExpansionPart -> {
                r.append('{')
                val bi = mutableListOf<String>()
                for (item in part.items) when (item) {
                    is BraceRangeItem -> {
                        val s = item.startStr ?: item.start.toString(); val e = item.endStr ?: item.end.toString()
                        bi.add(if (item.step != null) "$s..$e..${item.step}" else "$s..$e")
                    }
                    is BraceWordItem -> bi.add(wordToString(item.word))
                }
                if (bi.size == 1 && part.items[0] is BraceRangeItem) r.append(bi[0]) else r.append(bi.joinToString(","))
                r.append('}')
            }
            else -> r.append(part.type)
        }
    }
    return r.toString()
}

fun tokenToRedirectOp(type: TokenType): String = when (type) {
    TokenType.LESS -> "<"; TokenType.GREAT -> ">"; TokenType.DGREAT -> ">>"; TokenType.LESSAND -> "<&"
    TokenType.GREATAND -> ">&"; TokenType.LESSGREAT -> "<>"; TokenType.CLOBBER -> ">|"; TokenType.TLESS -> "<<<"
    TokenType.AND_GREAT -> "&>"; TokenType.AND_DGREAT -> "&>>"; TokenType.DLESS -> "<"; TokenType.DLESSDASH -> "<"
    else -> ">"
}