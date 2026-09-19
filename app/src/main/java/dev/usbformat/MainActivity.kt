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
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.usbformat.fmt.DriveInfo
import dev.usbformat.fmt.EraseMode
import dev.usbformat.fmt.Fs
import dev.usbformat.fmt.Inspector
import dev.usbformat.fmt.Options
import dev.usbformat.fmt.Scheme
import dev.usbformat.fmt.TableType
import dev.usbformat.log.AppLog
import dev.usbformat.usb.UsbDisks
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class Drive(val id: String, val title: String, val detail: String)

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_PERMISSION = "dev.usbformat.USB_PERMISSION"
    }

    private lateinit var usb: UsbManager
    private var drives by mutableStateOf(emptyList<Drive>())
    private var afterPermission: (() -> Unit)? = null
    private var info by mutableStateOf<InfoState>(InfoState.None)
    private var infoBefore by mutableStateOf<DriveInfo?>(null)
    private var inspectedId: String? = null
    private val inspecting = AtomicBoolean(false)

    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val next = afterPermission
                afterPermission = null
                if (granted) {
                    next?.invoke()
                } else {
                    info = InfoState.None
                }
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
                    info = info,
                    infoBefore = infoBefore,
                    onInspect = { id -> inspect(id) },
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

    /** Runs [action] once USB permission for [device] is available (asking for it if needed). */
    private fun withPermission(device: UsbDevice, action: () -> Unit) {
        if (usb.hasPermission(device)) {
            action()
        } else {
            afterPermission = action
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

    /** Reads the partition table and file systems of the drive. Opening the drive makes Android unmount it briefly. */
    private fun inspect(id: String) {
        if (FormatState.status.value is FormatStatus.Running) return
        val device = usb.deviceList[id] ?: return
        if (inspectedId != id) {
            inspectedId = id
            infoBefore = null
        }
        withPermission(device) {
            if (!inspecting.compareAndSet(false, true)) return@withPermission
            info = InfoState.Loading
            thread(name = "usb-inspect") {
                info = try {
                    AppLog.log("inspect: reading $id")
                    val result = UsbDisks.open(usb, device, 8_000).use { Inspector.inspect(it) }
                    AppLog.log("inspect: ${result.table}, ${result.partitions.size} partition(s), ${result.sectorCount} sectors")
                    InfoState.Ready(result)
                } catch (e: Throwable) {
                    AppLog.log("inspect FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    InfoState.Failed(e.message ?: e.javaClass.simpleName)
                } finally {
                    inspecting.set(false)
                }
            }
        }
    }

    private fun startFormat(id: String, options: Options) {
        val device = usb.deviceList[id]
        if (device == null) {
            refresh()
            return
        }
        // Remember what the drive looked like, so the result can be compared with it.
        infoBefore = (info as? InfoState.Ready)?.info
        withPermission(device) {
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
    info: InfoState,
    infoBefore: DriveInfo?,
    onInspect: (String) -> Unit,
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

    // Read the drive when it is picked, and again when a format job finishes.
    val running = status is FormatStatus.Running
    LaunchedEffect(current?.id, running) {
        if (!running) current?.let { onInspect(it.id) }
    }

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

                if (current != null) {
                    Section(stringResource(R.string.section_info)) {
                        when (info) {
                            InfoState.None -> Unit
                            InfoState.Loading ->
                                Text(stringResource(R.string.info_loading), style = MaterialTheme.typography.bodyMedium)
                            is InfoState.Ready -> InfoBlock(info.info)
                            is InfoState.Failed ->
                                Text(
                                    stringResource(R.string.info_failed, info.message),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                        }
                        if (infoBefore != null) {
                            Hint(stringResource(R.string.info_before, summary(infoBefore)))
                        }
                        OutlinedButton(
                            onClick = { onInspect(current.id) },
                            enabled = info !is InfoState.Loading,
                        ) { Text(stringResource(R.string.info_reread)) }
                    }
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
                    enabled = current != null && info !is InfoState.Loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.format_button))
                }
            }

            LogPanel()
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


@Composable
private fun InfoBlock(info: DriveInfo) {
    val unknown = stringResource(R.string.fs_unknown)
    val style = MaterialTheme.typography.bodyMedium
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.info_capacity, formatSize(info.sectorCount * info.sectorSize), info.sectorSize),
            style = style,
        )
        Text(stringResource(R.string.info_table, tableName(info.table)), style = style)
        if (info.partitions.isEmpty()) {
            Text(stringResource(R.string.info_no_partitions), style = style)
        }
        info.partitions.forEach { p ->
            Text(
                stringResource(
                    R.string.info_partition,
                    p.index,
                    p.fs ?: unknown,
                    formatSize(p.sectors * info.sectorSize),
                    formatSize(p.startLba * info.sectorSize),
                ),
                style = style,
            )
        }
    }
}

@Composable
private fun tableName(table: TableType): String = when (table) {
    TableType.MBR -> "MBR"
    TableType.GPT -> "GPT"
    TableType.NONE -> stringResource(R.string.info_table_none)
}

/** One line such as "GPT · exFAT · 29.72 GiB (31.91 GB)". */
@Composable
private fun summary(info: DriveInfo): String {
    val unknown = stringResource(R.string.fs_unknown)
    val fs = info.partitions.joinToString(" + ") { it.fs ?: unknown }.ifEmpty { "-" }
    return "${tableName(info.table)} · $fs · ${formatSize(info.sectorCount * info.sectorSize)}"
}


@Composable
private fun LogPanel() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val version by AppLog.version.collectAsStateWithLifecycle()
    val text = remember(version) { AppLog.tail(150) }
    val scroll = rememberScrollState()
    LaunchedEffect(version) { scroll.scrollTo(scroll.maxValue) }

    Section(stringResource(R.string.section_log)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(scroll),
        ) {
            Text(
                text = text.ifEmpty { stringResource(R.string.log_empty) },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                ),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(AppLog.text()))
                Toast.makeText(context, R.string.log_copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.log_copy)) }
            OutlinedButton(onClick = { AppLog.clear() }) { Text(stringResource(R.string.log_clear)) }
        }
    }
}
