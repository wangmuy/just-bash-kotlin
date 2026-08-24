package com.justbash.interpreter.expansion

import com.justbash.encoding.Encoding

fun cloneArray(array: ShellArray): ShellArray = ShellArray(array.kind, LinkedHashMap(array.elements))
fun cloneArrays(arrays: MutableMap<String, ShellArray>): MutableMap<String, ShellArray> { val out = LinkedHashMap<String, ShellArray>(); for ((n,a) in arrays) out[n] = cloneArray(a); return out }
fun getArray(ctx: ExpansionContext, arrayName: String): ShellArray? = ctx.state.arrays[arrayName]
fun ensureArray(ctx: ExpansionContext, arrayName: String, kind: String = "indexed"): ShellArray { var a = ctx.state.arrays[arrayName]; if (a == null) { a = ShellArray(kind, LinkedHashMap()); ctx.state.arrays[arrayName] = a }; return a }
fun setArrayKind(ctx: ExpansionContext, arrayName: String, kind: String): ShellArray { val a = ensureArray(ctx, arrayName, kind); a.kind = kind; return a }
fun hasArray(ctx: ExpansionContext, arrayName: String): Boolean = ctx.state.arrays.containsKey(arrayName)
fun getArrayElement(ctx: ExpansionContext, arrayName: String, key: Any): String? = ctx.state.arrays[arrayName]?.elements?.get(key.toString())
fun hasArrayElement(ctx: ExpansionContext, arrayName: String, key: Any): Boolean = ctx.state.arrays[arrayName]?.elements?.containsKey(key.toString()) ?: false
fun setArrayElement(ctx: ExpansionContext, arrayName: String, key: Any, value: String, kind: String? = null) { val a = ensureArray(ctx, arrayName, kind ?: if (ctx.state.associativeArrays.contains(arrayName)) "associative" else "indexed"); val nk = key.toString(); if (!a.elements.containsKey(nk) && a.elements.size >= ctx.limits.maxArrayElements) throw ExecutionLimitError("array element limit exceeded (${ctx.limits.maxArrayElements})", "array_elements"); if (Encoding.utf8ByteLength(value) > ctx.limits.maxStringLength) throw ExecutionLimitError("array value string limit exceeded (${ctx.limits.maxStringLength} bytes)", "string_length"); a.elements[nk] = value }
fun deleteArrayElement(ctx: ExpansionContext, arrayName: String, key: Any): Boolean = ctx.state.arrays[arrayName]?.elements?.remove(key.toString()) != null
fun deleteArray(ctx: ExpansionContext, arrayName: String) { ctx.state.arrays.remove(arrayName); ctx.state.associativeArrays.remove(arrayName) }
fun getArrayIndices(ctx: ExpansionContext, arrayName: String): List<Int> { val idx = ctx.state.arrays[arrayName]?.elements?.keys?.filter { it.matches(Regex("^(0|[1-9]\\d*)$")) }?.map { it.toInt() } ?: emptyList(); return idx.sorted() }
fun getAssocArrayKeys(ctx: ExpansionContext, arrayName: String): List<String> = (ctx.state.arrays[arrayName]?.elements?.keys ?: emptyList()).sorted()
fun unquoteKey(key: String): String { if ((key.startsWith("'") && key.endsWith("'")) || (key.startsWith("\"") && key.endsWith("\""))) return key.substring(1, key.length - 1); return key }
fun getArrayElements(ctx: ExpansionContext, arrayName: String): List<Pair<Any, String>> {
    when (arrayName) { "FUNCNAME" -> return ctx.state.funcNameStack.mapIndexed { i, n -> i as Any to n }; "BASH_LINENO" -> return ctx.state.callLineStack.mapIndexed { i, n -> i as Any to n.toString() }; "BASH_SOURCE" -> return ctx.state.sourceStack.mapIndexed { i, s -> i as Any to s } }
    val isAssoc = ctx.state.associativeArrays.contains(arrayName)
    if (isAssoc) return getAssocArrayKeys(ctx, arrayName).map { key -> key as Any to (getArrayElement(ctx, arrayName, key) ?: "") }
    return getArrayIndices(ctx, arrayName).map { index -> index as Any to (getArrayElement(ctx, arrayName, index) ?: "") }
}
fun isArray(ctx: ExpansionContext, name: String): Boolean { when (name) { "FUNCNAME" -> return ctx.state.funcNameStack.isNotEmpty(); "BASH_LINENO" -> return ctx.state.callLineStack.isNotEmpty(); "BASH_SOURCE" -> return ctx.state.sourceStack.isNotEmpty() }; return hasArray(ctx, name) }
