package com.justbash.parser

import com.justbash.ast.*

object CommandParser {
    fun isRedirection(p: Parser): Boolean {
        val ct = p.current(); val t = ct.type
        if (t == TokenType.NUMBER) { val nt = p.peek(1); if (ct.end != nt.start) return false; if (p.isProcessSubstitutionStart(1)) return false; return REDIRECTION_AFTER_NUMBER.contains(nt.type) }
        if (t == TokenType.FD_VARIABLE) { val nt = p.peek(1); if (ct.end != nt.start) return false; if (p.isProcessSubstitutionStart(1)) return false; return REDIRECTION_AFTER_FD_VARIABLE.contains(nt.type) }
        return !p.isProcessSubstitutionStart() && REDIRECTION_TOKENS.contains(t)
    }

    fun parseRedirection(p: Parser): RedirectionNode {
        var fd: Int? = null; var fdv: String? = null
        if (p.check(TokenType.NUMBER)) fd = p.advance().value.toIntOrNull(10)
        else if (p.check(TokenType.FD_VARIABLE)) fdv = p.advance().value
        val ot = p.advance(); val op = tokenToRedirectOp(ot.type)
        if (ot.type == TokenType.DLESS || ot.type == TokenType.DLESSDASH) return parseHeredocStart(p, fd, fdv, ot.type == TokenType.DLESSDASH)
        if (!p.isWord()) p.error("Expected redirection target")
        return RedirectionNode(fd, op, p.parseWord(), fdv)
    }

    private fun parseHeredocStart(p: Parser, fd: Int?, fdv: String?, stripTabs: Boolean): RedirectionNode {
        if (!p.isWord()) p.error("Expected here-document delimiter")
        val dt = p.advance(); val delim = dt.heredocDelimiter ?: dt.value; val quoted = dt.quoted
        val redirect = RedirectionNode(fd, if (stripTabs) "<<-" else "<<", HereDocNode(delim, WordNode(), stripTabs, quoted), fdv)
        p.addPendingHeredoc(redirect, delim, stripTabs, quoted); return redirect
    }

    fun parseSimpleCommand(p: Parser): SimpleCommandNode {
        val sl = p.getSourceLine()
        val assignments = mutableListOf<AssignmentNode>(); var name: WordNode? = null
        val args = mutableListOf<WordNode>(); val redirections = mutableListOf<RedirectionNode>()
        while (p.check(TokenType.ASSIGNMENT_WORD) || isRedirection(p)) {
            p.checkIterationLimit()
            if (p.check(TokenType.ASSIGNMENT_WORD)) assignments.add(parseAssignment(p)) else redirections.add(parseRedirection(p))
        }
        if (p.isWord()) name = p.parseWord()
        else if (assignments.isNotEmpty() && (p.check(TokenType.DBRACK_START) || p.check(TokenType.DPAREN_START))) {
            val t = p.advance(); name = WordNode(mutableListOf(LiteralPart(t.value)))
        }
        while ((!p.isStatementEnd() || p.check(TokenType.RBRACE)) && !p.check(TokenType.PIPE, TokenType.PIPE_AMP)) {
            p.checkIterationLimit()
            when {
                isRedirection(p) -> redirections.add(parseRedirection(p))
                p.check(TokenType.ASSIGNMENT_WORD) -> {
                    val t = p.advance(); val tv = t.value; val eeq = tv.endsWith("="); val eep = tv.endsWith("=(")
                    if ((eeq || eep) && (eep || p.check(TokenType.LPAREN))) {
                        val bn = if (eep) tv.dropLast(2) else tv.dropLast(1)
                        if (!eep) p.expect(TokenType.LPAREN)
                        val elements = parseArrayElements(p); p.expect(TokenType.RPAREN)
                        val es = elements.map { wordToString(it) }; val arrayStr = "$bn=(${es.joinToString(" ")})"
                        args.add(p.parseWordFromString(arrayStr, false, false, true))
                    } else {
                        val w = p.parseWordFromString(tv, t.quoted, t.singleQuoted, true)
                        args.add(p.parseAdjacentWordParts(w.parts, t.end, WordParseContext.Assignment))
                    }
                }
                p.isWord() -> args.add(p.parseWord())
                p.check(TokenType.RBRACE) -> { val t = p.advance(); args.add(p.parseWordFromString(t.value, false, false)) }
                p.check(TokenType.LBRACE) -> { val t = p.advance(); args.add(p.parseWordFromString(t.value, false, false)) }
                p.check(TokenType.DBRACK_END) -> { val t = p.advance(); args.add(p.parseWordFromString(t.value, false, false)) }
                p.check(TokenType.LPAREN) -> p.error("syntax error near unexpected token `('")
                else -> break
            }
        }
        val node = SimpleCommandNode(assignments, name, args, redirections); node.line = sl; return node
    }

    private fun parseAssignment(p: Parser): AssignmentNode {
        val t = p.expect(TokenType.ASSIGNMENT_WORD); val v = t.value
        val nm = Regex("^[a-zA-Z_][a-zA-Z0-9_]*").find(v) ?: p.error("Invalid assignment: $v") as Nothing
        val name = nm.value; var subscript: String? = null; var pos = name.length
        if (pos < v.length && v[pos] == '[') {
            var d = 0; val ss = pos + 1
            while (pos < v.length) { if (v[pos] == '[') d++; else if (v[pos] == ']') { d--; if (d == 0) break }; pos++ }
            if (d != 0) p.error("Invalid assignment: $v")
            subscript = quoteRemoveEscapes(v.substring(ss, pos)); pos++
        }
        val append = pos < v.length && v[pos] == '+'; if (append) pos++
        if (pos >= v.length || v[pos] != '=') p.error("Invalid assignment: $v")
        pos++
        val vs = if (pos < v.length) v.substring(pos) else ""
        val an = if (subscript != null) "$name[$subscript]" else name
        if (vs == "(") { val elements = parseArrayElements(p); p.expect(TokenType.RPAREN); return AssignmentNode(an, null, append, elements) }
        if (vs.isEmpty() && p.check(TokenType.LPAREN)) {
            val ct = p.current()
            if (t.end == ct.start) { p.advance(); val elements = parseArrayElements(p); p.expect(TokenType.RPAREN); return AssignmentNode(an, null, append, elements) }
        }
        var wv: WordNode? = if (vs.isNotEmpty()) p.parseWordFromString(vs, t.quoted, t.singleQuoted, true) else null
        if (t.end == p.current().start && p.isProcessSubstitutionStart()) wv = p.parseAdjacentWordParts(wv?.parts?.toMutableList() ?: mutableListOf(), t.end, WordParseContext.Assignment)
        return AssignmentNode(an, wv, append, null)
    }

    private val INVALID_ARRAY_TOKENS = setOf(TokenType.AMP, TokenType.PIPE, TokenType.PIPE_AMP, TokenType.SEMICOLON, TokenType.AND_AND, TokenType.OR_OR, TokenType.DSEMI, TokenType.SEMI_AND, TokenType.SEMI_SEMI_AND)

    private fun parseArrayElements(p: Parser): MutableList<WordNode> {
        val elements = mutableListOf<WordNode>(); p.skipNewlines()
        while (!p.check(TokenType.RPAREN, TokenType.EOF)) {
            p.checkIterationLimit()
            when { p.isWord() -> elements.add(p.parseWord()); INVALID_ARRAY_TOKENS.contains(p.current().type) -> p.error("syntax error near unexpected token `${p.current().value}'"); else -> p.advance() }
            p.skipNewlines()
        }
        return elements
    }
}