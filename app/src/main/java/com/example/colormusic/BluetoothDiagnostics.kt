package com.example.colormusic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

 data class BleDeviceInfo(val name: String, val address: String, val rssi: Int)
 data class BleCharacteristicInfo(val service: String, val characteristic: String, val properties: String)

/** Local BLE discovery and GATT inspection. It deliberately sends no undocumented Tuya commands. */
class BluetoothDiagnostics(private val context: Context) {
    val devices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val characteristics = MutableStateFlow<List<BleCharacteristicInfo>>(emptyList())
    val message = MutableStateFlow("Bluetooth не проверен")
    private var gatt: BluetoothGatt? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val item = BleDeviceInfo(
                name = device.name ?: result.scanRecord?.deviceName ?: "Без имени",
                address = device.address,
                rssi = result.rssi
            )
            devices.value = (devices.value.filterNot { it.address == item.address } + item)
                .sortedByDescending { it.rssi }
        }
        override fun onScanFailed(errorCode: Int) { message.value = "Ошибка Bluetooth-сканирования: $errorCode" }
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { message.value = "Включите Bluetooth на телефоне"; return }
        devices.value = emptyList()
        runCatching { adapter.bluetoothLeScanner.startScan(scanCallback) }
            .onSuccess { message.value = "Поиск BLE-устройств…" }
            .onFailure { message.value = "Не удалось начать поиск: ${it.message}" }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    @SuppressLint("MissingPermission")
    fun inspect(address: String) {
        stopScan()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) { message.value = "Устройство не найдено"; return }
        gatt?.close()
        characteristics.value = emptyList()
        message.value = "Подключение к $address…"
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    message.value = "Ошибка GATT: $status"; g.close(); return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    message.value = "Подключено. Чтение служб…"
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    message.value = "Отключено от устройства"
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { message.value = "Не удалось прочитать службы: $status"; return }
                val found = mutableListOf<BleCharacteristicInfo>()
                g.services.forEach { service -> service.characteristics.forEach { c ->
                    val p = c.properties
                    val labels = buildList {
                        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
                        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
                        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                    }
                    found += BleCharacteristicInfo(service.uuid.toString(), c.uuid.toString(), labels.joinToString(", "))
                } }
                characteristics.value = found
                message.value = "Служб найдено: ${g.services.size}; характеристик: ${found.size}. Команды не отправлялись."
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun close() { stopScan(); gatt?.disconnect(); gatt?.close(); gatt = null }
}
