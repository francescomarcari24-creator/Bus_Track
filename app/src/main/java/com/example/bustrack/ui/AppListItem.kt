package com.example.bustrack.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

/**
 * [ListItem] di Material3 (overload non deprecato, titolo come lambda finale) con l'aspetto
 * del vecchio overload: angoli retti (le voci affiancate non mostrano rientranze tra loro)
 * e 16 dp tra il contenuto iniziale e il testo (il nuovo ne lascia 12).
 */
@Composable
internal fun AppListItem(
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    ListItem(
        modifier = modifier,
        leadingContent = leadingContent?.let { leading -> { Box(Modifier.padding(end = 4.dp)) { leading() } } },
        supportingContent = supportingContent,
        trailingContent = trailingContent,
        shapes = ListItemDefaults.shapes(shape = RectangleShape),
        content = content,
    )
}
