/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.examples

import kotlinx.coroutines.CommonPool
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.asCompletableFuture
import java.util.concurrent.TimeUnit

fun main(args: Array<String>)  {
    log("Started")
    val deferred = async(CommonPool) {
        log("Busy...")
        delay(1, TimeUnit.SECONDS)
        log("Done...")
        42
    }
    val future = deferred.asCompletableFuture()
    log("Got ${future.get()}")
}


