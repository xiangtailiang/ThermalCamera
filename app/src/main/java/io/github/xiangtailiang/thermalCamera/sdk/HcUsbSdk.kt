package io.github.xiangtailiang.thermalCamera.sdk

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import com.hcusbsdk.Interface.FStreamCallBack
import com.hcusbsdk.Interface.JavaInterface
import com.hcusbsdk.Interface.USB_DEVICE_REG_RES
import com.hcusbsdk.Interface.USB_FRAME_INFO
import com.hcusbsdk.Interface.USB_IMAGE_ENHANCEMENT
import com.hcusbsdk.Interface.USB_STREAM_CALLBACK_PARAM
import com.hcusbsdk.Interface.USB_THERMOMETRY_BASIC_PARAM
import com.hcusbsdk.Interface.USB_USER_LOGIN_INFO
import com.hcusbsdk.Interface.USB_VIDEO_PARAM
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "HcUsbSdk"

data class ThermalUsbDevice(
    val index: Int,
    val vendorId: Int,
    val productId: Int,
    val fd: Int,
    val name: String,
    val manufacturer: String?,
    val serial: String?,
)

data class LoginResult(
    val userId: Int,
    val deviceName: String,
    val serialNumber: String,
)

data class ImageEnhancementSettings(
    val noiseReduceMode: Int = 2,
    val generalLevel: Int = 51,
    val frameNoiseReduceLevel: Int = 51,
    val interFrameNoiseReduceLevel: Int = 51,
    val lseDetailEnabled: Boolean = true,
    val lseDetailLevel: Int = 51,
)

data class ThermometryBasicSettings(
    val displayMaxTemperature: Boolean = true,
    val displayMinTemperature: Boolean = false,
    val displayAverageTemperature: Boolean = false,
    val displayCenterTemperature: Boolean = true,
    val temperatureUnit: Int = 1,
    val temperatureRange: Int = 1,
    val streamOverlay: Boolean = true,
    val displayPosition: Int = 1,
)

class HcUsbSdk {
    private val librariesLoaded = AtomicBoolean(false)
    private val sdk: JavaInterface
        get() {
            loadNativeLibraries()
            return JavaInterface.getInstance()
        }

    private var streamCallback: FStreamCallBack? = null
    @Volatile
    private var frameSink: ((android.graphics.Bitmap) -> Unit)? = null

    fun init(): Boolean = runSdkCall("USB_Init") {
        val ok = sdk.USB_Init()
        if (ok) {
            Log.i(TAG, "USB_Init success. sdkVersion=${sdkVersionHex()}")
        }
        ok
    }

    fun cleanup(): Boolean = runSdkCall("USB_Cleanup") { sdk.USB_Cleanup() }

    fun sdkVersionHex(): String = runCatching {
        "%08x".format(sdk.USB_GetSDKVersion())
    }.getOrDefault("unknown")

    fun lastError(): Int = runCatching { sdk.USB_GetLastError() }.getOrDefault(-1)

    fun login(device: ThermalUsbDevice): LoginResult? = runCatching {
        val loginInfo = USB_USER_LOGIN_INFO().apply {
            dwTimeout = 5000
            dwDevIndex = device.index
            dwVID = device.vendorId
            dwPID = device.productId
            dwFd = device.fd
            byLoginMode = 1
            szUserName = "admin"
            szPassword = "12345"
        }
        val registerResult = USB_DEVICE_REG_RES()
        val userId = sdk.USB_Login(loginInfo, registerResult)
        if (userId == JavaInterface.USB_INVALID_USER_ID) {
            Log.e(TAG, "USB_Login failed: ${lastError()}")
            null
        } else {
            LoginResult(
                userId = userId,
                deviceName = registerResult.szDeviceName.orEmpty().trimEnd('\u0000'),
                serialNumber = registerResult.szSerialNumber.orEmpty().trimEnd('\u0000'),
            )
        }
    }.getOrElse {
        Log.e(TAG, "USB_Login crashed", it)
        null
    }

    fun logout(userId: Int): Boolean =
        userId != JavaInterface.USB_INVALID_USER_ID && runSdkCall("USB_Logout") {
            sdk.USB_Logout(userId)
        }

    fun setMjpegVideoParam(userId: Int): Boolean {
        val param = USB_VIDEO_PARAM().apply {
            dwVideoFormat = JavaInterface.USB_STREAM_MJPEG
            dwWidth = 240
            dwHeight = 320
            dwFramerate = 30
            dwBitrate = 0
            dwParamType = 0
            dwValue = 0
        }
        return runSdkCall("USB_SetVideoParam") { sdk.USB_SetVideoParam(userId, param) }
    }

    fun manualCorrect(userId: Int): Boolean =
        runSdkCall("USB_SetImageManualCorrect") { sdk.USB_SetImageManualCorrect(userId) }

    fun backgroundCorrect(userId: Int): Boolean =
        runSdkCall("USB_SetImageBackGroundCorrect") { sdk.USB_SetImageBackGroundCorrect(userId) }

    fun getPaletteMode(userId: Int): Int? = runCatching {
        val enhancement = USB_IMAGE_ENHANCEMENT()
        if (sdk.USB_GetImageEnhancement(userId, enhancement)) {
            enhancement.byPaletteMode.toInt() and 0xff
        } else {
            Log.e(TAG, "USB_GetImageEnhancement failed: ${lastError()}")
            null
        }
    }.getOrElse {
        Log.e(TAG, "USB_GetImageEnhancement crashed", it)
        null
    }

    fun getImageEnhancementSettings(userId: Int): ImageEnhancementSettings? = runCatching {
        val enhancement = USB_IMAGE_ENHANCEMENT()
        if (sdk.USB_GetImageEnhancement(userId, enhancement)) {
            enhancement.toImageEnhancementSettings()
        } else {
            Log.e(TAG, "USB_GetImageEnhancement failed: ${lastError()}")
            null
        }
    }.getOrElse {
        Log.e(TAG, "USB_GetImageEnhancement crashed", it)
        null
    }

    fun setImageEnhancementSettings(userId: Int, settings: ImageEnhancementSettings): Boolean {
        val enhancement = USB_IMAGE_ENHANCEMENT()
        val gotCurrent = runSdkCall("USB_GetImageEnhancement") {
            sdk.USB_GetImageEnhancement(userId, enhancement)
        }
        if (!gotCurrent) return false

        enhancement.byNoiseReduceMode = settings.noiseReduceMode.coerceIn(0, 2).toByte()
        enhancement.dwGeneralLevel = settings.generalLevel.coerceIn(0, 100)
        enhancement.dwFrameNoiseReduceLevel = settings.frameNoiseReduceLevel.coerceIn(0, 100)
        enhancement.dwInterFrameNoiseReduceLevel = settings.interFrameNoiseReduceLevel.coerceIn(0, 100)
        enhancement.byLSEDetailEnabled = if (settings.lseDetailEnabled) 1 else 0
        enhancement.dwLSEDetailLevel = settings.lseDetailLevel.coerceIn(0, 100)

        return runSdkCall("USB_SetImageEnhancement") {
            sdk.USB_SetImageEnhancement(userId, enhancement)
        }
    }

    fun getThermometryBasicSettings(userId: Int): ThermometryBasicSettings? = runCatching {
        val param = USB_THERMOMETRY_BASIC_PARAM()
        if (sdk.USB_GetThermometryBasicParam(userId, param)) {
            param.toThermometryBasicSettings()
        } else {
            Log.e(TAG, "USB_GetThermometryBasicParam failed: ${lastError()}")
            null
        }
    }.getOrElse {
        Log.e(TAG, "USB_GetThermometryBasicParam crashed", it)
        null
    }

    fun setThermometryBasicSettings(userId: Int, settings: ThermometryBasicSettings): Boolean {
        val param = USB_THERMOMETRY_BASIC_PARAM()
        val gotCurrent = runSdkCall("USB_GetThermometryBasicParam") {
            sdk.USB_GetThermometryBasicParam(userId, param)
        }
        if (!gotCurrent) return false

        param.byEnabled = 1.toByte()
        param.byDisplayMaxTemperatureEnabled = settings.displayMaxTemperature.toByteFlag()
        param.byDisplayMinTemperatureEnabled = settings.displayMinTemperature.toByteFlag()
        param.byDisplayAverageTemperatureEnabled = settings.displayAverageTemperature.toByteFlag()
        param.byDisplayCenTempEnabled = settings.displayCenterTemperature.toByteFlag()
        param.byTemperatureUnit = settings.temperatureUnit.coerceIn(1, 3).toByte()
        param.byTemperatureRange = settings.temperatureRange.coerceIn(1, 5).toByte()
        param.byThermometryStreamOverlay = (if (settings.streamOverlay) 2 else 1).toByte()
        param.byThermomrtryInfoDisplayPosition = settings.displayPosition.coerceIn(1, 2).toByte()

        return runSdkCall("USB_SetThermometryBasicParam") {
            sdk.USB_SetThermometryBasicParam(userId, param)
        }
    }

    fun setPaletteMode(userId: Int, mode: Int): Boolean {
        val enhancement = USB_IMAGE_ENHANCEMENT()
        val gotCurrent = runSdkCall("USB_GetImageEnhancement") {
            sdk.USB_GetImageEnhancement(userId, enhancement)
        }
        if (!gotCurrent) return false

        enhancement.byPaletteMode = mode.toByte()
        return runSdkCall("USB_SetImageEnhancement") {
            sdk.USB_SetImageEnhancement(userId, enhancement)
        }
    }

    fun startMjpegPreview(userId: Int, surface: Surface, width: Int, height: Int): Int {
        if (!setMjpegVideoParam(userId)) return JavaInterface.USB_INVALID_CHANNEL

        val callback = FStreamCallBack { _, frame ->
            drawFrame(frame, surface, width, height)
        }
        streamCallback = callback

        return runCatching {
            val param = USB_STREAM_CALLBACK_PARAM().apply {
                dwStreamType = JavaInterface.USB_STREAM_MJPEG
                fnStreamCallBack = callback
            }
            sdk.USB_StartStreamCallback(userId, param).also { handle ->
                if (handle == JavaInterface.USB_INVALID_CHANNEL) {
                    Log.e(TAG, "USB_StartStreamCallback failed: ${lastError()}")
                }
            }
        }.getOrElse {
            Log.e(TAG, "USB_StartStreamCallback crashed", it)
            JavaInterface.USB_INVALID_CHANNEL
        }
    }

    fun stopPreview(userId: Int, handle: Int): Boolean =
        userId != JavaInterface.USB_INVALID_USER_ID &&
            handle != JavaInterface.USB_INVALID_CHANNEL &&
            runSdkCall("USB_StopChannel") { sdk.USB_StopChannel(userId, handle) }

    fun setFrameSink(sink: ((android.graphics.Bitmap) -> Unit)?) {
        frameSink = sink
    }

    private fun drawFrame(frame: USB_FRAME_INFO, surface: Surface, width: Int, height: Int) {
        val length = frame.dwBufSize.coerceAtLeast(0)
        val data = frame.pBuf ?: return
        if (length == 0 || length > data.size) return

        val bitmap = BitmapFactory.decodeByteArray(data, 0, length) ?: return
        val canvas: Canvas = surface.lockCanvas(Rect(0, 0, width, height)) ?: return
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, fitCenterRect(width, height, bitmap.width, bitmap.height), Paint(Paint.FILTER_BITMAP_FLAG))
            runCatching { frameSink?.invoke(bitmap) }
                .onFailure { Log.e(TAG, "Frame sink failed", it) }
        } finally {
            surface.unlockCanvasAndPost(canvas)
            bitmap.recycle()
        }
    }

    private fun fitCenterRect(canvasWidth: Int, canvasHeight: Int, frameWidth: Int, frameHeight: Int): Rect {
        if (canvasWidth <= 0 || canvasHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
            return Rect(0, 0, canvasWidth.coerceAtLeast(0), canvasHeight.coerceAtLeast(0))
        }

        val scale = minOf(
            canvasWidth.toFloat() / frameWidth.toFloat(),
            canvasHeight.toFloat() / frameHeight.toFloat(),
        )
        val drawWidth = (frameWidth * scale).toInt().coerceAtLeast(1)
        val drawHeight = (frameHeight * scale).toInt().coerceAtLeast(1)
        val left = (canvasWidth - drawWidth) / 2
        val top = (canvasHeight - drawHeight) / 2
        return Rect(left, top, left + drawWidth, top + drawHeight)
    }

    private fun USB_IMAGE_ENHANCEMENT.toImageEnhancementSettings(): ImageEnhancementSettings =
        ImageEnhancementSettings(
            noiseReduceMode = byNoiseReduceMode.toInt() and 0xff,
            generalLevel = dwGeneralLevel.coerceIn(0, 100),
            frameNoiseReduceLevel = dwFrameNoiseReduceLevel.coerceIn(0, 100),
            interFrameNoiseReduceLevel = dwInterFrameNoiseReduceLevel.coerceIn(0, 100),
            lseDetailEnabled = (byLSEDetailEnabled.toInt() and 0xff) == 1,
            lseDetailLevel = dwLSEDetailLevel.coerceIn(0, 100),
        )

    private fun USB_THERMOMETRY_BASIC_PARAM.toThermometryBasicSettings(): ThermometryBasicSettings =
        ThermometryBasicSettings(
            displayMaxTemperature = byDisplayMaxTemperatureEnabled.isEnabledByte(),
            displayMinTemperature = byDisplayMinTemperatureEnabled.isEnabledByte(),
            displayAverageTemperature = byDisplayAverageTemperatureEnabled.isEnabledByte(),
            displayCenterTemperature = byDisplayCenTempEnabled.isEnabledByte(),
            temperatureUnit = (byTemperatureUnit.toInt() and 0xff).coerceIn(1, 3),
            temperatureRange = (byTemperatureRange.toInt() and 0xff).coerceIn(1, 5),
            streamOverlay = (byThermometryStreamOverlay.toInt() and 0xff) == 2,
            displayPosition = (byThermomrtryInfoDisplayPosition.toInt() and 0xff).coerceIn(1, 2),
        )

    private fun Boolean.toByteFlag(): Byte = if (this) 1 else 0

    private fun Byte.isEnabledByte(): Boolean = (toInt() and 0xff) == 1

    private fun runSdkCall(name: String, block: () -> Boolean): Boolean =
        runCatching {
            block().also { ok ->
                if (!ok) Log.e(TAG, "$name failed: ${lastError()}")
            }
        }.getOrElse {
            Log.e(TAG, "$name crashed", it)
            false
        }

    private fun loadNativeLibraries() {
        if (!librariesLoaded.compareAndSet(false, true)) return
        listOf("jnidispatch", "usb1.0", "uvc", "iconv", "HCUSBSDK").forEach { name ->
            runCatching { System.loadLibrary(name) }
                .onFailure { Log.w(TAG, "Unable to pre-load $name; SDK loader may still resolve it", it) }
        }
    }
}
