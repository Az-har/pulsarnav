package com.pulsarnav.app.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.view.WindowManager
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.pulsarnav.app.data.VehicleCatalog
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import androidx.core.widget.NestedScrollView
import com.google.android.material.checkbox.MaterialCheckBox
import kotlin.math.roundToInt
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.android.material.snackbar.Snackbar
import com.pulsarnav.app.data.DataVaultManager
import com.pulsarnav.app.data.VaultPassphraseRequiredException
import com.pulsarnav.app.maintenance.GloveboxDocumentStatus
import com.pulsarnav.app.maintenance.GloveboxExpiryEvaluator
import com.pulsarnav.app.maintenance.MaintenanceMath
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.webkit.MimeTypeMap
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsarnav.app.R
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.ble.BleConnectionState
import com.pulsarnav.app.ble.DiscoveredBike
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.databinding.ActivityMainBinding
import com.pulsarnav.app.debug.DebugFilter
import com.pulsarnav.app.debug.DebugLogRepository
import com.pulsarnav.app.location.ParkingManager
import com.pulsarnav.app.maintenance.FuelLogManager
import com.pulsarnav.app.maintenance.MaintenanceManager
import com.pulsarnav.app.media.MediaControlManager
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.navigation.NavigationIntentLauncher
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.service.BikeBleForegroundService
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import com.pulsarnav.app.telemetry.PhoneTelemetryManager
import com.pulsarnav.app.telephony.CallManager
import com.pulsarnav.app.wifi.TftMirrorSource
import com.pulsarnav.app.wifi.TftMirrorStatus
import com.pulsarnav.app.wifi.TftNavigationMapEngine
import com.pulsarnav.app.wifi.TftPasswordGenerator
import com.pulsarnav.app.wifi.TftWifiMirrorManager
import com.pulsarnav.app.wifi.TftWifiProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val MAX_VAULT_IMPORT_BYTES = 5 * 1024 * 1024
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BajajBleManager
    private lateinit var callManager: CallManager
    private lateinit var telemetryManager: PhoneTelemetryManager
    private lateinit var appSettings: AppSettings
    private lateinit var parkingManager: ParkingManager
    private lateinit var maintenanceManager: MaintenanceManager
    private lateinit var fuelLogManager: FuelLogManager
    private lateinit var mediaControlManager: MediaControlManager

    private var currentTheme: CockpitTheme = getThemeDefinition(AppSettings.THEME_WINE_RED, isLightMode = false)

    private var pendingDocType: String? = null
    private var activeGloveboxSheet: BottomSheetDialog? = null
    private var activeGloveboxView: View? = null
    private var activeServiceHistoryDialog: AlertDialog? = null
    private var roundaboutTestState = 0
    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { saveUploadedDocument(it, pendingDocType) }
    }

    private val vaultFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importVaultFromUri(it) }
    }

    private val vaultGetContentPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importVaultFromUri(it) }
    }

    private val tftScreenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            if (appSettings.isTftCastForceLandscapeEnabled) {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            BikeBleForegroundService.startForTftLiveCast(this, result.resultCode, data)
            if (appSettings.isTftCastAutoOpenMapsEnabled) {
                showFeedback("Starting 800×480 live cast & opening Maps...")
                mainUiHandler.postDelayed({ proceedWithGoogleMapsLaunch() }, 700L)
            } else {
                showFeedback("Starting 800×480 live screen cast to TFT cluster...")
            }
        } else {
            showFeedback("Live screen cast cancelled")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                    permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }

        if (permissions[Manifest.permission.READ_PHONE_STATE] == true) {
            callManager.initialize()
            telemetryManager.initialize()
        }

        updateHardwareReadinessUi()
        updateTelephonyPermissionsUi()
        if (btGranted) {
            bleManager.startAutoConnect(force = true)
        } else {
            showFeedback("Bluetooth permissions are required to connect to your bike", isAlert = true)
        }
    }

    private val telephonyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        telemetryManager.initialize()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            callManager.initialize()
        }
        updateTelephonyPermissionsUi()
        val remaining = getMissingTelephonyPermissions()
        if (remaining.isEmpty()) {
            showFeedback("Call log, contacts, dial & SMS permissions granted")
        } else {
            showFeedback("Some telephony/POI permissions remain disabled (${remaining.size} left)", isAlert = true)
        }
        bleManager.syncPhonebookAndCalls()
        bleManager.dispatchPlaylistInfoOnBle()
    }

    private var pendingTftWifiAction: (() -> Unit)? = null
    private val tftWifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val action = pendingTftWifiAction
        pendingTftWifiAction = null
        if (getMissingTftWifiPermissions().isEmpty()) {
            action?.invoke()
        } else {
            showFeedback("Grant Nearby Wi-Fi / Location permission to start TFT Wi-Fi mirroring", isAlert = true)
        }
    }

    private var wasLiveCastActive = false
    private enum class PendingWifiActionType { NONE, MIRROR_HUD, LIVE_CAST, CONFIG_DIALOG }
    private var pendingWifiActionType = PendingWifiActionType.NONE
    private var pendingWifiAction: (() -> Unit)? = null
    private val wifiSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val action = pendingWifiAction
        val actionType = pendingWifiActionType
        pendingWifiAction = null
        pendingWifiActionType = PendingWifiActionType.NONE
        if (isWifiEnabled()) {
            showFeedback("Wi-Fi enabled")
            if (action != null) {
                action.invoke()
            } else {
                when (actionType) {
                    PendingWifiActionType.MIRROR_HUD -> startTftHudMirror()
                    PendingWifiActionType.LIVE_CAST -> checkLandscapeAndLaunchCast()
                    PendingWifiActionType.CONFIG_DIALOG -> showTftWifiConfigDialog()
                    PendingWifiActionType.NONE -> {}
                }
            }
        } else {
            showFeedback("Wi-Fi is still turned off. Wi-Fi is required to connect to cluster.", isAlert = true)
        }
    }

    private var statusDotAnimator: android.animation.ObjectAnimator? = null

    private fun startStatusDotPulse(dotView: View) {
        if (statusDotAnimator != null && statusDotAnimator?.isRunning == true) return
        statusDotAnimator = android.animation.ObjectAnimator.ofFloat(dotView, "alpha", 0.35f, 1.0f).apply {
            duration = 800L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopStatusDotPulse(dotView: View) {
        statusDotAnimator?.cancel()
        statusDotAnimator = null
        dotView.alpha = 1.0f
    }

    private var activeDebugFilter: DebugFilter = DebugFilter.ALL
    private var isDebugLogPaused: Boolean = false
    private var fullscreenTftDialog: Dialog? = null
    private var fullscreenTftImageView: ImageView? = null
    private var fullscreenTftMetricsView: TextView? = null
    private var fullscreenTftTouchView: TextView? = null
    private val isAccountTabVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var currentActiveTabId: Int = R.id.nav_home
    private var isRestoringTabSelection = false
    private var fuelListThemeDirty = true
    private var isUpdatingHexText = false
    private var pendingSeekThemeRunnable: Runnable? = null
    private val mainUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var renderLogJob: kotlinx.coroutines.Job? = null
    private var lastRenderedLogText: String? = null
    private val previewDecodeLock = Any()
    private val previewBitmapSlots = arrayOfNulls<Bitmap>(3)
    private var previewBitmapSlotIdx = 0
    private var lastDecodedFrameIndex = -1L
    private val fullscreenBitmapSlots = arrayOfNulls<Bitmap>(3)
    private var fullscreenBitmapSlotIdx = 0
    private var lastFullscreenFrameIndex = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        appSettings = AppSettings.getInstance(this)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (appSettings.isDarkMode) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdgeInsets()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.scrollFuel.visibility == View.VISIBLE ||
                    binding.scrollSupport.visibility == View.VISIBLE ||
                    binding.scrollSettings.visibility == View.VISIBLE
                ) {
                    binding.bottomNav.selectedItemId = R.id.nav_home
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Initialize Managers
        bleManager = BajajBleManager.getInstance(this)
        callManager = CallManager.getInstance(this)
        telemetryManager = PhoneTelemetryManager.getInstance(this)
        parkingManager = ParkingManager.getInstance(this)
        maintenanceManager = MaintenanceManager.getInstance(this)
        fuelLogManager = FuelLogManager.getInstance(this)
        mediaControlManager = MediaControlManager.getInstance(this)

        telemetryManager.initialize()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            callManager.initialize()
        }

        // Setup UI Sections
        setupBottomNavigation()
        setupListeners()
        setupClusterRecalibrate()
        updateBikeInfoDisplay()
        updateDocumentsSummary()
        updateServiceHistorySummary()
        updateNavAppSelectionUi()
        updateHeroRangeDisplay()
        updateBikeIssuesSummary()
        updateEmergencyHomeCard()
        updateTelephonyPermissionsUi()
        updateSmsRejectUi()
        setupSupportAndAccountDialogs()
        setupFeatureToggles()
        setupThemeSelector()
        setupQuickNav()

        // Apply saved theme
        applyTheme(appSettings.appTheme)

        // Restore active tab (handles theme recreation / configuration changes)
        val savedKey = savedInstanceState?.getString("active_tab_key")
            ?: appSettings.activeTabKey
        val savedAccountScrollY = savedInstanceState?.getInt("account_scroll_y", 0) ?: 0
        restoreActiveTab(tabKeyToId(savedKey), preservedAccountScrollY = savedAccountScrollY)

        // Observers
        observeBleState()
        observeTftWifiMirror()
        setupTftAndLogDebugger()
        observeNavigation()
        observeTelephonyAndAlerts()
        observeMaintenance()
        observeFuelLog()

        // Handle quick action intents
        intent?.let { handleIntentActions(it) }

        // Initial scan if permissions are ready
        checkAndStartScan(silent = true)

        // Show introduction / vehicle setup on first launch if not configured yet
        updateQuickNavButtons()
        updateFuelPriceUi()
        binding.tvAppVersionTitle.text = "PulsarNav Companion v${com.pulsarnav.app.BuildConfig.VERSION_NAME}"

        binding.root.post {
            if (!appSettings.isSetupCompleted) {
                showVehicleSetupBottomSheet()
            }
        }
    }

    private val hardwareStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        updateHardwareReadinessUi()
                        bleManager.onBluetoothEnabled()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        updateHardwareReadinessUi()
                        bleManager.onBluetoothDisabled()
                    }
                    else -> updateHardwareReadinessUi()
                }
            } else {
                updateHardwareReadinessUi()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAction(LocationManager.MODE_CHANGED_ACTION)
            }
        }
        ContextCompat.registerReceiver(
            this,
            hardwareStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateHardwareReadinessUi()
    }

    override fun onStop() {
        try {
            unregisterReceiver(hardwareStateReceiver)
        } catch (_: Exception) {}
        if (!isChangingConfigurations) {
            TftWifiMirrorManager.getInstance(this).stopLocalDebugPreview()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        DataVaultManager.getInstance(this).pruneExportCache()
        updateNotificationAccessUi()
        updateBatteryOptimizationUi()
        updateHardwareReadinessUi()
        updateTelephonyPermissionsUi()
        updateSmsRejectUi()
        updateBikeInfoDisplay()
        updateQuickNavButtons()
        updateFuelPriceUi()
        updateBikeIssuesSummary()
        updateNavAppSelectionUi()

        // Auto-connect when returning to the app if Bluetooth is active and not connected
        if (bleManager.connectionState.value is BleConnectionState.Disconnected) {
            checkAndStartScan(silent = true)
        }
    }

    override fun onDestroy() {
        pendingSeekThemeRunnable?.let { mainUiHandler.removeCallbacks(it) }
        pendingSeekThemeRunnable = null
        renderLogJob?.cancel()
        renderLogJob = null
        fullscreenTftDialog?.dismiss()
        fullscreenTftDialog = null
        fullscreenTftImageView = null
        fullscreenTftMetricsView = null
        fullscreenTftTouchView = null
        if (::binding.isInitialized) {
            binding.labsTftDebug.ivTftDebugPreview.setImageDrawable(null)
        }
        synchronized(previewDecodeLock) {
            previewBitmapSlots.fill(null)
            fullscreenBitmapSlots.fill(null)
        }
        if (!isChangingConfigurations) {
            TftWifiMirrorManager.getInstance(this).stopLocalDebugPreview()
        }
        if (isFinishing && !isChangingConfigurations) {
            DataVaultManager.getInstance(this).clearExportCache()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntentActions(it) }
    }

    private fun handleIntentActions(intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            "com.pulsarnav.action.NAV_HOME" -> {
                val addr = appSettings.homeAddress
                launchNavigationTo(if (addr.isNotBlank() && !addr.equals("Home", ignoreCase = true)) addr else "Home")
            }
            "com.pulsarnav.action.NAV_WORK" -> {
                val addr = appSettings.workAddress
                launchNavigationTo(if (addr.isNotBlank() && !addr.equals("Work", ignoreCase = true)) addr else "Work")
            }
            "com.pulsarnav.action.FIND_BIKE" -> {
                openParkedBikeLocation()
            }
            "com.pulsarnav.action.LOG_REFUEL" -> {
                showAddFuelDialog()
            }
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                if (!sharedText.isNullOrBlank()) {
                    startTftRouteFromSharedInput(sharedText)
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    if (uri.scheme.equals("geo", ignoreCase = true) ||
                        uri.scheme.equals("google.navigation", ignoreCase = true)
                    ) {
                        startTftRouteFromSharedInput(uri.toString())
                    } else {
                        showExternalVaultImportConfirmation(uri)
                    }
                }
            }
        }
        // Consume intent so it does not re-trigger on activity recreation (e.g. rotation, theme changes)
        intent.action = null
        intent.data = null
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_SUBJECT)
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun openParkedBikeLocation() {
        val primaryIntent = parkingManager.getWalkingDirectionsIntent()
        val fallbackIntent = parkingManager.getFallbackWalkingDirectionsIntent()
        val parked = parkingManager.lastParkedLocation.value
        if (primaryIntent == null || parked == null) {
            showFeedback("No parked bike location saved yet")
            return
        }
        try {
            startActivity(primaryIntent)
            showFeedback("Walking directions to ${parked.address}")
        } catch (_: Exception) {
            if (fallbackIntent != null) {
                try {
                    startActivity(fallbackIntent)
                    showFeedback("Opening parked location: ${parked.address}")
                } catch (_: Exception) {
                    showFeedback("Unable to open Maps for parked location", isAlert = true)
                }
            }
        }
    }

    private fun parseDecimal(raw: CharSequence?): Double? =
        raw?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()

    private fun formatDecimal(value: Number, decimals: Int = 1): String =
        String.format(Locale.US, "%.${decimals}f", value.toDouble())

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val tabKey = tabIdToKey(currentActiveTabId)
        outState.putString("active_tab_key", tabKey)
        outState.putString("key_pending_wifi_action", pendingWifiActionType.name)
        if (::binding.isInitialized) {
            outState.putInt("account_scroll_y", binding.scrollSettings.scrollY)
        }
        if (::appSettings.isInitialized) {
            appSettings.activeTabKey = tabKey
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val savedKey = savedInstanceState.getString("active_tab_key")
        val savedPendingAction = savedInstanceState.getString("key_pending_wifi_action")
        if (savedPendingAction != null) {
            try {
                pendingWifiActionType = PendingWifiActionType.valueOf(savedPendingAction)
            } catch (_: Exception) {}
        }
        val savedTab = if (savedKey != null) tabKeyToId(savedKey) else currentActiveTabId
        val savedAccountScrollY = savedInstanceState.getInt("account_scroll_y", 0)
        if (savedTab != currentActiveTabId) {
            restoreActiveTab(savedTab, preservedAccountScrollY = savedAccountScrollY)
        } else if (savedTab == R.id.nav_account && savedAccountScrollY > 0) {
            binding.scrollSettings.post { binding.scrollSettings.scrollTo(0, savedAccountScrollY) }
        }
    }

    private fun setupEdgeToEdgeInsets() {
        val baseTopLeft = binding.layoutTopBar.paddingLeft
        val baseTopTop = binding.layoutTopBar.paddingTop
        val baseTopRight = binding.layoutTopBar.paddingRight
        val baseTopBottom = binding.layoutTopBar.paddingBottom

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val sysBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())

            binding.layoutTopBar.setPadding(
                baseTopLeft + sysBars.left,
                baseTopTop + sysBars.top,
                baseTopRight + sysBars.right,
                baseTopBottom
            )

            if (imeVisible) {
                if (binding.bottomNav.visibility != View.GONE) {
                    binding.bottomNav.visibility = View.GONE
                }
                root.setPadding(0, 0, 0, imeInsets.bottom)
            } else {
                if (binding.bottomNav.visibility != View.VISIBLE) {
                    binding.bottomNav.visibility = View.VISIBLE
                }
                binding.bottomNav.setPadding(sysBars.left, 0, sysBars.right, sysBars.bottom)
                root.setPadding(0, 0, 0, 0)
            }
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
    }

    private fun tabIdToKey(tabId: Int): String = when (tabId) {
        R.id.nav_fuel -> AppSettings.TAB_FUEL
        R.id.nav_support -> AppSettings.TAB_SUPPORT
        R.id.nav_account -> AppSettings.TAB_ACCOUNT
        else -> AppSettings.TAB_HOME
    }

    private fun tabKeyToId(key: String?): Int = when (AppSettings.normalizeTabKey(key)) {
        AppSettings.TAB_FUEL -> R.id.nav_fuel
        AppSettings.TAB_SUPPORT -> R.id.nav_support
        AppSettings.TAB_ACCOUNT -> R.id.nav_account
        else -> R.id.nav_home
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (isRestoringTabSelection) return@setOnItemSelectedListener true
            provideHapticFeedback()
            if (item.itemId == currentActiveTabId) return@setOnItemSelectedListener true
            currentActiveTabId = item.itemId
            if (::appSettings.isInitialized) {
                appSettings.activeTabKey = tabIdToKey(item.itemId)
            }
            when (item.itemId) {
                R.id.nav_home -> switchTab(binding.scrollCockpit)
                R.id.nav_fuel -> switchTab(binding.scrollFuel)
                R.id.nav_support -> switchTab(binding.scrollSupport)
                R.id.nav_account -> switchTab(binding.scrollSettings)
                else -> false
            }
        }
    }

    private fun restoreActiveTab(tabId: Int, preservedAccountScrollY: Int = 0) {
        val targetView = when (tabId) {
            R.id.nav_fuel -> binding.scrollFuel
            R.id.nav_support -> binding.scrollSupport
            R.id.nav_account -> binding.scrollSettings
            else -> binding.scrollCockpit
        }
        val normalizedId = when (targetView) {
            binding.scrollFuel -> R.id.nav_fuel
            binding.scrollSupport -> R.id.nav_support
            binding.scrollSettings -> R.id.nav_account
            else -> R.id.nav_home
        }
        currentActiveTabId = normalizedId
        if (binding.bottomNav.selectedItemId != normalizedId) {
            isRestoringTabSelection = true
            try {
                binding.bottomNav.selectedItemId = normalizedId
            } finally {
                isRestoringTabSelection = false
            }
        }
        switchTab(
            targetView,
            resetAccountScrollToTop = (normalizedId == R.id.nav_account && preservedAccountScrollY == 0)
        )
        if (normalizedId == R.id.nav_account && preservedAccountScrollY > 0) {
            binding.scrollSettings.post { binding.scrollSettings.scrollTo(0, preservedAccountScrollY) }
        }
    }

    private fun switchTab(targetView: View, resetAccountScrollToTop: Boolean = true): Boolean {
        val visibleCount = (if (binding.scrollCockpit.visibility == View.VISIBLE) 1 else 0) +
            (if (binding.scrollFuel.visibility == View.VISIBLE) 1 else 0) +
            (if (binding.scrollSupport.visibility == View.VISIBLE) 1 else 0) +
            (if (binding.scrollSettings.visibility == View.VISIBLE) 1 else 0)
        val alreadyVisible = targetView.visibility == View.VISIBLE && visibleCount == 1
        if (!alreadyVisible) {
            binding.scrollCockpit.visibility = if (targetView === binding.scrollCockpit) View.VISIBLE else View.GONE
            binding.scrollFuel.visibility = if (targetView === binding.scrollFuel) View.VISIBLE else View.GONE
            binding.scrollSupport.visibility = if (targetView === binding.scrollSupport) View.VISIBLE else View.GONE
            binding.scrollSettings.visibility = if (targetView === binding.scrollSettings) View.VISIBLE else View.GONE
        }

        val isAccount = targetView === binding.scrollSettings
        isAccountTabVisible.value = isAccount
        if (isAccount) {
            if (!alreadyVisible && resetAccountScrollToTop) {
                binding.scrollSettings.scrollTo(0, 0)
            }
            if (lastRenderedLogText == null) {
                renderDebugLogsUi(DebugLogRepository.entries.value, forceRender = true)
            }
        } else if (fullscreenTftDialog?.isShowing != true) {
            val mirrorMgr = TftWifiMirrorManager.getInstance(this)
            if (mirrorMgr.isLocalPreviewActiveFlow.value) {
                mirrorMgr.stopLocalDebugPreview()
            }
        }

        if (targetView === binding.scrollFuel && fuelListThemeDirty && ::fuelLogManager.isInitialized) {
            fuelListThemeDirty = false
            updateFuelList(fuelLogManager.entries.value)
        }
        return true
    }

    private fun setupClusterRecalibrate() {
        binding.btnTopSos.setOnClickListener {
            provideHapticFeedback()
            showEmergencyQuickActionsSheet()
        }
        binding.btnRecalibrateCluster.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }
        binding.layoutProjectedRange.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }
        binding.layoutHomeOdometer.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }
        binding.btnEditOdo.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }
    }

    private fun recordSyncTime() {
        appSettings.lastSyncedTimestampMs = System.currentTimeMillis()
        updateSyncTimeDisplay()
    }

    private fun updateSyncTimeDisplay() {
        val syncMs = appSettings.lastSyncedTimestampMs
        if (syncMs <= 0L) {
            binding.tvSyncTimeSubtext.text = "Not synced yet • Tap Recalibrate"
            return
        }
        val elapsedMinutes = ((System.currentTimeMillis() - syncMs) / (60 * 1000)).coerceAtLeast(0)
        val text = when {
            elapsedMinutes < 1 -> "Synced just now"
            elapsedMinutes < 60 -> "Synced ${elapsedMinutes}m ago"
            else -> {
                val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                "Synced at ${sdf.format(Date(syncMs))}"
            }
        }
        binding.tvSyncTimeSubtext.text = text
    }

    private fun updateHeroRangeDisplay() {
        val dteKm = appSettings.projectedDteKm
        val fuelPercent = appSettings.fuelLevelPercent
        val fuelLiters = appSettings.fuelLevelLiters
        val model = appSettings.getActiveBikeProfile()
        val tankMax = model.tankCapacityLiters
        val reserveMax = model.reserveCapacityLiters

        if (dteKm <= 0 && fuelPercent <= 0) {
            binding.tvProjectedRange.text = "--"
            binding.tvDteSubtext.text = "Tap to sync"
            binding.tvFuelLevelText.text = "0% Tank Level • 0.0L / ${formatDecimal(tankMax)}L"
            binding.progressFuelLevel.progress = 0
        } else {
            binding.tvProjectedRange.text = if (dteKm > 0) dteKm.toString() else "--"
            binding.tvDteSubtext.text = "~${formatDecimal(fuelLiters.toDouble())}L in tank"
            binding.tvFuelLevelText.text = "${fuelPercent}% Tank Level • ${formatDecimal(fuelLiters.toDouble())}L / ${formatDecimal(tankMax)}L"
            binding.progressFuelLevel.progress = fuelPercent
        }

        // Unified Odometer & Mileage metrics across Home and Account tabs
        val totalOdo = maintenanceManager.status.value.totalOdometerKm
        binding.tvHomeOdometer.text = formatDecimal(totalOdo)
        binding.tvAccountOdometer.text = "${formatDecimal(totalOdo)} km"

        val fuelStats = fuelLogManager.stats.value
        if (fuelStats.averageKmpl > 0.0) {
            binding.tvHomeMileage.text = "${formatDecimal(fuelStats.averageKmpl)} km/l avg"
        } else {
            binding.tvHomeMileage.text = "-- km/l"
        }

        binding.tvTankSpecs.text = "${formatDecimal(tankMax)}L Tank Capacity • ${formatDecimal(reserveMax)}L Res"
        binding.tvTankReserveNote.text = "Reserve triggers below ${formatDecimal(reserveMax)}L"
        updateSyncTimeDisplay()
    }

    private fun updateBikeInfoDisplay() {
        val model = appSettings.getActiveBikeProfile()
        val country = appSettings.getActiveCountryProfile()
        val localizedName = VehicleCatalog.getLocalizedBikeName(model, country)

        binding.tvBikeName.text = localizedName
        val reg = if (appSettings.bikeRegNumber.isNotBlank()) appSettings.bikeRegNumber else "Add Number Plate"
        binding.tvBikeReg.text = "$reg • ${country.labelTag} ${country.name}"
        binding.tvProfileName.text = appSettings.riderName.ifBlank { "Rider Profile" }
        binding.tvTankSpecs.text = "${formatDecimal(model.tankCapacityLiters)}L Tank Capacity • ${formatDecimal(model.reserveCapacityLiters)}L Res"
    }

    private fun updateDocumentsSummary() {
        val rc = appSettings.docRcNumber.ifBlank { "No RC" }
        val ins = appSettings.docInsuranceExpiry.ifBlank { "No Ins" }
        val puc = appSettings.docPucExpiry.ifBlank { "No PUC" }
        val hasRc = appSettings.docRcPath.isNotBlank() && File(appSettings.docRcPath).exists()
        val hasIns = appSettings.docInsurancePath.isNotBlank() && File(appSettings.docInsurancePath).exists()
        val hasPuc = appSettings.docPucPath.isNotBlank() && File(appSettings.docPucPath).exists()
        val uploadedCount = listOf(hasRc, hasIns, hasPuc).count { it }

        binding.tvDocumentsSubtext.text = if (appSettings.docRcNumber.isNotBlank() || uploadedCount > 0) {
            "RC ($rc) • Ins ($ins) • PUC ($puc)"
        } else {
            "Upload RC, Insurance & PUC certificates"
        }
        val insStatus = GloveboxExpiryEvaluator.evaluate(hasIns, appSettings.docInsuranceExpiry)
        val pucStatus = GloveboxExpiryEvaluator.evaluate(hasPuc, appSettings.docPucExpiry)
        binding.tvDocumentsBadge.text = when {
            insStatus == GloveboxDocumentStatus.EXPIRED || pucStatus == GloveboxDocumentStatus.EXPIRED ->
                "$uploadedCount • EXPIRED"
            insStatus == GloveboxDocumentStatus.EXPIRING_SOON || pucStatus == GloveboxDocumentStatus.EXPIRING_SOON ->
                "$uploadedCount • EXPIRING"
            uploadedCount > 0 -> "$uploadedCount Uploaded"
            else -> "0 Uploaded"
        }
    }

    private fun updateBikeInvestmentDisplay() {
        val currency = appSettings.currentCurrencySymbol
        val fuelSpent = fuelLogManager.stats.value.totalSpentInr
        val serviceSpent = maintenanceManager.getTotalServiceCost()
        val totalSpent = fuelSpent + serviceSpent
        val entries = fuelLogManager.entries.value

        binding.tvTotalInvestment.text = "%s%.0f".format(currency, totalSpent)
        binding.tvInvestmentSubtext.text = "%s%.0f Fuel • %s%.0f Service".format(currency, fuelSpent, currency, serviceSpent)
        binding.tvFuelEntriesCount.text = "${entries.size} refill(s) logged"

        val fuelRatio = if (totalSpent > 0.0) ((fuelSpent / totalSpent) * 100).toInt().coerceIn(0, 100) else 0
        binding.progressFuelInvestment.progress = fuelRatio
        binding.tvFuelRatioCaption.text = if (totalSpent > 0.0) {
            "Fuel refills account for $fuelRatio% of total bike expenses"
        } else {
            "Expense distribution across fuel refills and maintenance"
        }
    }

    private fun updateServiceHistorySummary() {
        val records = maintenanceManager.getServiceHistory()
        val totalServiceSpent = maintenanceManager.getTotalServiceCost()
        val currency = appSettings.currentCurrencySymbol
        val serviceInterval = MaintenanceManager.SERVICE_INTERVAL_KM

        binding.tvSupportTotalInvested.text = String.format(Locale.US, "%s%.0f", currency, totalServiceSpent)
        binding.tvSupportServiceRecordsCount.text = String.format(Locale.US, "%d service(s) logged", records.size)

        val maint = maintenanceManager.status.value
        if (maint.isServiceOverdue) {
            binding.tvSupportServiceRemaining.text = "OVERDUE"
            binding.tvSupportServiceRemainingUnit.visibility = View.GONE
            binding.tvSupportServiceRemaining.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
            binding.tvServiceHistorySummary.text = "Service Overdue • Immediate maintenance required"
            binding.progressSupportService.progress = 100
            binding.tvSupportHealthNote.text = "Schedule periodic service to maintain engine longevity"
        } else {
            binding.tvSupportServiceRemaining.text = String.format(Locale.US, "%.0f", maint.serviceRemainingKm.coerceAtLeast(0.0))
            binding.tvSupportServiceRemainingUnit.visibility = View.VISIBLE
            binding.tvSupportServiceRemaining.setTextColor(currentTheme.accentColor)

            val serviceElapsed = (serviceInterval - maint.serviceRemainingKm.coerceIn(0.0, serviceInterval))
            val serviceProgress = ((serviceElapsed / serviceInterval) * 100).toInt().coerceIn(0, 100)
            binding.progressSupportService.progress = serviceProgress
            binding.tvServiceHistorySummary.text = "Periodic Service • $serviceProgress% of cycle elapsed"
            binding.tvSupportHealthNote.text = String.format(Locale.US, "Next routine service due in %.0f km", maint.serviceRemainingKm.coerceAtLeast(0.0))
        }
        binding.tvSupportChainRemaining.text = String.format(Locale.US, "Chain Lube: %.0f km left", maint.chainLubeRemainingKm.coerceAtLeast(0.0))
        binding.tvSupportServiceStatusPill.text = String.format(Locale.US, "%,.0f km Cycle", serviceInterval)

        updateBikeInvestmentDisplay()
    }

    private fun showClusterRecalibrateBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_recalibrate_cluster, null)
        dialog.setContentView(sheetView)

        val model = appSettings.getActiveBikeProfile()
        val tankMax = model.tankCapacityLiters.toFloat().coerceAtLeast(1.0f)
        val reserveL = model.reserveCapacityLiters.toFloat().coerceIn(0.5f, tankMax)
        val quarterL = (tankMax * 0.25f)
        val halfL = (tankMax * 0.50f)
        val estMileage = fuelLogManager.stats.value.averageKmpl.takeIf { it > 0.0 } ?: 35.0

        val etDte = sheetView.findViewById<EditText>(R.id.etRecalibrateDte)
        val etOdo = sheetView.findViewById<EditText>(R.id.etRecalibrateOdo)
        val etFuel = sheetView.findViewById<EditText>(R.id.etRecalibrateFuelLiters)
        val tvFuelSub = sheetView.findViewById<TextView>(R.id.tvRecalibrateFuelSubtitle)
        listOfNotNull(etDte, etOdo, etFuel).forEach { applyThemeToEditText(it) }
        val chipReserve = sheetView.findViewById<MaterialButton>(R.id.chipPresetReserve)
        val chipQuarter = sheetView.findViewById<MaterialButton>(R.id.chipPresetQuarter)
        val chipHalf = sheetView.findViewById<MaterialButton>(R.id.chipPresetHalf)
        val chipFull = sheetView.findViewById<MaterialButton>(R.id.chipPresetFull)
        val btnSave = sheetView.findViewById<MaterialButton>(R.id.btnSaveRecalibration)

        tvFuelSub?.text = "Quick select from cluster fuel bars or enter liters (Total ${formatDecimal(tankMax.toDouble())}L)"
        chipReserve?.text = "Reserve\n(${formatDecimal(reserveL.toDouble())}L)"
        chipQuarter?.text = "1/4 Tank\n(${formatDecimal(quarterL.toDouble())}L)"
        chipHalf?.text = "1/2 Tank\n(${formatDecimal(halfL.toDouble())}L)"
        chipFull?.text = "Full\n(${formatDecimal(tankMax.toDouble())}L)"

        val currentOdo = maintenanceManager.status.value.totalOdometerKm
        val currentDte = appSettings.projectedDteKm
        if (currentDte > 0) {
            etDte?.setText(currentDte.toString())
        } else {
            etDte?.setText("")
        }
        etOdo?.setText(formatDecimal(currentOdo))
        etFuel?.setText(formatDecimal(appSettings.fuelLevelLiters.toDouble()))

        btnSave?.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
        btnSave?.setTextColor(currentTheme.onPrimaryColor)

        val updatePresetChips = { selectedLiters: Float ->
            val presets = listOf(
                chipReserve to reserveL,
                chipQuarter to quarterL,
                chipHalf to halfL,
                chipFull to tankMax
            )
            for ((chip, lit) in presets) {
                if (chip != null) {
                    val isSelected = Math.abs(selectedLiters - lit) < 0.15f
                    if (isSelected) {
                        chip.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                        chip.strokeColor = ColorStateList.valueOf(currentTheme.accentColor)
                        chip.setTextColor(currentTheme.accentColor)
                    } else {
                        chip.backgroundTintList = ColorStateList.valueOf(currentTheme.cardColor)
                        chip.strokeColor = ColorStateList.valueOf(currentTheme.cardBorderColor)
                        chip.setTextColor(currentTheme.textSecondaryColor)
                    }
                }
            }
        }

        updatePresetChips(appSettings.fuelLevelLiters)

        val autoFillDteIfEmpty = { litersSelected: Float ->
            val current = etDte?.text?.toString()?.trim().orEmpty()
            if (current.isEmpty() || current == "0") {
                etDte?.setText((litersSelected * estMileage).roundToInt().toString())
            }
        }

        chipReserve?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText(formatDecimal(reserveL.toDouble()))
            autoFillDteIfEmpty(reserveL)
            updatePresetChips(reserveL)
        }
        chipQuarter?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText(formatDecimal(quarterL.toDouble()))
            autoFillDteIfEmpty(quarterL)
            updatePresetChips(quarterL)
        }
        chipHalf?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText(formatDecimal(halfL.toDouble()))
            autoFillDteIfEmpty(halfL)
            updatePresetChips(halfL)
        }
        chipFull?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText(formatDecimal(tankMax.toDouble()))
            autoFillDteIfEmpty(tankMax)
            updatePresetChips(tankMax)
        }

        btnSave?.setOnClickListener {
            provideHapticFeedback()
            val enteredDte = (etDte?.text?.toString()?.trim()?.toIntOrNull() ?: appSettings.projectedDteKm).coerceAtLeast(0)
            val enteredOdo = (parseDecimal(etOdo?.text) ?: currentOdo).coerceAtLeast(0.0)
            val enteredLiters = ((parseDecimal(etFuel?.text)?.toFloat()) ?: appSettings.fuelLevelLiters).coerceIn(0.0f, tankMax)
            val percent = ((enteredLiters / tankMax) * 100f).roundToInt().coerceIn(0, 100)

            appSettings.projectedDteKm = enteredDte
            appSettings.fuelLevelPercent = percent
            appSettings.fuelLevelLiters = enteredLiters
            maintenanceManager.setOdometer(enteredOdo)
            recordSyncTime()
            updateHeroRangeDisplay()

            dialog.dismiss()
            showFeedback("Cluster synced: $enteredDte km DTE • ${formatDecimal(enteredOdo)} km ODO")
        }

        dialog.show()
    }

    private fun setupListeners() {
        // BLE Scan / Disconnect Button
        binding.btnScan.setOnClickListener {
            provideHapticFeedback()
            when (bleManager.connectionState.value) {
                is BleConnectionState.Connected -> {
                    bleManager.disconnect()
                    BikeBleForegroundService.stop(this)
                }
                is BleConnectionState.Scanning -> bleManager.stopScan()
                else -> checkAndStartScan(silent = false)
            }
        }

        // TFT Wi-Fi Full-Screen Mirroring Controls (NS400Z / Bajaj TFT)
        binding.btnConfigureTftWifi.setOnClickListener {
            provideHapticFeedback()
            showTftWifiConfigDialog()
        }
        binding.btnToggleTftHudMirror.setOnClickListener {
            provideHapticFeedback()
            val wifiMgr = TftWifiMirrorManager.getInstance(this)
            val mirrorState = wifiMgr.mirrorState.value
            if (mirrorState.status != TftMirrorStatus.IDLE && mirrorState.status != TftMirrorStatus.ERROR) {
                BikeBleForegroundService.stopTftMirror(this)
                showFeedback("TFT Wi-Fi mirror stopped")
            } else {
                ensureWifiEnabled {
                    val missingWifi = getMissingTftWifiPermissions()
                    if (missingWifi.isNotEmpty()) {
                        pendingTftWifiAction = { startTftHudMirror() }
                        tftWifiPermissionLauncher.launch(missingWifi.toTypedArray())
                    } else {
                        startTftHudMirror()
                    }
                }
            }
        }
        binding.btnCastLiveScreen.setOnClickListener {
            provideHapticFeedback()
            val mirrorState = TftWifiMirrorManager.getInstance(this).mirrorState.value
            if (mirrorState.source == TftMirrorSource.LIVE_SCREEN_CAST &&
                mirrorState.status != TftMirrorStatus.IDLE &&
                mirrorState.status != TftMirrorStatus.ERROR
            ) {
                BikeBleForegroundService.stopTftMirror(this)
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                showFeedback("Live TFT cast stopped")
            } else {
                ensureWifiEnabled {
                    val missingWifi = getMissingTftWifiPermissions()
                    if (missingWifi.isNotEmpty()) {
                        pendingTftWifiAction = { checkLandscapeAndLaunchCast() }
                        tftWifiPermissionLauncher.launch(missingWifi.toTypedArray())
                    } else {
                        checkLandscapeAndLaunchCast()
                    }
                }
            }
        }

        // Notification Access & Maps TBT Troubleshooting
        binding.btnEnableNotificationAccess.setOnClickListener {
            provideHapticFeedback()
            openNotificationListenerSettings()
        }
        binding.rowNotificationAccess.setOnClickListener {
            provideHapticFeedback()
            if (isNotificationAccessGranted()) {
                showMapsTbtTroubleshooterDialog()
            } else {
                openNotificationListenerSettings()
            }
        }
        binding.tvMapsTbtFixLink.setOnClickListener {
            provideHapticFeedback()
            showMapsTbtTroubleshooterDialog()
        }
        binding.rowBatteryOptimization.setOnClickListener {
            provideHapticFeedback()
            requestIgnoreBatteryOptimization()
        }

        // Search Bar click -> Built-in Lock-Proof TFT Route OR Google Maps
        binding.layoutSearchBar.setOnClickListener {
            provideHapticFeedback()
            showDestinationSearchAndRouteDialog()
        }

        // Quick Nearby Fuel on Fuel Tab
        binding.btnFindPumps.setOnClickListener {
            launchNearbyFuelPumps()
        }

        // Turn simulation test bench (Continuous ~800ms stream to motorcycle cluster)
        binding.btnTestLeft.setOnClickListener {
            provideHapticFeedback()
            if (bleManager.isSimulationRunning.value && bleManager.currentActiveManeuver.value == Maneuver.TURN_LEFT) {
                bleManager.stopSimulation()
                showFeedback("Turn simulation stopped")
            } else {
                bleManager.startSimulation(Maneuver.TURN_LEFT, 250, "Left onto MG Road")
                updateNavDisplay(Maneuver.TURN_LEFT, "250 m", "Left onto MG Road", "14 min ETA")
                showFeedback("Simulating Left Turn on Cluster (~800ms stream)")
            }
        }
        binding.btnTestStraight.setOnClickListener {
            provideHapticFeedback()
            if (bleManager.isSimulationRunning.value && bleManager.currentActiveManeuver.value == Maneuver.STRAIGHT) {
                bleManager.stopSimulation()
                showFeedback("Turn simulation stopped")
            } else {
                bleManager.startSimulation(Maneuver.STRAIGHT, 750, "Western Express Highway")
                updateNavDisplay(Maneuver.STRAIGHT, "750 m", "Western Express Highway", "11 min ETA")
                showFeedback("Simulating Straight on Cluster (~800ms stream)")
            }
        }
        binding.btnTestRight.setOnClickListener {
            provideHapticFeedback()
            if (bleManager.isSimulationRunning.value && bleManager.currentActiveManeuver.value == Maneuver.TURN_RIGHT) {
                bleManager.stopSimulation()
                showFeedback("Turn simulation stopped")
            } else {
                bleManager.startSimulation(Maneuver.TURN_RIGHT, 150, "Right onto Link Road")
                updateNavDisplay(Maneuver.TURN_RIGHT, "150 m", "Right onto Link Road", "8 min ETA")
                showFeedback("Simulating Right Turn on Cluster (~800ms stream)")
            }
        }
        binding.btnTestRoundabout.setOnClickListener {
            provideHapticFeedback()
            if (bleManager.isSimulationRunning.value && (bleManager.currentActiveManeuver.value == Maneuver.ROUNDABOUT_LEFT || bleManager.currentActiveManeuver.value == Maneuver.ROUNDABOUT_RIGHT)) {
                if (roundaboutTestState == 1) {
                    roundaboutTestState = 2
                    bleManager.startSimulation(Maneuver.ROUNDABOUT_LEFT, 200, "Exit 2 • Ring Road", roundaboutExit = 2)
                    updateNavDisplay(Maneuver.ROUNDABOUT_LEFT, "200 m", "Exit 2 • Ring Road", "7 min ETA")
                    binding.btnTestRoundabout.text = "↺  Testing Exit 2 (Tap to Stop)"
                    showFeedback("Simulating Roundabout Exit 2 (OTC byte 7 = 0x02)")
                } else {
                    roundaboutTestState = 0
                    bleManager.stopSimulation()
                    binding.btnTestRoundabout.text = "↺  Test Roundabout (Safe / Exit 2)"
                    showFeedback("Roundabout simulation stopped")
                }
            } else {
                roundaboutTestState = 1
                bleManager.startSimulation(Maneuver.ROUNDABOUT_LEFT, 200, "Roundabout • Ring Road", roundaboutExit = 0)
                updateNavDisplay(Maneuver.ROUNDABOUT_LEFT, "200 m", "Roundabout • Ring Road", "7 min ETA")
                binding.btnTestRoundabout.text = "↺  Testing Safe Exit 0 (Tap for Exit 2)"
                showFeedback("Simulating Safe Roundabout (Exit 0: No exit number misdirection)")
            }
        }
        binding.btnStopSimulation.setOnClickListener {
            provideHapticFeedback()
            roundaboutTestState = 0
            binding.btnTestRoundabout.text = "↺  Test Roundabout (Safe / Exit 2)"
            bleManager.stopSimulation()
            bleManager.stopNavigation()
            binding.tvDistance.text = "-- m"
            binding.tvStreetName.text = "Navigation stopped"
            binding.tvManeuverIcon.text = "⏹"
            binding.tvEtaBadge.text = "Idle"
            showFeedback("Simulation Stopped & Cluster Cleared")
        }

        // Call & WhatsApp simulation
        binding.btnSimulateCall.setOnClickListener {
            provideHapticFeedback()
            if (callManager.callInfo.value.state == CallState.NO_CALL) {
                callManager.simulateIncomingCall("Dad (Mobile)", "+91 98765 43210")
                showFeedback("Simulating Incoming Call to Cluster (tap again to end)")
            } else {
                callManager.simulateEndCall()
                showFeedback("Call Ended / Idle")
            }
        }

        binding.btnSimulateWhatsApp.setOnClickListener {
            provideHapticFeedback()
            val sampleSender = "Rahul Sharma"
            bleManager.sendWhatsAppAlert(sampleSender, com.pulsarnav.app.protocol.AlertType.WHATSAPP)
            binding.tvWhatsAppAlertBanner.text = "WhatsApp Alert: $sampleSender (Showing 6s)"
            binding.tvWhatsAppAlertBanner.setTextColor(currentTheme.accentColor)
            showFeedback("WhatsApp alert sent to cluster (6s preview)")
        }

        binding.btnSimulateSms.setOnClickListener {
            provideHapticFeedback()
            val sampleSender = "Vikram (+91 98765)"
            bleManager.sendWhatsAppAlert(sampleSender, com.pulsarnav.app.protocol.AlertType.SMS)
            binding.tvWhatsAppAlertBanner.text = "SMS Alert: $sampleSender (Showing 6s)"
            binding.tvWhatsAppAlertBanner.setTextColor(currentTheme.accentColor)
            showFeedback("SMS alert (0410 type=1) sent to cluster (6s preview)")
        }

        binding.btnSimulateMissedCall.setOnClickListener {
            provideHapticFeedback()
            callManager.simulateMissedCall("Mom", "+91 98765 12345")
            showFeedback("Simulating Missed Call Alert on Cluster")
        }

        binding.btnSyncPhonebookCalls.setOnClickListener {
            provideHapticFeedback()
            bleManager.syncPhonebookAndCalls()
            bleManager.dispatchPlaylistInfoOnBle()
            showFeedback("Syncing Favs, Call Log & Playlist to cluster...")
        }

        binding.btnGrantTelephonyPerms.setOnClickListener {
            provideHapticFeedback()
            requestTelephonyAndClusterPermissions()
        }

        // Edit Odometer
        binding.btnEditOdo.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }

        // Mark Chain Lubed
        binding.btnMarkChainLubed.setOnClickListener {
            provideHapticFeedback()
            maintenanceManager.markChainLubed()
            recordSyncTime()
            showFeedback("Chain marked cleaned & lubed (500 km reset)")
        }

        // Mark Serviced
        binding.btnMarkServiced.setOnClickListener {
            provideHapticFeedback()
            maintenanceManager.markServiceDone()
            recordSyncTime()
            val target = maintenanceManager.status.value.nextServiceTargetOdoKm
            showFeedback("Service recorded. Next service target: %.0f km".format(target))
        }

        // Add Refuel Entry
        binding.btnAddFuel.setOnClickListener {
            provideHapticFeedback()
            showAddFuelDialog()
        }
        binding.btnFuelAction.setOnClickListener {
            provideHapticFeedback()
            showAddFuelDialog()
        }
        binding.layoutFuelInvestment.setOnClickListener {
            provideHapticFeedback()
            showServiceHistoryDialog()
        }
        binding.layoutFuelMileage.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }


        // Emergency & Medical Assistance
        binding.btnEmergencyHospitals.setOnClickListener {
            launchEmergencyHospitals()
        }
        binding.btnEmergencyAmbulance.setOnClickListener {
            showEmergencyAmbulanceDialog()
        }
        binding.layoutEmergencyRiderInfo.setOnClickListener {
            showEmergencyMedicalDialog()
        }
    }

    private fun launchGoogleMapsRide() {
        if (!isNotificationAccessGranted()) {
            showNotificationAccessDialog { proceedWithGoogleMapsLaunch() }
            return
        }
        proceedWithGoogleMapsLaunch()
    }

    private fun proceedWithGoogleMapsLaunch() {
        BikeBleForegroundService.start(this)
        if (appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage("com.waze")?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("waze://?navigate=yes")).apply {
                    setPackage("com.waze")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?navigate=yes")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    return
                } catch (_: Exception) {}
            }
        }
        try {
            // First: launch Google Maps directly via launch intent (preserves active navigation or opens to current GPS location)
            val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
                return
            }
            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=maps")).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(mapIntent)
        } catch (e: Exception) {
            try {
                val travelMode = if (appSettings.isTwoWheelerMode) "two_wheeler" else "driving"
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com?travelmode=$travelMode")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e2: Exception) {
                showFeedback("Could not open navigation app", isAlert = true)
            }
        }
    }

    private fun launchNearbyFuelPumps() {
        provideHapticFeedback()
        if (appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE) {
            try {
                val uri = Uri.parse("waze://?q=gas+station&navigate=yes")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.waze")
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=gas+station&navigate=yes")))
                    return
                } catch (_: Exception) {}
            }
        }
        val mode = if (appSettings.isTwoWheelerMode) "l" else "d"
        try {
            val uri = Uri.parse("google.navigation:q=petrol+pump&mode=$mode")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=petrol+pump")))
            } catch (e2: Exception) {
                showFeedback("Could not locate nearby petrol pumps", isAlert = true)
            }
        }
    }

    private fun setupSupportAndAccountDialogs() {
        // Service History Dialog (Editable)
        binding.cardServiceHistory.setOnClickListener {
            provideHapticFeedback()
            showServiceHistoryDialog()
        }
        binding.tvServiceHistoryAction.setOnClickListener {
            provideHapticFeedback()
            showServiceHistoryDialog()
        }
        binding.layoutSupportSpent.setOnClickListener {
            provideHapticFeedback()
            showServiceHistoryDialog()
        }
        binding.layoutSupportNextService.setOnClickListener {
            provideHapticFeedback()
            showServiceHistoryDialog()
        }

        // Roadside Assistance Dialog (24x7)
        binding.rowRsa.setOnClickListener {
            provideHapticFeedback()
            showRsaDialog()
        }

        // Vehicle Manual & NS200 Specs
        binding.rowManual.setOnClickListener {
            provideHapticFeedback()
            showManualDialog()
        }

        // Support Issue Button
        binding.btnSupportIssue.setOnClickListener {
            provideHapticFeedback()
            showSupportIssueDialog()
        }


        // Digital Glovebox Documents & Uploads
        binding.rowDocuments.setOnClickListener {
            provideHapticFeedback()
            showDocumentsDialog()
        }

        // Edit Motorcycle Info
        binding.cardMyBike.setOnClickListener {
            provideHapticFeedback()
            showEditBikeInfoDialog()
        }
        binding.btnEditBikeInfo.setOnClickListener {
            provideHapticFeedback()
            showEditBikeInfoDialog()
        }
        binding.btnSetupBikeModel.setOnClickListener {
            provideHapticFeedback()
            showVehicleSetupBottomSheet()
        }

        // Hardware warning chips in top bar
        binding.chipBtWarning.setOnClickListener {
            provideHapticFeedback()
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) {
                showFeedback("Open Bluetooth Settings in Android", isAlert = true)
            }
        }
        binding.chipGpsWarning.setOnClickListener {
            provideHapticFeedback()
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Exception) {
                showFeedback("Open Location Settings in Android", isAlert = true)
            }
        }
        binding.chipPermWarning.setOnClickListener {
            provideHapticFeedback()
            val missing = getMissingPermissions()
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
            } else {
                showFeedback("All required permissions are granted")
                updateHardwareReadinessUi()
            }
        }

        // Bike Issues Checklist Card
        binding.cardBikeIssues.setOnClickListener {
            provideHapticFeedback()
            showBikeIssuesChecklistDialog()
        }

        // Service Target / Milestone Schedule
        binding.btnSetServiceTarget.setOnClickListener {
            provideHapticFeedback()
            showServiceTargetDialog()
        }

        // Fuel Price Setting Card Row
        binding.rowFuelPriceSetting.setOnClickListener {
            provideHapticFeedback()
            showEditPetrolPriceDialog()
        }

        // Edit Rider Profile
        binding.cardProfile.setOnClickListener {
            provideHapticFeedback()
            showEditRiderProfileDialog()
        }

        // Data Vault & Phone Migration
        binding.rowVaultQr.setOnClickListener {
            provideHapticFeedback()
            showDataVaultBottomSheet(openQrDirectly = true)
        }
        binding.rowVaultBackup.setOnClickListener {
            provideHapticFeedback()
            showDataVaultBottomSheet(openQrDirectly = false)
        }

        // Navigation Provider Preference in Account Tab
        binding.btnNavChooseGmaps.setOnClickListener {
            provideHapticFeedback()
            appSettings.preferredNavApp = AppSettings.NAV_APP_GOOGLE_MAPS
            updateNavAppSelectionUi()
            updateQuickNavButtons()
            showFeedback("Default navigation app set to Google Maps")
        }
        binding.btnNavChooseWaze.setOnClickListener {
            provideHapticFeedback()
            appSettings.preferredNavApp = AppSettings.NAV_APP_WAZE
            updateNavAppSelectionUi()
            updateQuickNavButtons()
            showFeedback("Default navigation app set to Waze")
        }
    }

    private fun showEditBikeInfoDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvInfo = TextView(this).apply {
            text = "Edit your motorcycle registration and specs:"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        // Bike Name
        val tvNameLabel = TextView(this).apply {
            text = "Motorcycle Name / Nickname:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNameLabel)

        val etName = createThemedEditText(hintText = "e.g. Pulsar NS200", initialText = appSettings.bikeName)
        layout.addView(etName)

        // Registration Number (Plate)
        val tvRegLabel = TextView(this).apply {
            text = "\nRegistration Number (Plate):"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvRegLabel)

        val etReg = createThemedEditText(hintText = "e.g. MH02GC2593", initialText = appSettings.bikeRegNumber)
        layout.addView(etReg)

        // Model / Spec
        val tvSpecLabel = TextView(this).apply {
            text = "\nModel & Spec Details:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvSpecLabel)

        val etSpec = createThemedEditText(hintText = "e.g. BS6 DTS-i 4V Liquid-Cooled", initialText = appSettings.bikeModelSpec)
        layout.addView(etSpec)

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Motorcycle Details")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val reg = etReg.text.toString().trim()
                val spec = etSpec.text.toString().trim()

                if (name.isNotBlank()) appSettings.bikeName = name
                if (reg.isNotBlank()) {
                    appSettings.bikeRegNumber = reg
                    appSettings.docRcNumber = reg
                }
                if (spec.isNotBlank()) appSettings.bikeModelSpec = spec

                updateBikeInfoDisplay()
                updateDocumentsSummary()
                recordSyncTime()
                showFeedback("Motorcycle details updated!")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditRiderProfileDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvInfo = TextView(this).apply {
            text = "Edit your rider profile name and contact number:"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        val tvNameLabel = TextView(this).apply {
            text = "Rider Name:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNameLabel)

        val etName = createThemedEditText(hintText = "e.g. Alex Rider", initialText = appSettings.riderName)
        layout.addView(etName)

        val tvPhoneLabel = TextView(this).apply {
            text = "\nPhone Number:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvPhoneLabel)

        val etPhone = createThemedEditText(
            hintText = "e.g. +91 98765 43210",
            initialText = appSettings.riderPhone,
            inputTypeFlags = android.text.InputType.TYPE_CLASS_PHONE
        )
        layout.addView(etPhone)

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Rider Profile")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val phone = etPhone.text.toString().trim()

                if (name.isNotBlank()) appSettings.riderName = name
                if (phone.isNotBlank()) appSettings.riderPhone = phone

                updateBikeInfoDisplay()
                recordSyncTime()
                showFeedback("Profile updated!")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showServiceHistoryDialog() {
        activeServiceHistoryDialog?.dismiss()
        activeServiceHistoryDialog = null

        val records = maintenanceManager.getServiceHistory()
        val odo = maintenanceManager.status.value.totalOdometerKm
        val currency = appSettings.currentCurrencySymbol
        val totalServiceSpent = maintenanceManager.getTotalServiceCost()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val tvSummary = TextView(this).apply {
            text = String.format(
                Locale.US,
                "Total Maintenance Investment: %s%.0f (%d services)\nNext Due at %.0f km • Tap to edit or delete:",
                currency,
                totalServiceSpent,
                records.size,
                odo + maintenanceManager.status.value.serviceRemainingKm
            )
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvSummary)

        if (records.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "No service records logged yet.\nTap 'Add Service Record' below to track maintenance."
                setTextColor(currentTheme.textTertiaryColor)
                textSize = 13f
                setPadding(10, 20, 10, 30)
            }
            layout.addView(tvEmpty)
        }

        for (rec in records) {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 20)
                }
                setCardBackgroundColor(currentTheme.cardColor)
                strokeColor = currentTheme.cardBorderColor
                strokeWidth = dpToPx(1)
                radius = dpToPx(12).toFloat()
            }

            val cardContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 24, 30, 24)
            }

            val rowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvTitle = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = rec.title
                setTextColor(currentTheme.textPrimaryColor)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            rowTop.addView(tvTitle)

            val tvCost = TextView(this).apply {
                text = rec.cost
                setTextColor(currentTheme.accentColor)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            rowTop.addView(tvCost)
            cardContent.addView(rowTop)

            val tvMeta = TextView(this).apply {
                text = "${rec.date} • ${formatDecimal(rec.odoKm)} km"
                setTextColor(currentTheme.textTertiaryColor)
                textSize = 11f
                setPadding(0, 4, 0, 4)
            }
            cardContent.addView(tvMeta)

            if (rec.items.isNotEmpty()) {
                val layoutItems = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 6, 0, 4)
                }
                for (item in rec.items) {
                    val rowItem = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 2, 0, 2)
                    }
                    val tvItemName = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "• ${item.name}"
                        setTextColor(currentTheme.textSecondaryColor)
                        textSize = 12f
                    }
                    val tvItemCost = TextView(this).apply {
                        text = if (item.cost > 0.0) String.format(Locale.US, "%s%.0f", currency, item.cost) else "--"
                        setTextColor(currentTheme.accentColor)
                        textSize = 12f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                    rowItem.addView(tvItemName)
                    rowItem.addView(tvItemCost)
                    layoutItems.addView(rowItem)
                }
                cardContent.addView(layoutItems)
            }

            if (rec.notes.isNotBlank()) {
                val tvNotes = TextView(this).apply {
                    text = rec.notes
                    setTextColor(currentTheme.textTertiaryColor)
                    textSize = 11f
                    setPadding(0, 4, 0, 0)
                }
                cardContent.addView(tvNotes)
            }

            val rowActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                setPadding(0, 10, 0, 0)
            }

            val btnEdit = MaterialButton(this).apply {
                text = "Edit"
                textSize = 11f
                setTextColor(currentTheme.accentColor)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    provideHapticFeedback()
                    showAddOrEditServiceRecordDialog(rec)
                }
            }
            rowActions.addView(btnEdit)

            val btnDelete = MaterialButton(this).apply {
                text = "Delete"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    provideHapticFeedback()
                    maintenanceManager.deleteServiceRecord(rec.id)
                    recordSyncTime()
                    updateServiceHistorySummary()
                    updateBikeInvestmentDisplay()
                    showFeedback("Record deleted")
                    showServiceHistoryDialog()
                }
            }
            rowActions.addView(btnDelete)

            cardContent.addView(rowActions)
            card.addView(cardContent)
            layout.addView(card)
        }

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        activeServiceHistoryDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Service History & Records")
            .setView(scrollView)
            .setPositiveButton("Add Service Record") { _, _ ->
                provideHapticFeedback()
                showAddOrEditServiceRecordDialog(null)
            }
            .setNegativeButton("Close", null)
            .create()
            .apply {
                setOnDismissListener {
                    if (activeServiceHistoryDialog === this) {
                        activeServiceHistoryDialog = null
                    }
                }
                show()
            }
    }

    private fun showAddOrEditServiceRecordDialog(existing: com.pulsarnav.app.maintenance.ServiceRecord?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val isEdit = existing != null

        // Title
        val tvTitleLabel = TextView(this).apply {
            text = "Service Title / Type:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvTitleLabel)

        val etTitle = createThemedEditText(
            hintText = "e.g. 4th Paid Service, Synthetic Oil Flush",
            initialText = existing?.title ?: "Periodic Maintenance"
        )
        layout.addView(etTitle)

        // Date
        val tvDateLabel = TextView(this).apply {
            text = "\nService Date:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvDateLabel)

        val today = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        val etDate = createThemedEditText(
            hintText = "Select Service Date",
            initialText = existing?.date ?: today
        ).apply {
            isFocusable = false
            isClickable = true
            isCursorVisible = false
            setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_trips, 0)
            compoundDrawablePadding = dpToPx(8)
            setOnClickListener {
                provideHapticFeedback()
                val cal = Calendar.getInstance()
                try {
                    val parsed = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).parse(text.toString().trim())
                    if (parsed != null) cal.time = parsed
                } catch (_: Exception) {}

                val dpd = DatePickerDialog(
                    this@MainActivity,
                    { _, y, m, d ->
                        cal.set(y, m, d)
                        setText(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(cal.time))
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                )
                dpd.show()
            }
        }
        layout.addView(etDate)

        // Odometer
        val currentOdo = maintenanceManager.status.value.totalOdometerKm
        val tvOdoLabel = TextView(this).apply {
            text = "\nOdometer Reading (km):"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvOdoLabel)

        val etOdo = createThemedEditText(
            hintText = "e.g. 14250.0",
            initialText = if (isEdit) formatDecimal(existing!!.odoKm) else formatDecimal(currentOdo),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        layout.addView(etOdo)

        // Total Bill Cost (Editable, auto-sums items)
        val tvCostLabel = TextView(this).apply {
            text = "\nTotal Bill Cost (%s):".format(appSettings.currentCurrencySymbol)
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvCostLabel)

        val etCost = createThemedEditText(
            hintText = "e.g. %s550".format(appSettings.currentCurrencySymbol),
            initialText = existing?.cost ?: "%s0".format(appSettings.currentCurrencySymbol)
        )
        layout.addView(etCost)

        // Repaired & Replaced Parts Itemized List (Custom Editable Rows)
        val rowHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(16), 0, dpToPx(6))
        }

        val tvPartsLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "PARTS & LABOUR (ITEMIZED):"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        rowHeader.addView(tvPartsLabel)

        val btnAddItem = MaterialButton(this).apply {
            text = "+ Add Item"
            textSize = 11f
            setTextColor(currentTheme.accentColor)
            strokeColor = ColorStateList.valueOf(currentTheme.borderColor)
            strokeWidth = dpToPx(1)
            backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
            cornerRadius = dpToPx(12)
            setPadding(dpToPx(10), 0, dpToPx(10), 0)
        }
        rowHeader.addView(btnAddItem)
        layout.addView(rowHeader)

        val itemsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }
        layout.addView(itemsContainer)

        data class ServiceRowHolder(val rowView: View, val etName: EditText, val etItemCost: EditText)
        val rowHolders = mutableListOf<ServiceRowHolder>()

        var isCostManuallyEdited = false
        var isUpdatingCostFromItems = false
        val existingCostVal = MaintenanceMath.parseCurrencyAmount(existing?.cost)
        val existingItemsSum = existing?.items?.sumOf { it.cost } ?: 0.0
        if (existing != null && existingCostVal != null &&
            existingCostVal > 0.0 &&
            kotlin.math.abs(existingCostVal - existingItemsSum) >= 0.01
        ) {
            isCostManuallyEdited = true
        }
        etCost.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isUpdatingCostFromItems) {
                    isCostManuallyEdited = true
                }
            }
        })

        fun recalculateTotalCost() {
            if (isCostManuallyEdited) return
            val sum = rowHolders.sumOf {
                parseDecimal(it.etItemCost.text) ?: 0.0
            }
            if (sum > 0.0 || (existing == null && rowHolders.isNotEmpty())) {
                isUpdatingCostFromItems = true
                try {
                    etCost.setText(
                        "%s%s".format(
                            appSettings.currentCurrencySymbol,
                            MaintenanceMath.formatCurrencyAmount(sum)
                        )
                    )
                } finally {
                    isUpdatingCostFromItems = false
                }
            }
        }

        fun addItemRow(name: String = "", itemCost: Double = 0.0) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(4), 0, dpToPx(4))
            }

            val etName = createThemedEditText(
                hintText = "Item / Part / Labour",
                initialText = name,
                inputTypeFlags = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.8f).apply {
                    marginEnd = dpToPx(6)
                }
            }

            val etItemCost = createThemedEditText(
                hintText = "Cost (%s)".format(appSettings.currentCurrencySymbol),
                initialText = if (itemCost > 0.0) MaintenanceMath.formatCurrencyAmount(itemCost) else "",
                inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                    marginEnd = dpToPx(6)
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        recalculateTotalCost()
                    }
                })
            }

            val btnRemove = TextView(this).apply {
                text = "✕"
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                isClickable = true
                isFocusable = true
            }

            val holder = ServiceRowHolder(row, etName, etItemCost)
            rowHolders.add(holder)

            btnRemove.setOnClickListener {
                itemsContainer.removeView(row)
                rowHolders.remove(holder)
                recalculateTotalCost()
            }

            row.addView(etName)
            row.addView(etItemCost)
            row.addView(btnRemove)
            itemsContainer.addView(row)
        }

        btnAddItem.setOnClickListener {
            provideHapticFeedback()
            addItemRow()
        }

        if (existing != null && existing.items.isNotEmpty()) {
            for (item in existing.items) {
                addItemRow(item.name, item.cost)
            }
        } else if (existing != null && existing.notes.isNotBlank()) {
            val parts = existing.notes.replace("Replaced:", "").replace("Notes:", "").split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.isNotEmpty()) {
                for (p in parts) {
                    addItemRow(p, 0.0)
                }
            } else {
                addItemRow()
            }
        } else {
            // New record: populate 2 starter rows
            addItemRow()
            addItemRow()
        }

        // Additional Custom Notes
        val tvNotesLabel = TextView(this).apply {
            text = "\nAdditional Remarks / Mechanic Notes:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNotesLabel)

        val etNotes = createThemedEditText(
            hintText = "e.g. Done at local workshop, warranty details",
            initialText = existing?.notes.orEmpty()
        )
        layout.addView(etNotes)

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (isEdit) "Edit Service Record" else "Add Service Record")
            .setView(scrollView)
            .setPositiveButton("Save Record") { _, _ ->
                val title = etTitle.text.toString().trim()
                val date = etDate.text.toString().trim()
                val odoVal = parseDecimal(etOdo.text) ?: currentOdo

                val parsedItems = rowHolders.mapNotNull {
                    val name = it.etName.text.toString().trim()
                    val c = parseDecimal(it.etItemCost.text) ?: 0.0
                    if (name.isNotBlank()) com.pulsarnav.app.maintenance.ServiceItem(name, c) else null
                }
                val costStr = etCost.text.toString().trim().ifBlank {
                    val sum = parsedItems.sumOf { it.cost }
                    "%s%s".format(
                        appSettings.currentCurrencySymbol,
                        MaintenanceMath.formatCurrencyAmount(sum)
                    )
                }
                val extraNotes = etNotes.text.toString().trim()
                val resolvedDate = date.ifBlank { today }
                val resolvedWorkshop = existing?.workshop?.takeIf { it.isNotBlank() } ?: "Self / Workshop"

                val rec = existing?.copy(
                    title = if (title.isNotBlank()) title else "Periodic Service",
                    date = resolvedDate,
                    odoKm = odoVal,
                    workshop = resolvedWorkshop,
                    cost = costStr,
                    notes = extraNotes,
                    items = parsedItems
                ) ?: com.pulsarnav.app.maintenance.ServiceRecord(
                    title = if (title.isNotBlank()) title else "Periodic Service",
                    date = resolvedDate,
                    odoKm = odoVal,
                    workshop = resolvedWorkshop,
                    cost = costStr,
                    notes = extraNotes,
                    items = parsedItems
                )

                if (isEdit) {
                    maintenanceManager.updateServiceRecord(rec)
                    showFeedback("Service record updated")
                } else {
                    maintenanceManager.addServiceRecord(rec)
                    showFeedback("Service record added")
                }

                recordSyncTime()
                updateServiceHistorySummary()
                updateBikeInvestmentDisplay()
                provideHapticFeedback()
                showServiceHistoryDialog()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                show()
            }
    }

    private fun showDocumentsDialog() {
        val sheet = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_digital_glovebox, null)
        sheet.setContentView(view)

        activeGloveboxSheet = sheet
        activeGloveboxView = view
        sheet.setOnDismissListener {
            activeGloveboxSheet = null
            activeGloveboxView = null
        }

        bindGloveboxData(view, sheet)
        sheet.show()
    }

    private fun bindGloveboxData(view: View, sheet: BottomSheetDialog) {
        // --- RC Document ---
        val rcNumber = appSettings.docRcNumber.ifBlank { "Not set" }
        val hasRcFile = appSettings.docRcPath.isNotBlank() && File(appSettings.docRcPath).exists()
        val rcFileName = if (hasRcFile) File(appSettings.docRcPath).name else "No document attached"

        view.findViewById<TextView>(R.id.tvRcNumber)?.text = rcNumber
        view.findViewById<TextView>(R.id.tvRcFileName)?.text = rcFileName
        view.findViewById<TextView>(R.id.badgeRcStatus)?.apply {
            if (hasRcFile) {
                text = "ATTACHED"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.status_connected_subtle))
            } else {
                text = "NO FILE"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.surface_elevated))
            }
        }
        view.findViewById<View>(R.id.btnViewRc)?.apply {
            isEnabled = hasRcFile
            alpha = if (hasRcFile) 1.0f else 0.4f
            setOnClickListener {
                if (hasRcFile) {
                    provideHapticFeedback()
                    viewDocument(appSettings.docRcPath, "Registration Certificate (RC)")
                }
            }
        }
        view.findViewById<View>(R.id.btnUploadRc)?.setOnClickListener {
            provideHapticFeedback()
            pendingDocType = "rc"
            try {
                documentPicker.launch(arrayOf("image/*", "application/pdf"))
            } catch (e: Exception) {
                showFeedback("Could not open file picker: ${e.message}", isAlert = true)
            }
        }
        view.findViewById<View>(R.id.btnEditRc)?.setOnClickListener {
            provideHapticFeedback()
            showEditDocumentDetailsDialog("rc", "Registration Certificate (RC)", appSettings.docRcNumber, null)
        }

        // --- Insurance Document ---
        val insPolicy = appSettings.docInsurancePolicy.ifBlank { "Not set" }
        val insExpiry = appSettings.docInsuranceExpiry.ifBlank { "Expiry not set" }
        val hasInsFile = appSettings.docInsurancePath.isNotBlank() && File(appSettings.docInsurancePath).exists()

        view.findViewById<TextView>(R.id.tvInsurancePolicy)?.text = insPolicy
        view.findViewById<TextView>(R.id.tvInsuranceExpiry)?.text = insExpiry
        applyGloveboxStatusBadge(
            badge = view.findViewById(R.id.badgeInsuranceStatus),
            hasFile = hasInsFile,
            rawExpiry = appSettings.docInsuranceExpiry,
            validLabel = "ACTIVE"
        )
        view.findViewById<View>(R.id.btnViewInsurance)?.apply {
            isEnabled = hasInsFile
            alpha = if (hasInsFile) 1.0f else 0.4f
            setOnClickListener {
                if (hasInsFile) {
                    provideHapticFeedback()
                    viewDocument(appSettings.docInsurancePath, "Comprehensive Insurance")
                }
            }
        }
        view.findViewById<View>(R.id.btnUploadInsurance)?.setOnClickListener {
            provideHapticFeedback()
            pendingDocType = "insurance"
            try {
                documentPicker.launch(arrayOf("image/*", "application/pdf"))
            } catch (e: Exception) {
                showFeedback("Could not open file picker: ${e.message}", isAlert = true)
            }
        }
        view.findViewById<View>(R.id.btnEditInsurance)?.setOnClickListener {
            provideHapticFeedback()
            showEditDocumentDetailsDialog("insurance", "Comprehensive Insurance", appSettings.docInsurancePolicy, appSettings.docInsuranceExpiry)
        }

        // --- PUC Document ---
        val pucCert = appSettings.docPucCert.ifBlank { "Not set" }
        val pucExpiry = appSettings.docPucExpiry.ifBlank { "Expiry not set" }
        val hasPucFile = appSettings.docPucPath.isNotBlank() && File(appSettings.docPucPath).exists()

        view.findViewById<TextView>(R.id.tvPucNumber)?.text = pucCert
        view.findViewById<TextView>(R.id.tvPucExpiry)?.text = pucExpiry
        applyGloveboxStatusBadge(
            badge = view.findViewById(R.id.badgePucStatus),
            hasFile = hasPucFile,
            rawExpiry = appSettings.docPucExpiry,
            validLabel = "VALID"
        )
        view.findViewById<View>(R.id.btnViewPuc)?.apply {
            isEnabled = hasPucFile
            alpha = if (hasPucFile) 1.0f else 0.4f
            setOnClickListener {
                if (hasPucFile) {
                    provideHapticFeedback()
                    viewDocument(appSettings.docPucPath, "Pollution Certificate (PUC)")
                }
            }
        }
        view.findViewById<View>(R.id.btnUploadPuc)?.setOnClickListener {
            provideHapticFeedback()
            pendingDocType = "puc"
            try {
                documentPicker.launch(arrayOf("image/*", "application/pdf"))
            } catch (e: Exception) {
                showFeedback("Could not open file picker: ${e.message}", isAlert = true)
            }
        }
        view.findViewById<View>(R.id.btnEditPuc)?.setOnClickListener {
            provideHapticFeedback()
            showEditDocumentDetailsDialog("puc", "Pollution Under Control (PUC)", appSettings.docPucCert, appSettings.docPucExpiry)
        }

        // Apply theme to glovebox cards and action buttons
        val docCards = listOf(
            view.findViewById<MaterialCardView>(R.id.cardGloveboxRc),
            view.findViewById<MaterialCardView>(R.id.cardGloveboxInsurance),
            view.findViewById<MaterialCardView>(R.id.cardGloveboxPuc)
        )
        for (c in docCards) {
            c?.setCardBackgroundColor(currentTheme.cardColor)
            c?.strokeColor = currentTheme.cardBorderColor
        }
        val actionButtons = listOf(
            view.findViewById<MaterialButton>(R.id.btnViewRc),
            view.findViewById<MaterialButton>(R.id.btnViewInsurance),
            view.findViewById<MaterialButton>(R.id.btnViewPuc)
        )
        for (btn in actionButtons) {
            btn?.setTextColor(currentTheme.accentColor)
            btn?.iconTint = ColorStateList.valueOf(currentTheme.accentColor)
            btn?.strokeColor = ColorStateList.valueOf(currentTheme.borderColor)
        }
        val secondaryButtons = listOf(
            view.findViewById<MaterialButton>(R.id.btnUploadRc),
            view.findViewById<MaterialButton>(R.id.btnUploadInsurance),
            view.findViewById<MaterialButton>(R.id.btnUploadPuc),
            view.findViewById<MaterialButton>(R.id.btnEditRc),
            view.findViewById<MaterialButton>(R.id.btnEditInsurance),
            view.findViewById<MaterialButton>(R.id.btnEditPuc)
        )
        for (btn in secondaryButtons) {
            btn?.strokeColor = ColorStateList.valueOf(currentTheme.cardBorderColor)
        }
        view.findViewById<MaterialButton>(R.id.btnGloveboxClose)?.apply {
            backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
            setTextColor(currentTheme.onPrimaryColor)
        }

        view.findViewById<View>(R.id.btnGloveboxClose)?.setOnClickListener {
            sheet.dismiss()
        }
    }

    private fun showEditDocumentDetailsDialog(
        docType: String,
        title: String,
        currentNum: String,
        currentExpiry: String?
    ) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvNumLabel = TextView(this).apply {
            text = if (docType == "insurance") "Policy Name / Number:" else "Document / Plate Number:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNumLabel)

        val etNum = createThemedEditText(initialText = currentNum.ifBlank { numberOrPolicy(docType) })
        layout.addView(etNum)

        val etExpiry = if (expiryRequired(docType)) {
            val tvExpLabel = TextView(this).apply {
                text = "\nExpiry Date:"
                setTextColor(currentTheme.textPrimaryColor)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            layout.addView(tvExpLabel)

            val et = createThemedEditText(hintText = "e.g. 15 Nov 2027", initialText = currentExpiry ?: "")
            layout.addView(et)

            val btnPickExpiry = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Pick Date from Calendar"
                textSize = 12f
                setTextColor(currentTheme.accentColor)
                strokeColor = ColorStateList.valueOf(currentTheme.borderColor)
                setOnClickListener {
                    provideHapticFeedback()
                    val cal = Calendar.getInstance()
                    GloveboxExpiryEvaluator.parseExpiryDate(et.text.toString())?.let { existingDate ->
                        cal.set(existingDate.year, existingDate.monthValue - 1, existingDate.dayOfMonth)
                    }
                    DatePickerDialog(
                        this@MainActivity,
                        { _, y, m, d ->
                            cal.set(y, m, d)
                            et.setText(SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(cal.time))
                        },
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH),
                        cal.get(Calendar.DAY_OF_MONTH)
                    ).show()
                }
            }
            layout.addView(btnPickExpiry)
            et
        } else null

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit $title")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newNum = etNum.text.toString().trim()
                val newExp = etExpiry?.text?.toString()?.trim()

                when (docType) {
                    "rc" -> {
                        if (newNum.isNotBlank()) {
                            appSettings.docRcNumber = newNum
                            appSettings.bikeRegNumber = newNum
                        }
                    }
                    "insurance" -> {
                        if (newNum.isNotBlank()) appSettings.docInsurancePolicy = newNum
                        if (newExp != null) appSettings.docInsuranceExpiry = newExp
                    }
                    "puc" -> {
                        if (newNum.isNotBlank()) appSettings.docPucCert = newNum
                        if (newExp != null) appSettings.docPucExpiry = newExp
                    }
                }

                updateDocumentsSummary()
                updateBikeInfoDisplay()
                recordSyncTime()
                activeGloveboxView?.let { v -> activeGloveboxSheet?.let { s -> bindGloveboxData(v, s) } }
                showFeedback("$title updated!")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyGloveboxStatusBadge(
        badge: TextView?,
        hasFile: Boolean,
        rawExpiry: String?,
        validLabel: String
    ) {
        badge ?: return
        val status = GloveboxExpiryEvaluator.evaluate(hasFile, rawExpiry)
        val (label, textColorRes, bgColorRes) = when (status) {
            GloveboxDocumentStatus.VALID ->
                Triple(validLabel, R.color.status_connected, R.color.status_connected_subtle)
            GloveboxDocumentStatus.EXPIRING_SOON ->
                Triple("EXPIRING", R.color.status_scanning, R.color.status_scanning_subtle)
            GloveboxDocumentStatus.EXPIRED ->
                Triple("EXPIRED", R.color.status_disconnected, R.color.status_disconnected_subtle)
            GloveboxDocumentStatus.ATTACHED_NO_EXPIRY ->
                Triple("ATTACHED", R.color.text_secondary, R.color.surface_elevated)
            GloveboxDocumentStatus.NO_FILE ->
                Triple("NO FILE", R.color.text_tertiary, R.color.surface_elevated)
        }
        badge.text = label
        badge.setTextColor(ContextCompat.getColor(this, textColorRes))
        badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgColorRes))
    }

    private fun numberOrPolicy(docType: String): String {
        return when (docType) {
            "rc" -> appSettings.docRcNumber
            "insurance" -> appSettings.docInsurancePolicy
            else -> appSettings.docPucCert
        }
    }

    private fun expiryRequired(docType: String): Boolean {
        return docType == "insurance" || docType == "puc"
    }

    private fun saveUploadedDocument(uri: Uri, docType: String?) {
        if (docType == null) return
        var targetFile: File? = null
        try {
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val mappedExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            val extension = when {
                !mappedExt.isNullOrBlank() && mappedExt.length <= 5 -> mappedExt.lowercase(Locale.US)
                mime.contains("pdf", ignoreCase = true) -> "pdf"
                mime.contains("png", ignoreCase = true) -> "png"
                mime.contains("webp", ignoreCase = true) -> "webp"
                else -> "jpg"
            }
            val docsDir = File(filesDir, "documents").apply { mkdirs() }
            val createdFile = File(docsDir, "${docType}_${System.currentTimeMillis()}.$extension")
            targetFile = createdFile

            val input = contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Unable to read selected document")
            input.use { stream ->
                createdFile.outputStream().use { output ->
                    stream.copyTo(output)
                }
            }

            val oldPath = when (docType) {
                "rc" -> appSettings.docRcPath.also { appSettings.docRcPath = createdFile.absolutePath }
                "insurance" -> appSettings.docInsurancePath.also { appSettings.docInsurancePath = createdFile.absolutePath }
                "puc" -> appSettings.docPucPath.also { appSettings.docPucPath = createdFile.absolutePath }
                else -> null
            }

            if (!oldPath.isNullOrBlank() && oldPath != createdFile.absolutePath) {
                File(oldPath).takeIf { it.exists() }?.delete()
            }

            recordSyncTime()
            updateDocumentsSummary()
            activeGloveboxView?.let { v -> activeGloveboxSheet?.let { s -> bindGloveboxData(v, s) } }
            provideHapticFeedback()
            showFeedback("Document uploaded successfully!")
        } catch (e: Exception) {
            targetFile?.takeIf { it.exists() }?.delete()
            showFeedback("Failed to upload document: ${e.message}", isAlert = true)
        }
    }

    private fun viewDocument(path: String, title: String) {
        val file = File(path)
        if (!file.exists()) {
            showFeedback("Document file not found on device", isAlert = true)
            return
        }

        try {
            val fileUri: Uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val mimeType = if (file.name.endsWith(".pdf", ignoreCase = true)) {
                "application/pdf"
            } else {
                "image/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "View $title"))
        } catch (e: Exception) {
            showFeedback("Could not open document viewer: ${e.message}", isAlert = true)
        }
    }

    private fun showRsaDialog() {
        val country = appSettings.getActiveCountryProfile()
        val entries = linkedMapOf<String, String>()
        if (country.roadsideAssistance.isNotBlank()) {
            entries[country.roadsideAssistance] = "Roadside Assistance (${country.roadsideAssistance})"
        }
        if (country.ambulanceNumber.isNotBlank()) {
            entries.putIfAbsent(country.ambulanceNumber, "Ambulance / Medical (${country.ambulanceNumber})")
        }
        if (country.policeNumber.isNotBlank()) {
            entries.putIfAbsent(country.policeNumber, "Police (${country.policeNumber})")
        }
        if (country.sosNumber.isNotBlank()) {
            entries.putIfAbsent(country.sosNumber, "${country.name} Emergency SOS (${country.sosNumber})")
        }
        val phones = entries.keys.toList()
        val options = entries.values.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Emergency Roadside Assistance (${country.name})")
            .setItems(options) { _, which ->
                provideHapticFeedback()
                val phone = phones.getOrNull(which) ?: country.sosNumber
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    showFeedback("Could not open dialer for $phone", isAlert = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualDialog() {
        val model = appSettings.getActiveBikeProfile()
        val country = appSettings.getActiveCountryProfile()
        val bikeTitle = VehicleCatalog.getLocalizedBikeName(model, country)

        MaterialAlertDialogBuilder(this)
            .setTitle("$bikeTitle Quick Handbook")
            .setMessage(
                "ENGINE & TRANSMISSION:\n" +
                "• ${model.engineSpec}\n\n" +
                "RECOMMENDED COLD TIRE PRESSURES:\n" +
                "• Front: ${model.coldTireFrontPsiSolo} PSI (Solo) • ${model.coldTireFrontPsiPillion} PSI (Pillion)\n" +
                "  (Size: ${model.tireFrontSize})\n" +
                "• Rear: ${model.coldTireRearPsiSolo} PSI (Solo) • ${model.coldTireRearPsiPillion} PSI (Pillion)\n" +
                "  (Size: ${model.tireRearSize})\n\n" +
                "FLUID SPECIFICATIONS:\n" +
                "• Engine Oil: ${model.engineOilSpec}\n" +
                "• Coolant: ${model.coolantSpec}\n" +
                "• Brake Fluid: DOT 4\n\n" +
                "DRIVE CHAIN & PLUGS:\n" +
                "• Free Slack: ${model.chainSlackMm}\n" +
                "• Spark Plugs: ${model.sparkPlugSpec}\n" +
                "• Fuel Tank: ${model.tankCapacityLiters} L (Reserve: ${model.reserveCapacityLiters} L)\n\n" +
                "LOCAL EMERGENCY HELPLINES (${country.name} ${country.labelTag}):\n" +
                "• Primary SOS: ${country.sosNumber}\n" +
                "• Ambulance: ${country.ambulanceNumber} • Police: ${country.policeNumber}\n" +
                "• Roadside Assistance: ${country.roadsideAssistance}"
            )
            .setPositiveButton("Got It", null)
            .setNeutralButton("Change Model") { _, _ ->
                provideHapticFeedback()
                showVehicleSetupBottomSheet()
            }
            .show()
    }

    private fun showSupportIssueDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("PulsarNav Support & Diagnostic Logs")
            .setMessage("All Turn-by-Turn GATT packets and Bluetooth logs are stored locally. Would you like to share a diagnostic log or report an issue on GitHub?")
            .setPositiveButton("Report on GitHub") { _, _ ->
                provideHapticFeedback()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Az-har/pulsarnav/issues")))
            }
            .setNegativeButton("Close", null)
            .show()
    }


    private fun setupFeatureToggles() {
        // Auto-Connect Switch
        binding.switchAutoConnect.isChecked = bleManager.isAutoConnectEnabled
        binding.switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            bleManager.isAutoConnectEnabled = isChecked
            if (isChecked) {
                checkAndStartScan(silent = true)
            }
        }
        binding.rowAutoConnect.setOnClickListener {
            provideHapticFeedback()
            binding.switchAutoConnect.toggle()
        }

        // Two-Wheeler Mode Switch
        binding.switchTwoWheeler.isChecked = appSettings.isTwoWheelerMode
        binding.switchTwoWheeler.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isTwoWheelerMode = isChecked
        }
        binding.rowTwoWheeler.setOnClickListener {
            provideHapticFeedback()
            binding.switchTwoWheeler.toggle()
        }

        // Auto Park Switch
        binding.switchAutoPark.isChecked = appSettings.isAutoParkEnabled
        binding.switchAutoPark.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isAutoParkEnabled = isChecked
        }
        binding.rowAutoPark.setOnClickListener {
            provideHapticFeedback()
            binding.switchAutoPark.toggle()
        }

        // Ride Tracking Switch
        binding.switchRideTracking.isChecked = appSettings.isRideTrackingEnabled
        binding.switchRideTracking.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isRideTrackingEnabled = isChecked
        }
        binding.rowRideTracking.setOnClickListener {
            provideHapticFeedback()
            binding.switchRideTracking.toggle()
        }

        // Media Control Switch
        binding.switchMediaControl.isChecked = appSettings.isMediaControlEnabled
        binding.switchMediaControl.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isMediaControlEnabled = isChecked
        }
        binding.rowMediaControl.setOnClickListener {
            provideHapticFeedback()
            binding.switchMediaControl.toggle()
        }

        // WhatsApp Alerts Switch
        binding.switchWhatsAppAlerts.isChecked = appSettings.isWhatsAppAlertsEnabled
        binding.switchWhatsAppAlerts.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isWhatsAppAlertsEnabled = isChecked
        }
        binding.rowWhatsAppAlerts.setOnClickListener {
            provideHapticFeedback()
            binding.switchWhatsAppAlerts.toggle()
        }

        // Telemetry Switch
        binding.switchTelemetry.isChecked = appSettings.isTelemetrySyncEnabled
        binding.switchTelemetry.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isTelemetrySyncEnabled = isChecked
        }
        binding.rowTelemetry.setOnClickListener {
            provideHapticFeedback()
            binding.switchTelemetry.toggle()
        }

        // Headunit Cluster Skip Switch
        binding.switchSkipHeadunitNav.isChecked = appSettings.isSkipClusterNavWhenHeadunit
        binding.switchSkipHeadunitNav.setOnCheckedChangeListener { _, isChecked ->
            provideHapticFeedback()
            appSettings.isSkipClusterNavWhenHeadunit = isChecked
            showFeedback(
                if (isChecked) "Cluster Nav quiet mode: active (Headunit only)"
                else "Cluster Nav mirror mode: active (Cluster + Headunit)"
            )
        }
        binding.rowSkipHeadunitNav.setOnClickListener {
            provideHapticFeedback()
            binding.switchSkipHeadunitNav.toggle()
        }

        // Reject-with-SMS Auto-Reply Switch & Template
        updateSmsRejectUi()
        binding.switchSmsReject.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isSmsRejectEnabled = isChecked
            updateSmsRejectUi()
            if (isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                showFeedback("Grant SEND_SMS permission in Settings to auto-reply from switchgear", isAlert = true)
            }
        }
        binding.rowSmsReject.setOnClickListener {
            provideHapticFeedback()
            binding.switchSmsReject.toggle()
        }
        binding.btnEditSmsRejectMsg.setOnClickListener {
            provideHapticFeedback()
            showEditSmsRejectTemplateDialog()
        }

        // Cluster Phonebook, Call Log, Dialback & SMS Permissions Row
        binding.rowTelephonyPermissions.setOnClickListener {
            provideHapticFeedback()
            requestTelephonyAndClusterPermissions()
        }
    }

    private fun getThemeDefinition(themeKey: String, isLightMode: Boolean? = null): CockpitTheme {
        val isLight = isLightMode ?: if (::appSettings.isInitialized) !appSettings.isDarkMode else false
        val r = if (::appSettings.isInitialized) appSettings.customAccentRed else 158
        val g = if (::appSettings.isInitialized) appSettings.customAccentGreen else 27
        val b = if (::appSettings.isInitialized) appSettings.customAccentBlue else 50
        return CockpitThemePalette.getThemeDefinition(themeKey, isLight, r, g, b)
    }

    private fun setupThemeSelector() {
        binding.btnModeDark.setOnClickListener {
            provideHapticFeedback()
            if (!appSettings.isDarkMode) {
                appSettings.isDarkMode = true
                appSettings.activeTabKey = AppSettings.TAB_ACCOUNT
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                applyTheme(appSettings.appTheme)
                showFeedback("Dark mode active")
            }
        }
        binding.btnModeLight.setOnClickListener {
            provideHapticFeedback()
            if (appSettings.isDarkMode) {
                appSettings.isDarkMode = false
                appSettings.activeTabKey = AppSettings.TAB_ACCOUNT
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            } else {
                applyTheme(appSettings.appTheme)
                showFeedback("Light mode active")
            }
        }

        binding.cardThemeWineRed.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_WINE_RED)
            showFeedback("Wine Red theme active")
        }
        binding.cardThemeAmoled.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_AMOLED)
            showFeedback(if (appSettings.isDarkMode) "AMOLED Pure Black active" else "Pearl Minimal active")
        }
        binding.cardThemeStealth.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_STEALTH)
            showFeedback("Stealth Mono theme active")
        }
        binding.cardThemeAmber.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_AMBER)
            showFeedback("Neon Amber theme active")
        }
        binding.cardThemeMint.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_MINT)
            showFeedback("Hyper Mint theme active")
        }
        binding.cardThemeBlue.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_BLUE)
            showFeedback("Liquid Blue theme active")
        }
        binding.cardThemeCustom.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_CUSTOM)
            showFeedback("Custom RGB theme active")
        }

        // Initialize SeekBars and Custom RGB values
        binding.seekRed.progress = appSettings.customAccentRed
        binding.seekGreen.progress = appSettings.customAccentGreen
        binding.seekBlue.progress = appSettings.customAccentBlue
        binding.tvRedValue.text = appSettings.customAccentRed.toString()
        binding.tvGreenValue.text = appSettings.customAccentGreen.toString()
        binding.tvBlueValue.text = appSettings.customAccentBlue.toString()
        val initialHex = String.format(Locale.US, "#%02X%02X%02X", appSettings.customAccentRed, appSettings.customAccentGreen, appSettings.customAccentBlue)
        isUpdatingHexText = true
        try {
            binding.etCustomHex.setText(initialHex)
        } finally {
            isUpdatingHexText = false
        }
        val initialCustomColor = appSettings.getCustomAccentColor()
        binding.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(initialCustomColor)
        binding.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(initialCustomColor)

        val commitCustomAccentTheme = { r: Int, g: Int, b: Int ->
            appSettings.setCustomAccentRgb(r, g, b)
            applyTheme(AppSettings.THEME_CUSTOM)
        }

        val onSeekChanged = { fromUser: Boolean, immediateApply: Boolean ->
            val r = binding.seekRed.progress
            val g = binding.seekGreen.progress
            val b = binding.seekBlue.progress
            binding.tvRedValue.text = r.toString()
            binding.tvGreenValue.text = g.toString()
            binding.tvBlueValue.text = b.toString()
            val hex = String.format(Locale.US, "#%02X%02X%02X", r, g, b)
            if (!binding.etCustomHex.isFocused && binding.etCustomHex.text.toString() != hex) {
                isUpdatingHexText = true
                try {
                    binding.etCustomHex.setText(hex)
                } finally {
                    isUpdatingHexText = false
                }
            }
            val color = Color.rgb(r, g, b)
            binding.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(color)
            binding.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(color)

            if (fromUser || appSettings.appTheme == AppSettings.THEME_CUSTOM) {
                pendingSeekThemeRunnable?.let { mainUiHandler.removeCallbacks(it) }
                if (immediateApply) {
                    pendingSeekThemeRunnable = null
                    commitCustomAccentTheme(r, g, b)
                } else {
                    val runnable = Runnable {
                        pendingSeekThemeRunnable = null
                        commitCustomAccentTheme(binding.seekRed.progress, binding.seekGreen.progress, binding.seekBlue.progress)
                    }
                    pendingSeekThemeRunnable = runnable
                    mainUiHandler.postDelayed(runnable, 90L)
                }
            }
        }

        val seekListener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onSeekChanged(true, false)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                provideHapticFeedback()
                onSeekChanged(true, true)
            }
        }

        binding.seekRed.setOnSeekBarChangeListener(seekListener)
        binding.seekGreen.setOnSeekBarChangeListener(seekListener)
        binding.seekBlue.setOnSeekBarChangeListener(seekListener)

        // Palette presets
        val setPreset = { r: Int, g: Int, b: Int, name: String ->
            provideHapticFeedback()
            binding.seekRed.progress = r
            binding.seekGreen.progress = g
            binding.seekBlue.progress = b
            val hex = String.format(Locale.US, "#%02X%02X%02X", r, g, b)
            isUpdatingHexText = true
            try {
                binding.etCustomHex.setText(hex)
            } finally {
                isUpdatingHexText = false
            }
            onSeekChanged(true, true)
            showFeedback("$name applied")
        }

        binding.chipPresetPurple.setOnClickListener { setPreset(130, 143, 255, "Linear Purple") }
        binding.chipPresetStrava.setOnClickListener { setPreset(252, 76, 2, "Strava Orange") }
        binding.chipPresetCyan.setOnClickListener { setPreset(6, 182, 212, "Cyber Cyan") }
        binding.chipPresetPink.setOnClickListener { setPreset(236, 72, 153, "Neon Pink") }
        binding.chipPresetLime.setOnClickListener { setPreset(132, 204, 22, "Kawasaki Lime") }

        // Manual Hex Input Handling
        val parseAndApplyHex = { showFeedbackMessage: Boolean ->
            val raw = binding.etCustomHex.text.toString().trim()
            var clean = raw.removePrefix("#").trim()
            if (clean.length == 3) {
                clean = "${clean[0]}${clean[0]}${clean[1]}${clean[1]}${clean[2]}${clean[2]}"
            }
            if (clean.length == 6) {
                try {
                    val color = Color.parseColor("#$clean")
                    val r = Color.red(color)
                    val g = Color.green(color)
                    val b = Color.blue(color)
                    binding.seekRed.progress = r
                    binding.seekGreen.progress = g
                    binding.seekBlue.progress = b
                    binding.tvRedValue.text = r.toString()
                    binding.tvGreenValue.text = g.toString()
                    binding.tvBlueValue.text = b.toString()
                    val canonicalHex = String.format(Locale.US, "#%02X%02X%02X", r, g, b)
                    if (binding.etCustomHex.text.toString() != canonicalHex) {
                        isUpdatingHexText = true
                        try {
                            binding.etCustomHex.setText(canonicalHex)
                            binding.etCustomHex.setSelection(canonicalHex.length)
                        } finally {
                            isUpdatingHexText = false
                        }
                    }
                    binding.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(color)
                    binding.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(color)

                    pendingSeekThemeRunnable?.let { mainUiHandler.removeCallbacks(it) }
                    pendingSeekThemeRunnable = null
                    appSettings.setCustomAccentRgb(r, g, b)

                    applyTheme(AppSettings.THEME_CUSTOM)
                    if (showFeedbackMessage) {
                        showFeedback("Hex accent $canonicalHex active")
                    }
                    true
                } catch (_: Exception) {
                    if (showFeedbackMessage) showFeedback("Invalid hex color")
                    false
                }
            } else {
                if (showFeedbackMessage) showFeedback("Enter a 6-digit hex code (#RRGGBB)")
                false
            }
        }

        binding.etCustomHex.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUpdatingHexText) return
                if (binding.etCustomHex.isFocused) {
                    val str = s?.toString()?.trim() ?: ""
                    val clean = str.removePrefix("#").trim()
                    if (clean.length == 6) {
                        parseAndApplyHex(false)
                    }
                }
            }
        })

        binding.etCustomHex.setOnEditorActionListener { v, _, _ ->
            provideHapticFeedback()
            val ok = parseAndApplyHex(true)
            if (ok) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
            }
            ok
        }

        binding.btnApplyHex.setOnClickListener {
            provideHapticFeedback()
            val ok = parseAndApplyHex(true)
            if (ok) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etCustomHex.windowToken, 0)
                binding.etCustomHex.clearFocus()
            }
        }
    }

    private fun applyTheme(themeKey: String) {
        val theme = getThemeDefinition(themeKey)
        currentTheme = theme
        appSettings.appTheme = themeKey

        // 1. System Navigation & Status Bars (Edge-to-Edge transparent bars + light/dark icon appearance)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = theme.isLight
            isAppearanceLightNavigationBars = theme.isLight
        }
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        // 2. Root Canvases, ScrollViews & Dividers
        binding.rootLayout.setBackgroundColor(theme.backgroundColor)
        binding.layoutTopBar.setBackgroundColor(theme.surfaceColor)
        binding.layoutContentFrame.setBackgroundColor(theme.backgroundColor)
        binding.scrollCockpit.setBackgroundColor(theme.backgroundColor)
        binding.scrollFuel.setBackgroundColor(theme.backgroundColor)
        binding.scrollSupport.setBackgroundColor(theme.backgroundColor)
        binding.scrollSettings.setBackgroundColor(theme.backgroundColor)
        binding.viewTopDivider.setBackgroundColor(theme.cardBorderColor)
        binding.tvTopBarTitle.setTextColor(theme.textPrimaryColor)

        // Appearance Mode Buttons
        if (theme.isLight) {
            binding.btnModeLight.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
            binding.btnModeLight.strokeColor = ColorStateList.valueOf(theme.accentColor)
            binding.btnModeLight.setTextColor(theme.accentColor)
            binding.btnModeLight.strokeWidth = dpToPx(2)

            binding.btnModeDark.backgroundTintList = ColorStateList.valueOf(theme.cardColor)
            binding.btnModeDark.strokeColor = ColorStateList.valueOf(theme.cardBorderColor)
            binding.btnModeDark.setTextColor(theme.textSecondaryColor)
            binding.btnModeDark.strokeWidth = dpToPx(1)
        } else {
            binding.btnModeDark.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
            binding.btnModeDark.strokeColor = ColorStateList.valueOf(theme.accentColor)
            binding.btnModeDark.setTextColor(theme.accentColor)
            binding.btnModeDark.strokeWidth = dpToPx(2)

            binding.btnModeLight.backgroundTintList = ColorStateList.valueOf(theme.cardColor)
            binding.btnModeLight.strokeColor = ColorStateList.valueOf(theme.cardBorderColor)
            binding.btnModeLight.setTextColor(theme.textSecondaryColor)
            binding.btnModeLight.strokeWidth = dpToPx(1)
        }

        // Themed Icons across all Tabs
        val allThemedIcons = listOf(
            binding.ivIconHome,
            binding.ivIconWork,
            binding.ivFuelHeaderIcon,
            binding.ivRsaIcon,
            binding.ivManualIcon,
            binding.ivIssuesIcon,
            binding.ivProfileAvatar,
            binding.ivMyBikeIcon,
            binding.ivGloveboxIcon,
            binding.ivVaultQrIcon,
            binding.ivVaultBackupIcon,
            binding.ivNavAppPrefIcon,
            binding.ivInterceptorIcon,
            binding.ivBatteryOptIcon,
            binding.ivSmsRejectIcon,
            binding.ivTelephonyPermsIcon
        )
        for (icon in allThemedIcons) {
            icon.imageTintList = ColorStateList.valueOf(theme.accentColor)
        }

        // 3. Content Cards across all Tabs
        val contentCards = listOf(
            binding.cardHeroRange,
            binding.cardConnection,
            binding.cardTftWifiMirror,
            binding.cardEmergencyHome,
            binding.cardQuickHome,
            binding.cardQuickWork,
            binding.cardNearbyPumps,
            binding.cardAddFuel,
            binding.cardFuelBreakdown,
            binding.cardFuelLog,
            binding.cardServiceHistory,
            binding.cardChainLube,
            binding.cardService,
            binding.cardSupportOptions,
            binding.cardBikeIssues,
            binding.cardProfile,
            binding.cardMyBike,
            binding.cardOwnership,
            binding.cardDataVault,
            binding.cardNavPreference,
            binding.cardHudMirror,
            binding.cardCallerId,
            binding.labsTftDebug.cardTftScreenDebugger,
            binding.labsTftDebug.cardDebugLogConsole,
            binding.cardToggles,
            binding.cardQuickDestinations,
            binding.cardAboutLegal,
            binding.cardCustomRgb
        )
        for (card in contentCards) {
            card.setCardBackgroundColor(theme.cardColor)
            card.strokeColor = theme.cardBorderColor
        }

        // 4. Theme Selection Cards
        val themeCards = listOf(
            binding.cardThemeWineRed to (themeKey == AppSettings.THEME_WINE_RED),
            binding.cardThemeAmoled to (themeKey == AppSettings.THEME_AMOLED),
            binding.cardThemeStealth to (themeKey == AppSettings.THEME_STEALTH),
            binding.cardThemeAmber to (themeKey == AppSettings.THEME_AMBER),
            binding.cardThemeMint to (themeKey == AppSettings.THEME_MINT),
            binding.cardThemeBlue to (themeKey == AppSettings.THEME_BLUE),
            binding.cardThemeCustom to (themeKey == AppSettings.THEME_CUSTOM)
        )
        for ((card, isSelected) in themeCards) {
            card.setCardBackgroundColor(theme.cardColor)
            card.strokeColor = if (isSelected) theme.accentColor else theme.cardBorderColor
            card.strokeWidth = if (isSelected) dpToPx(2) else dpToPx(1)
        }
        binding.tvThemeAmoledTitle.text = if (theme.isLight) "Pearl Minimal" else "AMOLED Black"
        binding.viewThemeAmoledDot.backgroundTintList = null
        binding.viewThemeAmoledDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (theme.isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#000000"))
            setStroke(
                dpToPx(2),
                if (theme.isLight) Color.parseColor("#94A3B8") else Color.parseColor("#52525B")
            )
        }

        // Custom RGB Swatches & Highlight
        val customColor = appSettings.getCustomAccentColor()
        binding.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(customColor)
        binding.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(customColor)
        binding.etCustomHex.background = createThemedInputDrawable()
        binding.etCustomHex.setTextColor(theme.textPrimaryColor)
        binding.etCustomHex.setHintTextColor(theme.textTertiaryColor)
        if (!binding.etCustomHex.isFocused) {
            val hex = String.format(Locale.US, "#%02X%02X%02X", appSettings.customAccentRed, appSettings.customAccentGreen, appSettings.customAccentBlue)
            if (binding.etCustomHex.text.toString() != hex) {
                isUpdatingHexText = true
                try {
                    binding.etCustomHex.setText(hex)
                } finally {
                    isUpdatingHexText = false
                }
            }
        }
        binding.btnApplyHex.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.btnApplyHex.setTextColor(theme.onPrimaryColor)

        if (themeKey == AppSettings.THEME_CUSTOM) {
            binding.cardCustomRgb.strokeColor = theme.accentColor
            binding.cardCustomRgb.strokeWidth = dpToPx(2)
        } else {
            binding.cardCustomRgb.strokeColor = theme.cardBorderColor
            binding.cardCustomRgb.strokeWidth = dpToPx(1)
        }

        // Quick Palette Chips in Custom Theme Card (adaptive for dark & light modes)
        if (theme.isLight) {
            binding.chipPresetPurple.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EEF2FF"))
            binding.chipPresetPurple.setTextColor(Color.parseColor("#4F46E5"))

            binding.chipPresetStrava.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF7ED"))
            binding.chipPresetStrava.setTextColor(Color.parseColor("#EA580C"))

            binding.chipPresetCyan.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#ECFEFF"))
            binding.chipPresetCyan.setTextColor(Color.parseColor("#0891B2"))

            binding.chipPresetPink.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FDF2F8"))
            binding.chipPresetPink.setTextColor(Color.parseColor("#DB2777"))

            binding.chipPresetLime.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F7FEE7"))
            binding.chipPresetLime.setTextColor(Color.parseColor("#4D7C0F"))
        } else {
            binding.chipPresetPurple.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#242138"))
            binding.chipPresetPurple.setTextColor(Color.parseColor("#828FFF"))

            binding.chipPresetStrava.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#331A12"))
            binding.chipPresetStrava.setTextColor(Color.parseColor("#FC4C02"))

            binding.chipPresetCyan.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#122A36"))
            binding.chipPresetCyan.setTextColor(Color.parseColor("#06B6D4"))

            binding.chipPresetPink.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#361525"))
            binding.chipPresetPink.setTextColor(Color.parseColor("#EC4899"))

            binding.chipPresetLime.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#243312"))
            binding.chipPresetLime.setTextColor(Color.parseColor("#84CC16"))
        }

        // 5. Cockpit Elements
        binding.btnTopSos.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnTopSos.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnTopSos.setTextColor(theme.accentColor)

        binding.tvProjectedRange.setTextColor(theme.accentColor)
        binding.tvDteSubtext.setTextColor(theme.accentColor)
        binding.btnRecalibrateCluster.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnRecalibrateCluster.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnRecalibrateCluster.setTextColor(theme.accentColor)
        binding.progressFuelLevel.setIndicatorColor(theme.primaryColor)
        binding.progressFuelLevel.trackColor = theme.cardBorderColor

        binding.tvQuickHomeTitle.setTextColor(theme.accentColor)
        binding.tvQuickWorkTitle.setTextColor(theme.accentColor)
        binding.cardQuickHome.strokeColor = theme.borderColor
        binding.cardQuickWork.strokeColor = theme.borderColor

        val searchBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(14).toFloat()
            setColor(if (theme.isLight) Color.parseColor("#FFFFFF") else Color.parseColor("#222128"))
            setStroke(dpToPx(1), theme.cardBorderColor)
        }
        binding.layoutSearchBar.background = searchBg
        binding.tvConnectionStatus.setTextColor(theme.textPrimaryColor)
        binding.tvSubStatus.setTextColor(theme.textSecondaryColor)

        binding.layoutManeuverIcon.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.tvManeuverIcon.setTextColor(theme.accentColor)
        binding.tvDistance.setTextColor(theme.accentColor)
        binding.tvMapsTbtFixLink.setTextColor(theme.accentColor)
        binding.tvEtaBadge.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.tvEtaBadge.setTextColor(theme.accentColor)

        // Emergency Assistance Card
        binding.tvEmergencyBadge.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.tvEmergencyBadge.setTextColor(theme.accentColor)
        binding.btnEmergencyHospitals.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnEmergencyAmbulance.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnEmergencyAmbulance.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnEmergencyAmbulance.setTextColor(theme.accentColor)
        binding.tvBloodGroupBadge.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.tvBloodGroupBadge.setTextColor(theme.accentColor)
        binding.tvEditMedicalLink.setTextColor(theme.accentColor)

        binding.btnEnableNotificationAccess.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.btnEnableNotificationAccess.setTextColor(theme.onPrimaryColor)
        binding.cardNotificationPermissionBanner.strokeColor = theme.cardBorderColor

        binding.btnFindPumps.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.btnFindPumps.setTextColor(theme.onPrimaryColor)
        binding.tvNearbyPumpsSubtext.setTextColor(theme.accentColor)
        binding.tvTankSpecs.setTextColor(theme.textPrimaryColor)

        if (bleManager.connectionState.value !is BleConnectionState.Connected) {
            binding.btnScan.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
            binding.btnScan.setTextColor(theme.onPrimaryColor)
        } else {
            binding.btnScan.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
            binding.btnScan.setTextColor(theme.accentColor)
        }

        binding.btnConfigureTftWifi.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnConfigureTftWifi.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnConfigureTftWifi.setTextColor(theme.accentColor)
        binding.btnToggleTftHudMirror.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.btnToggleTftHudMirror.setTextColor(theme.onPrimaryColor)
        binding.btnCastLiveScreen.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnCastLiveScreen.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnCastLiveScreen.setTextColor(theme.accentColor)

        // Status Banners
        binding.tvMediaBanner.setTextColor(theme.accentColor)
        binding.tvCallerStatus.setTextColor(theme.accentColor)
        binding.tvWhatsAppAlertBanner.setTextColor(theme.accentColor)
        binding.tvClusterActionStatus.setTextColor(theme.accentColor)

        // Streamlined Metrics
        binding.tvHomeOdometer.setTextColor(theme.accentColor)
        binding.tvHomeMileage.setTextColor(theme.accentColor)
        binding.tvAccountOdometer.setTextColor(theme.accentColor)
        binding.btnEditOdo.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnEditOdo.setTextColor(theme.accentColor)
        binding.btnEditOdo.strokeColor = ColorStateList.valueOf(theme.borderColor)

        // Support Tab
        binding.tvServiceHistoryAction.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.tvServiceHistoryAction.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.tvServiceHistoryAction.setTextColor(theme.accentColor)
        binding.tvSupportTotalInvested.setTextColor(theme.accentColor)
        binding.tvSupportServiceRemaining.setTextColor(theme.accentColor)
        binding.progressSupportService.setIndicatorColor(theme.primaryColor)
        binding.progressSupportService.trackColor = theme.cardBorderColor
        binding.tvIssuesAction.setTextColor(theme.accentColor)
        binding.tvServiceRemaining.setTextColor(theme.accentColor)
        binding.progressService.setIndicatorColor(theme.primaryColor)
        binding.progressService.trackColor = theme.cardBorderColor
        binding.btnMarkServiced.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnMarkServiced.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnMarkServiced.setTextColor(theme.accentColor)

        binding.btnSetServiceTarget.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnSetServiceTarget.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnSetServiceTarget.setTextColor(theme.accentColor)

        binding.tvChainRemaining.setTextColor(theme.accentColor)
        binding.progressChain.setIndicatorColor(theme.primaryColor)
        binding.progressChain.trackColor = theme.cardBorderColor

        binding.btnMarkChainLubed.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnMarkChainLubed.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnMarkChainLubed.setTextColor(theme.accentColor)

        // Fuel Tab
        binding.btnFuelAction.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnFuelAction.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnFuelAction.setTextColor(theme.accentColor)
        binding.btnAddFuel.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnAddFuel.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnAddFuel.setTextColor(theme.accentColor)
        binding.tvTotalInvestment.setTextColor(theme.accentColor)
        binding.progressFuelInvestment.setIndicatorColor(theme.primaryColor)
        binding.progressFuelInvestment.trackColor = theme.cardBorderColor

        val fuelPriceBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(if (theme.isLight) Color.parseColor("#F1F3F7") else Color.parseColor("#1C1B20"))
            setStroke(dpToPx(1), theme.cardBorderColor)
        }
        binding.rowFuelPriceSetting.background = fuelPriceBg
        binding.tvFuelPriceTitle.setTextColor(theme.textPrimaryColor)
        binding.tvFuelPriceCountrySubtitle.setTextColor(theme.textSecondaryColor)
        binding.tvEditFuelPriceAction.setTextColor(theme.accentColor)

        // Account Tab
        binding.ivProfileAvatar.imageTintList = ColorStateList.valueOf(theme.accentColor)
        binding.btnEditBikeInfo.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnEditBikeInfo.setTextColor(theme.accentColor)
        binding.btnSetupBikeModel.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnSetupBikeModel.setTextColor(theme.accentColor)
        binding.tvDocumentsBadge.setTextColor(theme.accentColor)
        binding.tvVaultQrAction.setTextColor(theme.accentColor)
        binding.tvVaultBackupAction.setTextColor(theme.accentColor)
        val compactOutlinedBtns = listOf(
            binding.btnConfigHome,
            binding.btnConfigWork,
            binding.btnConfigFavPlace,
            binding.btnEditSmsRejectMsg
        )
        for (btn in compactOutlinedBtns) {
            btn.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
            btn.strokeColor = ColorStateList.valueOf(theme.borderColor)
            btn.strokeWidth = dpToPx(1)
            btn.setTextColor(theme.accentColor)
        }

        fun applyRoundedSurfaceBox(view: View, fillColor: Int, strokeColor: Int, radiusDp: Float = 10f) {
            val pl = view.paddingLeft
            val pt = view.paddingTop
            val pr = view.paddingRight
            val pb = view.paddingBottom
            view.backgroundTintList = null
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(radiusDp.toInt()).toFloat()
                setColor(fillColor)
                setStroke(dpToPx(1), strokeColor)
            }
            view.setPadding(pl, pt, pr, pb)
        }

        val innerBoxFill = theme.surfaceColor
        val innerBoxStroke = theme.cardBorderColor
        listOf(
            binding.tvPacketHex,
            binding.tvPacketDecoded,
            binding.tvWhatsAppAlertBanner,
            binding.tvClusterActionStatus,
            binding.labsTftDebug.tvBleDiagBanner,
            binding.labsTftDebug.tvTftDbgTouchInfo,
            binding.labsTftDebug.scrollDebugLogs
        ).forEach { applyRoundedSurfaceBox(it, innerBoxFill, innerBoxStroke, 10f) }

        binding.tvPacketHex.setTextColor(theme.textSecondaryColor)
        binding.tvPacketDecoded.setTextColor(theme.textSecondaryColor)
        binding.labsTftDebug.tvBleDiagBanner.setTextColor(theme.textPrimaryColor)
        binding.labsTftDebug.tvTftDbgTouchInfo.setTextColor(theme.textSecondaryColor)
        binding.labsTftDebug.tvTftDbgMetrics.setTextColor(theme.textSecondaryColor)
        binding.labsTftDebug.tvDebugLogStats.setTextColor(theme.textSecondaryColor)
        binding.labsTftDebug.tvDebugLogOutput.setTextColor(theme.textSecondaryColor)
        applyRoundedSurfaceBox(binding.tvMissedCallBadge, theme.subtleColor, theme.borderColor, 16f)
        binding.tvMissedCallBadge.setTextColor(theme.accentColor)
        applyRoundedSurfaceBox(binding.labsTftDebug.tvTftDbgStatus, theme.subtleColor, theme.borderColor, 16f)
        if (binding.labsTftDebug.tvTftDbgStatus.text == "IDLE") {
            binding.labsTftDebug.tvTftDbgStatus.setTextColor(theme.textSecondaryColor)
        }

        val testButtons = listOf(
            binding.btnTestLeft, binding.btnTestStraight, binding.btnTestRight, binding.btnTestRoundabout,
            binding.btnSimulateCall, binding.btnSimulateWhatsApp, binding.btnSimulateSms, binding.btnSimulateMissedCall,
            binding.btnSyncPhonebookCalls, binding.btnGrantTelephonyPerms,
            binding.labsTftDebug.btnTftDbgFullscreen, binding.labsTftDebug.btnTftDbgNavV2Test,
            binding.labsTftDebug.btnTftDbgRecenter, binding.labsTftDebug.btnTftDbgDaylight,
            binding.labsTftDebug.btnTftDbgMotion, binding.labsTftDebug.btnTftDbgHitboxes,
            binding.labsTftDebug.btnLogPauseResume, binding.labsTftDebug.btnLogCopy,
            binding.labsTftDebug.btnLogShare, binding.labsTftDebug.btnLogClear
        )
        for (btn in testButtons) {
            btn.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
            btn.strokeColor = ColorStateList.valueOf(theme.borderColor)
            btn.setTextColor(theme.accentColor)
        }
        binding.labsTftDebug.pbTftJpegBudget.progressTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.labsTftDebug.btnTftDbgTogglePreview.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.labsTftDebug.btnTftDbgTogglePreview.setTextColor(theme.onPrimaryColor)
        updateDebugFilterChipsUi()
        binding.btnStopSimulation.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnStopSimulation.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnStopSimulation.setTextColor(theme.textSecondaryColor)
        updateSimulationUiState(bleManager.isSimulationRunning.value, bleManager.currentActiveManeuver.value)

        // Switches
        val switches = listOf(
            binding.switchAutoConnect,
            binding.switchTwoWheeler,
            binding.switchAutoPark,
            binding.switchRideTracking,
            binding.switchMediaControl,
            binding.switchWhatsAppAlerts,
            binding.switchTelemetry,
            binding.switchSkipHeadunitNav,
            binding.switchSmsReject
        )
        val switchStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val trackColors = intArrayOf(
            theme.primaryColor,
            theme.cardBorderColor
        )
        val thumbColors = intArrayOf(
            theme.onPrimaryColor,
            theme.textTertiaryColor
        )
        val trackTintList = ColorStateList(switchStates, trackColors)
        val thumbTintList = ColorStateList(switchStates, thumbColors)

        for (sw in switches) {
            sw.trackTintList = trackTintList
            sw.thumbTintList = thumbTintList
        }

        // Bottom Navigation Bar
        binding.bottomNav.setBackgroundColor(theme.surfaceColor)
        binding.bottomNav.itemActiveIndicatorColor = ColorStateList.valueOf(theme.subtleColor)
        val navStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val navColors = intArrayOf(
            theme.accentColor,
            theme.textTertiaryColor
        )
        val navColorList = ColorStateList(navStates, navColors)
        binding.bottomNav.itemIconTintList = navColorList
        binding.bottomNav.itemTextColor = navColorList

        // Dynamic Lists (defer off-screen Fuel list inflation until tab becomes visible)
        if (binding.scrollFuel.visibility == View.VISIBLE) {
            fuelListThemeDirty = false
            try {
                updateFuelList(fuelLogManager.entries.value)
            } catch (_: Exception) {}
        } else {
            fuelListThemeDirty = true
        }

        updateNavAppSelectionUi()
        updateHeroRangeDisplay()
        updateBikeIssuesSummary()
    }

    private fun createThemedInputDrawable(): Drawable {
        val fillColor = if (currentTheme.isLight) Color.parseColor("#FFFFFF") else Color.parseColor("#1C1B20")
        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(fillColor)
            setStroke(dpToPx(1), currentTheme.cardBorderColor)
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(fillColor)
            setStroke(dpToPx(2), currentTheme.accentColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedBg)
            addState(intArrayOf(), normalBg)
        }
    }

    private fun applyThemeToEditText(editText: EditText) {
        editText.setTextColor(currentTheme.textPrimaryColor)
        editText.setHintTextColor(currentTheme.textTertiaryColor)
        editText.highlightColor = ColorUtils.setAlphaComponent(currentTheme.accentColor, 60)
        editText.background = createThemedInputDrawable()
        editText.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            editText.textCursorDrawable = null
            editText.textSelectHandle?.setTint(currentTheme.accentColor)
            editText.textSelectHandleLeft?.setTint(currentTheme.accentColor)
            editText.textSelectHandleRight?.setTint(currentTheme.accentColor)
        }
    }

    private fun createThemedEditText(
        hintText: String? = null,
        initialText: String? = null,
        inputTypeFlags: Int? = null
    ): EditText {
        return EditText(this).apply {
            if (!hintText.isNullOrEmpty()) hint = hintText
            if (!initialText.isNullOrEmpty()) setText(initialText)
            if (inputTypeFlags != null) inputType = inputTypeFlags
            applyThemeToEditText(this)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setupQuickNav() {
        updateQuickNavButtons()

        binding.cardQuickHome.setOnClickListener {
            provideHapticFeedback()
            val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
            val addr = appSettings.homeAddress
            if (isWaze && (addr.isBlank() || addr.equals("Home", ignoreCase = true))) {
                try {
                    val uri = Uri.parse("waze://?favorite=home&navigate=yes")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.waze")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    launchNavigationTo("Home")
                }
            } else {
                launchNavigationTo(addr.ifBlank { "Home" })
            }
        }
        binding.cardQuickHome.setOnLongClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Home", 0)
            true
        }

        binding.cardQuickWork.setOnClickListener {
            provideHapticFeedback()
            val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
            val addr = appSettings.workAddress
            if (isWaze && (addr.isBlank() || addr.equals("Work", ignoreCase = true))) {
                try {
                    val uri = Uri.parse("waze://?favorite=work&navigate=yes")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.waze")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    launchNavigationTo("Work")
                }
            } else {
                launchNavigationTo(addr.ifBlank { "Work" })
            }
        }
        binding.cardQuickWork.setOnLongClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Work", 1)
            true
        }

        binding.btnConfigHome.setOnClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Home", 0)
        }

        binding.btnConfigWork.setOnClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Work", 1)
        }

        binding.btnConfigFavPlace.setOnClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Favorite", 2)
        }
    }

    private fun updateNavAppSelectionUi() {
        val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
        if (isWaze) {
            binding.tvNavAppCurrentStatus.text = "Currently: Waze (Turn-by-turn routing active)"
            binding.btnNavChooseWaze.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
            binding.btnNavChooseWaze.setTextColor(currentTheme.accentColor)
            binding.btnNavChooseWaze.strokeColor = ColorStateList.valueOf(currentTheme.borderColor)

            binding.btnNavChooseGmaps.backgroundTintList = ColorStateList.valueOf(currentTheme.cardColor)
            binding.btnNavChooseGmaps.setTextColor(currentTheme.textSecondaryColor)
            binding.btnNavChooseGmaps.strokeColor = ColorStateList.valueOf(currentTheme.cardBorderColor)
        } else {
            binding.tvNavAppCurrentStatus.text = "Currently: Google Maps (Two-Wheeler routing active)"
            binding.btnNavChooseGmaps.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
            binding.btnNavChooseGmaps.setTextColor(currentTheme.accentColor)
            binding.btnNavChooseGmaps.strokeColor = ColorStateList.valueOf(currentTheme.borderColor)

            binding.btnNavChooseWaze.backgroundTintList = ColorStateList.valueOf(currentTheme.cardColor)
            binding.btnNavChooseWaze.setTextColor(currentTheme.textSecondaryColor)
            binding.btnNavChooseWaze.strokeColor = ColorStateList.valueOf(currentTheme.cardBorderColor)
        }
    }

    private fun updateQuickNavButtons() {
        val home = appSettings.homeAddress
        val work = appSettings.workAddress
        val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
        val defaultHomeLabel = if (isWaze) "Waze Saved Home" else "Google Maps Saved Home"
        val defaultWorkLabel = if (isWaze) "Waze Saved Work" else "Google Maps Saved Work"

        binding.tvQuickHomeTitle.text = "Home"
        binding.tvQuickWorkTitle.text = "Work"

        val displayHome = if (home.isNotBlank() && !home.equals("Home", ignoreCase = true)) home else defaultHomeLabel
        val displayWork = if (work.isNotBlank() && !work.equals("Work", ignoreCase = true)) work else defaultWorkLabel
        val fav = appSettings.favPlaceAddress.trim()
        val displayFav = fav.ifBlank { "Not configured (optional 3rd cluster favorite)" }
        binding.tvSettingsHome.text = "Home: $displayHome"
        binding.tvSettingsWork.text = "Work: $displayWork"
        binding.tvSettingsFavPlace.text = "Favorite: $displayFav"
    }

    private fun provideHapticFeedback() {
        try {
            val performed = window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!performed) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                    vibratorManager?.defaultVibrator?.vibrate(
                        android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        } catch (_: Exception) {}
    }

    private fun showFeedback(
        message: String,
        actionText: String? = null,
        isAlert: Boolean = false,
        onAction: (() -> Unit)? = null
    ) {
        try {
            val snackbar = Snackbar.make(
                binding.root,
                message,
                if (actionText != null) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
            )
            snackbar.anchorView = binding.bottomNav
            snackbar.setBackgroundTint(
                if (isAlert) currentTheme.cardColor
                else currentTheme.surfaceColor
            )
            snackbar.setTextColor(
                if (isAlert) ContextCompat.getColor(this, R.color.status_disconnected)
                else currentTheme.textPrimaryColor
            )
            if (actionText != null && onAction != null) {
                snackbar.setActionTextColor(currentTheme.accentColor)
                snackbar.setAction(actionText) {
                    provideHapticFeedback()
                    onAction()
                }
            }
            snackbar.show()
        } catch (_: Exception) {}
    }

    private fun dialEmergencyNumber(number: String) {
        val cleanNumber = number.filter { it.isDigit() || it == '+' }
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber"))
            startActivity(intent)
        } catch (e: Exception) {
            showFeedback("Could not open dialer for $cleanNumber", isAlert = true)
        }
    }

    private fun showEmergencyQuickActionsSheet() {
        val dialog = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_emergency, null)
        dialog.setContentView(sheetView)

        val cardSos108 = sheetView.findViewById<MaterialCardView>(R.id.cardSos108)
        val cardSos112 = sheetView.findViewById<MaterialCardView>(R.id.cardSos112)
        val cardSosIce = sheetView.findViewById<MaterialCardView>(R.id.cardSosIce)
        val cardFindHospitals = sheetView.findViewById<MaterialCardView>(R.id.cardFindHospitals)
        val cardMedicalProfile = sheetView.findViewById<MaterialCardView>(R.id.cardMedicalProfile)
        val tvSheetIceSubtitle = sheetView.findViewById<TextView>(R.id.tvSheetIceSubtitle)
        val tvSheetBloodGroup = sheetView.findViewById<TextView>(R.id.tvSheetBloodGroup)
        val tvSheetMedicalNotes = sheetView.findViewById<TextView>(R.id.tvSheetMedicalNotes)
        val btnEditMedicalProfile = sheetView.findViewById<TextView>(R.id.btnEditMedicalProfile)

        listOf(cardSos108, cardSos112, cardSosIce, cardFindHospitals, cardMedicalProfile).forEach { card ->
            card?.setCardBackgroundColor(currentTheme.cardColor)
            card?.strokeColor = currentTheme.cardBorderColor
        }

        val icePhone = appSettings.emergencyContactPhone
        val iceName = appSettings.emergencyContactName
        if (icePhone.isNotBlank() && icePhone != "112") {
            tvSheetIceSubtitle?.text = "$iceName • $icePhone"
        } else {
            tvSheetIceSubtitle?.text = "Tap to configure ICE emergency contact"
        }

        val blood = if (appSettings.riderBloodGroup.isNotBlank() && appSettings.riderBloodGroup != "Not Specified") "Blood Group: ${appSettings.riderBloodGroup}" else "Blood Group --"
        tvSheetBloodGroup?.text = blood
        tvSheetBloodGroup?.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
        tvSheetBloodGroup?.setTextColor(currentTheme.accentColor)

        btnEditMedicalProfile?.setTextColor(currentTheme.accentColor)

        val med = appSettings.medicalNotes
        tvSheetMedicalNotes?.text = if (med.isNotBlank() && med != "No known allergies") med else "No allergies or medical notes recorded. Tap Edit to update."

        val country = appSettings.getActiveCountryProfile()
        val tvAmbTitle = sheetView.findViewById<TextView>(R.id.tvSosAmbulanceTitle)
        val tvAmbSub = sheetView.findViewById<TextView>(R.id.tvSosAmbulanceSubtitle)
        val tvNatTitle = sheetView.findViewById<TextView>(R.id.tvSosNationalTitle)
        val tvNatSub = sheetView.findViewById<TextView>(R.id.tvSosNationalSubtitle)

        tvAmbTitle?.text = "${country.ambulanceNumber} — Ambulance / Medical"
        tvAmbSub?.text = "First Responders (${country.name} ${country.labelTag})"

        tvNatTitle?.text = "${country.sosNumber} — National Emergency SOS"
        tvNatSub?.text = "Police & Emergency Response (${country.name})"

        cardSos108?.setOnClickListener {
            provideHapticFeedback()
            dialEmergencyNumber(country.ambulanceNumber)
        }

        cardSos112?.setOnClickListener {
            provideHapticFeedback()
            dialEmergencyNumber(country.sosNumber)
        }

        cardSosIce?.setOnClickListener {
            provideHapticFeedback()
            if (icePhone.isNotBlank() && icePhone != "112") {
                dialEmergencyNumber(icePhone)
            } else {
                dialog.dismiss()
                showFeedback("Please configure your ICE contact details")
                showEmergencyMedicalDialog()
            }
        }

        cardFindHospitals?.setOnClickListener {
            provideHapticFeedback()
            launchEmergencyHospitals()
        }

        btnEditMedicalProfile?.setOnClickListener {
            provideHapticFeedback()
            dialog.dismiss()
            showEmergencyMedicalDialog()
        }

        cardMedicalProfile?.setOnClickListener {
            provideHapticFeedback()
            dialog.dismiss()
            showEmergencyMedicalDialog()
        }

        dialog.show()
    }

    private fun showDataVaultBottomSheet(openQrDirectly: Boolean = false) {
        val dialog = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_data_vault, null)
        dialog.setContentView(sheetView)

        val layoutQr = sheetView.findViewById<LinearLayout>(R.id.layoutQrContainer)
        val ivQr = sheetView.findViewById<ImageView>(R.id.ivVaultQrCode)
        val btnShowQr = sheetView.findViewById<MaterialButton>(R.id.btnSheetShowQr)
        val btnScanQr = sheetView.findViewById<MaterialButton>(R.id.btnSheetScanQr)
        val btnExportVault = sheetView.findViewById<MaterialButton>(R.id.btnSheetExportVault)
        val btnImportVault = sheetView.findViewById<MaterialButton>(R.id.btnSheetImportVault)

        btnExportVault?.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
        btnExportVault?.setTextColor(currentTheme.onPrimaryColor)
        btnScanQr?.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
        btnScanQr?.setTextColor(currentTheme.accentColor)

        fun loadAndShowQr(includeSensitiveProfile: Boolean = false, passphrase: String? = null) {
            lifecycleScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.Default) {
                        DataVaultManager.getInstance(this@MainActivity).generateSetupQrBitmap(
                            width = 600,
                            height = 600,
                            includeSensitiveProfile = includeSensitiveProfile,
                            passphrase = passphrase
                        )
                    }
                    if (!dialog.isShowing) return@launch
                    ivQr?.setImageBitmap(bitmap)
                    layoutQr?.visibility = View.VISIBLE
                } catch (e: Exception) {
                    showFeedback("QR Generation failed: ${e.message}", isAlert = true)
                }
            }
        }

        if (openQrDirectly) {
            layoutQr?.visibility = View.VISIBLE
            dialog.setOnShowListener { loadAndShowQr(includeSensitiveProfile = false, passphrase = null) }
        }

        btnShowQr?.setOnClickListener {
            provideHapticFeedback()
            if (layoutQr?.visibility == View.VISIBLE) {
                layoutQr.visibility = View.GONE
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Migration QR Privacy")
                    .setMessage(
                        "Choose whether to generate a safe Bike & Theme QR (no personal/medical details) " +
                            "or an AES-256-GCM Encrypted Full Profile QR."
                    )
                    .setPositiveButton("Bike Settings Only") { _, _ ->
                        loadAndShowQr(includeSensitiveProfile = false, passphrase = null)
                    }
                    .setNeutralButton("Full Encrypted QR") { _, _ ->
                        val layout = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(48, 20, 48, 10)
                        }
                        val etPass = createThemedEditText(
                            hintText = "QR Encryption Passphrase",
                            inputTypeFlags = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        )
                        layout.addView(etPass)
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Encrypt Migration QR")
                            .setView(layout)
                            .setPositiveButton("Generate Encrypted QR") { _, _ ->
                                val pass = etPass.text.toString().trim()
                                if (pass.isBlank()) {
                                    showFeedback("Enter a passphrase to encrypt full profile QR.", isAlert = true)
                                } else {
                                    loadAndShowQr(includeSensitiveProfile = true, passphrase = pass)
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        btnScanQr?.setOnClickListener {
            provideHapticFeedback()
            dialog.dismiss()
            launchGoogleCodeScanner()
        }

        btnExportVault?.setOnClickListener {
            provideHapticFeedback()
            dialog.dismiss()
            binding.rootLayout.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    showExportVaultPassphraseDialog()
                }
            }, 250)
        }

        btnImportVault?.setOnClickListener {
            provideHapticFeedback()
            dialog.dismiss()
            binding.rootLayout.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                try {
                    vaultGetContentPicker.launch("*/*")
                } catch (e: Exception) {
                    try {
                        vaultFilePicker.launch(arrayOf("*/*"))
                    } catch (e2: Exception) {
                        showFeedback("File picker unavailable: ${e2.message}", isAlert = true)
                    }
                }
            }, 250)
        }

        dialog.show()
    }

    private fun showExportVaultPassphraseDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }
        val tvDesc = TextView(this).apply {
            text = "Recommended: enter a strong passphrase to encrypt your .pnav backup with AES-256-GCM before sharing."
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 14)
        }
        val etPassphrase = createThemedEditText(
            hintText = "Encryption passphrase",
            inputTypeFlags = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        layout.addView(tvDesc)
        layout.addView(etPassphrase)

        MaterialAlertDialogBuilder(this)
            .setTitle("Export Data Vault")
            .setView(layout)
            .setPositiveButton("Export") { _, _ ->
                val rawPass = etPassphrase.text.toString().trim()
                if (rawPass.isNotEmpty()) {
                    exportVaultFile(passphrase = rawPass, allowUnencrypted = false)
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Export Unencrypted Backup?")
                        .setMessage(
                            "Without a passphrase, the exported .pnav.json file stores emergency contacts, medical notes, " +
                                "home/work addresses, parked coordinates, and service history in plaintext."
                        )
                        .setPositiveButton("Encrypt Instead") { _, _ ->
                            showExportVaultPassphraseDialog()
                        }
                        .setNeutralButton("Export Plaintext") { _, _ ->
                            exportVaultFile(passphrase = null, allowUnencrypted = true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportVaultFile(passphrase: String? = null, allowUnencrypted: Boolean = false) {
        lifecycleScope.launch {
            try {
                android.util.Log.i("PulsarNav", "Exporting vault file...")
                val manager = DataVaultManager.getInstance(this@MainActivity)
                val file = withContext(Dispatchers.IO) {
                    manager.exportVaultToFile(passphrase, allowUnencrypted = allowUnencrypted)
                }
                android.util.Log.i("PulsarNav", "Vault file created: ${file.absolutePath}, size=${file.length()} bytes")
                val chooser = manager.createVaultShareIntent(file)
                startActivity(chooser)
                showFeedback(if (passphrase != null) "Encrypted .pnav vault exported" else "Plaintext .pnav.json vault exported")
            } catch (e: Throwable) {
                android.util.Log.e("PulsarNav", "Export failed", e)
                showFeedback("Export failed: ${e.message}", isAlert = true)
            }
        }
    }

    private fun readBoundedText(stream: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_VAULT_IMPORT_BYTES) {
                throw IllegalArgumentException("Vault file exceeds ${MAX_VAULT_IMPORT_BYTES / (1024 * 1024)} MB limit")
            }
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private suspend fun readTextFromUriSafe(uri: Uri): String? = withContext(Dispatchers.IO) {
        android.util.Log.i("PulsarNav", "Reading vault text from URI: $uri, scheme: ${uri.scheme}")
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val content = readBoundedText(stream)
                if (content.isNotBlank()) return@withContext content
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("PulsarNav", "openInputStream failed for $uri: ${e.message}")
        }

        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val filePath = uri.path
            if (!filePath.isNullOrBlank()) {
                try {
                    val file = File(filePath)
                    if (file.exists() && file.canRead()) {
                        file.inputStream().use { stream ->
                            val content = readBoundedText(stream)
                            if (content.isNotBlank()) return@withContext content
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("PulsarNav", "Direct file read failed for $filePath: ${e.message}")
                }
            }
        }

        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { stream ->
                    val content = readBoundedText(stream)
                    if (content.isNotBlank()) return@withContext content
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("PulsarNav", "openFileDescriptor failed: ${e.message}")
        }

        null
    }

    private fun showExternalVaultImportConfirmation(uri: Uri) {
        val fileName = try {
            uri.lastPathSegment?.substringAfterLast('/') ?: "Data Vault Backup"
        } catch (_: Exception) {
            "Data Vault Backup"
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Restore Data Vault?")
            .setMessage("Do you want to restore motorcycle settings, trips, and fuel logs from '$fileName' into PulsarNav?")
            .setPositiveButton("Restore") { _, _ ->
                importVaultFromUri(uri)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDecryptVaultPassphraseDialog(rawContent: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }
        val tvDesc = TextView(this).apply {
            text = "This .pnav backup is encrypted. Enter your passphrase to restore:"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 14)
        }
        val etPassphrase = createThemedEditText(
            hintText = "Passphrase",
            inputTypeFlags = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        layout.addView(tvDesc)
        layout.addView(etPassphrase)

        MaterialAlertDialogBuilder(this)
            .setTitle("Unlock Encrypted Vault")
            .setView(layout)
            .setPositiveButton("Unlock & Restore") { _, _ ->
                val pass = etPassphrase.text.toString().trim()
                executeVaultImport(rawContent, pass)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeVaultImport(rawContent: String, passphrase: String?) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                DataVaultManager.getInstance(this@MainActivity).importVaultFromInput(rawContent, passphrase)
            }
            result.onSuccess { summary ->
                val name = summary.riderName.ifBlank { summary.bikeName.ifBlank { "Bike" } }
                showFeedback("Vault Restored: ${summary.tripsImported} trips, ${summary.fuelEntriesImported} fuel logs for $name")
                try {
                    refreshAllUiAfterVaultImport()
                } catch (e: Exception) {
                    android.util.Log.e("PulsarNav", "Error refreshing UI after vault import", e)
                }
            }.onFailure { err ->
                if (err is VaultPassphraseRequiredException) {
                    showDecryptVaultPassphraseDialog(rawContent)
                } else {
                    android.util.Log.e("PulsarNav", "Vault import failed", err)
                    showFeedback("Import failed: ${err.message ?: "Invalid vault file"}", isAlert = true)
                }
            }
        }
    }

    private fun importVaultFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                android.util.Log.i("PulsarNav", "Importing vault from URI: $uri")
                val rawContent = readTextFromUriSafe(uri)
                if (!rawContent.isNullOrBlank()) {
                    executeVaultImport(rawContent, passphrase = null)
                } else {
                    showFeedback("Could not read backup file. Please select the file using 'Import File'.", isAlert = true)
                }
            } catch (e: Exception) {
                android.util.Log.e("PulsarNav", "Error importing vault", e)
                showFeedback("Import error: ${e.message}", isAlert = true)
            }
        }
    }

    private fun executeQrImport(rawValue: String, passphrase: String? = null) {
        val result = DataVaultManager.getInstance(this).importFromQrPayload(rawValue, passphrase)
        result.onSuccess { msg ->
            showFeedback(msg)
            refreshAllUiAfterVaultImport()
        }.onFailure { err ->
            if (err is VaultPassphraseRequiredException) {
                showDecryptQrPassphraseDialog(rawValue)
            } else {
                showFeedback("QR Error: ${err.message}", isAlert = true)
            }
        }
    }

    private fun showDecryptQrPassphraseDialog(rawQrPayload: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }
        val tvDesc = TextView(this).apply {
            text = "This migration QR is encrypted. Enter its passphrase to restore:"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 14)
        }
        val etPass = createThemedEditText(
            hintText = "QR Passphrase",
            inputTypeFlags = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        layout.addView(tvDesc)
        layout.addView(etPass)

        MaterialAlertDialogBuilder(this)
            .setTitle("Unlock Migration QR")
            .setView(layout)
            .setPositiveButton("Unlock") { _, _ ->
                executeQrImport(rawQrPayload, etPass.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchGoogleCodeScanner() {
        try {
            val scanner = GmsBarcodeScanning.getClient(this)
            scanner.startScan()
                .addOnSuccessListener { barcode: Barcode ->
                    val rawValue = barcode.rawValue
                    if (!rawValue.isNullOrBlank()) {
                        executeQrImport(rawValue)
                    }
                }
                .addOnFailureListener { e: Exception ->
                    showFeedback("Scan cancelled: ${e.message}", isAlert = true)
                }
        } catch (e: Exception) {
            showFeedback("Google Code Scanner not available: ${e.message}", isAlert = true)
        }
    }

    private fun refreshAllUiAfterVaultImport() {
        binding.switchAutoConnect.isChecked = appSettings.isAutoConnectEnabled
        binding.switchTwoWheeler.isChecked = appSettings.isTwoWheelerMode
        binding.switchAutoPark.isChecked = appSettings.isAutoParkEnabled
        binding.switchRideTracking.isChecked = appSettings.isRideTrackingEnabled
        binding.switchMediaControl.isChecked = appSettings.isMediaControlEnabled
        binding.switchWhatsAppAlerts.isChecked = appSettings.isWhatsAppAlertsEnabled
        binding.switchTelemetry.isChecked = appSettings.isTelemetrySyncEnabled
        binding.switchSkipHeadunitNav.isChecked = appSettings.isSkipClusterNavWhenHeadunit
        binding.switchSmsReject.isChecked = appSettings.isSmsRejectEnabled
        updateFuelPriceUi()
        updateBikeInfoDisplay()
        updateDocumentsSummary()
        updateServiceHistorySummary()
        updateNavAppSelectionUi()
        updateHeroRangeDisplay()
        updateBikeIssuesSummary()
        updateEmergencyHomeCard()
        updateQuickNavButtons()
        updateTelephonyPermissionsUi()
        updateSmsRejectUi()
        applyTheme(appSettings.appTheme)
    }

    private fun launchNavigationTo(destination: String) {
        if (!isNotificationAccessGranted()) {
            showNotificationAccessDialog { proceedWithNavigationLaunch(destination) }
            return
        }
        proceedWithNavigationLaunch(destination)
    }

    private fun proceedWithNavigationLaunch(destination: String) {
        if (!NavigationIntentLauncher.launchNavigation(this, destination)) {
            showFeedback("Could not open navigation app", isAlert = true)
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun openNotificationListenerSettings() {
        provideHapticFeedback()
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            showFeedback("Unable to open Notification Settings: ${e.message}", isAlert = true)
        }
    }

    private fun updateNotificationAccessUi() {
        val granted = isNotificationAccessGranted()
        val diag = GoogleMapsNotificationListener.diagnostics.value
        binding.cardNotificationPermissionBanner.visibility = if (granted) View.GONE else View.VISIBLE
        binding.tvNotificationAccessStatus.text = when {
            !granted -> "Action Required (Tap to Enable)"
            diag.mapsActiveInShade -> "Active • Live Maps notification detected"
            else -> "Listener ON • Tap for Maps channel & TBT fix"
        }
        binding.tvNotificationAccessStatus.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.mint_accent else R.color.amber_accent)
        )
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isIgnoringBatteryOptimizations(packageName) ?: true
    }

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    showFeedback("Set Battery to 'Unrestricted' in App Info -> Battery", isAlert = true)
                }
            }
        } else {
            showFeedback("Background execution is already unrestricted")
        }
    }

    private fun updateBatteryOptimizationUi() {
        val isUnrestricted = isBatteryOptimizationIgnored()
        binding.tvBatteryOptimizationStatus.text = if (isUnrestricted) {
            "Unrestricted (Protected in background)"
        } else {
            "Optimized (Tap to allow unrestricted background)"
        }
        binding.tvBatteryOptimizationStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (isUnrestricted) R.color.mint_accent else R.color.amber_accent
            )
        )
    }

    private fun showNotificationAccessDialog(onContinueAnyway: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(this, R.style.ObsidianAlertDialog)
            .setTitle("Navigation Interceptor Access")
            .setMessage("Android requires Notification Access for PulsarNav so it can intercept turn-by-turn navigation arrows from Google Maps, Waze, and Android Auto and stream them directly to your Pulsar motorcycle cluster.\n\nPlease enable PulsarNav on the next screen.")
            .setPositiveButton("Open Settings") { _, _ ->
                openNotificationListenerSettings()
            }
            .setNegativeButton("Continue Anyway") { _, _ ->
                onContinueAnyway?.invoke()
            }
            .show()
    }

    private fun showSetAddressDialog(label: String, slot: Int) {
        val sheet = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_set_destination, null)
        sheet.setContentView(sheetView)

        val ivIcon = sheetView.findViewById<ImageView>(R.id.ivSheetIcon)
        val tvTitle = sheetView.findViewById<TextView>(R.id.tvSheetTitle)
        val tvOptionMapsTitle = sheetView.findViewById<TextView>(R.id.tvOptionMapsTitle)
        val cardOptionMapsSaved = sheetView.findViewById<MaterialCardView>(R.id.cardOptionMapsSaved)
        val cardOptionPinGps = sheetView.findViewById<MaterialCardView>(R.id.cardOptionPinGps)
        val cardOptionCustom = sheetView.findViewById<MaterialCardView>(R.id.cardOptionCustom)
        val etCustom = sheetView.findViewById<EditText>(R.id.etCustomAddress)
        etCustom?.let { applyThemeToEditText(it) }
        val btnSaveCustom = sheetView.findViewById<MaterialButton>(R.id.btnSaveCustomAddress)
        val btnDismiss = sheetView.findViewById<MaterialButton>(R.id.btnDismissSheet)

        ivIcon?.setImageResource(if (slot == 0) R.drawable.ic_home else R.drawable.ic_work)
        tvTitle.text = "Set $label Destination"
        val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
        tvOptionMapsTitle.text = if (isWaze) "Waze Saved $label" else "Google Maps Saved $label"

        val current = when (slot) {
            0 -> appSettings.homeAddress
            1 -> appSettings.workAddress
            else -> appSettings.favPlaceAddress
        }
        if (current.isNotBlank() && current != "Home" && current != "Work") {
            etCustom.setText(current)
        }

        btnSaveCustom.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
        btnSaveCustom.setTextColor(currentTheme.onPrimaryColor)
        ivIcon?.imageTintList = ColorStateList.valueOf(currentTheme.accentColor)
        listOf(cardOptionMapsSaved, cardOptionPinGps, cardOptionCustom).forEach { card ->
            card.setCardBackgroundColor(currentTheme.cardColor)
            card.strokeColor = currentTheme.cardBorderColor
        }

        cardOptionMapsSaved.setOnClickListener {
            provideHapticFeedback()
            when (slot) {
                0 -> {
                    appSettings.homeAddress = "Home"
                    appSettings.homeLat = 0.0
                    appSettings.homeLng = 0.0
                }
                1 -> {
                    appSettings.workAddress = "Work"
                    appSettings.workLat = 0.0
                    appSettings.workLng = 0.0
                }
                else -> {
                    appSettings.favPlaceAddress = ""
                    appSettings.favPlaceLat = 0.0
                    appSettings.favPlaceLng = 0.0
                }
            }
            updateQuickNavButtons()
            showFeedback(if (slot == 2) "Cleared Favorite spot" else "Linked to Google Maps $label")
            sheet.dismiss()
        }

        cardOptionPinGps.setOnClickListener {
            provideHapticFeedback()
            pinCurrentLocationAs(label, slot)
            sheet.dismiss()
        }

        btnSaveCustom.setOnClickListener {
            provideHapticFeedback()
            val text = etCustom.text.toString().trim()
            if (text.isNotBlank()) {
                when (slot) {
                    0 -> {
                        appSettings.homeAddress = text
                        appSettings.homeLat = 0.0
                        appSettings.homeLng = 0.0
                    }
                    1 -> {
                        appSettings.workAddress = text
                        appSettings.workLat = 0.0
                        appSettings.workLng = 0.0
                    }
                    else -> {
                        appSettings.favPlaceAddress = text
                        appSettings.favPlaceLat = 0.0
                        appSettings.favPlaceLng = 0.0
                    }
                }
                updateQuickNavButtons()
                showFeedback("$label address saved")
                sheet.dismiss()
            } else {
                showFeedback("Please enter an address or landmark", isAlert = true)
            }
        }

        btnDismiss.setOnClickListener {
            sheet.dismiss()
        }

        sheet.show()
    }

    @SuppressLint("MissingPermission")
    private fun pinCurrentLocationAs(label: String, slot: Int) {
        val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLoc) {
            showFeedback("Grant Location permission to pin current GPS", isAlert = true)
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        var location: Location? = null
        try {
            if (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
            if (location == null && lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Exception) {}

        if (location != null) {
            val lat = location.latitude
            val lng = location.longitude
            val addr = "%.5f,%.5f".format(java.util.Locale.US, lat, lng)
            when (slot) {
                0 -> {
                    appSettings.homeAddress = addr
                    appSettings.homeLat = lat
                    appSettings.homeLng = lng
                }
                1 -> {
                    appSettings.workAddress = addr
                    appSettings.workLat = lat
                    appSettings.workLng = lng
                }
                else -> {
                    appSettings.favPlaceAddress = addr
                    appSettings.favPlaceLat = lat
                    appSettings.favPlaceLng = lng
                }
            }
            updateQuickNavButtons()
            showFeedback("Pinned GPS as $label")
        } else {
            showFeedback("Unable to get current GPS location", isAlert = true)
        }
    }

    private fun showAddFuelDialog() {
        val currentOdo = maintenanceManager.status.value.totalOdometerKm
        val petrolRate = appSettings.petrolPricePerLiter
        val currSymbol = appSettings.currentCurrencySymbol
        val model = appSettings.getActiveBikeProfile()
        val tankMax = model.tankCapacityLiters.toDouble().coerceAtLeast(1.0)
        val tankMaxFloat = tankMax.toFloat()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvDesc = TextView(this).apply {
            text = String.format(
                Locale.US,
                "Easy Refuel Wizard • Petrol Rate: %s%.2f/L\nTap a quick amount to auto-compute litres:",
                currSymbol,
                petrolRate
            )
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvDesc)

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        val odoInput = createThemedEditText(
            hintText = "Current Odometer (km)",
            initialText = formatDecimal(currentOdo),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val costInput = createThemedEditText(
            hintText = "Total Paid $currSymbol",
            initialText = "500",
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val litersInput = createThemedEditText(
            hintText = "Fuel Litres",
            initialText = formatDecimal(500.0 / petrolRate, 2),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        costInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val amt = parseDecimal(s)
                if (amt != null && amt > 0) {
                    val lit = amt / petrolRate
                    litersInput.setText(formatDecimal(lit, 2))
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        fun createPresetButton(label: String, amount: Double, isFull: Boolean = false): MaterialButton {
            return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                textSize = 10f
                setPadding(0, 0, 0, 0)
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(2, 0, 2, 0)
                }
                setOnClickListener {
                    provideHapticFeedback()
                    if (isFull) {
                        val estCost = tankMax * petrolRate
                        costInput.setText(String.format(Locale.US, "%.0f", estCost))
                        litersInput.setText(formatDecimal(tankMax, 2))
                    } else {
                        costInput.setText(String.format(Locale.US, "%.0f", amount))
                        litersInput.setText(formatDecimal(amount / petrolRate, 2))
                    }
                }
            }
        }

        val p1 = (petrolRate * 2).roundToInt()
        val p2 = (petrolRate * 5).roundToInt()
        val p3 = (petrolRate * 10).roundToInt()
        presetRow.addView(createPresetButton("${currSymbol}${p1}", p1.toDouble()))
        presetRow.addView(createPresetButton("${currSymbol}${p2}", p2.toDouble()))
        presetRow.addView(createPresetButton("${currSymbol}${p3}", p3.toDouble()))
        presetRow.addView(createPresetButton("Full Tank", 0.0, isFull = true))
        layout.addView(presetRow)

        val tvOdoLabel = TextView(this).apply {
            text = "Odometer (km):"
            setTextColor(currentTheme.textTertiaryColor)
            textSize = 11f
        }
        layout.addView(tvOdoLabel)
        layout.addView(odoInput)

        val tvCostLabel = TextView(this).apply {
            text = "Total Cost ($currSymbol):"
            setTextColor(currentTheme.textTertiaryColor)
            textSize = 11f
        }
        layout.addView(tvCostLabel)
        layout.addView(costInput)

        val tvLitersLabel = TextView(this).apply {
            text = "Fuel Litres (Auto-calculated):"
            setTextColor(currentTheme.textTertiaryColor)
            textSize = 11f
        }
        layout.addView(tvLitersLabel)
        layout.addView(litersInput)

        val checkFullTank = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Full Tank refill (Calculates true mileage)"
            isChecked = true
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 12f
        }
        layout.addView(checkFullTank)

        MaterialAlertDialogBuilder(this)
            .setTitle("Log Refuel")
            .setView(layout)
            .setPositiveButton("Save Refill") { _, _ ->
                val odo = parseDecimal(odoInput.text)
                val liters = parseDecimal(litersInput.text)
                val cost = parseDecimal(costInput.text)

                if (odo != null && liters != null && cost != null && liters > 0) {
                    val isFull = checkFullTank.isChecked
                    val entry = fuelLogManager.addFuelEntry(odo, liters, cost, isFullTank = isFull)
                    provideHapticFeedback()

                    val currentLiters = appSettings.fuelLevelLiters
                    val newLiters = if (isFull) tankMaxFloat else (currentLiters + liters.toFloat()).coerceIn(0.0f, tankMaxFloat)
                    val newPercent = ((newLiters / tankMaxFloat) * 100f).roundToInt().coerceIn(0, 100)
                    val avgMileage = fuelLogManager.stats.value.averageKmpl.coerceIn(25.0, 50.0)
                    val newDte = (newLiters * avgMileage).toInt()

                    appSettings.fuelLevelLiters = newLiters
                    appSettings.fuelLevelPercent = newPercent
                    appSettings.projectedDteKm = newDte
                    recordSyncTime()
                    updateHeroRangeDisplay()

                    val kmplMsg = if (entry.calculatedKmpl != null) {
                        " • Mileage: ${formatDecimal(entry.calculatedKmpl)} km/l!"
                    } else if (!isFull) {
                        " (Partial fill recorded)"
                    } else {
                        " (Will compute mileage on next refill)"
                    }
                    showFeedback("Logged ${formatDecimal(liters, 2)} L for $currSymbol${String.format(Locale.US, "%.0f", cost)}$kmplMsg")
                } else {
                    showFeedback("Please enter valid fuel details", isAlert = true)
                }
            }
            .setNeutralButton(String.format(Locale.US, "Price: %s%.2f/L", currSymbol, petrolRate)) { _, _ ->
                showEditPetrolPriceDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateFuelPriceUi() {
        val currSymbol = appSettings.currentCurrencySymbol
        binding.tvFuelPriceTitle.text = String.format(Locale.US, "Fuel Price: %s%.2f / L", currSymbol, appSettings.petrolPricePerLiter)
        binding.tvFuelPriceCountrySubtitle.text = "Tap to update default ${appSettings.currentCurrencyCode} rate"
        binding.tvFuelPriceTitle.setTextColor(currentTheme.textPrimaryColor)
        binding.tvFuelPriceCountrySubtitle.setTextColor(currentTheme.textSecondaryColor)
        binding.tvEditFuelPriceAction.setTextColor(currentTheme.accentColor)
    }

    private fun showEditPetrolPriceDialog() {
        val currSymbol = appSettings.currentCurrencySymbol
        val input = createThemedEditText(
            hintText = "Price in $currSymbol",
            initialText = formatDecimal(appSettings.petrolPricePerLiter, 2),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val container = FrameLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Set Local Fuel Price ($currSymbol / Litre)")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val p = parseDecimal(input.text)
                if (p != null && p > 0.0) {
                    appSettings.petrolPricePerLiter = p
                    updateFuelPriceUi()
                    updateHeroRangeDisplay()
                    showFeedback("Fuel price set to $currSymbol${formatDecimal(p, 2)}/L")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isWifiEnabled(): Boolean {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        return wm?.isWifiEnabled == true
    }

    private fun promptEnableWifi(
        actionType: PendingWifiActionType = PendingWifiActionType.NONE,
        onWifiReady: (() -> Unit)? = null
    ) {
        pendingWifiActionType = actionType
        pendingWifiAction = onWifiReady
        MaterialAlertDialogBuilder(this)
            .setTitle("Turn On Wi-Fi")
            .setMessage("Wi-Fi is currently turned off on your phone. To cast navigation maps to your motorcycle TFT cluster, Wi-Fi must be enabled.")
            .setPositiveButton("Turn On") { _, _ ->
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        Intent(android.provider.Settings.Panel.ACTION_WIFI)
                    } catch (_: Exception) {
                        Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                    }
                } else {
                    Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                }
                try {
                    wifiSettingsLauncher.launch(intent)
                } catch (e: Exception) {
                    showFeedback("Unable to open Wi-Fi settings: ${e.message}", isAlert = true)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingWifiAction = null
                pendingWifiActionType = PendingWifiActionType.NONE
            }
            .setOnCancelListener {
                pendingWifiAction = null
                pendingWifiActionType = PendingWifiActionType.NONE
            }
            .show()
    }

    private fun ensureWifiEnabled(onReady: () -> Unit) {
        if (!isWifiEnabled()) {
            promptEnableWifi(PendingWifiActionType.NONE, onReady)
        } else {
            onReady()
        }
    }

    private fun startTftHudMirror() {
        if (!isWifiEnabled()) {
            promptEnableWifi(PendingWifiActionType.MIRROR_HUD) { startTftHudMirror() }
            return
        }
        val mirrorModeLabel = if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_HUD) {
            "Cockpit HUD"
        } else {
            "800×480 Live Map (Maps-Only, Lock-Proof)"
        }
        TftWifiMirrorManager.getInstance(this).stopLocalDebugPreview()
        BikeBleForegroundService.startForTftCockpitMirror(this)
        showFeedback("Connecting to TFT Wi-Fi for $mirrorModeLabel...")
    }

    private fun checkLandscapeAndLaunchCast() {
        if (!isWifiEnabled()) {
            promptEnableWifi(PendingWifiActionType.LIVE_CAST) { checkLandscapeAndLaunchCast() }
            return
        }
        if (appSettings.isTftCastForceLandscapeEnabled && !android.provider.Settings.System.canWrite(this)) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Allow Auto-Landscape")
                .setMessage("To automatically rotate your screen and navigation apps into landscape during TFT Live Cast, PulsarNav needs the 'Modify system settings' permission.")
                .setPositiveButton("Allow") { _, _ ->
                    try {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        )
                    } catch (_: Exception) {
                        launchTftScreenCapture()
                    }
                }
                .setNegativeButton("Continue Anyway") { _, _ ->
                    launchTftScreenCapture()
                }
                .show()
        } else {
            launchTftScreenCapture()
        }
    }

    private fun launchTftScreenCapture() {
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        if (mpm == null) {
            showFeedback("Screen capture unsupported on this device", isAlert = true)
            return
        }
        try {
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mpm.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForUserChoice()
                )
            } else {
                mpm.createScreenCaptureIntent()
            }
            tftScreenCaptureLauncher.launch(captureIntent)
        } catch (e: Exception) {
            showFeedback("Unable to start screen capture: ${e.message}", isAlert = true)
        }
    }

    private fun getMissingBlePermissions(): List<String> {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getMissingTftWifiPermissions(): List<String> {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getMissingPermissions(): List<String> {
        val required = getMissingBlePermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        return required
    }

    private fun getMissingTelephonyPermissions(): List<String> {
        val required = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestTelephonyAndClusterPermissions() {
        val missing = getMissingTelephonyPermissions()
        if (missing.isEmpty()) {
            showFeedback("All Call Log, Contacts, Dialback, SMS & POI permissions granted")
            bleManager.syncPhonebookAndCalls()
            bleManager.dispatchPlaylistInfoOnBle()
            updateTelephonyPermissionsUi()
        } else {
            telephonyPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun updateTelephonyPermissionsUi() {
        val missing = getMissingTelephonyPermissions()
        if (missing.isEmpty()) {
            binding.tvTelephonyPermsStatus.text = "All Granted (Favs, Recents, Dialback, SMS, POI GPS)"
            binding.tvTelephonyPermsStatus.setTextColor(ContextCompat.getColor(this, R.color.mint_accent))
        } else {
            val shortNames = missing.joinToString(", ") { it.substringAfterLast('.') }
            binding.tvTelephonyPermsStatus.text = "Tap to grant: $shortNames"
            binding.tvTelephonyPermsStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
        }
    }

    private fun updateSmsRejectUi() {
        binding.switchSmsReject.isChecked = appSettings.isSmsRejectEnabled
        val msg = appSettings.smsRejectMessage.trim().ifBlank { com.pulsarnav.app.data.AppSettings.DEFAULT_REJECT_SMS_TEMPLATE }
        binding.tvSmsRejectSummary.text = if (appSettings.isSmsRejectEnabled) {
            "Reply: \"$msg\""
        } else {
            "Off (Template: \"$msg\")"
        }
    }

    private fun showEditSmsRejectTemplateDialog() {
        val currentMsg = appSettings.smsRejectMessage.trim().ifBlank { com.pulsarnav.app.data.AppSettings.DEFAULT_REJECT_SMS_TEMPLATE }
        val input = createThemedEditText(
            hintText = "SMS reply when rejecting call on switchgear",
            initialText = currentMsg
        )
        val container = FrameLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }
        MaterialAlertDialogBuilder(this, R.style.ObsidianAlertDialog)
            .setTitle("Call Reject Auto-Reply SMS")
            .setMessage("Sent when Reject with SMS is triggered on your Bajaj TFT cluster switchgear (up to 160 chars).")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    appSettings.smsRejectMessage = text.take(160)
                    updateSmsRejectUi()
                    showFeedback("Reject-with-SMS template saved")
                } else {
                    showFeedback("Template cannot be empty", isAlert = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateHardwareReadinessUi() {
        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val isBtOn = btAdapter?.isEnabled == true
        val locManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        val isGpsOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locManager?.isLocationEnabled == true
        } else {
            locManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        }
        val isGpsRequiredForBle = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

        val missingBle = getMissingBlePermissions()
        val hasPerms = missingBle.isEmpty()

        binding.chipBtWarning.visibility = if (!isBtOn) View.VISIBLE else View.GONE
        binding.chipGpsWarning.visibility = if (isGpsRequiredForBle && !isGpsOn) View.VISIBLE else View.GONE
        binding.chipPermWarning.visibility = if (!hasPerms) View.VISIBLE else View.GONE

        val isConnected = bleManager.connectionState.value is BleConnectionState.Connected
        if (!isConnected) {
            when {
                !hasPerms -> {
                    binding.tvSubStatus.text = "Permissions needed: Tap PERMS NEEDED above"
                    binding.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                !isBtOn && isGpsRequiredForBle && !isGpsOn -> {
                    binding.tvSubStatus.text = "Turn ON Bluetooth & GPS in Quick Settings"
                    binding.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                !isBtOn -> {
                    binding.tvSubStatus.text = "Turn ON Bluetooth in Quick Settings"
                    binding.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                isGpsRequiredForBle && !isGpsOn -> {
                    binding.tvSubStatus.text = "Turn ON Location (GPS) in Quick Settings"
                    binding.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                else -> {
                    binding.tvSubStatus.text = if (bleManager.isAutoConnectEnabled) {
                        "Auto-connect active • waiting for bike"
                    } else {
                        "Auto-connect paused • tap Scan to connect"
                    }
                    binding.tvSubStatus.setTextColor(currentTheme.textTertiaryColor)
                }
            }
        }
    }

    private fun checkAndStartScan(silent: Boolean = false) {
        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val isBtOn = btAdapter?.isEnabled == true
        val locManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        val isGpsOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locManager?.isLocationEnabled == true
        } else {
            locManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        }
        val isGpsRequiredForBle = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

        updateHardwareReadinessUi()

        val missingBle = getMissingBlePermissions()
        val btGranted = missingBle.isEmpty()
        if (!btGranted) {
            if (!silent) {
                showFeedback("Grant required permissions to connect to your bike console", isAlert = true)
                permissionLauncher.launch(getMissingPermissions().toTypedArray())
            }
            return
        }

        if (!isBtOn) {
            if (!silent) {
                showFeedback("Bluetooth is OFF. Turn ON Bluetooth to scan for your bike console.", isAlert = true)
                try {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (_: Exception) {}
            }
            return
        }

        if (isGpsRequiredForBle && !isGpsOn) {
            if (!silent) {
                showFeedback("Location (GPS) is OFF. Android requires Location to scan for Bluetooth devices.", isAlert = true)
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {}
            }
            return
        }

        bleManager.startAutoConnect(force = !silent)
    }

    private fun showVehicleSetupBottomSheet() {
        val sheet = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_vehicle_setup, null)
        sheet.setContentView(sheetView)

        val spinnerCountry = sheetView.findViewById<Spinner>(R.id.spinnerCountry)
        val spinnerModel = sheetView.findViewById<Spinner>(R.id.spinnerModel)
        val btnNavGmaps = sheetView.findViewById<MaterialButton>(R.id.btnNavGoogleMaps)
        val btnNavWaze = sheetView.findViewById<MaterialButton>(R.id.btnNavWaze)
        val tvPreviewTitle = sheetView.findViewById<TextView>(R.id.tvPreviewTitle)
        val tvPreviewEngine = sheetView.findViewById<TextView>(R.id.tvPreviewEngine)
        val tvPreviewFrontTire = sheetView.findViewById<TextView>(R.id.tvPreviewFrontTire)
        val tvPreviewRearTire = sheetView.findViewById<TextView>(R.id.tvPreviewRearTire)
        val tvPreviewTank = sheetView.findViewById<TextView>(R.id.tvPreviewTank)
        val tvPreviewSos = sheetView.findViewById<TextView>(R.id.tvPreviewSos)
        val btnSave = sheetView.findViewById<MaterialButton>(R.id.btnSaveVehicleSetup)

        btnSave?.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
        btnSave?.setTextColor(currentTheme.onPrimaryColor)

        // Setup Country Spinner
        val countryNames = VehicleCatalog.COUNTRIES.map {
            "${it.labelTag} ${it.name} (SOS: ${it.sosNumber})"
        }
        val countryAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, countryNames)
        spinnerCountry?.adapter = countryAdapter

        val activeCountryIndex = VehicleCatalog.COUNTRIES.indexOfFirst {
            it.code.equals(appSettings.selectedCountryCode, ignoreCase = true)
        }.coerceAtLeast(0)
        spinnerCountry?.setSelection(activeCountryIndex)

        // Setup Model Spinner
        val modelNames = VehicleCatalog.MODELS.map {
            "[${it.series}] ${it.name}"
        }
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelNames)
        spinnerModel?.adapter = modelAdapter

        val activeModelIndex = VehicleCatalog.MODELS.indexOfFirst {
            it.id.equals(appSettings.selectedModelId, ignoreCase = true)
        }.coerceAtLeast(0)
        spinnerModel?.setSelection(activeModelIndex)

        // Setup Nav App Selection
        var selectedNavApp = appSettings.preferredNavApp
        fun updateNavAppButtons() {
            if (selectedNavApp == AppSettings.NAV_APP_WAZE) {
                btnNavWaze?.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                btnNavWaze?.setTextColor(currentTheme.accentColor)
                btnNavWaze?.strokeColor = ColorStateList.valueOf(currentTheme.accentColor)

                btnNavGmaps?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_card))
                btnNavGmaps?.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnNavGmaps?.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_border))
            } else {
                btnNavGmaps?.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                btnNavGmaps?.setTextColor(currentTheme.accentColor)
                btnNavGmaps?.strokeColor = ColorStateList.valueOf(currentTheme.accentColor)

                btnNavWaze?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_card))
                btnNavWaze?.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnNavWaze?.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_border))
            }
        }
        updateNavAppButtons()

        btnNavGmaps?.setOnClickListener {
            provideHapticFeedback()
            selectedNavApp = AppSettings.NAV_APP_GOOGLE_MAPS
            updateNavAppButtons()
        }

        btnNavWaze?.setOnClickListener {
            provideHapticFeedback()
            selectedNavApp = AppSettings.NAV_APP_WAZE
            updateNavAppButtons()
        }

        // Function to refresh the live preview card
        fun refreshPreview() {
            val cIdx = (spinnerCountry?.selectedItemPosition ?: 0).coerceIn(0, VehicleCatalog.COUNTRIES.lastIndex)
            val mIdx = (spinnerModel?.selectedItemPosition ?: 0).coerceIn(0, VehicleCatalog.MODELS.lastIndex)
            val country = VehicleCatalog.COUNTRIES[cIdx]
            val model = VehicleCatalog.MODELS[mIdx]
            val bikeDisplayName = VehicleCatalog.getLocalizedBikeName(model, country)

            tvPreviewTitle?.text = "$bikeDisplayName Specs Preview"
            tvPreviewTitle?.setTextColor(currentTheme.accentColor)
            tvPreviewEngine?.text = model.engineSpec
            tvPreviewFrontTire?.text = "${model.coldTireFrontPsiSolo} PSI (Solo) • ${model.coldTireFrontPsiPillion} PSI (Pillion)\n${model.tireFrontSize}"
            tvPreviewRearTire?.text = "${model.coldTireRearPsiSolo} PSI (Solo) • ${model.coldTireRearPsiPillion} PSI (Pillion)\n${model.tireRearSize}"
            tvPreviewTank?.text = "${model.tankCapacityLiters} L (Reserve: ${model.reserveCapacityLiters} L)"
            tvPreviewSos?.text = "${country.sosNumber} (${country.name} SOS)"
        }

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                refreshPreview()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        spinnerCountry?.onItemSelectedListener = listener
        spinnerModel?.onItemSelectedListener = listener
        refreshPreview()

        btnSave?.setOnClickListener {
            provideHapticFeedback()
            val cIdx = (spinnerCountry?.selectedItemPosition ?: 0).coerceIn(0, VehicleCatalog.COUNTRIES.lastIndex)
            val mIdx = (spinnerModel?.selectedItemPosition ?: 0).coerceIn(0, VehicleCatalog.MODELS.lastIndex)
            val country = VehicleCatalog.COUNTRIES[cIdx]
            val model = VehicleCatalog.MODELS[mIdx]

            appSettings.applyBikeAndCountrySelection(model.id, country.code)
            appSettings.preferredNavApp = selectedNavApp

            updateHeroRangeDisplay()
            updateBikeInfoDisplay()
            updateQuickNavButtons()
            updateNavAppSelectionUi()
            val bikeDisplayName = VehicleCatalog.getLocalizedBikeName(model, country)
            showFeedback("Configured for $bikeDisplayName (${country.name})")
            sheet.dismiss()
        }

        sheet.show()
    }

    private fun observeBleState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bleManager.connectionState.collectLatest { state ->
                when (state) {
                    is BleConnectionState.Disconnected -> {
                        stopStatusDotPulse(binding.viewStatusDot)
                        stopStatusDotPulse(binding.viewTopConnectionDot)
                        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(currentTheme.accentColor)
                        binding.tvConnectionStatus.text = "Disconnected"
                        binding.tvConnectionStatus.setTextColor(currentTheme.textPrimaryColor)
                        binding.tvSubStatus.text = if (bleManager.isAutoConnectEnabled) {
                            "Auto-connect active • waiting for bike"
                        } else {
                            "Auto-connect paused • tap Scan to connect"
                        }
                        binding.tvSubStatus.setTextColor(currentTheme.textSecondaryColor)
                        binding.btnScan.text = "Scan"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
                        binding.btnScan.setTextColor(currentTheme.onPrimaryColor)
                        binding.layoutDeviceList.visibility = View.VISIBLE

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                        binding.tvTopConnectionStatus.text = "OFFLINE"
                        binding.tvTopConnectionStatus.setTextColor(currentTheme.textTertiaryColor)
                    }
                    is BleConnectionState.Scanning -> {
                        startStatusDotPulse(binding.viewStatusDot)
                        startStatusDotPulse(binding.viewTopConnectionDot)
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_scanning)
                        binding.tvConnectionStatus.text = "Searching for bike..."
                        binding.tvConnectionStatus.setTextColor(currentTheme.textPrimaryColor)
                        binding.tvSubStatus.text = "Auto-scanning • ensure bike ignition is ON"
                        binding.tvSubStatus.setTextColor(currentTheme.textSecondaryColor)
                        binding.btnScan.text = "Stop"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
                        binding.btnScan.setTextColor(currentTheme.onPrimaryColor)
                        binding.layoutDeviceList.visibility = View.VISIBLE

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_scanning)
                        binding.tvTopConnectionStatus.text = "SEARCHING"
                        binding.tvTopConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_scanning))
                    }
                    is BleConnectionState.Connecting -> {
                        startStatusDotPulse(binding.viewStatusDot)
                        startStatusDotPulse(binding.viewTopConnectionDot)
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_scanning)
                        binding.tvConnectionStatus.text = "Connecting..."
                        binding.tvConnectionStatus.setTextColor(currentTheme.textPrimaryColor)
                        binding.tvSubStatus.text = state.deviceName
                        binding.tvSubStatus.setTextColor(currentTheme.textSecondaryColor)
                        binding.btnScan.text = "Cancel"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                        binding.btnScan.setTextColor(currentTheme.accentColor)

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_scanning)
                        binding.tvTopConnectionStatus.text = "CONNECTING"
                        binding.tvTopConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_scanning))
                    }
                    is BleConnectionState.Connected -> {
                        stopStatusDotPulse(binding.viewStatusDot)
                        stopStatusDotPulse(binding.viewTopConnectionDot)
                        BikeBleForegroundService.start(this@MainActivity)
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_connected)
                        binding.tvConnectionStatus.text = "Connected • ${state.deviceName}"
                        binding.tvConnectionStatus.setTextColor(currentTheme.textPrimaryColor)
                        binding.tvSubStatus.text = "Cluster synced (${state.address})"
                        binding.tvSubStatus.setTextColor(currentTheme.textSecondaryColor)
                        binding.btnScan.text = "Disconnect"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                        binding.btnScan.setTextColor(currentTheme.accentColor)
                        binding.layoutDeviceList.visibility = View.GONE

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_connected)
                        binding.tvTopConnectionStatus.text = "CONNECTED"
                        binding.tvTopConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))

                        recordSyncTime()
                    }
                    is BleConnectionState.Error -> {
                        stopStatusDotPulse(binding.viewStatusDot)
                        stopStatusDotPulse(binding.viewTopConnectionDot)
                        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(currentTheme.accentColor)
                        binding.tvConnectionStatus.text = state.message
                        binding.btnScan.text = "Scan"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
                        binding.btnScan.setTextColor(currentTheme.onPrimaryColor)

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                        binding.tvTopConnectionStatus.text = "OFFLINE"
                        binding.tvTopConnectionStatus.setTextColor(currentTheme.textTertiaryColor)
                    }
                }
            }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bleManager.discoveredBikes.collectLatest { bikes ->
                    updateDeviceList(bikes)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bleManager.lastSentPacket.collectLatest { packet ->
                    if (packet != null) {
                        val compactHex = DebugLogRepository.toCompactHex(packet, maxBytes = 12)
                        binding.tvPacketHex.text = "Last Frame (${packet.size}B): $compactHex • payload redacted"
                        binding.tvPacketDecoded.text = "Decoded: ${DebugLogRepository.summarizeBlePacket(packet)}"
                    } else {
                        binding.tvPacketHex.text = "Last BLE Packet: None"
                        binding.tvPacketDecoded.text = "Decoded: Waiting for BLE frame..."
                    }
                }
            }
        }
    }

    private fun observeTftWifiMirror() {
        val mirrorManager = TftWifiMirrorManager.getInstance(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(mirrorManager.mirrorState, bleManager.connectionState) { mirror, ble ->
                    mirror to ble
                }.collectLatest { (mirror, ble) ->
                val active = mirror.status != TftMirrorStatus.IDLE && mirror.status != TftMirrorStatus.ERROR
                val dotRes = when (mirror.status) {
                    TftMirrorStatus.STREAMING, TftMirrorStatus.TCP_CONNECTED_IDLE -> R.color.status_connected
                    TftMirrorStatus.CONNECTING_WIFI, TftMirrorStatus.WIFI_CONNECTED_WAITING_TCP -> R.color.status_scanning
                    TftMirrorStatus.IDLE, TftMirrorStatus.ERROR -> R.color.status_disconnected
                }
                binding.viewTftWifiDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, dotRes)

                val targetSsid = mirror.ssid.ifBlank {
                    appSettings.tftWifiSsid.ifBlank {
                        TftPasswordGenerator.deriveDefaultSsid(bleManager.getConnectedClusterName(), appSettings.tftWifiVin)
                    }
                }
                val isOtcConnected = (ble is BleConnectionState.Connected) && !bleManager.isConnectedToTftCluster()

                binding.tvTftWifiStatus.text = when (mirror.status) {
                    TftMirrorStatus.IDLE -> {
                        if (isOtcConnected) {
                            "OTC LCD cluster connected • Wi-Fi mirror is for TFT (NS400Z)"
                        } else {
                            "Idle • Target $targetSsid (800×480)"
                        }
                    }
                    TftMirrorStatus.CONNECTING_WIFI -> "Joining SoftAP $targetSsid..."
                    TftMirrorStatus.WIFI_CONNECTED_WAITING_TCP -> "Wi-Fi linked (${mirror.clusterHost}) • Waiting for TCP 5558"
                    TftMirrorStatus.TCP_CONNECTED_IDLE -> "Cluster TCP ready (${mirror.clusterHost}) • Idle"
                    TftMirrorStatus.STREAMING -> {
                        val modeLabel = when (mirror.source) {
                            TftMirrorSource.LIVE_MAP -> "Live Map"
                            TftMirrorSource.COCKPIT_HUD -> "HUD Mirror"
                            TftMirrorSource.LIVE_SCREEN_CAST -> "Live Cast"
                        }
                        String.format(
                            java.util.Locale.US,
                            "%s • %.1f fps • %s:%d",
                            modeLabel,
                            mirror.fps,
                            mirror.clusterHost,
                            com.pulsarnav.app.wifi.TftWifiProtocol.UDP_PORT
                        )
                    }
                    TftMirrorStatus.ERROR -> "Error • Check Wi-Fi / VIN config"
                }

                binding.tvTftWifiMeta.text = when {
                    !mirror.errorMessage.isNullOrBlank() -> mirror.errorMessage
                    active && mirror.framesSent > 0 -> {
                        val parkTag = if (mirror.isBikeStationary) "Stationary" else "Moving"
                        val touchTag = if (mirror.lastTouchInfo.isNotBlank()) " • ${mirror.lastTouchInfo}" else ""
                        "${mirror.framesSent} frames • ${mirror.lastFrameBytes}B (Q=${mirror.jpegQuality}) • $parkTag$touchTag"
                    }
                    else -> {
                        val credHint = if (appSettings.tftWifiVin.isNotBlank() || appSettings.tftWifiPassphrase.isNotBlank()) {
                            "SoftAP credentials saved for $targetSsid."
                        } else {
                            "Tap Config to enter VIN/WPA2 password or set orientation."
                        }
                        val prefLabel = if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_HUD) {
                            "Cockpit HUD (works screen-off)"
                        } else {
                            "Live Maps 800×480 (maps-only • works locked)"
                        }
                        "$prefLabel. $credHint"
                    }
                }

                val isHandshaking = mirror.status == TftMirrorStatus.CONNECTING_WIFI ||
                    mirror.status == TftMirrorStatus.WIFI_CONNECTED_WAITING_TCP
                binding.progressTftHandshake.visibility = if (isHandshaking) View.VISIBLE else View.GONE
                if (isHandshaking) {
                    binding.progressTftHandshake.setIndicatorColor(
                        if (mirror.status == TftMirrorStatus.CONNECTING_WIFI)
                            ContextCompat.getColor(this@MainActivity, R.color.amber_accent)
                        else
                            ContextCompat.getColor(this@MainActivity, R.color.mint_accent)
                    )
                }

                val prefIdleBtn = if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_HUD) "HUD Mirror\n(Screen Off)" else "Map Mirror\n(Screen Off)"
                binding.btnToggleTftHudMirror.text = if (active) "Stop Mirror" else prefIdleBtn
                binding.btnCastLiveScreen.text = if (active && mirror.source == TftMirrorSource.LIVE_SCREEN_CAST) {
                    "Stop Cast"
                } else {
                    "Cast Maps\n(Google Maps)"
                }
                binding.btnToggleTftHudMirror.isEnabled = !isHandshaking
                binding.btnCastLiveScreen.isEnabled = !isHandshaking

                val isLiveCast = active && mirror.source == TftMirrorSource.LIVE_SCREEN_CAST
                if (isLiveCast) {
                    wasLiveCastActive = true
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    if (active && mirror.source == TftMirrorSource.LIVE_MAP) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    if (wasLiveCastActive && requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        wasLiveCastActive = false
                        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTftAndLogDebugger() {
        val wifiMgr = TftWifiMirrorManager.getInstance(this)
        val dbg = binding.labsTftDebug

        attachTftTouchMapper(dbg.ivTftDebugPreview) { x, y ->
            val live = wifiMgr.mirrorState.value.status != TftMirrorStatus.IDLE &&
                wifiMgr.mirrorState.value.status != TftMirrorStatus.ERROR
            if (!wifiMgr.isLocalPreviewActiveFlow.value && !live) {
                showFeedback("Tap 'Start Preview' first to interact with the cluster")
                return@attachTftTouchMapper
            }
            wifiMgr.simulateClusterTouch(9, x, y)
            dbg.tvTftDbgTouchInfo.text = "Touch mapped @ ($x, $y) on 800×480"
        }

        dbg.btnTftDbgTogglePreview.setOnClickListener {
            provideHapticFeedback()
            if (wifiMgr.isLocalPreviewActiveFlow.value) {
                wifiMgr.stopLocalDebugPreview()
            } else {
                wifiMgr.startLocalDebugPreview()
                showFeedback("800×480 Local TFT Preview started")
            }
        }

        dbg.btnTftDbgFullscreen.setOnClickListener {
            provideHapticFeedback()
            if (!wifiMgr.isLocalPreviewActiveFlow.value &&
                wifiMgr.mirrorState.value.status == TftMirrorStatus.IDLE
            ) {
                wifiMgr.startLocalDebugPreview()
            }
            showFullscreenTftDebuggerDialog()
        }

        dbg.btnTftDbgNavV2Test.setOnClickListener {
            provideHapticFeedback()
            wifiMgr.runNavV2LoopbackDiagnostics()
            showFeedback("NAV_V2 loopback suite queued — check Log Console")
        }

        dbg.btnTftDbgRecenter.setOnClickListener {
            provideHapticFeedback()
            wifiMgr.startLocalDebugPreview()
            wifiMgr.simulateHandlebarRecenter()
        }

        dbg.btnTftDbgDaylight.setOnClickListener {
            provideHapticFeedback()
            wifiMgr.startLocalDebugPreview()
            val highContrast = wifiMgr.toggleDebugDaylightContrast()
            dbg.btnTftDbgDaylight.text = if (highContrast) "🌙 Night UI" else "☀ Daylight"
        }

        dbg.btnTftDbgMotion.setOnClickListener {
            provideHapticFeedback()
            wifiMgr.startLocalDebugPreview()
            val stationary = wifiMgr.toggleDebugStationaryMode()
            dbg.btnTftDbgMotion.text = if (stationary) "PARK ✓" else "RIDE ✓"
        }

        dbg.btnTftDbgHitboxes.setOnClickListener {
            provideHapticFeedback()
            wifiMgr.startLocalDebugPreview()
            val guides = wifiMgr.toggleDebugHitboxOverlay()
            dbg.btnTftDbgHitboxes.text = if (guides) "⊞ Guides ON" else "⊞ Guides"
        }

        // Yield smoothly back to outer NestedScrollView when inner log console cannot scroll in drag direction
        var lastLogTouchY = 0f
        dbg.scrollDebugLogs.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastLogTouchY = event.y
                    val canScroll = v.canScrollVertically(1) || v.canScrollVertically(-1)
                    v.parent?.requestDisallowInterceptTouchEvent(canScroll)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lastLogTouchY
                    lastLogTouchY = event.y
                    val canConsume = when {
                        dy > 0f -> v.canScrollVertically(-1)
                        dy < 0f -> v.canScrollVertically(1)
                        else -> v.canScrollVertically(1) || v.canScrollVertically(-1)
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(canConsume)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        val filterButtons = listOf(
            dbg.btnLogFilterAll to DebugFilter.ALL,
            dbg.btnLogFilterBle to DebugFilter.BLE,
            dbg.btnLogFilterWifi to DebugFilter.WIFI_NAV_V2,
            dbg.btnLogFilterCall to DebugFilter.TELEPHONY,
            dbg.btnLogFilterMaps to DebugFilter.MAPS,
            dbg.btnLogFilterErr to DebugFilter.ERRORS
        )
        for ((btn, filter) in filterButtons) {
            btn.setOnClickListener {
                provideHapticFeedback()
                activeDebugFilter = filter
                updateDebugFilterChipsUi()
                renderDebugLogsUi(DebugLogRepository.entries.value, forceRender = true)
            }
        }

        dbg.btnLogPauseResume.setOnClickListener {
            provideHapticFeedback()
            isDebugLogPaused = !isDebugLogPaused
            dbg.btnLogPauseResume.text = if (isDebugLogPaused) "▶ Resume" else "⏸ Pause"
            if (!isDebugLogPaused) {
                renderDebugLogsUi(DebugLogRepository.entries.value, forceRender = true)
            }
        }

        dbg.btnLogCopy.setOnClickListener {
            provideHapticFeedback()
            lifecycleScope.launch {
                val (count, payload) = withContext(Dispatchers.Default) {
                    val filtered = DebugLogRepository.filterEntries(DebugLogRepository.entries.value, activeDebugFilter)
                    val formatted = DebugLogRepository.formatLogsForExport(
                        filtered,
                        headerInfo = "${bleManager.getDebugDiagnosticsSummary()} • Model=${appSettings.selectedModelId}"
                    )
                    filtered.size to formatted
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("PulsarNav Logs", payload))
                showFeedback("Copied $count filtered log entries")
            }
        }

        dbg.btnLogShare.setOnClickListener {
            provideHapticFeedback()
            shareDebugLogText()
        }

        dbg.btnLogClear.setOnClickListener {
            provideHapticFeedback()
            DebugLogRepository.clear()
            renderDebugLogsUi(emptyList(), forceRender = true)
            showFeedback("Debug log buffer cleared")
        }

        suspend fun renderFrameIfVisible(
            frame: com.pulsarnav.app.wifi.TftDebugFrame?,
            mirrorState: com.pulsarnav.app.wifi.TftMirrorState,
            anyActive: Boolean,
            accountVisible: Boolean
        ) {
            if (frame != null && anyActive) {
                val fullscreenVisible = fullscreenTftDialog?.isShowing == true
                if (accountVisible && frame.frameIndex != lastDecodedFrameIndex) {
                    val targetSlot = (previewBitmapSlotIdx + 1) % previewBitmapSlots.size
                    val bmp = decodeSlotBitmap(frame, previewBitmapSlots, targetSlot)
                    if (bmp != null && isAccountTabVisible.value && !isDestroyed && !isFinishing) {
                        dbg.ivTftDebugPreview.setImageBitmap(bmp)
                        dbg.layoutTftDebugEmpty.visibility = View.GONE
                        previewBitmapSlotIdx = targetSlot
                        lastDecodedFrameIndex = frame.frameIndex
                    }
                }
                if (fullscreenVisible && frame.frameIndex != lastFullscreenFrameIndex) {
                    val targetFullSlot = (fullscreenBitmapSlotIdx + 1) % fullscreenBitmapSlots.size
                    val fullBmp = decodeSlotBitmap(frame, fullscreenBitmapSlots, targetFullSlot)
                    val ivFull = fullscreenTftImageView
                    if (fullBmp != null && ivFull != null && fullscreenTftDialog?.isShowing == true) {
                        ivFull.setImageBitmap(fullBmp)
                        fullscreenBitmapSlotIdx = targetFullSlot
                        lastFullscreenFrameIndex = frame.frameIndex
                    }
                }
                if (accountVisible || fullscreenVisible) {
                    val kb = frame.byteSize / 1024f
                    val motionBadge = if (mirrorState.isBikeStationary) "PARK" else "RIDE"
                    val metricsText = String.format(
                        Locale.US,
                        "%dx%d • %.1f KB / 30 KB • Q=%d • #%d • %s",
                        frame.width,
                        frame.height,
                        kb,
                        frame.quality,
                        frame.frameIndex,
                        motionBadge
                    )
                    if (accountVisible) {
                        dbg.tvTftDbgMetrics.text = metricsText
                        dbg.pbTftJpegBudget.progress =
                            frame.byteSize.coerceIn(0, TftWifiProtocol.UDP_MAX_JPEG_BYTES)
                    }
                    fullscreenTftMetricsView?.text = metricsText
                }
            } else if (!anyActive) {
                lastDecodedFrameIndex = -1L
                lastFullscreenFrameIndex = -1L
                dbg.layoutTftDebugEmpty.visibility = View.VISIBLE
                dbg.pbTftJpegBudget.progress = 0
                dbg.tvTftDbgMetrics.text = "800×480 • Idle • JPEG budget <= 30 KB"
            }
        }

        // Collect 800x480 TFT controls/stats and frame decodes independently so 1s FPS updates do not cancel bitmap decodes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        wifiMgr.mirrorState,
                        wifiMgr.isLocalPreviewActiveFlow
                    ) { mirrorState, localActive ->
                        mirrorState to localActive
                    }.collect { (mirrorState, localActive) ->
                        val liveWifi = mirrorState.status != TftMirrorStatus.IDLE &&
                            mirrorState.status != TftMirrorStatus.ERROR
                        val anyActive = liveWifi || localActive

                        dbg.btnTftDbgTogglePreview.text = when {
                            liveWifi -> "Live Wi-Fi Active"
                            localActive -> "⏹ Stop Preview"
                            else -> "▶ Start Preview"
                        }

                        dbg.tvTftDbgStatus.text = when {
                            liveWifi -> "LIVE WI-FI"
                            localActive -> "LOCAL PREVIEW"
                            else -> "IDLE"
                        }
                        dbg.tvTftDbgStatus.setTextColor(
                            if (anyActive) ContextCompat.getColor(this@MainActivity, R.color.status_connected)
                            else currentTheme.textSecondaryColor
                        )

                        val touchSummary = mirrorState.lastTouchInfo.ifBlank { "None" }
                        dbg.tvTftDbgTouchInfo.text = "Last Cluster Input: $touchSummary"
                        fullscreenTftTouchView?.text = "Input: $touchSummary"

                        if (!anyActive) {
                            renderFrameIfVisible(null, mirrorState, anyActive = false, accountVisible = isAccountTabVisible.value)
                        }
                    }
                }

                launch {
                    combine(
                        wifiMgr.latestDebugFrame,
                        isAccountTabVisible,
                        wifiMgr.isLocalPreviewActiveFlow
                    ) { frame, accountVisible, localActive ->
                        Triple(frame, accountVisible, localActive)
                    }.collectLatest { (frame, accountVisible, localActive) ->
                        val mirrorState = wifiMgr.mirrorState.value
                        val liveWifi = mirrorState.status != TftMirrorStatus.IDLE &&
                            mirrorState.status != TftMirrorStatus.ERROR
                        val anyActive = liveWifi || localActive
                        renderFrameIfVisible(frame, mirrorState, anyActive, accountVisible)
                    }
                }

                // Automatically refresh local 800x480 TFT bench whenever Labs sims / Maps / Call / Media change
                launch {
                    combine(
                        GoogleMapsNotificationListener.currentNavStep,
                        bleManager.simulatedNavStep,
                        bleManager.simulatedAlert,
                        callManager.callInfo
                    ) { _, _, _, _ -> Unit }.collectLatest {
                        wifiMgr.refreshDebugCockpitFrame()
                    }
                }

                // Collect live protocol & system logs + BLE diagnostics banner
                launch {
                    DebugLogRepository.entries.collectLatest { entries ->
                        renderDebugLogsUi(entries, forceRender = false)
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTftTouchMapper(imageView: ImageView, onMappedTouch: (Int, Int) -> Unit) {
        val touchSlop = android.view.ViewConfiguration.get(imageView.context).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        var movedBeyondSlop = false

        imageView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    movedBeyondSlop = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!movedBeyondSlop &&
                        kotlin.math.hypot(event.x - downX, event.y - downY) > touchSlop
                    ) {
                        movedBeyondSlop = true
                    }
                    !movedBeyondSlop
                }
                MotionEvent.ACTION_UP -> {
                    if (movedBeyondSlop) return@setOnTouchListener false
                    val w = v.width
                    val h = v.height
                    if (w <= 0 || h <= 0 || event.x < 0f || event.x >= w || event.y < 0f || event.y >= h) {
                        return@setOnTouchListener false
                    }
                    val x = ((event.x / w.toFloat()) * TftWifiProtocol.TFT_WIDTH).roundToInt()
                        .coerceIn(0, TftWifiProtocol.TFT_WIDTH - 1)
                    val y = ((event.y / h.toFloat()) * TftWifiProtocol.TFT_HEIGHT).roundToInt()
                        .coerceIn(0, TftWifiProtocol.TFT_HEIGHT - 1)
                    provideHapticFeedback()
                    onMappedTouch(x, y)
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    movedBeyondSlop = true
                    false
                }
                else -> false
            }
        }
    }

    private suspend fun decodeSlotBitmap(
        frame: com.pulsarnav.app.wifi.TftDebugFrame,
        slots: Array<Bitmap?>,
        targetSlot: Int
    ): Bitmap? = withContext(Dispatchers.Default) {
        synchronized(previewDecodeLock) {
            val reusable = slots[targetSlot]
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
                if (reusable != null && !reusable.isRecycled &&
                    reusable.width == frame.width && reusable.height == frame.height
                ) {
                    inBitmap = reusable
                }
            }
            val decoded = try {
                BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, opts)
            } catch (_: IllegalArgumentException) {
                opts.inBitmap = null
                BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, opts)
            }
            if (decoded != null) {
                slots[targetSlot] = decoded
            }
            decoded
        }
    }

    private fun showFullscreenTftDebuggerDialog() {
        fullscreenTftDialog?.dismiss()
        val wifiMgr = TftWifiMirrorManager.getInstance(this)
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080B10"))
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvTitle = TextView(this).apply {
            text = "800×480 TFT LIVE BENCH"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTouch = TextView(this).apply {
            text = "Tap display to inspect touch (x,y)"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dpToPx(8), 0)
        }
        val btnClose = MaterialButton(this).apply {
            text = "Close"
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            maxLines = 1
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(tvTitle)
        topBar.addView(tvTouch)
        topBar.addView(btnClose)
        root.addView(topBar)

        val previewFrame = androidx.constraintlayout.widget.ConstraintLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dpToPx(8)
                bottomMargin = dpToPx(8)
            }
        }
        val ivFull = ImageView(this).apply {
            id = View.generateViewId()
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                dimensionRatio = "5:3"
            }
        }
        previewFrame.addView(ivFull)
        root.addView(previewFrame)

        val tvMetrics = TextView(this).apply {
            text = binding.labsTftDebug.tvTftDbgMetrics.text
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dpToPx(6))
        }
        root.addView(tvMetrics)

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun addBarBtn(label: String, onClick: () -> Unit) {
            val b = MaterialButton(this).apply {
                text = label
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                minimumHeight = 0
                setPadding(dpToPx(4), 0, dpToPx(4), 0)
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1E293B"))
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply {
                    marginEnd = dpToPx(4)
                }
                setOnClickListener {
                    provideHapticFeedback()
                    onClick()
                }
            }
            actionBar.addView(b)
        }
        addBarBtn("Recenter") { wifiMgr.simulateHandlebarRecenter() }
        addBarBtn("Day/Night") { wifiMgr.toggleDebugDaylightContrast() }
        addBarBtn("Park/Ride") { wifiMgr.toggleDebugStationaryMode() }
        addBarBtn("Guides") { wifiMgr.toggleDebugHitboxOverlay() }
        addBarBtn("NAV_V2") { wifiMgr.runNavV2LoopbackDiagnostics() }
        root.addView(actionBar)

        attachTftTouchMapper(ivFull) { x, y ->
            wifiMgr.simulateClusterTouch(9, x, y)
            tvTouch.text = "Touch @ ($x, $y)"
        }

        // Seed currently cached frame asynchronously using the fullscreen bitmap pool
        wifiMgr.latestDebugFrame.value?.let { frame ->
            lifecycleScope.launch {
                val targetFullSlot = (fullscreenBitmapSlotIdx + 1) % fullscreenBitmapSlots.size
                val seeded = decodeSlotBitmap(frame, fullscreenBitmapSlots, targetFullSlot)
                if (seeded != null && dialog.isShowing && ivFull === fullscreenTftImageView) {
                    ivFull.setImageBitmap(seeded)
                    fullscreenBitmapSlotIdx = targetFullSlot
                    lastFullscreenFrameIndex = frame.frameIndex
                }
            }
        }

        fullscreenTftImageView = ivFull
        fullscreenTftMetricsView = tvMetrics
        fullscreenTftTouchView = tvTouch
        fullscreenTftDialog = dialog

        dialog.setOnDismissListener {
            if (fullscreenTftDialog === dialog) {
                fullscreenTftDialog = null
                fullscreenTftImageView = null
                fullscreenTftMetricsView = null
                fullscreenTftTouchView = null
                lastFullscreenFrameIndex = -1L
                if (!isAccountTabVisible.value && wifiMgr.isLocalPreviewActiveFlow.value) {
                    wifiMgr.stopLocalDebugPreview()
                }
            }
        }
        dialog.setContentView(root)
        dialog.show()
    }

    private fun updateDebugFilterChipsUi() {
        val theme = currentTheme
        val dbg = binding.labsTftDebug
        val chips = listOf(
            dbg.btnLogFilterAll to DebugFilter.ALL,
            dbg.btnLogFilterBle to DebugFilter.BLE,
            dbg.btnLogFilterWifi to DebugFilter.WIFI_NAV_V2,
            dbg.btnLogFilterCall to DebugFilter.TELEPHONY,
            dbg.btnLogFilterMaps to DebugFilter.MAPS,
            dbg.btnLogFilterErr to DebugFilter.ERRORS
        )
        for ((btn, filter) in chips) {
            val selected = activeDebugFilter == filter
            if (selected) {
                btn.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
                btn.strokeColor = ColorStateList.valueOf(theme.primaryColor)
                btn.setTextColor(theme.onPrimaryColor)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
                btn.strokeColor = ColorStateList.valueOf(theme.cardBorderColor)
                btn.setTextColor(theme.textSecondaryColor)
            }
        }
    }

    private fun renderDebugLogsUi(entries: List<com.pulsarnav.app.debug.DebugLogEntry>, forceRender: Boolean) {
        if (!forceRender && !isAccountTabVisible.value) return
        val dbg = binding.labsTftDebug
        dbg.tvBleDiagBanner.text = bleManager.getDebugDiagnosticsSummary()
        val filterSnapshot = activeDebugFilter
        val errCount = DebugLogRepository.errorCount.value

        if (isDebugLogPaused && !forceRender) {
            val filteredCount = DebugLogRepository.filterEntries(entries, filterSnapshot).size
            dbg.tvDebugLogStats.text = "$filteredCount/${entries.size} • $errCount err • PAUSED"
            return
        }

        renderLogJob?.cancel()
        renderLogJob = lifecycleScope.launch {
            val (statsText, formattedText) = withContext(Dispatchers.Default) {
                val filtered = DebugLogRepository.filterEntries(entries, filterSnapshot)
                val pauseTag = if (isDebugLogPaused) " • PAUSED" else ""
                val stats = "${filtered.size}/${entries.size} • $errCount err$pauseTag"
                val recent = if (filtered.size > 140) filtered.takeLast(140) else filtered
                val body = if (recent.isEmpty()) {
                    "No entries matching '${filterSnapshot.label}'. Trigger BLE, TFT preview, or NAV_V2 test."
                } else {
                    recent.joinToString("\n") { DebugLogRepository.formatEntryLine(it) }
                }
                stats to body
            }

            if (isDestroyed || isFinishing) return@launch
            dbg.tvDebugLogStats.text = statsText

            if (formattedText != lastRenderedLogText) {
                val isFirstRender = lastRenderedLogText == null
                val inner = dbg.scrollDebugLogs.getChildAt(0)
                val wasNearBottom = inner == null ||
                    !dbg.scrollDebugLogs.canScrollVertically(1) ||
                    (inner.bottom - (dbg.scrollDebugLogs.height + dbg.scrollDebugLogs.scrollY) < dpToPx(24))
                lastRenderedLogText = formattedText
                dbg.tvDebugLogOutput.text = formattedText

                // Only auto-scroll INSIDE scrollDebugLogs using focus-safe scrollTo() when user is already
                // pinned at bottom on an explicit log append — never call fullScroll(FOCUS_DOWN), which
                // hijacks focus up to outer scrollSettings and yanks the Account page to the bottom.
                if (!isFirstRender && !isDebugLogPaused && wasNearBottom) {
                    dbg.scrollDebugLogs.post {
                        val content = dbg.scrollDebugLogs.getChildAt(0) ?: return@post
                        val targetY = (content.height - dbg.scrollDebugLogs.height).coerceAtLeast(0)
                        dbg.scrollDebugLogs.scrollTo(0, targetY)
                    }
                }
            }
        }
    }

    private fun shareDebugLogText() {
        val entriesSnapshot = DebugLogRepository.entries.value
        val filterSnapshot = activeDebugFilter
        val header = "PulsarNav v${com.pulsarnav.app.BuildConfig.VERSION_NAME} • Model=${appSettings.selectedModelId} • ${bleManager.getDebugDiagnosticsSummary()}"
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val filtered = DebugLogRepository.filterEntries(entriesSnapshot, filterSnapshot)
                    val payload = DebugLogRepository.formatLogsForExport(filtered, headerInfo = header)
                    val dir = File(cacheDir, "debug_logs").apply { mkdirs() }
                    val logFile = File(dir, "pulsarnav-log.txt")
                    logFile.writeText(payload, Charsets.UTF_8)
                    FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.fileprovider",
                        logFile
                    )
                }
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "PulsarNav Diagnostic Log (${activeDebugFilter.label})")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("PulsarNav Diagnostic Log", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(sendIntent, "Share PulsarNav Diagnostic Log"))
            } catch (e: Exception) {
                showFeedback("Unable to open Share sheet: ${e.message}", isAlert = true)
            }
        }
    }

    private fun showTftWifiConfigDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, dpToPx(12), pad, 0)
        }

        if (!isWifiEnabled()) {
            val wifiWarningCard = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val padH = dpToPx(12)
                val padV = dpToPx(8)
                setPadding(padH, padV, padH, padV)
                val amberColor = ContextCompat.getColor(this@MainActivity, R.color.amber_accent)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(ColorUtils.setAlphaComponent(amberColor, 35))
                    setStroke(dpToPx(1), amberColor)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dpToPx(12)) }
            }
            val tvWarn = android.widget.TextView(this).apply {
                text = "⚠️ Wi-Fi is turned off on your phone. Turn on Wi-Fi to connect and cast to your cluster."
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.amber_accent))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnEnable = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Turn On"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.amber_accent))
                setOnClickListener {
                    promptEnableWifi(PendingWifiActionType.CONFIG_DIALOG) { showTftWifiConfigDialog() }
                }
            }
            wifiWarningCard.addView(tvWarn)
            wifiWarningCard.addView(btnEnable)
            container.addView(wifiWarningCard)
        }

        val infoText = android.widget.TextView(this).apply {
            text = "For Bajaj NS400Z / TFT clusters. Leave SSID blank to auto-use the connected PULSAR device name. If you enter your 17-char VIN, PulsarNav derives the OEM SoftAP WPA2 password automatically."
            textSize = 12f
            setTextColor(currentTheme.textSecondaryColor)
        }
        container.addView(infoText)

        val etSsid = createThemedEditText("Custom Wi-Fi SSID (optional, e.g. PULSAR1854)").apply {
            setText(appSettings.tftWifiSsid)
        }
        container.addView(etSsid)

        val etVin = createThemedEditText("17-char Bike VIN (for auto WPA2 password)").apply {
            setText(appSettings.tftWifiVin)
        }
        container.addView(etVin)

        val etPass = createThemedEditText("Or manual WPA2 passphrase (optional)").apply {
            setText(appSettings.tftWifiPassphrase)
        }
        container.addView(etPass)

        val tvPreview = android.widget.TextView(this).apply {
            textSize = 11f
            setTextColor(currentTheme.accentColor)
            setPadding(0, dpToPx(8), 0, 0)
        }
        fun refreshPreview() {
            val vin = etVin.text.toString().trim()
            val manual = etPass.text.toString().trim()
            tvPreview.text = when {
                manual.isNotEmpty() -> "Using custom WPA2 passphrase (${manual.length} chars)"
                vin.isNotEmpty() -> "Derived OEM PSK: ${TftPasswordGenerator.deriveSoftApPassword(vin)}"
                else -> "Tip: Joining PULSAR Wi-Fi manually in Android Settings works without VIN too."
            }
        }
        refreshPreview()
        etVin.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refreshPreview() }
        })
        etPass.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refreshPreview() }
        })
        container.addView(tvPreview)

        val tvModeHeader = android.widget.TextView(this).apply {
            text = "Mirror Mode (Screen-Off Safe):"
            textSize = 12f
            setPadding(0, dpToPx(12), 0, dpToPx(4))
            setTextColor(currentTheme.textPrimaryColor)
        }
        container.addView(tvModeHeader)

        val rgMirrorMode = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val rbLiveMap = android.widget.RadioButton(this).apply {
            text = "PulsarNav Live Map (OSM + GPS test only)"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
        }
        val rbHud = android.widget.RadioButton(this).apply {
            text = "Cockpit HUD (Default • GMap TBT, lock-proof)"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
        }
        rgMirrorMode.addView(rbHud)
        rgMirrorMode.addView(rbLiveMap)
        if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_LIVE_MAP) rbLiveMap.isChecked = true else rbHud.isChecked = true
        container.addView(rgMirrorMode)

        val tvCastHeader = android.widget.TextView(this).apply {
            text = "Cast App NS400Z Safe Viewport & Landscape:"
            textSize = 12f
            setPadding(0, dpToPx(10), 0, dpToPx(4))
            setTextColor(currentTheme.textPrimaryColor)
        }
        container.addView(tvCastHeader)

        val rgCastLayout = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val rbCastFit = android.widget.RadioButton(this).apply {
            text = "Fit Full Width (754px • Uncropped • No Black Sidebars)"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
        }
        val rbCastFill = android.widget.RadioButton(this).apply {
            text = "Fill Safe Panorama (754×256 • Top-Anchored • Above Speedo)"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
        }
        val rbCastRot90 = android.widget.RadioButton(this).apply {
            text = "Auto-Panorama (Rotate Portrait -90° • Direct Fill in Landscape)"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
        }
        rgCastLayout.addView(rbCastFit)
        rgCastLayout.addView(rbCastFill)
        rgCastLayout.addView(rbCastRot90)
        when (appSettings.tftCastLayoutMode) {
            AppSettings.CAST_LAYOUT_FILL -> rbCastFill.isChecked = true
            AppSettings.CAST_LAYOUT_ROT90 -> rbCastRot90.isChecked = true
            else -> rbCastFit.isChecked = true
        }
        container.addView(rgCastLayout)

        val cbAutoOpenMaps = android.widget.CheckBox(this).apply {
            text = "Auto-open Google Maps when starting Live Cast"
            isChecked = appSettings.isTftCastAutoOpenMapsEnabled
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, dpToPx(8), 0, 0)
        }
        container.addView(cbAutoOpenMaps)

        val canWriteRotation = android.provider.Settings.System.canWrite(this)
        val cbForceLandscape = android.widget.CheckBox(this).apply {
            text = if (canWriteRotation) {
                "Auto-lock phone to Landscape during Live Cast"
            } else {
                "Auto-lock phone to Landscape during Cast (tap to allow)"
            }
            isChecked = appSettings.isTftCastForceLandscapeEnabled
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setOnClickListener {
                if (isChecked && !android.provider.Settings.System.canWrite(this@MainActivity)) {
                    try {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        }
        container.addView(cbForceLandscape)

        val scrollWrapper = android.widget.ScrollView(this).apply {
            addView(container)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("TFT Wi-Fi & Display Config")
            .setView(scrollWrapper)
            .setPositiveButton("Save") { _, _ ->
                appSettings.tftWifiSsid = etSsid.text.toString().trim()
                appSettings.tftWifiVin = etVin.text.toString().trim()
                appSettings.tftWifiPassphrase = etPass.text.toString().trim()
                val nextMode = if (rbLiveMap.isChecked) AppSettings.TFT_MODE_LIVE_MAP else AppSettings.TFT_MODE_HUD
                appSettings.tftMirrorMode = nextMode
                appSettings.tftCastLayoutMode = when {
                    rbCastFill.isChecked -> AppSettings.CAST_LAYOUT_FILL
                    rbCastRot90.isChecked -> AppSettings.CAST_LAYOUT_ROT90
                    else -> AppSettings.CAST_LAYOUT_FIT
                }
                appSettings.isTftCastAutoOpenMapsEnabled = cbAutoOpenMaps.isChecked
                appSettings.isTftCastForceLandscapeEnabled = cbForceLandscape.isChecked
                TftWifiMirrorManager.getInstance(this).switchHeadlessModeIfActive(nextMode)
                if (!isWifiEnabled()) {
                    showFeedback("TFT settings saved • Remember to turn on Wi-Fi before casting", isAlert = true)
                } else {
                    showFeedback("TFT settings saved")
                }
            }
            .setNeutralButton("Clear Wi-Fi") { _, _ ->
                appSettings.tftWifiSsid = ""
                appSettings.tftWifiVin = ""
                appSettings.tftWifiPassphrase = ""
                showFeedback("TFT Wi-Fi credentials cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTftRouteFromSharedInput(rawInput: String) {
        val mapEngine = TftNavigationMapEngine.getInstance(this)
        val wifiMgr = TftWifiMirrorManager.getInstance(this)
        BikeBleForegroundService.startForLocationNavigation(this)
        if (wifiMgr.mirrorState.value.status == TftMirrorStatus.IDLE) {
            appSettings.tftMirrorMode = AppSettings.TFT_MODE_LIVE_MAP
            if (isAccountTabVisible.value || fullscreenTftDialog?.isShowing == true) {
                wifiMgr.startLocalDebugPreview()
            }
        }
        showFeedback("Resolving destination for PulsarNav Live Map...")
        mapEngine.startRouteFromInput(rawInput) { ok, message ->
            runOnUiThread {
                showFeedback(message, isAlert = !ok)
            }
        }
    }

    private fun showDestinationSearchAndRouteDialog() {
        val mapEngine = TftNavigationMapEngine.getInstance(this)
        val activeState = mapEngine.uiState.value
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, dpToPx(12), pad, 0)
        }
        val info = android.widget.TextView(this).apply {
            text = "Search any place, enter lat,lng, or paste a shared Google Maps link to route on the 800×480 Live Map & cluster TBT (keeps working when phone is locked)."
            textSize = 12f
            setTextColor(currentTheme.textSecondaryColor)
        }
        layout.addView(info)

        val input = createThemedEditText("Place, address, or Google Maps URL").apply {
            setPadding(0, dpToPx(10), 0, 0)
        }
        layout.addView(input)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Route on PulsarNav TFT Map")
            .setView(layout)
            .setPositiveButton("Route on TFT Map") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isBlank()) {
                    showFeedback("Enter a destination or share a pin from Google Maps", isAlert = true)
                } else {
                    startTftRouteFromSharedInput(q)
                }
            }
            .setNeutralButton("Open Google Maps") { _, _ ->
                launchGoogleMapsRide()
            }
        if (activeState.routeActive || mapEngine.hasPendingRoute()) {
            builder.setNegativeButton("Stop Route") { _, _ ->
                mapEngine.clearActiveRoute()
                showFeedback("PulsarNav Live Map route cleared")
            }
        } else {
            builder.setNegativeButton("Cancel", null)
        }
        builder.show()
    }

    private fun showMapsTbtTroubleshooterDialog() {
        val granted = isNotificationAccessGranted()
        val diag = GoogleMapsNotificationListener.diagnostics.value
        val activeRoute = TftNavigationMapEngine.getInstance(this).uiState.value

        val statusLines = buildString {
            append("• Notification Access: ${if (granted) "GRANTED" else "MISSING"}\n")
            append("• Listener Service: ${if (diag.isConnected) "CONNECTED" else "DISCONNECTED"}\n")
            append("• Maps Notif in Shade: ${if (diag.mapsActiveInShade) "DETECTED" else "NOT FOUND"}\n")
            if (diag.lastNavSummary.isNotBlank()) {
                append("• Last TBT: ${diag.lastNavSummary}\n")
            }
            if (activeRoute.routeActive) {
                append("• Built-in Live Map Route: ${activeRoute.destinationLabel} (${activeRoute.remainingDistanceText})\n")
            }
            append("\nIf Google Maps is in mini PiP window with NO notification in the top shade (common on ColorOS / OxygenOS / RealmeUI):\n")
            append("1. Tap \"Maps Notifs\" below and turn ON Notifications -> \"Navigation\".\n")
            append("2. Tap \"Maps Battery\" and set to Unrestricted / Allow background activity.\n")
            append("3. Or inside Google Maps, tap Share -> PulsarNav to route directly on our 800×480 Live Map (works locked & needs zero Maps notification).")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Google Maps TBT Diagnostics")
            .setMessage(statusLines)
            .setPositiveButton("Maps Notifs") { _, _ ->
                openAppNotificationSettings("com.google.android.apps.maps")
            }
            .setNeutralButton("Maps Battery") { _, _ ->
                openAppDetailsSettings("com.google.android.apps.maps")
            }
            .setNegativeButton("Route in App") { _, _ ->
                showDestinationSearchAndRouteDialog()
            }
            .show()
    }

    private fun openAppNotificationSettings(targetPackage: String) {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, targetPackage)
            }
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings(targetPackage)
        }
    }

    private fun openAppDetailsSettings(targetPackage: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$targetPackage")
            }
            startActivity(intent)
        } catch (e: Exception) {
            showFeedback("Unable to open settings: ${e.message}", isAlert = true)
        }
    }

    private fun updateDeviceList(bikes: List<DiscoveredBike>) {
        binding.layoutDeviceList.removeAllViews()
        for (bike in bikes) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_device, binding.layoutDeviceList, false)
            itemView.findViewById<TextView>(R.id.tvDeviceName).text = bike.name
            itemView.findViewById<TextView>(R.id.tvDeviceAddress).text = "${bike.address}  •  ${bike.rssi} dBm"
            itemView.findViewById<MaterialButton>(R.id.btnConnectDevice).setOnClickListener {
                bleManager.connect(bike.device)
            }
            binding.layoutDeviceList.addView(itemView)
        }
    }

    private fun observeNavigation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        GoogleMapsNotificationListener.currentNavStep,
                        GoogleMapsNotificationListener.diagnostics,
                        TftNavigationMapEngine.getInstance(this@MainActivity).uiState
                    ) { extStep, diag, mapUi ->
                        Triple(extStep, diag, mapUi)
                    }.collectLatest { (extStep, diag, mapUi) ->
                        val step = extStep ?: TftNavigationMapEngine.getInstance(this@MainActivity).getCurrentStepForHud()
                        if (step != null) {
                            val dist = step.rawDistanceText.ifBlank { "${step.distanceMeters} m" }
                            val maneuverDesc = if (step.roundaboutExit > 0) {
                                "Roundabout (Exit ${step.roundaboutExit})"
                            } else {
                                step.maneuver.description
                            }
                            val street = if (step.roundaboutExit > 0 && step.streetName.isNotBlank()) {
                                "Exit ${step.roundaboutExit} • ${step.streetName}"
                            } else {
                                step.streetName.ifBlank { maneuverDesc }
                            }
                            val etaStr = if (step.etaHour > 0) {
                                val ampm = if (step.etaIsAm) "AM" else "PM"
                                "ETA %d:%02d %s".format(step.etaHour, step.etaMinute, ampm)
                            } else {
                                mapUi.remainingEtaText.ifBlank { "Active Route" }
                            }
                            updateNavDisplay(step.maneuver, dist, street, etaStr)
                        } else if (!bleManager.isSimulationRunning.value) {
                            binding.tvDistance.text = "-- m"
                            binding.tvStreetName.text = if (!isNotificationAccessGranted()) {
                                "Enable Notification Access or tap Search"
                            } else if (!diag.mapsActiveInShade) {
                                "Waiting for Maps Notif (tap Maps Fix ›)"
                            } else {
                                "Waiting for Google Maps..."
                            }
                            binding.tvManeuverIcon.text = "⬆"
                            binding.tvEtaBadge.text = "-- min ETA"
                        }
                        updateNotificationAccessUi()
                    }
                }

                launch {
                    combine(bleManager.isSimulationRunning, bleManager.currentActiveManeuver) { running, maneuver ->
                        Pair(running, maneuver)
                    }.collectLatest { (running, maneuver) ->
                        updateSimulationUiState(running, maneuver)
                    }
                }
            }
        }
    }

    private fun updateNavDisplay(m: Maneuver, distText: String, street: String, etaText: String) {
        binding.tvDistance.text = distText
        binding.tvStreetName.text = street
        binding.tvManeuverIcon.text = getArrowForManeuver(m)
        binding.tvEtaBadge.text = etaText
    }

    private fun updateSimulationUiState(running: Boolean, maneuver: Maneuver?) {
        val theme = currentTheme
        val defaultSubtle = ColorStateList.valueOf(theme.subtleColor)
        val defaultBorder = ColorStateList.valueOf(theme.borderColor)
        val defaultText = theme.accentColor

        val activeTint = ColorStateList.valueOf(theme.primaryColor)
        val activeText = theme.onPrimaryColor

        if (running && maneuver == Maneuver.TURN_LEFT) {
            binding.btnTestLeft.backgroundTintList = activeTint
            binding.btnTestLeft.setTextColor(activeText)
        } else {
            binding.btnTestLeft.backgroundTintList = defaultSubtle
            binding.btnTestLeft.strokeColor = defaultBorder
            binding.btnTestLeft.setTextColor(defaultText)
        }

        if (running && maneuver == Maneuver.STRAIGHT) {
            binding.btnTestStraight.backgroundTintList = activeTint
            binding.btnTestStraight.setTextColor(activeText)
        } else {
            binding.btnTestStraight.backgroundTintList = defaultSubtle
            binding.btnTestStraight.strokeColor = defaultBorder
            binding.btnTestStraight.setTextColor(defaultText)
        }

        if (running && maneuver == Maneuver.TURN_RIGHT) {
            binding.btnTestRight.backgroundTintList = activeTint
            binding.btnTestRight.setTextColor(activeText)
        } else {
            binding.btnTestRight.backgroundTintList = defaultSubtle
            binding.btnTestRight.strokeColor = defaultBorder
            binding.btnTestRight.setTextColor(defaultText)
        }

        if (running && (maneuver == Maneuver.ROUNDABOUT_LEFT || maneuver == Maneuver.ROUNDABOUT_RIGHT)) {
            binding.btnTestRoundabout.backgroundTintList = activeTint
            binding.btnTestRoundabout.setTextColor(activeText)
        } else {
            binding.btnTestRoundabout.backgroundTintList = defaultSubtle
            binding.btnTestRoundabout.strokeColor = defaultBorder
            binding.btnTestRoundabout.setTextColor(defaultText)
        }

        if (running) {
            binding.btnStopSimulation.strokeColor = ColorStateList.valueOf(theme.accentColor)
            binding.btnStopSimulation.setTextColor(theme.accentColor)
        } else {
            binding.btnStopSimulation.strokeColor = defaultBorder
            binding.btnStopSimulation.setTextColor(theme.textSecondaryColor)
        }
    }

    private fun observeTelephonyAndAlerts() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    callManager.callInfo.collectLatest { call ->
                        when (call.state) {
                            CallState.INCOMING_CALL -> {
                                binding.tvCallerStatus.text = "Incoming Call: ${call.callerName ?: "Unknown"} (${call.callerNumber.orEmpty()})"
                                binding.tvCallerStatus.setTextColor(currentTheme.accentColor)
                                binding.btnSimulateCall.text = "End Call"
                            }
                            CallState.ACTIVE_CALL -> {
                                binding.tvCallerStatus.text = "Active Call: ${call.callerName ?: "In Call"}"
                                binding.tvCallerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                                binding.btnSimulateCall.text = "End Call"
                            }
                            CallState.OUTGOING_CALL -> {
                                binding.tvCallerStatus.text = "Outgoing Call: ${call.callerName ?: "Dialing..."}"
                                binding.tvCallerStatus.setTextColor(currentTheme.accentColor)
                                binding.btnSimulateCall.text = "End Call"
                            }
                            else -> {
                                binding.tvCallerStatus.text = "Phone Idle • No incoming call"
                                binding.tvCallerStatus.setTextColor(currentTheme.textPrimaryColor)
                                binding.btnSimulateCall.text = "Call"
                            }
                        }
                        binding.tvMissedCallBadge.text = "${call.missedCallCount} Missed"
                    }
                }

                launch {
                    mediaControlManager.lastMediaEvent.collectLatest { event ->
                        if (event != null) {
                            binding.tvMediaBanner.text = "Media Control: $event"
                            binding.tvMediaBanner.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                        } else {
                            binding.tvMediaBanner.text = "Handlebar Button: Click = Play/Pause • Hold = Next • Click+Hold = Prev"
                            binding.tvMediaBanner.setTextColor(currentTheme.accentColor)
                        }
                    }
                }

                launch {
                    GoogleMapsNotificationListener.lastWhatsAppAlert.collectLatest { alert ->
                        if (alert != null) {
                            val label = if (alert.alertType == com.pulsarnav.app.protocol.AlertType.SMS) {
                                "SMS Alert"
                            } else {
                                "WhatsApp Alert"
                            }
                            binding.tvWhatsAppAlertBanner.text = "$label: ${alert.sender} (Showing on cluster)"
                            binding.tvWhatsAppAlertBanner.setTextColor(currentTheme.accentColor)
                        } else {
                            binding.tvWhatsAppAlertBanner.text = "Messaging Alerts: Listening (WhatsApp & SMS)"
                            binding.tvWhatsAppAlertBanner.setTextColor(currentTheme.textSecondaryColor)
                        }
                    }
                }

                launch {
                    bleManager.lastClusterAction.collectLatest { action ->
                        if (action != null) {
                            binding.tvClusterActionStatus.text = "Cluster Action: $action"
                            binding.tvClusterActionStatus.setTextColor(currentTheme.accentColor)
                        } else {
                            binding.tvClusterActionStatus.text = "Cluster Action: Waiting for switchgear / phonebook sync"
                            binding.tvClusterActionStatus.setTextColor(currentTheme.textSecondaryColor)
                        }
                    }
                }
            }
        }
    }

    private fun showServiceTargetDialog() {
        provideHapticFeedback()
        val status = maintenanceManager.status.value
        val currentOdo = status.totalOdometerKm
        val currentTarget = status.nextServiceTargetOdoKm

        val options = mutableListOf<String>()
        options.add("1st Service (500 km - Break-in / New Bike)")
        options.add("2nd Service (5,000 km)")
        options.add("3rd Service (10,000 km)")
        options.add("Periodic (+5,000 km from current ODO)")
        if (status.isServiceOverdue) {
            options.add("Overdue Extension (+500 km)")
            options.add("Mark Service Completed Today")
        }
        options.add("Custom Target Odometer...")

        val titleText = if (status.isServiceOverdue) {
            "Service OVERDUE by %.0f km (Target: %.0f)".format(status.serviceOverdueKm, currentTarget)
        } else {
            "Service Target (%.0f km left • Target: %.0f km)".format(status.serviceRemainingKm, currentTarget)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(titleText)
            .setItems(options.toTypedArray()) { _, which ->
                provideHapticFeedback()
                when (options[which]) {
                    "1st Service (500 km - Break-in / New Bike)" -> {
                        maintenanceManager.setNextServiceTargetOdo(500.0)
                        recordSyncTime()
                        showFeedback("Next service target set to 500 km (1st Service)")
                    }
                    "2nd Service (5,000 km)" -> {
                        maintenanceManager.setNextServiceTargetOdo(5000.0)
                        recordSyncTime()
                        showFeedback("Next service target set to 5,000 km (2nd Service)")
                    }
                    "3rd Service (10,000 km)" -> {
                        maintenanceManager.setNextServiceTargetOdo(10000.0)
                        recordSyncTime()
                        showFeedback("Next service target set to 10,000 km (3rd Service)")
                    }
                    "Periodic (+5,000 km from current ODO)" -> {
                        val target = currentOdo + MaintenanceManager.SERVICE_INTERVAL_KM
                        maintenanceManager.setNextServiceTargetOdo(target)
                        recordSyncTime()
                        showFeedback(String.format(Locale.US, "Next service target set to %.0f km", target))
                    }
                    "Overdue Extension (+500 km)" -> {
                        maintenanceManager.rescheduleNextService(500.0)
                        recordSyncTime()
                        showFeedback("Service rescheduled. Extended by 500 km.")
                    }
                    "Mark Service Completed Today" -> {
                        maintenanceManager.markServiceDone()
                        recordSyncTime()
                        showFeedback(String.format(Locale.US, "Service marked completed. Next target: %.0f km", maintenanceManager.status.value.nextServiceTargetOdoKm))
                    }
                    "Custom Target Odometer..." -> {
                        showCustomServiceTargetDialog()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomServiceTargetDialog() {
        val input = createThemedEditText(
            hintText = "Target Odometer in km",
            initialText = String.format(Locale.US, "%.0f", maintenanceManager.status.value.nextServiceTargetOdoKm),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val container = FrameLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Set Custom Service Target Odometer")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val target = parseDecimal(input.text?.toString())
                if (target != null && target > 0) {
                    maintenanceManager.setNextServiceTargetOdo(target)
                    recordSyncTime()
                    showFeedback(String.format(Locale.US, "Next service target set to %.0f km", target))
                } else {
                    showFeedback("Invalid odometer value", isAlert = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateBikeIssuesSummary() {
        val issues = appSettings.getBikeIssues()
        val openCount = issues.count { !it.isResolved }
        if (openCount > 0) {
            binding.tvIssuesCountBadge.text = "$openCount Open"
            binding.tvIssuesCountBadge.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
            binding.tvIssuesCountBadge.setTextColor(currentTheme.accentColor)
        } else {
            binding.tvIssuesCountBadge.text = "0 Pending"
            binding.tvIssuesCountBadge.backgroundTintList = ColorStateList.valueOf(if (currentTheme.isLight) Color.parseColor("#EAECEF") else Color.parseColor("#2A2832"))
            binding.tvIssuesCountBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        }
    }

    private fun showBikeIssuesChecklistDialog() {
        provideHapticFeedback()
        val dialog = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 48)
            setBackgroundColor(currentTheme.cardColor)
        }

        val title = TextView(this).apply {
            text = "Bike Issues & Repair Checklist"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(currentTheme.textPrimaryColor)
        }
        val subtitle = TextView(this).apply {
            text = "Track mechanical symptoms and parts needing attention before your next service."
            textSize = 12f
            setTextColor(currentTheme.textTertiaryColor)
            setPadding(0, 8, 0, 24)
        }
        root.addView(title)
        root.addView(subtitle)

        // Input row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        val etNewIssue = createThemedEditText(
            hintText = "Add issue (e.g. front brake squeal)..."
        ).apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnAdd = MaterialButton(this).apply {
            text = "Add"
            textSize = 12f
            backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
            setTextColor(currentTheme.onPrimaryColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 16
            }
        }
        inputRow.addView(etNewIssue)
        inputRow.addView(btnAdd)
        root.addView(inputRow)

        val scrollView = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(300))
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(listContainer)
        root.addView(scrollView)

        fun renderIssues() {
            listContainer.removeAllViews()
            val issues = appSettings.getBikeIssues()
            if (issues.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "No issues logged. Your bike is running in top condition."
                    textSize = 13f
                    setTextColor(currentTheme.textTertiaryColor)
                    setPadding(0, 32, 0, 32)
                    gravity = Gravity.CENTER
                }
                listContainer.addView(emptyTv)
                return
            }

            for (issue in issues) {
                val itemRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 16, 0, 16)
                }
                val chk = MaterialCheckBox(this).apply {
                    isChecked = issue.isResolved
                    buttonTintList = ColorStateList.valueOf(currentTheme.accentColor)
                    setOnCheckedChangeListener { _, _ ->
                        provideHapticFeedback()
                        appSettings.toggleBikeIssue(issue.id)
                        updateBikeIssuesSummary()
                        renderIssues()
                    }
                }
                val textLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(8, 0, 8, 0)
                }
                val tvDesc = TextView(this).apply {
                    text = issue.title
                    textSize = 14f
                    if (issue.isResolved) {
                        paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                        setTextColor(currentTheme.textTertiaryColor)
                    } else {
                        setTextColor(currentTheme.textPrimaryColor)
                    }
                }
                val tvDate = TextView(this).apply {
                    text = if (issue.isResolved) "Resolved • ${issue.dateLogged}" else "Added • ${issue.dateLogged}"
                    textSize = 10f
                    setTextColor(currentTheme.textTertiaryColor)
                }
                textLayout.addView(tvDesc)
                textLayout.addView(tvDate)

                val btnDel = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Del"
                    textSize = 10f
                    setPadding(0, 0, 0, 0)
                    minWidth = 0
                    minimumWidth = 0
                    layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(32))
                    setOnClickListener {
                        provideHapticFeedback()
                        appSettings.deleteBikeIssue(issue.id)
                        updateBikeIssuesSummary()
                        renderIssues()
                    }
                }

                itemRow.addView(chk)
                itemRow.addView(textLayout)
                itemRow.addView(btnDel)
                listContainer.addView(itemRow)
            }
        }

        btnAdd.setOnClickListener {
            provideHapticFeedback()
            val desc = etNewIssue.text.toString().trim()
            if (desc.isNotBlank()) {
                appSettings.addBikeIssue(desc)
                etNewIssue.text?.clear()
                updateBikeIssuesSummary()
                renderIssues()
            }
        }

        renderIssues()
        dialog.setContentView(root)
        dialog.show()
    }

    @SuppressLint("SetTextI18n")
    private fun observeMaintenance() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                maintenanceManager.status.collectLatest { s ->
                    updateHeroRangeDisplay()
                    binding.tvChainRemaining.text = String.format(Locale.US, "%.0f km left", s.chainLubeRemainingKm)
                    binding.progressChain.progress = s.chainLubePercent
                    binding.tvSupportChainRemaining.text = String.format(Locale.US, "Chain Lube: %.0f km left", s.chainLubeRemainingKm.coerceAtLeast(0.0))

                    if (s.isServiceOverdue) {
                        binding.tvSupportServiceRemaining.text = "OVERDUE"
                        binding.tvSupportServiceRemainingUnit.visibility = View.GONE
                        binding.tvSupportServiceRemaining.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                        binding.tvServiceHistorySummary.text = "Service Overdue • Immediate maintenance required"
                        binding.progressSupportService.progress = 100
                        binding.tvSupportHealthNote.text = "Schedule periodic service to maintain engine longevity"
                        binding.tvServiceRemaining.text = String.format(Locale.US, "OVERDUE by %.0f km", s.serviceOverdueKm)
                        binding.tvServiceRemaining.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                        binding.progressService.progress = 100
                    } else {
                        binding.tvSupportServiceRemaining.text = String.format(Locale.US, "%.0f", s.serviceRemainingKm.coerceAtLeast(0.0))
                        binding.tvSupportServiceRemainingUnit.visibility = View.VISIBLE
                        binding.tvSupportServiceRemaining.setTextColor(currentTheme.accentColor)
                        val serviceInterval = MaintenanceManager.SERVICE_INTERVAL_KM
                        val serviceElapsed = (serviceInterval - s.serviceRemainingKm.coerceIn(0.0, serviceInterval))
                        val serviceProgress = ((serviceElapsed / serviceInterval) * 100).toInt().coerceIn(0, 100)
                        binding.progressSupportService.progress = serviceProgress
                        binding.tvServiceHistorySummary.text = "Periodic Service • $serviceProgress% of cycle elapsed"
                        binding.tvSupportHealthNote.text = String.format(Locale.US, "Next routine service due in %.0f km", s.serviceRemainingKm.coerceAtLeast(0.0))
                        binding.tvServiceRemaining.text = String.format(Locale.US, "%.0f km left (Target: %.0f km)", s.serviceRemainingKm, s.nextServiceTargetOdoKm)
                        binding.tvServiceRemaining.setTextColor(if (s.isServiceDue) ContextCompat.getColor(this@MainActivity, R.color.amber_accent) else currentTheme.accentColor)
                        binding.progressService.progress = s.servicePercent
                    }

                    if (s.isChainLubeDue) {
                        binding.tvChainRemaining.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_scanning))
                    } else {
                        binding.tvChainRemaining.setTextColor(currentTheme.accentColor)
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeFuelLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    fuelLogManager.stats.collectLatest { stats ->
                        updateHeroRangeDisplay()
                        updateBikeInvestmentDisplay()
                        if (stats.averageKmpl > 0.0) {
                            binding.tvAvgMileage.text = formatDecimal(stats.averageKmpl)
                            binding.tvAvgMileageUnit.visibility = View.VISIBLE
                        } else {
                            binding.tvAvgMileage.text = "--"
                            binding.tvAvgMileageUnit.visibility = View.VISIBLE
                        }
                        if (stats.costPerKm > 0.0) {
                            binding.tvCostPerKm.text = String.format(Locale.US, "Cost: %s%.2f / km", appSettings.currentCurrencySymbol, stats.costPerKm)
                        } else {
                            binding.tvCostPerKm.text = "Cost: -- / km"
                        }
                    }
                }
                launch {
                    fuelLogManager.entries.collectLatest { entries ->
                        if (binding.scrollFuel.visibility == View.VISIBLE) {
                            fuelListThemeDirty = false
                            updateFuelList(entries)
                        } else {
                            fuelListThemeDirty = true
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateFuelList(entries: List<com.pulsarnav.app.maintenance.FuelEntry>) {
        binding.layoutFuelList.removeAllViews()
        if (entries.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No refuel records yet. Tap 'Log Refueling' above to track fuel & economy."
                setTextColor(currentTheme.textTertiaryColor)
                textSize = 13f
                setPadding(10, 20, 10, 20)
            }
            binding.layoutFuelList.addView(emptyView)
            return
        }
        for (entry in entries) {
            val view = LayoutInflater.from(this).inflate(R.layout.item_fuel, binding.layoutFuelList, false)
            (view as? com.google.android.material.card.MaterialCardView)?.let { card ->
                card.setCardBackgroundColor(currentTheme.cardColor)
                card.strokeColor = currentTheme.cardBorderColor
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener {
                    provideHapticFeedback()
                    showEditFuelEntryDialog(entry)
                }
            }
            view.findViewById<TextView>(R.id.tvFuelDate).text = entry.formattedDate
            view.findViewById<TextView>(R.id.tvFuelOdo).text = "Odo: ${formatDecimal(entry.odometerKm)} km • ${formatDecimal(entry.liters)} L"
            val tvCost = view.findViewById<TextView>(R.id.tvFuelCost)
            tvCost.text = String.format(Locale.US, "%s%.0f", appSettings.currentCurrencySymbol, entry.totalCostInr)
            tvCost.setTextColor(currentTheme.accentColor)
            val mileageView = view.findViewById<TextView>(R.id.tvFuelMileage)
            val kmpl = entry.calculatedKmpl
            if (kmpl != null) {
                mileageView.text = "${formatDecimal(kmpl)} km/l"
                mileageView.visibility = View.VISIBLE
            } else {
                mileageView.visibility = View.GONE
            }
            binding.layoutFuelList.addView(view)
        }
    }

    private fun showEditFuelEntryDialog(entry: com.pulsarnav.app.maintenance.FuelEntry) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvInfo = TextView(this).apply {
            text = "Refuel recorded on ${entry.formattedDate}\nTap Save to recalculate running economy or Delete to remove:"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        val odoInput = createThemedEditText(
            hintText = "Odometer (km)",
            initialText = formatDecimal(entry.odometerKm),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val litersInput = createThemedEditText(
            hintText = "Fuel Litres",
            initialText = formatDecimal(entry.liters, 2),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val costInput = createThemedEditText(
            hintText = "Total Cost (${appSettings.currentCurrencySymbol})",
            initialText = String.format(Locale.US, "%.0f", entry.totalCostInr),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        fun addField(label: String, field: View) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(currentTheme.textTertiaryColor)
                textSize = 11f
                setPadding(0, 8, 0, 4)
            }
            layout.addView(tv)
            layout.addView(field)
        }

        addField("Odometer (km):", odoInput)
        addField("Fuel Litres:", litersInput)
        addField("Total Amount Paid (${appSettings.currentCurrencySymbol}):", costInput)

        val checkFullTank = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Full Tank refill (Calculates true mileage)"
            isChecked = entry.isFullTank
            setTextColor(currentTheme.textPrimaryColor)
            buttonTintList = ColorStateList.valueOf(currentTheme.accentColor)
            textSize = 12f
        }
        layout.addView(checkFullTank)

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Refuel Entry")
            .setView(layout)
            .setPositiveButton("Save Changes") { _, _ ->
                val odo = parseDecimal(odoInput.text)
                val lit = parseDecimal(litersInput.text)
                val cost = parseDecimal(costInput.text)
                if (odo != null && odo >= 0 && lit != null && lit > 0 && cost != null && cost >= 0) {
                    fuelLogManager.updateFuelEntry(entry.id, odo, lit, cost, isFullTank = checkFullTank.isChecked)
                    recordSyncTime()
                    updateHeroRangeDisplay()
                    provideHapticFeedback()
                    showFeedback("Refuel entry updated!")
                } else {
                    showFeedback("Please enter valid fuel details", isAlert = true)
                }
            }
            .setNeutralButton("Delete Entry") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Fuel Entry?")
                    .setMessage("Are you sure you want to delete this refuel record (${entry.formattedDate})? This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        fuelLogManager.deleteFuelEntry(entry.id)
                        recordSyncTime()
                        updateHeroRangeDisplay()
                        provideHapticFeedback()
                        showFeedback("Refuel entry deleted")
                    }
                    .setNegativeButton("Keep Entry", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchEmergencyHospitals() {
        provideHapticFeedback()
        val uri = Uri.parse("geo:0,0?q=hospitals+emergency+near+me")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            startActivity(mapIntent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/hospitals+emergency+near+me")))
            } catch (_: Exception) {
                showFeedback("Could not open Maps for nearest hospitals", isAlert = true)
            }
        }
    }

    private fun showEmergencyAmbulanceDialog() {
        provideHapticFeedback()
        showEmergencyQuickActionsSheet()
    }

    private fun showEmergencyMedicalDialog() {
        provideHapticFeedback()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val bloodGroups = arrayOf("O+ Positive", "O- Negative", "A+ Positive", "A- Negative", "B+ Positive", "B- Negative", "AB+ Positive", "AB- Negative")
        var selectedBloodGroup = appSettings.riderBloodGroup
        val bloodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, bloodGroups)
            val idx = bloodGroups.indexOfFirst { it.equals(selectedBloodGroup, ignoreCase = true) }
            if (idx >= 0) setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedBloodGroup = bloodGroups[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val iceNameInput = createThemedEditText(
            hintText = "ICE Contact Name / Relation",
            initialText = appSettings.emergencyContactName
        )
        val icePhoneInput = createThemedEditText(
            hintText = "ICE Phone Number",
            initialText = appSettings.emergencyContactPhone,
            inputTypeFlags = android.text.InputType.TYPE_CLASS_PHONE
        )
        val notesInput = createThemedEditText(
            hintText = "Medical Allergies / Conditions",
            initialText = appSettings.medicalNotes
        ).apply {
            minLines = 2
        }

        fun addField(label: String, field: View) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(currentTheme.textTertiaryColor)
                textSize = 11f
                setPadding(0, 8, 0, 4)
            }
            layout.addView(tv)
            layout.addView(field)
        }

        addField("Rider Blood Group:", bloodSpinner)
        addField("Emergency Contact Person (ICE):", iceNameInput)
        addField("Emergency Contact Phone:", icePhoneInput)
        addField("Medical Allergies / Emergency Notes:", notesInput)

        val scrollView = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Medical & Emergency Profile")
            .setView(scrollView)
            .setPositiveButton("Save Profile") { _, _ ->
                appSettings.riderBloodGroup = selectedBloodGroup
                appSettings.emergencyContactName = iceNameInput.text.toString().trim().ifBlank { "Family / ICE" }
                appSettings.emergencyContactPhone = icePhoneInput.text.toString().trim().ifBlank { "112" }
                appSettings.medicalNotes = notesInput.text.toString().trim().ifBlank { "No known allergies" }
                updateEmergencyHomeCard()
                recordSyncTime()
                provideHapticFeedback()
                showFeedback("Emergency medical profile saved!")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateEmergencyHomeCard() {
        val blood = if (appSettings.riderBloodGroup.isNotBlank() && appSettings.riderBloodGroup != "Not Specified") "${appSettings.riderBloodGroup}" else "Blood Group --"
        binding.tvBloodGroupBadge.text = blood
        val icePhone = appSettings.emergencyContactPhone
        binding.tvEmergencyContactName.text = if (icePhone.isNotBlank()) "ICE: $icePhone" else "ICE: Not Configured"
        val iceName = appSettings.emergencyContactName
        val med = appSettings.medicalNotes
        binding.tvEmergencyMedicalNotes.text = when {
            iceName.isNotBlank() && med.isNotBlank() -> "$iceName • $med"
            iceName.isNotBlank() -> iceName
            med.isNotBlank() -> med
            else -> "Tap to configure ICE emergency contact & info"
        }
    }

    private fun getArrowForManeuver(m: Maneuver): String {
        return when (m) {
            Maneuver.TURN_LEFT, Maneuver.TURN_SHARP_LEFT, Maneuver.KEEP_LEFT, Maneuver.FORK_LEFT, Maneuver.RAMP_LEFT -> "⬅"
            Maneuver.TURN_RIGHT, Maneuver.TURN_SHARP_RIGHT, Maneuver.KEEP_RIGHT, Maneuver.FORK_RIGHT, Maneuver.RAMP_RIGHT -> "➡"
            Maneuver.TURN_SLIGHT_LEFT -> "↖"
            Maneuver.TURN_SLIGHT_RIGHT -> "↗"
            Maneuver.U_TURN_LEFT, Maneuver.U_TURN_RIGHT -> "↩"
            Maneuver.ROUNDABOUT_LEFT -> "↺"
            Maneuver.ROUNDABOUT_RIGHT -> "↻"
            Maneuver.DESTINATION_REACHED -> "ARRIVED"
            else -> "⬆"
        }
    }
}
