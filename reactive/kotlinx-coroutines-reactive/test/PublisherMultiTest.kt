/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.reactive

import kotlinx.coroutines.DefaultDispatcher
import kotlinx.coroutines.TestBase
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.hamcrest.core.IsEqual
import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test emitting multiple values with [publish].
 */
class PublisherMultiTest : TestBase() {
    @Test
    fun testConcurrentStress() = runBlocking<Unit> {
        val n = 10_000 * stressTestMultiplier
        val observable = publish<Int>(DefaultDispatcher) {
            // concurrent emitters (many coroutines)
            val jobs = List(n) {
                // launch
                launch {
                    send(it)
                }
            }
            jobs.forEach { it.join() }
        }
        val resultSet = mutableSetOf<Int>()
        observable.consumeEach {
            assertTrue(resultSet.add(it))
        }
        assertThat(resultSet.size, IsEqual(n))
    }
}
