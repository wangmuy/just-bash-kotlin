package com.justbash

/**
 * Custom Commands API — user-registered commands for bash extension.
 *
 * Port of just-bash `src/custom-commands.ts`. Provides:
 * - [defineCommand] — factory for creating a [Command] from a lambda
 * - [LazyCommand] — deferred-loading command (loaded on first execution)
 *
 * @example
 * ```kotlin
 * val hello = defineCommand("hello") { args, ctx ->
 *     ExecResult(stdout = "Hello, ${args.firstOrNull() ?: "world"}!\n")
 * }
 * val bash = BashEnvironment(customCommands = listOf(hello))
 * ```
 */
object CustomCommands {

    /**
     * Factory for creating a [Command] from a lambda.
     * The lambda receives the same `(args, ctx)` signature as [Command.execute].
     */
    fun defineCommand(
        name: String,
        execute: suspend (args: List<String>, ctx: CommandContext) -> ExecResult,
    ): Command = object : Command {
        override val name: String = name
        override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult =
            execute(args, ctx)
    }
}

/**
 * A lazy-loaded custom command. The command's implementation is loaded on first
 * execution — useful for code-splitting scenarios where the command depends on
 * heavy libraries that should not be loaded at construction time.
 *
 * Once loaded, the result is cached; subsequent executions reuse the same
 * instance.
 */
class LazyCommand(
    override val name: String,
    /** Loader: returns the actual [Command] on first call. Called once. */
    private val loader: suspend () -> Command,
) : Command {

    @Volatile
    private var cached: Command? = null

    private var loading: Any? = null // null = not started, Deferred<Command> = in flight

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        val cmd = cached ?: loadOnce()
        return cmd.execute(args, ctx)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun loadOnce(): Command {
        // Double-checked locking with coroutine-safe single-flight
        cached?.let { return it }
        val currentLoading = loading
        if (currentLoading != null) {
            // Another coroutine is already loading; wait for it
            val deferred = currentLoading as kotlinx.coroutines.CompletableDeferred<Command>
            return deferred.await()
        }
        val deferred = kotlinx.coroutines.CompletableDeferred<Command>()
        loading = deferred
        try {
            val cmd = loader()
            cached = cmd
            deferred.complete(cmd)
            return cmd
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            loading = null
            throw e
        }
    }
}