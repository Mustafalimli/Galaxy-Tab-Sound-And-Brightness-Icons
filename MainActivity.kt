package com.example.volumescroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings

    private var overlayGranted by mutableStateOf(false)
    private var writeSettingsGranted by mutableStateOf(false)
    private var notificationGranted by mutableStateOf(false)
    private var notificationAsked = false

    // Ayar sayfalarından dönüşte izin durumları otomatik güncellenir.
    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissions()
        }

    private val writeSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissions()
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings.get(this)
        refreshPermissions()
        restoreServiceIfNeeded()

        setContent {
            AppTheme {
                val cfg by settings.config.collectAsStateWithLifecycle()
                ControlPanelScreen(
                    cfg = cfg,
                    settings = settings,
                    overlayGranted = overlayGranted,
                    writeSettingsGranted = writeSettingsGranted,
                    notificationGranted = notificationGranted,
                    onRequestOverlay = { requestOverlayPermission() },
                    onRequestWriteSettings = { requestWriteSettingsPermission() },
                    onRequestNotification = { requestNotificationPermission() },
                    onToggleService = { enable -> setServiceEnabled(enable) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        overlayGranted = Settings.canDrawOverlays(this)
        writeSettingsGranted = Settings.System.canWrite(this)
        notificationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Kayıtlı durum "çalışıyor" ama servis gerçekte ölmüşse yeniden başlatır / durumu düzeltir. */
    private fun restoreServiceIfNeeded() {
        if (settings.read().isServiceRunning && !UnifiedOverlayService.isAlive) {
            if (overlayGranted) UnifiedOverlayService.start(this)
            else settings.setServiceRunning(false)
        } else if (!UnifiedOverlayService.isAlive && settings.read().isCalibrationMode) {
            settings.setCalibrationMode(false)
        }
    }

    private fun requestOverlayPermission() {
        overlayLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun requestWriteSettingsPermission() {
        writeSettingsLauncher.launch(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun requestNotificationPermission() {
        if (!notificationAsked) {
            notificationAsked = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Sistem diyaloğu artık gösterilmeyebilir: uygulama bildirim ayarlarını aç.
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun setServiceEnabled(enable: Boolean) {
        if (!enable) {
            UnifiedOverlayService.stop(this)
            return
        }
        when {
            !overlayGranted -> requestOverlayPermission()
            !writeSettingsGranted -> requestWriteSettingsPermission()
            else -> UnifiedOverlayService.start(this)
        }
    }
}

// ============================================================================ tema (karanlık)

private val VolumeAccent = Color(0xFF00BCD4)
private val BrightnessAccent = Color(0xFFFFC107)
private val WarningOrange = Color(0xFFFFA726)
private val ConfirmGreen = Color(0xFF2E7D32)
private val CalibrationSurface = Color(0xFF3B2A00)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF00363D),
    secondary = Color(0xFFFFCA28),
    onSecondary = Color(0xFF3B2F00),
    tertiary = Color(0xFF66BB6A),
    background = Color(0xFF101215),
    onBackground = Color(0xFFE3E6EA),
    surface = Color(0xFF1A1D22),
    onSurface = Color(0xFFE3E6EA),
    surfaceVariant = Color(0xFF262A31),
    onSurfaceVariant = Color(0xFFA9B0BA),
    error = Color(0xFFFF6E6E),
    onError = Color(0xFF3A0000)
)

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

// ============================================================================ ana ekran

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlPanelScreen(
    cfg: AppConfig,
    settings: AppSettings,
    overlayGranted: Boolean,
    writeSettingsGranted: Boolean,
    notificationGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestWriteSettings: () -> Unit,
    onRequestNotification: () -> Unit,
    onToggleService: (Boolean) -> Unit
) {
    val screenConfig = LocalConfiguration.current
    val screen = remember(screenConfig) { settings.screenSize() }
    val screenW = screen.first
    val screenH = screen.second

    val leftColumn: @Composable () -> Unit = {
        // 1. Bölüm: izinler ve ana şalter
        PermissionCard(
            overlayGranted = overlayGranted,
            writeSettingsGranted = writeSettingsGranted,
            notificationGranted = notificationGranted,
            running = cfg.isServiceRunning,
            onRequestOverlay = onRequestOverlay,
            onRequestWriteSettings = onRequestWriteSettings,
            onRequestNotification = onRequestNotification,
            onToggleService = onToggleService
        )
        // 2. Bölüm: mod seçim sekmesi
        ModeCard(activeMode = cfg.activeMode, onSelect = { settings.setActiveMode(it) })
        // 3. Bölüm: kalibrasyon kartı
        CalibrationCard(
            calibrating = cfg.isCalibrationMode,
            running = cfg.isServiceRunning,
            mode = cfg.activeMode,
            onStart = { settings.setCalibrationMode(true) },
            onConfirm = { settings.setCalibrationMode(false) }
        )
    }

    val rightColumn: @Composable () -> Unit = {
        // 4. Bölüm: seçili moda göre detaylı ayarlar
        if (cfg.activeMode == AppMode.INVISIBLE_STRIPS) {
            StripCard(
                target = OverlayTarget.VOLUME,
                title = "\uD83D\uDD0A Ses Şeridi",
                accent = VolumeAccent,
                cfg = cfg,
                screenW = screenW,
                screenH = screenH,
                settings = settings
            )
            StripCard(
                target = OverlayTarget.BRIGHTNESS,
                title = "\u2600\uFE0F Parlaklık Şeridi",
                accent = BrightnessAccent,
                cfg = cfg,
                screenW = screenW,
                screenH = screenH,
                settings = settings
            )
            StripPresetCard(onPreset = { settings.applyStripPreset(it) })
        } else {
            BubbleCard(
                target = OverlayTarget.VOLUME,
                title = "\uD83D\uDD0A Ses Topçuğu",
                accent = VolumeAccent,
                cfg = cfg,
                screenW = screenW,
                screenH = screenH,
                settings = settings
            )
            BubbleCard(
                target = OverlayTarget.BRIGHTNESS,
                title = "\u2600\uFE0F Parlaklık Topçuğu",
                accent = BrightnessAccent,
                cfg = cfg,
                screenW = screenW,
                screenH = screenH,
                settings = settings
            )
            BubbleAppearanceCard(cfg = cfg, settings = settings)
        }
        // 5. Bölüm: ortak touchpad ayarları
        TouchpadCard(cfg = cfg, settings = settings)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Touchpad Ses ve Parlaklık") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (maxWidth >= 840.dp) {
                // Tablet / yatay ekran: iki sütun
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) { leftColumn() }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) { rightColumn() }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    leftColumn()
                    rightColumn()
                }
            }
        }
    }
}

// ============================================================================ kartlar

@Composable
private fun SectionCard(
    title: String,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor
            )
            content()
        }
    }
}

// ---------------------------------------------------------------- 1. bölüm: izinler ve ana şalter

@Composable
private fun PermissionCard(
    overlayGranted: Boolean,
    writeSettingsGranted: Boolean,
    notificationGranted: Boolean,
    running: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestWriteSettings: () -> Unit,
    onRequestNotification: () -> Unit,
    onToggleService: (Boolean) -> Unit
) {
    val allGranted = overlayGranted && writeSettingsGranted

    SectionCard("1. İzinler ve Servis") {
        PermissionRow(
            label = "Diğer uygulamaların üzerinde gösterim",
            hint = "Şeritlerin / topçukların ekranda çalışması için gerekli.",
            granted = overlayGranted,
            buttonText = "1. İzin: Diğer Uygulamaların Üzerinde Göster",
            onClick = onRequestOverlay
        )
        PermissionRow(
            label = "Sistem ayarlarını değiştirme",
            hint = "Parlaklığın değiştirilebilmesi için gerekli.",
            granted = writeSettingsGranted,
            buttonText = "2. İzin: Sistem Ayarlarını Değiştir (Parlaklık İçin)",
            onClick = onRequestWriteSettings
        )
        PermissionRow(
            label = "Bildirim izni (isteğe bağlı)",
            hint = "Bildirimdeki \"Ayarla / Onayla\" ve \"Servisi Kapat\" düğmeleri için.",
            granted = notificationGranted,
            buttonText = "Bildirim İznini Ver",
            onClick = onRequestNotification,
            optional = true
        )

        Text(
            if (allGranted) "\u2714 Gerekli tüm izinler verildi."
            else "\u26A0 Servisi başlatmak için yukarıdaki iki izni de vermelisiniz.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (allGranted) MaterialTheme.colorScheme.tertiary else WarningOrange
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("Servisi Başlat / Durdur", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (running) "Servis çalışıyor." else "Servis durdurulmuş.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (running) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = running,
                onCheckedChange = onToggleService,
                enabled = allGranted || running
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    hint: String,
    granted: Boolean,
    buttonText: String,
    onClick: () -> Unit,
    optional: Boolean = false
) {
    val statusColor = when {
        granted -> MaterialTheme.colorScheme.tertiary
        optional -> WarningOrange
        else -> MaterialTheme.colorScheme.error
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (granted) "\u2714 Verildi" else if (optional) "Önerilir" else "\u26A0 Gerekli",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = statusColor
        )
        if (!granted) {
            FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text(buttonText)
            }
        }
    }
}

// ---------------------------------------------------------------- 2. bölüm: mod seçim sekmesi

@Composable
private fun ModeCard(activeMode: Int, onSelect: (Int) -> Unit) {
    SectionCard("2. Kullanım Modu") {
        TabRow(
            selectedTabIndex = activeMode,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = activeMode == AppMode.INVISIBLE_STRIPS,
                onClick = { onSelect(AppMode.INVISIBLE_STRIPS) },
                text = { Text("\uD83D\uDCCF Görünmez Şerit Modu") }
            )
            Tab(
                selected = activeMode == AppMode.FLOATING_BUBBLES,
                onClick = { onSelect(AppMode.FLOATING_BUBBLES) },
                text = { Text("\uD83E\uDEE7 Yüzen Topçuk Modu") }
            )
        }
        Text(
            if (activeMode == AppMode.INVISIBLE_STRIPS) {
                "Ekranda iki görünmez şerit bulunur. İmleci şeridin üzerine getirip touchpad'de " +
                    "iki parmakla yukarı/aşağı kaydırın. Şeritler ayarlama modu dışında tamamen şeffaftır."
            } else {
                "Ekranda iki yarı saydam top bulunur. Topa tıklayınca altından dikey slider açılır; " +
                    "sürükleyin ya da topun/slider'ın üzerinde iki parmakla kaydırın."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------- 3. bölüm: kalibrasyon

@Composable
private fun CalibrationCard(
    calibrating: Boolean,
    running: Boolean,
    mode: Int,
    onStart: () -> Unit,
    onConfirm: () -> Unit
) {
    val stripMode = mode == AppMode.INVISIBLE_STRIPS
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (calibrating) CalibrationSurface else MaterialTheme.colorScheme.surface
        ),
        border = if (calibrating) BorderStroke(2.dp, WarningOrange) else null
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "3. Konum ve Boyut Ayarlama",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (calibrating) WarningOrange else MaterialTheme.colorScheme.onSurface
            )

            if (!calibrating) {
                Text(
                    "Önce ayarlamayı başlatın: " +
                        (if (stripMode) "şeritler" else "toplar") +
                        " ekranda görünür olur, sürükleyebilir ve aşağıdaki kaydırıcılarla " +
                        "boyutlandırabilirsiniz. İşiniz bitince onaylayıp tekrar gizleyin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onStart,
                    enabled = running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    Text(
                        if (stripMode) "\uD83D\uDEE0\uFE0F Konum ve Boyut Ayarlamayı Başlat (Şeritleri Göster)"
                        else "\uD83D\uDEE0\uFE0F Konum ve Boyut Ayarlamayı Başlat (Topçukları Göster)",
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center
                    )
                }
                if (!running) {
                    Text(
                        "Ayarlama için önce servisi başlatın.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningOrange
                    )
                }
            } else {
                Text(
                    "Şu an " + (if (stripMode) "şeritler" else "toplar") +
                        " ekranda görünür durumda. Ekranda sürükleyebilir veya aşağıdaki " +
                        "barlarla boyutlandırabilirsiniz. Ekranın üstündeki yüzen kutudan da " +
                        "onaylayabilirsiniz.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ConfirmGreen,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    Text(
                        if (stripMode) "\u2705 Ayarlamayı Onayla ve Görünmez Yap"
                        else "\u2705 Ayarlamayı Onayla ve Kilitle",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 4. bölüm: Mod 1 ayarları

@Composable
private fun StripCard(
    target: OverlayTarget,
    title: String,
    accent: Color,
    cfg: AppConfig,
    screenW: Int,
    screenH: Int,
    settings: AppSettings
) {
    val box = cfg.stripBox(target)
    SectionCard("4. $title", titleColor = accent) {
        SwitchRow(
            title = "Şeridi Etkinleştir",
            description = "Kapalıyken bu şerit ekranda bulunmaz.",
            checked = cfg.stripEnabled(target),
            onCheckedChange = { settings.setStripEnabled(target, it) }
        )
        ValueSliderRow(
            label = "X Konumu",
            value = box.x,
            rangeMin = 0,
            rangeMax = max(1, screenW - box.width),
            onChange = { settings.setStripGeometry(target, x = it) }
        )
        ValueSliderRow(
            label = "Y Konumu",
            value = box.y,
            rangeMin = 0,
            rangeMax = max(1, screenH - box.height),
            onChange = { settings.setStripGeometry(target, y = it) }
        )
        ValueSliderRow(
            label = "Genişlik (Width)",
            value = box.width,
            rangeMin = AppSettings.MIN_STRIP_WIDTH,
            rangeMax = min(AppSettings.MAX_STRIP_WIDTH, screenW),
            onChange = { settings.setStripGeometry(target, w = it) }
        )
        ValueSliderRow(
            label = "Yükseklik (Height)",
            value = box.height,
            rangeMin = min(AppSettings.MIN_STRIP_HEIGHT, screenH),
            rangeMax = screenH,
            onChange = { settings.setStripGeometry(target, h = it) }
        )
        Text(
            "Ekran: $screenW × $screenH px",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StripPresetCard(onPreset: (StripPreset) -> Unit) {
    SectionCard("Hızlı Şablonlar") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { onPreset(StripPreset.VOLUME_RIGHT_BRIGHTNESS_LEFT) }) {
                Text("Ses Sağda / Parlaklık Solda")
            }
            OutlinedButton(onClick = { onPreset(StripPreset.BOTH_RIGHT_STACKED) }) {
                Text("İkisi Sağ Kenarda Alt-Üst")
            }
        }
    }
}

// ---------------------------------------------------------------- 4. bölüm: Mod 2 ayarları

@Composable
private fun BubbleCard(
    target: OverlayTarget,
    title: String,
    accent: Color,
    cfg: AppConfig,
    screenW: Int,
    screenH: Int,
    settings: AppSettings
) {
    SectionCard("4. $title", titleColor = accent) {
        SwitchRow(
            title = "Topçuğu Etkinleştir",
            description = "Kapalıyken bu topçuk ekranda gösterilmez.",
            checked = cfg.bubbleEnabled(target),
            onCheckedChange = { settings.setBubbleEnabled(target, it) }
        )
        ValueSliderRow(
            label = "X Konumu",
            value = cfg.bubbleX(target),
            rangeMin = 0,
            rangeMax = max(1, screenW - cfg.bubbleSizePx),
            onChange = { settings.setBubblePosition(target, x = it) }
        )
        ValueSliderRow(
            label = "Y Konumu",
            value = cfg.bubbleY(target),
            rangeMin = 0,
            rangeMax = max(1, screenH - cfg.bubbleSizePx),
            onChange = { settings.setBubblePosition(target, y = it) }
        )
        Text(
            "Ekran: $screenW × $screenH px",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BubbleAppearanceCard(cfg: AppConfig, settings: AppSettings) {
    SectionCard("Topçuk Görünümü ve Davranışı") {
        ValueSliderRow(
            label = "Top Çapı (Boyutu)",
            value = cfg.bubbleSizePx,
            rangeMin = AppSettings.MIN_BUBBLE_SIZE,
            rangeMax = AppSettings.MAX_BUBBLE_SIZE,
            onChange = { settings.setBubbleSizePx(it) }
        )
        ValueSliderRow(
            label = "Açılır Slider Uzunluğu",
            value = cfg.dropdownSliderHeightPx,
            rangeMin = AppSettings.MIN_DROPDOWN_HEIGHT,
            rangeMax = AppSettings.MAX_DROPDOWN_HEIGHT,
            onChange = { settings.setDropdownSliderHeightPx(it) }
        )
        ValueSliderRow(
            label = "Saydamlık (Opaklık)",
            value = (cfg.bubbleIdleAlpha * 100f).roundToInt(),
            rangeMin = 10,
            rangeMax = 100,
            unit = "%",
            fineStep = 5,
            onChange = { settings.setBubbleIdleAlpha(it / 100f) }
        )
        ValueSliderRow(
            label = "Otomatik Kapanma Süresi",
            value = cfg.autoCollapseSec,
            rangeMin = 2,
            rangeMax = 10,
            unit = "sn",
            fineStep = 1,
            onChange = { settings.setAutoCollapseSec(it) }
        )
    }
}

// ---------------------------------------------------------------- 5. bölüm: ortak touchpad ayarları

@Composable
private fun TouchpadCard(cfg: AppConfig, settings: AppSettings) {
    SectionCard("5. Touchpad Hassasiyeti (Ortak)") {
        ValueSliderRow(
            label = "Kaydırma Tepki Süresi (Cooldown)",
            value = cfg.scrollCooldownMs.toInt(),
            rangeMin = 10,
            rangeMax = 300,
            unit = "ms",
            fineStep = 5,
            onChange = { settings.setScrollCooldownMs(it.toLong()) }
        )
        Text(
            "Düşük değer = hızlı tepki, yüksek değer = daha kontrollü değişim.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SwitchRow(
            title = "Kaydırma Yönünü Ters Çevir",
            description = "Açıkken iki parmakla aşağı kaydırmak artırır, yukarı kaydırmak azaltır.",
            checked = cfg.invertScroll,
            onCheckedChange = { settings.setInvertScroll(it) }
        )
        SwitchRow(
            title = "Sistem Ses Çubuğunu Göster",
            description = "Ses değişirken Android'in kendi ses panelini gösterir.",
            checked = cfg.showSystemVolumeUI,
            onCheckedChange = { settings.setShowSystemVolumeUI(it) }
        )
    }
}

// ============================================================================ ortak bileşenler

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Başlık + anlık değer + (−/+) ince ayar düğmeleri + kaydırıcı. */
@Composable
private fun ValueSliderRow(
    label: String,
    value: Int,
    rangeMin: Int,
    rangeMax: Int,
    unit: String = "px",
    fineStep: Int = 10,
    onChange: (Int) -> Unit
) {
    val hi = if (rangeMax > rangeMin) rangeMax else rangeMin + 1
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = { onChange(min(max(value - fineStep, rangeMin), hi)) },
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) { Text("\u2212$fineStep") }
            Text(
                "$value $unit",
                modifier = Modifier.widthIn(min = 96.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedButton(
                onClick = { onChange(min(max(value + fineStep, rangeMin), hi)) },
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) { Text("+$fineStep") }
        }
        Slider(
            value = value.toFloat().coerceIn(rangeMin.toFloat(), hi.toFloat()),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = rangeMin.toFloat()..hi.toFloat()
        )
    }
}
