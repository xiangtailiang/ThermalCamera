package io.github.xiangtailiang.thermalCamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ThermalVideoRecorder(
    private val context: Context,
    private val width: Int = 240,
    private val height: Int = 320,
    private val frameRate: Int = 30,
) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var recorder: MediaRecorder? = null
    private var inputSurface: Surface? = null
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var legacyOutputFile: File? = null
    private var legacyOutputStream: FileOutputStream? = null
    private var fileName: String = ""
    private var started = false

    @Synchronized
    fun start(): String {
        check(!started) { "录像已经开始" }
        fileName = "thermal_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"

        val output = createOutput()
        val mediaRecorder = createMediaRecorder(context)
        mediaRecorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(frameRate)
            setVideoEncodingBitRate(1_500_000)
            setOutputFile(output)
            prepare()
        }

        inputSurface = mediaRecorder.surface
        mediaRecorder.start()
        recorder = mediaRecorder
        started = true
        return fileName
    }

    @Synchronized
    fun drawFrame(bitmap: Bitmap) {
        if (!started) return
        val surface = inputSurface ?: return
        val canvas: Canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, fitCenterRect(bitmap.width, bitmap.height), paint)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    @Synchronized
    fun stop(): String {
        if (!started) return fileName
        val currentRecorder = recorder
        runCatching { currentRecorder?.stop() }
            .onFailure { deletePendingOutput() }
            .getOrThrow()
        release()
        publishOutput()
        started = false
        return fileName
    }

    @Synchronized
    fun cancel() {
        runCatching { recorder?.stop() }
        deletePendingOutput()
        release()
        started = false
    }

    private fun createOutput(): FileDescriptor {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ThermalCamera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建视频文件")
            outputUri = uri
            outputPfd = context.contentResolver.openFileDescriptor(uri, "w")
            outputPfd?.fileDescriptor
                ?: error("无法打开视频文件")
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ThermalCamera")
            check(dir.exists() || dir.mkdirs()) { "无法创建视频目录" }
            val file = File(dir, fileName)
            legacyOutputFile = file
            legacyOutputStream = FileOutputStream(file)
            legacyOutputStream?.fd ?: error("无法打开视频文件")
        }
    }

    private fun publishOutput() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val uri = outputUri ?: return
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        } else {
            legacyOutputFile?.let { file ->
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
            }
        }
    }

    private fun deletePendingOutput() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            outputUri?.let { context.contentResolver.delete(it, null, null) }
        } else {
            legacyOutputFile?.delete()
        }
    }

    private fun release() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        runCatching { inputSurface?.release() }
        runCatching { outputPfd?.close() }
        runCatching { legacyOutputStream?.close() }
        recorder = null
        inputSurface = null
        outputPfd = null
        legacyOutputStream = null
    }

    private fun fitCenterRect(frameWidth: Int, frameHeight: Int): Rect {
        if (frameWidth <= 0 || frameHeight <= 0) return Rect(0, 0, width, height)
        val scale = minOf(width.toFloat() / frameWidth.toFloat(), height.toFloat() / frameHeight.toFloat())
        val drawWidth = (frameWidth * scale).toInt().coerceAtLeast(1)
        val drawHeight = (frameHeight * scale).toInt().coerceAtLeast(1)
        val left = (width - drawWidth) / 2
        val top = (height - drawHeight) / 2
        return Rect(left, top, left + drawWidth, top + drawHeight)
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(context: Context): MediaRecorder =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

}
