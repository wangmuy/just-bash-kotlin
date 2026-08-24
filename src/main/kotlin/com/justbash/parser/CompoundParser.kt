package com.justbash.parser

import com.justbash.ast.*

object CompoundParser {
    class SkipRedirections(val skipRedirections: Boolean = false)

    fun parseIf(p: Parser, options: SkipRedirections? = null): IfNode {
        p.expect(TokenType.IF); val clauses = mutableListOf<IfClause>()
        val cond = p.parseCompoundList(); p.expect(TokenType.THEN); val body = p.parseCompoundList()
        if (body.isEmpty()) p.error("syntax error near unexpected token `${nextIfTok(p)}'")
        clauses.add(IfClause(cond, body))
        while (p.check(TokenType.ELIF)) {
            p.advance(); val ec = p.parseCompoundList(); p.expect(TokenType.THEN); val eb = p.parseCompoundList()
            if (eb.isEmpty()) p.error("syntax error near unexpected token `${nextIfTok(p)}'")
            clauses.add(IfClause(ec, eb))
        }
        var elseBody: MutableList<StatementNode>? = null
        if (p.check(TokenType.ELSE)) { p.advance(); elseBody = p.parseCompoundList(); if (elseBody.isEmpty()) p.error("syntax error near unexpected token `fi'") }
        p.expect(TokenType.FI)
        val rd = if (options?.skipRedirections == true) mutableListOf<RedirectionNode>() else p.parseOptionalRedirections()
        return IfNode(clauses, elseBody, rd)
    }

    private fun nextIfTok(p: Parser): String = when {
        p.check(TokenType.FI) -> "fi"; p.check(TokenType.ELSE) -> "else"; p.check(TokenType.ELIF) -> "elif"; else -> "fi"
    }

    fun parseFor(p: Parser, options: SkipRedirections? = null): CompoundCommandNode {
        val ft = p.expect(TokenType.FOR)
        if (p.check(TokenType.DPAREN_START)) return parseCStyleFor(p, options, p.getSourceLine(ft))
        if (!p.isWord()) p.error("Expected variable name in for loop")
        val variable = p.advance().value
        var words: MutableList<WordNode>? = null
        p.skipNewlines()
        if (p.check(TokenType.IN)) {
            p.advance(); words = mutableListOf()
            while (!p.check(TokenType.SEMICOLON, TokenType.NEWLINE, TokenType.DO, TokenType.EOF)) { if (p.isWord()) words.add(p.parseWord()) else break }
        }
        if (p.check(TokenType.SEMICOLON)) p.advance()
        p.skipNewlines(); p.expect(TokenType.DO); val body = p.parseCompoundList(); p.expect(TokenType.DONE)
        val rd = if (options?.skipRedirections == true) mutableListOf<RedirectionNode>() else p.parseOptionalRedirections()
        return ForNode(variable, words, body, rd)
    }

    private fun parseCStyleFor(p: Parser, options: SkipRedirections?, sl: Int?): CStyleForNode {
        p.expect(TokenType.DPAREN_START)
        var init: ArithmeticExpressionNode? = null; var cond: ArithmeticExpressionNode? = null; var upd: ArithmeticExpressionNode? = null
        val parts = arrayOf("", "", ""); var pi = 0; var depth = 0
        while (!p.check(TokenType.DPAREN_END, TokenType.EOF)) {
            val t = p.advance()
            if (t.type == TokenType.SEMICOLON && depth == 0) { pi++; if (pi > 2) break }
            else { if (t.value == "(") depth++; if (t.value == ")") depth--; parts[pi] += t.value }
        }
        p.expect(TokenType.DPAREN_END)
        if (parts[0].isNotBlank()) init = ArithmeticParser.parseArithmeticExpression(p, parts[0].trim())
        if (parts[1].isNotBlank()) cond = ArithmeticParser.parseArithmeticExpression(p, parts[1].trim())
        if (parts[2].isNotBlank()) upd = ArithmeticParser.parseArithmeticExpression(p, parts[2].trim())
        p.skipNewlines(); if (p.check(TokenType.SEMICOLON)) p.advance(); p.skipNewlines()
        val body: MutableList<StatementNode> = if (p.check(TokenType.LBRACE)) { p.advance(); val b = p.parseCompoundList(); p.expect(TokenType.RBRACE); b }
        else { p.expect(TokenType.DO); val b = p.parseCompoundList(); p.expect(TokenType.DONE); b }
        val rd = if (options?.skipRedirections == true) mutableListOf<RedirectionNode>() else p.parseOptionalRedirections()
        val node = CStyleForNode(init, cond, upd, body, rd); node.line = sl ?: 0; return node
    }

    fun parseWhile(p: Parser, options: SkipRedirections? = null): WhileNode {
        p.expect(TokenType.WHILE); val c = p.parseCompoundList(); p.expect(TokenType.DO); val b = p.parseCompoundList()
        if (b.isEmpty()) p.error("syntax error near unexpected token `done'")
        p.expect(TokenType.DONE)
        val rd = if (options?.skipRedirections == true) mutableListOf<RedirectionNode>() else p.parseOptionalRedirections()
        return WhileNode(c, b, rd)
    }

    fun parseUntil(p: Parser, options: SkipRedirections? = null): UntilNode {
        p.expect(TokenType.UNTIL); val c = p.parseCompoundList(); p.expect(TokenType.DO); val b = p.parseCompoundList()
        if (b.isEmpty()) p.error("syntax error near unexpected token `done'")
        p.expect(TokenType.DONE)
        val rd = if (options?.skipRedirections == true) mutableListOf<RedirectionNode>() else p.parseOptionalRedirections()
        return UntilNode(c, b, rd)
    }

    fun parseCase(p: Parser, options: SkipRedirections? = null): CaseNode {
        p.expect(TokenType.CASE); if (!p.isWord()) p.error("Expected word after 'case'")
        val word = p.parseWord(); p.skipNewlines(); p.expect(TokenType.IN); p.skipNewlines()
        val items = mutableListOf<CaseItemNode>()
        while (!p.check(TokenType.ESAC, TokenType.EOF)) {
            p.checkIterationLimit(); val pb = p.getPos(); val item = parseCaseItem(p)
            if (item != null) items.add(item); p.skipNewlines()
            if (p.getPos() == pb && item == null) break
        }
        p.expect(TokenType.ESAC)
        val rd = if (options?.skipRedirections == true) mutableListOf<RedirectionNode>() else p.parseOptionalRedirections()
        return CaseNode(word, items, rd)
    }

    private fun parseCaseItem(p: Parser): CaseItemNode? {
        if (p.check(TokenType.LPAREN)) p.advance()
        val patterns = mutableListOf<WordNode>()
        while (p.isWord()) { patterns.add(p.parseWord()); if (p.check(TokenType.PIPE)) p.advance() else break }
        if (patterns.isEmpty()) return null
        p.expect(TokenType.RPAREN); p.skipNewlines()
        val body = mutableListOf<StatementNode>()
        while (!p.check(TokenType.DSEMI, TokenType.SEMI_AND, TokenType.SEMI_SEMI_AND, TokenType.ESAC, TokenType.EOF)) {
            p.checkIterationLimit()
            if (p.isWord() && p.peek(1).type == TokenType.RPAREN) p.error("syntax error near unexpected token `)'")
            if (p.check(TokenType.LPAREN) && p.peek(1).type == TokenType.WORD) p.error("syntax error near unexpected token `${p.peek(1).value}'")
            val pb = p.getPos(); val stmt = p.parseStatement()
            if (stmt != null) body.add(stmt)
            p.skipSeparators(false)
            if (p.getPos() == pb && stmt == null) break
        }
        var term = ";;"
        when { p.check(TokenType.DSEMI) -> { p.advance(); term = ";;" }; p.check(TokenType.SEMI_AND) -> { p.advance(); term = ";&" }; p.check(TokenType.SEMI_SEMI_AND) -> { p.advance(); term = ";;&" } }
        return CaseItemNode(patterns, body, term)
    }

    fun parseSubshell(p: Parser, options: SkipRedirections? = null): SubshellNode {
        p.expect(TokenType.LPAREN); val b = p.parseCompoundList(); p.expect(TokenType.RPAREN)
        val rd = if (options?.skipRedirections == true) mutableListOf<RedirectionNode>() else p.parseOptionalRedirections()
        return SubshellNode(b, rd)
    }

    fun parseGroup(p: Parser, options: SkipRedirections? = null): GroupNode {
        p.expect(TokenType.LBRACE); val b = p.parseCompoundList(); p.expect(TokenType.RBRACE)
        val rd = if (options?.skipRedirections == true) mutableListOf<RedirectionNode>() else p.parseOptionalRedirections()
        return GroupNode(b, rd)
    }
}