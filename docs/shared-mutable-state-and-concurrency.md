<!--- TEST_NAME SharedStateGuideTest -->
 
**Table of contents**

<!--- TOC -->

* [Shared mutable state and concurrency](#shared-mutable-state-and-concurrency)
  * [The problem](#the-problem)
  * [Volatiles are of no help](#volatiles-are-of-no-help)
  * [Thread-safe data structures](#thread-safe-data-structures)
  * [Thread confinement fine-grained](#thread-confinement-fine-grained)
  * [Thread confinement coarse-grained](#thread-confinement-coarse-grained)
  * [Mutual exclusion](#mutual-exclusion)
  * [Actors](#actors)

<!--- END -->

## Shared mutable state and concurrency

Coroutines can be executed concurrently using a multi-threaded dispatcher like the [Dispatchers.Default]. It presents
all the usual concurrency problems. The main problem being synchronization of access to **shared mutable state**. 
Some solutions to this problem in the land of coroutines are similar to the solutions in the multi-threaded world, 
but others are unique.

### The problem

Let us launch a hundred coroutines all doing the same action thousand times. 
We'll also measure their completion time for further comparisons:

<div class="sample" markdown="1" theme="idea" data-highlight-only>

```kotlin
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
```

</div> 

We start with a very simple action that increments a shared mutable variable using 
multi-threaded [Dispatchers.Default]. 

<!--- CLEAR -->

<div class="sample" markdown="1" theme="idea" data-min-compiler-version="1.3">

```kotlin
import kotlinx.coroutines.*
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

//sampleStart
var counter = 0

fun main() = runBlocking {
    withContext(Dispatchers.Default) {
        massiveRun {
            counter++
        }
    }
    println("Counter = $counter")
}
//sampleEnd    
```

</div>

> You can get full code [here](../kotlinx-coroutines-core/jvm/test/guide/example-sync-01.kt).

<!--- TEST LINES_START
Completed 100000 actions in
Counter =
-->

What does it print at the end? It is highly unlikely to ever print "Counter = 100000", because a hundred coroutines 
increment the `counter` concurrently from multiple threads without any synchronization.

### Volatiles are of no help

There is common misconception that making a variable `volatile` solves concurrency problem. Let us try it:

<!--- CLEAR -->

<div class="sample" markdown="1" theme="idea" data-min-compiler-version="1.3">

```kotlin
import kotlinx.coroutines.*
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

//sampleStart
@Volatile // in Kotlin `volatile` is an annotation 
var counter = 0

fun main() = runBlocking {
    withContext(Dispatchers.Default) {
        massiveRun {
            counter++
        }
    }
    println("Counter = $counter")
}
//sampleEnd    
```

</div>

> You can get full code [here](../kotlinx-coroutines-core/jvm/test/guide/example-sync-02.kt).

<!--- TEST LINES_START
Completed 100000 actions in
Counter =
-->

This code works slower, but we still don't get "Counter = 100000" at the end, because volatile variables guarantee
linearizable (this is a technical term for "atomic") reads and writes to the corresponding variable, but
do not provide atomicity of larger actions (increment in our case).

### Thread-safe data structures

The general solution that works both for threads and for coroutines is to use a thread-safe (aka synchronized,
linearizable, or atomic) data structure that provides all the necessarily synchronization for the corresponding 
operations that needs to be performed on a shared state. 
In the case of a simple counter we can use `AtomicInteger` class which has atomic `incrementAndGet` operations:

<!--- CLEAR -->

<div class="sample" markdown="1" theme="idea" data-min-compiler-version="1.3">

```kotlin
import kotlinx.coroutines.*
import java.util.concurrent.atomic.*
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

//sampleStart
var counter = AtomicInteger()

fun main() = runBlocking {
    withContext(Dispatchers.Default) {
        massiveRun {
            counter.incrementAndGet()
        }
    }
    println("Counter = $counter")
}
//sampleEnd    
```

</div>

> You can get full code [here](../kotlinx-coroutines-core/jvm/test/guide/example-sync-03.kt).

<!--- TEST ARBITRARY_TIME
Completed 100000 actions in xxx ms
Counter = 100000
-->

This is the fastest solution for this particular problem. It works for plain counters, collections, queues and other
standard data structures and basic operations on them. However, it does not easily scale to complex
state or to complex operations that do not have ready-to-use thread-safe implementations. 

### Thread confinement fine-grained

_Thread confinement_ is an approach to the problem of shared mutable state where all access to the particular shared
state is confined to a single thread. It is typically used in UI applications, where all UI state is confined to 
the single event-dispatch/application thread. It is easy to apply with coroutines by using a  
single-threaded context. 

<!--- CLEAR -->

<div class="sample" markdown="1" theme="idea" data-min-compiler-version="1.3">

```kotlin
import kotlinx.coroutines.*
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

//sampleStart
val counterContext = newSingleThreadContext("CounterContext")
var counter = 0

fun main() = runBlocking {
    withContext(Dispatchers.Default) {
        massiveRun {
            // confine each increment to a single-threaded context
            withContext(counterContext) {
                counter++
            }
        }
    }
    println("Counter = $counter")
}
//sampleEnd      
```

</div>

> You can get full code [here](../kotlinx-coroutines-core/jvm/test/guide/example-sync-04.kt).

<!--- TEST ARBITRARY_TIME
Completed 100000 actions in xxx ms
Counter = 100000
-->

This code works very slowly, because it does _fine-grained_ thread-confinement. Each individual increment switches 
from multi-threaded [Dispatchers.Default] context to the single-threaded context using 
[withContext(counterContext)][withContext] block.

### Thread confinement coarse-grained

In practice, thread confinement is performed in large chunks, e.g. big pieces of state-updating business logic
are confined to the single thread. The following example does it like that, running each coroutine in 
the single-threaded context to start with. 

<!--- CLEAR -->

<div class="sample" markdown="1" theme="idea" data-min-compiler-version="1.3">

```kotlin
import kotlinx.coroutines.*
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

//sampleStart
val counterContext = newSingleThreadContext("CounterContext")
var counter = 0

fun main() = runBlocking {
    // confine everything to a single-threaded context
    withContext(counterContext) {
        massiveRun {
            counter++
        }
    }
    println("Counter = $counter")
}
//sampleEnd     
```

</div>

> You can get full code [here](../kotlinx-coroutines-core/jvm/test/guide/example-sync-05.kt).

<!--- TEST ARBITRARY_TIME
Completed 100000 actions in xxx ms
Counter = 100000
-->

This now works much faster and produces correct result.

### Mutual exclusion

Mutual exclusion solution to the problem is to protect all modifications of the shared state with a _critical section_
that is never executed concurrently. In a blocking world you'd typically use `synchronized` or `ReentrantLock` for that.
Coroutine's alternative is called [Mutex]. It has [lock][Mutex.lock] and [unlock][Mutex.unlock] functions to 
delimit a critical section. The key difference is that `Mutex.lock()` is a suspending function. It does not block a thread.

There is also [withLock] extension function that conveniently represents 
`mutex.lock(); try { ... } finally { mutex.unlock() }` pattern: 

<!--- CLEAR -->

<div class="sample" markdown="1" theme="idea" data-min-compiler-version="1.3">

```kotlin
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
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

//sampleStart
val mutex = Mutex()
var counter = 0

fun main() = runBlocking {
    withContext(Dispatchers.Default) {
        massiveRun {
            // protect each increment with lock
            mutex.withLock {
                counter++
            }
        }
    }
    println("Counter = $counter")
}
//sampleEnd    
```

</div>

> You can get full code [here](../kotlinx-coroutines-core/jvm/test/guide/example-sync-06.kt).

<!--- TEST ARBITRARY_TIME
Completed 100000 actions in xxx ms
Counter = 100000
-->

The locking in this example is fine-grained, so it pays the price. However, it is a good choice for some situations
where you absolutely must modify some shared state periodically, but there is no natural thread that this state
is confined to.

### Actors

An [actor](https://en.wikipedia.org/wiki/Actor_model) is a way of preserving mutable data by using messages.
The state of the actor is stored in a private field, and is only accessible from a specific coroutine (or set thereof).

The first step to using an actor is to create the class that the state is encapsulated in. This class can be created
either through delegation or through derivation. Generally, there is less boilerplate and simpler code when using
derivation, but it is incompatible when you are subclassing another class, as classes can only have one superclass.

To create this class through derivation, simply extend BaseActor and use the `act` function for any state-private data.
For example:


<div class="sample" markdown="1" theme="idea" data-highlight-only>

<!--- INCLUDE
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.system.*
-->

```kotlin
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

//sampleStart
class CounterActor(scope: CoroutineScope) : BaseActor(scope) {
    private var counter = 0

    suspend fun increment(): Int = act {
        counter++
    }

    suspend fun getCounter(): In t = act {
        counter
    }
}
//sampleEnd
```

</div>

Or to create a delegating Actor through delegation:
<div class="sample" markdown="1" theme="idea" data-highlight-only>

```kotlin
open class MySuperClass

//sampleStart
class DelegatingCounterActor(scope: CoroutineScope) : MySuperClass() {
    private val act = ActorImpl(scope)
    private var counter = 0

    suspend fun increment(): Int = act {
        counter++
    }

    suspend fun getCounter(): Int = act {
        counter
    }

    suspend fun cancel() = act.cancel()
}
//sampleEnd
```

</div>

Using these actors is as simple as invoking the functions on them. By default, they are initialized lazily, so the
underlying coroutines will not be started until the first invocation. If the underlying coroutines were started,
it is essential to close the actor, otherwise the parent scope will hang forever.

Implementing our above example using this pattern, we get:

<div class="sample" markdown="1" theme="idea" data-highlight-only>

```kotlin
//sampleStart
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
//sampleEnd
```

</div>

> You can get full code [here](../kotlinx-coroutines-core/jvm/test/guide/example-sync-07.kt).

<!--- TEST ARBITRARY_TIME
Completed 100000 actions in xxx ms
Counter = 100000
-->

It does not matter (for correctness) what context the actor itself is executed in. An actor is
a coroutine and a coroutine is executed sequentially, so confinement of the state to the specific coroutine
works as a solution to the problem of shared mutable state. Indeed, actors may modify their own private state, 
but can only affect each other through messages (avoiding the need for any locks).

Actor is more efficient than locking under load, because in this case it always has work to do and it does not 
have to switch to a different context at all.

<!--- MODULE kotlinx-coroutines-core -->
<!--- INDEX kotlinx.coroutines -->
[Dispatchers.Default]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-default.html
[withContext]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html
<!--- INDEX kotlinx.coroutines.sync -->
[Mutex]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/index.html
[Mutex.lock]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/lock.html
[Mutex.unlock]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/unlock.html
[withLock]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/with-lock.html
<!--- INDEX kotlinx.coroutines.channels -->
<!--- END -->
