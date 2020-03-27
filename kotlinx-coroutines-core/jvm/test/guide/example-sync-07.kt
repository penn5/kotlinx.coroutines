/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

// This file was automatically generated from shared-mutable-state-and-concurrency.md by Knit tool. Do not edit.
package kotlinx.coroutines.guide.exampleSync07

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.system.*

suspend fun massiveRun(action: suspend () -> Unit) {
    val n = 100  // number of coroutines to launch
    val k = 1000 // times an action is repeated by each coroutine
    val time = measureTimeMillis {
        coroutineScope { // scope for coroutines 
            repeat(n) {
                launch {
                    repeat(k) { action() }
                }
            }
        }
    }
    println("Completed ${n * k} actions in $time ms")    
}

class CounterActor(scope: CoroutineScope) : BaseActor(scope) {
    private var counter = 0

    suspend fun increment(): Unit = act {
        counter++
    }

    suspend fun getCounter(): Int = act {
        counter
    }
}

open class MySuperClass

class DelegatingCounterActor(scope: CoroutineScope) : MySuperClass() {
    private val act = ActorImpl(scope)
    private var counter = 0

    suspend fun increment(): Unit = act {
        counter++
    }

    suspend fun getCounter(): Int = act {
        counter
    }

    suspend fun cancel() = act.cancel()
}

fun main() = runBlocking {
    withContext(Dispatchers.Default) {
        val counter = CounterActor(this)
        massiveRun {
            counter.increment()
        }
        println("Counter = ${counter.getCounter()}")
        counter.cancel()
    }
}
