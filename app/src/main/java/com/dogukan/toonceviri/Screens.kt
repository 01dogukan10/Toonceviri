package com.dogukan.toonceviri

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

// ======================= Kütüphane =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: MainViewModel,
    state: UiState,
    snack: SnackbarHostState,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var toDelete by remember { mutableStateOf<Chapter?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ToonÇeviri", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Ayarlar") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Yeni bölüm") },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.work?.let { w -> item(key = "work") { WorkCard(w, onCancel = vm::cancel) } }

            if (state.chapters.isEmpty() && state.work == null) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Kütüphane boş", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Bir bölüm linki yapıştır ya da galeriden sayfa seç.\nÇevrilen bölümler burada saklanır ve internetsiz okunur.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(state.chapters, key = { it.id }) { c ->
                ChapterCard(
                    vm = vm,
                    c = c,
                    onClick = { if (c.translated.isNotEmpty()) onOpen(c.id) },
                    onExport = { vm.exportChapter(c.id) },
                    onDelete = { toDelete = c },
                )
            }
        }
    }

    toDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Bölüm silinsin mi?") },
            text = { Text("\"${c.title}\" ve tüm sayfaları cihazdan silinecek.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(c.id)
                    toDelete = null
                }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun WorkCard(w: WorkState, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(w.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(w.status, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            if (w.total > 0) {
                LinearProgressIndicator(
                    progress = { w.done.toFloat() / w.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("İptal") }
            }
        }
    }
}

@Composable
private fun ChapterCard(vm: MainViewModel, c: Chapter, onClick: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val thumb = c.translated.firstOrNull()
            Box(
                Modifier.size(width = 60.dp, height = 84.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (thumb != null) {
                    AsyncImage(
                        model = vm.library.file(c.id, thumb),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${c.pageCount} sayfa · ${Langs.short(c.src)} → ${Langs.short(c.tgt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!c.complete) {
                    Text(
                        "Tamamlanmadı",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Seçenekler") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (c.complete) {
                        DropdownMenuItem(
                            text = { Text("CBZ olarak kaydet") },
                            onClick = {
                                menu = false
                                onExport()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Sil") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

// ======================= Yeni bölüm =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChapterScreen(
    vm: MainViewModel,
    state: UiState,
    initialUrl: String?,
    snack: SnackbarHostState,
    onBack: () -> Unit,
    onStarted: () -> Unit,
    onModels: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl ?: "") }
    var title by rememberSaveable { mutableStateOf("") }
    var src by rememberSaveable { mutableStateOf(vm.srcLang) }
    var tgt by rememberSaveable { mutableStateOf(vm.tgtLang) }

    LaunchedEffect(initialUrl) {
        if (initialUrl != null) url = initialUrl
    }

    val sameLang = src == tgt
    // Kaynak için çeviri + yazı tanıma, hedef için yalnızca çeviri modeli gerekir
    val readySrc = state.models.filter { it.ready }.map { it.lang.code }.toSet()
    val readyTgt = state.models.filter { it.translate }.map { it.lang.code }.toSet()
    val loaded = state.models.isNotEmpty()
    val missing = loaded && (src !in readySrc || tgt !in readyTgt)
    fun saveLangs() {
        vm.srcLang = src
        vm.tgtLang = tgt
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) {
            saveLangs()
            vm.translateUris(uris, title.trim())
            onStarted()
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Yeni bölüm") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Bölüm linki") },
                placeholder = { Text("https://…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Başlık (isteğe bağlı)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                LangPicker("Kaynak dil", src, Langs.source, readySrc, loaded) { src = it }
                TextButton(
                    onClick = {
                        if (Langs.source.any { it.code == tgt }) {
                            val t = src
                            src = tgt
                            tgt = t
                        }
                    },
                ) { Text("⇄", style = MaterialTheme.typography.titleLarge) }
                LangPicker("Hedef dil", tgt, Langs.target, readyTgt, loaded) { tgt = it }
            }
            if (sameLang) {
                Text("Kaynak ve hedef dil aynı olamaz.", color = MaterialTheme.colorScheme.error)
            }
            if (missing) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Seçilen dilin modeli indirilmemiş.",
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = onModels) { Text("İndir") }
                    }
                }
            }

            Button(
                onClick = {
                    saveLangs()
                    vm.translateUrl(url.trim(), title.trim())
                    onStarted()
                },
                enabled = url.trim().startsWith("http") && !sameLang && !missing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Linkten çevir") }

            OutlinedButton(
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = !sameLang && !missing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Galeriden sayfa seç") }

            Text(
                "• Link: bölümün okunduğu sayfanın adresi. Sayfadaki resimler indirilip sırayla çevrilir.\n" +
                    "• Bazı siteler resimleri JavaScript ile yükler; o zaman ekran görüntüsü alıp galeriden seç.\n" +
                    "• Türkçe ve İngilizce hazırdır; diğer diller Ayarlar → Dil modelleri'nden iner.\n" +
                    "• Bitince bölüm, seçtiğin klasöre CBZ olarak da kaydedilir.\n" +
                    "• Çeviri sürerken uygulamayı açık tut.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowScope.LangPicker(
    label: String,
    value: String,
    options: List<Lang>,
    ready: Set<String>,
    loaded: Boolean,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(Langs.name(value))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { l ->
                DropdownMenuItem(
                    text = {
                        Text(if (!loaded || l.code in ready) l.name else "${l.name}  · indirilmedi")
                    },
                    onClick = {
                        onPick(l.code)
                        open = false
                    },
                )
            }
        }
    }
}

// ======================= Okuyucu =======================

@Composable
fun ReaderScreen(vm: MainViewModel, state: UiState, id: String, onBack: () -> Unit) {
    val ch = state.chapters.firstOrNull { it.id == id }
    BackHandler(onBack = onBack)
    if (ch == null) {
        LaunchedEffect(id) { onBack() }
        return
    }

    var showOriginal by rememberSaveable { mutableStateOf(false) }
    var bars by remember { mutableStateOf(true) }
    val pages = if (showOriginal) ch.original else ch.translated
    val theme by vm.settings.theme.collectAsStateWithLifecycle()
    val bg = Color(theme.readerBg.argb)

    Box(Modifier.fillMaxSize().background(bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { bars = !bars })
            },
        ) {
            items(pages, key = { it }) { name ->
                val f = vm.library.file(ch.id, name)
                val ratio = remember(name) { Library.aspect(f) }
                AsyncImage(
                    model = f,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                )
            }
            item(key = "end") {
                Text(
                    if (ch.complete) "— Bölüm sonu —" else "Çeviri sürüyor…",
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(32.dp),
                )
            }
        }

        if (bars) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xCC000000))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = Color.White)
                }
                Text(
                    ch.title,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showOriginal = !showOriginal }) {
                    Text(if (showOriginal) "Çeviriyi göster" else "Orijinali göster", color = Color.White)
                }
            }
        }
    }
}
