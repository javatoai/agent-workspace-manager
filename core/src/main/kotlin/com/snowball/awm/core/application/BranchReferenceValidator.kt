package com.snowball.awm.core

fun interface BranchReferenceValidator {
    fun isValid(branch: String): Boolean
}
