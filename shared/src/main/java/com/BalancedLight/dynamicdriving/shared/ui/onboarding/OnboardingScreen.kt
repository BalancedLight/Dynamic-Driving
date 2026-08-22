package com.BalancedLight.dynamicdriving.shared.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.BalancedLight.dynamicdriving.shared.R
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
    val actionRes: Int? = null
)

/**
 * First-run introduction.
 *
 * Four pages: what Dynamic Driving is (and that a demo is already bundled), how to bring your own
 * songs, how adaptive playback works, and why location may be requested. The location page is
 * deliberately last and explains the reason before any permission dialog can appear.
 */
@Composable
fun OnboardingScreen(
    onChooseFolder: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pages = listOf(
        OnboardingPage(
            icon = Icons.Rounded.Route,
            titleRes = R.string.onboarding_welcome_title,
            bodyRes = R.string.onboarding_welcome_body
        ),
        OnboardingPage(
            icon = Icons.Rounded.FolderOpen,
            titleRes = R.string.onboarding_folder_title,
            bodyRes = R.string.onboarding_folder_body,
            actionRes = R.string.onboarding_folder_action
        ),
        OnboardingPage(
            icon = Icons.Rounded.GraphicEq,
            titleRes = R.string.onboarding_adaptive_title,
            bodyRes = R.string.onboarding_adaptive_body
        ),
        OnboardingPage(
            icon = Icons.Rounded.MyLocation,
            titleRes = R.string.onboarding_location_title,
            bodyRes = R.string.onboarding_location_body
        )
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onFinish) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { pageIndex ->
            OnboardingPageContent(page = pages[pageIndex], onAction = onChooseFolder)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(pages.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == pagerState.currentPage) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                            )
                    )
                }
            }

            val isLastPage = pagerState.currentPage == pages.lastIndex
            Button(
                onClick = {
                    if (isLastPage) {
                        onFinish()
                    } else {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                }
            ) {
                Text(
                    stringResource(
                        if (isLastPage) R.string.onboarding_done else R.string.onboarding_next
                    )
                )
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = page.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        page.actionRes?.let { actionRes ->
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onAction) {
                Text(stringResource(actionRes))
            }
        }
    }
}
