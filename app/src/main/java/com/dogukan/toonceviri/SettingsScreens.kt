@file:OptIn(ExperimentalMaterial3Api::class)

package com.dogukan.toonceviri

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.nl.translate.TranslateLanguage

@Composable
private fun folderName(folder: String?): String? {
    val ctx = LocalContext.current
    return remember(folder) {
        folder?.let { runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(it))?.name }.getOrNull() ?: "Seçili klasör" }
    }
}

// ======================= İlk açılış =======================

@Composable
fun SetupScreen(vm: MainViewModel, state: UiState) {
    val folder by vm.settings.folder.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFolder(uri)
    }
    val tr = state.models.firstOrNull { it.lang.code == TranslateLanguage.TURKISH }
    val trBusy = TranslateLanguage.TURKISH in state.modelBusy

    // Türkçe çeviri modeli ilk açılışta kendiliğinden iner
    LaunchedEffect(tr?.translate) {
        if (tr != null && !tr.translate && !trBusy) vm.downloadModel(TranslateLanguage.TURKISH, quiet = true)
    }

    Column(
        Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("ToonÇeviri'ye hoş geldin", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Başlamadan önce çevrilen bölümlerin CBZ dosyası olarak nereye kaydedileceğini seç. " +
                "Bunu yalnızca bir kez soruyoruz, sonra Ayarlar'dan değiştirebilirsin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. Kayıt klasörü", style = MaterialTheme.typography.titleSmall)
                Text(
                    folderName(folder) ?: "Henüz seçilmedi",
                    color = if (folder == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (folder == null) "Klasör seç" else "Başka klasör seç")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2. Türkçe ve İngilizce dil modeli", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        tr?.ready == true -> {
                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Hazır")
                        }
                        trBusy || tr == null -> {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("İndiriliyor (~30 MB)…")
                        }
                        else -> TextButton(onClick = { vm.downloadModel(TranslateLanguage.TURKISH) }) {
                            Text("İnemedi, tekrar dene")
                        }
                    }
                }
                Text(
                    "Korece, Japonca, Çince ve diğer diller Ayarlar → Dil modelleri'nden istediğinde indirilir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = { vm.settings.finishSetup() },
            enabled = folder != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Başla") }
        TextButton(onClick = { vm.settings.finishSetup() }, modifier = Modifier.fillMaxWidth()) {
            Text("Klasör seçmeden devam et (CBZ kaydedilmez)")
        }
    }
}

// ======================= Ayarlar =======================

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    snack: SnackbarHostState,
    onBack: () -> Unit,
    onModels: () -> Unit,
) {
    val theme by vm.settings.theme.collectAsStateWithLifecycle()
    val folder by vm.settings.folder.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFolder(uri)
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            Section("Görünüm")

            Label("Tema")
            ChipRow {
                ThemeMode.entries.forEach { m ->
                    FilterChip(
                        selected = theme.mode == m,
                        onClick = { vm.settings.setTheme(theme.copy(mode = m)) },
                        label = { Text(m.label) },
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= 31) {
                ListItem(
                    headlineContent = { Text("Duvar kâğıdından renk al") },
                    supportingContent = { Text("Android'in dinamik renklerini kullanır") },
                    trailingContent = {
                        Switch(
                            checked = theme.dynamic,
                            onCheckedChange = { vm.settings.setTheme(theme.copy(dynamic = it)) },
                        )
                    },
                )
            }

            Label(if (theme.dynamic && Build.VERSION.SDK_INT >= 31) "Vurgu rengi (dinamik renk kapalıyken)" else "Vurgu rengi")
            ChipRow {
                Accents.list.forEach { (argb, name) ->
                    val c = Color(argb)
                    val selected = theme.accent == argb
                    Box(
                        Modifier.size(40.dp)
                            .background(c, CircleShape)
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = CircleShape,
                            )
                            .clickable { vm.settings.setTheme(theme.copy(accent = argb, dynamic = false)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Filled.Check, name, tint = if (c.luminance() > 0.5f) Color.Black else Color.White)
                    }
                }
            }

            Label("Okuyucu arka planı")
            ChipRow {
                ReaderBg.entries.forEach { b ->
                    FilterChip(
                        selected = theme.readerBg == b,
                        onClick = { vm.settings.setTheme(theme.copy(readerBg = b)) },
                        label = { Text(b.label) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(top = 16.dp))
            Section("Kayıt")
            ListItem(
                headlineContent = { Text("CBZ kayıt klasörü") },
                supportingContent = { Text(folderName(folder) ?: "Seçilmedi — CBZ kaydedilmiyor") },
                trailingContent = { TextButton(onClick = { picker.launch(null) }) { Text("Değiştir") } },
            )

            HorizontalDivider()
            Section("Diller")
            ListItem(
                headlineContent = { Text("Dil modelleri") },
                supportingContent = { Text("Korece, Japonca, Çince ve diğer dilleri indir veya sil") },
                modifier = Modifier.clickable(onClick = onModels),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

// ======================= Dil modelleri =======================

@Composable
fun ModelsScreen(vm: MainViewModel, state: UiState, snack: SnackbarHostState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { vm.refreshModels() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dil modelleri") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    "Her dil için çeviri modeli yaklaşık 30 MB'tır. Korece, Japonca ve Çince için " +
                        "ayrıca yazı tanıma modeli Google Play Hizmetleri üzerinden iner. İndirilen dil internetsiz çalışır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (state.models.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(state.models, key = { it.lang.code }) { m ->
                val busy = m.lang.code in state.modelBusy
                val sourceOk = Langs.source.any { it.code == m.lang.code }
                ListItem(
                    headlineContent = { Text(m.lang.name) },
                    supportingContent = {
                        Text(
                            when {
                                busy -> "İşleniyor…"
                                m.ready && m.builtIn -> "Hazır gelir"
                                m.ready -> "İndirildi"
                                m.translate && !m.ocr -> "Yazı tanıma modeli eksik"
                                else -> if (sourceOk) "İndirilmedi" else "İndirilmedi · yalnızca hedef dil"
                            }
                        )
                    },
                    trailingContent = {
                        when {
                            busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            !m.ready -> Button(onClick = { vm.downloadModel(m.lang.code) }) { Text("İndir") }
                            !m.builtIn -> OutlinedButton(onClick = { vm.deleteModel(m.lang.code) }) { Text("Sil") }
                            else -> Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
            }
            item {
                Text(
                    "Not: Korece/Japonca/Çince yazı tanıma Google Play Hizmetleri gerektirir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
