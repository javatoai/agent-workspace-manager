package com.snowball.awm.core

/**
 * Defines the persistence compatibility boundary for AWM JSON files.
 *
 * PATCH releases must not change persisted field semantics, so documents from
 * the same MAJOR.MINOR line can be read safely. A MAJOR or MINOR change is a
 * deliberate schema boundary and must be rejected rather than guessed at.
 */
object SchemaVersionCompatibility {
    fun isCompatible(actual: String?, expected: String): Boolean {
        val actualParts = actual?.split('.')?.mapNotNull(String::toIntOrNull) ?: return false
        val expectedParts = expected.split('.').mapNotNull(String::toIntOrNull)
        if (actualParts.size != 3 || expectedParts.size != 3) return false
        return actualParts[0] == expectedParts[0] && actualParts[1] == expectedParts[1]
    }
}
