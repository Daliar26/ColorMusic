package com.example.colormusic

import android.Manifest
import android.os.Build
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        ColorMusicService.liveSettings.value = store.load()
        setContent {
            MaterialTheme { Screen(store) }
        }
    }
}

@Composable
fun Screen(store: SettingsStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by ColorMusicService.status.collectAsState()
    val s by ColorMusicService.liveSettings.collectAsState()
    var pendingStart by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    val ble = remember { BluetoothDiagnostics(ctx) }
    val bleDevices by ble.devices.collectAsState()
    val bleCharacteristics by ble.characteristics.collectAsState()
    val bleMessage by ble.message.collectAsState()
    val blePermissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val blePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) ble.scan() else ble.message.value = "Разрешите доступ Bluetooth для поиска лампы"
    }

    fun update(new: AppSettings) {
        ColorMusicService.liveSettings.value = new
        store.save(new)
    }

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            ctx.startForegroundService(
                Intent(ctx, ColorMusicService::class.java)
                    .setAction(ColorMusicService.ACTION_START)
                    .putExtra(ColorMusicService.EXTRA_RESULT_CODE, res.resultCode)
                    .putExtra(ColorMusicService.EXTRA_PROJECTION_DATA, data)
            )
        } else {
            ColorMusicService.status.value = ServiceStatus(text = "Доступ к захвату звука не выдан.")
        }
    }

    fun beginStart() {
        if (s.source == AudioSourceMode.SYSTEM) {
            val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            ctx.startForegroundService(
                Intent(ctx, ColorMusicService::class.java).setAction(ColorMusicService.ACTION_START)
            )
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val ok = hasMic()
        val wantStart = pendingStart
        pendingStart = false
        if (!ok) {
            ColorMusicService.status.value =
                ServiceStatus(text = "Нужен доступ к микрофону: без него звук не анализируется.")
        } else if (wantStart) {
            beginStart()
        }
    }

    fun onStartClick() {
        if (hasMic()) {
            beginStart()
        } else {
            pendingStart = true
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    androidx.compose.runtime.DisposableEffect(ble) { onDispose { ble.close() } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ColorMusic", style = MaterialTheme.typography.headlineMedium)

        SectionCard("Состояние") {
            Text(status.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Захват: ${if (status.running) "идёт" else "выключен"}  ·  " +
                    "Сигнал: ${if (status.hasSignal) "есть" else "нет"}  ·  " +
                    "Лампочка: ${if (status.lightConnected) "подключена" else "нет связи"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onStartClick() }, enabled = !status.running) { Text("Старт") }
                OutlinedButton(
                    onClick = {
                        ctx.startService(
                            Intent(ctx, ColorMusicService::class.java).setAction(ColorMusicService.ACTION_STOP)
                        )
                    },
                    enabled = status.running,
                ) { Text("Стоп") }
            }
        }

        SectionCard("Лампочка BY Smart A60 · Bluetooth LE") {
            Text("Поиск и диагностика локального Bluetooth-соединения. Пока изучаем протокол устройства; неподтверждённые команды не отправляются.", style = MaterialTheme.typography.bodySmall)
            Text(bleMessage, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val granted = blePermissions.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
                    if (granted) ble.scan() else blePermissionLauncher.launch(blePermissions)
                }) { Text("Найти лампу") }
                OutlinedButton(onClick = { ble.stopScan(); ble.message.value = "Поиск остановлен" }) { Text("Стоп поиска") }
            }
            if (bleDevices.isEmpty()) Text("Устройства появятся после поиска", style = MaterialTheme.typography.bodySmall)
            bleDevices.forEach { device ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleSmall)
                        Text("${device.address} · RSSI ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { ble.inspect(device.address) }) { Text("Подключить и прочитать службы") }
                    }
                }
            }
            if (bleCharacteristics.isNotEmpty()) {
                Text("Обнаруженные GATT-характеристики", style = MaterialTheme.typography.titleSmall)
                bleCharacteristics.forEach { c ->
                    Text("Служба: ${c.service}\nХарактеристика: ${c.characteristic}\nСвойства: ${c.properties.ifBlank { "не указаны" }}", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = { ble.close(); ble.message.value = "Соединение закрыто" }) { Text("Отключить") }
        }

        SectionCard("Старая интеграция Home Assistant (резерв)") {
            Field("Адрес Home Assistant", s.haUrl) { update(s.copy(haUrl = it)) }
            Field("Долгосрочный токен HA", s.haToken, secret = true) { update(s.copy(haToken = it)) }
            Field("entity_id лампочки", s.entityId) { update(s.copy(entityId = it)) }
            Text(
                "Адрес, токен и entity_id применяются при следующем «Старт».",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                scope.launch {
                    testResult = "Проверка..."
                    testResult = testLight(s)
                }
            }) { Text("Проверить лампочку") }
            if (testResult.isNotEmpty()) Text(testResult, style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard("Источник звука") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = s.source == AudioSourceMode.SYSTEM,
                    onClick = { update(s.copy(source = AudioSourceMode.SYSTEM)) },
                    label = { Text("Системный звук") },
                )
                FilterChip(
                    selected = s.source == AudioSourceMode.MICROPHONE,
                    onClick = { update(s.copy(source = AudioSourceMode.MICROPHONE)) },
                    label = { Text("Микрофон") },
                )
            }
            Text(
                "Многие стриминговые приложения запрещают захват своего звука. " +
                    "Если тишина, выберите «Микрофон».",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Палитра") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Palette.entries.forEach { p ->
                    FilterChip(
                        selected = s.palette == p,
                        onClick = { update(s.copy(palette = p)) },
                        label = {
                            Text(
                                when (p) {
                                    Palette.RAINBOW -> "Радуга"
                                    Palette.WARM -> "Тёплые"
                                    Palette.COOL -> "Холодные"
                                    Palette.CUSTOM -> "Свой"
                                }
                            )
                        },
                    )
                }
            }
            if (s.palette == Palette.CUSTOM) {
                LabeledSlider("Оттенок", s.customHue, 0f..360f) { update(s.copy(customHue = it)) }
            }
        }

        SectionCard("Чувствительность и яркость") {
            LabeledSlider("Чувствительность", s.sensitivity, 0.5f..2f) { update(s.copy(sensitivity = it)) }
            LabeledSlider("Мин. яркость, %", s.minPercent.toFloat(), 0f..50f) {
                update(s.copy(minPercent = it.toInt()))
            }
            LabeledSlider("Макс. яркость, %", s.maxPercent.toFloat(), 10f..100f) {
                update(s.copy(maxPercent = it.toInt()))
            }
        }

        SectionCard("Скорость и переходы") {
            LabeledSlider("Скорость смены цвета", s.speed, 0.2f..2f) { update(s.copy(speed = it)) }
            LabeledSlider("Сглаживание, мс", s.transitionMs.toFloat(), 100f..1500f) {
                update(s.copy(transitionMs = it.toInt()))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Мягкие переходы (без вспышек)", Modifier.weight(1f))
                Switch(checked = s.safeMode, onCheckedChange = { update(s.copy(safeMode = it)) })
            }
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val shown = if (value >= 10f) "${value.toInt()}" else "%.1f".format(value)
    Text("$label: $shown")
    Slider(value = value, onValueChange = onChange, valueRange = range)
}
