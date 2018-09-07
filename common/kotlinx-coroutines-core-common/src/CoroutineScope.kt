/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental

import kotlinx.coroutines.experimental.internal.*
import kotlin.coroutines.experimental.*

/**
 *
 * Interface which defines a scope of the coroutines. Every coroutine builder
 * is an extension on [CoroutineScope] and inherits its [coroutineContext][CoroutineScope.coroutineContext]
 * to automatically propagate both context elements and cancellation.
 *
 * [CoroutineScope] should be implemented on entities with well-defined lifecycle which are responsible
 * for launching children coroutines. Example of such entity on Android is Activity.
 * Usage of this interface may look like this:
 *
 * ```
 * class MyActivity : AppCompatActivity(), CoroutineScope {
 *
 * 	   override val coroutineContext: CoroutineContext
 *         get() = job + UI
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         job = Job()
 *     }
 *
 *     override fun onDestroy() {
 *         super.onDestroy()
 *         job.cancel() // Cancel job on activity destroy. After destroy all children jobs will be cancelled automatically
 *     }
 *
 *     /*
 *      * Note how coroutine builders are scoped: if activity is destroyed or any of the launched coroutines
 *      * in this method throws an exception, then all nested coroutines will be cancelled.
 *      */
 *     fun loadDataFromUI() = launch { // <- extension on current activity, launched in CommonPool
 *        val ioData = async(IO) { // <- extension on launch scope, launched in IO dispatcher
 *          // long computation
 *        }
 *
 *        withContext(UI) {
 *            val data = ioData.await()
 *            draw(data)
 *        }
 *     }
 * }
 *
 * ```
 */
public interface CoroutineScope {

    /**
     * Returns `true` when this coroutine is still active (has not completed and was not cancelled yet).
     *
     * Check this property in long-running computation loops to support cancellation:
     * ```
     * while (isActive) {
     *     // do some computation
     * }
     * ```
     *
     * This property is a shortcut for `coroutineContext.isActive` in the scope when
     * [CoroutineScope] is available.
     * See [coroutineContext][kotlin.coroutines.experimental.coroutineContext],
     * [isActive][kotlinx.coroutines.experimental.isActive] and [Job.isActive].
     */
    @Deprecated(message = "Deprecated in the favor of top-level property", replaceWith = ReplaceWith("CoroutineScope._isActive"))
    public val isActive: Boolean
        get() = coroutineContext[Job]?.isActive ?: true

    /**
     * Returns the context of this scope
     **/
    public val coroutineContext: CoroutineContext

    public operator fun plus(contextElement: CoroutineContext.Element): CoroutineScope =
        CoroutineScope(coroutineContext + contextElement)
}

/**
 * Returns `true` when current [Job] is still active (has not completed and was not cancelled yet).
 *
 * Check this property in long-running computation loops to support cancellation:
 * ```
 * while (_isActive) {
 *     // do some computation
 * }
 * ```
 *
 * This property is a shortcut for `coroutineContext.isActive` in the scope when
 * [CoroutineScope] is available.
 * See [coroutineContext][kotlin.coroutines.experimental.coroutineContext],
 * [isActive][kotlinx.coroutines.experimental.isActive] and [Job.isActive].
 *
 * TODO it's a replacement for isActive, but ABI breaking change
 */
public val CoroutineScope._isActive: Boolean get() = coroutineContext[Job]?.isActive ?: true

/**
 * A global [CoroutineScope] not bound to any job.
 *
 * Global scope is used to launch top-level coroutines which are operating on the whole application lifetime
 * and are not cancelled prematurely.
 * Another use of the global scope is [Unconfined] operators, which don't have any job associated with them.
 *
 * Application code usually should use application-defined [CoroutineScope], using [async] or [launch]
 * on the instance of [GlobalScope] is highly discouraged.
 *
 * Usage of this interface may look like this:
 * ```
 * fun ReceiveChannel<Int>.sqrt(): ReceiveChannel<Double> = GlobalScope.produce(Unconfined) {
 *     for (number in this) {
 *         send(Math.sqrt(number))
 *     }
 * }
 *
 * ```
 */
object GlobalScope : CoroutineScope {

    override val isActive: Boolean
        get() = true

    override val coroutineContext: CoroutineContext = EmptyCoroutineContext
}

/**
 * Creates new [CoroutineScope] and calls the specified suspend block with this scope.
 * The provided scope inherits its [coroutineContext][CoroutineScope.coroutineContext] from the outer scope, but overrides
 * context's [Job].
 *
 * This methods returns as soon as given block and all launched from within the scope children coroutines are completed.
 * Example of the scope usages looks like this:
 *
 * ```
 * suspend fun loadDataForUI() = coroutineScope {
 *
 *   val data = async { // <- extension on current scope
 *      ... load some UI data ...
 *   }
 *
 *   withContext(UI) {
 *     doSomeWork()
 *     val result = data.await()
 *     display(result)
 *   }
 * }
 * ```
 *
 * Semantics of the scope in this example:
 * 1) `loadDataForUI` returns as soon as data is loaded and UI is updated
 * 2) If `doSomeWork` throws an exception, then `async` task will be cancelled and `loadDataForUI` will rethrow that exception
 * 3) If outer scope of `loadDataForUI` is cancelled, both started `async` and `withContext` will be cancelled
 *
 * Method may throw [JobCancellationException] if job was cancelled externally or corresponding unhandled [Throwable] if scope has any.
 */
public suspend fun <R> coroutineScope(block: suspend CoroutineScope.() -> R): R {
    val owner = ScopeOwnerCoroutine<R>(coroutineContext)
    owner.start(CoroutineStart.UNDISPATCHED, owner, block)
    owner.join()
    if (owner.isCancelled) {
        throw owner.getCancellationException().let { it.cause ?: it }
    }

    val state = owner.state
    if (state is CompletedExceptionally) {
        throw state.cause
    }

    @Suppress("UNCHECKED_CAST")
    return state as R
}

/**
 * Inherits [CoroutineScope] from one already present in the current [coroutineContext].
 * This method doesn't wait for all launched children to complete (as opposed to [coroutineContext]), but
 * properly setups parent-child relationship.
 *
 * @throws IllegalStateException if current coroutine context doesn't have a [Job] in it
 */
public suspend fun <R> currentScope(block: suspend CoroutineScope.() -> R): R {
    require(coroutineContext[Job] != null) { "Current context doesn't have a job in it: $coroutineContext" }
    return CoroutineScope(coroutineContext).block()
}

/**
 * Creates [CoroutineScope] which wraps given [coroutineContext]
 */
public fun CoroutineScope(context: CoroutineContext): CoroutineScope = ContextScope(context)