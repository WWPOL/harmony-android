package lol.mony.harmony

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.components.HarmonyCard
import lol.mony.harmony.ui.components.SectionHeader
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.LocalHarmonyColors

/**
 * A screen made of the app's own components, so a capture measures the design system itself.
 *
 * It is shared by the compact and the wide render tests: the same tokens have to survive both
 * widths, and one gallery means the two captures are comparable.
 */
internal object DesignGallery {
    @Composable
    fun Content() {

        val harmony = LocalHarmonyColors.current
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            SectionHeader("Your look", supporting = "The account's own palette, in OKLCH.")
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Display", style = MaterialTheme.typography.displaySmall)
                Text("Headline", style = MaterialTheme.typography.headlineSmall)
                Text("Title", style = MaterialTheme.typography.titleMedium)
                Text("Body text sits at the readable size the person chose.", style = MaterialTheme.typography.bodyMedium)
                Text("Label", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionHeader("Shapes")
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    "Card" to HarmonyShape.large,
                    "Chip" to HarmonyShape.pill,
                    "Tile" to HarmonyShape.medium,
                ).forEach { (label, shape) ->
                    Surface(
                        shape = shape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(width = 88.dp, height = 56.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            SectionHeader("A card and a message row")
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listOf("Ada", "Grace", "Alan")) { name ->
                    HarmonyCard(onClick = {}) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(36.dp).background(harmony.brand, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(name.take(1), color = harmony.onBrand,
                                    style = MaterialTheme.typography.titleSmall)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold)
                                Text("Something worth reading in this channel.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("12:04", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        }
}
