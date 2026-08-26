package com.snowball.awm.core

import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/** Persists read-only Genbu status beside every Git Tag operation with an exact Tag. */
class GenbuTagProbeService(
    private val operations: TagOperationStore = TagOperationStore(),
    private val genbu: GenbuTagStatusProvider = ProcessGenbuTagStatusService(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun probe(config: AppConfig, tasks: List<TaskManifest>, force: Boolean = false): Boolean {
        val taskRoot = config.taskRoot?.takeIf(String::isNotBlank)?.let(Path::of) ?: return false
        val candidates = tasks.flatMap { task -> candidatesForTask(config, taskRoot, task) }
        var changed = false
        candidates.groupBy { it.genbuServiceName }.values.forEach { serviceCandidates ->
            changed = probeService(serviceCandidates.sortedByDescending { it.operation.createdAt }, force) || changed
        }
        return changed
    }

    private fun candidatesForTask(config: AppConfig, taskRoot: Path, task: TaskManifest): List<Candidate> {
        val group = config.groups.firstOrNull { it.id == task.groupId } ?: return emptyList()
        val services = group.services.associateBy(GroupServiceConfig::id)
        val directory = taskRoot.resolve(task.taskDirectoryName)
        return runCatching { operations.list(directory) }.getOrDefault(emptyList()).mapNotNull { operation ->
            val service = services[operation.groupServiceId] ?: return@mapNotNull null
            if (!service.genbuProbeEnabled || operation.tag.isNullOrBlank()) return@mapNotNull null
            Candidate(directory, operation, service.genbuServiceName.trim())
        }
    }

    private fun probeService(candidates: List<Candidate>, force: Boolean): Boolean {
        var changed = false
        val latestReleasedIndex = candidates.indexOfFirst { it.operation.genbuStatus.released }
        if (!force && latestReleasedIndex >= 0) {
            changed = stopOlderCandidates(candidates.drop(latestReleasedIndex + 1)) || changed
        }
        val candidatesToProbe = if (force || latestReleasedIndex < 0) candidates else candidates.take(latestReleasedIndex)
        candidatesToProbe.forEachIndexed { index, candidate ->
            val status = candidate.operation.genbuStatus
            if (!force && (status.released || status.notFound || status.stoppedByNewerRelease)) return@forEachIndexed
            val queried = runCatching { genbu.query(candidate.genbuServiceName, requireNotNull(candidate.operation.tag)) }
            val updated = queried.fold(
                onSuccess = { result -> candidate.operation.copy(genbuStatus = status.copy(
                    built = result.built,
                    released = result.uatReleased,
                    productionReleased = result.productionReleased,
                    notFound = result.notFound,
                    builtCompletedAt = result.builtCompletedAt,
                    releasedCompletedAt = result.uatReleasedCompletedAt,
                    productionReleasedCompletedAt = result.productionReleasedCompletedAt,
                    checkedAt = AwmTime.format(Instant.now(clock)),
                    failureReason = null,
                )) },
                onFailure = { error -> candidate.operation.copy(genbuStatus = status.copy(
                    notFound = false,
                    checkedAt = AwmTime.format(Instant.now(clock)),
                    failureReason = error.message ?: "Genbu 探测失败",
                )) },
            )
            if (updated != candidate.operation) {
                operations.save(candidate.taskDirectory, updated)
                changed = true
            }
            if (!force && updated.genbuStatus.released) {
                changed = stopOlderCandidates(candidates.drop(index + 1)) || changed
                return changed
            }
        }
        return changed
    }

    private fun stopOlderCandidates(candidates: List<Candidate>): Boolean {
        var changed = false
        candidates.forEach { older ->
            val oldStatus = older.operation.genbuStatus
            if (!oldStatus.released && !oldStatus.stoppedByNewerRelease) {
                operations.save(older.taskDirectory, older.operation.copy(genbuStatus = oldStatus.copy(
                    stoppedByNewerRelease = true,
                    failureReason = null,
                )))
                changed = true
            }
        }
        return changed
    }

    private data class Candidate(
        val taskDirectory: Path,
        val operation: TagOperation,
        val genbuServiceName: String,
    )
}
