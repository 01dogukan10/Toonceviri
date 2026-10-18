package com.dogukan.toonceviri

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Release notundaki basit Markdown'ı (##, **kalın**, - madde) okunur metne çevirir. */
private fun notesToText(md: String): AnnotatedString = buildAnnotatedString {
    val lines = md.replace("\r", "").lines().map { it.trimEnd() }.toMutableList()
    // Sürüm adı pencere başlığında zaten var: ilk satır başlıksa atla
    if (lines.firstOrNull()?.startsWith("#") == true) lines.removeAt(0)
    while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
    while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)

    lines.forEachIndexed { idx, raw ->
        val heading = raw.startsWith("#")
        var line = if (heading) raw.trimStart('#').trim() else raw
        line = when {
            line.startsWith("- ") -> "• " + line.removePrefix("- ")
            line.startsWith("* ") -> "• " + line.removePrefix("* ")
            else -> line
        }
        line.split("**").forEachIndexed { i, part ->
            if (heading || i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
            else append(part)
        }
        if (idx < lines.lastIndex) append("\n")
    }
}

@Composable
private fun NotesText(md: String) {
    val text = remember(md) { notesToText(md) }
    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Yeni sürüm indirildiğinde: neler değiştiğini gösterir, tek dokunuşla kurar. */
@Composable
fun UpdateReadyDialog(u: UpdateState, current: String, onInstall: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("ToonÇeviri ${u.info.version} hazır") },
        text = {
            Column {
                Text(
                    "Şu an kullandığın sürüm: $current",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                NotesText(u.info.notes.ifBlank { "Bu sürüm için not eklenmemiş." })
            }
        },
        confirmButton = { Button(onClick = onInstall) { Text("Şimdi güncelle") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Sonra") } },
    )
}

/** Güncellemeden sonraki ilk açılışta: bu sürümde neyin değiştiği. */
@Composable
fun WhatsNewDialog(version: String, notes: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("$version sürümünde neler yeni") },
        text = { NotesText(notes) },
        confirmButton = { Button(onClick = onClose) { Text("Tamam") } },
    )
}

/** Kütüphanenin üstünde: güncelleme iniyor / kurulmaya hazır. */
@Composable
fun UpdateCard(u: UpdateState, onInstall: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (u.file == null) {
                Text(
                    "Yeni sürüm ${u.info.version} indiriliyor… %${(u.progress * 100).toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "ToonÇeviri ${u.info.version} kurulmaya hazır",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onInstall) { Text("Güncelle") }
                }
            }
        }
    }
}
