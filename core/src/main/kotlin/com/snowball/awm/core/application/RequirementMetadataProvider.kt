package com.snowball.awm.core

/** Retrieves optional requirement metadata; failures are non-blocking to task creation. */
fun interface RequirementMetadataProvider {
    fun fetch(requirementLink: String): RequirementMetadata?
}
