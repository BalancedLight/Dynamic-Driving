package com.BalancedLight.dynamicdriving.shared.playback

import com.BalancedLight.dynamicdriving.shared.settings.PlaylistPlaybackPolicy
import kotlin.random.Random

/**
 * Pure rules for moving through the active collection.
 *
 * Manual previous steps through collection order. Manual next follows Shuffle when it is selected,
 * while Repeat Song still permits an explicit skip. Automatic advancement only happens for
 * [PlaylistPlaybackPolicy.SEQUENTIAL] and [PlaylistPlaybackPolicy.SHUFFLE], and only once the
 * configured number of loops has elapsed.
 */
object PlaybackQueueRules {
    /** True when the current song has looped enough times to hand over to the next one. */
    fun shouldAdvanceAfterLoop(
        policy: PlaylistPlaybackPolicy,
        completedLoopCount: Int,
        loopsBeforeAdvance: Int
    ): Boolean {
        if (policy == PlaylistPlaybackPolicy.REPEAT_SONG) {
            return false
        }
        return completedLoopCount >= loopsBeforeAdvance.coerceAtLeast(1)
    }

    /** The song automatic advancement should move to, or null when there is nowhere to go. */
    fun nextAutomaticSongId(
        collectionSongIds: List<String>,
        currentSongId: String?,
        policy: PlaylistPlaybackPolicy,
        random: Random
    ): String? {
        if (collectionSongIds.isEmpty() || policy == PlaylistPlaybackPolicy.REPEAT_SONG) {
            return null
        }
        return when (policy) {
            PlaylistPlaybackPolicy.SEQUENTIAL -> adjacentSongId(collectionSongIds, currentSongId, 1)
            PlaylistPlaybackPolicy.SHUFFLE -> {
                val candidates = collectionSongIds.filter { it != currentSongId }
                when {
                    candidates.isNotEmpty() -> candidates[random.nextInt(candidates.size)]
                    else -> null
                }
            }

            PlaylistPlaybackPolicy.REPEAT_SONG -> null
        }
    }

    /** Resolves an explicit next/previous action without making Repeat Song trap the user. */
    fun manualSkipSongId(
        collectionSongIds: List<String>,
        currentSongId: String?,
        offset: Int,
        policy: PlaylistPlaybackPolicy,
        random: Random
    ): String? {
        return if (offset > 0 && policy == PlaylistPlaybackPolicy.SHUFFLE) {
            nextAutomaticSongId(collectionSongIds, currentSongId, policy, random)
        } else {
            adjacentSongId(collectionSongIds, currentSongId, offset)
        }
    }

    /**
     * Steps [offset] positions from [currentSongId] within [collectionSongIds], wrapping around.
     *
     * A song that is no longer in the collection is treated as position 0 so a stale selection still
     * produces a predictable move.
     */
    fun adjacentSongId(
        collectionSongIds: List<String>,
        currentSongId: String?,
        offset: Int
    ): String? {
        if (collectionSongIds.isEmpty()) {
            return null
        }
        val currentIndex = collectionSongIds.indexOf(currentSongId).takeIf { it >= 0 } ?: 0
        val size = collectionSongIds.size
        val targetIndex = ((currentIndex + offset) % size + size) % size
        return collectionSongIds[targetIndex]
    }
}
