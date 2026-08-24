package com.justbash.interpreter

import com.justbash.encoding.Encoding

const val MAX_ARRAY_ELEMENTS = 1024

object Arrays {
    fun cloneArray(array: ShellArray): ShellArray = ShellArray(array.kind, LinkedHashMap(array.elements))

    fun getArray(ctx: InterpreterContext, name: String): ShellArray? = ctx.state.arrays?.get(name)

    fun ensureArray(ctx: InterpreterContext, name: String, kind: String = "indexed"): ShellArray {
        var arrays = ctx.state.arrays
        if (arrays == null) { arrays = LinkedHashMap(); ctx.state.arrays = arrays }
        var array = arrays[name]
        if (array == null) { array = ShellArray(kind); arrays[name] = array }
        return array
    }

    fun setArrayKind(ctx: InterpreterContext, name: String, kind: String): ShellArray {
        val array = ensureArray(ctx, name, kind); array.kind = kind; return array
    }

    fun getArrayElement(ctx: InterpreterContext, name: String, key: Any): String? =
        getArray(ctx, name)?.elements?.get(key.toString())

    fun setArrayElement(ctx: InterpreterContext, name: String, key: Any, value: String, kind: String? = null) {
        val array = ensureArray(ctx, name, kind ?: "indexed")
        val k = key.toString()
        if (!array.elements.containsKey(k) && array.elements.size >= MAX_ARRAY_ELEMENTS) {
            throw ExecutionLimitError("array element limit exceeded ($MAX_ARRAY_ELEMENTS)", "array_elements")
        }
        if (Encoding.utf8ByteLength(value).toLong() > ctx.limits.maxStringLength) {
            throw ExecutionLimitError("array value string limit exceeded (${ctx.limits.maxStringLength} bytes)", "string_length")
        }
        array.elements[k] = value
    }

    fun deleteArrayElement(ctx: InterpreterContext, name: String, key: Any): Boolean =
        getArray(ctx, name)?.elements?.remove(key.toString()) != null

    fun deleteArray(ctx: InterpreterContext, name: String) {
        ctx.state.arrays?.remove(name); ctx.state.associativeArrays?.remove(name)
    }

    fun getArrayIndices(ctx: InterpreterContext, name: String): List<Int> =
        (getArray(ctx, name)?.elements?.keys ?: emptyList())
            .filter { it.matches(Regex("^(0|[1-9][0-9]*)$")) }.map { it.toInt() }.sorted()

    fun clearArray(ctx: InterpreterContext, name: String) {
        val kind = if (ctx.state.associativeArrays?.contains(name) == true) "associative" else "indexed"
        ensureArray(ctx, name, kind).elements.clear()
    }

    fun getAssocArrayKeys(ctx: InterpreterContext, name: String): List<String> =
        (getArray(ctx, name)?.elements?.keys ?: emptyList()).sorted()
}
