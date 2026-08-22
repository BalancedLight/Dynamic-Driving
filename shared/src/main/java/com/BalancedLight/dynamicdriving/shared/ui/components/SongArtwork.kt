package com.BalancedLight.dynamicdriving.shared.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.BalancedLight.dynamicdriving.shared.R
import com.BalancedLight.dynamicdriving.shared.artwork.SongArtworkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cover art for a song, falling back to a tinted note placeholder.
 *
 * Decoding happens off the main thread and reuses the same bounded JPEG cache the car surfaces read
 * from, so scrolling a large library does not decode full-size images.
 */
@Composable
fun SongArtwork(
    songId: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp
) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, songId) {
        value = songId?.let { id ->
            withContext(Dispatchers.IO) {
                SongArtworkStore.get(context).loadBitmap(id)
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val loadedBitmap = bitmap
        if (loadedBitmap != null) {
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.now_playing_artwork),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(percentPadding)
            )
        }
    }
}

private val percentPadding = 12.dp
