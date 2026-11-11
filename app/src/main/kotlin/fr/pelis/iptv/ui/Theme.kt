package fr.pelis.iptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val Colors = darkColorScheme(
    primary = Color(0xFF29B6F6),
    onPrimary = Color(0xFF00222F),
    secondary = Color(0xFF80D8FF),
    background = Color(0xFF0A1220),
    surface = Color(0xFF0F1A2B),
    surfaceVariant = Color(0xFF1B2940),
    surfaceContainer = Color(0xFF13203A),
    onBackground = Color(0xFFE6ECF5),
    onSurface = Color(0xFFE6ECF5),
)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry, modifier = Modifier.tvItem(androidx.compose.foundation.shape.CircleShape)) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Text("  Réessayer")
        }
    }
}
