package com.justbash.ast

/**
 * Abstract Syntax Tree (AST) types for Bash.
 *
 * Port of just-bash `src/ast/types.ts`. Uses a sealed class hierarchy instead
 * of TypeScript discriminated unions. Each node carries an optional [line]
 * (1-based source line) for `$LINENO` tracking.
 */

sealed class AstNode(typeStr: String, var line: Int = 0) {
    open val type: String = typeStr
}

// ===========================================================================
// SCRIPT & STATEMENTS
// ===========================================================================

class ScriptNode(val statements: MutableList<StatementNode>) : AstNode("Script")

class DeferredError(val message: String, val token: String)

class StatementNode(
    val pipelines: MutableList<PipelineNode>,
    val operators: MutableList<String> = mutableListOf(), // "&&" | "||" | ";"
    val background: Boolean = false,
    var deferredError: DeferredError? = null,
    var sourceText: String? = null,
) : AstNode("Statement")

class PipelineNode(
    val commands: MutableList<CommandNode>,
    val negated: Boolean = false,
    val timed: Boolean = false,
    val timePosix: Boolean = false,
    val pipeStderr: MutableList<Boolean>? = null,
) : AstNode("Pipeline")

sealed class CommandNode : AstNode("Command")

class SimpleCommandNode(
    val assignments: MutableList<AssignmentNode> = mutableListOf(),
    var name: WordNode? = null,
    val args: MutableList<WordNode> = mutableListOf(),
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
) : CommandNode() {
    init { /* type = "SimpleCommand" via override below */ }
    override val type: String get() = "SimpleCommand"
}

sealed class CompoundCommandNode : CommandNode()

class IfNode(
    val clauses: MutableList<IfClause> = mutableListOf(),
    var elseBody: MutableList<StatementNode>? = null,
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
) : CompoundCommandNode() {
    override val type: String get() = "If"
}

data class IfClause(
    val condition: MutableList<StatementNode>,
    val body: MutableList<StatementNode>,
)

class ForNode(
    val variable: String,
    val words: MutableList<WordNode>?,
    val body: MutableList<StatementNode>,
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
) : CompoundCommandNode() {
    override val type: String get() = "For"
}

class CStyleForNode(
    val init: ArithmeticExpressionNode?,
    val condition: ArithmeticExpressionNode?,
    val update: ArithmeticExpressionNode?,
    val body: MutableList<StatementNode>,
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
) : CompoundCommandNode() {
    override val type: String get() = "CStyleFor"
}

class WhileNode(
    val condition: MutableList<StatementNode>,
    val body: MutableList<StatementNode>,
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
) : CompoundCommandNode() {
    override val type: String get() = "While"
}

class UntilNode(
    val condition: MutableList<StatementNode>,
    val body: MutableList<StatementNode>,
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
) : CompoundCommandNode() {
    override val type: String get() = "Until"
}

class CaseNode(
    val word: WordNode,
    val items: MutableList<CaseItemNode> = mutableListOf(),
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
    var matched: Boolean = false,
) : CompoundCommandNode() {
    override val type: String get() = "Case"
}

class CaseItemNode(
    val patterns: MutableList<WordNode>,
    val body: MutableList<StatementNode>,
    val terminator: String = ";;", // ";;" | ";&" | ";;&"
) : AstNode("CaseItem")

class SubshellNode(
    val body: MutableList<StatementNode>,
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
) : CompoundCommandNode() {
    override val type: String get() = "Subshell"
}

class GroupNode(
    val body: MutableList<StatementNode>,
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
) : CompoundCommandNode() {
    override val type: String get() = "Group"
}

class ArithmeticCommandNode(
    val expression: ArithmeticExpressionNode,
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
) : CompoundCommandNode() {
    override val type: String get() = "ArithmeticCommand"
}

class ConditionalCommandNode(
    val expression: ConditionalExpressionNode,
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
) : CompoundCommandNode() {
    override val type: String get() = "ConditionalCommand"
}

class FunctionDefNode(
    val name: String,
    val body: CompoundCommandNode,
    val redirections: MutableList<RedirectionNode> = mutableListOf(),
    val sourceFile: String? = null,
) : CommandNode() {
    override val type: String get() = "FunctionDef"
}

// ===========================================================================
// ASSIGNMENTS
// ===========================================================================

class AssignmentNode(
    val name: String,
    var value: WordNode?,
    val append: Boolean = false,
    val array: MutableList<WordNode>? = null,
) : AstNode("Assignment")

// ===========================================================================
// REDIRECTIONS
// ===========================================================================

class RedirectionNode(
    val fd: Int?,
    val operator: String,
    var target: Any, // WordNode | HereDocNode — mutable so heredoc processing can replace placeholder
    val fdVariable: String? = null,
) : AstNode("Redirection")

class HereDocNode(
    val delimiter: String,
    val content: WordNode,
    val stripTabs: Boolean = false,
    val quoted: Boolean = false,
    val terminated: Boolean? = null,
) : AstNode("HereDoc")

// ===========================================================================
// WORDS
// ===========================================================================

class WordNode(val parts: MutableList<WordPart> = mutableListOf()) : AstNode("Word") {
    constructor(part: WordPart) : this(mutableListOf(part))
}

sealed class WordPart : AstNode("Part")

class LiteralPart(val value: String) : WordPart() {
    override val type: String get() = "Literal"
}

class SingleQuotedPart(val value: String) : WordPart() {
    override val type: String get() = "SingleQuoted"
}

class DoubleQuotedPart(val parts: MutableList<WordPart>) : WordPart() {
    override val type: String get() = "DoubleQuoted"
}

class EscapedPart(val value: String) : WordPart() {
    override val type: String get() = "Escaped"
}

class ParameterExpansionPart(
    val parameter: String,
    val operation: ParameterOperation? = null,
) : WordPart() {
    override val type: String get() = "ParameterExpansion"
}

class CommandSubstitutionPart(
    val body: ScriptNode,
    val legacy: Boolean = false,
) : WordPart() {
    override val type: String get() = "CommandSubstitution"
}

class ArithmeticExpansionPart(val expression: ArithmeticExpressionNode) : WordPart() {
    override val type: String get() = "ArithmeticExpansion"
}

class ProcessSubstitutionPart(
    val body: ScriptNode,
    val direction: String, // "input" | "output"
) : WordPart() {
    override val type: String get() = "ProcessSubstitution"
}

class BraceExpansionPart(val items: MutableList<BraceItem> = mutableListOf()) : WordPart() {
    override val type: String get() = "BraceExpansion"
}

class TildeExpansionPart(val user: String?) : WordPart() {
    override val type: String get() = "TildeExpansion"
}

class GlobPart(val pattern: String) : WordPart() {
    override val type: String get() = "Glob"
}

sealed class BraceItem
class BraceWordItem(val word: WordNode) : BraceItem()
data class BraceRangeItem(
    val start: Any, // String | Int
    val end: Any,   // String | Int
    val step: Int? = null,
    val startStr: String? = null,
    val endStr: String? = null,
) : BraceItem()

// ===========================================================================
// PARAMETER EXPANSION OPERATIONS
// ===========================================================================

sealed class ParameterOperation(val type: String)

class LengthSliceErrorOp : ParameterOperation("LengthSliceError")
class BadSubstitutionOp(val text: String) : ParameterOperation("BadSubstitution")
class DefaultValueOp(val word: WordNode, val checkEmpty: Boolean) : ParameterOperation("DefaultValue")
class AssignDefaultOp(val word: WordNode, val checkEmpty: Boolean) : ParameterOperation("AssignDefault")
class ErrorIfUnsetOp(val word: WordNode?, val checkEmpty: Boolean) : ParameterOperation("ErrorIfUnset")
class UseAlternativeOp(val word: WordNode, val checkEmpty: Boolean) : ParameterOperation("UseAlternative")
class LengthOp : ParameterOperation("Length")
class SubstringOp(val offset: ArithmeticExpressionNode, val length: ArithmeticExpressionNode?) : ParameterOperation("Substring")
class PatternRemovalOp(val pattern: WordNode, val side: String, val greedy: Boolean) : ParameterOperation("PatternRemoval")
class PatternReplacementOp(val pattern: WordNode, val replacement: WordNode?, val all: Boolean, val anchor: String?) : ParameterOperation("PatternReplacement")
class CaseModificationOp(val direction: String, val all: Boolean, val pattern: WordNode?) : ParameterOperation("CaseModification")
class TransformOp(val operator: String) : ParameterOperation("Transform")
class IndirectionOp(val innerOp: ParameterOperation? = null) : ParameterOperation("Indirection")
class ArrayKeysOp(val array: String, val star: Boolean) : ParameterOperation("ArrayKeys")
class VarNamePrefixOp(val prefix: String, val star: Boolean) : ParameterOperation("VarNamePrefix")

// ===========================================================================
// ARITHMETIC
// ===========================================================================

class ArithmeticExpressionNode(
    val expression: ArithExpr,
    val originalText: String? = null,
) : AstNode("ArithmeticExpression")

sealed class ArithExpr(val type: String)

class ArithBracedExpansionNode(val content: String) : ArithExpr("ArithBracedExpansion")
class ArithDynamicBaseNode(val baseExpr: String, val value: String) : ArithExpr("ArithDynamicBase")
class ArithDynamicNumberNode(val prefix: String, val suffix: String) : ArithExpr("ArithDynamicNumber")
class ArithConcatNode(val parts: MutableList<ArithExpr>) : ArithExpr("ArithConcat")
class ArithArrayElementNode(val array: String, val index: ArithExpr? = null, val stringKey: String? = null) : ArithExpr("ArithArrayElement")
class ArithDoubleSubscriptNode(val array: String, val index: ArithExpr) : ArithExpr("ArithDoubleSubscript")
class ArithNumberSubscriptNode(val number: String, val errorToken: String) : ArithExpr("ArithNumberSubscript")
class ArithSyntaxErrorNode(val errorToken: String, val message: String) : ArithExpr("ArithSyntaxError")
class ArithSingleQuoteNode(val content: String, val value: Long) : ArithExpr("ArithSingleQuote")
class ArithNumberNode(val value: Long) : ArithExpr("ArithNumber")
class ArithVariableNode(val name: String, val hasDollarPrefix: Boolean = false) : ArithExpr("ArithVariable")
class ArithSpecialVarNode(val name: String) : ArithExpr("ArithSpecialVar")
class ArithBinaryNode(val operator: String, val left: ArithExpr, val right: ArithExpr) : ArithExpr("ArithBinary")
class ArithUnaryNode(val operator: String, val operand: ArithExpr, val prefix: Boolean) : ArithExpr("ArithUnary")
class ArithTernaryNode(val condition: ArithExpr, val consequent: ArithExpr, val alternate: ArithExpr) : ArithExpr("ArithTernary")
class ArithAssignmentNode(val operator: String, val variable: String, val value: ArithExpr, val subscript: ArithExpr? = null, val stringKey: String? = null) : ArithExpr("ArithAssignment")
class ArithDynamicAssignmentNode(val operator: String, val target: ArithExpr, val value: ArithExpr, val subscript: ArithExpr? = null) : ArithExpr("ArithDynamicAssignment")
class ArithDynamicElementNode(val nameExpr: ArithExpr, val subscript: ArithExpr) : ArithExpr("ArithDynamicElement")
class ArithGroupNode(val expression: ArithExpr) : ArithExpr("ArithGroup")
class ArithNestedNode(val expression: ArithExpr) : ArithExpr("ArithNested")
class ArithCommandSubstNode(val command: String) : ArithExpr("ArithCommandSubst")

// ===========================================================================
// CONDITIONAL EXPRESSIONS ([[ ]])
// ===========================================================================

sealed class ConditionalExpressionNode(val type: String)

class CondBinaryNode(val operator: String, val left: WordNode, val right: WordNode) : ConditionalExpressionNode("CondBinary")
class CondUnaryNode(val operator: String, val operand: WordNode) : ConditionalExpressionNode("CondUnary")
class CondNotNode(val operand: ConditionalExpressionNode) : ConditionalExpressionNode("CondNot")
class CondAndNode(val left: ConditionalExpressionNode, val right: ConditionalExpressionNode) : ConditionalExpressionNode("CondAnd")
class CondOrNode(val left: ConditionalExpressionNode, val right: ConditionalExpressionNode) : ConditionalExpressionNode("CondOr")
class CondGroupNode(val expression: ConditionalExpressionNode) : ConditionalExpressionNode("CondGroup")
class CondWordNode(val word: WordNode) : ConditionalExpressionNode("CondWord")
