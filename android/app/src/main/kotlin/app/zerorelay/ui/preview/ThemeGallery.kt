package app.zerorelay.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.zerorelay.ui.theme.ZeroRelayTheme

@Preview(showBackground = true, name = "Branded light")
@Composable
private fun ThemeGalleryBrandedLightPreview() {
    ThemeGallerySample(darkTheme = false, dynamicColor = false)
}

@Preview(showBackground = true, name = "Branded dark")
@Composable
private fun ThemeGalleryBrandedDarkPreview() {
    ThemeGallerySample(darkTheme = true, dynamicColor = false)
}

@Preview(showBackground = true, name = "Tablet width", widthDp = 840, heightDp = 600)
@Composable
private fun ThemeGalleryTabletWidthPreview() {
    ThemeGallerySample(darkTheme = false, dynamicColor = false)
}

@Composable
private fun ThemeGallerySample(
    darkTheme: Boolean,
    dynamicColor: Boolean,
) {
    ZeroRelayTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("ZeroRelay", style = MaterialTheme.typography.headlineMedium)
                Text("Primary", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text(
                    "On surface variant",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        "Primary container",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        "Surface container high",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
