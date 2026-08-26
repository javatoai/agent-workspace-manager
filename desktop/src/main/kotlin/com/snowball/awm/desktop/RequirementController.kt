package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.FeishuWorkItemLink
import com.snowball.awm.core.RequirementMetadata
import com.snowball.awm.core.RequirementMetadataProvider
import com.snowball.awm.core.MeegleRequirementLinkSource
import com.snowball.awm.core.RequirementLinkCandidate
import com.snowball.awm.core.RequirementLinkFailure
import com.snowball.awm.core.RequirementLinkFailureLog
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.fetch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Clock
import java.time.Duration

/** Session-only requirement metadata state. It is never persisted into task JSON. */
sealed interface RequirementUiState {
    data object NotLoaded : RequirementUiState
    data object Loading : RequirementUiState
    data class Loaded(val metadata: RequirementMetadata) : RequirementUiState
    data object Failed : RequirementUiState
}

private data class RequirementRequestKey(val projectKey: String?, val link: String)
internal sealed interface RequirementFetchResult {
    data class Success(val metadata: RequirementMetadata) : RequirementFetchResult
    data object Failure : RequirementFetchResult
}
private data class RequirementCacheEntry(val result: RequirementFetchResult, val expiresAtMillis: Long)

/**
 * Deduplicates local Meegle calls and bounds concurrency. Failures intentionally
 * have a short cache window so an unavailable CLI cannot create a process storm.
 */
internal class RequirementMetadataCoordinator(
    private val provider: RequirementMetadataProvider,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.systemUTC(),
    maxConcurrency: Int = 4,
    private val successTtl: Duration = Duration.ofMinutes(5),
    private val failureTtl: Duration = Duration.ofSeconds(30),
) {
    private val mutex = Mutex()
    private val semaphore = Semaphore(maxConcurrency)
    private val cache = mutableMapOf<RequirementRequestKey, RequirementCacheEntry>()
    private val inFlight = mutableMapOf<RequirementRequestKey, Deferred<RequirementFetchResult>>()

    suspend fun fetch(link: String, projectKey: String?, force: Boolean = false): RequirementFetchResult {
        val key = RequirementRequestKey(projectKey, link.trim())
        val now = clock.millis()
        val deferred = mutex.withLock {
            if (!force) {
                cache[key]?.takeIf { it.expiresAtMillis > now }?.let { return it.result }
            }
            inFlight[key] ?: scope.async(ioDispatcher) {
                semaphore.withPermit {
                    runCatching { provider.fetch(key.link, key.projectKey) }
                        .getOrNull()
                        ?.let(RequirementFetchResult::Success)
                        ?: RequirementFetchResult.Failure
                }
            }.also { inFlight[key] = it }
        }
        val result = deferred.await()
        mutex.withLock {
            if (inFlight[key] === deferred) inFlight.remove(key)
            val ttl = if (result is RequirementFetchResult.Success) successTtl else failureTtl
            cache[key] = RequirementCacheEntry(result, clock.millis() + ttl.toMillis())
        }
        return result
    }

    suspend fun clear() = mutex.withLock { cache.clear() }
}

/**
 * Owns all Meegle metadata presentation state. Task identity and generation are
 * checked before every write so late subprocess results cannot update another task.
 */
class RequirementController internal constructor(
    private val session: AppSessionStore,
    private val scope: CoroutineScope,
    private val coordinator: RequirementMetadataCoordinator,
    private val linkSource: MeegleRequirementLinkSource? = null,
    private val failureLog: RequirementLinkFailureLog? = null,
    private val ioDispatcher: CoroutineDispatcher? = null,
) {
    var states by mutableStateOf<Map<String, RequirementUiState>>(emptyMap())
        private set
    private val generations = mutableMapOf<String, Long>()
    private var draftJob: Job? = null
    private var draftGeneration = 0L
    var candidates by mutableStateOf<List<RequirementLinkCandidate>>(emptyList())
        private set
    var candidatesLoading by mutableStateOf(false)
        private set

    fun stateFor(task: TaskManifest): RequirementUiState =
        states[task.taskDirectoryName] ?: RequirementUiState.NotLoaded

    fun loadedMetadataFor(task: TaskManifest): RequirementMetadata? =
        (stateFor(task) as? RequirementUiState.Loaded)?.metadata

    /** Resolves metadata for an action without changing the task-detail loading state. */
    fun fetchMetadata(task: TaskManifest, onResult: (RequirementMetadata?) -> Unit) {
        val parsed = FeishuWorkItemLink.parse(task.requirementLink)
        if (parsed == null) {
            onResult(null)
            return
        }
        val projectKey = projectKey(parsed, session.config)
        scope.launch {
            val result = coordinator.fetch(task.requirementLink, projectKey)
            if (result is RequirementFetchResult.Failure) recordMetadataFailure(projectKey)
            onResult((result as? RequirementFetchResult.Success)?.metadata)
        }
    }

    fun refreshAll(force: Boolean = false) {
        reconcileTasks()
        session.tasks.forEach { refresh(it, force) }
    }

    fun refresh(task: TaskManifest, force: Boolean = false) {
        val parsed = FeishuWorkItemLink.parse(task.requirementLink)
        if (parsed == null) {
            states = states + (task.taskDirectoryName to RequirementUiState.NotLoaded)
            return
        }
        val identity = task.taskDirectoryName
        val generation = (generations[identity] ?: 0L) + 1L
        generations[identity] = generation
        states = states + (identity to RequirementUiState.Loading)
        val link = task.requirementLink
        val projectKey = projectKey(parsed, session.config)
        scope.launch {
            val result = coordinator.fetch(link, projectKey, force)
            val stillCurrent = generations[identity] == generation &&
                session.tasks.any { it.taskDirectoryName == identity && it.requirementLink == link }
            if (!stillCurrent) return@launch
            states = states + (identity to when (result) {
                is RequirementFetchResult.Success -> RequirementUiState.Loaded(result.metadata)
                RequirementFetchResult.Failure -> {
                    recordMetadataFailure(projectKey)
                    RequirementUiState.Failed
                }
            })
        }
    }

    fun requestDraftMetadata(link: String, onResult: (RequirementMetadata?) -> Unit) {
        val generation = ++draftGeneration
        draftJob?.cancel()
        val parsed = FeishuWorkItemLink.parse(link)
        if (parsed == null) {
            onResult(null)
            return
        }
        val projectKey = projectKey(parsed, session.config)
        draftJob = scope.launch {
            delay(250)
            val result = coordinator.fetch(link, projectKey)
            if (generation != draftGeneration) return@launch
            if (result is RequirementFetchResult.Failure) recordMetadataFailure(projectKey)
            onResult((result as? RequirementFetchResult.Success)?.metadata)
        }
    }

    /** Runs once when the create dialog opens; failures remain non-blocking. */
    fun loadCandidates() {
        val source = linkSource ?: return
        val dispatcher = ioDispatcher ?: return
        val projects = session.config.meegleProjects
        if (projects.isEmpty() || candidatesLoading) return
        candidatesLoading = true
        scope.launch {
            try {
                val result = kotlinx.coroutines.withContext(dispatcher) { source.load(projects) }
                result.failures.forEach { failureLog?.record(it) }
                candidates = result.candidates
            } catch (error: Exception) {
                failureLog?.record(
                    RequirementLinkFailure(
                        stage = "desktop-load",
                        message = error.message ?: error::class.simpleName.orEmpty(),
                    ),
                )
            } finally {
                candidatesLoading = false
            }
        }
    }

    fun onConfigurationChanged() {
        generations.keys.forEach { generations[it] = (generations[it] ?: 0L) + 1L }
        states = emptyMap()
        candidates = emptyList()
        scope.launch { coordinator.clear() }
    }

    fun reconcileTasks() {
        val valid = session.tasks.map(TaskManifest::taskDirectoryName).toSet()
        generations.keys.retainAll(valid)
        states = states.filterKeys(valid::contains)
    }

    fun close() {
        draftJob?.cancel()
    }

    private fun projectKey(workItem: FeishuWorkItemLink, config: AppConfig): String? =
        config.meegleProjects.firstOrNull { it.simpleName.equals(workItem.space, ignoreCase = true) }
            ?.projectKey
            ?: workItem.projectKey

    private fun recordMetadataFailure(projectKey: String?) {
        failureLog?.record(
            RequirementLinkFailure(
                projectKey = projectKey,
                stage = "metadata",
                message = "Meegle metadata request failed",
            ),
        )
    }
}
