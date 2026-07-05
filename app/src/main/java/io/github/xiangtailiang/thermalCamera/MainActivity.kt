package io.github.xiangtailiang.thermalCamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.xiangtailiang.thermalCamera.sdk.ImageEnhancementSettings
import io.github.xiangtailiang.thermalCamera.sdk.PaletteMode
import io.github.xiangtailiang.thermalCamera.sdk.ThermalCameraController
import io.github.xiangtailiang.thermalCamera.sdk.ThermalCameraState
import io.github.xiangtailiang.thermalCamera.sdk.ThermalUsbDevice
import io.github.xiangtailiang.thermalCamera.sdk.ThermometryBasicSettings
import io.github.xiangtailiang.thermalCamera.ui.theme.ThermalCameraTheme
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AppBackground = Color(0xFF0B0F12)
private val Panel = Color(0xFF141A1F)
private val PanelSoft = Color(0xFF192126)
private val Line = Color(0xFF29343B)
private val TextPrimary = Color(0xFFF4F7F8)
private val TextSecondary = Color(0xFF9EADB5)
private val Teal = Color(0xFF16A6A6)
private val TealStrong = Color(0xFF19BFC0)
private val Amber = Color(0xFFE6B450)
private val Danger = Color(0xFFE45B5B)

class MainActivity : ComponentActivity() {
    private lateinit var controller: ThermalCameraController
    private var initialized = false
    private var startAfterPermission = false
    private var previewTextureView: TextureView? = null
    private var pendingStorageAction: (() -> Unit)? = null
    private var videoRecorder: ThermalVideoRecorder? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startController()
        } else {
            controller.setStatus("需要允许相机权限，才能扫描 4117 USB 视频设备")
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingStorageAction
        pendingStorageAction = null
        if (granted && action != null) {
            action()
        } else if (!granted) {
            controller.setStatus("需要存储权限才能保存文件")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ThermalCameraController(this)
        controller.registerUsbReceiver()
        setContent {
            ThermalCameraTheme {
                ThermalCameraApp(
                    controller = controller,
                    onRefresh = ::refreshAfterCameraPermission,
                    onPreviewViewChanged = { previewTextureView = it },
                    onSaveScreenshot = ::requestPreviewScreenshot,
                    onStartRecording = ::requestStartVideoRecording,
                    onStopRecording = ::stopVideoRecording,
                    onStopPreview = ::stopPreviewFromUi,
                    onLogout = ::logoutFromUi,
                    onFullscreenChanged = ::setImmersivePreview,
                )
            }
        }
        refreshAfterCameraPermission()
    }

    override fun onDestroy() {
        setImmersivePreview(false)
        controller.unregisterUsbReceiver()
        controller.cleanup()
        super.onDestroy()
    }

    private fun refreshAfterCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startController()
        } else if (!startAfterPermission) {
            startAfterPermission = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            controller.setStatus("请在系统权限中允许相机权限后再扫描设备")
        }
    }

    private fun startController() {
        startAfterPermission = false
        if (initialized) {
            controller.refreshDevices()
        } else {
            initialized = true
            controller.initialize()
        }
    }

    private fun requestPreviewScreenshot() {
        requestStoragePermissionIfNeeded(::savePreviewScreenshot)
    }

    private fun requestStartVideoRecording() {
        requestStoragePermissionIfNeeded(::startVideoRecording)
    }

    private fun requestStoragePermissionIfNeeded(action: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStorageAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        action()
    }

    private fun savePreviewScreenshot() {
        val textureView = previewTextureView
        if (textureView == null || !textureView.isAvailable) {
            controller.setStatus("当前没有可保存的预览画面")
            return
        }

        val bitmap = textureView.bitmap
        if (bitmap == null || bitmap.width <= 1 || bitmap.height <= 1) {
            controller.setStatus("截图失败: 预览画面尚未就绪")
            return
        }

        val fileName = "thermal_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                saveBitmapToMediaStore(bitmap, fileName)
            } else {
                saveBitmapToLegacyPictures(bitmap, fileName)
            }
            controller.setStatus("截图已保存: Pictures/ThermalCamera/$fileName")
        } catch (e: Exception) {
            controller.setStatus("截图保存失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, fileName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ThermalCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建相册文件")
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
            } ?: error("无法打开相册文件")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveBitmapToLegacyPictures(bitmap: Bitmap, fileName: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ThermalCamera")
        check(dir.exists() || dir.mkdirs()) { "无法创建截图目录" }
        val file = File(dir, fileName)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
        }
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/png"), null)
    }

    private fun startVideoRecording() {
        if (!controller.state.value.previewing) {
            controller.setStatus("请先启动预览再录像")
            return
        }
        if (videoRecorder != null) return

        runCatching {
            val recorder = ThermalVideoRecorder(this)
            val fileName = recorder.start()
            videoRecorder = recorder
            controller.setFrameSink(recorder::drawFrame)
            controller.setRecording(true, "正在录制视频: $fileName")
        }.onFailure { error ->
            videoRecorder?.cancel()
            videoRecorder = null
            controller.setFrameSink(null)
            controller.setRecording(false, "录像启动失败: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun stopVideoRecording() {
        val recorder = videoRecorder ?: return
        controller.setFrameSink(null)
        runCatching {
            val fileName = recorder.stop()
            controller.setRecording(false, "视频已保存: Movies/ThermalCamera/$fileName")
        }.onFailure { error ->
            recorder.cancel()
            controller.setRecording(false, "视频保存失败: ${error.message ?: error.javaClass.simpleName}")
        }
        videoRecorder = null
    }

    private fun stopPreviewFromUi() {
        stopVideoRecording()
        controller.stopPreview()
    }

    private fun logoutFromUi() {
        stopVideoRecording()
        controller.logout()
    }

    private fun setImmersivePreview(enabled: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun ThermalCameraApp(
    controller: ThermalCameraController,
    onRefresh: () -> Unit,
    onPreviewViewChanged: (TextureView?) -> Unit,
    onSaveScreenshot: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onStopPreview: () -> Unit,
    onLogout: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
) {
    val state by controller.state.collectAsState()
    var fullScreenPreview by remember { mutableStateOf(false) }
    var previewLocked by remember { mutableStateOf(false) }
    var enhancementPanelVisible by remember { mutableStateOf(false) }
    var thermometryPanelVisible by remember { mutableStateOf(false) }
    var restartPreviewAfterLayoutChange by remember { mutableStateOf(false) }

    LaunchedEffect(state.previewing, state.loggedIn, state.recording) {
        if (state.recording && (!state.previewing || !state.loggedIn)) {
            onStopRecording()
        }
    }

    LaunchedEffect(fullScreenPreview) {
        onFullscreenChanged(fullScreenPreview)
        if (!fullScreenPreview) previewLocked = false
        if (fullScreenPreview) {
            enhancementPanelVisible = false
            thermometryPanelVisible = false
        }
    }

    LaunchedEffect(state.previewing) {
        if (state.previewing) restartPreviewAfterLayoutChange = false
    }

    BackHandler(enabled = fullScreenPreview) {
        if (previewLocked) {
            previewLocked = false
        } else {
            restartPreviewAfterLayoutChange = state.previewing
            fullScreenPreview = false
        }
    }

    BackHandler(enabled = enhancementPanelVisible && !fullScreenPreview) {
        enhancementPanelVisible = false
    }

    BackHandler(enabled = thermometryPanelVisible && !fullScreenPreview) {
        thermometryPanelVisible = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBackground,
    ) {
        if (fullScreenPreview) {
            FullscreenPreviewScreen(
                state = state,
                onStartPreview = controller::startPreview,
                onStopPreview = onStopPreview,
                onPreviewViewChanged = onPreviewViewChanged,
                onSaveScreenshot = onSaveScreenshot,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                locked = previewLocked,
                onToggleLock = { previewLocked = !previewLocked },
                onExitFullscreen = {
                    restartPreviewAfterLayoutChange = state.previewing
                    fullScreenPreview = false
                },
                autoStartPreview = restartPreviewAfterLayoutChange,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TopBar(state)
                    PreviewPanel(
                        modifier = Modifier.weight(1f),
                        state = state,
                        onStartPreview = controller::startPreview,
                        onStopPreview = onStopPreview,
                        onPreviewViewChanged = onPreviewViewChanged,
                        onEnterFullscreen = {
                            restartPreviewAfterLayoutChange = state.previewing
                            fullScreenPreview = true
                        },
                        autoStartPreview = restartPreviewAfterLayoutChange,
                    )
                    PaletteStrip(
                        state = state,
                        onSelect = controller::setPaletteMode,
                    )
                    ActionDock(
                        state = state,
                        onRefresh = onRefresh,
                        onLogin = controller::login,
                        onLogout = onLogout,
                        onSaveScreenshot = onSaveScreenshot,
                        onStartRecording = onStartRecording,
                        onStopRecording = onStopRecording,
                        enhancementPanelVisible = enhancementPanelVisible,
                        thermometryPanelVisible = thermometryPanelVisible,
                        onToggleEnhancementPanel = {
                            enhancementPanelVisible = !enhancementPanelVisible
                            if (enhancementPanelVisible) thermometryPanelVisible = false
                        },
                        onToggleThermometryPanel = {
                            thermometryPanelVisible = !thermometryPanelVisible
                            if (thermometryPanelVisible) enhancementPanelVisible = false
                        },
                    )
                    DeviceShelf(
                        state = state,
                        onSelect = controller::selectDevice,
                    )
                }

                if (enhancementPanelVisible) {
                    FloatingEnhancementPanel(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(12.dp),
                        state = state,
                        onClose = { enhancementPanelVisible = false },
                        onManualCorrect = controller::manualCorrect,
                        onBackgroundCorrect = controller::backgroundCorrect,
                        onRefreshImageEnhancement = controller::refreshImageEnhancement,
                        onApplyImageEnhancement = controller::setImageEnhancement,
                    )
                }

                if (thermometryPanelVisible) {
                    FloatingThermometryPanel(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(12.dp),
                        state = state,
                        onClose = { thermometryPanelVisible = false },
                        onRefresh = controller::refreshThermometryBasic,
                        onApply = controller::setThermometryBasic,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenPreviewScreen(
    state: ThermalCameraState,
    onStartPreview: (android.view.Surface, Int, Int) -> Unit,
    onStopPreview: () -> Unit,
    onPreviewViewChanged: (TextureView?) -> Unit,
    onSaveScreenshot: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    locked: Boolean,
    onToggleLock: () -> Unit,
    onExitFullscreen: () -> Unit,
    autoStartPreview: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        PreviewPanel(
            modifier = Modifier.fillMaxSize(),
            state = state,
            onStartPreview = onStartPreview,
            onStopPreview = onStopPreview,
            onPreviewViewChanged = onPreviewViewChanged,
            autoStartPreview = autoStartPreview,
            showPreviewControls = !locked,
            fullBleed = true,
        )

        if (locked) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 22.dp),
                color = Color.Black.copy(alpha = 0.42f),
                contentColor = Amber,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, Amber.copy(alpha = 0.58f)),
                onClick = onToggleLock,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = "解锁预览", modifier = Modifier.size(20.dp))
                    Text("已锁定", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconButton(
                    icon = Icons.Filled.FullscreenExit,
                    description = "退出全屏",
                    onClick = onExitFullscreen,
                )
                StateBadge(
                    text = if (state.recording) "REC" else if (state.previewing) "LIVE" else "READY",
                    color = if (state.recording || state.previewing) Danger else TealStrong,
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconButton(
                    icon = Icons.Filled.PhotoCamera,
                    description = "保存截图",
                    enabled = state.previewing,
                    onClick = onSaveScreenshot,
                )
                FloatingIconButton(
                    icon = if (state.recording) Icons.Filled.Stop else Icons.Filled.Videocam,
                    description = if (state.recording) "停止录像" else "开始录像",
                    enabled = state.previewing || state.recording,
                    color = if (state.recording) Danger else TextPrimary,
                    onClick = if (state.recording) onStopRecording else onStartRecording,
                )
                FloatingIconButton(
                    icon = Icons.Filled.LockOpen,
                    description = "锁定预览",
                    color = Amber,
                    onClick = onToggleLock,
                )
            }
        }
    }
}

@Composable
private fun TopBar(state: ThermalCameraState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "4117 热成像",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${state.status} · MJPEG · SDK ${state.sdkVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StateBadge(
            text = if (state.previewing) "LIVE" else if (state.loggedIn) "READY" else "USB",
            color = if (state.previewing) Danger else if (state.loggedIn) TealStrong else Amber,
        )
    }
}

@Composable
private fun StateBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.FiberManualRecord,
                contentDescription = null,
                modifier = Modifier.size(8.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PaletteStrip(
    state: ThermalCameraState,
    onSelect: (PaletteMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "调色板",
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.paletteMode.label,
                color = if (state.loggedIn) TealStrong else TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(PaletteMode.all) { mode ->
                PaletteChip(
                    mode = mode,
                    selected = mode == state.paletteMode,
                    enabled = state.loggedIn,
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}

@Composable
private fun PaletteChip(
    mode: PaletteMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .height(36.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (selected) Color(0xFF18383B) else Panel,
        contentColor = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.48f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(
            1.dp,
            if (selected) TealStrong.copy(alpha = 0.75f) else Line,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaletteSwatch(mode, enabled)
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PaletteSwatch(mode: PaletteMode, enabled: Boolean) {
    val colors = when (mode.id) {
        1 -> listOf(Color.White, Color(0xFFB9C0C8))
        2 -> listOf(Color.Black, Color(0xFF5B6570))
        10 -> listOf(Color(0xFF32236F), Color(0xFFFFB14A))
        11 -> listOf(Color(0xFF6A00A8), Color(0xFF00B6FF), Color(0xFFFFFF2D))
        12 -> listOf(Color(0xFF111B3A), Color(0xFFFF5A36))
        13 -> listOf(Color(0xFF2A0055), Color(0xFFE44D2E))
        14 -> listOf(Color(0xFF32105B), Color(0xFFFF7A18))
        15 -> listOf(Color(0xFF2B1D14), Color(0xFFC18954))
        16 -> listOf(Color(0xFF004B84), Color(0xFF21D19F))
        17 -> listOf(Color(0xFF203E8F), Color(0xFFE858C3))
        18 -> listOf(Color(0xFF1B6CFF), Color(0xFFFF522E))
        19 -> listOf(Color(0xFF14345F), Color(0xFF57C7FF))
        20 -> listOf(Color(0xFF250808), Color(0xFFFF3030))
        21 -> listOf(Color(0xFF052414), Color(0xFF24D66D))
        22 -> listOf(Color(0xFF061229), Color(0xFF2459C9))
        else -> listOf(Teal, TealStrong)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(999.dp)),
    ) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(width = 8.dp, height = 15.dp)
                    .background(if (enabled) color else color.copy(alpha = 0.35f)),
            )
        }
    }
}

@Composable
private fun PreviewPanel(
    modifier: Modifier = Modifier,
    state: ThermalCameraState,
    onStartPreview: (android.view.Surface, Int, Int) -> Unit,
    onStopPreview: () -> Unit,
    onPreviewViewChanged: (TextureView?) -> Unit,
    onEnterFullscreen: (() -> Unit)? = null,
    autoStartPreview: Boolean = false,
    showPreviewControls: Boolean = true,
    fullBleed: Boolean = false,
) {
    var previewSurface by remember { mutableStateOf<android.view.Surface?>(null) }
    var width by remember { mutableIntStateOf(240) }
    var height by remember { mutableIntStateOf(320) }

    LaunchedEffect(previewSurface, autoStartPreview, state.loggedIn, state.previewing, width, height) {
        val surface = previewSurface
        if (autoStartPreview && surface != null && state.loggedIn && !state.previewing) {
            onStartPreview(surface, width, height)
        }
    }

    Box(
        modifier = if (fullBleed) {
            modifier
                .fillMaxWidth()
                .background(Color.Black)
        } else {
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .border(1.dp, Line, RoundedCornerShape(8.dp))
        },
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val frameAspectRatio = 240f / 320f
            val viewAspectRatio = if (maxHeight.value > 0f) {
                maxWidth.value / maxHeight.value
            } else {
                frameAspectRatio
            }
            val previewModifier = if (viewAspectRatio > frameAspectRatio) {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(frameAspectRatio)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(frameAspectRatio)
            }

            AndroidView(
                modifier = previewModifier,
                factory = { context ->
                    TextureView(context).apply {
                        onPreviewViewChanged(this)
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                                previewSurface?.release()
                                previewSurface = android.view.Surface(surface)
                                width = w.coerceAtLeast(1)
                                height = h.coerceAtLeast(1)
                            }

                            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {
                                width = w.coerceAtLeast(1)
                                height = h.coerceAtLeast(1)
                            }

                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                onStopPreview()
                                previewSurface?.release()
                                previewSurface = null
                                onPreviewViewChanged(null)
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                            }
                        }
                    }
                },
            )
        }

        if (onEnterFullscreen != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            ) {
                FloatingIconButton(
                    icon = Icons.Filled.Fullscreen,
                    description = "全屏预览",
                    enabled = state.loggedIn || state.previewing,
                    onClick = onEnterFullscreen,
                )
            }
        }

        if (showPreviewControls && (state.loggedIn || state.previewing)) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
            ) {
                if (state.previewing) {
                    RoundActionButton(
                        enabled = true,
                        icon = Icons.Filled.Stop,
                        color = Danger,
                        onClick = onStopPreview,
                    )
                } else {
                    RoundActionButton(
                        enabled = previewSurface != null,
                        icon = Icons.Filled.PlayArrow,
                        color = TealStrong,
                        onClick = { previewSurface?.let { onStartPreview(it, width, height) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundActionButton(
    enabled: Boolean,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = PanelSoft.copy(alpha = 0.78f),
            disabledContentColor = TextSecondary.copy(alpha = 0.45f),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun FloatingIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    color: Color = TextPrimary,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, if (enabled) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Black.copy(alpha = 0.46f),
            contentColor = color,
            disabledContainerColor = Color.Black.copy(alpha = 0.24f),
            disabledContentColor = TextSecondary.copy(alpha = 0.38f),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) color else TextSecondary.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun ActionDock(
    state: ThermalCameraState,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onSaveScreenshot: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    enhancementPanelVisible: Boolean,
    thermometryPanelVisible: Boolean,
    onToggleEnhancementPanel: () -> Unit,
    onToggleThermometryPanel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolButton(
                modifier = Modifier.weight(1f),
                text = "扫描",
                icon = Icons.Filled.Search,
                primary = true,
                enabled = true,
                onClick = onRefresh,
            )
            ToolButton(
                modifier = Modifier.weight(1f),
                text = if (state.loggedIn) "断开" else "连接",
                icon = Icons.Filled.PowerSettingsNew,
                primary = !state.loggedIn,
                enabled = state.selectedDevice != null || state.loggedIn,
                onClick = if (state.loggedIn) onLogout else onLogin,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) {
            IconToolButton(
                icon = Icons.Filled.PhotoCamera,
                description = "保存截图",
                enabled = state.previewing,
                onClick = onSaveScreenshot,
            )
            IconToolButton(
                icon = if (state.recording) Icons.Filled.Stop else Icons.Filled.Videocam,
                description = if (state.recording) "停止录像" else "开始录像",
                enabled = state.previewing || state.recording,
                activeColor = if (state.recording) Danger else null,
                onClick = if (state.recording) onStopRecording else onStartRecording,
            )
            IconToolButton(
                icon = Icons.Filled.Settings,
                description = if (thermometryPanelVisible) "收起测温显示" else "测温显示",
                enabled = true,
                activeColor = if (thermometryPanelVisible) Amber else null,
                onClick = onToggleThermometryPanel,
            )
            IconToolButton(
                icon = Icons.Filled.Tune,
                description = if (enhancementPanelVisible) "收起增强" else "校正和增强",
                enabled = true,
                activeColor = if (enhancementPanelVisible) TealStrong else null,
                onClick = onToggleEnhancementPanel,
            )
        }
    }
}

@Composable
private fun FloatingEnhancementPanel(
    modifier: Modifier = Modifier,
    state: ThermalCameraState,
    onClose: () -> Unit,
    onManualCorrect: () -> Unit,
    onBackgroundCorrect: () -> Unit,
    onRefreshImageEnhancement: () -> Unit,
    onApplyImageEnhancement: (ImageEnhancementSettings) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xF2141A1F),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, TealStrong.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "校正和图像增强",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.imageEnhancement.compactSummary(),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    modifier = Modifier.height(34.dp),
                    onClick = onClose,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, Line),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = PanelSoft,
                        contentColor = TextPrimary,
                    ),
                ) {
                    Text("收起", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolButton(
                    modifier = Modifier.weight(1f),
                    text = "快门校正",
                    icon = Icons.Filled.Tune,
                    primary = false,
                    enabled = state.loggedIn,
                    onClick = onManualCorrect,
                )
                ToolButton(
                    modifier = Modifier.weight(1f),
                    text = "背景校正",
                    icon = Icons.Filled.Settings,
                    primary = false,
                    enabled = state.loggedIn,
                    onClick = onBackgroundCorrect,
                )
            }

            ImageEnhancementPanel(
                state = state,
                onRefresh = onRefreshImageEnhancement,
                onApply = onApplyImageEnhancement,
            )
        }
    }
}

@Composable
private fun FloatingThermometryPanel(
    modifier: Modifier = Modifier,
    state: ThermalCameraState,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onApply: (ThermometryBasicSettings) -> Unit,
) {
    var settings by remember { mutableStateOf(state.thermometryBasic) }

    LaunchedEffect(state.thermometryBasic) {
        settings = state.thermometryBasic
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xF2141A1F),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Amber.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "测温显示",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = settings.compactSummary(),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    modifier = Modifier.height(34.dp),
                    onClick = onClose,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, Line),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = PanelSoft,
                        contentColor = TextPrimary,
                    ),
                ) {
                    Text("收起", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Panel,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Line),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "温度标记",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = settings.enabledMarksText(),
                            color = Amber,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EnhancementModeButton(
                            modifier = Modifier.weight(1f),
                            text = "最高",
                            selected = settings.displayMaxTemperature,
                            enabled = state.loggedIn,
                            onClick = { settings = settings.copy(displayMaxTemperature = !settings.displayMaxTemperature) },
                        )
                        EnhancementModeButton(
                            modifier = Modifier.weight(1f),
                            text = "最低",
                            selected = settings.displayMinTemperature,
                            enabled = state.loggedIn,
                            onClick = { settings = settings.copy(displayMinTemperature = !settings.displayMinTemperature) },
                        )
                        EnhancementModeButton(
                            modifier = Modifier.weight(1f),
                            text = "平均",
                            selected = settings.displayAverageTemperature,
                            enabled = state.loggedIn,
                            onClick = { settings = settings.copy(displayAverageTemperature = !settings.displayAverageTemperature) },
                        )
                        EnhancementModeButton(
                            modifier = Modifier.weight(1f),
                            text = "中心",
                            selected = settings.displayCenterTemperature,
                            enabled = state.loggedIn,
                            onClick = { settings = settings.copy(displayCenterTemperature = !settings.displayCenterTemperature) },
                        )
                    }

                    Text(
                        text = "温度单位",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(1 to "摄氏", 2 to "华氏", 3 to "开尔文").forEach { option ->
                            EnhancementModeButton(
                                modifier = Modifier.weight(1f),
                                text = option.second,
                                selected = settings.temperatureUnit == option.first,
                                enabled = state.loggedIn,
                                onClick = { settings = settings.copy(temperatureUnit = option.first) },
                            )
                        }
                    }

                    Text(
                        text = "测温范围",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(1 to "30~45", 2 to "-20~150", 3 to "0~400").forEach { option ->
                            EnhancementModeButton(
                                modifier = Modifier.weight(1f),
                                text = option.second,
                                selected = settings.temperatureRange == option.first,
                                enabled = state.loggedIn,
                                onClick = { settings = settings.copy(temperatureRange = option.first) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EnhancementModeButton(
                            modifier = Modifier.weight(1f),
                            text = "叠加到画面",
                            selected = settings.streamOverlay,
                            enabled = state.loggedIn,
                            onClick = { settings = settings.copy(streamOverlay = !settings.streamOverlay) },
                        )
                        EnhancementModeButton(
                            modifier = Modifier.weight(1f),
                            text = if (settings.displayPosition == 1) "位置: 跟随" else "位置: 左上",
                            selected = true,
                            enabled = state.loggedIn,
                            onClick = {
                                settings = settings.copy(
                                    displayPosition = if (settings.displayPosition == 1) 2 else 1,
                                )
                            },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ToolButton(
                            modifier = Modifier.weight(1f),
                            text = "读取测温",
                            icon = Icons.Filled.Search,
                            primary = false,
                            enabled = state.loggedIn,
                            onClick = onRefresh,
                        )
                        ToolButton(
                            modifier = Modifier.weight(1f),
                            text = "应用测温",
                            icon = Icons.Filled.Settings,
                            primary = true,
                            enabled = state.loggedIn,
                            onClick = { onApply(settings) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageEnhancementPanel(
    state: ThermalCameraState,
    onRefresh: () -> Unit,
    onApply: (ImageEnhancementSettings) -> Unit,
) {
    var settings by remember { mutableStateOf(state.imageEnhancement) }
    var selectedParameter by remember { mutableIntStateOf(1) }

    LaunchedEffect(state.imageEnhancement) {
        settings = state.imageEnhancement
    }

    val parameters = buildList {
        when (settings.noiseReduceMode) {
            1 -> add(0 to "普通降噪")
            2 -> {
                add(1 to "空域降噪")
                add(2 to "时域降噪")
            }
        }
        add(3 to "细节等级")
    }

    LaunchedEffect(settings.noiseReduceMode) {
        if (parameters.none { it.first == selectedParameter }) {
            selectedParameter = parameters.firstOrNull()?.first ?: 3
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Line),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "图像增强",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = settings.compactSummary(),
                    color = TealStrong,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EnhancementModeButton(
                    modifier = Modifier.weight(1f),
                    text = "关闭",
                    selected = settings.noiseReduceMode == 0,
                    enabled = state.loggedIn,
                    onClick = { settings = settings.copy(noiseReduceMode = 0) },
                )
                EnhancementModeButton(
                    modifier = Modifier.weight(1f),
                    text = "普通",
                    selected = settings.noiseReduceMode == 1,
                    enabled = state.loggedIn,
                    onClick = { settings = settings.copy(noiseReduceMode = 1) },
                )
                EnhancementModeButton(
                    modifier = Modifier.weight(1f),
                    text = "专家",
                    selected = settings.noiseReduceMode == 2,
                    enabled = state.loggedIn,
                    onClick = { settings = settings.copy(noiseReduceMode = 2) },
                )
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(parameters) { parameter ->
                    EnhancementParameterChip(
                        text = parameter.second,
                        selected = selectedParameter == parameter.first,
                        enabled = state.loggedIn,
                        onClick = { selectedParameter = parameter.first },
                    )
                }
            }

            val sliderLabel = parameters.firstOrNull { it.first == selectedParameter }?.second ?: "细节等级"
            val sliderValue = when (selectedParameter) {
                0 -> settings.generalLevel
                1 -> settings.frameNoiseReduceLevel
                2 -> settings.interFrameNoiseReduceLevel
                else -> settings.lseDetailLevel
            }
            val sliderEnabled = state.loggedIn && selectedParameter != 3 || state.loggedIn && settings.lseDetailEnabled
            EnhancementSlider(
                label = sliderLabel,
                value = sliderValue,
                enabled = sliderEnabled,
                onValueChange = {
                    settings = when (selectedParameter) {
                        0 -> settings.copy(generalLevel = it)
                        1 -> settings.copy(frameNoiseReduceLevel = it)
                        2 -> settings.copy(interFrameNoiseReduceLevel = it)
                        else -> settings.copy(lseDetailLevel = it)
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "细节增强",
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Switch(
                    checked = settings.lseDetailEnabled,
                    enabled = state.loggedIn,
                    onCheckedChange = { settings = settings.copy(lseDetailEnabled = it) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolButton(
                    modifier = Modifier.weight(1f),
                    text = "读取增强",
                    icon = Icons.Filled.Search,
                    primary = false,
                    enabled = state.loggedIn,
                    onClick = onRefresh,
                )
                ToolButton(
                    modifier = Modifier.weight(1f),
                    text = "应用增强",
                    icon = Icons.Filled.Tune,
                    primary = true,
                    enabled = state.loggedIn,
                    onClick = { onApply(settings) },
                )
            }
        }
    }
}

private fun ImageEnhancementSettings.compactSummary(): String {
    val mode = when (noiseReduceMode) {
        0 -> "关闭"
        1 -> "普通"
        2 -> "专家"
        else -> "未知"
    }
    val noise = when (noiseReduceMode) {
        1 -> "普$generalLevel"
        2 -> "空$frameNoiseReduceLevel 时$interFrameNoiseReduceLevel"
        else -> "降噪关"
    }
    val detail = if (lseDetailEnabled) "细$lseDetailLevel" else "细节关"
    return "$mode · $noise · $detail"
}

private fun ThermometryBasicSettings.compactSummary(): String =
    "${enabledMarksText()} · ${temperatureUnitLabel()} · ${temperatureRangeLabel()} · ${if (streamOverlay) "叠加" else "不叠加"}"

private fun ThermometryBasicSettings.enabledMarksText(): String =
    buildList {
        if (displayMaxTemperature) add("最高")
        if (displayMinTemperature) add("最低")
        if (displayAverageTemperature) add("平均")
        if (displayCenterTemperature) add("中心")
    }.ifEmpty { listOf("无标记") }.joinToString("/")

private fun ThermometryBasicSettings.temperatureUnitLabel(): String =
    when (temperatureUnit) {
        2 -> "华氏"
        3 -> "开尔文"
        else -> "摄氏"
    }

private fun ThermometryBasicSettings.temperatureRangeLabel(): String =
    when (temperatureRange) {
        1 -> "30~45"
        2 -> "-20~150"
        3 -> "0~400"
        else -> "范围$temperatureRange"
    }

@Composable
private fun EnhancementModeButton(
    modifier: Modifier = Modifier,
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        modifier = modifier.height(38.dp),
        enabled = enabled,
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        border = BorderStroke(1.dp, if (selected) TealStrong.copy(alpha = 0.8f) else Line),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color(0xFF18383B) else PanelSoft,
            contentColor = if (selected) TealStrong else TextPrimary,
            disabledContainerColor = PanelSoft.copy(alpha = 0.48f),
            disabledContentColor = TextSecondary.copy(alpha = 0.42f),
        ),
    ) {
        Text(text = text, maxLines = 1, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun EnhancementParameterChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .height(32.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (selected) Color(0xFF18383B) else PanelSoft,
        contentColor = if (selected) TealStrong else TextPrimary,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (selected) TealStrong.copy(alpha = 0.78f) else Line),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EnhancementSlider(
    label: String,
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = value.coerceIn(0, 100).toString(),
                color = if (enabled) TealStrong else TextSecondary.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value.coerceIn(0, 100).toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(0, 100)) },
            valueRange = 0f..100f,
            steps = 99,
            enabled = enabled,
        )
    }
}

@Composable
private fun IconToolButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    activeColor: Color? = null,
    onClick: () -> Unit,
) {
    OutlinedButton(
        modifier = Modifier.size(width = 48.dp, height = 42.dp),
        enabled = enabled,
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, if (enabled) Line else Line.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = activeColor?.copy(alpha = 0.16f) ?: Panel,
            contentColor = TextPrimary,
            disabledContainerColor = Panel.copy(alpha = 0.5f),
            disabledContentColor = TextSecondary.copy(alpha = 0.45f),
        ),
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(21.dp),
            tint = activeColor ?: TextPrimary,
        )
    }
}

@Composable
private fun ToolButton(
    modifier: Modifier = Modifier,
    text: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    if (primary) {
        Button(
            modifier = modifier.height(42.dp),
            enabled = enabled,
            onClick = onClick,
            shape = shape,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Teal,
                contentColor = Color.White,
                disabledContainerColor = PanelSoft,
                disabledContentColor = TextSecondary.copy(alpha = 0.5f),
            ),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            SpacerWidth()
            Text(text, maxLines = 1)
        }
    } else {
        OutlinedButton(
            modifier = modifier.height(42.dp),
            enabled = enabled,
            onClick = onClick,
            shape = shape,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            border = BorderStroke(1.dp, if (enabled) Line else Line.copy(alpha = 0.45f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Panel,
                contentColor = TextPrimary,
                disabledContainerColor = Panel.copy(alpha = 0.5f),
                disabledContentColor = TextSecondary.copy(alpha = 0.45f),
            ),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            SpacerWidth()
            Text(text, maxLines = 1)
        }
    }
}

@Composable
private fun SpacerWidth() {
    Box(modifier = Modifier.width(6.dp))
}

@Composable
private fun DeviceShelf(
    state: ThermalCameraState,
    onSelect: (ThermalUsbDevice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "USB 设备",
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${state.devices.size} 台",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        if (state.devices.isEmpty()) {
            EmptyDeviceRow()
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 62.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.devices) { device ->
                    DeviceRow(
                        device = device,
                        selected = device == state.selectedDevice,
                        onClick = { onSelect(device) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDeviceRow() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Line),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = "未检测到 4117 模块",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: ThermalUsbDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF193438) else Panel,
        ),
        border = BorderStroke(1.dp, if (selected) TealStrong.copy(alpha = 0.65f) else Line),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) TealStrong.copy(alpha = 0.18f) else PanelSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    tint = if (selected) TealStrong else Amber,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = device.name,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "VID ${device.vendorId}  PID ${device.productId}  FD ${device.fd}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StateBadge(
                text = if (selected) "已选" else "可用",
                color = if (selected) TealStrong else Amber,
            )
        }
    }
}
