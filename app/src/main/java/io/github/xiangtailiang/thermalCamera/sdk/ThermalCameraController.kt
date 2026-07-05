package io.github.xiangtailiang.thermalCamera.sdk

import android.content.Context
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ThermalCameraState(
    val initialized: Boolean = false,
    val devices: List<ThermalUsbDevice> = emptyList(),
    val selectedDevice: ThermalUsbDevice? = null,
    val userId: Int = -1,
    val previewHandle: Int = -1,
    val sdkVersion: String = "unknown",
    val paletteMode: PaletteMode = PaletteMode.Ironbow2,
    val imageEnhancement: ImageEnhancementSettings = ImageEnhancementSettings(),
    val thermometryBasic: ThermometryBasicSettings = ThermometryBasicSettings(),
    val recording: Boolean = false,
    val status: String = "等待初始化",
) {
    val loggedIn: Boolean = userId != -1
    val previewing: Boolean = previewHandle != -1
}

data class PaletteMode(
    val id: Int,
    val label: String,
) {
    companion object {
        val WhiteHot = PaletteMode(1, "白热")
        val BlackHot = PaletteMode(2, "黑热")
        val Fusion1 = PaletteMode(10, "融合1")
        val Rainbow = PaletteMode(11, "彩虹")
        val Fusion2 = PaletteMode(12, "融合2")
        val Ironbow1 = PaletteMode(13, "铁红1")
        val Ironbow2 = PaletteMode(14, "铁红2")
        val Sepia = PaletteMode(15, "深褐色")
        val Color1 = PaletteMode(16, "色彩1")
        val Color2 = PaletteMode(17, "色彩2")
        val IceFire = PaletteMode(18, "冰火")
        val Rain = PaletteMode(19, "雨")
        val RedHot = PaletteMode(20, "红热")
        val GreenHot = PaletteMode(21, "绿热")
        val DarkBlue = PaletteMode(22, "深蓝")

        val all = listOf(
            WhiteHot,
            BlackHot,
            Ironbow1,
            Ironbow2,
            Rainbow,
            Fusion1,
            Fusion2,
            Sepia,
            Color1,
            Color2,
            IceFire,
            Rain,
            RedHot,
            GreenHot,
            DarkBlue,
        )

        fun fromId(id: Int): PaletteMode = all.firstOrNull { it.id == id } ?: Ironbow2
    }
}

class ThermalCameraController(context: Context) {
    private val sdk = HcUsbSdk()
    private val repository = UsbDeviceRepository(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ThermalCameraState())

    val state: StateFlow<ThermalCameraState> = _state

    fun registerUsbReceiver() {
        repository.register(
            onChanged = { handleUsbChanged() },
            onPermissionDenied = { deviceName ->
                setStatus("USB 授权失败或被拒绝: $deviceName")
            },
        )
    }

    fun unregisterUsbReceiver() {
        repository.unregister()
    }

    fun initialize() {
        _state.update { it.copy(status = "SDK 初始化中") }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { sdk.init() }
            _state.update {
                it.copy(
                    initialized = ok,
                    sdkVersion = sdk.sdkVersionHex(),
                    status = if (ok) "SDK 已初始化" else "SDK 初始化失败: ${sdk.lastError()}",
                )
            }
            scanDevices(closeExisting = true)
        }
    }

    fun refreshDevices() {
        if (state.value.loggedIn) {
            setStatus("已连接设备；如需重新扫描，请先断开")
            return
        }
        scanDevices(closeExisting = true)
    }

    private fun scanDevices(closeExisting: Boolean) {
        _state.update { it.copy(status = "正在扫描 USB 设备") }
        scope.launch {
            val devices = withContext(Dispatchers.IO) { repository.devices(closeExisting) }
            _state.update {
                val selected = it.selectedDevice?.let { current ->
                    devices.firstOrNull { device -> device.name == current.name }
                } ?: devices.firstOrNull()
                it.copy(
                    devices = devices,
                    selectedDevice = selected,
                    status = if (devices.isEmpty()) "未找到 USB 热成像设备，或正在等待授权" else "发现 ${devices.size} 个设备",
                )
            }
        }
    }

    fun selectDevice(device: ThermalUsbDevice) {
        _state.update { it.copy(selectedDevice = device) }
    }

    fun login() {
        val device = state.value.selectedDevice ?: return setStatus("请先授权并选择设备")
        setStatus("正在连接设备")
        scope.launch {
            val result = withContext(Dispatchers.IO) { sdk.login(device) }
            _state.update {
                if (result == null) {
                    it.copy(userId = -1, status = "登录失败: ${sdk.lastError()}")
                } else {
                    it.copy(userId = result.userId, status = "已连接 ${result.deviceName.ifBlank { device.name }}")
                }
            }
            if (result != null) {
                refreshPaletteMode(result.userId)
                refreshImageEnhancement(result.userId)
                refreshThermometryBasic(result.userId)
            }
        }
    }

    fun logout() {
        stopPreview()
        val userId = state.value.userId
        scope.launch {
            val ok = withContext(Dispatchers.IO) { sdk.logout(userId) }
            _state.update { it.copy(userId = -1, status = if (ok) "已断开连接" else "断开失败: ${sdk.lastError()}") }
        }
    }

    fun startPreview(surface: Surface, width: Int, height: Int) {
        val userId = state.value.userId
        if (userId == -1) {
            setStatus("请先连接设备")
            return
        }
        if (state.value.previewing) return
        val handle = sdk.startMjpegPreview(userId, surface, width, height)
        _state.update {
            it.copy(
                previewHandle = handle,
                status = if (handle == -1) "预览启动失败: ${sdk.lastError()}" else "预览中",
            )
        }
    }

    fun stopPreview() {
        val current = state.value
        if (!current.previewing) return
        sdk.setFrameSink(null)
        val ok = sdk.stopPreview(current.userId, current.previewHandle)
        _state.update {
            it.copy(
                previewHandle = -1,
                status = if (ok) "预览已停止" else "停止预览失败: ${sdk.lastError()}",
            )
        }
    }

    fun setFrameSink(sink: ((android.graphics.Bitmap) -> Unit)?) {
        sdk.setFrameSink(sink)
    }

    fun setRecording(recording: Boolean, status: String) {
        _state.update { it.copy(recording = recording, status = status) }
    }

    fun manualCorrect() {
        val userId = state.value.userId
        if (userId == -1) return setStatus("请先连接设备")
        val ok = sdk.manualCorrect(userId)
        setStatus(if (ok) "已触发快门校正" else "快门校正失败: ${sdk.lastError()}")
    }

    fun backgroundCorrect() {
        val userId = state.value.userId
        if (userId == -1) return setStatus("请先连接设备")
        val ok = sdk.backgroundCorrect(userId)
        setStatus(if (ok) "已触发背景校正" else "背景校正失败: ${sdk.lastError()}")
    }

    fun setPaletteMode(mode: PaletteMode) {
        val userId = state.value.userId
        if (userId == -1) return setStatus("请先连接设备")
        setStatus("正在切换调色板: ${mode.label}")
        scope.launch {
            val ok = withContext(Dispatchers.IO) { sdk.setPaletteMode(userId, mode.id) }
            _state.update {
                if (ok) {
                    it.copy(paletteMode = mode, status = "调色板已切换为 ${mode.label}")
                } else {
                    it.copy(status = "调色板切换失败: ${sdk.lastError()}")
                }
            }
        }
    }

    fun refreshImageEnhancement() {
        val userId = state.value.userId
        if (userId == -1) return setStatus("请先连接设备")
        refreshImageEnhancement(userId)
    }

    fun setImageEnhancement(settings: ImageEnhancementSettings) {
        val userId = state.value.userId
        if (userId == -1) return setStatus("请先连接设备")
        setStatus("正在设置图像增强参数")
        scope.launch {
            val ok = withContext(Dispatchers.IO) { sdk.setImageEnhancementSettings(userId, settings) }
            _state.update {
                if (ok) {
                    it.copy(imageEnhancement = settings, status = "已应用增强: ${settings.statusSummary()}")
                } else {
                    it.copy(status = "图像增强设置失败: ${sdk.lastError()}")
                }
            }
        }
    }

    fun refreshThermometryBasic() {
        val userId = state.value.userId
        if (userId == -1) return setStatus("请先连接设备")
        refreshThermometryBasic(userId)
    }

    fun setThermometryBasic(settings: ThermometryBasicSettings) {
        val userId = state.value.userId
        if (userId == -1) return setStatus("请先连接设备")
        setStatus("正在设置测温显示参数")
        scope.launch {
            val ok = withContext(Dispatchers.IO) { sdk.setThermometryBasicSettings(userId, settings) }
            _state.update {
                if (ok) {
                    it.copy(thermometryBasic = settings, status = "已应用测温: ${settings.statusSummary()}")
                } else {
                    it.copy(status = "测温显示设置失败: ${sdk.lastError()}")
                }
            }
        }
    }

    fun cleanup() {
        logout()
        repository.unregister()
        sdk.cleanup()
        scope.cancel()
    }

    fun setStatus(value: String) {
        _state.update { it.copy(status = value) }
    }

    private fun handleUsbChanged() {
        val current = state.value
        if (!current.loggedIn) {
            scanDevices(closeExisting = true)
            return
        }

        setStatus("USB 状态变化，正在重置连接")
        stopPreview()
        scope.launch {
            withContext(Dispatchers.IO) { sdk.logout(current.userId) }
            _state.update {
                it.copy(
                    userId = -1,
                    previewHandle = -1,
                    selectedDevice = null,
                    status = "USB 状态变化，正在重新扫描",
                )
            }
            scanDevices(closeExisting = true)
        }
    }

    private fun refreshPaletteMode(userId: Int) {
        scope.launch {
            val mode = withContext(Dispatchers.IO) { sdk.getPaletteMode(userId) }
            if (mode != null) {
                _state.update { it.copy(paletteMode = PaletteMode.fromId(mode)) }
            }
        }
    }

    private fun refreshImageEnhancement(userId: Int) {
        scope.launch {
            val settings = withContext(Dispatchers.IO) { sdk.getImageEnhancementSettings(userId) }
            _state.update {
                if (settings == null) {
                    it.copy(status = "图像增强参数读取失败: ${sdk.lastError()}")
                } else {
                    it.copy(imageEnhancement = settings, status = "已读取增强: ${settings.statusSummary()}")
                }
            }
        }
    }

    private fun refreshThermometryBasic(userId: Int) {
        scope.launch {
            val settings = withContext(Dispatchers.IO) { sdk.getThermometryBasicSettings(userId) }
            _state.update {
                if (settings == null) {
                    it.copy(status = "测温显示参数读取失败: ${sdk.lastError()}")
                } else {
                    it.copy(thermometryBasic = settings, status = "已读取测温: ${settings.statusSummary()}")
                }
            }
        }
    }
}

private fun ImageEnhancementSettings.statusSummary(): String {
    val mode = when (noiseReduceMode) {
        0 -> "关闭"
        1 -> "普通"
        2 -> "专家"
        else -> "未知"
    }
    val noise = when (noiseReduceMode) {
        1 -> "普通$generalLevel"
        2 -> "空域$frameNoiseReduceLevel 时域$interFrameNoiseReduceLevel"
        else -> "降噪关"
    }
    val detail = if (lseDetailEnabled) "细节$lseDetailLevel" else "细节关"
    return "$mode · $noise · $detail"
}

private fun ThermometryBasicSettings.statusSummary(): String {
    val marks = buildList {
        if (displayMaxTemperature) add("最高")
        if (displayMinTemperature) add("最低")
        if (displayAverageTemperature) add("平均")
        if (displayCenterTemperature) add("中心")
    }.ifEmpty { listOf("无标记") }.joinToString("/")
    return "$marks · ${temperatureUnitLabel()} · ${temperatureRangeLabel()} · ${if (streamOverlay) "叠加" else "不叠加"}"
}

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
