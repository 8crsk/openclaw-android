package com.crsk.openclaw.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.crsk.openclaw.ui.theme.AccentBlue
import com.crsk.openclaw.ui.theme.BgBase
import com.crsk.openclaw.ui.theme.Separator
import com.crsk.openclaw.ui.theme.SystemGray1

data class NavTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * iOS-style tab bar. Hairline separator on top, no pill, no glow. Active tab is the
 * accent color; inactive tabs are system gray 1. Caller passes the same icon for both
 * states (icon swap to filled-vs-outlined is the caller's call).
 */
@Composable
fun SegmentedBottomNav(
    tabs: List<NavTab>,
    activeRoute: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeIndex = tabs.indexOfFirst { it.route == activeRoute }.coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BgBase),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Separator),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(49.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                NavTabItem(
                    tab = tab,
                    selected = index == activeIndex,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable { onSelect(tab.route) }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NavTabItem(
    tab: NavTab,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) AccentBlue else SystemGray1
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
