package com.oppo.themeinstaller

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ThemeInstallerScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeInstallerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ThemeInfo?>(null) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var status by remember { mutableStateOf("Import an OPPO .theme file to inspect it.") }
    var savedTheme by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busy = true
                status = "Inspecting theme package…"
                try {
                    val result = withContext(Dispatchers.IO) {
                        val dest = File(context.filesDir, "imported_theme.theme")
                        context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "Could not open selected file." }
                            FileOutputStream(dest).use { output -> input.copyTo(output) }
                        }
                        ThemeArchive.inspect(dest)
                    }
                    info = result.info
                    preview = result.previewFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    savedTheme = File(context.filesDir, "imported_theme.theme")
                    status = if (result.valid) {
                        "Package inspected. This does not mean ColorOS has installed it."
                    } else result.message
                } catch (e: Exception) {
                    info = null
                    preview = null
                    status = "Could not read theme: ${e.message ?: "unknown error"}"
                } finally { busy = false }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Palette, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("OPPO Theme Installer", fontWeight = FontWeight.Bold)
            }
        })
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("My Themes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Import and inspect local OPPO theme packages.")
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Checking…" else "Import .theme file")
            }

            if (preview != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(
                            bitmap = preview!!.asImageBitmap(),
                            contentDescription = "Theme preview",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                            contentScale = ContentScale.Fit
                        )
                        Text(info?.name ?: "Imported theme", style = MaterialTheme.typography.titleLarge)
                        Text("Preview extracted from the theme package.")
                    }
                }
            }

            info?.let { theme ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Theme details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Detail("Name", theme.name)
                        Detail("Description", theme.description)
                        Detail("Author", theme.author)
                        Detail("Version", theme.version)
                        Detail("Components", theme.components.ifBlank { "Not declared" })
                        Detail("Resolutions", theme.resolutions.ifBlank { "Not declared" })
                        Detail("Preview", if (preview != null) "Found" else "Not found")
                    }
                }

                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Installation status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Direct installation of third-party .theme archives is not exposed through a verified public Android/ColorOS API. Copying the archive into a Theme Store folder does not prove it is installed.")
                        Text("This app can apply the extracted preview image as a lock-screen wallpaper. It cannot install the proprietary lockstyle behavior.")
                        Button(
                            onClick = {
                                val f = ThemeArchive.extractPreview(savedTheme!!, context.cacheDir)
                                if (f != null) {
                                    try {
                                        val bmp = BitmapFactory.decodeFile(f.absolutePath)
                                        WallpaperManager.getInstance(context).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
                                        status = "Lock-screen wallpaper preview applied. The full .theme lockstyle was not installed."
                                    } catch (e: Exception) {
                                        status = "Could not set wallpaper: ${e.message}"
                                    }
                                } else status = "No preview image was found in this package."
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = preview != null && savedTheme != null
                        ) { Text("Apply preview to lock screen") }

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                val pm = context.packageManager
                                val candidates = pm.queryIntentActivities(intent, 0)
                                val themeApp = candidates.firstOrNull {
                                    val label = it.loadLabel(pm).toString().lowercase()
                                    label.contains("theme store") || label.contains("themes")
                                }
                                if (themeApp != null) {
                                    val launch = pm.getLaunchIntentForPackage(themeApp.activityInfo.packageName)
                                    if (launch != null) context.startActivity(launch) else status = "Theme Store launch intent unavailable."
                                } else {
                                    status = "Theme Store app was not detected. Open Settings > Wallpapers & style > Themes manually."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open Theme Store (if available)") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("OPPO A78 4G · ColorOS 15 (target device)")
                    }
                    Text("The exact ColorOS build and Theme Store version must be checked on the phone.")
                }
            }

            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Important: applying the preview sets only a still wallpaper image. It does not install the theme's lockscreen/lockstyle component.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        Text(value, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
