package dev.novacain.ntclocks

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.novacain.ntclocks.anim.AnimPrefs
import dev.novacain.ntclocks.anim.ColorPrefs
import dev.novacain.ntclocks.anim.DepthPrefs
import dev.novacain.ntclocks.anim.GlassPrefs
import dev.novacain.ntclocks.anim.HomePrefs
import dev.novacain.ntclocks.anim.LucentPrefs
import dev.novacain.ntclocks.anim.NavPrefs
import dev.novacain.ntclocks.anim.UnlockAnim
import dev.novacain.ntclocks.anim.WidgetPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Вкладка «Профили / Бэкап»: список сохранённых профилей, создание, переименование, удаление,
 * экспорт в файл (Downloads), импорт из файла, применение профиля.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProfileTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(Profile.list(ctx)) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<String?>(null) }

    // File picker for import
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val file = copyUriToCache(ctx, uri)
                file?.let { f ->
                    BackupManager.importProfile(ctx, f)?.let { profile ->
                        Profile.save(ctx, profile)
                        profiles = Profile.list(ctx)
                        ctx.runOnUiThread { Toast.makeText(ctx, s(R.string.profile_imported, profile.name), Toast.LENGTH_SHORT).show() }
                    } ?: ctx.runOnUiThread { Toast.makeText(ctx, s(R.string.profile_import_failed), Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    fun refresh() { profiles = Profile.list(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Group(s(R.string.profile_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s(R.string.profile_subtitle), style = t(15), modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { showCreateDialog = true; newProfileName = "" }) {
                    Text(s(R.string.profile_create))
                }
            }
            Text(s(R.string.profile_hint), style = t(12, c = Dim), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        }

        if (profiles.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(s(R.string.profile_empty), style = t(15, c = Dim), textAlign = androidx.compose.ui.text.TextAlign.Center)
                }
            }
        } else {
            profiles.forEach { profile ->
                ProfileCard(
                    profile = profile,
                    isApplying = applying == profile.name,
                    onApply = {
                        applying = profile.name
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { BackupManager.applyProfile(ctx, profile) }
                            ctx.runOnUiThread {
                                applying = null
                                Toast.makeText(ctx, if (ok) s(R.string.profile_applied, profile.name) else s(R.string.profile_apply_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onExport = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { BackupManager.exportToDownloads(ctx, profile) }
                            ctx.runOnUiThread {
                                file?.let { f ->
                                    Toast.makeText(ctx, s(R.string.profile_exported, f.name), Toast.LENGTH_LONG).show()
                                } ?: Toast.makeText(ctx, s(R.string.profile_export_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onRename = {
                        renameTarget = profile.name
                        renameInput = profile.name
                    },
                    onDelete = {
                        Profile.delete(ctx, profile.name)
                        refresh()
                    }
                )
            }
        }

        Group(s(R.string.profile_import_section)) {
            FilledTonalButton(onClick = { pickFile.launch(arrayOf("application/gzip", "application/json", "application/octet-stream")) }) {
                Text(s(R.string.profile_import))
            }
            Text(s(R.string.profile_import_hint), style = t(12, c = Dim), modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(110.dp))
    }

    // Create profile dialog
    if (showCreateDialog) {
        ProfileDialog(
            title = s(R.string.profile_create),
            input = newProfileName,
            onInputChange = { newProfileName = it },
            onConfirm = {
                if (newProfileName.trim().isNotEmpty()) {
                    val profile = BackupManager.createProfile(ctx, newProfileName.trim())
                    Profile.save(ctx, profile)
                    refresh()
                    showCreateDialog = false
                }
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    // Rename dialog
    if (renameTarget != null) {
        ProfileDialog(
            title = s(R.string.profile_rename),
            input = renameInput,
            onInputChange = { renameInput = it },
            onConfirm = {
                if (renameInput.trim().isNotEmpty() && renameInput != renameTarget) {
                    if (Profile.rename(ctx, renameTarget!!, renameInput.trim())) {
                        refresh()
                    } else {
                        Toast.makeText(ctx, s(R.string.profile_name_exists), Toast.LENGTH_SHORT).show()
                    }
                }
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    isApplying: Boolean,
    onApply: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        onClick = onApply
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    if (isApplying) {
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    s(R.string.profile_updated, BackupManager.formatDate(profile.created)),
                    style = t(12, c = Dim), modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onExport) {
                    Icon(ExportIcon, s(R.string.profile_export))
                }
                IconButton(onClick = onRename) {
                    Icon(EditIcon, s(R.string.profile_rename))
                }
                IconButton(onClick = onDelete) {
                    Icon(DeleteIcon, s(R.string.profile_delete))
                }
            }
        }
    }
}

@Composable
private fun ProfileDialog(
    title: String,
    input: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onConfirm) { Text(s(R.string.action_ok)) } },
        dismissButton = { Button(onClick = onDismiss) { Text(s(R.string.action_cancel)) } },
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onSurface) },
        text = {
            TextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text(s(R.string.profile_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    )
}

// Icons
private val ExportIcon by lazy { icon("export", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }
private val EditIcon by lazy { icon("edit", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z") }
private val DeleteIcon by lazy { icon("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z") }

private fun icon(name: String, path: String): ImageVector {
    return androidx.compose.ui.graphics.vector.ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(path).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
        .build()
}

/** Копировать URI выбранного файла в кэш приложения для чтения. */
private suspend fun copyUriToCache(ctx: android.content.Context, uri: Uri): File? = withContext(Dispatchers.IO) {
    val cacheFile = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.drp")
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        cacheFile.outputStream().use { output -> input.copyTo(output) }
    } ?: return@withContext null
    cacheFile
}