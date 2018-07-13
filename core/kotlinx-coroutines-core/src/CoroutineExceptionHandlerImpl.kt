/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines

import java.util.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal actual fun handleCoroutineExceptionImpl(context: CoroutineContext, exception: Throwable) {
    // use additional extension handlers
    ServiceLoader.load(CoroutineExceptionHandler::class.java).forEach { handler ->
        handler.handleException(context, exception)
    }
    // use thread's handler
    val currentThread = Thread.currentThread()
    currentThread.uncaughtExceptionHandler.uncaughtException(currentThread, exception)
}
