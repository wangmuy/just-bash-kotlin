package com.justbash.parser

import com.justbash.ast.*

object ConditionalParser {
    private val UNARY_OPS = setOf(
        "-a", "-b", "-c", "-d", "-e", "-f", "-g", "-h", "-k", "-p", "-r", "-s",
        "-t", "-u", "-w", "-x", "-G", "-L", "-N", "-O", "-S", "-z", "-n", "-o", "-v", "-R",
    )
    private val BINARY_OPS = setOf(
        "==", "!=", "=~", "<", ">", "-eq", "-ne", "-lt", "-le", "-gt", "-ge", "-nt", "-ot", "-ef",
    )

    private fun isCondOperand(p: Parser): Boolean = p.isWord() || p.check(TokenType.LBRACE, TokenType.RBRACE, TokenType.ASSIGNMENT_WORD)

    private fun parsePatternWord(p: Parser): WordNode {
        if (p.check(TokenType.BANG) && p.peek(1).type == TokenType.LPAREN) {
            p.advance(); p.advance(); var depth = 1; val pattern = StringBuilder("!(")
            while (depth > 0 && !p.check(TokenType.EOF)) {
                when {
                    p.check(TokenType.LPAREN) -> { depth++; pattern.append("("); p.advance() }
                    p.check(TokenType.RPAREN) -> { depth--; if (depth > 0) pattern.append(")"); p.advance() }
                    p.isWord() -> pattern.append(p.advance().value)
                    p.check(TokenType.PIPE) -> { pattern.append("|"); p.advance() }
                    else -> break
                }
            }
            pattern.append(")")
            return p.parseWordFromString(pattern.toString(), false, false, false, false, true)
        }
        return p.parseWordNoBraceExpansion()
    }

    fun parseConditionalExpression(p: Parser): ConditionalExpressionNode {
        p.skipNewlines(); return parseCondOr(p)
    }

    private fun parseCondOr(p: Parser): ConditionalExpressionNode {
        var left = parseCondAnd(p); p.skipNewlines()
        while (p.check(TokenType.OR_OR)) {
            p.advance(); p.skipNewlines(); val right = parseCondAnd(p); left = CondOrNode(left, right); p.skipNewlines()
        }
        return left
    }

    private fun parseCondAnd(p: Parser): ConditionalExpressionNode {
        var left = parseCondNot(p); p.skipNewlines()
        while (p.check(TokenType.AND_AND)) {
            p.advance(); p.skipNewlines(); val right = parseCondNot(p); left = CondAndNode(left, right); p.skipNewlines()
        }
        return left
    }

    private fun parseCondNot(p: Parser): ConditionalExpressionNode {
        p.skipNewlines()
        if (p.check(TokenType.BANG)) { p.advance(); p.skipNewlines(); val op = p.withDepth { parseCondNot(p) }; return CondNotNode(op) }
        return parseCondPrimary(p)
    }

    private fun parseCondPrimary(p: Parser): ConditionalExpressionNode {
        if (p.check(TokenType.LPAREN)) {
            p.advance(); val e = p.withDepth { parseConditionalExpression(p) }; p.expect(TokenType.RPAREN); return CondGroupNode(e)
        }
        if (isCondOperand(p)) {
            val ft = p.current(); val first = ft.value
            if (UNARY_OPS.contains(first) && !ft.quoted) {
                p.advance()
                if (p.check(TokenType.DBRACK_END)) p.error("Expected operand after $first")
                if (isCondOperand(p)) { val operand = p.parseWordNoBraceExpansion(); return CondUnaryNode(first, operand) }
                val bt = p.current(); p.error("unexpected argument `${bt.value}' to conditional unary operator")
            }
            val left = p.parseWordNoBraceExpansion()
            if (p.isWord() && BINARY_OPS.contains(p.current().value)) {
                val op = p.advance().value
                val right = when (op) {
                    "=~" -> parseRegexPattern(p)
                    "==", "!=" -> parsePatternWord(p)
                    else -> p.parseWordNoBraceExpansion()
                }
                return CondBinaryNode(op, left, right)
            }
            if (p.check(TokenType.LESS)) { p.advance(); return CondBinaryNode("<", left, p.parseWordNoBraceExpansion()) }
            if (p.check(TokenType.GREAT)) { p.advance(); return CondBinaryNode(">", left, p.parseWordNoBraceExpansion()) }
            if (p.isWord() && p.current().value == "=") { p.advance(); return CondBinaryNode("==", left, parsePatternWord(p)) }
            return CondWordNode(left)
        }
        p.error("Expected conditional expression")
    }

    private fun parseRegexPattern(p: Parser): WordNode {
        val parts = mutableListOf<WordPart>(); var pd = 0; var lte = -1; val input = p.getInput()
        fun isTerm() = p.check(TokenType.DBRACK_END) || p.check(TokenType.AND_AND) || p.check(TokenType.OR_OR) || p.check(TokenType.NEWLINE) || p.check(TokenType.EOF)
        while (!isTerm()) {
            val ct = p.current(); val gap = lte >= 0 && ct.start > lte
            if (pd == 0 && gap) break
            if (pd > 0 && gap) parts.add(LiteralPart(input.substring(lte, ct.start)))
            when {
                p.isWord() || p.check(TokenType.ASSIGNMENT_WORD) -> { val w = p.parseWordForRegex(); parts.addAll(w.parts); lte = p.peek(-1).end }
                p.check(TokenType.LPAREN) -> { val t = p.advance(); parts.add(LiteralPart("(")); pd++; lte = t.end }
                p.check(TokenType.DPAREN_START) -> { val t = p.advance(); parts.add(LiteralPart("((")); pd += 2; lte = t.end }
                p.check(TokenType.DPAREN_END) -> { if (pd >= 2) { val t = p.advance(); parts.add(LiteralPart("))")); pd -= 2; lte = t.end } else break }
                p.check(TokenType.RPAREN) -> { if (pd > 0) { val t = p.advance(); parts.add(LiteralPart(")")); pd--; lte = t.end } else break }
                p.check(TokenType.PIPE) -> { val t = p.advance(); parts.add(LiteralPart("|")); lte = t.end }
                p.check(TokenType.SEMICOLON) -> { if (pd > 0) { val t = p.advance(); parts.add(LiteralPart(";")); lte = t.end } else break }
                pd > 0 && p.check(TokenType.LESS) -> { val t = p.advance(); parts.add(LiteralPart("<")); lte = t.end }
                pd > 0 && p.check(TokenType.GREAT) -> { val t = p.advance(); parts.add(LiteralPart(">")); lte = t.end }
                pd > 0 && p.check(TokenType.DGREAT) -> { val t = p.advance(); parts.add(LiteralPart(">>")); lte = t.end }
                pd > 0 && p.check(TokenType.DLESS) -> { val t = p.advance(); parts.add(LiteralPart("<<")); lte = t.end }
                pd > 0 && p.check(TokenType.LESSAND) -> { val t = p.advance(); parts.add(LiteralPart("<&")); lte = t.end }
                pd > 0 && p.check(TokenType.GREATAND) -> { val t = p.advance(); parts.add(LiteralPart(">&")); lte = t.end }
                pd > 0 && p.check(TokenType.LESSGREAT) -> { val t = p.advance(); parts.add(LiteralPart("<>")); lte = t.end }
                pd > 0 && p.check(TokenType.CLOBBER) -> { val t = p.advance(); parts.add(LiteralPart(">|")); lte = t.end }
                pd > 0 && p.check(TokenType.TLESS) -> { val t = p.advance(); parts.add(LiteralPart("<<<")); lte = t.end }
                pd > 0 && p.check(TokenType.AMP) -> { val t = p.advance(); parts.add(LiteralPart("&")); lte = t.end }
                pd > 0 && p.check(TokenType.LBRACE) -> { val t = p.advance(); parts.add(LiteralPart("{")); lte = t.end }
                pd > 0 && p.check(TokenType.RBRACE) -> { val t = p.advance(); parts.add(LiteralPart("}")); lte = t.end }
                else -> break
            }
        }
        if (parts.isEmpty()) p.error("Expected regex pattern after =~")
        return WordNode(parts.toMutableList())
    }
}