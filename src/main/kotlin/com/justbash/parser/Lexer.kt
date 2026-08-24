package com.justbash.parser

class Lexer(
    private val input: String,
    private val maxHeredocSize: Long = 10_485_760,
) {
    private var pos = 0
    private var line = 1
    private var column = 1
    private val tokens = mutableListOf<Token>()
    private val pendingHeredocs = mutableListOf<PendingHeredoc>()
    private var pendingHeredocDelimiterMode: Boolean? = null
    private var dparenDepth = 0

    data class PendingHeredoc(val delimiter: String, val stripTabs: Boolean, val quoted: Boolean)

    companion object {
        private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'
        private fun isValidName(s: String) = s.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))
        private fun isWordBoundary(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == ';' || c == '&' || c == '|' || c == '(' || c == ')' || c == '<' || c == '>'

        private val THREE_CHAR_OPS = listOf(
            Triple(";", ";", "&") to TokenType.SEMI_SEMI_AND,
            Triple("<", "<", "<") to TokenType.TLESS,
            Triple("&", ">", ">") to TokenType.AND_DGREAT,
        )
        private val TWO_CHAR_OPS = listOf(
            Pair("[", "[") to TokenType.DBRACK_START,
            Pair("]", "]") to TokenType.DBRACK_END,
            Pair("(", "(") to TokenType.DPAREN_START,
            Pair(")", ")") to TokenType.DPAREN_END,
            Pair("&", "&") to TokenType.AND_AND,
            Pair("|", "|") to TokenType.OR_OR,
            Pair(";", ";") to TokenType.DSEMI,
            Pair(";", "&") to TokenType.SEMI_AND,
            Pair("|", "&") to TokenType.PIPE_AMP,
            Pair(">", ">") to TokenType.DGREAT,
            Pair("<", "&") to TokenType.LESSAND,
            Pair(">", "&") to TokenType.GREATAND,
            Pair("<", ">") to TokenType.LESSGREAT,
            Pair(">", "|") to TokenType.CLOBBER,
            Pair("&", ">") to TokenType.AND_GREAT,
        )
        private val SINGLE_CHAR_OPS = mapOf(
            '|' to TokenType.PIPE, '&' to TokenType.AMP, ';' to TokenType.SEMICOLON,
            '(' to TokenType.LPAREN, ')' to TokenType.RPAREN, '<' to TokenType.LESS, '>' to TokenType.GREAT,
        )

        private fun isValidAssignmentLHS(str: String): Boolean {
            val m = Regex("^[a-zA-Z_][a-zA-Z0-9_]*").find(str) ?: return false
            val after = str.substring(m.value.length)
            if (after.isEmpty() || after == "+") return true
            if (after[0] == '[') {
                var depth = 0; var i = 0
                while (i < after.length) {
                    if (after[i] == '[') depth++; else if (after[i] == ']') { depth--; if (depth == 0) break }
                    i++
                }
                if (depth != 0 || i >= after.length) return false
                val ab = after.substring(i + 1)
                return ab.isEmpty() || ab == "+"
            }
            return false
        }

        private fun findAssignmentEq(str: String): Int {
            var depth = 0
            for (i in str.indices) {
                val c = str[i]
                if (c == '[') depth++; else if (c == ']') depth--
                else if (depth == 0 && c == '=') return i
                else if (depth == 0 && c == '+' && i + 1 < str.length && str[i + 1] == '=') return i + 1
            }
            return -1
        }
    }

    fun tokenize(): List<Token> {
        val len = input.length
        while (pos < len) {
            if (pendingHeredocs.isNotEmpty() && tokens.isNotEmpty() && tokens.last().type == TokenType.NEWLINE) {
                readHeredocContent(); continue
            }
            skipWhitespace()
            if (pos >= len) break
            nextToken()?.let { tokens.add(it) }
        }
        if (pendingHeredocs.isNotEmpty()) readHeredocContent()
        tokens.add(Token(TokenType.EOF, "", pos, pos, line, column))
        return tokens
    }

    private fun skipWhitespace() {
        val len = input.length
        while (pos < len) {
            val c = input[pos]
            if (c == ' ' || c == '\t') { pos++; column++ }
            else if (c == '\\' && pos + 1 < len && input[pos + 1] == '\n') { pos += 2; line++; column = 1 }
            else break
        }
    }

    private fun nextToken(): Token? {
        val sp = pos; val sl = line; val sc = column
        val c0 = input[pos]
        val c1 = if (pos + 1 < input.length) input[pos + 1] else '\u0000'
        val c2 = if (pos + 2 < input.length) input[pos + 2] else '\u0000'

        val sht = pendingHeredocDelimiterMode
        pendingHeredocDelimiterMode = null
        if (sht != null && c0 != '\n' && !(c0 == '#' && dparenDepth == 0)) return readHeredocDelimiterToken(sp, sl, sc, sht)
        if (c0 == '#' && dparenDepth == 0) return readComment(sp, sl, sc)
        if (c0 == '\n') { pos = sp + 1; line++; column = 1; return makeToken(TokenType.NEWLINE, "\n", sp, sl, sc) }
        if (c0 == '<' && c1 == '<' && c2 == '-') {
            pos = sp + 3; column = sc + 3; pendingHeredocDelimiterMode = true
            return makeToken(TokenType.DLESSDASH, "<<-", sp, sl, sc)
        }
        for ((t, ty) in THREE_CHAR_OPS) {
            if (c0 == t.first[0] && c1 == t.second[0] && c2 == t.third[0]) {
                pos = sp + 3; column = sc + 3
                return makeToken(ty, "${t.first}${t.second}${t.third}", sp, sl, sc)
            }
        }
        if (c0 == '<' && c1 == '<') {
            pos = sp + 2; column = sc + 2; pendingHeredocDelimiterMode = false
            return makeToken(TokenType.DLESS, "<<", sp, sl, sc)
        }
        if (c0 == '(' && c1 == '(') {
            val pt = tokens.lastOrNull()
            if (dparenDepth == 0 && pt != null && pt.end == sp && (pt.type == TokenType.LESS || pt.type == TokenType.GREAT)) {
                pos = sp + 1; column = sc + 1; return makeToken(TokenType.LPAREN, "(", sp, sl, sc)
            }
            if (dparenDepth > 0) { pos = sp + 1; column = sc + 1; dparenDepth++; return makeToken(TokenType.LPAREN, "(", sp, sl, sc) }
            if (looksLikeNestedSubshells(sp + 2) || dparenClosesWithSpacedParens(sp + 2)) {
                pos = sp + 1; column = sc + 1; return makeToken(TokenType.LPAREN, "(", sp, sl, sc)
            }
            pos = sp + 2; column = sc + 2; dparenDepth = 1
            return makeToken(TokenType.DPAREN_START, "((", sp, sl, sc)
        }
        if (c0 == ')' && c1 == ')') {
            if (dparenDepth == 1) { pos = sp + 2; column = sc + 2; dparenDepth = 0; return makeToken(TokenType.DPAREN_END, "))", sp, sl, sc) }
            if (dparenDepth > 1) { pos = sp + 1; column = sc + 1; dparenDepth--; return makeToken(TokenType.RPAREN, ")", sp, sl, sc) }
            pos = sp + 1; column = sc + 1; return makeToken(TokenType.RPAREN, ")", sp, sl, sc)
        }
        for ((p, ty) in TWO_CHAR_OPS) {
            if ((p.first == "(" && p.second == "(") || (p.first == ")" && p.second == ")")) continue
            if (dparenDepth > 0 && p.first == ";" && (ty == TokenType.DSEMI || ty == TokenType.SEMI_AND || ty == TokenType.SEMI_SEMI_AND)) continue
            if (c0 == p.first[0] && c1 == p.second[0]) {
                if (ty == TokenType.DBRACK_START || ty == TokenType.DBRACK_END) {
                    val after = if (sp + 2 < input.length) input[sp + 2] else '\u0000'
                    if (after != '\u0000' && !isWordBoundary(after)) break
                }
                pos = sp + 2; column = sc + 2
                return makeToken(ty, "${p.first}${p.second}", sp, sl, sc)
            }
        }
        if (c0 == '(' && dparenDepth > 0) { pos = sp + 1; column = sc + 1; dparenDepth++; return makeToken(TokenType.LPAREN, "(", sp, sl, sc) }
        if (c0 == ')' && dparenDepth > 1) { pos = sp + 1; column = sc + 1; dparenDepth--; return makeToken(TokenType.RPAREN, ")", sp, sl, sc) }
        val st = SINGLE_CHAR_OPS[c0]
        if (st != null) { pos = sp + 1; column = sc + 1; return makeToken(st, c0.toString(), sp, sl, sc) }

        if (c0 == '{') {
            val fv = scanFdVariable(sp)
            if (fv != null) {
                pos = fv.end; column = sc + (fv.end - sp)
                return Token(TokenType.FD_VARIABLE, fv.varname, sp, fv.end, sl, sc)
            }
            if (c1 == '}') {
                pos = sp + 2; column = sc + 2
                return Token(TokenType.WORD, "{}", sp, pos, sl, sc, quoted = false)
            }
            if (scanBraceExpansion(sp) != null) return readWordWithBraceExpansion(sp, sl, sc)
            if (scanLiteralBraceWord(sp) != null) return readWordWithBraceExpansion(sp, sl, sc)
            if (c1 != '\u0000' && c1 != ' ' && c1 != '\t' && c1 != '\n') return readWord(sp, sl, sc)
            pos = sp + 1; column = sc + 1; return makeToken(TokenType.LBRACE, "{", sp, sl, sc)
        }
        if (c0 == '}') {
            if (isWordCharFollowing(pos + 1)) return readWord(sp, sl, sc)
            pos = sp + 1; column = sc + 1; return makeToken(TokenType.RBRACE, "}", sp, sl, sc)
        }
        if (c0 == '!') {
            if (c1 == '=') { pos = sp + 2; column = sc + 2; return makeToken(TokenType.WORD, "!=", sp, sl, sc) }
            pos = sp + 1; column = sc + 1; return makeToken(TokenType.BANG, "!", sp, sl, sc)
        }
        return readWord(sp, sl, sc)
    }

    private fun looksLikeNestedSubshells(sp: Int): Boolean {
        val len = input.length; var p = sp
        while (p < len && (input[p] == ' ' || input[p] == '\t')) p++
        if (p >= len) return false
        val c = input[p]
        if (c == '(') return looksLikeNestedSubshells(p + 1)
        val isL = isLetter(c); val isSp = c == '!' || c == '['
        if (!isL && !isSp) return false
        var we = p
        while (we < len && input[we] in "a-zA-Z0-9_\\-.") we++
        var aw = we
        while (aw < len && (input[aw] == ' ' || input[aw] == '\t')) aw++
        if (aw >= len) return false
        val nc = input[aw]
        if (nc == '=' && (aw + 1 >= len || input[aw + 1] != '=')) return false
        if (nc == '\n') return false
        if (we == aw && "+\\-*/%<>&|^!~?:".contains(nc) && nc != '-') return false
        if (nc == ')' && aw + 1 < len && input[aw + 1] == ')') return false
        if (aw > we && (nc == '-' || nc == '"' || nc == '\'' || nc == '$' || isLetter(nc) || nc == '/' || nc == '.')) {
            var s = aw
            while (s < len && input[s] != '\n') { if (input[s] == ')') return true; s++ }
            return false
        }
        if (nc == ')') {
            var ap = aw + 1
            while (ap < len && (input[ap] == ' ' || input[ap] == '\t')) ap++
            if (ap < len) {
                val apc = input[ap]; val ap2 = if (ap + 1 < len) input[ap + 1] else '\u0000'
                if ((apc == '|' && ap2 == '|') || (apc == '&' && ap2 == '&') || apc == ';' || (apc == '|' && ap2 != '|')) return true
            }
        }
        return false
    }

    private fun isWordCharFollowing(p: Int): Boolean =
        p < input.length && !isWordBoundary(input[p])

    private fun dollarDparenIsSubshell(sp: Int): Boolean {
        val len = input.length; var p = sp + 1; var depth = 2; var inSQ = false; var inDQ = false; var hasNL = false
        while (p < len && depth > 0) {
            val c = input[p]
            if (inSQ) { if (c == '\'') inSQ = false; if (c == '\n') hasNL = true; p++; continue }
            if (inDQ) { if (c == '\\') { p += 2; continue }; if (c == '"') inDQ = false; if (c == '\n') hasNL = true; p++; continue }
            when (c) {
                '\'' -> { inSQ = true; p++; continue }; '"' -> { inDQ = true; p++; continue }; '\\' -> { p += 2; continue }
            }
            if (c == '\n') hasNL = true
            if (c == '(') { depth++; p++; continue }
            if (c == ')') {
                depth--
                if (depth == 1) {
                    if (p + 1 < len && input[p + 1] == ')') return false
                    var s = p + 1; var hw = false
                    while (s < len && (input[s] == ' ' || input[s] == '\t' || input[s] == '\n')) { hw = true; s++ }
                    if (hw && s < len && input[s] == ')') return true
                    if (hasNL) return true
                }
                if (depth == 0) return false
                p++; continue
            }
            p++
        }
        return false
    }

    private fun dparenClosesWithSpacedParens(sp: Int): Boolean {
        val len = input.length; var p = sp; var depth = 2; var inSQ = false; var inDQ = false
        while (p < len && depth > 0) {
            val c = input[p]
            if (inSQ) { if (c == '\'') inSQ = false; p++; continue }
            if (inDQ) { if (c == '\\') { p += 2; continue }; if (c == '"') inDQ = false; p++; continue }
            when (c) {
                '\'' -> { inSQ = true; p++; continue }; '"' -> { inDQ = true; p++; continue }; '\\' -> { p += 2; continue }
            }
            if (c == '(') { depth++; p++; continue }
            if (c == ')') {
                depth--
                if (depth == 1) {
                    if (p + 1 < len && input[p + 1] == ')') return false
                    var s = p + 1; var hw = false
                    while (s < len && (input[s] == ' ' || input[s] == '\t' || input[s] == '\n')) { hw = true; s++ }
                    if (hw && s < len && input[s] == ')') return true
                }
                if (depth == 0) return false
                p++; continue
            }
            if (depth == 1) {
                if (c == '|' && p + 1 < len && input[p + 1] == '|') return true
                if (c == '&' && p + 1 < len && input[p + 1] == '&') return true
                if (c == '|' && (p + 1 >= len || input[p + 1] != '|')) return true
            }
            p++
        }
        return false
    }

    private fun makeToken(type: TokenType, value: String, start: Int, line: Int, column: Int): Token =
        Token(type, value, start, pos, line, column)

    private fun readComment(start: Int, line: Int, column: Int): Token {
        val len = input.length; var p = pos
        while (p < len && input[p] != '\n') p++
        val v = input.substring(start, p)
        pos = p; this.column = column + (p - start)
        return Token(TokenType.COMMENT, v, start, p, line, column)
    }

    private fun readWord(start: Int, line: Int, column: Int): Token {
        val len = input.length; var p = pos
        val fs = p
        while (p < len) {
            val c = input[p]
            if (c == ' ' || c == '\t' || c == '\n' || c == ';' || c == '&' || c == '|' || c == '(' || c == ')' || c == '<' || c == '>' ||
                c == '\'' || c == '"' || c == '\\' || c == '$' || c == '`' || c == '{' || c == '}' || c == '~' || c == '*' || c == '?' || c == '['
            ) break
            p++
        }
        if (p > fs) {
            val c = if (p < len) input[p] else '\u0000'
            if (c == '(' && p > fs && "@*+?!".contains(input[p - 1])) {
                // fall to slow path
            } else if (p >= len || c == ' ' || c == '\t' || c == '\n' || c == ';' || c == '&' || c == '|' || c == '(' || c == ')' || c == '<' || c == '>') {
                val v = input.substring(fs, p); pos = p; this.column = column + (p - fs)
                RESERVED_WORDS[v]?.let { return Token(it, v, start, p, line, column) }
                val eq = findAssignmentEq(v)
                if (eq > 0 && isValidAssignmentLHS(v.substring(0, eq))) return Token(TokenType.ASSIGNMENT_WORD, v, start, p, line, column)
                if (v.matches(Regex("^[0-9]+$"))) return Token(TokenType.NUMBER, v, start, p, line, column)
                if (isValidName(v)) return Token(TokenType.NAME, v, start, p, line, column, quoted = false)
                return Token(TokenType.WORD, v, start, p, line, column, quoted = false)
            }
        }
        p = pos; var col = column; var ln = line
        var value = StringBuilder(); var quoted = false; var singleQuoted = false
        var inSQ = false; var inDQ = false
        var startsWithQuote = input[p] == '"' || input[p] == '\''
        var hasContentAfterQuote = false; var bracketDepth = 0

        while (p < len) {
            val char = input[p]
            if (!inSQ && !inDQ) {
                if (char == '(' && value.isNotEmpty() && "@*+?!".contains(value[value.lastIndex])) {
                    val er = scanExtglobPattern(p)
                    if (er != null) { value.append(er.content); p = er.end; col += er.content.length; continue }
                }
                if (char == '[' && bracketDepth == 0) {
                    if (Regex("^[a-zA-Z_][a-zA-Z0-9_]*$").matches(value.toString())) {
                        val ab = if (p + 1 < len) input[p + 1] else '\u0000'
                        if (ab == '^' || ab == '!') { value.append(char); p++; col++; continue }
                        bracketDepth = 1; value.append(char); p++; col++; continue
                    }
                } else if (char == '[' && bracketDepth > 0) {
                    if (value.isNotEmpty() && value[value.lastIndex] != '\\') bracketDepth++
                    value.append(char); p++; col++; continue
                } else if (char == ']' && bracketDepth > 0) {
                    if (value.isNotEmpty() && value[value.lastIndex] != '\\') bracketDepth--
                    value.append(char); p++; col++; continue
                }
                if (bracketDepth > 0) {
                    if (char == '\n') break
                    value.append(char); p++; col++; continue
                }
                if (isWordBoundary(char)) break
            }

            if (char == '$' && p + 1 < len && input[p + 1] == '\'' && !inSQ && !inDQ) {
                value.append("\$'"); p += 2; col += 2
                while (p < len && input[p] != '\'') {
                    if (input[p] == '\\' && p + 1 < len) { value.append(input[p]).append(input[p + 1]); p += 2; col += 2 }
                    else { value.append(input[p]); p++; col++ }
                }
                if (p < len) { value.append('\''); p++; col++ }
                continue
            }
            if (char == '$' && p + 1 < len && input[p + 1] == '"' && !inSQ && !inDQ) {
                p++; col++; inDQ = true; quoted = true; if (value.isEmpty()) startsWithQuote = true; p++; col++; continue
            }
            if (char == '\'' && !inDQ) {
                if (inSQ) {
                    inSQ = false
                    if (!startsWithQuote || hasContentAfterQuote) value.append(char)
                    else {
                        val nc = if (p + 1 < len) input[p + 1] else '\u0000'
                        if (nc != '\u0000' && !isWordBoundary(nc) && nc != '\'') {
                            if (nc == '"') { hasContentAfterQuote = true; value.append(char); singleQuoted = false; quoted = false }
                            else { hasContentAfterQuote = true; value.append(char) }
                        }
                    }
                } else {
                    inSQ = true
                    if (startsWithQuote && !hasContentAfterQuote) { singleQuoted = true; quoted = true } else value.append(char)
                }
                p++; col++; continue
            }
            if (char == '"' && !inSQ) {
                if (inDQ) {
                    inDQ = false
                    if (!startsWithQuote || hasContentAfterQuote) value.append(char)
                    else {
                        val nc = if (p + 1 < len) input[p + 1] else '\u0000'
                        if (nc != '\u0000' && !isWordBoundary(nc) && nc != '"') {
                            if (nc == '\'') { hasContentAfterQuote = true; value.append(char); singleQuoted = false; quoted = false }
                            else { hasContentAfterQuote = true; value.append(char) }
                        }
                    }
                } else {
                    inDQ = true
                    if (startsWithQuote && !hasContentAfterQuote) quoted = true else value.append(char)
                }
                p++; col++; continue
            }
            if (char == '\\' && !inSQ && p + 1 < len) {
                val next = input[p + 1]
                if (next == '\n') { p += 2; ln++; col = 1; continue }
                if (inDQ) {
                    if (next == '"' || next == '\\' || next == '$' || next == '`' || next == '\n') {
                        if (next == '\n') { p += 2; col = 1; ln++; continue }
                        value.append(char).append(next); p += 2; col += 2; continue
                    }
                } else { value.append(char).append(next); p += 2; col += 2; continue }
            }
            if (char == '$' && p + 1 < len && input[p + 1] == '(' && !inSQ) {
                value.append(char); p++; col++; value.append(input[p]); p++; col++
                var depth = 1; var qs = false; var qd = false; var cd = 0; var icp = false
                var wb = StringBuilder(); val ph = mutableListOf<Pair<String, Boolean>>()
                val isArith = input[p] == '(' && !dollarDparenIsSubshell(p)
                var ad = if (isArith) 1 else 0
                while (depth > 0 && p < len) {
                    val c = input[p]; value.append(c)
                    if (qs) { if (c == '\'') qs = false }
                    else if (qd) { if (c == '\\' && p + 1 < len) { value.append(input[p + 1]); p++; col++ } else if (c == '"') qd = false }
                    else {
                        if (c == '(' && p + 1 < len && input[p + 1] == '(') ad++
                        else if (c == ')' && p + 1 < len && input[p + 1] == ')' && ad > 0) ad--
                        if (ad == 0 && c == '<' && p + 1 < len && input[p + 1] == '<' && (p + 2 >= len || input[p + 2] != '<')) {
                            var hp = p + 2; var ht = false
                            if (hp < len && input[hp] == '-') { ht = true; hp++ }
                            while (hp < len && (input[hp] == ' ' || input[hp] == '\t')) hp++
                            val r = readHeredocDelimiter(input, hp)
                            if (r.unclosedQuote != null) throw LexerError("unexpected EOF while looking for matching `'${r.unclosedQuote}'", ln, col)
                            if (r.unclosedSubstitution) throw LexerError("unexpected EOF while looking for matching `)'", ln, col)
                            if (r.delim.isNotEmpty()) { value.append(input, p + 1, r.endPos); col += r.endPos - p; ph.add(r.delim to ht); p = r.endPos; continue }
                        }
                        if (c == '\n' && ph.isNotEmpty()) {
                            ln++; col = 0; var bp = p + 1
                            for ((d, ht) in ph) {
                                while (true) {
                                    if (bp >= len) break
                                    var le = input.indexOf('\n', bp); if (le == -1) le = len
                                    val rl = input.substring(bp, le); val cmp = if (ht) rl.trimStart('\t') else rl
                                    value.append(input, bp, minOf(le + 1, len)); if (le < len) ln++
                                    val reached = le >= len; bp = le + 1
                                    if (cmp == d || reached) break
                                }
                            }
                            ph.clear(); col = 0; p = minOf(bp, len); continue
                        }
                        if (c == '\'') { qs = true; wb = StringBuilder() }
                        else if (c == '"') { qd = true; wb = StringBuilder() }
                        else if (c == '\\' && p + 1 < len) { value.append(input[p + 1]); p++; col++; wb = StringBuilder() }
                        else if (c == '$' && p + 1 < len && input[p + 1] == '{') {
                            p++; col++; value.append(input[p]); p++; col++
                            var bd = 1; var bsq = false; var bdq = false
                            while (bd > 0 && p < len) {
                                val bc = input[p]
                                if (bc == '\\' && p + 1 < len && !bsq) { value.append(bc); p++; col++; value.append(input[p]); p++; col++; continue }
                                value.append(bc)
                                if (bsq) { if (bc == '\'') bsq = false }
                                else if (bdq) { if (bc == '"') bdq = false }
                                else { if (bc == '\'') bsq = true; else if (bc == '"') bdq = true; else if (bc == '{') bd++; else if (bc == '}') bd-- }
                                if (bc == '\n') { ln++; col = 0 } else col++
                                p++
                            }
                            wb = StringBuilder(); continue
                        } else if (c == '#' && !isArith && (wb.isEmpty() || (p > 0 && input[p - 1].isWhitespace()))) {
                            while (p + 1 < len && input[p + 1] != '\n') { p++; col++; value.append(input[p]) }
                            wb = StringBuilder()
                        } else if (isLetter(c)) wb.append(c)
                        else {
                            val ws = wb.toString()
                            if (ws == "case") { cd++; icp = false }
                            else if (ws == "in" && cd > 0) icp = true
                            else if (ws == "esac" && cd > 0) { cd--; icp = false }
                            wb = StringBuilder()
                            if (c == '(') { if (p > 0 && input[p - 1] == '$') depth++; else if (!icp) depth++ }
                            else if (c == ')') { if (icp) icp = false else depth-- }
                            else if (c == ';') {
                                if (cd > 0 && p + 1 < len && input[p + 1] == ';') icp = true
                                else if (p + 1 < len && input[p + 1] == '&') icp = true
                            }
                        }
                    }
                    if (c == '\n') { ln++; col = 0; wb = StringBuilder() }
                    p++; col++
                }
                continue
            }
            if (char == '$' && p + 1 < len && input[p + 1] == '[' && !inSQ) {
                value.append(char); p++; col++; value.append(input[p]); p++; col++
                var depth = 1
                while (depth > 0 && p < len) {
                    val c = input[p]; value.append(c)
                    if (c == '[') depth++; else if (c == ']') depth--; else if (c == '\n') { ln++; col = 0 }
                    p++; col++
                }
                continue
            }
            if (char == '$' && p + 1 < len && input[p + 1] == '{' && !inSQ) {
                value.append(char); p++; col++; value.append(input[p]); p++; col++
                var depth = 1; var psq = false; var pdq = false
                var ssLine = ln; var ssCol = col; var dsLine = ln; var dsCol = col
                while (depth > 0 && p < len) {
                    val c = input[p]
                    if (c == '\\' && p + 1 < len && input[p + 1] == '\n') { p += 2; ln++; col = 1; continue }
                    if (c == '\\' && p + 1 < len && !psq) { value.append(c); p++; col++; value.append(input[p]); p++; col++; continue }
                    value.append(c)
                    if (psq) { if (c == '\'') psq = false }
                    else if (pdq) { if (c == '"') pdq = false }
                    else {
                        if (c == '\'') { psq = true; ssLine = ln; ssCol = col }
                        else if (c == '"') { pdq = true; dsLine = ln; dsCol = col }
                        else if (c == '{') depth++; else if (c == '}') depth--
                    }
                    if (c == '\n') { ln++; col = 0 }
                    p++; col++
                }
                if (psq) throw LexerError("unexpected EOF while looking for matching `''", ssLine, ssCol)
                if (pdq) throw LexerError("unexpected EOF while looking for matching `\"'", dsLine, dsCol)
                continue
            }
            if (char == '$' && p + 1 < len && !inSQ) {
                val next = input[p + 1]
                if (next == '#' || next == '?' || next == '$' || next == '!' || next == '@' || next == '*' || next == '-' || next in '0'..'9') {
                    value.append(char).append(next); p += 2; col += 2; continue
                }
            }
            if (char == '`' && !inSQ) {
                value.append(char); p++; col++
                while (p < len && input[p] != '`') {
                    val c = input[p]; value.append(c)
                    if (c == '\\' && p + 1 < len) { value.append(input[p + 1]); p++; col++ }
                    if (c == '\n') { ln++; col = 0 }
                    p++; col++
                }
                if (p < len) { value.append(input[p]); p++; col++ }
                continue
            }
            value.append(char); p++
            if (char == '\n') { ln++; col = 1 } else col++
        }
        pos = p; this.column = col; this.line = ln
        if (hasContentAfterQuote && startsWithQuote) {
            value.insert(0, input[start])
            quoted = false; singleQuoted = false
        }
        if (inSQ || inDQ) {
            val qt = if (inSQ) '\'' else '"'
            throw LexerError("unexpected EOF while looking for matching `$qt'", line, column)
        }
        if (!startsWithQuote && value.length >= 2) {
            if (value[0] == '\'' && value[value.lastIndex] == '\'') {
                val inner = value.substring(1, value.lastIndex)
                if (!inner.contains('\'') && !inner.contains('"')) {
                    value = StringBuilder(inner); quoted = true; singleQuoted = true
                }
            } else if (value[0] == '"' && value[value.lastIndex] == '"') {
                val inner = value.substring(1, value.lastIndex)
                var hasUq = false; var i = 0
                while (i < inner.length) { if (inner[i] == '"') { hasUq = true; break }; if (inner[i] == '\\' && i + 1 < inner.length) i++; i++ }
                if (!hasUq) { value = StringBuilder(inner); quoted = true; singleQuoted = false }
            }
        }
        val s = value.toString()
        if (s.isEmpty()) return Token(TokenType.WORD, "", start, pos, line, column, quoted, singleQuoted)
        val rt = RESERVED_WORDS[s]
        if (!quoted && rt != null) return Token(rt, s, start, pos, line, column)
        if (!startsWithQuote) {
            val eq = findAssignmentEq(s)
            if (eq > 0 && isValidAssignmentLHS(s.substring(0, eq))) return Token(TokenType.ASSIGNMENT_WORD, s, start, pos, line, column, quoted, singleQuoted)
        }
        if (s.matches(Regex("^[0-9]+$"))) return Token(TokenType.NUMBER, s, start, pos, line, column)
        if (isValidName(s)) return Token(TokenType.NAME, s, start, pos, line, column, quoted, singleQuoted)
        return Token(TokenType.WORD, s, start, pos, line, column, quoted, singleQuoted)
    }

    private fun readHeredocContent() {
        while (pendingHeredocs.isNotEmpty()) {
            val heredoc = pendingHeredocs.removeFirst()
            val start = pos; val sl = line; val sc = column
            var content = StringBuilder(); var terminated = false
            while (pos < input.length) {
                var lc = StringBuilder(); var cc = StringBuilder(); var ltc = StringBuilder()
                while (true) {
                    lc = StringBuilder()
                    while (pos < input.length && input[pos] != '\n') { lc.append(input[pos]); pos++; column++ }
                    var tbs = 0; var idx = lc.length - 1
                    while (idx >= 0 && lc[idx] == '\\') { tbs++; idx-- }
                    ltc.append(lc)
                    if (heredoc.quoted || tbs % 2 == 0 || pos + 1 >= input.length) break
                    ltc.deleteAt(ltc.length - 1); cc.append(lc).append('\n')
                    pos++; line++; column = 1
                }
                val dl = if (heredoc.stripTabs) ltc.toString().trimStart('\t') else ltc.toString()
                if (dl == heredoc.delimiter) {
                    terminated = true
                    if (pos < input.length && input[pos] == '\n') { pos++; line++; column = 1 }
                    break
                }
                content.append(cc).append(lc)
                if (pos < input.length && input[pos] == '\n') { content.append('\n'); pos++; line++; column = 1 }
                else if (lc.isNotEmpty()) content.append('\n')
                if (content.length.toLong() > maxHeredocSize) throw LexerError("Heredoc size limit exceeded ($maxHeredocSize bytes)", sl, sc)
            }
            tokens.add(Token(TokenType.HEREDOC_CONTENT, content.toString(), start, pos, sl, sc, heredocTerminated = terminated))
        }
    }

    private fun readHeredocDelimiterToken(start: Int, line: Int, column: Int, stripTabs: Boolean): Token {
        val r = readHeredocDelimiter(input, start)
        if (r.unclosedQuote != null) throw LexerError("unexpected EOF while looking for matching `'${r.unclosedQuote}'", line, column)
        if (r.unclosedSubstitution) throw LexerError("unexpected EOF while looking for matching `)'", line, column)
        if (r.endPos == start) throw LexerError("Expected here-document delimiter", line, column)
        var cl = line; var cc = column
        for (i in start until r.endPos) { if (input[i] == '\n') { cl++; cc = 1 } else cc++ }
        pos = r.endPos; this.line = cl; this.column = cc
        pendingHeredocs.add(PendingHeredoc(r.delim, stripTabs, r.quoted))
        return Token(TokenType.WORD, input.substring(start, r.endPos), start, r.endPos, line, column, quoted = r.quoted, heredocDelimiter = r.delim)
    }

    private fun readWordWithBraceExpansion(start: Int, line: Int, column: Int): Token {
        val len = input.length; var p = start; var col = column
        while (p < len) {
            val c = input[p]
            if (isWordBoundary(c)) break
            if (c == '{') {
                if (scanBraceExpansion(p) != null) { var d = 1; p++; col++; while (p < len && d > 0) { if (input[p] == '{') d++; else if (input[p] == '}') d--; p++; col++ }; continue }
                p++; col++; continue
            }
            if (c == '}') { p++; col++; continue }
            if (c == '$' && p + 1 < len && input[p + 1] == '(') { p++; col++; p++; col++; var d = 1; while (d > 0 && p < len) { if (input[p] == '(') d++; else if (input[p] == ')') d--; p++; col++ }; continue }
            if (c == '$' && p + 1 < len && input[p + 1] == '{') { p++; col++; p++; col++; var d = 1; while (d > 0 && p < len) { if (input[p] == '{') d++; else if (input[p] == '}') d--; p++; col++ }; continue }
            if (c == '`') { p++; col++; while (p < len && input[p] != '`') { if (input[p] == '\\' && p + 1 < len) { p += 2; col += 2 } else { p++; col++ } }; if (p < len) { p++; col++ }; continue }
            p++; col++
        }
        val v = input.substring(start, p); pos = p; this.column = col
        return Token(TokenType.WORD, v, start, p, line, column, quoted = false)
    }

    private fun scanBraceExpansion(sp: Int): String? {
        val len = input.length; var p = sp + 1; var depth = 1; var hasC = false; var hasR = false
        while (p < len && depth > 0) {
            when {
                input[p] == '{' -> { depth++; p++ }
                input[p] == '}' -> { depth--; p++ }
                input[p] == ',' && depth == 1 -> { hasC = true; p++ }
                input[p] == '.' && p + 1 < len && input[p + 1] == '.' -> { hasR = true; p += 2 }
                isWordBoundary(input[p]) -> return null
                else -> p++
            }
        }
        return if (depth == 0 && (hasC || hasR)) input.substring(sp, p) else null
    }

    private fun scanLiteralBraceWord(sp: Int): String? {
        val len = input.length; var p = sp + 1; var depth = 1
        while (p < len && depth > 0) {
            when {
                input[p] == '{' -> { depth++; p++ }
                input[p] == '}' -> { depth--; if (depth == 0) return input.substring(sp, p + 1); p++ }
                isWordBoundary(input[p]) -> return null
                else -> p++
            }
        }
        return null
    }

    private fun scanExtglobPattern(sp: Int): ExtglobResult? {
        val len = input.length; var p = sp + 1; var depth = 1
        while (p < len && depth > 0) {
            val c = input[p]
            if (c == '\\' && p + 1 < len) { p += 2; continue }
            if ("@*+?!".contains(c) && p + 1 < len && input[p + 1] == '(') { p++; depth++; p++; continue }
            if (c == '(') { depth++; p++ } else if (c == ')') { depth--; p++ } else if (c == '\n') return null else p++
        }
        return if (depth == 0) ExtglobResult(input.substring(sp, p), p) else null
    }

    data class ExtglobResult(val content: String, val end: Int)

    private fun scanFdVariable(sp: Int): FdVarResult? {
        val len = input.length; var p = sp + 1; val ns = p
        while (p < len) {
            val c = input[p]
            if (p == ns) { if (!(c in 'a'..'z' || c in 'A'..'Z' || c == '_')) return null }
            else { if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_')) break }
            p++
        }
        if (p == ns) return null
        val vn = input.substring(ns, p)
        if (p >= len || input[p] != '}') return null
        p++
        if (p >= len) return null
        val c = input[p]; val c2 = if (p + 1 < len) input[p + 1] else '\u0000'
        if (!(c == '>' || c == '<' || (c == '&' && (c2 == '>' || c2 == '<')))) return null
        return FdVarResult(vn, p)
    }

    data class FdVarResult(val varname: String, val end: Int)
}