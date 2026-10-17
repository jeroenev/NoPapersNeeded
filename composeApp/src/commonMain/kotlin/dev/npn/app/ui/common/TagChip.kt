package dev.npn.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.npn.core.app.TagInfo

/** Parse a hex color string like "#RRGGBB" or "#AARRGGBB". Returns null on failure. */
fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val cleaned = hex.trim().removePrefix("#")
    return runCatching {
        when (cleaned.length) {
            6 -> {
                val v = cleaned.toLong(16)
                Color(0xFF000000 or v)
            }

            8 -> {
                Color(cleaned.toLong(16))
            }

            else -> {
                null
            }
        }
    }.getOrNull()
}

/** Each tag's colour by name, the lookup [TagChip] callers and the tag editor read. */
fun List<TagInfo>.colorsByName(): Map<String, String?> = associate { it.name to it.color }

@Composable
fun TagChip(
    name: String,
    modifier: Modifier = Modifier,
    color: String? = null,
) {
    val dot = parseHexColor(color) ?: MaterialTheme.colorScheme.primary
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
