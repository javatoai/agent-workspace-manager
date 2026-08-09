package com.snowball.awm.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs one mutating application operation without coupling feature controllers together. */
class OperationRunner internal constructor(
    private val coordinator: OperationCoordinator,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    fun <T> run(
        activeMessage: String,
        successMessage: String,
        block: () -> T,
        onSuccess: (T) -> Unit = {},
    ): Boolean {
        if (!coordinator.begin(activeMessage)) {
            coordinator.errorMessage = "另一个操作正在执行，请稍候"
            return false
        }
        scope.launch {
            val result = withContext(dispatcher) { runCatching(block) }
            result.fold(
                onSuccess = { value ->
                    runCatching { onSuccess(value) }
                        .onSuccess { coordinator.succeed(successMessage) }
                        .onFailure(coordinator::fail)
                },
                onFailure = coordinator::fail,
            )
        }
        return true
    }
}
