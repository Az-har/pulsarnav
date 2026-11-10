package com.pulsarnav.app.ui

import android.app.DatePickerDialog
import android.view.WindowManager
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import com.pulsarnav.app.data.BikeModelProfile
import com.pulsarnav.app.data.CountryProfile
import android.view.LayoutInflater
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
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
import com.pulsarnav.app.location.ParkingManager
import com.pulsarnav.app.maintenance.FuelLogManager
import com.pulsarnav.app.maintenance.MaintenanceManager
import com.pulsarnav.app.media.MediaControlManager
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.service.BikeBleForegroundService
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import com.pulsarnav.app.telemetry.PhoneTelemetryManager
import com.pulsarnav.app.telephony.CallManager
import com.pulsarnav.app.tracking.RideTracker
import com.pulsarnav.app.tracking.Trip
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class CockpitTheme(
    val id: String,
    val name: String,
    val displayName: String,
    val isLight: Boolean,
    val backgroundColor: Int,
    val surfaceColor: Int,
    val cardColor: Int,
    val cardBorderColor: Int,
    val textPrimaryColor: Int,
    val textSecondaryColor: Int,
    val textTertiaryColor: Int,
    val primaryColor: Int,
    val onPrimaryColor: Int,
    val accentColor: Int,
    val subtleColor: Int,
    val borderColor: Int
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BajajBleManager
    private lateinit var callManager: CallManager
    private lateinit var telemetryManager: PhoneTelemetryManager
    private lateinit var appSettings: AppSettings
    private lateinit var parkingManager: ParkingManager
    private lateinit var rideTracker: RideTracker
    private lateinit var maintenanceManager: MaintenanceManager
    private lateinit var fuelLogManager: FuelLogManager
    private lateinit var mediaControlManager: MediaControlManager

    private var currentTheme: CockpitTheme = getThemeDefinition(AppSettings.THEME_WINE_RED, isLightMode = false)

    private var pendingDocType: String? = null
    private var activeGloveboxSheet: BottomSheetDialog? = null
    private var activeGloveboxView: View? = null
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
        if (btGranted) {
            bleManager.startScan()
        } else {
            showFeedback("Bluetooth permissions are required to connect to your bike", isAlert = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appSettings = AppSettings.getInstance(this)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (appSettings.isDarkMode) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Managers
        bleManager = BajajBleManager.getInstance(this)
        callManager = CallManager.getInstance(this)
        telemetryManager = PhoneTelemetryManager.getInstance(this)
        parkingManager = ParkingManager.getInstance(this)
        rideTracker = RideTracker.getInstance(this)
        maintenanceManager = MaintenanceManager.getInstance(this)
        fuelLogManager = FuelLogManager.getInstance(this)
        mediaControlManager = MediaControlManager.getInstance(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            callManager.initialize()
            telemetryManager.initialize()
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
        setupSupportAndAccountDialogs()
        setupFeatureToggles()
        setupThemeSelector()
        setupQuickNav()

        // Apply saved theme
        applyTheme(appSettings.appTheme)

        // Restore active tab (handles theme recreation / configuration changes)
        val savedTab = savedInstanceState?.getInt("active_tab_id")
            ?: if (appSettings.lastActiveTab != 0) appSettings.lastActiveTab else binding.bottomNav.selectedItemId
        restoreActiveTab(savedTab)

        // Observers
        observeBleState()
        observeNavigation()
        observeTelephonyAndAlerts()
        observeTelemetry()
        observeMaintenance()
        observeFuelLog()

        // Handle quick action intents
        intent?.let { handleIntentActions(it) }

        // Initial scan if permissions are ready
        checkAndStartScan(silent = true)

        // Show introduction / vehicle setup on first launch if not configured yet
        updateQuickNavButtons()
        updateFuelPriceUi()
        updateBikeIssuesSummary()
        updateNavAppSelectionUi()
        binding.tvAppVersionTitle.text = "PulsarNav Companion v${com.pulsarnav.app.BuildConfig.VERSION_NAME}"

        binding.root.post {
            if (!appSettings.isSetupCompleted) {
                showVehicleSetupBottomSheet()
            }
        }
    }

    private val hardwareStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateHardwareReadinessUi()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAction(LocationManager.MODE_CHANGED_ACTION)
            }
        }
        registerReceiver(hardwareStateReceiver, filter)
        updateHardwareReadinessUi()
    }

    override fun onStop() {
        try {
            unregisterReceiver(hardwareStateReceiver)
        } catch (_: Exception) {}
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationAccessUi()
        updateHardwareReadinessUi()
        updateBikeInfoDisplay()
        updateQuickNavButtons()
        updateFuelPriceUi()
        updateBikeIssuesSummary()
        updateNavAppSelectionUi()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntentActions(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.scrollFuel.visibility == View.VISIBLE ||
            binding.scrollSupport.visibility == View.VISIBLE ||
            binding.scrollSettings.visibility == View.VISIBLE
        ) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun handleIntentActions(intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            "com.pulsarnav.action.NAV_HOME" -> {
                val addr = appSettings.homeAddress
                launchNavigationTo(if (addr.isNotBlank() && addr != "Home") addr else "Home")
            }
            "com.pulsarnav.action.NAV_WORK" -> {
                val addr = appSettings.workAddress
                launchNavigationTo(if (addr.isNotBlank() && addr != "Work") addr else "Work")
            }
            "com.pulsarnav.action.FIND_BIKE" -> {
                showFeedback("Bike locator feature removed")
            }
            "com.pulsarnav.action.LOG_REFUEL" -> {
                showAddFuelDialog()
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    showExternalVaultImportConfirmation(uri)
                }
            }
        }
        // Consume intent so it does not re-trigger on activity recreation (e.g. rotation, theme changes)
        intent.action = null
        intent.data = null
        setIntent(Intent(this, MainActivity::class.java))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val activeTabId = when {
            binding.scrollFuel.visibility == View.VISIBLE -> R.id.nav_fuel
            binding.scrollSupport.visibility == View.VISIBLE -> R.id.nav_support
            binding.scrollSettings.visibility == View.VISIBLE -> R.id.nav_account
            else -> R.id.nav_home
        }
        outState.putInt("active_tab_id", activeTabId)
        if (::appSettings.isInitialized) {
            appSettings.lastActiveTab = activeTabId
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val savedTab = savedInstanceState.getInt("active_tab_id", 0)
        if (savedTab != 0) {
            restoreActiveTab(savedTab)
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            provideHapticFeedback()
            if (::appSettings.isInitialized) {
                appSettings.lastActiveTab = item.itemId
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

    private fun restoreActiveTab(tabId: Int) {
        when (tabId) {
            R.id.nav_fuel -> {
                binding.bottomNav.selectedItemId = R.id.nav_fuel
                switchTab(binding.scrollFuel)
            }
            R.id.nav_support -> {
                binding.bottomNav.selectedItemId = R.id.nav_support
                switchTab(binding.scrollSupport)
            }
            R.id.nav_account -> {
                binding.bottomNav.selectedItemId = R.id.nav_account
                switchTab(binding.scrollSettings)
            }
            else -> {
                binding.bottomNav.selectedItemId = R.id.nav_home
                switchTab(binding.scrollCockpit)
            }
        }
    }

    private fun switchTab(targetView: View): Boolean {
        binding.scrollCockpit.visibility = View.GONE
        binding.scrollFuel.visibility = View.GONE
        binding.scrollSupport.visibility = View.GONE
        binding.scrollSettings.visibility = View.GONE

        targetView.visibility = View.VISIBLE
        return true
    }

    private fun getPillChipDrawable(active: Boolean, theme: CockpitTheme): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(20).toFloat()
            if (active) {
                setColor(theme.subtleColor)
                setStroke(dpToPx(2), theme.accentColor)
            } else {
                setColor(theme.cardColor)
                setStroke(dpToPx(1), theme.cardBorderColor)
            }
        }
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

        if (dteKm <= 0 && fuelPercent <= 0) {
            binding.tvProjectedRange.text = "--"
            binding.tvDteSubtext.text = "Tap to sync"
            binding.tvFuelLevelText.text = "0% Tank Level • 0.0L / ${"%.1f".format(tankMax)}L"
            binding.progressFuelLevel.progress = 0
        } else {
            binding.tvProjectedRange.text = if (dteKm > 0) dteKm.toString() else "--"
            binding.tvDteSubtext.text = "~${"%.1f".format(fuelLiters)}L in tank"
            binding.tvFuelLevelText.text = "${fuelPercent}% Tank Level • ${"%.1f".format(fuelLiters)}L / ${"%.1f".format(tankMax)}L"
            binding.progressFuelLevel.progress = fuelPercent
        }

        // Unified Odometer & Mileage metrics across Home and Account tabs
        val totalOdo = maintenanceManager.status.value.totalOdometerKm
        binding.tvHomeOdometer.text = "%.1f".format(totalOdo)
        binding.tvAccountOdometer.text = "%.1f km".format(totalOdo)

        val fuelStats = fuelLogManager.stats.value
        if (fuelStats.averageKmpl > 0.0) {
            binding.tvHomeMileage.text = "%.1f km/l avg".format(fuelStats.averageKmpl)
        } else {
            binding.tvHomeMileage.text = "-- km/l"
        }

        binding.tvTankSpecs.text = "${tankMax.toInt()} L FUEL TANK"
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
        binding.tvTankSpecs.text = "${model.tankCapacityLiters.toInt()} L FUEL TANK"
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
        binding.tvDocumentsBadge.text = if (uploadedCount > 0) "$uploadedCount Uploaded" else "0 Uploaded"
    }

    private fun updateBikeInvestmentDisplay() {
        val currency = appSettings.currentCurrencySymbol
        val fuelSpent = fuelLogManager.stats.value.totalSpentInr
        val serviceSpent = maintenanceManager.getTotalServiceCost()
        val totalSpent = fuelSpent + serviceSpent

        binding.tvTotalInvestment.text = "%s%.0f".format(currency, totalSpent)
        binding.tvInvestmentSubtext.text = "%s%.0f Fuel • %s%.0f Service".format(currency, fuelSpent, currency, serviceSpent)
    }

    private fun updateServiceHistorySummary() {
        val records = maintenanceManager.getServiceHistory()
        val totalServiceSpent = maintenanceManager.getTotalServiceCost()
        val currency = appSettings.currentCurrencySymbol
        binding.tvServiceHistorySummary.text = if (records.isNotEmpty()) {
            "%d service(s) • %s%.0f invested • Tap to view".format(records.size, currency, totalServiceSpent)
        } else {
            "0 services recorded • Tap to log service"
        }
        binding.tvSupportTotalInvested.text = "%s%.0f".format(currency, totalServiceSpent)
        val maint = maintenanceManager.status.value
        binding.tvSupportChainRemaining.text = "%.0f km".format(maint.chainLubeRemainingKm.coerceAtLeast(0.0))
        binding.tvSupportServiceRemaining.text = if (maint.isServiceOverdue) "OVERDUE" else "%.0f km".format(maint.serviceRemainingKm.coerceAtLeast(0.0))

        updateBikeInvestmentDisplay()
    }

    private fun showClusterRecalibrateBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_recalibrate_cluster, null)
        dialog.setContentView(sheetView)

        val etDte = sheetView.findViewById<EditText>(R.id.etRecalibrateDte)
        val etOdo = sheetView.findViewById<EditText>(R.id.etRecalibrateOdo)
        val etFuel = sheetView.findViewById<EditText>(R.id.etRecalibrateFuelLiters)
        listOfNotNull(etDte, etOdo, etFuel).forEach { applyThemeToEditText(it) }
        val chipReserve = sheetView.findViewById<MaterialButton>(R.id.chipPresetReserve)
        val chipQuarter = sheetView.findViewById<MaterialButton>(R.id.chipPresetQuarter)
        val chipHalf = sheetView.findViewById<MaterialButton>(R.id.chipPresetHalf)
        val chipFull = sheetView.findViewById<MaterialButton>(R.id.chipPresetFull)
        val btnSave = sheetView.findViewById<MaterialButton>(R.id.btnSaveRecalibration)

        val currentOdo = maintenanceManager.status.value.totalOdometerKm
        val currentDte = appSettings.projectedDteKm
        if (currentDte > 0) {
            etDte?.setText(currentDte.toString())
        } else {
            etDte?.setText("")
        }
        etOdo?.setText(String.format(Locale.US, "%.1f", currentOdo))
        etFuel?.setText(String.format(Locale.US, "%.1f", appSettings.fuelLevelLiters))

        btnSave?.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
        btnSave?.setTextColor(currentTheme.onPrimaryColor)

        val updatePresetChips = { selectedLiters: Float ->
            val presets = listOf(
                chipReserve to 2.4f,
                chipQuarter to 3.0f,
                chipHalf to 6.0f,
                chipFull to 12.0f
            )
            for ((chip, lit) in presets) {
                if (chip != null) {
                    val isSelected = Math.abs(selectedLiters - lit) < 0.15f
                    if (isSelected) {
                        chip.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                        chip.strokeColor = ColorStateList.valueOf(currentTheme.accentColor)
                        chip.setTextColor(currentTheme.accentColor)
                    } else {
                        chip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_card)
                        chip.strokeColor = ContextCompat.getColorStateList(this, R.color.surface_border)
                        chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    }
                }
            }
        }

        updatePresetChips(appSettings.fuelLevelLiters)

        val autoFillDteIfEmpty = { suggestedDte: Int ->
            val current = etDte?.text?.toString()?.trim().orEmpty()
            if (current.isEmpty() || current == "0") {
                etDte?.setText(suggestedDte.toString())
            }
        }

        chipReserve?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText("2.4")
            autoFillDteIfEmpty(80)
            updatePresetChips(2.4f)
        }
        chipQuarter?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText("3.0")
            autoFillDteIfEmpty(105)
            updatePresetChips(3.0f)
        }
        chipHalf?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText("6.0")
            autoFillDteIfEmpty(210)
            updatePresetChips(6.0f)
        }
        chipFull?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText("12.0")
            autoFillDteIfEmpty(420)
            updatePresetChips(12.0f)
        }

        btnSave?.setOnClickListener {
            provideHapticFeedback()
            val enteredDte = etDte?.text?.toString()?.toIntOrNull() ?: appSettings.projectedDteKm
            val enteredOdo = etOdo?.text?.toString()?.toDoubleOrNull() ?: currentOdo
            val enteredLiters = (etFuel?.text?.toString()?.toFloatOrNull() ?: appSettings.fuelLevelLiters).coerceIn(0.0f, 12.0f)
            val percent = ((enteredLiters / 12.0f) * 100).toInt().coerceIn(0, 100)

            appSettings.projectedDteKm = enteredDte
            appSettings.fuelLevelPercent = percent
            appSettings.fuelLevelLiters = enteredLiters
            maintenanceManager.setOdometer(enteredOdo)
            recordSyncTime()
            updateHeroRangeDisplay()

            dialog.dismiss()
            showFeedback("Cluster synced: ${enteredDte} km DTE • ${String.format(Locale.US, "%.1f", enteredOdo)} km ODO")
        }

        dialog.show()
    }

    private fun setupListeners() {
        // BLE Scan / Disconnect Button
        binding.btnScan.setOnClickListener {
            provideHapticFeedback()
            when (bleManager.connectionState.value) {
                is BleConnectionState.Connected -> bleManager.disconnect()
                is BleConnectionState.Scanning -> bleManager.stopScan()
                else -> checkAndStartScan(silent = false)
            }
        }

        // Open Google Maps
        binding.btnOpenMaps.setOnClickListener {
            provideHapticFeedback()
            launchGoogleMapsRide()
        }

        // Notification Access Prompts
        binding.btnEnableNotificationAccess.setOnClickListener {
            provideHapticFeedback()
            openNotificationListenerSettings()
        }
        binding.rowNotificationAccess.setOnClickListener {
            provideHapticFeedback()
            openNotificationListenerSettings()
        }

        // Search Bar click
        binding.layoutSearchBar.setOnClickListener {
            provideHapticFeedback()
            launchGoogleMapsRide()
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
            callManager.simulateIncomingCall("Dad (Mobile)", "+91 98765 43210")
            showFeedback("Simulating Incoming Call to Cluster")
        }

        binding.btnSimulateWhatsApp.setOnClickListener {
            provideHapticFeedback()
            val sampleSender = "Rahul Sharma"
            bleManager.sendWhatsAppAlert(sampleSender)
            binding.tvWhatsAppAlertBanner.text = "WhatsApp Alert: $sampleSender (Showing 6s)"
            binding.tvWhatsAppAlertBanner.setTextColor(currentTheme.accentColor)
            showFeedback("WhatsApp alert sent to cluster (6s preview)")
        }

        binding.btnSimulateMissedCall.setOnClickListener {
            provideHapticFeedback()
            callManager.simulateEndCall()
            showFeedback("Call Ended / Idle")
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
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("waze://?navigate=yes")).apply {
                    setPackage("com.waze")
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?navigate=yes")))
                    return
                } catch (_: Exception) {}
            }
        }
        val mode = if (appSettings.isTwoWheelerMode) "l" else "d"
        try {
            val gmmIntentUri = Uri.parse("google.navigation:q=&mode=$mode")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                setPackage("com.google.android.apps.maps")
            }
            startActivity(mapIntent)
        } catch (e: Exception) {
            try {
                val travelMode = if (appSettings.isTwoWheelerMode) "two_wheeler" else "driving"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com?travelmode=$travelMode")))
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
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
        val records = maintenanceManager.getServiceHistory()
        val odo = maintenanceManager.status.value.totalOdometerKm
        val currency = appSettings.currentCurrencySymbol
        val totalServiceSpent = maintenanceManager.getTotalServiceCost()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val tvSummary = TextView(this).apply {
            text = "Total Maintenance Investment: %s%.0f (%d services)\nNext Due at %.0f km • Tap to edit or delete:".format(
                currency,
                totalServiceSpent,
                records.size,
                odo + maintenanceManager.status.value.serviceRemainingKm
            )
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvSummary)

        if (records.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "No service records logged yet.\nTap 'Add Service Record' below to track maintenance."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
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
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
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
                text = "${rec.date} • %.1f km".format(rec.odoKm)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
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
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        textSize = 12f
                    }
                    val tvItemCost = TextView(this).apply {
                        text = if (item.cost > 0.0) "%s%.0f".format(currency, item.cost) else "--"
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
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
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
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.wine_red_light))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    provideHapticFeedback()
                    maintenanceManager.deleteServiceRecord(rec.id)
                    recordSyncTime()
                    updateServiceHistorySummary()
                    updateBikeInvestmentDisplay()
                    showFeedback("Record deleted")
                    showServiceHistoryDialog() // refresh dialog
                }
            }
            rowActions.addView(btnDelete)

            cardContent.addView(rowActions)
            card.addView(cardContent)
            layout.addView(card)
        }

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Service History & Records")
            .setView(scrollView)
            .setPositiveButton("Add Service Record") { _, _ ->
                provideHapticFeedback()
                showAddOrEditServiceRecordDialog(null)
            }
            .setNegativeButton("Close", null)
            .show()
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
            initialText = if (isEdit) "%.1f".format(existing!!.odoKm) else "%.1f".format(currentOdo),
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

        fun recalculateTotalCost() {
            val sum = rowHolders.sumOf {
                it.etItemCost.text.toString().toDoubleOrNull() ?: 0.0
            }
            if (sum > 0.0 || rowHolders.isNotEmpty()) {
                etCost.setText("%s%.0f".format(appSettings.currentCurrencySymbol, sum))
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
                initialText = if (itemCost > 0.0) "%.0f".format(itemCost) else "",
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
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.wine_red_light))
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
                val odoVal = etOdo.text.toString().toDoubleOrNull() ?: currentOdo

                val parsedItems = rowHolders.mapNotNull {
                    val name = it.etName.text.toString().trim()
                    val c = it.etItemCost.text.toString().toDoubleOrNull() ?: 0.0
                    if (name.isNotBlank()) com.pulsarnav.app.maintenance.ServiceItem(name, c) else null
                }
                val costStr = etCost.text.toString().trim().ifBlank {
                    val sum = parsedItems.sumOf { it.cost }
                    "%s%.0f".format(appSettings.currentCurrencySymbol, sum)
                }
                val extraNotes = etNotes.text.toString().trim()

                val rec = existing?.copy(
                    title = if (title.isNotBlank()) title else "Periodic Service",
                    date = if (date.isNotBlank()) date else "Today",
                    odoKm = odoVal,
                    workshop = "Self / Workshop",
                    cost = costStr,
                    notes = extraNotes,
                    items = parsedItems
                ) ?: com.pulsarnav.app.maintenance.ServiceRecord(
                    title = if (title.isNotBlank()) title else "Periodic Service",
                    date = if (date.isNotBlank()) date else "Today",
                    odoKm = odoVal,
                    workshop = "Self / Workshop",
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
        view.findViewById<TextView>(R.id.badgeInsuranceStatus)?.apply {
            if (hasInsFile) {
                text = "ACTIVE"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.status_connected_subtle))
            } else {
                text = "NO FILE"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.surface_elevated))
            }
        }
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
        view.findViewById<TextView>(R.id.badgePucStatus)?.apply {
            if (hasPucFile) {
                text = "VALID"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.status_connected_subtle))
            } else {
                text = "NO FILE"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.surface_elevated))
            }
        }
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

        val etNum = createThemedEditText(initialText = numberOrPolicy(docType))
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
                        if (!newExp.isNullOrBlank()) appSettings.docInsuranceExpiry = newExp
                    }
                    "puc" -> {
                        if (newNum.isNotBlank()) appSettings.docPucCert = newNum
                        if (!newExp.isNullOrBlank()) appSettings.docPucExpiry = newExp
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
        try {
            val contentResolver = contentResolver
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val extension = if (mime.contains("pdf")) "pdf" else "jpg"
            val docsDir = File(filesDir, "documents")
            if (!docsDir.exists()) docsDir.mkdirs()
            val targetFile = File(docsDir, "${docType}_${System.currentTimeMillis()}.$extension")

            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            when (docType) {
                "rc" -> appSettings.docRcPath = targetFile.absolutePath
                "insurance" -> appSettings.docInsurancePath = targetFile.absolutePath
                "puc" -> appSettings.docPucPath = targetFile.absolutePath
            }

            recordSyncTime()
            updateDocumentsSummary()
            activeGloveboxView?.let { v -> activeGloveboxSheet?.let { s -> bindGloveboxData(v, s) } }
            provideHapticFeedback()
            showFeedback("Document uploaded successfully!")
        } catch (e: Exception) {
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
            startActivity(intent)
        } catch (e: Exception) {
            showFeedback("Could not open document viewer: ${e.message}", isAlert = true)
        }
    }

    private fun showRsaDialog() {
        val options = arrayOf(
            "Bajaj 24x7 RSA Helpline (1800 233 2453)",
            "National Highway Assistance (1033)",
            "Emergency Services (112)"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Emergency Roadside Assistance")
            .setItems(options) { _, which ->
                provideHapticFeedback()
                val phone = when (which) {
                    0 -> "18002332453"
                    1 -> "1033"
                    else -> "112"
                }
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
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nikhilmaity00/pulsarnav/issues")))
            }
            .setNegativeButton("Close", null)
            .show()
    }


    private fun setupFeatureToggles() {
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
    }

    private fun getThemeDefinition(themeKey: String, isLightMode: Boolean? = null): CockpitTheme {
        val isLight = isLightMode ?: if (::appSettings.isInitialized) !appSettings.isDarkMode else false
        return if (isLight) {
            when (themeKey) {
                AppSettings.THEME_AMOLED -> CockpitTheme(
                    AppSettings.THEME_AMOLED, "Pearl Minimal", "Pearl Minimal",
                    isLight = true,
                    backgroundColor = Color.parseColor("#F4F5F8"),
                    surfaceColor = Color.parseColor("#FFFFFF"),
                    cardColor = Color.parseColor("#FFFFFF"),
                    cardBorderColor = Color.parseColor("#E2E8F0"),
                    textPrimaryColor = Color.parseColor("#0F172A"),
                    textSecondaryColor = Color.parseColor("#475569"),
                    textTertiaryColor = Color.parseColor("#64748B"),
                    primaryColor = Color.parseColor("#0F172A"),
                    onPrimaryColor = Color.WHITE,
                    accentColor = Color.parseColor("#0F172A"),
                    subtleColor = Color.parseColor("#F1F5F9"),
                    borderColor = Color.parseColor("#CBD5E1")
                )
                AppSettings.THEME_STEALTH -> CockpitTheme(
                    AppSettings.THEME_STEALTH, "Stealth Platinum", "Stealth Platinum",
                    isLight = true,
                    backgroundColor = Color.parseColor("#F4F5F8"),
                    surfaceColor = Color.parseColor("#FFFFFF"),
                    cardColor = Color.parseColor("#FFFFFF"),
                    cardBorderColor = Color.parseColor("#E2E8F0"),
                    textPrimaryColor = Color.parseColor("#0F172A"),
                    textSecondaryColor = Color.parseColor("#475569"),
                    textTertiaryColor = Color.parseColor("#64748B"),
                    primaryColor = Color.parseColor("#334155"),
                    onPrimaryColor = Color.WHITE,
                    accentColor = Color.parseColor("#334155"),
                    subtleColor = Color.parseColor("#F1F5F9"),
                    borderColor = Color.parseColor("#CBD5E1")
                )
                AppSettings.THEME_AMBER -> CockpitTheme(
                    AppSettings.THEME_AMBER, "Neon Amber", "Neon Amber",
                    isLight = true,
                    backgroundColor = Color.parseColor("#F4F5F8"),
                    surfaceColor = Color.parseColor("#FFFFFF"),
                    cardColor = Color.parseColor("#FFFFFF"),
                    cardBorderColor = Color.parseColor("#E2E8F0"),
                    textPrimaryColor = Color.parseColor("#0F172A"),
                    textSecondaryColor = Color.parseColor("#475569"),
                    textTertiaryColor = Color.parseColor("#64748B"),
                    primaryColor = Color.parseColor("#D97706"),
                    onPrimaryColor = Color.WHITE,
                    accentColor = Color.parseColor("#B45309"),
                    subtleColor = Color.parseColor("#FEF3C7"),
                    borderColor = Color.parseColor("#FDE68A")
                )
                AppSettings.THEME_MINT -> CockpitTheme(
                    AppSettings.THEME_MINT, "Hyper Mint", "Hyper Mint",
                    isLight = true,
                    backgroundColor = Color.parseColor("#F4F5F8"),
                    surfaceColor = Color.parseColor("#FFFFFF"),
                    cardColor = Color.parseColor("#FFFFFF"),
                    cardBorderColor = Color.parseColor("#E2E8F0"),
                    textPrimaryColor = Color.parseColor("#0F172A"),
                    textSecondaryColor = Color.parseColor("#475569"),
                    textTertiaryColor = Color.parseColor("#64748B"),
                    primaryColor = Color.parseColor("#059669"),
                    onPrimaryColor = Color.WHITE,
                    accentColor = Color.parseColor("#047857"),
                    subtleColor = Color.parseColor("#ECFDF5"),
                    borderColor = Color.parseColor("#A7F3D0")
                )
                AppSettings.THEME_BLUE -> CockpitTheme(
                    AppSettings.THEME_BLUE, "Liquid Blue", "Liquid Blue",
                    isLight = true,
                    backgroundColor = Color.parseColor("#F4F5F8"),
                    surfaceColor = Color.parseColor("#FFFFFF"),
                    cardColor = Color.parseColor("#FFFFFF"),
                    cardBorderColor = Color.parseColor("#E2E8F0"),
                    textPrimaryColor = Color.parseColor("#0F172A"),
                    textSecondaryColor = Color.parseColor("#475569"),
                    textTertiaryColor = Color.parseColor("#64748B"),
                    primaryColor = Color.parseColor("#0284C7"),
                    onPrimaryColor = Color.WHITE,
                    accentColor = Color.parseColor("#0369A1"),
                    subtleColor = Color.parseColor("#F0F9FF"),
                    borderColor = Color.parseColor("#BAE6FD")
                )
                AppSettings.THEME_CUSTOM -> {
                    val r = if (::appSettings.isInitialized) appSettings.customAccentRed else 158
                    val g = if (::appSettings.isInitialized) appSettings.customAccentGreen else 27
                    val b = if (::appSettings.isInitialized) appSettings.customAccentBlue else 50
                    val customColor = Color.rgb(r, g, b)
                    val lum = 0.299 * r + 0.587 * g + 0.114 * b
                    val onPrimary = if (lum > 150) Color.BLACK else Color.WHITE
                    val subtle = Color.rgb(
                        (255 * 0.90 + r * 0.10).toInt().coerceIn(0, 255),
                        (255 * 0.90 + g * 0.10).toInt().coerceIn(0, 255),
                        (255 * 0.90 + b * 0.10).toInt().coerceIn(0, 255)
                    )
                    val border = Color.rgb(
                        (255 * 0.75 + r * 0.25).toInt().coerceIn(0, 255),
                        (255 * 0.75 + g * 0.25).toInt().coerceIn(0, 255),
                        (255 * 0.75 + b * 0.25).toInt().coerceIn(0, 255)
                    )
                    CockpitTheme(
                        AppSettings.THEME_CUSTOM, "Custom RGB", "Custom RGB",
                        isLight = true,
                        backgroundColor = Color.parseColor("#F4F5F8"),
                        surfaceColor = Color.parseColor("#FFFFFF"),
                        cardColor = Color.parseColor("#FFFFFF"),
                        cardBorderColor = Color.parseColor("#E2E8F0"),
                        textPrimaryColor = Color.parseColor("#0F172A"),
                        textSecondaryColor = Color.parseColor("#475569"),
                        textTertiaryColor = Color.parseColor("#64748B"),
                        primaryColor = customColor,
                        onPrimaryColor = onPrimary,
                        accentColor = customColor,
                        subtleColor = subtle,
                        borderColor = border
                    )
                }
                else -> CockpitTheme(
                    AppSettings.THEME_WINE_RED, "Wine Red", "Wine Red",
                    isLight = true,
                    backgroundColor = Color.parseColor("#F4F5F8"),
                    surfaceColor = Color.parseColor("#FFFFFF"),
                    cardColor = Color.parseColor("#FFFFFF"),
                    cardBorderColor = Color.parseColor("#E2E8F0"),
                    textPrimaryColor = Color.parseColor("#0F172A"),
                    textSecondaryColor = Color.parseColor("#475569"),
                    textTertiaryColor = Color.parseColor("#64748B"),
                    primaryColor = Color.parseColor("#9E1B32"),
                    onPrimaryColor = Color.WHITE,
                    accentColor = Color.parseColor("#9E1B32"),
                    subtleColor = Color.parseColor("#FDF2F4"),
                    borderColor = Color.parseColor("#F8D7DA")
                )
            }
        } else {
            when (themeKey) {
                AppSettings.THEME_AMOLED -> CockpitTheme(
                    AppSettings.THEME_AMOLED, "AMOLED Black", "AMOLED Black",
                    isLight = false,
                    backgroundColor = Color.parseColor("#000000"),
                    surfaceColor = Color.parseColor("#000000"),
                    cardColor = Color.parseColor("#0B0B0E"),
                    cardBorderColor = Color.parseColor("#262630"),
                    textPrimaryColor = Color.parseColor("#F8FAFC"),
                    textSecondaryColor = Color.parseColor("#A5A4AF"),
                    textTertiaryColor = Color.parseColor("#72707D"),
                    primaryColor = Color.parseColor("#FFFFFF"),
                    onPrimaryColor = Color.BLACK,
                    accentColor = Color.parseColor("#FFFFFF"),
                    subtleColor = Color.parseColor("#141418"),
                    borderColor = Color.parseColor("#383844")
                )
                AppSettings.THEME_STEALTH -> CockpitTheme(
                    AppSettings.THEME_STEALTH, "Stealth Mono", "Stealth Mono",
                    isLight = false,
                    backgroundColor = Color.parseColor("#131215"),
                    surfaceColor = Color.parseColor("#1C1B20"),
                    cardColor = Color.parseColor("#222128"),
                    cardBorderColor = Color.parseColor("#32303B"),
                    textPrimaryColor = Color.parseColor("#F7F7F8"),
                    textSecondaryColor = Color.parseColor("#A5A4AF"),
                    textTertiaryColor = Color.parseColor("#72707D"),
                    primaryColor = Color.parseColor("#475569"),
                    onPrimaryColor = Color.WHITE,
                    accentColor = Color.parseColor("#94A3B8"),
                    subtleColor = Color.parseColor("#1E293B"),
                    borderColor = Color.parseColor("#334155")
                )
                AppSettings.THEME_AMBER -> CockpitTheme(
                    AppSettings.THEME_AMBER, "Neon Amber", "Neon Amber",
                    isLight = false,
                    backgroundColor = Color.parseColor("#131215"),
                    surfaceColor = Color.parseColor("#1C1B20"),
                    cardColor = Color.parseColor("#222128"),
                    cardBorderColor = Color.parseColor("#32303B"),
                    textPrimaryColor = Color.parseColor("#F7F7F8"),
                    textSecondaryColor = Color.parseColor("#A5A4AF"),
                    textTertiaryColor = Color.parseColor("#72707D"),
                    primaryColor = Color.parseColor("#D97706"),
                    onPrimaryColor = Color.WHITE,
                    accentColor = Color.parseColor("#F59E0B"),
                    subtleColor = Color.parseColor("#2E1E08"),
                    borderColor = Color.parseColor("#5C3D10")
                )
                AppSettings.THEME_MINT -> CockpitTheme(
                    AppSettings.THEME_MINT, "Hyper Mint", "Hyper Mint",
                    isLight = false,
                    backgroundColor = Color.parseColor("#131215"),
                    surfaceColor = Color.parseColor("#1C1B20"),
                    cardColor = Color.parseColor("#222128"),
                    cardBorderColor = Color.parseColor("#32303B"),
                    textPrimaryColor = Color.parseColor("#F7F7F8"),
                    textSecondaryColor = Color.parseColor("#A5A4AF"),
                    textTertiaryColor = Color.parseColor("#72707D"),
                    primaryColor = Color.parseColor("#059669"),
                    onPrimaryColor = Color.WHITE,
                    accentColor = Color.parseColor("#10B981"),
                    subtleColor = Color.parseColor("#0D2E21"),
                    borderColor = Color.parseColor("#134E35")
                )
                AppSettings.THEME_BLUE -> CockpitTheme(
                    AppSettings.THEME_BLUE, "Liquid Blue", "Liquid Blue",
                    isLight = false,
                    backgroundColor = Color.parseColor("#131215"),
                    surfaceColor = Color.parseColor("#1C1B20"),
                    cardColor = Color.parseColor("#222128"),
                    cardBorderColor = Color.parseColor("#32303B"),
                    textPrimaryColor = Color.parseColor("#F7F7F8"),
                    textSecondaryColor = Color.parseColor("#A5A4AF"),
                    textTertiaryColor = Color.parseColor("#72707D"),
                    primaryColor = Color.parseColor("#0284C7"),
                    onPrimaryColor = Color.WHITE,
                    accentColor = Color.parseColor("#38BDF8"),
                    subtleColor = Color.parseColor("#0C253B"),
                    borderColor = Color.parseColor("#144566")
                )
                AppSettings.THEME_CUSTOM -> {
                    val r = if (::appSettings.isInitialized) appSettings.customAccentRed else 158
                    val g = if (::appSettings.isInitialized) appSettings.customAccentGreen else 27
                    val b = if (::appSettings.isInitialized) appSettings.customAccentBlue else 50
                    val customColor = Color.rgb(r, g, b)
                    val lum = 0.299 * r + 0.587 * g + 0.114 * b
                    val onPrimary = if (lum > 150) Color.BLACK else Color.WHITE
                    val subtle = Color.rgb(
                        (19 * 0.80 + r * 0.20).toInt().coerceIn(0, 255),
                        (18 * 0.80 + g * 0.20).toInt().coerceIn(0, 255),
                        (21 * 0.80 + b * 0.20).toInt().coerceIn(0, 255)
                    )
                    val border = Color.rgb(
                        (19 * 0.60 + r * 0.40).toInt().coerceIn(0, 255),
                        (18 * 0.60 + g * 0.40).toInt().coerceIn(0, 255),
                        (21 * 0.60 + b * 0.40).toInt().coerceIn(0, 255)
                    )
                    CockpitTheme(
                        AppSettings.THEME_CUSTOM, "Custom RGB", "Custom RGB",
                        isLight = false,
                        backgroundColor = Color.parseColor("#131215"),
                        surfaceColor = Color.parseColor("#1C1B20"),
                        cardColor = Color.parseColor("#222128"),
                        cardBorderColor = Color.parseColor("#32303B"),
                        textPrimaryColor = Color.parseColor("#F7F7F8"),
                        textSecondaryColor = Color.parseColor("#A5A4AF"),
                        textTertiaryColor = Color.parseColor("#72707D"),
                        primaryColor = customColor,
                        onPrimaryColor = onPrimary,
                        accentColor = customColor,
                        subtleColor = subtle,
                        borderColor = border
                    )
                }
                else -> CockpitTheme(
                    AppSettings.THEME_WINE_RED, "Wine Red", "Wine Red",
                    isLight = false,
                    backgroundColor = Color.parseColor("#131215"),
                    surfaceColor = Color.parseColor("#1C1B20"),
                    cardColor = Color.parseColor("#222128"),
                    cardBorderColor = Color.parseColor("#32303B"),
                    textPrimaryColor = Color.parseColor("#F7F7F8"),
                    textSecondaryColor = Color.parseColor("#A5A4AF"),
                    textTertiaryColor = Color.parseColor("#72707D"),
                    primaryColor = Color.parseColor("#9E1B32"),
                    onPrimaryColor = Color.WHITE,
                    accentColor = Color.parseColor("#C92A45"),
                    subtleColor = Color.parseColor("#2C1218"),
                    borderColor = Color.parseColor("#541B26")
                )
            }
        }
    }

    private fun setupThemeSelector() {
        binding.btnModeDark.setOnClickListener {
            provideHapticFeedback()
            if (!appSettings.isDarkMode) {
                appSettings.isDarkMode = true
                appSettings.lastActiveTab = R.id.nav_account
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
                appSettings.lastActiveTab = R.id.nav_account
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
        val initialHex = String.format("#%02X%02X%02X", appSettings.customAccentRed, appSettings.customAccentGreen, appSettings.customAccentBlue)
        binding.etCustomHex.setText(initialHex)
        val initialCustomColor = appSettings.getCustomAccentColor()
        binding.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(initialCustomColor)
        binding.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(initialCustomColor)

        val onSeekChanged = { fromUser: Boolean ->
            val r = binding.seekRed.progress
            val g = binding.seekGreen.progress
            val b = binding.seekBlue.progress
            binding.tvRedValue.text = r.toString()
            binding.tvGreenValue.text = g.toString()
            binding.tvBlueValue.text = b.toString()
            val hex = String.format("#%02X%02X%02X", r, g, b)
            if (!binding.etCustomHex.isFocused && binding.etCustomHex.text.toString() != hex) {
                binding.etCustomHex.setText(hex)
            }
            val color = Color.rgb(r, g, b)
            binding.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(color)
            binding.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(color)

            appSettings.customAccentRed = r
            appSettings.customAccentGreen = g
            appSettings.customAccentBlue = b

            if (fromUser || appSettings.appTheme == AppSettings.THEME_CUSTOM) {
                applyTheme(AppSettings.THEME_CUSTOM)
            }
        }

        val seekListener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onSeekChanged(true)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                provideHapticFeedback()
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
            val hex = String.format("#%02X%02X%02X", r, g, b)
            binding.etCustomHex.setText(hex)
            onSeekChanged(true)
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
                    val canonicalHex = String.format("#%02X%02X%02X", r, g, b)
                    if (binding.etCustomHex.text.toString() != canonicalHex) {
                        binding.etCustomHex.setText(canonicalHex)
                        binding.etCustomHex.setSelection(canonicalHex.length)
                    }
                    binding.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(color)
                    binding.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(color)

                    appSettings.customAccentRed = r
                    appSettings.customAccentGreen = g
                    appSettings.customAccentBlue = b

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

        // 1. System Navigation & Status Bars
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = theme.isLight
            isAppearanceLightNavigationBars = theme.isLight
        }
        window.statusBarColor = theme.backgroundColor
        window.navigationBarColor = theme.surfaceColor

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
            binding.ivServiceHistoryIcon,
            binding.ivRsaIcon,
            binding.ivManualIcon,
            binding.ivIssuesIcon,
            binding.ivProfileAvatar,
            binding.ivMyBikeIcon,
            binding.ivGloveboxIcon,
            binding.ivVaultQrIcon,
            binding.ivVaultBackupIcon,
            binding.ivNavAppPrefIcon,
            binding.ivInterceptorIcon
        )
        for (icon in allThemedIcons) {
            icon.imageTintList = ColorStateList.valueOf(theme.accentColor)
        }

        // 3. Content Cards across all Tabs
        val contentCards = listOf(
            binding.cardHeroRange,
            binding.cardConnection,
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

        // Custom RGB Swatches & Highlight
        val customColor = appSettings.getCustomAccentColor()
        binding.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(customColor)
        binding.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(customColor)
        if (!binding.etCustomHex.isFocused) {
            val hex = String.format("#%02X%02X%02X", appSettings.customAccentRed, appSettings.customAccentGreen, appSettings.customAccentBlue)
            if (binding.etCustomHex.text.toString() != hex) {
                binding.etCustomHex.setText(hex)
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

        // Primary Buttons (Open Google Maps & Scan)
        binding.btnOpenMaps.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.btnOpenMaps.setTextColor(theme.onPrimaryColor)
        binding.btnEnableNotificationAccess.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.btnEnableNotificationAccess.setTextColor(theme.onPrimaryColor)
        binding.cardNotificationPermissionBanner.strokeColor = theme.cardBorderColor
        binding.viewConnectionDivider.setBackgroundColor(theme.cardBorderColor)

        binding.btnFindPumps.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.btnFindPumps.setTextColor(theme.onPrimaryColor)
        binding.tvNearbyPumpsSubtext.setTextColor(theme.accentColor)
        binding.tvTankSpecs.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.tvTankSpecs.setTextColor(theme.accentColor)

        if (bleManager.connectionState.value !is BleConnectionState.Connected) {
            binding.btnScan.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
            binding.btnScan.setTextColor(theme.onPrimaryColor)
        } else {
            binding.btnScan.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
            binding.btnScan.setTextColor(theme.accentColor)
        }

        // Status Banners
        binding.tvMediaBanner.setTextColor(theme.accentColor)
        binding.tvCallerStatus.setTextColor(theme.accentColor)
        binding.tvWhatsAppAlertBanner.setTextColor(theme.accentColor)

        // Streamlined Metrics
        binding.tvHomeOdometer.setTextColor(theme.accentColor)
        binding.tvHomeMileage.setTextColor(theme.accentColor)
        binding.tvAccountOdometer.setTextColor(theme.accentColor)
        binding.btnEditOdo.setTextColor(theme.accentColor)
        binding.btnEditOdo.strokeColor = ColorStateList.valueOf(theme.borderColor)

        // Support Tab
        binding.tvServiceHistoryAction.setTextColor(theme.accentColor)
        binding.tvSupportTotalInvested.setTextColor(theme.accentColor)
        binding.tvSupportChainRemaining.setTextColor(theme.accentColor)
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
        binding.btnAddFuel.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnAddFuel.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnAddFuel.setTextColor(theme.accentColor)
        binding.tvTotalInvestment.setTextColor(theme.accentColor)

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
        binding.btnConfigHome.setTextColor(theme.accentColor)
        binding.btnConfigWork.setTextColor(theme.accentColor)

        val testButtons = listOf(
            binding.btnTestLeft, binding.btnTestStraight, binding.btnTestRight, binding.btnTestRoundabout,
            binding.btnSimulateCall, binding.btnSimulateWhatsApp, binding.btnSimulateMissedCall
        )
        for (btn in testButtons) {
            btn.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
            btn.strokeColor = ColorStateList.valueOf(theme.borderColor)
            btn.setTextColor(theme.accentColor)
        }
        binding.btnStopSimulation.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnStopSimulation.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnStopSimulation.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        updateSimulationUiState(bleManager.isSimulationRunning.value, bleManager.currentActiveManeuver.value)

        // Switches
        val switches = listOf(
            binding.switchTwoWheeler,
            binding.switchAutoPark,
            binding.switchRideTracking,
            binding.switchMediaControl,
            binding.switchWhatsAppAlerts,
            binding.switchTelemetry,
            binding.switchSkipHeadunitNav
        )
        val switchStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val trackColors = intArrayOf(
            theme.primaryColor,
            ContextCompat.getColor(this, R.color.surface_border)
        )
        val thumbColors = intArrayOf(
            theme.onPrimaryColor,
            ContextCompat.getColor(this, R.color.text_tertiary)
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
            ContextCompat.getColor(this, R.color.text_tertiary)
        )
        val navColorList = ColorStateList(navStates, navColors)
        binding.bottomNav.itemIconTintList = navColorList
        binding.bottomNav.itemTextColor = navColorList

        // Dynamic Lists
        try {
            updateFuelList(fuelLogManager.entries.value)
        } catch (_: Exception) {}

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
            if (isWaze && addr.isBlank()) {
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
            showSetAddressDialog("Home", true)
            true
        }

        binding.cardQuickWork.setOnClickListener {
            provideHapticFeedback()
            val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
            val addr = appSettings.workAddress
            if (isWaze && addr.isBlank()) {
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
            showSetAddressDialog("Work", false)
            true
        }

        binding.btnConfigHome.setOnClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Home", true)
        }

        binding.btnConfigWork.setOnClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Work", false)
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

        binding.tvSettingsHome.text = "Home: ${if (home.isNotBlank()) home else defaultHomeLabel}"
        binding.tvSettingsWork.text = "Work: ${if (work.isNotBlank()) work else defaultWorkLabel}"

        binding.btnOpenMaps.text = if (isWaze) "Start Ride with Waze" else "Start Ride with Google Maps"
    }

    private fun provideHapticFeedback() {
        try {
            window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK)
                )
            } else {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(35)
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
                if (isAlert) ContextCompat.getColor(this, R.color.wine_red_subtle)
                else ContextCompat.getColor(this, R.color.surface_elevated)
            )
            snackbar.setTextColor(
                if (isAlert) ContextCompat.getColor(this, R.color.wine_red_light)
                else ContextCompat.getColor(this, R.color.text_primary)
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

        if (openQrDirectly) {
            try {
                val bitmap = DataVaultManager.getInstance(this).generateSetupQrBitmap(600, 600)
                ivQr?.setImageBitmap(bitmap)
                layoutQr?.visibility = View.VISIBLE
            } catch (e: Exception) {
                showFeedback("QR Generation failed: ${e.message}", isAlert = true)
            }
        }

        btnShowQr?.setOnClickListener {
            provideHapticFeedback()
            try {
                val bitmap = DataVaultManager.getInstance(this).generateSetupQrBitmap(600, 600)
                ivQr?.setImageBitmap(bitmap)
                layoutQr?.visibility = if (layoutQr?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            } catch (e: Exception) {
                showFeedback("QR Generation failed: ${e.message}", isAlert = true)
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
                exportVaultFile()
            }, 250)
        }

        btnImportVault?.setOnClickListener {
            provideHapticFeedback()
            dialog.dismiss()
            binding.rootLayout.postDelayed({
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

    private fun exportVaultFile() {
        try {
            android.util.Log.i("PulsarNav", "Exporting vault file...")
            val file = DataVaultManager.getInstance(this).exportVaultToFile()
            android.util.Log.i("PulsarNav", "Vault file created: ${file.absolutePath}, size=${file.length()} bytes")
            val chooser = DataVaultManager.getInstance(this).createVaultShareIntent(file)
            startActivity(chooser)
            showFeedback("Vault bundle exported successfully")
        } catch (e: Throwable) {
            android.util.Log.e("PulsarNav", "Export failed", e)
            showFeedback("Export failed: ${e.message}", isAlert = true)
        }
    }

    private fun readTextFromUri(uri: Uri): String? {
        android.util.Log.i("PulsarNav", "Attempting to read text from URI: $uri, scheme: ${uri.scheme}")
        // Strategy 1: ContentResolver openInputStream
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val content = stream.bufferedReader(Charsets.UTF_8).readText()
                if (content.isNotBlank()) {
                    android.util.Log.i("PulsarNav", "Read ${content.length} chars via contentResolver.openInputStream")
                    return content
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("PulsarNav", "openInputStream failed for $uri: ${e.message}")
        }

        // Strategy 2: Direct File access if scheme is file or path is extractable
        val rawPath = when {
            uri.scheme == ContentResolver.SCHEME_FILE -> uri.path
            uri.scheme == null -> uri.path
            else -> {
                val fullUri = Uri.decode(uri.toString())
                val idx = fullUri.indexOf("/storage/emulated/0/")
                if (idx != -1) fullUri.substring(idx) else null
            }
        }

        if (!rawPath.isNullOrBlank()) {
            try {
                val file = java.io.File(rawPath)
                if (file.exists() && file.canRead()) {
                    val content = file.readText(Charsets.UTF_8)
                    if (content.isNotBlank()) {
                        android.util.Log.i("PulsarNav", "Read ${content.length} chars via direct File($rawPath)")
                        return content
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PulsarNav", "Direct File read failed for $rawPath: ${e.message}")
            }
        }

        // Strategy 3: ParcelFileDescriptor
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { stream ->
                    val content = stream.bufferedReader(Charsets.UTF_8).readText()
                    if (content.isNotBlank()) {
                        android.util.Log.i("PulsarNav", "Read ${content.length} chars via ParcelFileDescriptor")
                        return content
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("PulsarNav", "openFileDescriptor failed: ${e.message}")
        }

        return null
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

    private fun importVaultFromUri(uri: Uri) {
        try {
            android.util.Log.i("PulsarNav", "Importing vault from URI: $uri")
            val jsonString = readTextFromUri(uri)
            if (!jsonString.isNullOrBlank()) {
                val result = DataVaultManager.getInstance(this).importVaultFromJson(jsonString)
                result.onSuccess { summary ->
                    val name = summary.riderName.ifBlank { summary.bikeName.ifBlank { "Bike" } }
                    showFeedback("Vault Restored: ${summary.tripsImported} trips, ${summary.fuelEntriesImported} fuel logs for $name")
                    try {
                        refreshAllUiAfterVaultImport()
                    } catch (e: Exception) {
                        android.util.Log.e("PulsarNav", "Error refreshing UI after vault import", e)
                    }
                }.onFailure { err ->
                    android.util.Log.e("PulsarNav", "Vault import failed", err)
                    showFeedback("Import failed: ${err.message ?: "Invalid vault file"}", isAlert = true)
                }
            } else {
                showFeedback("Could not read backup file. Please select the file using 'Import File'.", isAlert = true)
            }
        } catch (e: Exception) {
            android.util.Log.e("PulsarNav", "Error importing vault", e)
            showFeedback("Import error: ${e.message}", isAlert = true)
        }
    }

    private fun launchGoogleCodeScanner() {
        try {
            val scanner = GmsBarcodeScanning.getClient(this)
            scanner.startScan()
                .addOnSuccessListener { barcode: Barcode ->
                    val rawValue = barcode.rawValue
                    if (!rawValue.isNullOrBlank()) {
                        val result = DataVaultManager.getInstance(this).importFromQrPayload(rawValue)
                        result.onSuccess { msg ->
                            showFeedback(msg)
                            refreshAllUiAfterVaultImport()
                        }.onFailure { err ->
                            showFeedback("QR Error: ${err.message}", isAlert = true)
                        }
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
        binding.switchTwoWheeler.isChecked = appSettings.isTwoWheelerMode
        binding.switchAutoPark.isChecked = appSettings.isAutoParkEnabled
        binding.switchRideTracking.isChecked = appSettings.isRideTrackingEnabled
        binding.switchMediaControl.isChecked = appSettings.isMediaControlEnabled
        binding.switchWhatsAppAlerts.isChecked = appSettings.isWhatsAppAlertsEnabled
        binding.switchTelemetry.isChecked = appSettings.isTelemetrySyncEnabled
        binding.switchSkipHeadunitNav.isChecked = appSettings.isSkipClusterNavWhenHeadunit
        updateFuelPriceUi()
        updateBikeInfoDisplay()
        updateDocumentsSummary()
        updateServiceHistorySummary()
        updateNavAppSelectionUi()
        updateHeroRangeDisplay()
        updateBikeIssuesSummary()
        updateEmergencyHomeCard()
        updateQuickNavButtons()
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
        BikeBleForegroundService.start(this)
        if (appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE) {
            try {
                val uri = Uri.parse("waze://?q=${Uri.encode(destination)}&navigate=yes")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.waze")
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
                try {
                    val webWaze = Uri.parse("https://waze.com/ul?q=${Uri.encode(destination)}&navigate=yes")
                    startActivity(Intent(Intent.ACTION_VIEW, webWaze))
                    return
                } catch (_: Exception) {}
            }
        }

        val mode = if (appSettings.isTwoWheelerMode) "l" else "d"
        try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$mode")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val travelMode = if (appSettings.isTwoWheelerMode) "two_wheeler" else "driving"
                val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=$travelMode")
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            } catch (e2: Exception) {
                showFeedback("Could not open navigation app", isAlert = true)
            }
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
        binding.cardNotificationPermissionBanner.visibility = if (granted) View.GONE else View.VISIBLE
        binding.tvNotificationAccessStatus.text = if (granted) "Active (Maps, Waze & Auto)" else "Action Required (Tap to Enable)"
        binding.tvNotificationAccessStatus.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.mint_accent else R.color.amber_accent)
        )
    }

    private fun showNotificationAccessDialog(onContinueAnyway: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(this, R.style.ObsidianAlertDialog)
            .setTitle("Navigation Interceptor Access")
            .setMessage("Android requires Notification Access for PulsarNav so it can intercept turn-by-turn navigation arrows from Google Maps, Waze, and Android Auto and stream them directly to your Pulsar NS200 cluster.\n\nPlease enable PulsarNav on the next screen.")
            .setPositiveButton("Open Settings") { _, _ ->
                openNotificationListenerSettings()
            }
            .setNegativeButton("Continue Anyway") { _, _ ->
                onContinueAnyway?.invoke()
            }
            .show()
    }

    private fun showSetAddressDialog(label: String, isHome: Boolean) {
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

        ivIcon?.setImageResource(if (isHome) R.drawable.ic_home else R.drawable.ic_work)
        tvTitle.text = "Set $label Destination"
        val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
        tvOptionMapsTitle.text = if (isWaze) "Waze Saved $label" else "Google Maps Saved $label"

        val current = if (isHome) appSettings.homeAddress else appSettings.workAddress
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
            val defaultVal = if (isHome) "Home" else "Work"
            if (isHome) appSettings.homeAddress = defaultVal else appSettings.workAddress = defaultVal
            updateQuickNavButtons()
            showFeedback("Linked to Google Maps $label")
            sheet.dismiss()
        }

        cardOptionPinGps.setOnClickListener {
            provideHapticFeedback()
            pinCurrentLocationAs(label, isHome)
            sheet.dismiss()
        }

        btnSaveCustom.setOnClickListener {
            provideHapticFeedback()
            val text = etCustom.text.toString().trim()
            if (text.isNotBlank()) {
                if (isHome) appSettings.homeAddress = text else appSettings.workAddress = text
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
    private fun pinCurrentLocationAs(label: String, isHome: Boolean) {
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
            val addr = "%.5f,%.5f".format(lat, lng)
            if (isHome) appSettings.homeAddress = addr else appSettings.workAddress = addr
            updateQuickNavButtons()
            showFeedback("Pinned GPS as $label")
        } else {
            showFeedback("Unable to get current GPS location", isAlert = true)
        }
    }

    private fun showEditOdometerDialog() {
        val current = maintenanceManager.status.value.totalOdometerKm
        val input = createThemedEditText(
            hintText = "Odometer in km",
            initialText = "%.1f".format(current),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val container = FrameLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Lifetime Odometer")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val odo = input.text.toString().toDoubleOrNull()
                if (odo != null && odo >= 0) {
                    maintenanceManager.setOdometer(odo)
                    recordSyncTime()
                    showFeedback("Odometer updated to %.1f km".format(odo))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddFuelDialog() {
        val currentOdo = maintenanceManager.status.value.totalOdometerKm
        val petrolRate = appSettings.petrolPricePerLiter
        val currSymbol = appSettings.currentCurrencySymbol

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvDesc = TextView(this).apply {
            text = "Easy Refuel Wizard • Petrol Rate: %s%.2f/L\nTap a quick amount to auto-compute litres:".format(currSymbol, petrolRate)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
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
            initialText = "%.1f".format(currentOdo),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val costInput = createThemedEditText(
            hintText = "Total Paid $currSymbol",
            initialText = "500",
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val litersInput = createThemedEditText(
            hintText = "Fuel Litres",
            initialText = "%.2f".format(500.0 / petrolRate),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        costInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val amt = s?.toString()?.toDoubleOrNull()
                if (amt != null && amt > 0) {
                    val lit = amt / petrolRate
                    litersInput.setText("%.2f".format(lit))
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
                        val estLiters = 10.0
                        val estCost = estLiters * petrolRate
                        costInput.setText("%.0f".format(estCost))
                        litersInput.setText("%.2f".format(estLiters))
                    } else {
                        costInput.setText("%.0f".format(amount))
                        litersInput.setText("%.2f".format(amount / petrolRate))
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
        }
        layout.addView(tvOdoLabel)
        layout.addView(odoInput)

        val tvCostLabel = TextView(this).apply {
            text = "Total Cost ($currSymbol):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
        }
        layout.addView(tvCostLabel)
        layout.addView(costInput)

        val tvLitersLabel = TextView(this).apply {
            text = "Fuel Litres (Auto-calculated):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
        }
        layout.addView(tvLitersLabel)
        layout.addView(litersInput)

        val checkFullTank = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Full Tank refill (Calculates true mileage)"
            isChecked = true
            textSize = 12f
        }
        layout.addView(checkFullTank)

        MaterialAlertDialogBuilder(this)
            .setTitle("Log Refuel")
            .setView(layout)
            .setPositiveButton("Save Refill") { _, _ ->
                val odo = odoInput.text.toString().toDoubleOrNull()
                val liters = litersInput.text.toString().toDoubleOrNull()
                val cost = costInput.text.toString().toDoubleOrNull()

                if (odo != null && liters != null && cost != null && liters > 0) {
                    val entry = fuelLogManager.addFuelEntry(odo, liters, cost)
                    provideHapticFeedback()

                    // DIRECTLY UPDATE HOME PAGE FUEL & RANGE METRICS
                    val currentLiters = appSettings.fuelLevelLiters
                    val newLiters = if (checkFullTank.isChecked) 12.0f else (currentLiters + liters.toFloat()).coerceIn(0.0f, 12.0f)
                    val newPercent = ((newLiters / 12.0f) * 100).toInt().coerceIn(0, 100)
                    val avgMileage = fuelLogManager.stats.value.averageKmpl.coerceIn(25.0, 50.0)
                    val newDte = (newLiters * avgMileage).toInt()

                    appSettings.fuelLevelLiters = newLiters
                    appSettings.fuelLevelPercent = newPercent
                    appSettings.projectedDteKm = newDte
                    recordSyncTime()
                    updateHeroRangeDisplay()

                    val kmplMsg = if (entry.calculatedKmpl != null) {
                        " • Mileage: %.1f km/l!".format(entry.calculatedKmpl)
                    } else {
                        " (Will compute mileage on next refill)"
                    }
                    showFeedback("Logged %.2f L for %s%.0f%s".format(liters, currSymbol, cost, kmplMsg))
                } else {
                    showFeedback("Please enter valid fuel details", isAlert = true)
                }
            }
            .setNeutralButton("Price: %s%.2f/L".format(currSymbol, petrolRate)) { _, _ ->
                showEditPetrolPriceDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateFuelPriceUi() {
        val currSymbol = appSettings.currentCurrencySymbol
        binding.tvFuelPriceTitle.text = "Fuel Price: %s%.2f / L".format(currSymbol, appSettings.petrolPricePerLiter)
        binding.tvFuelPriceCountrySubtitle.text = "Tap to update default ${appSettings.currentCurrencyCode} rate"
        binding.tvFuelPriceTitle.setTextColor(currentTheme.textPrimaryColor)
        binding.tvFuelPriceCountrySubtitle.setTextColor(currentTheme.textSecondaryColor)
        binding.tvEditFuelPriceAction.setTextColor(currentTheme.accentColor)
    }

    private fun showEditPetrolPriceDialog() {
        val currSymbol = appSettings.currentCurrencySymbol
        val input = createThemedEditText(
            hintText = "Price in $currSymbol",
            initialText = "%.2f".format(appSettings.petrolPricePerLiter),
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
                val p = input.text.toString().toDoubleOrNull()
                if (p != null && p > 0.0) {
                    appSettings.petrolPricePerLiter = p
                    updateFuelPriceUi()
                    updateHeroRangeDisplay()
                    showFeedback("Fuel price set to %s%.2f/L".format(currSymbol, p))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getMissingPermissions(): List<String> {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
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

        val missingPerms = getMissingPermissions()
        val hasPerms = missingPerms.isEmpty()

        binding.chipBtWarning.visibility = if (!isBtOn) View.VISIBLE else View.GONE
        binding.chipGpsWarning.visibility = if (!isGpsOn) View.VISIBLE else View.GONE
        binding.chipPermWarning.visibility = if (!hasPerms) View.VISIBLE else View.GONE

        val isConnected = bleManager.connectionState.value is BleConnectionState.Connected
        if (!isConnected) {
            when {
                !hasPerms -> {
                    binding.tvSubStatus.text = "Permissions needed: Tap PERMS NEEDED above"
                    binding.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                !isBtOn && !isGpsOn -> {
                    binding.tvSubStatus.text = "Turn ON Bluetooth & GPS in Quick Settings"
                    binding.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                !isBtOn -> {
                    binding.tvSubStatus.text = "Turn ON Bluetooth in Quick Settings"
                    binding.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                !isGpsOn -> {
                    binding.tvSubStatus.text = "Turn ON Location (GPS) in Quick Settings"
                    binding.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                else -> {
                    binding.tvSubStatus.text = "Auto-reconnects when ignition turns ON"
                    binding.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
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

        updateHardwareReadinessUi()

        val missing = getMissingPermissions()
        if (missing.isNotEmpty()) {
            if (!silent) {
                showFeedback("Grant required permissions to connect to your bike console", isAlert = true)
                permissionLauncher.launch(missing.toTypedArray())
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

        if (!isGpsOn) {
            if (!silent) {
                showFeedback("Location (GPS) is OFF. Android requires Location to scan for Bluetooth devices.", isAlert = true)
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {}
            }
            return
        }

        bleManager.startScan()
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
            bleManager.connectionState.collectLatest { state ->
                when (state) {
                    is BleConnectionState.Disconnected -> {
                        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(currentTheme.accentColor)
                        binding.tvConnectionStatus.text = "Disconnected"
                        binding.tvConnectionStatus.setTextColor(currentTheme.textPrimaryColor)
                        binding.tvSubStatus.text = "Auto-reconnects when ignition turns ON"
                        binding.tvSubStatus.setTextColor(currentTheme.textSecondaryColor)
                        binding.btnScan.text = "Scan"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
                        binding.btnScan.setTextColor(currentTheme.onPrimaryColor)
                        binding.layoutDeviceList.visibility = View.VISIBLE

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                        binding.tvTopConnectionStatus.text = "OFFLINE"
                        binding.tvTopConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                    }
                    is BleConnectionState.Scanning -> {
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_scanning)
                        binding.tvConnectionStatus.text = "Searching for bikes..."
                        binding.tvConnectionStatus.setTextColor(currentTheme.textPrimaryColor)
                        binding.tvSubStatus.text = "Looking for Pulsar NS200 cluster"
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
                        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(currentTheme.accentColor)
                        binding.tvConnectionStatus.text = state.message
                        binding.btnScan.text = "Scan"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
                        binding.btnScan.setTextColor(currentTheme.onPrimaryColor)

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                        binding.tvTopConnectionStatus.text = "OFFLINE"
                        binding.tvTopConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                    }
                }
            }
        }

        lifecycleScope.launch {
            bleManager.discoveredBikes.collectLatest { bikes ->
                updateDeviceList(bikes)
            }
        }

        lifecycleScope.launch {
            bleManager.lastSentPacket.collectLatest { packet ->
                if (packet != null) {
                    val hex = packet.joinToString(" ") { "%02X".format(it) }
                    binding.tvPacketHex.text = "Last Packet (${packet.size}B):\n$hex"
                } else {
                    binding.tvPacketHex.text = "Last BLE Packet: None"
                }
            }
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
            GoogleMapsNotificationListener.currentNavStep.collectLatest { step ->
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
                        "Active Route"
                    }
                    updateNavDisplay(step.maneuver, dist, street, etaStr)
                } else if (!bleManager.isSimulationRunning.value) {
                    binding.tvDistance.text = "-- m"
                    binding.tvStreetName.text = "Waiting for Google Maps..."
                    binding.tvManeuverIcon.text = "⬆"
                    binding.tvEtaBadge.text = "-- min ETA"
                }
            }
        }

        lifecycleScope.launch {
            combine(bleManager.isSimulationRunning, bleManager.currentActiveManeuver) { running, maneuver ->
                Pair(running, maneuver)
            }.collectLatest { (running, maneuver) ->
                updateSimulationUiState(running, maneuver)
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
            binding.btnStopSimulation.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun observeTelephonyAndAlerts() {
        lifecycleScope.launch {
            callManager.callInfo.collectLatest { call ->
                when (call.state) {
                    CallState.INCOMING_CALL -> {
                        binding.tvCallerStatus.text = "Incoming Call: ${call.callerName ?: "Unknown"} (${call.callerNumber.orEmpty()})"
                        binding.tvCallerStatus.setTextColor(currentTheme.accentColor)
                    }
                    CallState.ACTIVE_CALL -> {
                        binding.tvCallerStatus.text = "Active Call: ${call.callerName ?: "In Call"}"
                        binding.tvCallerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                    }
                    else -> {
                        binding.tvCallerStatus.text = "Phone Idle • No incoming call"
                        binding.tvCallerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    }
                }
                binding.tvMissedCallBadge.text = "${call.missedCallCount} Missed"
            }
        }

        lifecycleScope.launch {
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

        lifecycleScope.launch {
            GoogleMapsNotificationListener.lastWhatsAppAlert.collectLatest { alert ->
                if (alert != null) {
                    binding.tvWhatsAppAlertBanner.text = "WhatsApp Alert: ${alert.sender} (Showing on cluster)"
                    binding.tvWhatsAppAlertBanner.setTextColor(currentTheme.accentColor)
                }
            }
        }
    }

    private fun observeTelemetry() {
        lifecycleScope.launch {
            telemetryManager.telemetryState.collectLatest { _ ->
                // Telemetry syncs in background to cluster
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
                        val target = currentOdo + 5000.0
                        maintenanceManager.setNextServiceTargetOdo(target)
                        recordSyncTime()
                        showFeedback("Next service target set to %.0f km".format(target))
                    }
                    "Overdue Extension (+500 km)" -> {
                        maintenanceManager.rescheduleNextService(500.0)
                        recordSyncTime()
                        showFeedback("Service rescheduled. Extended by 500 km.")
                    }
                    "Mark Service Completed Today" -> {
                        maintenanceManager.markServiceDone()
                        recordSyncTime()
                        showFeedback("Service marked completed. Next target: %.0f km".format(maintenanceManager.status.value.nextServiceTargetOdoKm))
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
            initialText = "%.0f".format(maintenanceManager.status.value.nextServiceTargetOdoKm),
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
                val target = input.text.toString().toDoubleOrNull()
                if (target != null && target > 0) {
                    maintenanceManager.setNextServiceTargetOdo(target)
                    recordSyncTime()
                    showFeedback("Next service target set to %.0f km".format(target))
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
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
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
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
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                    } else {
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    }
                }
                val tvDate = TextView(this).apply {
                    text = if (issue.isResolved) "Resolved • ${issue.dateLogged}" else "Added • ${issue.dateLogged}"
                    textSize = 10f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
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
            maintenanceManager.status.collectLatest { s ->
                updateHeroRangeDisplay()
                binding.tvChainRemaining.text = "%.0f km left".format(s.chainLubeRemainingKm)
                binding.progressChain.progress = s.chainLubePercent
                binding.tvSupportChainRemaining.text = "%.0f km".format(s.chainLubeRemainingKm.coerceAtLeast(0.0))
                binding.tvSupportServiceRemaining.text = if (s.isServiceOverdue) "OVERDUE" else "%.0f km".format(s.serviceRemainingKm.coerceAtLeast(0.0))

                if (s.isServiceOverdue) {
                    binding.tvServiceRemaining.text = "OVERDUE by %.0f km".format(s.serviceOverdueKm)
                    binding.tvServiceRemaining.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.wine_red_light))
                    binding.progressService.progress = 100
                } else {
                    binding.tvServiceRemaining.text = "%.0f km left (Target: %.0f km)".format(s.serviceRemainingKm, s.nextServiceTargetOdoKm)
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

    @SuppressLint("SetTextI18n")
    private fun observeFuelLog() {
        lifecycleScope.launch {
            fuelLogManager.stats.collectLatest { stats ->
                updateHeroRangeDisplay()
                updateBikeInvestmentDisplay()
                if (stats.averageKmpl > 0.0) {
                    binding.tvAvgMileage.text = "%.1f km/l".format(stats.averageKmpl)
                } else {
                    binding.tvAvgMileage.text = "-- km/l"
                }
                if (stats.costPerKm > 0.0) {
                    binding.tvCostPerKm.text = "%s%.2f / km".format(appSettings.currentCurrencySymbol, stats.costPerKm)
                } else {
                    binding.tvCostPerKm.text = "-- / km"
                }
            }
        }

        lifecycleScope.launch {
            fuelLogManager.entries.collectLatest { entries ->
                updateFuelList(entries)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateFuelList(entries: List<com.pulsarnav.app.maintenance.FuelEntry>) {
        binding.layoutFuelList.removeAllViews()
        if (entries.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No refuel records yet. Tap 'Log Refueling' above to track fuel & economy."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
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
            view.findViewById<TextView>(R.id.tvFuelOdo).text = "Odo: %.1f km • %.1f L".format(entry.odometerKm, entry.liters)
            val tvCost = view.findViewById<TextView>(R.id.tvFuelCost)
            tvCost.text = "%s%.0f".format(appSettings.currentCurrencySymbol, entry.totalCostInr)
            tvCost.setTextColor(currentTheme.accentColor)
            val mileageView = view.findViewById<TextView>(R.id.tvFuelMileage)
            if (entry.calculatedKmpl != null) {
                mileageView.text = "%.1f km/l".format(entry.calculatedKmpl)
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        val odoInput = createThemedEditText(
            hintText = "Odometer (km)",
            initialText = "%.1f".format(entry.odometerKm),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val litersInput = createThemedEditText(
            hintText = "Fuel Litres",
            initialText = "%.2f".format(entry.liters),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val costInput = createThemedEditText(
            hintText = "Total Cost (%s)".format(appSettings.currentCurrencySymbol),
            initialText = "%.0f".format(entry.totalCostInr),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        fun addField(label: String, field: View) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                textSize = 11f
                setPadding(0, 8, 0, 4)
            }
            layout.addView(tv)
            layout.addView(field)
        }

        addField("Odometer (km):", odoInput)
        addField("Fuel Litres:", litersInput)
        addField("Total Amount Paid (%s):".format(appSettings.currentCurrencySymbol), costInput)

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Refuel Entry")
            .setView(layout)
            .setPositiveButton("Save Changes") { _, _ ->
                val odo = odoInput.text.toString().toDoubleOrNull()
                val lit = litersInput.text.toString().toDoubleOrNull()
                val cost = costInput.text.toString().toDoubleOrNull()
                if (odo != null && lit != null && cost != null && lit > 0) {
                    fuelLogManager.updateFuelEntry(entry.id, odo, lit, cost)
                    recordSyncTime()
                    updateHeroRangeDisplay()
                    provideHapticFeedback()
                    showFeedback("Refuel entry updated!")
                }
            }
            .setNeutralButton("Delete Entry") { _, _ ->
                fuelLogManager.deleteFuelEntry(entry.id)
                recordSyncTime()
                updateHeroRangeDisplay()
                provideHapticFeedback()
                showFeedback("Refuel entry deleted")
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
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
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
            Maneuver.TURN_RIGHT, Maneuver.TURN_SHARP_RIGHT, Maneuver.KEEP_RIGHT, Maneuver.FORK_RIGHT, Maneuver.RAMP_RIGHT -> ""
            Maneuver.TURN_SLIGHT_LEFT -> "↖"
            Maneuver.TURN_SLIGHT_RIGHT -> "↗"
            Maneuver.U_TURN_LEFT, Maneuver.U_TURN_RIGHT -> "↩"
            Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT -> "↺"
            Maneuver.DESTINATION_REACHED -> "ARRIVED"
            else -> "⬆"
        }
    }
}
