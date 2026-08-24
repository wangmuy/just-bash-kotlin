package com.justbash.parser

import com.justbash.ast.*

object ExpansionParser {
    private fun normRegexEscapes(pattern: String): String {
        val n = StringBuilder(); var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]; val e = if (i + 1 < pattern.length) pattern[i + 1] else '\u0000'
            if (ch != '\\' || e == '\u0000') { n.append(ch); i++; continue }
            if ("\\[]^-".contains(e)) n.append('\\')
            n.append(e); i += 2
        }
        return n.toString()
    }

    private fun ensureNonEmpty(parts: List<WordPart>): List<WordPart> =
        if (parts.isNotEmpty()) parts else listOf(LiteralPart(""))

    private fun findExtglobClose(value: String, openIdx: Int): Int {
        var depth = 1; var i = openIdx + 1
        while (i < value.length && depth > 0) {
            val c = value[i]
            if (c == '\\') { i += 2; continue }
            if ("@*+?!".contains(c) && i + 1 < value.length && value[i + 1] == '(') { i++; depth++; i++; continue }
            if (c == '(') depth++; else if (c == ')') { depth--; if (depth == 0) return i }
            i++
        }
        return -1
    }

    private fun parseSimpleParameter(value: String, start: Int): Pair<ParameterExpansionPart, Int> {
        var i = start + 1; val ch = value[i]
        if ("@*#?\$!-0123456789".contains(ch)) return ParameterExpansionPart(ch.toString()) to (i + 1)
        val name = StringBuilder()
        while (i < value.length && (value[i] in 'a'..'z' || value[i] in 'A'..'Z' || value[i] in '0'..'9' || value[i] == '_')) { name.append(value[i]); i++ }
        return ParameterExpansionPart(name.toString()) to i
    }

    private fun parseParameterExpansion(p: Parser, value: String, start: Int, quoted: Boolean = false): Pair<ParameterExpansionPart, Int> {
        var i = start + 2
        var indirection = false
        if (i < value.length && value[i] == '!') { indirection = true; i++ }
        var lengthOp = false
        if (i < value.length && value[i] == '#' && !(i + 1 < value.length && "}:#%/^,".contains(value[i + 1]))) { lengthOp = true; i++ }
        var name = ""
        if (i < value.length) {
            val fc = value[i]
            if ("@*#?\$!-".contains(fc) && !(i + 1 < value.length && (value[i + 1] in 'a'..'z' || value[i + 1] in 'A'..'Z' || value[i + 1] in '0'..'9' || value[i + 1] == '_'))) { name = fc.toString(); i++ }
            else {
                val sb = StringBuilder()
                while (i < value.length && (value[i] in 'a'..'z' || value[i] in 'A'..'Z' || value[i] in '0'..'9' || value[i] == '_')) { sb.append(value[i]); i++ }
                name = sb.toString()
            }
        }
        if (i < value.length && value[i] == '[') {
            val ci = findMatchingBracket(value, i, '[', ']')
            name += "[${quoteRemoveEscapes(value.substring(i + 1, ci))}]"; i = ci + 1
            if (i < value.length && value[i] == '[') {
                var d = 1; var j = i
                while (j < value.length && d > 0) { if (value[j] == '{') d++; else if (value[j] == '}') d--; if (d > 0) j++ }
                return ParameterExpansionPart("", BadSubstitutionOp(value.substring(start + 2, j))) to (j + 1)
            }
        }
        if (name.isEmpty() && !indirection && !lengthOp && i < value.length && value[i] != '}') {
            var d = 1; var j = i
            while (j < value.length && d > 0) { if (value[j] == '{') d++; else if (value[j] == '}') d--; if (d > 0) j++ }
            if (d > 0) throw ParseException("unexpected EOF while looking for matching '}'", 0, 0)
            return ParameterExpansionPart("", BadSubstitutionOp(value.substring(start + 2, j))) to (j + 1)
        }

        var operation: ParameterOperation? = null
        if (indirection) {
            val akm = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\[([@*])\\]$").find(name)
            if (akm != null) {
                if (i < value.length && value[i] != '}' && ":=\\-+?#%/^,@".contains(value[i])) {
                    val (op, opEnd) = parseParameterOperation(p, value, i, name, quoted)
                    if (op != null) { operation = IndirectionOp(op); i = opEnd }
                    else { operation = ArrayKeysOp(akm.groupValues[1], akm.groupValues[2] == "*"); name = "" }
                } else { operation = ArrayKeysOp(akm.groupValues[1], akm.groupValues[2] == "*"); name = "" }
            } else if (i < value.length && (value[i] == '*' || (value[i] == '@' && !(i + 1 < value.length && "QPaAEKkuUL".contains(value[i + 1]))))) {
                val suffix = value[i]; i++
                operation = VarNamePrefixOp(name, suffix == '*'); name = ""
            } else {
                if (i < value.length && value[i] != '}' && ":=\\-+?#%/^,@".contains(value[i])) {
                    val (op, opEnd) = parseParameterOperation(p, value, i, name, quoted)
                    if (op != null) { operation = IndirectionOp(op); i = opEnd } else operation = IndirectionOp()
                } else operation = IndirectionOp()
            }
        } else if (lengthOp) {
            if (i < value.length && value[i] == ':') { operation = LengthSliceErrorOp(); while (i < value.length && value[i] != '}') i++ }
            else if (i < value.length && value[i] != '}' && "-+=?".contains(value[i])) p.error("\${#$name${value.substring(i, value.indexOf('}', i))}}: bad substitution")
            else if (i < value.length && value[i] == '/') p.error("\${#$name${value.substring(i, value.indexOf('}', i))}}: bad substitution")
            else operation = LengthOp()
        }
        if (operation == null && i < value.length && value[i] != '}') {
            val (op, opEnd) = parseParameterOperation(p, value, i, name, quoted); operation = op; i = opEnd
        }
        if (i < value.length && value[i] != '}') {
            val c = value[i]
            if (!":\\-+=?#%/^,@[]".contains(c)) {
                var ei = i
                while (ei < value.length && value[ei] != '}') ei++
                p.error("\${${value.substring(start + 2, ei)}}: bad substitution")
            }
        }
        while (i < value.length && value[i] != '}') i++
        if (i >= value.length) throw ParseException("unexpected EOF while looking for matching '}'", 0, 0)
        return ParameterExpansionPart(name, operation) to (i + 1)
    }

    private fun parseParameterOperation(p: Parser, value: String, start: Int, paramName: String, quoted: Boolean = false): Pair<ParameterOperation?, Int> {
        var i = start
        val char = value[i]; val nc = if (i + 1 < value.length) value[i + 1] else '\u0000'
        if (char == ':') {
            val op = nc
            if ("-=?+".contains(op)) {
                i += 2
                val we = findParameterOperationEnd(value, i)
                val ws = value.substring(i, we)
                val wp = p.withDepth { parseWordParts(p, ws, false, false, true, false, quoted, false, false, true) }
                val word = WordNode(ensureNonEmpty(wp).toMutableList())
                return when (op) {
                    '-' -> DefaultValueOp(word, true) to we; '=' -> AssignDefaultOp(word, true) to we
                    '?' -> ErrorIfUnsetOp(word, true) to we; '+' -> UseAlternativeOp(word, true) to we
                    else -> null to i
                }
            }
            i++
            val we = findParameterOperationEnd(value, i); val ws = value.substring(i, we)
            var ci = -1; var d = 0; var td = 0
            for (j in ws.indices) {
                val c = ws[j]
                if (c == '(' || c == '[') d++; else if (c == ')' || c == ']') d--
                else if (c == '?' && d == 0) td++; else if (c == ':' && d == 0) { if (td > 0) td-- else { ci = j; break } }
            }
            val os = if (ci >= 0) ws.substring(0, ci) else ws
            val ls = if (ci >= 0) ws.substring(ci + 1) else null
            return SubstringOp(parseArithExprFromString(p, os), ls?.let { parseArithExprFromString(p, it) }) to we
        }
        if ("-=?+".contains(char)) {
            i++
            val we = findParameterOperationEnd(value, i); val ws = value.substring(i, we)
            val wp = p.withDepth { parseWordParts(p, ws, false, false, true, false, quoted, false, false, true) }
            val word = WordNode(ensureNonEmpty(wp).toMutableList())
            return when (char) {
                '-' -> DefaultValueOp(word, false) to we; '=' -> AssignDefaultOp(word, false) to we
                '?' -> ErrorIfUnsetOp(if (ws.isNotEmpty()) word else null, false) to we
                '+' -> UseAlternativeOp(word, false) to we; else -> null to i
            }
        }
        if (char == '#' || char == '%') {
            val greedy = nc == char; val side = if (char == '#') "prefix" else "suffix"; i += if (greedy) 2 else 1
            val pe = findParameterOperationEnd(value, i); val ps = value.substring(i, pe)
            val pp = p.withDepth { parseWordParts(p, ps, false, false, false) }
            return PatternRemovalOp(WordNode(ensureNonEmpty(pp).toMutableList()), side, greedy) to pe
        }
        if (char == '/') {
            val all = nc == '/'; i += if (all) 2 else 1
            var anchor: String? = null
            if (i < value.length && value[i] == '#') { anchor = "start"; i++ }
            else if (i < value.length && value[i] == '%') { anchor = "end"; i++ }
            val pe = if (anchor != null && i < value.length && (value[i] == '/' || value[i] == '}')) i else findPatternEnd(value, i)
            val ps = value.substring(i, pe)
            val pp = p.withDepth { parseWordParts(p, ps, false, false, false) }
            val pattern = WordNode(ensureNonEmpty(pp).toMutableList())
            var replacement: WordNode? = null; var ei = pe
            if (pe < value.length && value[pe] == '/') {
                val rs = pe + 1; val re = findParameterOperationEnd(value, rs)
                val rp = p.withDepth { parseWordParts(p, value.substring(rs, re), false, false, false) }
                replacement = WordNode(ensureNonEmpty(rp).toMutableList()); ei = re
            }
            return PatternReplacementOp(pattern, replacement, all, anchor) to ei
        }
        if (char == '^' || char == ',') {
            val all = nc == char; val direction = if (char == '^') "upper" else "lower"; i += if (all) 2 else 1
            val pe = findParameterOperationEnd(value, i); val ps = value.substring(i, pe)
            val pattern = if (ps.isNotEmpty()) WordNode(mutableListOf(LiteralPart(ps))) else null
            return CaseModificationOp(direction, all, pattern) to pe
        }
        if (char == '@' && "QPaAEKkuUL".contains(nc)) return TransformOp(nc.toString()) to (i + 2)
        return null to i
    }

    private fun parseExpansion(p: Parser, value: String, start: Int, quoted: Boolean = false): Pair<WordPart?, Int> {
        val i = start + 1; if (i >= value.length) return LiteralPart("$") to i
        val char = value[i]
        if (char == '(' && i + 1 < value.length && value[i + 1] == '(') {
            return if (p.isDollarDparenSubshell(value, start)) p.parseCommandSubstitution(value, start) else p.parseArithmeticExpansion(value, start)
        }
        if (char == '[') {
            var d = 1; var j = i + 1
            while (j < value.length && d > 0) { if (value[j] == '[') d++; else if (value[j] == ']') d--; if (d > 0) j++ }
            if (d == 0) { val expr = value.substring(i + 1, j); return ArithmeticExpansionPart(ArithmeticParser.parseArithmeticExpression(p, expr)) to (j + 1) }
        }
        if (char == '(') return p.parseCommandSubstitution(value, start)
        if (char == '{') return parseParameterExpansion(p, value, start, quoted)
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' || "@*#?\$!-".contains(char)) return parseSimpleParameter(value, start)
        return LiteralPart("$") to i
    }

    private fun parseDoubleQuotedContent(p: Parser, value: String): List<WordPart> {
        val parts = mutableListOf<WordPart>(); var i = 0; var lit = StringBuilder()
        fun flush() { if (lit.isNotEmpty()) { parts.add(LiteralPart(lit.toString())); lit = StringBuilder() } }
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                if (next == '$' || next == '`' || next == '"' || next == '\\') { lit.append(next); i += 2; continue }
                lit.append(ch); i++; continue
            }
            if (ch == '$') { flush(); val (part, ei) = parseExpansion(p, value, i, true); if (part != null) parts.add(part); i = ei; continue }
            if (ch == '`') { flush(); val (part, ei) = p.parseBacktickSubstitution(value, i, true); parts.add(part); i = ei; continue }
            lit.append(ch); i++
        }
        flush(); return parts
    }

    private fun parseDoubleQuoted(p: Parser, value: String, start: Int): Pair<WordPart, Int> {
        val ip = mutableListOf<WordPart>(); var i = start; var lit = StringBuilder()
        fun flush() { if (lit.isNotEmpty()) { ip.add(LiteralPart(lit.toString())); lit = StringBuilder() } }
        while (i < value.length && value[i] != '"') {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) { val n = value[i + 1]; if ("\"\\\$\n`".contains(n)) { lit.append(n); i += 2; continue }; lit.append(ch); i++; continue }
            if (ch == '$') { flush(); val (part, ei) = parseExpansion(p, value, i, true); if (part != null) ip.add(part); i = ei; continue }
            if (ch == '`') { flush(); val (part, ei) = p.parseBacktickSubstitution(value, i, true); ip.add(part); i = ei; continue }
            lit.append(ch); i++
        }
        flush(); return DoubleQuotedPart(ip) to i
    }

    fun parseWordParts(
        p: Parser, value: String, quoted: Boolean = false, singleQuoted: Boolean = false,
        isAssignment: Boolean = false, hereDoc: Boolean = false,
        singleQuotesAreLiteral: Boolean = false, noBraceExpansion: Boolean = false,
        regexPattern: Boolean = false, inParameterExpansion: Boolean = false,
        atWordStart: Boolean = true,
    ): List<WordPart> {
        if (singleQuoted) return listOf(SingleQuotedPart(value))
        if (quoted) { val ip = parseDoubleQuotedContent(p, value); return listOf(DoubleQuotedPart(ip.toMutableList())) }
        if (value.length >= 2 && value[0] == '"' && value[value.lastIndex] == '"') {
            val inner = value.substring(1, value.lastIndex); var huq = false; var j = 0
            while (j < inner.length) { if (inner[j] == '"') { huq = true; break }; if (inner[j] == '\\' && j + 1 < inner.length) j++; j++ }
            if (!huq) { val ip = parseDoubleQuotedContent(p, inner); return listOf(DoubleQuotedPart(ip.toMutableList())) }
        }
        val parts = mutableListOf<WordPart>(); var i = 0; var lit = StringBuilder()
        fun flush() { if (lit.isNotEmpty()) { parts.add(LiteralPart(lit.toString())); lit = StringBuilder() } }
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                if (regexPattern) { flush(); parts.add(EscapedPart(next.toString())); i += 2; continue }
                if (!hereDoc && !singleQuotesAreLiteral) { if (next != '\n') { flush(); parts.add(EscapedPart(next.toString())) }; i += 2; continue }
                val isEsc = if (hereDoc) next == '$' || next == '`' || next == '\n'
                else next == '$' || next == '`' || next == '"' || next == '\'' || next == '\n' || (inParameterExpansion && next == '}')
                val isGlob = if (singleQuotesAreLiteral) "*?[]\\".contains(next) else "*?[]\\(){}.^+".contains(next)
                if (isEsc) { if (next != '\n') lit.append(next) }
                else if (isGlob) { flush(); parts.add(EscapedPart(next.toString())) }
                else lit.append('\\').append(next)
                i += 2; continue
            }
            if (ch == '\'' && !singleQuotesAreLiteral && !hereDoc) {
                flush(); val cq = value.indexOf('\'', i + 1)
                if (cq == -1) { lit.append(value.substring(i)); break }
                parts.add(SingleQuotedPart(value.substring(i + 1, cq))); i = cq + 1; continue
            }
            if (ch == '"' && !hereDoc) { flush(); val (part, ei) = parseDoubleQuoted(p, value, i + 1); parts.add(part); i = ei + 1; continue }
            if (ch == '$' && i + 1 < value.length && value[i + 1] == '\'') { flush(); val (part, ei) = parseAnsiCQuoted(value, i + 2); parts.add(part); i = ei; continue }
            if (ch == '$') { flush(); val (part, ei) = parseExpansion(p, value, i); if (part != null) parts.add(part); i = ei; continue }
            if (ch == '`') { flush(); val (part, ei) = p.parseBacktickSubstitution(value, i); parts.add(part); i = ei; continue }
            if ((ch == '<' || ch == '>') && i + 1 < value.length && value[i + 1] == '(' && !hereDoc && !singleQuotesAreLiteral) {
                flush(); val (part, ei) = p.parseProcessSubstitutionFromString(value, i); parts.add(part); i = ei; continue
            }
            if (ch == '~') {
                val pc = if (i > 0) value[i - 1] else ' '; val cec = isAssignment && pc == ':'
                if ((i == 0 && atWordStart) || pc == '=' || cec) {
                    val te = findTildeEnd(value, i); val at = if (te < value.length) value[te] else '\u0000'
                    if (at == '\u0000' || at == '/' || at == ':') { flush(); val user = value.substring(i + 1, te).ifEmpty { null }; parts.add(TildeExpansionPart(user)); i = te; continue }
                }
            }
            if ("@*+?!".contains(ch) && i + 1 < value.length && value[i + 1] == '(') {
                val ci = findExtglobClose(value, i + 1)
                if (ci != -1) { flush(); parts.add(GlobPart(value.substring(i, ci + 1))); i = ci + 1; continue }
            }
            if (ch == '*' || ch == '?' || ch == '[') {
                flush(); val (pat, ei) = parseGlobPattern(value, i)
                parts.add(GlobPart(if (regexPattern) normRegexEscapes(pat) else pat)); i = ei; continue
            }
            if (ch == '{' && !isAssignment && !noBraceExpansion && !hereDoc) {
                val br = tryParseBraceExpansion(p, value, i, ::parseWordParts)
                if (br != null) { flush(); parts.add(br.first); i = br.second; continue }
            }
            lit.append(ch); i++
        }
        flush(); return parts
    }
}