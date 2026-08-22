package com.BalancedLight.dynamicdriving.shared.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where adaptive playback should take its speed reading from. */
enum class SpeedSourceSelection {
    /** Pick the best available source for the current connection state. */
    AUTOMATIC,

    /** Native AAOS vehicle speed, or projected car speed on Android Auto. Never falls back. */
    CAR,

    /** The phone's own location provider. Never falls back. */
    PHONE_GPS,

    /** A speed the user sets by hand. */
    MANUAL
}

/** How playback advances through the active collection. */
enum class PlaylistPlaybackPolicy {
    /** Stay on the current song forever. */
    REPEAT_SONG,

    /** Advance in collection order after [DynamicDrivingSettings.loopsBeforeAdvance] loops. */
    SEQUENTIAL,

    /** Advance to a random other song after [DynamicDrivingSettings.loopsBeforeAdvance] loops. */
    SHUFFLE
}

/** Identifies the collection playback is currently drawing from. */
sealed interface ActiveCollection {
    val id: String

    data object AllSongs : ActiveCollection {
        override val id: String = ALL_SONGS_ID
    }

    data class Playlist(val playlistId: String) : ActiveCollection {
        override val id: String = "$PLAYLIST_PREFIX$playlistId"
    }

    companion object {
        const val ALL_SONGS_ID: String = "all_songs"
        const val PLAYLIST_PREFIX: String = "playlist:"

        fun fromId(rawId: String?): ActiveCollection {
            if (rawId == null || rawId == ALL_SONGS_ID) {
                return AllSongs
            }
            if (rawId.startsWith(PLAYLIST_PREFIX)) {
                val playlistId = rawId.removePrefix(PLAYLIST_PREFIX)
                if (playlistId.isNotBlank()) {
                    return Playlist(playlistId)
                }
            }
            return AllSongs
        }
    }
}

data class DynamicDrivingSettingsState(
    val speedSourceSelection: SpeedSourceSelection = SpeedSourceSelection.AUTOMATIC,
    val manualSpeedMph: Double = DEFAULT_MANUAL_SPEED_MPH,
    val playbackPolicy: PlaylistPlaybackPolicy = PlaylistPlaybackPolicy.REPEAT_SONG,
    val loopsBeforeAdvance: Int = DEFAULT_LOOPS_BEFORE_ADVANCE,
    val bundledDemoEnabled: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val activeCollectionId: String = ActiveCollection.ALL_SONGS_ID,
    val lastSongId: String? = null
) {
    val activeCollection: ActiveCollection
        get() = ActiveCollection.fromId(activeCollectionId)

    companion object {
        const val DEFAULT_MANUAL_SPEED_MPH: Double = 25.0
        const val DEFAULT_LOOPS_BEFORE_ADVANCE: Int = 2
    }
}

/**
 * Durable, device-local user settings.
 *
 * Everything here stays on the device: nothing is uploaded, and no speed or location sample is
 * ever persisted — only the user's choice of *which* source to read from.
 */
class DynamicDrivingSettings(context: Context) {
    companion object {
        const val MIN_LOOPS_BEFORE_ADVANCE: Int = 1
        const val MAX_LOOPS_BEFORE_ADVANCE: Int = 8
        const val MIN_MANUAL_SPEED_MPH: Double = 0.0
        const val MAX_MANUAL_SPEED_MPH: Double = 120.0

        private const val PREFERENCES_NAME = "dynamic_driving_settings"
        private const val KEY_SPEED_SOURCE = "speed_source_selection"
        private const val KEY_MANUAL_SPEED = "manual_speed_mph"
        private const val KEY_PLAYBACK_POLICY = "playback_policy"
        private const val KEY_LOOPS_BEFORE_ADVANCE = "loops_before_advance"
        private const val KEY_BUNDLED_DEMO_ENABLED = "bundled_demo_enabled"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_ACTIVE_COLLECTION = "active_collection_id"
        private const val KEY_LAST_SONG_ID = "last_song_id"
    }

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(readState())

    val state: StateFlow<DynamicDrivingSettingsState> = _state.asStateFlow()

    fun setSpeedSourceSelection(selection: SpeedSourceSelection) {
        preferences.edit { putString(KEY_SPEED_SOURCE, selection.name) }
        _state.update { it.copy(speedSourceSelection = selection) }
    }

    fun setManualSpeedMph(mph: Double) {
        val clamped = mph.coerceIn(MIN_MANUAL_SPEED_MPH, MAX_MANUAL_SPEED_MPH)
        preferences.edit { putFloat(KEY_MANUAL_SPEED, clamped.toFloat()) }
        _state.update { it.copy(manualSpeedMph = clamped) }
    }

    fun setPlaybackPolicy(policy: PlaylistPlaybackPolicy) {
        preferences.edit { putString(KEY_PLAYBACK_POLICY, policy.name) }
        _state.update { it.copy(playbackPolicy = policy) }
    }

    fun setLoopsBeforeAdvance(loops: Int) {
        val clamped = loops.coerceIn(MIN_LOOPS_BEFORE_ADVANCE, MAX_LOOPS_BEFORE_ADVANCE)
        preferences.edit { putInt(KEY_LOOPS_BEFORE_ADVANCE, clamped) }
        _state.update { it.copy(loopsBeforeAdvance = clamped) }
    }

    fun setBundledDemoEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_BUNDLED_DEMO_ENABLED, enabled) }
        _state.update { it.copy(bundledDemoEnabled = enabled) }
    }

    fun setOnboardingCompleted(completed: Boolean) {
        preferences.edit { putBoolean(KEY_ONBOARDING_COMPLETED, completed) }
        _state.update { it.copy(onboardingCompleted = completed) }
    }

    fun setActiveCollection(collection: ActiveCollection) {
        preferences.edit { putString(KEY_ACTIVE_COLLECTION, collection.id) }
        _state.update { it.copy(activeCollectionId = collection.id) }
    }

    fun setLastSongId(songId: String?) {
        preferences.edit {
            if (songId == null) remove(KEY_LAST_SONG_ID) else putString(KEY_LAST_SONG_ID, songId)
        }
        _state.update { it.copy(lastSongId = songId) }
    }

    private fun readState(): DynamicDrivingSettingsState {
        return DynamicDrivingSettingsState(
            speedSourceSelection = preferences.getString(KEY_SPEED_SOURCE, null)
                ?.let { name -> SpeedSourceSelection.entries.firstOrNull { it.name == name } }
                ?: SpeedSourceSelection.AUTOMATIC,
            manualSpeedMph = preferences
                .getFloat(KEY_MANUAL_SPEED, DynamicDrivingSettingsState.DEFAULT_MANUAL_SPEED_MPH.toFloat())
                .toDouble()
                .coerceIn(MIN_MANUAL_SPEED_MPH, MAX_MANUAL_SPEED_MPH),
            playbackPolicy = preferences.getString(KEY_PLAYBACK_POLICY, null)
                ?.let { name -> PlaylistPlaybackPolicy.entries.firstOrNull { it.name == name } }
                ?: PlaylistPlaybackPolicy.REPEAT_SONG,
            loopsBeforeAdvance = preferences
                .getInt(KEY_LOOPS_BEFORE_ADVANCE, DynamicDrivingSettingsState.DEFAULT_LOOPS_BEFORE_ADVANCE)
                .coerceIn(MIN_LOOPS_BEFORE_ADVANCE, MAX_LOOPS_BEFORE_ADVANCE),
            bundledDemoEnabled = preferences.getBoolean(KEY_BUNDLED_DEMO_ENABLED, true),
            onboardingCompleted = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false),
            activeCollectionId = preferences.getString(KEY_ACTIVE_COLLECTION, null)
                ?: ActiveCollection.ALL_SONGS_ID,
            lastSongId = preferences.getString(KEY_LAST_SONG_ID, null)
        )
    }
}
