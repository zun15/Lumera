package com.lumera.app.data.model

data class ParsedStreamInfo(
    val quality: StreamQuality,
    val sizeBytes: Long?,
    val seeds: Int?,
    val formats: Set<String> = emptySet(),
    /** Source text mentions IMAX (e.g. "IMAX", "IMAX Enhanced"). Independent of HDR/DV formats. */
    val isImax: Boolean = false,
    /** Source text suggests Latin American Spanish audio (best effort, depends on the addon's wording). */
    val isLatino: Boolean = false
)
