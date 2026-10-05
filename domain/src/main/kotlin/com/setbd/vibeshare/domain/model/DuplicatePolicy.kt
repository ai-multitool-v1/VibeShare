package com.setbd.vibeshare.domain.model

/** How to resolve a filename that already exists at the destination (spec section 24). */
enum class DuplicatePolicy { ASK, REPLACE, KEEP_BOTH, SKIP }

/** A pending duplicate decision surfaced to the receiver UI. */
data class DuplicateDecision(
    val fileId: String,
    val fileName: String,
    val chosen: DuplicatePolicy? = null,
)
