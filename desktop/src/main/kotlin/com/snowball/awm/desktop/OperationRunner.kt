package com.snowball.awm.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Runs one mutating application operation without coupling feature controllers together. */
class OperationRunner internal constructor(
    private val coordinator: OperationCoordinator,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private var activeJob: Job? = null

    fun <T> run(
        activeMessage: String,
        successMessage: String,
        cancellable: Boolean = false,
        block: () -> T,
        onFailure: (Throwable) -> Unit = {},
        onSuccess: (T) -> Unit = {},
    ): Boolean {
        if (!coordinator.begin(activeMessage, cancellable)) {
            val error = IllegalStateException("另一个操作正在执行，请稍候")
            runCatching { onFailure(error) }
                .onFailure { callbackError -> coordinator.errorMessage = OperationFailureDetails.format(callbackError) }
                .onSuccess { coordinator.errorMessage = error.message }
            return false
        }
        val job = scope.launch {
            try {
                val value = runInterruptible(dispatcher, block)
                runCatching { onSuccess(value) }
                    .onSuccess { coordinator.succeed(successMessage) }
                    .onFailure { error -> onFailure(error); coordinator.fail(error) }
            } catch (_: CancellationException) {
                coordinator.cancelled()
            } catch (error: Throwable) {
                onFailure(error)
                coordinator.fail(error)
            } finally {
                activeJob = null
            }
        }
        activeJob = job
        return true
    }

    fun cancel(): Boolean {
        if (!coordinator.busy || !coordinator.cancellable) return false
        coordinator.markCancelling()
        activeJob?.cancel()
        return true
    }
}
