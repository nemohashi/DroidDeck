package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R

/**
 * A small button that stays on the desktop session, at the right edge above the middle, for the
 * soft keyboard: the drawer's Android button is two gestures away, and nothing of the app's is
 * otherwise on the desktop's screen. Above the middle keeps it clear of the keyboard itself.
 */
@Composable
internal fun KeyboardFab(onClick: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = (-10).dp, y = (-96).dp).size(44.dp).testTag("desktop-keyboard"),
        ) {
            Icon(Icons.Outlined.Keyboard, contentDescription = stringResource(R.string.pckb_android), modifier = Modifier.padding(10.dp))
        }
    }
}
