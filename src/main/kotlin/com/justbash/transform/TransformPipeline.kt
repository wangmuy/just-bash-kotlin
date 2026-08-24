package com.justbash.transform

import com.justbash.ast.ScriptNode
import com.justbash.parser.MAX_INPUT_SIZE
import com.justbash.parser.parse

/**
 * Transform pipeline types and runner.
 *
 * Port of just-bash `src/transform/types.ts` and `src/transform/pipeline.ts`.
 */

/** A transform plugin operating on a [TransformContext]. */
interface TransformPlugin {
    val name: String
    val transform: (context: TransformContext) -> TransformResult
}

/** Context passed to each plugin's [TransformPlugin.transform]. */
data class TransformContext(
    val ast: ScriptNode,
    val metadata: MutableMap<String, Any?>,
)

/** Result returned by a plugin transform. */
data class TransformResult(
    val ast: ScriptNode,
    val metadata: MutableMap<String, Any?>? = null,
)

/** Aggregated result of a full pipeline transform. */
data class BashTransformResult(
    val script: String,
    val ast: ScriptNode,
    val metadata: MutableMap<String, Any?>,
)

/**
 * A pipeline of [TransformPlugin]s that parses a script, runs each plugin in
 * order, and serializes the transformed AST back to a script string.
 */
class BashTransformPipeline(
    private val maxSourceBytes: Int = MAX_INPUT_SIZE,
) {
    private val plugins = mutableListOf<TransformPlugin>()

    init {
        require(maxSourceBytes >= 0) { "BashTransformPipeline: invalid maxSourceBytes" }
    }

    /** Register a plugin. Returns `this` for chaining. */
    fun use(plugin: TransformPlugin): BashTransformPipeline {
        plugins.add(plugin)
        return this
    }

    /** Parse [script], run all plugins, and serialize the result. */
    fun transform(script: String): BashTransformResult {
        if (script.length > maxSourceBytes) {
            throw IllegalArgumentException("Source exceeds maximum size limit ($maxSourceBytes bytes)")
        }
        var ast = parse(script)
        val metadata = mutableMapOf<String, Any?>()
        for (plugin in plugins) {
            val result = plugin.transform(TransformContext(ast, metadata))
            ast = result.ast
            result.metadata?.let { m -> metadata.putAll(m) }
        }
        return BashTransformResult(
            script = Serializer.serialize(ast),
            ast = ast,
            metadata = metadata,
        )
    }
}
