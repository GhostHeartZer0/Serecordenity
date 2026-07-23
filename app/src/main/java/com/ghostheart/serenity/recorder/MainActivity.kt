package com.ghostheart.serenity.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

// ============================================================================
// DATA MODELS & ENUMS
// ============================================================================

enum class TargetCodec(val mime: String, val extension: String) {
    AAC(MediaFormat.MIMETYPE_AUDIO_AAC, "m4a"),
    MP3(MediaFormat.MIMETYPE_AUDIO_MPEG, "mp3"),
    FLAC(MediaFormat.MIMETYPE_AUDIO_FLAC, "flac"),
    OPUS(MediaFormat.MIMETYPE_AUDIO_OPUS, "opus"),
    AMR_WB(MediaFormat.MIMETYPE_AUDIO_AMR_WB, "3gp"),
    PCM_RAW("audio/raw", "wav")
}

enum class BluetoothCodecType(val displayName: String) {
    SBC("Subband Codec (SBC)"),
    AAC("Advanced Audio Coding (AAC)"),
    APTX("Qualcomm aptX"),
    LDAC("Sony LDAC"),
    UNKNOWN("Built-in Microphone")
}

data class RecorderConfig(
    var sampleRate: Int = 44100,
    var channelCount: Int = 1,
    var audioSource: Int = MediaRecorder.AudioSource.MIC,
    var codec: TargetCodec = TargetCodec.AAC,
    var bitrate: Int = 64000,
    var softwareGainDb: Float = 6.0f,
    var enableAEC: Boolean = true,
    var enableNS: Boolean = true,
    var enableAGC: Boolean = true
)

data class PhishTrack(
    val id: Long,
    val title: String,
    val durationMs: Long,
    val mp3Url: String,
    val showDate: String,
    val venue: String
)

data class ResumableDownloadState(
    val isDownloading: Boolean = false,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val retryCount: Int = 0,
    val statusText: String = "Idle",
    val isTranscoding: Boolean = false,
    val isCompleted: Boolean = false,
    val localFile: File? = null
)

// ============================================================================
// MAIN ACTIVITY
// ============================================================================

class MainActivity : ComponentActivity() {

    private val recorderEngine by lazy { NativeAudioRecorderEngine(this) }
    private val phishDownloader by lazy { ResumablePhishDownloader(this) }
    private var isPermissionGranted = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        isPermissionGranted.value = permissions[Manifest.permission.RECORD_AUDIO] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
                ) {
                    MainTabScreen(
                        recorderEngine = recorderEngine,
                        phishDownloader = phishDownloader,
                        hasPermission = isPermissionGranted.value,
                        onRequestPermission = { checkAndRequestPermissions() }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            isPermissionGranted.value = true
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

// ============================================================================
// MAIN TAB NAVIGATION SCREEN & HAMBURGER SETTINGS
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabScreen(
    recorderEngine: NativeAudioRecorderEngine,
    phishDownloader: ResumablePhishDownloader,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("serenity_app_prefs", Context.MODE_PRIVATE) }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        // Top App Bar with Title & Hamburger Icon Menu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🎙️", fontSize = 20.sp)
                Text(
                    "Serenity Studio",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            IconButton(onClick = { showSettingsSheet = true }) {
                Text("☰", fontSize = 24.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
            }
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF1E293B),
            contentColor = Color(0xFF38BDF8)
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Voice Recorder", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Phish.in Streamer", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Offline Notes", fontWeight = FontWeight.Bold) }
            )
        }

        when (selectedTab) {
            0 -> AudioStudioScreen(recorderEngine, hasPermission, onRequestPermission)
            1 -> PhishEngineScreen(phishDownloader)
            2 -> OfflineNotesScreen()
        }
    }

    if (showSettingsSheet) {
        AppSettingsDialog(
            prefs = prefs,
            onDismiss = { showSettingsSheet = false }
        )
    }
}

// ============================================================================
// APP & STORAGE SETTINGS DIALOG (HAMBURGER MENU)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsDialog(
    prefs: SharedPreferences,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var voiceSubfolder by remember { mutableStateOf(prefs.getString("voice_subfolder", "SerenityAudio") ?: "SerenityAudio") }
    var notesSubfolder by remember { mutableStateOf(prefs.getString("notes_subfolder", "SerenityNotes") ?: "SerenityNotes") }
    var preferredAudioSource by remember { mutableStateOf(prefs.getInt("pref_audio_source", MediaRecorder.AudioSource.MIC)) }
    var preferredSampleRate by remember { mutableStateOf(prefs.getInt("pref_sample_rate", 44100)) }
    var autoSaveNotes by remember { mutableStateOf(prefs.getBoolean("pref_auto_save_notes", true)) }
    var phishApiKey by remember { mutableStateOf(prefs.getString("phish_api_key", "") ?: "") }
    var statusMsg by remember { mutableStateOf("") }

    var audioSourceDropdownExpanded by remember { mutableStateOf(false) }
    var sampleRateDropdownExpanded by remember { mutableStateOf(false) }

    val audioSources = listOf(
        MediaRecorder.AudioSource.MIC to "Microphone (MIC)",
        MediaRecorder.AudioSource.DEFAULT to "Default Audio Source",
        MediaRecorder.AudioSource.VOICE_RECOGNITION to "Voice Recognition",
        MediaRecorder.AudioSource.VOICE_COMMUNICATION to "Voice Communication",
        MediaRecorder.AudioSource.CAMCORDER to "Camcorder Mic"
    )

    val sampleRates = listOf(44100, 48000, 22050, 16000, 8000)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⚙️ App & Storage Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                Text("✕", modifier = Modifier.clickable { onDismiss() }, color = Color.Gray, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("📁 File Storage Locations", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), fontSize = 14.sp)

                OutlinedTextField(
                    value = voiceSubfolder,
                    onValueChange = {
                        voiceSubfolder = it
                        prefs.edit().putString("voice_subfolder", it).apply()
                    },
                    label = { Text("Voice Recordings Folder (in Music/)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = notesSubfolder,
                    onValueChange = {
                        notesSubfolder = it
                        prefs.edit().putString("notes_subfolder", it).apply()
                        prefs.edit().putString("subfolder", it).apply() // Keep legacy key in sync
                    },
                    label = { Text("Offline Notes Folder (in Documents/)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF334155)))

                Text("🎙️ Microphone & Hardware Defaults", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), fontSize = 14.sp)

                // Preferred Audio Source Dropdown
                Box(modifier = Modifier.fillMaxWidth()) {
                    val currentSourceName = audioSources.find { it.first == preferredAudioSource }?.second ?: "Microphone (MIC)"
                    OutlinedButton(
                        onClick = { audioSourceDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color(0xFF64748B))
                    ) {
                        Text("Audio Source: $currentSourceName", fontSize = 12.sp, color = Color.White)
                    }
                    DropdownMenu(
                        expanded = audioSourceDropdownExpanded,
                        onDismissRequest = { audioSourceDropdownExpanded = false }
                    ) {
                        audioSources.forEach { (srcId, srcLabel) ->
                            DropdownMenuItem(
                                text = { Text(srcLabel) },
                                onClick = {
                                    preferredAudioSource = srcId
                                    prefs.edit().putInt("pref_audio_source", srcId).apply()
                                    audioSourceDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Preferred Sample Rate Dropdown
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { sampleRateDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color(0xFF64748B))
                    ) {
                        Text("Sample Rate: $preferredSampleRate Hz", fontSize = 12.sp, color = Color.White)
                    }
                    DropdownMenu(
                        expanded = sampleRateDropdownExpanded,
                        onDismissRequest = { sampleRateDropdownExpanded = false }
                    ) {
                        sampleRates.forEach { sr ->
                            DropdownMenuItem(
                                text = { Text("$sr Hz") },
                                onClick = {
                                    preferredSampleRate = sr
                                    prefs.edit().putInt("pref_sample_rate", sr).apply()
                                    sampleRateDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF334155)))

                Text("⚙️ App Behavior", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), fontSize = 14.sp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-Save Offline Notes", fontSize = 13.sp, color = Color.White)
                    Switch(
                        checked = autoSaveNotes,
                        onCheckedChange = {
                            autoSaveNotes = it
                            prefs.edit().putBoolean("pref_auto_save_notes", it).apply()
                            prefs.edit().putBoolean("auto_save", it).apply() // Sync legacy key
                        }
                    )
                }

                OutlinedTextField(
                    value = phishApiKey,
                    onValueChange = {
                        phishApiKey = it
                        prefs.edit().putString("phish_api_key", it).apply()
                        prefs.edit().putString("api_key", it).apply() // Sync legacy key
                    },
                    label = { Text("Phish.in API Key (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Button(
                    onClick = {
                        try {
                            val count = context.cacheDir.listFiles()?.count { file ->
                                file.delete()
                            } ?: 0
                            statusMsg = "Cleared $count temp files"
                        } catch (e: Exception) {
                            statusMsg = "Clear failed: ${e.message}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear Temporary Cache Files", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                if (statusMsg.isNotEmpty()) {
                    Text(statusMsg, fontSize = 11.sp, color = Color(0xFF38BDF8))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
            ) {
                Text("Save & Close", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF1E293B)
    )
}

// ============================================================================
// TAB 1: VOICE RECORDER
// ============================================================================

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AudioStudioScreen(
    recorderEngine: NativeAudioRecorderEngine,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("serenity_app_prefs", Context.MODE_PRIVATE) }
    var config by remember { mutableStateOf(RecorderConfig()) }
    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var currentPeakDb by remember { mutableStateOf(-60f) }
    var activeBtCodec by remember { mutableStateOf(BluetoothCodecType.UNKNOWN) }
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }
    var statusMessage by remember { mutableStateOf("Ready to record") }
    val coroutineScope = rememberCoroutineScope()

    var voiceFilename by remember { mutableStateOf("VoiceNote") }
    var voiceDropdownExpanded by remember { mutableStateOf(false) }
    var isVoiceLookupExpanded by remember { mutableStateOf(false) }
    var voiceLookupQuery by remember { mutableStateOf("") }
    var voiceFilesList by remember { mutableStateOf<List<File>>(emptyList()) }

    val refreshVoiceFiles = {
        val voiceSubfolder = prefs.getString("voice_subfolder", "SerenityAudio") ?: "SerenityAudio"
        val customMusicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), voiceSubfolder)
        val defaultMusicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "SerenityAudio")
        val appMusicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val cacheDirFiles = context.cacheDir.listFiles { _, name ->
            name.endsWith(".m4a") || name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".opus") || name.endsWith(".3gp") || name.endsWith(".wav")
        }?.toList() ?: emptyList()

        val customFiles = if (customMusicDir.exists()) customMusicDir.listFiles()?.toList() ?: emptyList() else emptyList()
        val defaultFiles = if (defaultMusicDir.exists()) defaultMusicDir.listFiles()?.toList() ?: emptyList() else emptyList()
        val appFiles = if (appMusicDir != null && appMusicDir.exists()) appMusicDir.listFiles()?.toList() ?: emptyList() else emptyList()

        voiceFilesList = (customFiles + defaultFiles + appFiles + cacheDirFiles).distinctBy { it.name }.sortedByDescending { it.lastModified() }
    }

    LaunchedEffect(Unit) {
        refreshVoiceFiles()
        recorderEngine.detectBluetoothCodec { codec: BluetoothCodecType -> activeBtCodec = codec }
    }

    var voiceFileToDelete by remember { mutableStateOf<File?>(null) }

    voiceFileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { voiceFileToDelete = null },
            title = { Text("Delete Voice Recording?", fontWeight = FontWeight.Bold, color = Color.White) },
            text = { Text("Are you sure you want to delete '${file.name}'?", color = Color.LightGray) },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            file.delete()
                            statusMessage = "Deleted: ${file.name}"
                        } catch (e: Exception) {
                            statusMessage = "Failed to delete: ${e.message}"
                        }
                        refreshVoiceFiles()
                        voiceFileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { voiceFileToDelete = null }) {
                    Text("Cancel")
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Voice Note Recorder", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)

        // 1. Voice Recordings Lookup Menu (Positioned in Front of Voice Recording)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        isVoiceLookupExpanded = !isVoiceLookupExpanded
                        if (isVoiceLookupExpanded) refreshVoiceFiles()
                    },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎙️ Voice Recordings Lookup Menu (${voiceFilesList.size} Saved)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "🔄 Refresh",
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF0F172A))
                                .clickable { refreshVoiceFiles() }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                        Text(if (isVoiceLookupExpanded) "▲ Hide" else "▼ Browse", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                if (isVoiceLookupExpanded) {
                    OutlinedTextField(
                        value = voiceLookupQuery,
                        onValueChange = { voiceLookupQuery = it },
                        label = { Text("Search Voice Notes (Hold to Delete)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    val filteredVoiceFiles = voiceFilesList.filter { it.name.contains(voiceLookupQuery, ignoreCase = true) }

                    if (filteredVoiceFiles.isEmpty()) {
                        Text("No voice recordings found in storage", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            filteredVoiceFiles.forEach { file ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    border = BorderStroke(1.dp, Color(0xFF334155)),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(file.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                            Text("${file.length() / 1024} KB", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Button(
                                                onClick = {
                                                    try {
                                                        MediaPlayer().apply {
                                                            setDataSource(file.absolutePath)
                                                            prepare()
                                                            start()
                                                        }
                                                        statusMessage = "Playing preview: ${file.name}"
                                                    } catch (e: Exception) {
                                                        statusMessage = "Preview failed: ${e.message}"
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text("▶ Preview", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Button(
                                                onClick = {
                                                    val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "audio/*"
                                                        putExtra(Intent.EXTRA_STREAM, fileUri as android.os.Parcelable)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(Intent.createChooser(shareIntent, "Share Voice Note"))
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text("Share", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Button(
                                                onClick = { voiceFileToDelete = file },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text("🗑️", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Status & Mic Info Card with Status Badge
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mic Hardware: ${activeBtCodec.displayName}", fontSize = 13.sp, color = Color.White)
                    val badgeText = when {
                        isRecording && isPaused -> "PAUSED"
                        isRecording -> "REC 🔴"
                        else -> "IDLE"
                    }
                    val badgeBg = when {
                        isRecording && isPaused -> Color(0xFFEAB308)
                        isRecording -> Color.Red
                        else -> Color(0xFF10B981)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(badgeText, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                Text(statusMessage, fontSize = 12.sp, color = Color(0xFF94A3B8))
            }
        }

        // 3. High-Visibility Voice Filename & Extension Selection Row
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF020617))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Voice Filename Box
                OutlinedTextField(
                    value = voiceFilename,
                    onValueChange = { voiceFilename = it },
                    label = { Text("Voice Filename", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF64748B)
                    )
                )

                // Extension Selection Box
                Box {
                    OutlinedButton(
                        onClick = { voiceDropdownExpanded = true },
                        border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("Ext: .${config.codec.extension} ▾", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }
                    DropdownMenu(
                        expanded = voiceDropdownExpanded,
                        onDismissRequest = { voiceDropdownExpanded = false }
                    ) {
                        TargetCodec.values().forEach { targetCodec ->
                            DropdownMenuItem(
                                text = { Text("${targetCodec.name} (.${targetCodec.extension})", fontWeight = FontWeight.Bold) },
                                onClick = {
                                    config = config.copy(codec = targetCodec)
                                    voiceDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // 4. Signal Level Meter
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF020617))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Signal Level", fontSize = 12.sp, color = Color.Gray)
                    Text("${currentPeakDb.toInt()} dB", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF38BDF8))
                }
                val normalizedLevel = ((currentPeakDb + 60f) / 60f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E293B))
                ) {
                    Box(
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(normalizedLevel).background(if (normalizedLevel > 0.85f) Color.Red else Color(0xFF38BDF8))
                    )
                }
            }
        }

        Text("Target Codec", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TargetCodec.values().forEach { target ->
                FilterChip(
                    selected = config.codec == target,
                    onClick = { config = config.copy(codec = target) },
                    label = { Text(target.name) }
                )
            }
        }

        Column {
            Text("Mic Software Boost: +${config.softwareGainDb.toInt()} dB", fontSize = 12.sp, color = Color.White)
            Slider(
                value = config.softwareGainDb,
                onValueChange = { config = config.copy(softwareGainDb = it) },
                valueRange = 0f..24f,
                steps = 24
            )
        }

        if (!hasPermission) {
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text("Grant Mic Permission")
            }
        } else {
            if (!isRecording) {
                Button(
                    onClick = {
                        val prefSource = prefs.getInt("pref_audio_source", MediaRecorder.AudioSource.MIC)
                        val prefSampleRate = prefs.getInt("pref_sample_rate", 44100)
                        config = config.copy(audioSource = prefSource, sampleRate = prefSampleRate)

                        isRecording = true
                        isPaused = false
                        lastSavedUri = null
                        statusMessage = "Recording active..."
                        coroutineScope.launch(Dispatchers.IO) {
                            recorderEngine.startRecording(
                                filenamePrefix = voiceFilename,
                                config = config,
                                onDbPeak = { db: Float -> currentPeakDb = db },
                                onSaved = { uri: Uri ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        lastSavedUri = uri
                                        val voiceFolder = prefs.getString("voice_subfolder", "SerenityAudio") ?: "SerenityAudio"
                                        statusMessage = "Saved to Music/$voiceFolder"
                                        refreshVoiceFiles()
                                    }
                                },
                                onError = { err: String ->
                                    statusMessage = "Error: $err"
                                    isRecording = false
                                    isPaused = false
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("🎙️ Start Recording", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Pause / Resume Button
                    Button(
                        onClick = {
                            if (isPaused) {
                                isPaused = false
                                statusMessage = "Recording active..."
                                recorderEngine.resumeRecording()
                            } else {
                                isPaused = true
                                statusMessage = "Recording paused"
                                recorderEngine.pauseRecording()
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPaused) Color(0xFF0284C7) else Color(0xFFEAB308)
                        )
                    ) {
                        Text(
                            if (isPaused) "▶️ Resume" else "⏸️ Pause",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.Black
                        )
                    }

                    // Stop & Save Button
                    Button(
                        onClick = {
                            isRecording = false
                            isPaused = false
                            recorderEngine.stopRecording()
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("⏹️ Stop & Save", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }

        val savedAudioUri = lastSavedUri
        if (savedAudioUri != null) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF065F46))) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Button(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, savedAudioUri as android.os.Parcelable)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Send Voice Note"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Share Voice Note", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ============================================================================
// TAB 2: PHISH.IN ULTRA-RESILIENT LOW-BANDWIDTH ENGINE
// ============================================================================

@Composable
fun PhishEngineScreen(phishDownloader: ResumablePhishDownloader) {
    val context = LocalContext.current
    val prefs: SharedPreferences =
        remember { context.getSharedPreferences("serenity_app_prefs", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("phish_api_key", prefs.getString("api_key", "") ?: "") ?: "") }
    var searchQuery by remember { mutableStateOf("You Enjoy Myself") }
    var tracks by remember { mutableStateOf<List<PhishTrack>>(emptyList()) }
    var isLoadingTracks by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf(ResumableDownloadState()) }
    var targetBitrateKbps by remember { mutableStateOf(32) }
    var player: MediaPlayer? by remember { mutableStateOf(null) }
    var isPlaying by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Phish.in 64kbps Resumable Engine",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                prefs.edit().putString("phish_api_key", it).apply()
                prefs.edit().putString("api_key", it).apply()
            },
            label = { Text("Phish.in API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Track / Song") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(
                onClick = {
                    isLoadingTracks = true
                    coroutineScope.launch(Dispatchers.IO) {
                        val result = phishDownloader.searchTracks(searchQuery, apiKey)
                        withContext(Dispatchers.Main) {
                            tracks = result
                            isLoadingTracks = false
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text("Search")
            }
        }

        if (isLoadingTracks) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        Text("Target Transcode Quality", fontSize = 13.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(16, 32, 64).forEach { kbps ->
                FilterChip(
                    selected = targetBitrateKbps == kbps,
                    onClick = { targetBitrateKbps = kbps },
                    label = { Text("${kbps} kbps") }
                )
            }
        }

        if (downloadState.isDownloading || downloadState.isTranscoding) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        downloadState.statusText,
                        fontSize = 12.sp,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (downloadState.totalBytes > 0) downloadState.bytesDownloaded.toFloat() / downloadState.totalBytes else 0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        val readyFile = downloadState.localFile
        if (readyFile != null && readyFile.exists()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF065F46))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Offline Audio Cached", fontWeight = FontWeight.Bold, color = Color.White)
                    Button(
                        onClick = {
                            if (isPlaying) {
                                player?.stop()
                                player?.release()
                                player = null
                                isPlaying = false
                            } else {
                                try {
                                    player = MediaPlayer().apply {
                                        setDataSource(readyFile.absolutePath)
                                        prepare()
                                        start()
                                    }
                                    isPlaying = true
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        Text(if (isPlaying) "Stop" else "Play Low-Bandwidth Stream")
                    }
                }
            }
        }

        tracks.forEach { track ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "${track.showDate} - ${track.venue}",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Button(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                phishDownloader.downloadAndCompressTrack(
                                    track,
                                    targetBitrateKbps
                                ) { state ->
                                    downloadState = state
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text("Fetch ${targetBitrateKbps}k", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 3: OFFLINE RICH-TEXT & MARKDOWN EDITOR
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OfflineNotesScreen() {
    val context = LocalContext.current
    val prefs: SharedPreferences =
        remember { context.getSharedPreferences("serenity_app_prefs", Context.MODE_PRIVATE) }
    var subfolderName by remember { mutableStateOf(prefs.getString("notes_subfolder", prefs.getString("subfolder", "SerenityNotes")) ?: "SerenityNotes") }
    var noteTitle by remember { mutableStateOf("UntitledNote") }
    var selectedFormat by remember { mutableStateOf("txt") }
    var noteContent by remember { mutableStateOf("") }
    var isBold by remember { mutableStateOf(false) }
    var isItalic by remember { mutableStateOf(false) }
    var selectedFontFamily by remember { mutableStateOf("SansSerif") }
    var selectedFontSizeSp by remember { mutableStateOf(16) }
    var statusMessage by remember { mutableStateOf("Ready") }
    var isAutoSaveEnabled by remember { mutableStateOf(prefs.getBoolean("pref_auto_save_notes", prefs.getBoolean("auto_save", true))) }
    var isLookupExpanded by remember { mutableStateOf(false) }
    var fileQuery by remember { mutableStateOf("") }
    var existingFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var previewingFileContent by remember { mutableStateOf<Pair<String, String>?>(null) }

    val formats = listOf("txt", "md", "html", "pdf")
    val fontFamilies = listOf("SansSerif", "Serif", "Monospace", "Cursive", "Arial", "TimesNewRoman", "ComicSans")
    val fontSizes = listOf(12, 14, 16, 18, 20, 24, 28, 32)

    var formatDropdownExpanded by remember { mutableStateOf(false) }
    var fontDropdownExpanded by remember { mutableStateOf(false) }
    var sizeDropdownExpanded by remember { mutableStateOf(false) }

    val refreshNotesList = {
        val notesSubfolder = prefs.getString("notes_subfolder", subfolderName) ?: "SerenityNotes"
        val docsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), notesSubfolder)
        val defaultDocsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SerenityNotes")
        val appDocsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)

        val docsFiles = if (docsDir.exists()) docsDir.listFiles()?.toList() ?: emptyList() else emptyList()
        val defaultFiles = if (defaultDocsDir.exists()) defaultDocsDir.listFiles()?.toList() ?: emptyList() else emptyList()
        val appFiles = if (appDocsDir != null && appDocsDir.exists()) appDocsDir.listFiles()?.toList() ?: emptyList() else emptyList()

        existingFiles = (docsFiles + defaultFiles + appFiles).distinctBy { it.name }.sortedByDescending { it.lastModified() }
    }

    LaunchedEffect(subfolderName) {
        refreshNotesList()
    }

    val saveCurrentNote = { isAuto: Boolean ->
        val success = saveNoteToFile(
            context = context,
            subfolder = subfolderName,
            title = noteTitle.ifBlank { "UntitledNote" },
            format = selectedFormat,
            content = noteContent,
            isBold = isBold,
            isItalic = isItalic,
            fontFamilyName = selectedFontFamily,
            fontSizeSp = selectedFontSizeSp
        )
        if (success) {
            statusMessage = if (isAuto) "Auto-saved at ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}" else "Saved successfully to Documents/$subfolderName"
            refreshNotesList()
        } else {
            statusMessage = "Save failed!"
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var autoSaveJob: Job? by remember { mutableStateOf(null) }

    val triggerAutoSave = {
        if (isAutoSaveEnabled) {
            autoSaveJob?.cancel()
            autoSaveJob = coroutineScope.launch {
                delay(1500)
                saveCurrentNote(true)
            }
        }
    }

    var fileToDelete by remember { mutableStateOf<File?>(null) }

    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete File?", fontWeight = FontWeight.Bold, color = Color.White) },
            text = { Text("Are you sure you want to delete '${file.name}'?", color = Color.LightGray) },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            file.delete()
                            statusMessage = "Deleted: ${file.name}"
                        } catch (e: Exception) {
                            statusMessage = "Failed to delete: ${e.message}"
                        }
                        refreshNotesList()
                        fileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { fileToDelete = null }) {
                    Text("Cancel")
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Offline Note Editor",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        // 1. Files Lookup Menu (Positioned in Front of Note Writing)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            isLookupExpanded = !isLookupExpanded
                            if (isLookupExpanded) refreshNotesList()
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📁 Documents Lookup Menu (${existingFiles.size} Saved)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "🔄 Refresh",
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF0F172A))
                                .clickable { refreshNotesList() }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                        Text(
                            if (isLookupExpanded) "▲ Hide" else "▼ Browse",
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (isLookupExpanded) {
                    OutlinedTextField(
                        value = fileQuery,
                        onValueChange = { fileQuery = it },
                        label = { Text("Search Documents (Hold to Delete)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    val filtered = existingFiles.filter {
                        it.name.contains(
                            fileQuery,
                            ignoreCase = true
                        )
                    }

                    if (filtered.isEmpty()) {
                        Text(
                            "No files found in Documents/$subfolderName",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            filtered.forEach { file ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val nameWithoutExt = file.nameWithoutExtension
                                            val ext = file.extension
                                            noteTitle = nameWithoutExt
                                            if (formats.contains(ext)) selectedFormat = ext
                                            try {
                                                noteContent = file.readText()
                                                statusMessage = "Loaded: ${file.name}"
                                            } catch (e: Exception) {
                                                statusMessage = "Read failed: ${e.message}"
                                            }
                                        },
                                    border = BorderStroke(1.dp, Color(0xFF334155)),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                file.name,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                "${file.length()} bytes | Click to Open",
                                                fontSize = 10.sp,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Button(
                                                onClick = {
                                                    try {
                                                        val content = file.readText()
                                                        previewingFileContent = Pair(file.name, content)
                                                    } catch (e: Exception) {
                                                        statusMessage = "Preview error: ${e.message}"
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text(
                                                    "Preview",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Button(
                                                onClick = {
                                                    val fileUri = FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        file
                                                    )
                                                    val shareIntent =
                                                        Intent(Intent.ACTION_SEND).apply {
                                                            type = when (file.extension) {
                                                                "pdf" -> "application/pdf"
                                                                "html" -> "text/html"
                                                                else -> "text/plain"
                                                            }
                                                            putExtra(
                                                                Intent.EXTRA_STREAM,
                                                                fileUri as android.os.Parcelable
                                                            )
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                    context.startActivity(
                                                        Intent.createChooser(
                                                            shareIntent,
                                                            "Share Document"
                                                        )
                                                    )
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text(
                                                    "Share",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Button(
                                                onClick = { fileToDelete = file },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text("🗑️", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Settings Bar Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Subfolder: Documents/$subfolderName",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-Save", fontSize = 12.sp, color = Color.White)
                        Switch(
                            checked = isAutoSaveEnabled,
                            onCheckedChange = {
                                isAutoSaveEnabled = it
                                prefs.edit().putBoolean("pref_auto_save_notes", it).apply()
                                prefs.edit().putBoolean("auto_save", it).apply()
                            },
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                }
                OutlinedTextField(
                    value = subfolderName,
                    onValueChange = {
                        subfolderName = it
                        prefs.edit().putString("notes_subfolder", it).apply()
                        prefs.edit().putString("subfolder", it).apply()
                        refreshNotesList()
                    },
                    label = { Text("Documents Subfolder Setting") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        // 3. High-Visibility Filename & Extension Selection Row
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF020617))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filename Box
                OutlinedTextField(
                    value = noteTitle,
                    onValueChange = {
                        noteTitle = it
                        triggerAutoSave()
                    },
                    label = {
                        Text(
                            "Filename",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF64748B)
                    )
                )

                // Extension Selection Box
                Box {
                    OutlinedButton(
                        onClick = { formatDropdownExpanded = true },
                        border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(
                                0xFF38BDF8
                            )
                        ),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text(
                            "Ext: .$selectedFormat ▾",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                    }
                    DropdownMenu(
                        expanded = formatDropdownExpanded,
                        onDismissRequest = { formatDropdownExpanded = false }
                    ) {
                        formats.forEach { fmt ->
                            DropdownMenuItem(
                                text = { Text(".$fmt", fontWeight = FontWeight.Bold) },
                                onClick = {
                                    selectedFormat = fmt
                                    formatDropdownExpanded = false
                                    triggerAutoSave()
                                }
                            )
                        }
                    }
                }
            }
        }

        // Formatting Toolbar: Bold, Italic, Font Family, Font Size
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bold Toggle Button
                IconToggleButton(
                    checked = isBold,
                    onCheckedChange = { isBold = it }
                ) {
                    Text(
                        "B",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = if (isBold) Color(0xFF38BDF8) else Color.White
                    )
                }

                // Italic Toggle Button
                IconToggleButton(
                    checked = isItalic,
                    onCheckedChange = { isItalic = it }
                ) {
                    Text(
                        "I",
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = if (isItalic) Color(0xFF38BDF8) else Color.White
                    )
                }

                // Font Family Dropdown
                Box {
                    AssistChip(
                        onClick = { fontDropdownExpanded = true },
                        label = { Text(selectedFontFamily, fontSize = 11.sp) }
                    )
                    DropdownMenu(
                        expanded = fontDropdownExpanded,
                        onDismissRequest = { fontDropdownExpanded = false }
                    ) {
                        fontFamilies.forEach { font ->
                            DropdownMenuItem(
                                text = { Text(font) },
                                onClick = {
                                    selectedFontFamily = font
                                    fontDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Font Size Dropdown
                Box {
                    AssistChip(
                        onClick = { sizeDropdownExpanded = true },
                        label = { Text("${selectedFontSizeSp}sp", fontSize = 11.sp) }
                    )
                    DropdownMenu(
                        expanded = sizeDropdownExpanded,
                        onDismissRequest = { sizeDropdownExpanded = false }
                    ) {
                        fontSizes.forEach { sz ->
                            DropdownMenuItem(
                                text = { Text("${sz}sp") },
                                onClick = {
                                    selectedFontSizeSp = sz
                                    sizeDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Editor Content Area
        val targetFontFamily = when (selectedFontFamily) {
            "Serif", "TimesNewRoman" -> FontFamily.Serif
            "Monospace" -> FontFamily.Monospace
            "Cursive", "ComicSans" -> FontFamily.Cursive
            "Arial", "SansSerif" -> FontFamily.SansSerif
            else -> FontFamily.SansSerif
        }

        OutlinedTextField(
            value = noteContent,
            onValueChange = {
                noteContent = it
                triggerAutoSave()
            },
            modifier = Modifier.fillMaxWidth().height(220.dp),
            placeholder = { Text("Write your offline note here...") },
            textStyle = TextStyle(
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                fontFamily = targetFontFamily,
                fontSize = selectedFontSizeSp.sp,
                color = Color.White
            )
        )

        // Action Row & Manual Save Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                statusMessage,
                fontSize = 11.sp,
                color = Color(0xFF94A3B8),
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = { saveCurrentNote(false) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
            ) {
                Text("Save Note", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }

    previewingFileContent?.let { (filename, text) ->
        AlertDialog(
            onDismissRequest = { previewingFileContent = null },
            title = {
                Text(
                    "Preview: $filename",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = { Text(text, color = Color.LightGray, fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = { previewingFileContent = null }) {
                    Text("Close")
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }
}

// ============================================================================
// FILE STORAGE HELPER FOR NOTES
// ============================================================================

fun saveNoteToFile(
    context: Context,
    subfolder: String,
    title: String,
    format: String,
    content: String,
    isBold: Boolean,
    isItalic: Boolean,
    fontFamilyName: String,
    fontSizeSp: Int
): Boolean {
    return try {
        val fileName = "$title.$format"
        val formattedContent = when (format) {
            "html" -> "<html><body style=\"font-family:$fontFamilyName; font-size:${fontSizeSp}px; font-weight:${if (isBold) "bold" else "normal"}; font-style:${if (isItalic) "italic" else "normal"};\"><p>$content</p></body></html>"
            "md" -> "${if (isBold) "**" else ""}${if (isItalic) "*" else ""}$content${if (isItalic) "*" else ""}${if (isBold) "**" else ""}"
            else -> content
        }

        if (format == "pdf") {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 Size
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            val paint = Paint().apply {
                textSize = fontSizeSp * 1.5f
                isFakeBoldText = isBold
                textSkewX = if (isItalic) -0.25f else 0f
            }

            var y = 60f
            formattedContent.split("\n").forEach { line ->
                canvas.drawText(line, 40f, y, paint)
                y += fontSizeSp * 2f
            }
            pdfDocument.finishPage(page)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
                    put(
                        MediaStore.Files.FileColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOCUMENTS}/$subfolder"
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Files.getContentUri("external"),
                    values
                )
                uri?.let {
                    context.contentResolver.openOutputStream(it)
                        ?.use { out -> pdfDocument.writeTo(out) }
                }
            } else {
                val docsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    subfolder
                ).apply { if (!exists()) mkdirs() }
                val destFile = File(docsDir, fileName)
                FileOutputStream(destFile).use { out -> pdfDocument.writeTo(out) }
            }
            pdfDocument.close()
        } else {
            val mime = when (format) {
                "html" -> "text/html"
                "md" -> "text/markdown"
                else -> "text/plain"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.Files.FileColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOCUMENTS}/$subfolder"
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Files.getContentUri("external"),
                    values
                )
                uri?.let {
                    context.contentResolver.openOutputStream(it)
                        ?.use { out -> out.write(formattedContent.toByteArray()) }
                }
            } else {
                val docsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    subfolder
                ).apply { if (!exists()) mkdirs() }
                val destFile = File(docsDir, fileName)
                destFile.writeText(formattedContent)
            }
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

// Helper Modifier Extension
fun Modifier.scale(scale: Float): Modifier = this.graphicsLayer(scaleX = scale, scaleY = scale)

// ============================================================================
// RESUMABLE HTTP RANGE DOWNLOADER & ON-DEVICE TRANSCODER
// ============================================================================

class ResumablePhishDownloader(private val context: Context) {

    private val chunkSize = 32 * 1024

    fun searchTracks(query: String, apiKey: String): List<PhishTrack> {
        val list = mutableListOf<PhishTrack>()
        try {
            val trimmed = query.trim()
            val encoded =
                if (trimmed.isNotBlank()) java.net.URLEncoder.encode(trimmed, "UTF-8") else ""
            val urlStr =
                if (encoded.isNotBlank()) "https://phish.in/api/v1/tracks?search=$encoded" else "https://phish.in/api/v1/tracks"
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "SerenityAudioRecorder/1.0")
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                val data = root.optJSONArray("data") ?: JSONArray()

                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    list.add(
                        PhishTrack(
                            id = obj.getLong("id"),
                            title = obj.getString("title"),
                            durationMs = obj.optLong("duration", 0L),
                            mp3Url = obj.getString("mp3"),
                            showDate = obj.optString("show_date", "Unknown Date"),
                            venue = obj.optString("venue_name", "Unknown Venue")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    suspend fun downloadAndCompressTrack(
        track: PhishTrack,
        targetBitrateKbps: Int,
        onProgress: (ResumableDownloadState) -> Unit
    ) {
        var state = ResumableDownloadState(
            isDownloading = true,
            statusText = "Initializing Chunk Stream..."
        )
        onProgress(state)

        val rawMp3File = File(context.cacheDir, "raw_phish_${track.id}.mp3")
        val compressedFile =
            File(context.cacheDir, "phish_${track.id}_${targetBitrateKbps}k.m4a")

        try {
            val totalBytes = getContentLength(track.mp3Url)
            val totalChunks = ((totalBytes + chunkSize - 1) / chunkSize).toInt()

            state = state.copy(totalBytes = totalBytes, totalChunks = totalChunks)
            onProgress(state)

            var existingBytes = if (rawMp3File.exists()) rawMp3File.length() else 0L

            while (existingBytes < totalBytes) {
                val startByte = existingBytes
                val endByte = (startByte + chunkSize - 1).coerceAtMost(totalBytes - 1)

                var chunkSuccess = false
                var retries = 0

                while (!chunkSuccess) {
                    try {
                        downloadByteRange(track.mp3Url, rawMp3File, startByte, endByte)
                        chunkSuccess = true
                        existingBytes = rawMp3File.length()

                        val currentChunk = ((existingBytes + chunkSize - 1) / chunkSize).toInt()
                        state = state.copy(
                            bytesDownloaded = existingBytes,
                            currentChunk = currentChunk,
                            statusText = "Fetching packet $currentChunk of $totalChunks..."
                        )
                        onProgress(state)

                    } catch (e: Exception) {
                        retries++
                        state = state.copy(
                            retryCount = state.retryCount + 1,
                            statusText = "Network dropped! Retrying chunk (Attempt $retries)..."
                        )
                        onProgress(state)
                        delay(2000)
                    }
                }
            }

            state = state.copy(
                isDownloading = false,
                isTranscoding = true,
                statusText = "Transcoding to $targetBitrateKbps kbps local audio..."
            )
            onProgress(state)

            transcodeToLowBitrate(rawMp3File, compressedFile, targetBitrateKbps * 1000)

            state = state.copy(
                isTranscoding = false,
                isCompleted = true,
                statusText = "Ready! Compressed from ${(totalBytes / 1024 / 1024)}MB to ${(compressedFile.length() / 1024)}KB",
                localFile = compressedFile
            )
            onProgress(state)

        } catch (e: Exception) {
            state = state.copy(isDownloading = false, statusText = "Error: ${e.message}")
            onProgress(state)
        }
    }

    private fun getContentLength(urlStr: String): Long {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = 8000
        val length = conn.contentLengthLong
        conn.disconnect()
        return if (length > 0) length else 10 * 1024 * 1024
    }

    private fun downloadByteRange(
        urlStr: String,
        destinationFile: File,
        startByte: Long,
        endByte: Long
    ) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=$startByte-$endByte")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        RandomAccessFile(destinationFile, "rw").use { raf ->
            raf.seek(startByte)
            conn.inputStream.use { input ->
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    raf.write(buffer, 0, bytesRead)
                }
            }
        }
        conn.disconnect()
    }

    private fun transcodeToLowBitrate(inputFile: File, outputFile: File, targetBitrate: Int) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                break
            }
        }

        if (trackIndex < 0) return
        extractor.selectTrack(trackIndex)

        val inputFormat = extractor.getTrackFormat(trackIndex)
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        val outputFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
        outputFormat.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer =
            MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val decoder =
            MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        var isExtractorEOS = false
        var isDecoderEOS = false

        while (!isDecoderEOS) {
            if (!isExtractorEOS) {
                val inIndex = decoder.dequeueInputBuffer(5000)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        isExtractorEOS = true
                    } else {
                        decoder.queueInputBuffer(
                            inIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            0
                        )
                        extractor.advance()
                    }
                }
            }

            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 5000)
            if (outIndex >= 0) {
                val pcmBuffer = decoder.getOutputBuffer(outIndex)!!
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    isDecoderEOS = true
                }

                val encInIndex = encoder.dequeueInputBuffer(5000)
                if (encInIndex >= 0) {
                    val encBuffer = encoder.getInputBuffer(encInIndex)!!
                    encBuffer.clear()
                    encBuffer.put(pcmBuffer)
                    encoder.queueInputBuffer(
                        encInIndex,
                        0,
                        bufferInfo.size,
                        bufferInfo.presentationTimeUs,
                        if (isDecoderEOS) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    )
                }
                decoder.releaseOutputBuffer(outIndex, false)
            }

            var encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, 5000)
            while (encOutIndex >= 0) {
                val encBuffer = encoder.getOutputBuffer(encOutIndex)!!
                if (!muxerStarted) {
                    muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                muxer.writeSampleData(muxerTrackIndex, encBuffer, bufferInfo)
                encoder.releaseOutputBuffer(encOutIndex, false)
                encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            }
        }

        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        if (muxerStarted) {
            muxer.stop(); muxer.release()
        }
        extractor.release()
    }
}

// ============================================================================
// NATIVE VOICE RECORDER ENGINE WITH PAUSE, STOP & HARDWARE FALLBACKS
// ============================================================================

class NativeAudioRecorderEngine(private val context: Context) {
    @Volatile
    private var isRecording = false
    @Volatile
    private var isPaused = false

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun detectBluetoothCodec(onCodecDetected: (BluetoothCodecType) -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            onCodecDetected(BluetoothCodecType.UNKNOWN)
            return
        }

        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.A2DP && proxy is BluetoothA2dp && proxy.connectedDevices.isNotEmpty()) {
                    onCodecDetected(BluetoothCodecType.SBC)
                } else {
                    onCodecDetected(BluetoothCodecType.UNKNOWN)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                onCodecDetected(BluetoothCodecType.UNKNOWN)
            }
        }, BluetoothProfile.A2DP)
    }

    fun pauseRecording() {
        isPaused = true
    }

    fun resumeRecording() {
        isPaused = false
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        filenamePrefix: String = "VoiceNote",
        config: RecorderConfig,
        onDbPeak: (Float) -> Unit,
        onSaved: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            isPaused = false
            val channelConfig =
                if (config.channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

            val sourcesToTry = listOf(
                config.audioSource,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.CAMCORDER
            ).distinct()

            val ratesToTry = listOf(config.sampleRate, 44100, 48000, 16000, 22050, 8000).distinct()

            var initializedRecord: AudioRecord? = null
            var chosenBufferSize = 0
            var actualSampleRate = config.sampleRate

            sourceLoop@ for (src in sourcesToTry) {
                for (rate in ratesToTry) {
                    val minBufferSize = AudioRecord.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                    if (minBufferSize <= 0) continue
                    val bufferSize = minBufferSize * 2
                    try {
                        val rec = AudioRecord(
                            src,
                            rate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                        )
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            initializedRecord = rec
                            chosenBufferSize = bufferSize
                            actualSampleRate = rate
                            break@sourceLoop
                        } else {
                            rec.release()
                        }
                    } catch (e: Exception) {
                        // try next candidate
                    }
                }
            }

            if (initializedRecord == null) {
                onError("Microphone hardware error: AudioRecord initialization failed across all audio sources & sample rates.")
                return
            }

            audioRecord = initializedRecord
            val audioSessionId = audioRecord?.audioSessionId ?: 0

            if (audioSessionId != 0) {
                if (config.enableNS && NoiseSuppressor.isAvailable()) {
                    try { noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true } } catch (e: Exception) {}
                }
                if (config.enableAEC && AcousticEchoCanceler.isAvailable()) {
                    try { echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true } } catch (e: Exception) {}
                }
                if (config.enableAGC && AutomaticGainControl.isAvailable()) {
                    try { automaticGainControl = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true } } catch (e: Exception) {}
                }
            }

            val prefix = filenamePrefix.trim().ifEmpty { "VoiceNote" }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val tempFile = File(context.cacheDir, "${prefix}_$timeStamp.${config.codec.extension}")

            audioRecord?.startRecording()
            isRecording = true

            val activeConfig = config.copy(sampleRate = actualSampleRate)

            if (config.codec == TargetCodec.PCM_RAW) {
                recordWavFile(tempFile, activeConfig, chosenBufferSize, onDbPeak)
            } else {
                val success = recordMediaCodec(tempFile, activeConfig, chosenBufferSize, onDbPeak)
                if (!success || !tempFile.exists() || tempFile.length() <= 44) {
                    val fallbackWav = File(context.cacheDir, "${prefix}_$timeStamp.wav")
                    recordWavFile(fallbackWav, activeConfig, chosenBufferSize, onDbPeak)
                    if (fallbackWav.exists() && fallbackWav.length() > 44) {
                        fallbackWav.copyTo(tempFile, overwrite = true)
                        fallbackWav.delete()
                    }
                }
            }

            val prefs = context.getSharedPreferences("serenity_app_prefs", Context.MODE_PRIVATE)
            val targetSubfolder = prefs.getString("voice_subfolder", "SerenityAudio") ?: "SerenityAudio"

            val savedUri = exportToPublicMusicFolder(context, tempFile, prefix, timeStamp, config.codec, targetSubfolder)
            if (savedUri != null) onSaved(savedUri) else onError("Save failed")
        } catch (e: Exception) {
            onError(e.message ?: "Recording failed")
            stopRecording()
        }
    }

    private fun exportToPublicMusicFolder(
        context: Context,
        tempFile: File,
        prefix: String,
        timeStamp: String,
        codec: TargetCodec,
        subfolder: String = "SerenityAudio"
    ): Uri? {
        val fileName = "${prefix}_$timeStamp.${codec.extension}"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, codec.mime)
                put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MUSIC}/$subfolder"
                )
            }
            context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?.also { uri ->
                    context.contentResolver.openOutputStream(uri)
                        ?.use { out -> tempFile.inputStream().use { it.copyTo(out) } }
                }
        } else {
            val musicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                subfolder
            ).apply { if (!exists()) mkdirs() }
            val destFile = File(musicDir, fileName)
            tempFile.copyTo(destFile, overwrite = true)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
        }
    }

    private fun recordWavFile(
        outputFile: File,
        config: RecorderConfig,
        bufferSize: Int,
        onDbPeak: (Float) -> Unit
    ) {
        val rawPcmFile = File(context.cacheDir, "temp_raw.pcm")
        var totalAudioLen = 0L
        val gainFactor = 10.0f.pow(config.softwareGainDb / 20.0f)
        val sampleRate = config.sampleRate
        val channels = config.channelCount

        try {
            FileOutputStream(rawPcmFile).use { out ->
                val pcmBuffer = ByteArray(bufferSize)
                var errorRetries = 0
                while (isRecording) {
                    if (isPaused) {
                        onDbPeak(-60f)
                        try { Thread.sleep(50) } catch (e: Exception) {}
                        continue
                    }

                    val bytesRead = audioRecord?.read(pcmBuffer, 0, bufferSize) ?: 0
                    if (bytesRead > 0) {
                        errorRetries = 0
                        var i = 0
                        var maxVal = 0
                        while (i < bytesRead - 1) {
                            val low = pcmBuffer[i].toInt() and 0xFF
                            val high = pcmBuffer[i + 1].toInt()
                            val sampleShort = ((high shl 8) or low).toShort()
                            var sample = (sampleShort.toInt() * gainFactor).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            val absVal = Math.abs(sample)
                            if (absVal > maxVal) maxVal = absVal
                            pcmBuffer[i] = (sample and 0xFF).toByte()
                            pcmBuffer[i + 1] = ((sample shr 8) and 0xFF).toByte()
                            i += 2
                        }

                        val db = if (maxVal > 0) 20 * log10(maxVal / 32768.0f) else -60f
                        onDbPeak(db)

                        out.write(pcmBuffer, 0, bytesRead)
                        totalAudioLen += bytesRead
                    } else if (bytesRead < 0) {
                        errorRetries++
                        if (errorRetries > 5) break
                        try { Thread.sleep(10) } catch (e: Exception) {}
                    }
                }
            }

            writeWavHeader(rawPcmFile, outputFile, totalAudioLen, sampleRate, channels)
            rawPcmFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeWavHeader(
        pcmFile: File,
        wavFile: File,
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte(); header[33] = 0
        header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        try {
            FileOutputStream(wavFile).use { out ->
                out.write(header, 0, 44)
                if (pcmFile.exists()) {
                    pcmFile.inputStream().use { input -> input.copyTo(out) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun recordMediaCodec(
        outputFile: File,
        config: RecorderConfig,
        bufferSize: Int,
        onDbPeak: (Float) -> Unit
    ): Boolean {
        var muxerStarted = false
        var totalBytesProcessed = 0L

        try {
            val encoderMime = when (config.codec) {
                TargetCodec.MP3, TargetCodec.PCM_RAW -> MediaFormat.MIMETYPE_AUDIO_AAC
                else -> config.codec.mime
            }
            val mediaCodec = try {
                MediaCodec.createEncoderByType(encoderMime)
            } catch (e: Exception) {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            }

            val format = MediaFormat.createAudioFormat(encoderMime, config.sampleRate, config.channelCount)
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            if (encoderMime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                format.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
            }

            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var audioTrackIndex = -1

            val bufferInfo = MediaCodec.BufferInfo()
            val pcmBuffer = ByteArray(bufferSize)
            val gainFactor = 10.0f.pow(config.softwareGainDb / 20.0f)
            val bytesPerSec = config.sampleRate * config.channelCount * 2

            var errorRetries = 0

            while (isRecording) {
                if (isPaused) {
                    onDbPeak(-60f)
                    try { Thread.sleep(50) } catch (e: Exception) {}
                    continue
                }

                val bytesRead = audioRecord?.read(pcmBuffer, 0, bufferSize) ?: 0
                if (bytesRead > 0) {
                    errorRetries = 0
                    var i = 0
                    var maxVal = 0
                    while (i < bytesRead - 1) {
                        val low = pcmBuffer[i].toInt() and 0xFF
                        val high = pcmBuffer[i + 1].toInt()
                        val sampleShort = ((high shl 8) or low).toShort()
                        var sample = (sampleShort.toInt() * gainFactor).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        val absVal = Math.abs(sample)
                        if (absVal > maxVal) maxVal = absVal
                        pcmBuffer[i] = (sample and 0xFF).toByte()
                        pcmBuffer[i + 1] = ((sample shr 8) and 0xFF).toByte()
                        i += 2
                    }

                    val db = if (maxVal > 0) 20 * log10(maxVal / 32768.0f) else -60f
                    onDbPeak(db)

                    val presentationTimeUs = (totalBytesProcessed * 1_000_000L) / bytesPerSec
                    totalBytesProcessed += bytesRead

                    val inputIndex = mediaCodec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = mediaCodec.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(pcmBuffer, 0, bytesRead)
                        mediaCodec.queueInputBuffer(
                            inputIndex,
                            0,
                            bytesRead,
                            presentationTimeUs,
                            0
                        )
                    }

                    var outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000)
                    while (outputIndex >= 0) {
                        val outputBuffer = mediaCodec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            if (!muxerStarted) {
                                audioTrackIndex = muxer.addTrack(mediaCodec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            muxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                        }
                        mediaCodec.releaseOutputBuffer(outputIndex, false)
                        outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
                    }
                } else if (bytesRead < 0) {
                    errorRetries++
                    if (errorRetries > 5) break
                    try { Thread.sleep(10) } catch (e: Exception) {}
                }
            }

            try {
                mediaCodec.stop()
                mediaCodec.release()
                if (muxerStarted) {
                    muxer.stop(); muxer.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return muxerStarted && outputFile.exists() && outputFile.length() > 100
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun stopRecording() {
        isRecording = false
        isPaused = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            noiseSuppressor?.release()
            echoCanceler?.release()
            automaticGainControl?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

