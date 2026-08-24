package com.justbash.interpreter.expansion

import com.justbash.ast.*
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ExpansionTest {
    private fun ctx(): ExpansionHost { val fs = InMemoryFs(); return ExpansionHost(state = InterpreterState(), fs = fs, _execFn = { RunResult("", "", 0) }, _executeScript = { RunResult("", "", 0) }, _globList = { emptyList() }) }

    @Test fun `parseArithNumber decimal`() { assertEquals(42.0, parseArithNumber("42")); assertEquals(-5.0, parseArithNumber("-5")); assertEquals(0.0, parseArithNumber("0")) }
    @Test fun `parseArithNumber hex`() { assertEquals(255.0, parseArithNumber("0xFF")); assertEquals(26.0, parseArithNumber("0x1a")) }
    @Test fun `parseArithNumber octal`() { assertEquals(8.0, parseArithNumber("010")) }
    @Test fun `parseArithNumber base`() { assertEquals(10.0, parseArithNumber("2#1010")); assertEquals(255.0, parseArithNumber("16#ff")) }
    @Test fun `arithmetic binary`() { val c = ctx(); assertEquals(8.0, evaluateArithmetic(c, ArithBinaryNode("+", ArithNumberNode(5), ArithNumberNode(3)))) }
    @Test fun `arithmetic div by zero`() { val c = ctx(); assertThrows(ArithmeticError::class.java) { evaluateArithmetic(c, ArithBinaryNode("/", ArithNumberNode(5), ArithNumberNode(0))) } }
    @Test fun `arithmetic variable`() { val c = ctx(); c.state.env["x"] = "5"; assertEquals(8.0, evaluateArithmetic(c, ArithBinaryNode("+", ArithVariableNode("x"), ArithNumberNode(3)))) }
    @Test fun `arithmetic unset zero`() { val c = ctx(); assertEquals(5.0, evaluateArithmetic(c, ArithBinaryNode("+", ArithVariableNode("u"), ArithNumberNode(5)))) }
    @Test fun `arithmetic assignment`() { val c = ctx(); val r = evaluateArithmetic(c, ArithAssignmentNode("=", "x", ArithNumberNode(42))); assertEquals(42.0, r); assertEquals("42", c.state.env["x"]) }
    @Test fun `arithmetic ternary`() { val c = ctx(); assertEquals(10.0, evaluateArithmetic(c, ArithTernaryNode(ArithNumberNode(1), ArithNumberNode(10), ArithNumberNode(20)))) }
    @Test fun `brace range`() { assertEquals(listOf("1","2","3","4","5"), expandBraceRange(1, 5, 1).expanded) }
    @Test fun `brace range char`() { assertEquals(listOf("a","b","c","d","e"), expandBraceRange("a", "e", 1).expanded) }
    @Test fun `brace range mixed case`() { assertThrows(BraceExpansionError::class.java) { expandBraceRange("z", "A", 1) } }
    @Test fun `pattern regex`() { assertEquals(".*", patternToRegex("*", true)); assertEquals(".", patternToRegex("?", true)); assertEquals("[abc]", patternToRegex("[abc]", true)) }
    @Test fun `pattern extglob`() { assertEquals("(?:a|b)", patternToRegex("@(a|b)", true, extglob = true)) }
    @Test fun `glob escape`() { assertEquals("\\*", escapeGlobChars("*")); assertEquals("\\?", escapeGlobChars("?")) }
    @Test fun `hasGlobPattern`() { assertTrue(hasGlobPattern("file*", false)); assertFalse(hasGlobPattern("file", false)) }
    @Test fun `quoteValue`() { assertEquals("''", quoteValue("")); assertEquals("'hello'", quoteValue("hello")) }
    @Test fun `tilde`() { val c = ctx(); c.state.env["HOME"] = "/home/u"; assertEquals("/home/u", applyTildeExpansion(c, "~")); assertEquals("/home/u/docs", applyTildeExpansion(c, "~/docs")); assertEquals("/root", applyTildeExpansion(c, "~root")) }
    @Test fun `ifs`() { assertEquals(" \t\n", getIfs(LinkedHashMap())); assertEquals(listOf("a","b","c"), splitByIfsForExpansion("a b c", " \t\n", 1000)); assertEquals(listOf("a b c"), splitByIfsForExpansion("a b c", "", 1000)) }
    @Test fun `getVariable scalar`() { val c = ctx(); c.state.env["foo"] = "bar"; assertEquals("bar", getVariable(c, "foo")) }
    @Test fun `getVariable unset`() { assertEquals("", getVariable(ctx(), "nonexistent")) }
    @Test fun `getVariable nounset`() { val c = ctx(); c.state.options.nounset = true; assertThrows(NounsetError::class.java) { getVariable(c, "x") } }
    @Test fun `getVariable dollarQ`() { val c = ctx(); c.state.lastExitCode = 42; assertEquals("42", getVariable(c, "?")) }
    @Test fun `getVariable BASH_VERSION`() { assertEquals("5.1.0(1)-release", getVariable(ctx(), "BASH_VERSION")) }
    @Test fun `getVariable array`() { val c = ctx(); setArrayElement(c, "arr", 0, "zero"); assertEquals("zero", getVariable(c, "arr[0]")); setArrayElement(c, "arr", 1, "one"); assertEquals("zero one", getVariable(c, "arr[@]")) }
    @Test fun `isVariableSet`() { val c = ctx(); c.state.env["x"] = "1"; assertTrue(isVariableSet(c, "x")); assertFalse(isVariableSet(c, "y")) }
    @Test fun `expandParameter default`() { val c = ctx(); val p = ParameterExpansionPart("x", DefaultValueOp(WordNode(LiteralPart("default")), true)); assertEquals("default", expandParameter(c, p)) }
    @Test fun `expandParameter assign default`() { val c = ctx(); val p = ParameterExpansionPart("x", AssignDefaultOp(WordNode(LiteralPart("assigned")), true)); assertEquals("assigned", expandParameter(c, p)); assertEquals("assigned", c.state.env["x"]) }
    @Test fun `expandParameter use alternative`() { val c = ctx(); c.state.env["x"] = "hello"; val p = ParameterExpansionPart("x", UseAlternativeOp(WordNode(LiteralPart("alt")), true)); assertEquals("alt", expandParameter(c, p)) }
    @Test fun `expandParameter length`() { val c = ctx(); c.state.env["x"] = "hello"; val p = ParameterExpansionPart("x", LengthOp()); assertEquals("5", expandParameter(c, p)) }
    @Test fun `expandParameter pattern removal`() { val c = ctx(); c.state.env["x"] = "hello.txt"; val pat = WordNode(GlobPart("hello.")); val p = ParameterExpansionPart("x", PatternRemovalOp(pat, "prefix", false)); assertEquals("txt", expandParameter(c, p)) }
    @Test fun `expandParameter pattern replacement`() { val c = ctx(); c.state.env["x"] = "hello world"; val pat = WordNode(LiteralPart("hello")); val rep = WordNode(LiteralPart("hi")); val p = ParameterExpansionPart("x", PatternReplacementOp(pat, rep, false, null)); assertEquals("hi world", expandParameter(c, p)) }
    @Test fun `expandParameter pattern replacement all`() { val c = ctx(); c.state.env["x"] = "a_b_c"; val pat = WordNode(LiteralPart("_")); val rep = WordNode(LiteralPart("-")); val p = ParameterExpansionPart("x", PatternReplacementOp(pat, rep, true, null)); assertEquals("a-b-c", expandParameter(c, p)) }
    @Test fun `expandParameter case upper`() { val c = ctx(); c.state.env["x"] = "hello"; val p = ParameterExpansionPart("x", CaseModificationOp("upper", true, null)); assertEquals("HELLO", expandParameter(c, p)) }
    @Test fun `expandParameter error if unset`() { val c = ctx(); val p = ParameterExpansionPart("x", ErrorIfUnsetOp(WordNode(LiteralPart("missing")), true)); assertThrows(ExitError::class.java) { expandParameter(c, p) } }
    @Test fun `expandParameter bad substitution`() { val c = ctx(); val p = ParameterExpansionPart("x", BadSubstitutionOp("bad")); assertThrows(BadSubstitutionError::class.java) { expandParameter(c, p) } }
    @Test fun `expandWord literal`() { assertEquals("hello", expandWord(ctx(), WordNode(LiteralPart("hello")))) }
    @Test fun `expandWord variable`() { val c = ctx(); c.state.env["name"] = "World"; val w = WordNode(mutableListOf(LiteralPart("Hello "), ParameterExpansionPart("name"), LiteralPart("!"))); assertEquals("Hello World!", expandWord(c, w)) }
    @Test fun `expandWord double quoted`() { val w = WordNode(DoubleQuotedPart(mutableListOf(LiteralPart("hello")))); assertEquals("hello", expandWord(ctx(), w)) }
    @Test fun `expandWord arithmetic`() { val c = ctx(); val e = ArithmeticExpressionNode(ArithBinaryNode("+", ArithNumberNode(5), ArithNumberNode(3))); val w = WordNode(ArithmeticExpansionPart(e)); assertEquals("8", expandWord(c, w)) }
    @Test fun `array helpers`() { val c = ctx(); setArrayElement(c, "arr", 0, "zero"); assertEquals("zero", getArrayElement(c, "arr", 0)); assertTrue(isArray(c, "arr")) }
    @Test fun `pattern removal`() { val c = ctx(); assertEquals("world", applyPatternRemoval(c, "hello world", "hello ", "prefix", false)); assertEquals("hello", applyPatternRemoval(c, "hello world", " world", "suffix", false)) }
    @Test fun `variable attrs`() { val c = ctx(); assertEquals("", getVariableAttributes(c, "x")); setArrayElement(c, "arr", 0, "v"); assertEquals("a", getVariableAttributes(c, "arr")); c.state.integerVars.add("n"); assertEquals("i", getVariableAttributes(c, "n")); c.state.namerefs.add("ref"); assertEquals("n", getVariableAttributes(c, "ref")); c.state.readonlyVars.add("r"); assertEquals("r", getVariableAttributes(c, "r")); c.state.exportedVars.add("e"); assertEquals("x", getVariableAttributes(c, "e")) }
}
