/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

// This file was automatically generated from coroutines-guide-reactive.md by Knit tool. Do not edit.
package kotlinx.coroutines.rx2.guide.operators01

import kotlinx.coroutines.*
import kotlinx.coroutines.reactive.*
import kotlin.coroutines.CoroutineContext

fun range(context: CoroutineContext, start: Int, count: Int) = publish<Int>(context) {
    for (x in start until start + count) send(x)
}

fun main(args: Array<String>) = runBlocking<Unit> {
    range(CommonPool, 1, 5).consumeEach { println(it) }
}
