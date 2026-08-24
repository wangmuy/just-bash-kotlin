package com.justbash.parser

import com.justbash.ast.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Parser tests for the just-bash Kotlin parser port.
 */
class ParserTests {

    private fun parseScript(text: String): ScriptNode = parse(text, ParseOptions())

    // =========================================================================
    // SIMPLE COMMANDS
    // =========================================================================

    @Test
    fun `simple command with single word`() {
        val script = parseScript("echo hello")
        assertEquals(1, script.statements.size)
        val stmt = script.statements[0]
        val pipeline = stmt.pipelines[0]
        val cmd = pipeline.commands[0] as SimpleCommandNode
        assertEquals("echo", cmd.name?.parts?.joinToString("") { (it as? LiteralPart)?.value ?: "" })
        assertEquals(1, cmd.args.size)
        assertTrue(cmd.args[0].parts.any { it is LiteralPart && it.value == "hello" })
    }

    @Test
    fun `simple command with flags`() {
        val script = parseScript("ls -la /tmp")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        assertEquals("ls", (cmd.name?.parts?.get(0) as? LiteralPart)?.value)
        assertEquals(2, cmd.args.size)
    }

    @Test
    fun `empty command`() {
        val script = parseScript("")
        assertEquals(0, script.statements.size)
    }

    @Test
    fun `assignment before command`() {
        val script = parseScript("FOO=bar cmd")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        assertEquals(1, cmd.assignments.size)
        assertEquals("FOO", cmd.assignments[0].name)
        assertEquals("cmd", (cmd.name?.parts?.get(0) as? LiteralPart)?.value)
    }

    // =========================================================================
    // QUOTING
    // =========================================================================

    @Test
    fun `single quoted string`() {
        val script = parseScript("echo 'hello world'")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        val part = cmd.args[0].parts[0]
        assertTrue(part is SingleQuotedPart)
        assertEquals("hello world", (part as SingleQuotedPart).value)
    }

    @Test
    fun `double quoted string`() {
        val script = parseScript("echo \"hello world\"")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        val part = cmd.args[0].parts[0]
        assertTrue(part is DoubleQuotedPart)
    }

    @Test
    fun `escaped characters`() {
        val script = parseScript("echo \\\$HOME")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        // Should have escaped $ and literal HOME
        val parts = cmd.args[0].parts
        assertTrue(parts.any { it is EscapedPart && it.value == "\$" })
        assertTrue(parts.any { it is LiteralPart && it.value == "HOME" })
    }

    // =========================================================================
    // PARAMETER EXPANSION
    // =========================================================================

    @Test
    fun `simple parameter expansion`() {
        val script = parseScript("echo \$HOME")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        val part = cmd.args[0].parts[0]
        assertTrue(part is ParameterExpansionPart)
        assertEquals("HOME", (part as ParameterExpansionPart).parameter)
    }

    @Test
    fun `braced parameter expansion`() {
        val script = parseScript("echo \${HOME}")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        val part = cmd.args[0].parts[0]
        assertTrue(part is ParameterExpansionPart)
        assertEquals("HOME", (part as ParameterExpansionPart).parameter)
    }

    @Test
    fun `default value expansion`() {
        val script = parseScript("echo \${VAR:-default}")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        val part = cmd.args[0].parts[0]
        assertTrue(part is ParameterExpansionPart)
        val op = (part as ParameterExpansionPart).operation
        assertTrue(op is DefaultValueOp)
        assertTrue((op as DefaultValueOp).checkEmpty)
    }

    @Test
    fun `special parameter expansion`() {
        val script = parseScript("echo \$?")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        val part = cmd.args[0].parts[0]
        assertTrue(part is ParameterExpansionPart)
        assertEquals("?", (part as ParameterExpansionPart).parameter)
    }

    // =========================================================================
    // COMMAND SUBSTITUTION
    // =========================================================================

    @Test
    fun `command substitution with dollar paren`() {
        val script = parseScript("echo \$(pwd)")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        val part = cmd.args[0].parts[0]
        assertTrue(part is CommandSubstitutionPart)
        assertFalse((part as CommandSubstitutionPart).legacy)
    }

    @Test
    fun `backtick command substitution`() {
        val script = parseScript("echo `pwd`")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        val part = cmd.args[0].parts[0]
        assertTrue(part is CommandSubstitutionPart)
        assertTrue((part as CommandSubstitutionPart).legacy)
    }

    // =========================================================================
    // ARITHMETIC EXPANSION
    // =========================================================================

    @Test
    fun `arithmetic expansion`() {
        val script = parseScript("echo \$((1 + 2))")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        val part = cmd.args[0].parts[0]
        assertTrue(part is ArithmeticExpansionPart)
    }

    @Test
    fun `arithmetic command`() {
        val script = parseScript("((a = 1 + 2))")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is ArithmeticCommandNode)
    }

    // =========================================================================
    // PIPELINES
    // =========================================================================

    @Test
    fun `pipeline with two commands`() {
        val script = parseScript("cat file | grep pattern")
        val pipeline = script.statements[0].pipelines[0]
        assertEquals(2, pipeline.commands.size)
        val cmd1 = pipeline.commands[0] as SimpleCommandNode
        val cmd2 = pipeline.commands[1] as SimpleCommandNode
        assertEquals("cat", (cmd1.name?.parts?.get(0) as? LiteralPart)?.value)
        assertEquals("grep", (cmd2.name?.parts?.get(0) as? LiteralPart)?.value)
    }

    @Test
    fun `negated pipeline`() {
        val script = parseScript("! grep pattern file")
        val pipeline = script.statements[0].pipelines[0]
        assertTrue(pipeline.negated)
    }

    @Test
    fun `logical and`() {
        val script = parseScript("true && echo ok")
        val stmt = script.statements[0]
        assertEquals(2, stmt.pipelines.size)
        assertEquals(listOf("&&"), stmt.operators)
    }

    @Test
    fun `logical or`() {
        val script = parseScript("false || echo fallback")
        val stmt = script.statements[0]
        assertEquals(2, stmt.pipelines.size)
        assertEquals(listOf("||"), stmt.operators)
    }

    // =========================================================================
    // IF STATEMENTS
    // =========================================================================

    @Test
    fun `if statement`() {
        val script = parseScript("if true; then echo yes; fi")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is IfNode)
        val ifNode = cmd as IfNode
        assertEquals(1, ifNode.clauses.size)
        assertEquals(1, ifNode.clauses[0].body.size)
    }

    @Test
    fun `if else statement`() {
        val script = parseScript("if false; then echo no; else echo yes; fi")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is IfNode)
        val ifNode = cmd as IfNode
        assertNotNull(ifNode.elseBody)
        assertEquals(1, ifNode.elseBody!!.size)
    }

    @Test
    fun `if elif statement`() {
        val script = parseScript("if false; then echo no; elif true; then echo maybe; fi")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is IfNode)
        val ifNode = cmd as IfNode
        assertEquals(2, ifNode.clauses.size)
    }

    // =========================================================================
    // FOR LOOPS
    // =========================================================================

    @Test
    fun `for loop`() {
        val script = parseScript("for i in 1 2 3; do echo \$i; done")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is ForNode)
        val forNode = cmd as ForNode
        assertEquals("i", forNode.variable)
        assertEquals(3, forNode.words?.size)
    }

    @Test
    fun `for loop without in`() {
        val script = parseScript("for i; do echo \$i; done")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is ForNode)
        val forNode = cmd as ForNode
        assertEquals("i", forNode.variable)
        assertNull(forNode.words)
    }

    // =========================================================================
    // WHILE & UNTIL
    // =========================================================================

    @Test
    fun `while loop`() {
        val script = parseScript("while true; do echo hello; done")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is WhileNode)
    }

    @Test
    fun `until loop`() {
        val script = parseScript("until false; do echo hello; done")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is UntilNode)
    }

    // =========================================================================
    // CASE STATEMENTS
    // =========================================================================

    @Test
    fun `case statement`() {
        val script = parseScript("case \$x in a) echo match;; b) echo other;; esac")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is CaseNode)
        val caseNode = cmd as CaseNode
        assertEquals(2, caseNode.items.size)
    }

    // =========================================================================
    // FUNCTIONS
    // =========================================================================

    @Test
    fun `function definition`() {
        val script = parseScript("myfunc() { echo hello; }")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is FunctionDefNode)
        assertEquals("myfunc", (cmd as FunctionDefNode).name)
    }

    @Test
    fun `function with keyword`() {
        val script = parseScript("function myfunc { echo hello; }")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is FunctionDefNode)
        assertEquals("myfunc", (cmd as FunctionDefNode).name)
    }

    // =========================================================================
    // REDIRECTIONS
    // =========================================================================

    @Test
    fun `output redirection`() {
        val script = parseScript("echo hello > file.txt")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        assertEquals(1, cmd.redirections.size)
        assertEquals(">", cmd.redirections[0].operator)
    }

    @Test
    fun `input redirection`() {
        val script = parseScript("cat < file.txt")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        assertEquals(1, cmd.redirections.size)
        assertEquals("<", cmd.redirections[0].operator)
    }

    @Test
    fun `append redirection`() {
        val script = parseScript("echo hello >> file.txt")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        assertEquals(1, cmd.redirections.size)
        assertEquals(">>", cmd.redirections[0].operator)
    }

    @Test
    fun `fd redirection`() {
        val script = parseScript("cmd 2>&1")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        assertEquals(1, cmd.redirections.size)
        assertEquals(2, cmd.redirections[0].fd)
        assertEquals(">&", cmd.redirections[0].operator)
    }

    // =========================================================================
    // HEREDOCS
    // =========================================================================

    @Test
    fun `heredoc`() {
        val script = parseScript("cat << EOF\nhello\nEOF\n")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        assertEquals(1, cmd.redirections.size)
        val target = cmd.redirections[0].target
        assertTrue(target is HereDocNode)
        assertEquals("EOF", (target as HereDocNode).delimiter)
    }

    @Test
    fun `heredoc with strip tabs`() {
        val script = parseScript("cat <<- EOF\n\thello\nEOF\n")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        assertEquals(1, cmd.redirections.size)
        val target = cmd.redirections[0].target
        assertTrue(target is HereDocNode)
        assertTrue((target as HereDocNode).stripTabs)
    }

    // =========================================================================
    // SUBSHELL & GROUP
    // =========================================================================

    @Test
    fun `subshell`() {
        val script = parseScript("(echo hello)")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is SubshellNode)
    }

    @Test
    fun `group command`() {
        val script = parseScript("{ echo hello; }")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is GroupNode)
    }

    // =========================================================================
    // CONDITIONAL COMMANDS
    // =========================================================================

    @Test
    fun `conditional command`() {
        val script = parseScript("[[ -f file.txt ]]")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is ConditionalCommandNode)
    }

    @Test
    fun `conditional binary`() {
        val script = parseScript("[[ a == b ]]")
        val cmd = script.statements[0].pipelines[0].commands[0]
        assertTrue(cmd is ConditionalCommandNode)
        val expr = (cmd as ConditionalCommandNode).expression
        assertTrue(expr is CondBinaryNode)
        assertEquals("==", (expr as CondBinaryNode).operator)
    }

    // =========================================================================
    // ASSIGNMENTS
    // =========================================================================

    @Test
    fun `simple assignment`() {
        val script = parseScript("FOO=bar")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        assertEquals(1, cmd.assignments.size)
        assertEquals("FOO", cmd.assignments[0].name)
        assertNull(cmd.name)
    }

    @Test
    fun `append assignment`() {
        val script = parseScript("FOO+=bar")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        assertEquals(1, cmd.assignments.size)
        assertTrue(cmd.assignments[0].append)
    }

    @Test
    fun `array assignment`() {
        val script = parseScript("arr=(a b c)")
        val cmd = script.statements[0].pipelines[0].commands[0] as SimpleCommandNode
        assertEquals(1, cmd.assignments.size)
        assertEquals(3, cmd.assignments[0].array?.size)
    }

    // =========================================================================
    // COMPLEX SCRIPTS
    // =========================================================================

    @Test
    fun `multiple statements`() {
        val script = parseScript("echo one; echo two")
        assertEquals(2, script.statements.size)
    }

    @Test
    fun `multi-line script`() {
        val script = parseScript("echo one\necho two\n")
        assertEquals(2, script.statements.size)
    }

    @Test
    fun `pipeline with pipe stderr`() {
        val script = parseScript("cmd1 |& cmd2")
        val pipeline = script.statements[0].pipelines[0]
        assertEquals(2, pipeline.commands.size)
        assertTrue(pipeline.pipeStderr?.get(0) ?: false)
    }

    @Test
    fun `comments are ignored`() {
        val script = parseScript("# this is a comment\necho hello")
        assertEquals(1, script.statements.size)
    }
}