package com.justbash.parser

import com.justbash.ast.*

object ArithmeticParser {
    private fun preprocess(input: String): String {
        val r = StringBuilder(); var i = 0
        while (i < input.length) {
            if (input[i] == '"') {
                i++
                while (i < input.length && input[i] != '"') {
                    if (input[i] == '\\' && i + 1 < input.length) { r.append(input[i + 1]); i += 2 }
                    else { r.append(input[i]); i++ }
                }
                if (i < input.length) i++
            } else { r.append(input[i]); i++ }
        }
        return r.toString()
    }

    fun parseArithmeticExpression(p: Parser, input: String): ArithmeticExpressionNode {
        val pre = preprocess(input)
        val (expr, pos) = parseArithExpr(p, pre, 0)
        val fp = skipArithWhitespace(pre, pos)
        if (fp < pre.length) {
            val rem = input.substring(fp).trim()
            if (rem.isNotEmpty()) return ArithmeticExpressionNode(
                ArithSyntaxErrorNode(rem, "$rem: syntax error: invalid arithmetic operator (error token is \"$rem\")"), input)
        }
        return ArithmeticExpressionNode(expr, input)
    }

    private fun missOperandError(op: String, pos: Int): Pair<ArithExpr, Int> =
        ArithSyntaxErrorNode(op, "syntax error: operand expected (error token is \"$op\")") to pos

    private fun isMissing(input: String, pos: Int) = skipArithWhitespace(input, pos) >= input.length

    fun parseArithExpr(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> = parseComma(p, input, pos)
    private fun parseNested(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> = p.withDepth { parseArithExpr(p, input, pos) }

    private fun parseComma(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (left, cp) = parseTernary(p, input, pos)
        cp = skipArithWhitespace(input, cp)
        while (cp < input.length && input[cp] == ',') {
            cp++; if (isMissing(input, cp)) return missOperandError(",", cp)
            val (right, p2) = parseTernary(p, input, cp); left = ArithBinaryNode(",", left, right); cp = skipArithWhitespace(input, p2)
        }
        return left to cp
    }

    private fun parseTernary(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        val (cond, cp) = parseLogicalOr(p, input, pos)
        var c = skipArithWhitespace(input, cp)
        if (c < input.length && input[c] == '?') {
            c++
            val (cons, p2) = parseNested(p, input, c); c = skipArithWhitespace(input, p2)
            if (c < input.length && input[c] == ':') {
                c++
                val (alt, p3) = parseNested(p, input, c)
                return ArithTernaryNode(cond, cons, alt) to p3
            }
        }
        return cond to c
    }

    private fun parseLogicalOr(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (left, cp) = parseLogicalAnd(p, input, pos)
        while (true) {
            cp = skipArithWhitespace(input, cp)
            if (cp + 1 < input.length && input[cp] == '|' && input[cp + 1] == '|') {
                cp += 2; if (isMissing(input, cp)) return missOperandError("||", cp)
                val (right, p2) = parseLogicalAnd(p, input, cp); left = ArithBinaryNode("||", left, right); cp = p2
            } else break
        }
        return left to cp
    }

    private fun parseLogicalAnd(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (left, cp) = parseBitwiseOr(p, input, pos)
        while (true) {
            cp = skipArithWhitespace(input, cp)
            if (cp + 1 < input.length && input[cp] == '&' && input[cp + 1] == '&') {
                cp += 2; if (isMissing(input, cp)) return missOperandError("&&", cp)
                val (right, p2) = parseBitwiseOr(p, input, cp); left = ArithBinaryNode("&&", left, right); cp = p2
            } else break
        }
        return left to cp
    }

    private fun parseBitwiseOr(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (left, cp) = parseBitwiseXor(p, input, pos)
        while (true) {
            cp = skipArithWhitespace(input, cp)
            if (cp < input.length && input[cp] == '|' && (cp + 1 >= input.length || input[cp + 1] != '|')) {
                cp++; if (isMissing(input, cp)) return missOperandError("|", cp)
                val (right, p2) = parseBitwiseXor(p, input, cp); left = ArithBinaryNode("|", left, right); cp = p2
            } else break
        }
        return left to cp
    }

    private fun parseBitwiseXor(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (left, cp) = parseBitwiseAnd(p, input, pos)
        while (true) {
            cp = skipArithWhitespace(input, cp)
            if (cp < input.length && input[cp] == '^') {
                cp++; if (isMissing(input, cp)) return missOperandError("^", cp)
                val (right, p2) = parseBitwiseAnd(p, input, cp); left = ArithBinaryNode("^", left, right); cp = p2
            } else break
        }
        return left to cp
    }

    private fun parseBitwiseAnd(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (left, cp) = parseEquality(p, input, pos)
        while (true) {
            cp = skipArithWhitespace(input, cp)
            if (cp < input.length && input[cp] == '&' && (cp + 1 >= input.length || input[cp + 1] != '&')) {
                cp++; if (isMissing(input, cp)) return missOperandError("&", cp)
                val (right, p2) = parseEquality(p, input, cp); left = ArithBinaryNode("&", left, right); cp = p2
            } else break
        }
        return left to cp
    }

    private fun parseEquality(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (left, cp) = parseRelational(p, input, pos)
        while (true) {
            cp = skipArithWhitespace(input, cp)
            val two = if (cp + 1 < input.length) input.substring(cp, cp + 2) else ""
            if (two == "==" || two == "!=") {
                cp += 2; if (isMissing(input, cp)) return missOperandError(two, cp)
                val (right, p2) = parseRelational(p, input, cp); left = ArithBinaryNode(two, left, right); cp = p2
            } else break
        }
        return left to cp
    }

    private fun parseRelational(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (left, cp) = parseShift(p, input, pos)
        while (true) {
            cp = skipArithWhitespace(input, cp)
            val two = if (cp + 1 < input.length) input.substring(cp, cp + 2) else ""
            if (two == "<=" || two == ">=") {
                cp += 2; if (isMissing(input, cp)) return missOperandError(two, cp)
                val (right, p2) = parseShift(p, input, cp); left = ArithBinaryNode(two, left, right); cp = p2
            } else if (cp < input.length && (input[cp] == '<' || input[cp] == '>')) {
                val op = input[cp].toString(); cp++
                if (isMissing(input, cp)) return missOperandError(op, cp)
                val (right, p2) = parseShift(p, input, cp); left = ArithBinaryNode(op, left, right); cp = p2
            } else break
        }
        return left to cp
    }

    private fun parseShift(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (left, cp) = parseAdditive(p, input, pos)
        while (true) {
            cp = skipArithWhitespace(input, cp)
            val two = if (cp + 1 < input.length) input.substring(cp, cp + 2) else ""
            if (two == "<<" || two == ">>") {
                cp += 2; if (isMissing(input, cp)) return missOperandError(two, cp)
                val (right, p2) = parseAdditive(p, input, cp); left = ArithBinaryNode(two, left, right); cp = p2
            } else break
        }
        return left to cp
    }

    private fun parseAdditive(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (left, cp) = parseMultiplicative(p, input, pos)
        while (true) {
            cp = skipArithWhitespace(input, cp)
            if (cp < input.length && (input[cp] == '+' || input[cp] == '-') &&
                (cp + 1 >= input.length || input[cp + 1] != input[cp])) {
                val op = input[cp].toString(); cp++
                if (isMissing(input, cp)) return missOperandError(op, cp)
                val (right, p2) = parseMultiplicative(p, input, cp); left = ArithBinaryNode(op, left, right); cp = p2
            } else break
        }
        return left to cp
    }

    private fun parseMultiplicative(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (left, cp) = parsePower(p, input, pos)
        while (true) {
            cp = skipArithWhitespace(input, cp)
            if (cp < input.length && input[cp] == '*' && (cp + 1 >= input.length || input[cp + 1] != '*')) {
                cp++; if (isMissing(input, cp)) return missOperandError("*", cp)
                val (right, p2) = parsePower(p, input, cp); left = ArithBinaryNode("*", left, right); cp = p2
            } else if (cp < input.length && (input[cp] == '/' || input[cp] == '%')) {
                val op = input[cp].toString(); cp++
                if (isMissing(input, cp)) return missOperandError(op, cp)
                val (right, p2) = parsePower(p, input, cp); left = ArithBinaryNode(op, left, right); cp = p2
            } else break
        }
        return left to cp
    }

    private fun parsePower(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        val (base, cp) = parseUnary(p, input, pos)
        var p2 = skipArithWhitespace(input, cp)
        val two = if (p2 + 1 < input.length) input.substring(p2, p2 + 2) else ""
        if (two == "**") {
            p2 += 2; if (isMissing(input, p2)) return missOperandError("**", p2)
            val (exp, p3) = p.withDepth { parsePower(p, input, p2) }
            return ArithBinaryNode("**", base, exp) to p3
        }
        return base to cp
    }

    private fun parseUnary(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var cp = skipArithWhitespace(input, pos)
        val two = if (cp + 1 < input.length) input.substring(cp, cp + 2) else ""
        if (two == "++" || two == "--") {
            cp += 2
            val (op, p2) = p.withDepth { parseUnary(p, input, cp) }
            return ArithUnaryNode(two, op, true) to p2
        }
        if (cp < input.length && (input[cp] == '+' || input[cp] == '-' || input[cp] == '!' || input[cp] == '~')) {
            val op = input[cp].toString(); cp++
            val (operand, p2) = p.withDepth { parseUnary(p, input, cp) }
            return ArithUnaryNode(op, operand, true) to p2
        }
        return parsePostfix(p, input, cp)
    }

    private fun canStartConcat(input: String, pos: Int): Boolean =
        pos < input.length && (input[pos] == '$' || input[pos] == '`')

    private fun parsePostfix(p: Parser, input: String, pos: Int): Pair<ArithExpr, Int> {
        var (expr, cp) = parsePrimary(p, input, pos, false)
        val parts = mutableListOf(expr)
        while (canStartConcat(input, cp)) {
            val (next, np) = parsePrimary(p, input, cp, true); parts.add(next); cp = np
        }
        if (parts.size > 1) expr = ArithConcatNode(parts)

        var subscript: ArithExpr? = null
        if (cp < input.length && input[cp] == '[' && expr is ArithConcatNode) {
            cp++
            val (ie, p2) = parseNested(p, input, cp); subscript = ie; cp = p2
            if (cp < input.length && input[cp] == ']') cp++
        }
        if (subscript != null && expr is ArithConcatNode) expr = ArithDynamicElementNode(expr, subscript)

        cp = skipArithWhitespace(input, cp)
        if (expr is ArithConcatNode || expr is ArithVariableNode || expr is ArithDynamicElementNode) {
            for (op in ARITH_ASSIGN_OPS) {
                if (cp + op.length <= input.length && input.substring(cp, cp + op.length) == op &&
                    (cp + op.length + 1 > input.length || input.substring(cp, cp + op.length + 1) != "==")) {
                    cp += op.length
                    val (value, p2) = p.withDepth { parseTernary(p, input, cp) }
                    return when (expr) {
                        is ArithDynamicElementNode -> ArithDynamicAssignmentNode(op, expr.nameExpr, value, expr.subscript) to p2
                        is ArithConcatNode -> ArithDynamicAssignmentNode(op, expr, value) to p2
                        is ArithVariableNode -> ArithAssignmentNode(op, expr.name, value) to p2
                        else -> expr to cp
                    }
                }
            }
        }

        val two2 = if (cp + 1 < input.length) input.substring(cp, cp + 2) else ""
        if (two2 == "++" || two2 == "--") { cp += 2; return ArithUnaryNode(two2, expr, false) to cp }
        return expr to cp
    }

    private fun parsePrimary(p: Parser, input: String, pos: Int, skipAssignment: Boolean = false): Pair<ArithExpr, Int> {
        var cp = skipArithWhitespace(input, pos)
        parseNestedArithmetic(::parseNested, p, input, cp)?.let { return it }
        parseAnsiCQuoting(input, cp)?.let { return it }
        parseLocalizationQuoting(input, cp)?.let { return it }

        if (cp + 2 < input.length && input[cp] == '$' && input[cp + 1] == '(' && input[cp + 2] != '(') {
            cp += 2; var depth = 1; val cs = cp
            while (cp < input.length && depth > 0) {
                if (input[cp] == '(') depth++; else if (input[cp] == ')') depth--
                if (depth > 0) cp++
            }
            val cmd = input.substring(cs, cp); cp++
            return ArithCommandSubstNode(cmd) to cp
        }
        if (cp < input.length && input[cp] == '`') {
            cp++; val cs = cp
            while (cp < input.length && input[cp] != '`') cp++
            val cmd = input.substring(cs, cp)
            if (cp < input.length && input[cp] == '`') cp++
            return ArithCommandSubstNode(cmd) to cp
        }
        if (cp < input.length && input[cp] == '(') {
            cp++
            val (e, p2) = parseNested(p, input, cp); cp = skipArithWhitespace(input, p2)
            if (cp < input.length && input[cp] == ')') cp++
            return ArithGroupNode(e) to cp
        }
        if (cp < input.length && input[cp] == '\'') {
            cp++; val content = StringBuilder()
            while (cp < input.length && input[cp] != '\'') { content.append(input[cp]); cp++ }
            if (cp < input.length && input[cp] == '\'') cp++
            val nv = content.toString().toLongOrNull(10) ?: 0L
            return ArithSingleQuoteNode(content.toString(), nv) to cp
        }
        if (cp < input.length && input[cp] == '"') {
            cp++; val content = StringBuilder()
            while (cp < input.length && input[cp] != '"') {
                if (input[cp] == '\\' && cp + 1 < input.length) { content.append(input[cp + 1]); cp += 2 }
                else { content.append(input[cp]); cp++ }
            }
            if (cp < input.length && input[cp] == '"') cp++
            val trimmed = content.toString().trim()
            if (trimmed.isEmpty()) return ArithNumberNode(0L) to cp
            val (e, _) = parseNested(p, trimmed, 0)
            return e to cp
        }
        if (cp < input.length && input[cp] in '0'..'9') {
            val ns = StringBuilder(); var seenHash = false; var isHex = false
            while (cp < input.length) {
                val ch = input[cp]
                when {
                    seenHash -> if (ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == '@' || ch == '_') { ns.append(ch); cp++ } else break
                    ch == '#' -> { seenHash = true; ns.append(ch); cp++ }
                    ns.toString() == "0" && (ch == 'x' || ch == 'X') && cp + 1 < input.length && (input[cp + 1] in '0'..'9' || input[cp + 1] in 'a'..'f' || input[cp + 1] in 'A'..'F') -> { isHex = true; ns.append(ch); cp++ }
                    isHex && (ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F') -> { ns.append(ch); cp++ }
                    !isHex && ch in '0'..'9' -> { ns.append(ch); cp++ }
                    else -> break
                }
            }
            if (cp < input.length && (input[cp] in 'a'..'z' || input[cp] in 'A'..'Z' || input[cp] == '_')) {
                var it = ns.toString()
                while (cp < input.length && (input[cp] in 'a'..'z' || input[cp] in 'A'..'Z' || input[cp] in '0'..'9' || input[cp] == '_')) { it += input[cp]; cp++ }
                return ArithSyntaxErrorNode(it, "$it: value too great for base (error token is \"$it\")") to cp
            }
            if (cp < input.length && input[cp] == '.' && cp + 1 < input.length && input[cp + 1] in '0'..'9') {
                return ArithSyntaxErrorNode("${ns}.${input[cp + 1]}...", "${ns}.${input[cp + 1]}...: syntax error: invalid arithmetic operator") to (cp + 2)
            }
            if (cp < input.length && input[cp] == '[') {
                val et = input.substring(cp).trim()
                return ArithNumberSubscriptNode(ns.toString(), et) to input.length
            }
            return ArithNumberNode(parseArithNumber(ns.toString())) to cp
        }
        if (cp + 1 < input.length && input[cp] == '$' && input[cp + 1] == '{') {
            val bs = cp + 2; var bd = 1; var i = bs
            while (i < input.length && bd > 0) {
                if (input[i] == '{') bd++; else if (input[i] == '}') bd--
                if (bd > 0) i++
            }
            val content = input.substring(bs, i); val ab = i + 1
            if (ab < input.length && input[ab] == '#') {
                var ve = ab + 1
                while (ve < input.length && (input[ve] in '0'..'9' || input[ve] in 'a'..'z' || input[ve] in 'A'..'Z' || input[ve] == '@' || input[ve] == '_')) ve++
                return ArithDynamicBaseNode(content, input.substring(ab + 1, ve)) to ve
            }
            if (ab < input.length && (input[ab] in '0'..'9' || input[ab] == 'x' || input[ab] == 'X')) {
                var ne = ab
                if (input[ab] == 'x' || input[ab] == 'X') { ne++; while (ne < input.length && (input[ne] in '0'..'9' || input[ne] in 'a'..'f' || input[ne] in 'A'..'F')) ne++ }
                else while (ne < input.length && input[ne] in '0'..'9') ne++
                return ArithDynamicNumberNode(content, input.substring(ab, ne)) to ne
            }
            cp = ab
            return ArithBracedExpansionNode(content) to cp
        }
        if (cp + 1 < input.length && input[cp] == '$' && input[cp + 1] in '0'..'9') {
            cp++; val name = StringBuilder()
            while (cp < input.length && input[cp] in '0'..'9') { name.append(input[cp]); cp++ }
            return ArithVariableNode(name.toString(), true) to cp
        }
        if (cp + 1 < input.length && input[cp] == '$' && "*@#?\\-!\$".contains(input[cp + 1])) {
            val name = input[cp + 1].toString(); cp += 2
            return ArithSpecialVarNode(name) to cp
        }
        var hdp = false
        if (cp + 1 < input.length && input[cp] == '$' && (input[cp + 1] in 'a'..'z' || input[cp + 1] in 'A'..'Z' || input[cp + 1] == '_')) { hdp = true; cp++ }
        if (cp < input.length && (input[cp] in 'a'..'z' || input[cp] in 'A'..'Z' || input[cp] == '_')) {
            val name = StringBuilder()
            while (cp < input.length && (input[cp] in 'a'..'z' || input[cp] in 'A'..'Z' || input[cp] in '0'..'9' || input[cp] == '_')) { name.append(input[cp]); cp++ }
            if (cp < input.length && input[cp] == '[' && !skipAssignment) {
                cp++; var sk: String? = null
                if (cp < input.length && (input[cp] == '\'' || input[cp] == '"')) {
                    val q = input[cp]; cp++; val key = StringBuilder()
                    while (cp < input.length && input[cp] != q) { key.append(input[cp]); cp++ }
                    if (cp < input.length && input[cp] == q) cp++
                    sk = key.toString(); cp = skipArithWhitespace(input, cp)
                    if (cp < input.length && input[cp] == ']') cp++
                }
                var ie: ArithExpr? = null
                if (sk == null) { val (e, p2) = parseNested(p, input, cp); ie = e; cp = p2; if (cp < input.length && input[cp] == ']') cp++ }
                cp = skipArithWhitespace(input, cp)
                if (cp < input.length && input[cp] == '[' && ie != null) return ArithDoubleSubscriptNode(name.toString(), ie) to cp
                if (!skipAssignment) {
                    for (op in ARITH_ASSIGN_OPS) {
                        if (cp + op.length <= input.length && input.substring(cp, cp + op.length) == op &&
                            (cp + op.length + 1 > input.length || input.substring(cp, cp + op.length + 1) != "==")) {
                            cp += op.length
                            val (value, p2) = p.withDepth { parseTernary(p, input, cp) }
                            return ArithAssignmentNode(op, name.toString(), value, ie, sk) to p2
                        }
                    }
                }
                return ArithArrayElementNode(name.toString(), ie, sk) to cp
            }
            cp = skipArithWhitespace(input, cp)
            if (!skipAssignment) {
                for (op in ARITH_ASSIGN_OPS) {
                    if (cp + op.length <= input.length && input.substring(cp, cp + op.length) == op &&
                        (cp + op.length + 1 > input.length || input.substring(cp, cp + op.length + 1) != "==")) {
                        cp += op.length
                        val (value, p2) = p.withDepth { parseTernary(p, input, cp) }
                        return ArithAssignmentNode(op, name.toString(), value) to p2
                    }
                }
            }
            return ArithVariableNode(name.toString(), hdp) to cp
        }
        if (cp < input.length && input[cp] == '#') {
            var ee = cp + 1
            while (ee < input.length && input[ee] != '\n') ee++
            val et = input.substring(cp, ee).trim().ifEmpty { "#" }
            return ArithSyntaxErrorNode(et, "$et: syntax error: invalid arithmetic operator (error token is \"$et\")") to input.length
        }
        return ArithNumberNode(0L) to cp
    }
}