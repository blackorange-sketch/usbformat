package dev.usbformat

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.usbformat.fmt.EraseMode
import dev.usbformat.fmt.Fs
import dev.usbformat.fmt.Options
import dev.usbformat.fmt.Scheme

data class Drive(val id: String, val title: String, val detail: String)

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_PERMISSION = "dev.usbformat.USB_PERMISSION"
    }

    private lateinit var usb: UsbManager
    private var drives by mutableStateOf(emptyList<Drive>())
    private var afterPermission: (() -> Unit)? = null

    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val next = afterPermission
                afterPermission = null
                if (granted) next?.invoke()
            } else {
                refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        usb = getSystemService(USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AppTheme {
                val status by FormatState.status.collectAsStateWithLifecycle()
                MainScreen(
                    drives = drives,
                    status = status,
                    onRefresh = { refresh() },
                    onFormat = { id, options -> startFormat(id, options) },
                    onCancel = { FormatState.cancel.requested = true },
                    onDismiss = { FormatState.status.value = FormatStatus.Idle },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        unregisterReceiver(usbEvents)
        super.onDestroy()
    }

    private fun refresh() {
        drives = usb.deviceList.values
            .filter { d ->
                (0 until d.interfaceCount).any {
                    d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
                }
            }
            .map { d ->
                Drive(
                    id = d.deviceName,
                    title = d.displayName(),
                    detail = "%s · %04x:%04x".format(d.deviceName, d.vendorId, d.productId),
                )
            }
    }

    private fun UsbDevice.displayName(): String {
        val name = try {
            listOfNotNull(manufacturerName, productName).joinToString(" ")
        } catch (e: SecurityException) {
            ""
        }
        return name.ifBlank { "USB drive" }
    }

    private fun startFormat(id: String, options: Options) {
        val device = usb.deviceList[id]
        if (device == null) {
            refresh()
            return
        }
        val start: () -> Unit = {
            ContextCompat.startForegroundService(
                this,
                Intent(this, FormatService::class.java)
                    .putExtra(FormatService.EXTRA_DEVICE, id)
                    .putExtra(FormatService.EXTRA_SCHEME, options.scheme.name)
                    .putExtra(FormatService.EXTRA_FS, options.fs.name)
                    .putExtra(FormatService.EXTRA_MODE, options.mode.name)
                    .putExtra(FormatService.EXTRA_LABEL, options.label),
            )
        }
        if (usb.hasPermission(device)) {
            start()
        } else {
            afterPermission = start
            // The system fills in the extras, so this PendingIntent has to be mutable on Android 12+.
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_PERMISSION).setPackage(packageName),
                flags,
            )
            usb.requestPermission(device, pending)
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    drives: List<Drive>,
    status: FormatStatus,
    onRefresh: () -> Unit,
    onFormat: (String, Options) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var scheme by rememberSaveable { mutableStateOf(Scheme.MBR) }
    var fs by rememberSaveable { mutableStateOf(Fs.FAT32) }
    var mode by rememberSaveable { mutableStateOf(EraseMode.QUICK) }
    var label by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }

    val current = drives.firstOrNull { it.id == selected } ?: drives.singleOrNull()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

            if (status is FormatStatus.Running) {
                RunningPanel(status, onCancel)
            } else {
                ResultLine(status, onDismiss)

                Section(stringResource(R.string.section_drive)) {
                    if (drives.isEmpty()) {
                        Text(stringResource(R.string.no_drives), style = MaterialTheme.typography.bodyMedium)
                    }
                    drives.forEach { d ->
                        val isSelected = d.id == current?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = isSelected, onClick = { selected = d.id }, role = Role.RadioButton)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(d.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    d.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
                }

                Section(stringResource(R.string.section_scheme)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Scheme.entries.forEach { s ->
                            FilterChip(selected = scheme == s, onClick = { scheme = s }, label = { Text(s.name) })
                        }
                    }
                    Hint(stringResource(R.string.scheme_hint))
                }

                Section(stringResource(R.string.section_fs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Fs.entries.forEach { f ->
                            FilterChip(
                                selected = fs == f,
                                onClick = { fs = f },
                                label = { Text(if (f == Fs.FAT32) "FAT32" else "exFAT") },
                            )
                        }
                        FilterChip(selected = false, onClick = { }, enabled = false, label = { Text("NTFS") })
                    }
                    Hint(stringResource(if (fs == Fs.FAT32) R.string.fs_fat32_hint else R.string.fs_exfat_hint))
                    Hint(stringResource(R.string.fs_ntfs_hint))
                }

                Section(stringResource(R.string.section_erase)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EraseMode.entries.forEach { m ->
                            val name = when (m) {
                                EraseMode.QUICK -> R.string.erase_quick
                                EraseMode.ZERO -> R.string.erase_zero
                                EraseMode.VERIFY -> R.string.erase_verify
                            }
                            FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(stringResource(name)) })
                        }
                    }
                    val hint = when (mode) {
                        EraseMode.QUICK -> R.string.erase_quick_hint
                        EraseMode.ZERO -> R.string.erase_zero_hint
                        EraseMode.VERIFY -> R.string.erase_verify_hint
                    }
                    Hint(stringResource(hint))
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(11) },
                    label = { Text(stringResource(R.string.label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = { confirm = true },
                    enabled = current != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.format_button))
                }
            }
        }
    }

    if (confirm && current != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(stringResource(R.string.confirm_text, current.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onFormat(current.id, Options(scheme, fs, label, mode))
                }) { Text(stringResource(R.string.confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RunningPanel(status: FormatStatus.Running, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(status.phase.labelRes()), style = MaterialTheme.typography.titleMedium)
        if (status.total > 0) {
            LinearProgressIndicator(
                progress = { status.done.toFloat() / status.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(
                    R.string.progress_line,
                    (status.done * 100 / status.total).toInt(),
                    status.bytesPerSec / 1e6,
                    formatEta(status.etaSec),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun ResultLine(status: FormatStatus, onDismiss: () -> Unit) {
    val text = when (status) {
        FormatStatus.Done -> stringResource(R.string.status_done)
        FormatStatus.Cancelled -> stringResource(R.string.status_cancelled)
        is FormatStatus.Failed -> stringResource(R.string.status_failed, status.message)
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
    }
}

private fun formatEta(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
