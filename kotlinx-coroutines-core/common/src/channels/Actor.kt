package kotlinx.coroutines.channels

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An Actor executes functions in a set of coroutines.
 */
interface Actor {
    /**
     * The current state of this Actor
     */
    val state: ActorState

    /**
     * Run the provided lambda in the actor's Job
     *
     * @param block the code to be executed in the actor
     * @return the result of [block]
     */
    suspend fun <R>act(block: suspend () -> R): R

    /**
     * Start the actor's jobs
     */
    suspend fun start()

    /**
     * Stop all the actor's jobs
     *
     * If [cause] is null,
     * 1. Stop accepting new jobs
     * 2. Complete execution of the backlog, returning results as usual
     * 3. Cancel all actor jobs
     * If [cause] is not null, cancel all actor jobs with [cause] as the cause
     */
    suspend fun cancel(cause: Throwable? = null)
}

/**
 * This represents the possible states of an [Actor]
 */
enum class ActorState {
    /**
     * The [Actor] is not running
     */
    STOPPED,

    /**
     * The Actor is in the process of starting.
     * It accepts tasks but they will not begin to be executed until the actor is [STARTED]
     */
    STARTING,

    /**
     * The [Actor] is running and executing tasks.
     */
    STARTED,

    /**
     * The [Actor] is no longer accepting tasks, but is still executing a backlog of tasks from when it was [STARTED]
     */
    STOPPING
}

/**
 * An Actor executes functions in a set of coroutines.
 * Internally, this is implemented with a [Channel], to which tasks are sent. Each task is simply a Kotlin Lambda.
 *
 * @param scope The scope in which to run the workers that execute the tasks enqueued upon the Actor
 * @param start The starting mode, which can be [CoroutineStart.LAZY] or [CoroutineStart.DEFAULT].
 * @param concurrency The number of workers, defaulting to one worker (which should be used when data is mutable
 */
abstract class BaseActor(private val scope: CoroutineScope = GlobalScope, private val start: CoroutineStart? = CoroutineStart.LAZY, private val concurrency: Int = 1) : Actor {
    /**
     * Internal communication mechanism between the actor and the user
     */
    private lateinit var tasks: Channel<RemoteFunction<*>>

    /**
     * The Job executing tasks
     * This Job has at least one child. Each child of this Job is used to execute tasks
     */
    private var job: Job? = null

    /**
     * Lock held when creating or destroying jobs, or changing [acceptingTasks]
     */
    private val startStopLock = Mutex(start != CoroutineStart.LAZY)

    /**
     * Variable to set whether new tasks should be accepted
     * Set to false during [ActorState.STOPPING] or [ActorState.STOPPED]
     */
    private var acceptingTasks = false

    /**
     * The CoroutineStart used for the jobs
     */
    private val realStart get() = when (start) {
        CoroutineStart.LAZY, null -> CoroutineStart.DEFAULT
        else -> start
    }

    init {
        when (start) {
            CoroutineStart.LAZY, null -> { } // Lazily wait
            else -> {
                @Suppress("DEPRECATION_ERROR")
                startUnlocked()
                startStopLock.unlock() // It starts locked if the [start] != LAZY
            }
        }
    }

    final override val state: ActorState
        get() = when {
            job == null && !acceptingTasks -> ActorState.STOPPED
            job == null && acceptingTasks -> ActorState.STARTING // We should only be in this state for a few ms, but it saves us the need to lock
            job != null && acceptingTasks -> ActorState.STARTED
            else -> ActorState.STOPPING // job != null && acceptingTasks
        }

    final override suspend fun <R>act(block: suspend () -> R): R {
        if (state == ActorState.STOPPED && start == CoroutineStart.LAZY) {
            start()
        }
        if (!acceptingTasks) error("This Actor is not ready to accept tasks")
        val result = CompletableDeferred<R>()
        tasks.send(RemoteFunction(block, result))
        return result.await()
    }

    /**
     * The code running inside the actor that takes tasks from [tasks] and executes them
     */
    private suspend fun loop() {
        for (task in tasks) {
            task.runAndComplete()
        }
    }

    private fun startUnlocked() {
        when (state) {
            ActorState.STOPPED -> {
                tasks = Channel(Channel.UNLIMITED)
                acceptingTasks = true
                job = scope.launch(start = realStart) {
                    repeat(concurrency) {
                        this@launch.launch {
                            loop()
                        }
                    }
                }
            }
            else -> return
        }
    }

    final override suspend fun start() = startStopLock.withLock {
        @Suppress("DEPRECATION_ERROR")
        startUnlocked()
    }

    final override suspend fun cancel(cause: Throwable?) = startStopLock.withLock {
        if (state == ActorState.STARTED) {
            acceptingTasks = false
            tasks.close(cause)
            job!!.join() // The cancellation token will mean the jobs keep running until all input is exhausted
            job = null
        }
    }
}

/**
 * An actor that can be used without any subclassing or inheritance
 */
class ActorImpl(scope: CoroutineScope = GlobalScope, start: CoroutineStart = CoroutineStart.LAZY, concurrency: Int = 1) : BaseActor(scope, start, concurrency) {
    suspend operator fun <R>invoke(block: suspend () -> R): R = act(block)
}

/**
 * Class used to represent the equivalent of an RPC for Actors - running a procedure in another Job
 *
 * @param function The code to be executed remotely
 * @param result The output of the code
 */
private class RemoteFunction<R>(private val function: suspend () -> R, private val result: CompletableDeferred<R>) {
    /**
     * Execute the [function] and complete the [result], whether exceptionally or successfully
     */
    suspend fun runAndComplete() {
        try {
            result.complete(function())
        } catch (e: Throwable) {
            result.completeExceptionally(e)
        }
    }
}
