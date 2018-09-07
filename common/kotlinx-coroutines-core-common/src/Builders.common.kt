/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmMultifileClass
@file:JvmName("BuildersKt")

package kotlinx.coroutines.experimental

import kotlinx.coroutines.experimental.internal.*
import kotlinx.coroutines.experimental.intrinsics.*
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.*

// --------------- basic coroutine builders ---------------

/**
 * TODO replace with and explain why it's deprecated
 */
@Deprecated(message = "Top-level launch is deprecated in favor of extension on CoroutineScope",
    level = DeprecationLevel.WARNING, replaceWith = ReplaceWith("CoroutineScope.launch"))
public fun launch(
    context: CoroutineContext = DefaultDispatcher,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    parent: Job? = null,
    onCompletion: CompletionHandler? = null,
    block: suspend CoroutineScope.() -> Unit
): Job {
    val newContext = newCoroutineContext(context, parent)
    val coroutine = if (start.isLazy)
        LazyStandaloneCoroutine(newContext, block) else
        StandaloneCoroutine(newContext, active = true)
    if (onCompletion != null) coroutine.invokeOnCompletion(handler = onCompletion)
    coroutine.start(start, coroutine, block)
    return coroutine
}


/**
 * Launches new coroutine without blocking current thread and returns a reference to the coroutine as a [Job].
 * The coroutine is cancelled when the resulting job is [cancelled][Job.cancel].
 * Parent of the created coroutine is inherited from the provided [CoroutineScope].
 *
 * By default, the coroutine is immediately scheduled for execution.
 * If the context does not have any dispatcher nor any other [ContinuationInterceptor], then [DefaultDispatcher] is used.
 *
 * Other start options can be specified via `start` parameter. See [CoroutineStart] for details.
 * An optional [start] parameter can be set to [CoroutineStart.LAZY] to start coroutine _lazily_. In this case,
 * the coroutine [Job] is created in _new_ state. It can be explicitly started with [start][Job.start] function
 * and will be started implicitly on the first invocation of [join][Job.join].
 *
 * Uncaught exceptions in this coroutine cancel parent job in the context by default
 * (unless [CoroutineExceptionHandler] is explicitly specified), which means that when `launch` is used with
 * the context of another coroutine, then any uncaught exception leads to the cancellation of parent coroutine.
 *
 * See [newCoroutineContext] for a description of debugging facilities that are available for newly created coroutine.
 *
 * @param context additional to [CoroutineScope.coroutineContext] context of the coroutine
 * @param start coroutine start option. The default value is [CoroutineStart.DEFAULT].
 * @param onCompletion optional completion handler for the coroutine (see [Job.invokeOnCompletion]).
 * @param block the coroutine code which will be invoked in the context of the provided scope
 **/
public fun CoroutineScope.launch(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    onCompletion: CompletionHandler? = null,
    block: suspend CoroutineScope.() -> Unit
): Job {
    val newContext = newContextWithDispatcher(context)
    val coroutine = if (start.isLazy)
        LazyStandaloneCoroutine(newContext, block) else
        StandaloneCoroutine(newContext, active = true)
    if (onCompletion != null) coroutine.invokeOnCompletion(handler = onCompletion)
    coroutine.start(start, coroutine, block)
    return coroutine
}

/**
 * TODO deprecate?
 *
 * Calls the specified suspending block with a given coroutine context, suspends until it completes, and returns
 * the result.
 *
 * This function immediately applies dispatcher from the new context, shifting execution of the block into the
 * different thread inside the block, and back when it completes.
 * The specified [context] is added onto the current coroutine context for the execution of the block.
 *
 * An optional `start` parameter is used only if the specified `context` uses a different [CoroutineDispatcher] than
 * a current one, otherwise it is ignored.
 * By default, the coroutine is immediately scheduled for execution and can be cancelled
 * while it is waiting to be executed and it can be cancelled while the result is scheduled
 * to be processed by the invoker context.
 * Other options can be specified via `start` parameter. See [CoroutineStart] for details.
 * A value of [CoroutineStart.LAZY] is not supported and produces [IllegalArgumentException].
 */
public suspend fun <T> withContext(
    context: CoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend () -> T
): T = suspendCoroutineUninterceptedOrReturn sc@ { uCont ->
    val oldContext = uCont.context
    // fast path #1 if there is no change in the actual context:
    if (context === oldContext || context is CoroutineContext.Element && oldContext[context.key] === context)
        return@sc block.startCoroutineUninterceptedOrReturn(uCont)
    // compute new context
    val newContext = oldContext + context
    // fast path #2 if the result is actually the same
    if (newContext === oldContext)
        return@sc block.startCoroutineUninterceptedOrReturn(uCont)
    // fast path #3 if the new dispatcher is the same as the old one.
    // `equals` is used by design (see equals implementation is wrapper context like ExecutorCoroutineDispatcher)
    if (newContext[ContinuationInterceptor] == oldContext[ContinuationInterceptor]) {
        val newContinuation = RunContinuationUnintercepted(newContext, uCont)
        // There are some other changes in the context, so this thread needs to be updated
        withCoroutineContext(newContext) {
            return@sc block.startCoroutineUninterceptedOrReturn(newContinuation)
        }
    }
    // slowest path otherwise -- use new interceptor, sync to its result via a full-blown instance of RunCompletion
    require(!start.isLazy) { "$start start is not supported" }
    val completion = RunCompletion(
        context = newContext,
        delegate = uCont.intercepted(), // delegate to continuation intercepted with old dispatcher on completion
        resumeMode = if (start == CoroutineStart.ATOMIC) MODE_ATOMIC_DEFAULT else MODE_CANCELLABLE
    )
    completion.initParentJobInternal(newContext[Job]) // attach to job
    start(block, completion)
    completion.getResult()
}

// --------------- implementation ---------------

private open class StandaloneCoroutine(
    private val parentContext: CoroutineContext,
    active: Boolean
) : AbstractCoroutine<Unit>(parentContext, active) {
    override fun hasOnFinishingHandler(update: Any?) = update is CompletedExceptionally

    override fun handleJobException(exception: Throwable) {
        handleCoroutineException(parentContext, exception, this)
    }

    override fun onFinishingInternal(update: Any?) {
        if (update is CompletedExceptionally && update.cause !is CancellationException) {
            parentContext[Job]?.cancel(update.cause)
        }
    }
}

private class LazyStandaloneCoroutine(
    parentContext: CoroutineContext,
    private val block: suspend CoroutineScope.() -> Unit
) : StandaloneCoroutine(parentContext, active = false) {
    override fun onStart() {
        block.startCoroutineCancellable(this, this)
    }
}

private class RunContinuationUnintercepted<in T>(
    override val context: CoroutineContext,
    private val continuation: Continuation<T>
): Continuation<T> {
    override fun resume(value: T) {
        withCoroutineContext(continuation.context) {
            continuation.resume(value)
        }
    }

    override fun resumeWithException(exception: Throwable) {
        withCoroutineContext(continuation.context) {
            continuation.resumeWithException(exception)
        }
    }
}

@Suppress("UNCHECKED_CAST")
private class RunCompletion<in T>(
    override val context: CoroutineContext,
    delegate: Continuation<T>,
    resumeMode: Int
) : AbstractContinuation<T>(delegate, resumeMode) {

    override val useCancellingState: Boolean get() = true
}
