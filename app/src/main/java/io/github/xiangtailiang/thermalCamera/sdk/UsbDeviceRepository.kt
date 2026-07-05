package io.github.xiangtailiang.thermalCamera.sdk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "UsbDeviceRepository"
private const val ACTION_USB_PERMISSION = "io.github.xiangtailiang.thermalCamera.USB_PERMISSION"

class UsbDeviceRepository(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var receiver: BroadcastReceiver? = null
    private val retainedConnections = linkedMapOf<String, UsbDeviceConnection>()
    private val pendingPermissionRequests = mutableSetOf<String>()

    fun register(onChanged: () -> Unit, onPermissionDenied: (String) -> Unit) {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        val deviceName = device?.deviceName.orEmpty()
                        pendingPermissionRequests.remove(deviceName)

                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Log.i(TAG, "USB permission result: device=$deviceName granted=$granted")
                        if (granted) {
                            onChanged()
                        } else {
                            onPermissionDenied(deviceName.ifBlank { "USB 设备" })
                        }
                    }

                    UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED,
                    -> onChanged()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun unregister() {
        receiver?.let(context::unregisterReceiver)
        receiver = null
        pendingPermissionRequests.clear()
        closeConnections()
    }

    fun devices(closeExisting: Boolean = true): List<ThermalUsbDevice> {
        val allDevices = usbManager.deviceList.values.toList()
        Log.i(TAG, "scan started. usbDeviceCount=${allDevices.size}")
        if (closeExisting) {
            closeConnections()
        }

        return allDevices
            .onEach { device ->
                Log.i(
                    TAG,
                    "usb device: name=${device.deviceName} vid=${device.vendorId} pid=${device.productId} " +
                        "class=${device.deviceClass} product=${device.productName}",
                )
            }
            .filter(::isSupportedThermalDevice)
            .mapIndexedNotNull { index, device ->
                if (!usbManager.hasPermission(device)) {
                    if (pendingPermissionRequests.add(device.deviceName)) {
                        Log.i(TAG, "requesting USB permission: ${device.deviceName}")
                        requestPermission(device)
                    } else {
                        Log.i(TAG, "USB permission request already pending: ${device.deviceName}")
                    }
                    null
                } else {
                    pendingPermissionRequests.remove(device.deviceName)
                    Log.i(TAG, "opening USB device: ${device.deviceName}")
                    val connection = usbManager.openDevice(device)
                    if (connection == null) {
                        Log.e(TAG, "openDevice failed: vid=${device.vendorId} pid=${device.productId}")
                        null
                    } else {
                        retainedConnections[device.deviceName] = connection
                        Log.i(TAG, "openDevice success: fd=${connection.fileDescriptor}")
                        ThermalUsbDevice(
                            index = index,
                            vendorId = device.vendorId,
                            productId = device.productId,
                            fd = connection.fileDescriptor,
                            name = device.deviceName,
                            manufacturer = device.manufacturerName,
                            serial = null,
                        )
                    }
                }
            }
            .also { Log.i(TAG, "scan finished. thermalDeviceCount=${it.size}") }
    }

    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        usbManager.requestPermission(device, intent)
    }

    private fun isSupportedThermalDevice(device: UsbDevice): Boolean {
        if (device.vendorId != 11231) return false
        return device.productId == 257 ||
            device.productId == 383 ||
            device.productId in 257..512 ||
            device.productId in 513..768
    }

    private fun closeConnections() {
        retainedConnections.values.forEach { connection ->
            runCatching { connection.close() }
        }
        retainedConnections.clear()
    }
}
