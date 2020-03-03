/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines

import kotlinx.coroutines.flow.*
import kotlin.coroutines.*
import kotlin.test.*

class AsyncJvmTest : TestBase() {

    private val emitFun =
        FlowCollector<*>::emit as Function3<FlowCollector<Any?>, Any?, Continuation<Unit>, Any?>


    @Test
    fun testCCe() = runBlocking {
        val flow = flow<Any?> {
            val collector = this
            emitFun.invoke(this, 42, Continuation(EmptyCoroutineContext) { })
        }
        flow.collect()
    }
}
