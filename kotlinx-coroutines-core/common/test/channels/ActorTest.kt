/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.channels

import kotlinx.coroutines.*
import kotlin.test.*


class ActorTest : TestBase() {
    @Test
    fun testOne() = runTest {
        var invocations = 0
        class SingleActor(scope: CoroutineScope) : BaseActor(scope) {
            suspend fun testOne(input: Int): Int = act {
                invocations += 1
                assertEquals(3, input)
                7
            }
        }
        val singleActor = SingleActor(this)
        assertEquals(7, singleActor.testOne(3))
        singleActor.cancel()
    }

    @Test
    fun testOneDelegated() = runTest {
        var invocations = 0
        println("testing delegated start")
        class SingleActor {
            val act = ActorImpl(this@runTest)

            suspend fun testOneDelegated(input: Int): Int = act {
                invocations += 1
                assertEquals(3, input)
                7
            }

            suspend fun cancel() = act.cancel()
        }
        println("testing delegated")
        val singleActor = SingleActor()
        assertEquals(7, singleActor.testOneDelegated(3))
        singleActor.cancel()
        println("delegated done")
    }

    @Test
    fun testLots() = runTest {
        class CounterActor(scope: CoroutineScope) : BaseActor(scope) {
            var counter = 0

            suspend fun increment(): Int = act {
                ++counter
            }
        }
        val counter = CounterActor(this)
        var last = 0
        var next: Int
        repeat(100) {
            next = counter.increment()
            assertEquals(last + 1, next) // assert that it is sequential
            last = next
        }
        assertEquals(100, counter.counter)
        counter.cancel()
    }

    @Test
    fun testLotsQuickly() = runTest {
        class CounterActor(scope: CoroutineScope) : BaseActor(scope, concurrency = 1) {
            var counter = 0

            suspend fun increment() = act {
                counter++
            }
        }

        val counter = CounterActor(this + Dispatchers.Default)
        coroutineScope {
            repeat(1000000) {
                launch {
                    counter.increment()
                }
            }
        }

        assertEquals(1000000, counter.counter)
        counter.cancel()
    }

    @Test
    fun testLazyStates() = runTest {
        val actor = ActorImpl(this)
        assertEquals(ActorState.STOPPED, actor.state)
        actor.start()
        assertEquals(ActorState.STARTED, actor.state)
        actor.cancel()
        assertEquals(ActorState.STOPPED, actor.state)
        var count = 0
        actor.act { count += 1 }
        assertEquals(1, count)
        assertEquals(ActorState.STARTED, actor.state)
        actor.cancel()
        assertEquals(0, coroutineContext[Job]?.children?.count()) // cancel() should kill all child jobs
    }

    @Test
    fun testNormalStates() = runTest {
        val actor = ActorImpl(this, CoroutineStart.DEFAULT)
        assertEquals(ActorState.STARTED, actor.state)
        actor.cancel()
        assertEquals(ActorState.STOPPED, actor.state)
        assertFailsWith(IllegalStateException::class, "This Actor is not ready to accept tasks") {
            actor.act<Unit> { fail() }
        }
        actor.start()
        assertEquals(ActorState.STARTED, actor.state)
        var count = 0
        actor.act { count += 1 }
        assertEquals(1, count)
        actor.cancel()
        assertEquals(0, coroutineContext[Job]!!.children.count()) // cancel() should kill all child jobs
    }

    @Test
    fun testBadMutableStateWithMultipleExecutors() = runTest {
        class CounterActor(scope: CoroutineScope) : BaseActor(scope, concurrency = 100) {
            var counter = 0

            suspend fun increment() = act {
                counter++
            }
        }

        val counter = CounterActor(this + Dispatchers.Default)
        coroutineScope {
            repeat(1000000) {
                launch {
                    counter.increment()
                }
            }
        }

        assertNotEquals(1000000, counter.counter)
        counter.cancel()
    }

    @Test
    fun testClosing() = runTest {
        class ClosingActor(scope: CoroutineScope) : BaseActor(scope) {
            var counter = 0
            suspend fun waitAndReturn(): Int = act {
                delay(500)
                ++counter
            }
        }
        val actor = ClosingActor(this)
        val job = async {
            actor.waitAndReturn()
        }
        delay(50)
        val job2 = async {
            actor.waitAndReturn()
        }
        delay(50)
        val stop = launch {
            actor.cancel()
        }
        delay(50)
        assertEquals(ActorState.STOPPING, actor.state)
        assertEquals(1, job.await())
        assertEquals(ActorState.STOPPING, actor.state)
        assertEquals(2, job2.await())
        delay(50)
        assertEquals(ActorState.STOPPED, actor.state)
        assertTrue(stop.isCompleted)
        assertTrue(coroutineContext[Job]!!.children.none())
    }
}