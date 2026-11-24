package com.instalogger.app.engine.pool

import kotlin.math.ceil

/**
 * Explicit completeness status for follower/following list fetches.
 * Prevents partial 47-50 item API slices or empty error responses from
 * triggering false "unfollowed" alerts.
 */
enum class ListCompletenessStatus {
    COMPLETE,
    PARTIAL,
    EMPTY_UNEXPECTED,
    UNAVAILABLE
}

data class ListDiffDecision(
    val status: ListCompletenessStatus,
    val allowGainAlerts: Boolean,
    val allowLossAlerts: Boolean,
    val allowArchiveOverwrite: Boolean
)

object ListCompletenessGuard {
    private const val COMPLETE_RATIO_THRESHOLD = 0.85
    private const val MAX_SINGLE_PAGE_SIZE = 50L

    fun evaluate(
        fetchedCount: Int,
        reportedTotalCount: Long,
        guardEnabled: Boolean
    ): ListDiffDecision {
        if (!guardEnabled) {
            return if (fetchedCount <= 0) {
                ListDiffDecision(
                    status = ListCompletenessStatus.EMPTY_UNEXPECTED,
                    allowGainAlerts = false,
                    allowLossAlerts = false,
                    allowArchiveOverwrite = false
                )
            } else {
                ListDiffDecision(
                    status = ListCompletenessStatus.COMPLETE,
                    allowGainAlerts = true,
                    allowLossAlerts = true,
                    allowArchiveOverwrite = true
                )
            }
        }

        if (fetchedCount <= 0) {
            return if (reportedTotalCount > 0L) {
                ListDiffDecision(
                    status = ListCompletenessStatus.EMPTY_UNEXPECTED,
                    allowGainAlerts = false,
                    allowLossAlerts = false,
                    allowArchiveOverwrite = false
                )
            } else {
                ListDiffDecision(
                    status = ListCompletenessStatus.UNAVAILABLE,
                    allowGainAlerts = false,
                    allowLossAlerts = false,
                    allowArchiveOverwrite = false
                )
            }
        }

        // If reported total exceeds a single API page and we fetched significantly less than 85%,
        // the list is a partial slice — items sliding off the page are NOT unfollows.
        if (reportedTotalCount > MAX_SINGLE_PAGE_SIZE) {
            val minCompleteCount = ceil(reportedTotalCount * COMPLETE_RATIO_THRESHOLD).toLong()
            if (fetchedCount.toLong() < minCompleteCount) {
                return ListDiffDecision(
                    status = ListCompletenessStatus.PARTIAL,
                    allowGainAlerts = false,
                    allowLossAlerts = false,
                    allowArchiveOverwrite = true
                )
            }
        }

        return ListDiffDecision(
            status = ListCompletenessStatus.COMPLETE,
            allowGainAlerts = true,
            allowLossAlerts = true,
            allowArchiveOverwrite = true
        )
    }
}
