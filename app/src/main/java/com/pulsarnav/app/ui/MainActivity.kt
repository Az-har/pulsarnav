package com.pulsarnav.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    val backgroundColor: Int,
    val surfaceColor: Int,
    val cardColor: Int,
    val cardBorderColor: Int,
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

    private var currentTheme: CockpitTheme = getThemeDefinition(AppSettings.THEME_WINE_RED)

    private var pendingDocType: String? = null
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

        if (btGranted) {
            bleManager.startScan()
        } else {
            showFeedback("Bluetooth permissions are required to connect to your bike", isAlert = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        appSettings = AppSettings.getInstance(this)
        parkingManager = ParkingManager.getInstance(this)
        rideTracker = RideTracker.getInstance(this)
        maintenanceManager = MaintenanceManager.getInstance(this)
        fuelLogManager = FuelLogManager.getInstance(this)
        mediaControlManager = MediaControlManager.getInstance(this)

        // Setup UI Sections
        setupBottomNavigation()
        setupListeners()
        setupClusterRecalibrate()
        updateBikeInfoDisplay()
        updateDocumentsSummary()
        updateServiceHistorySummary()
        updateHeroRangeDisplay()
        updateEmergencyHomeCard()
        updateOwnershipDays()
        setupStatsNavigation()
        setupSupportAndAccountDialogs()
        setupFeatureToggles()
        setupThemeSelector()
        setupQuickNav()

        // Apply saved theme
        applyTheme(appSettings.appTheme)

        // Observers
        observeBleState()
        observeNavigation()
        observeTelephonyAndAlerts()
        observeTelemetry()
        observeParking()
        observeTrips()
        observeMaintenance()
        observeFuelLog()

        // Handle quick action intents
        intent?.let { handleIntentActions(it) }

        // Initial scan if permissions are ready
        checkAndStartScan(silent = true)
    }

    override fun onResume() {
        super.onResume()
        updateNotificationAccessUi()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntentActions(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.scrollTrips.visibility == View.VISIBLE) {
            switchTab(binding.scrollCockpit)
        } else if (binding.scrollFuel.visibility == View.VISIBLE ||
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
        when (intent.action) {
            "com.pulsarnav.action.NAV_HOME" -> {
                val addr = appSettings.homeAddress
                launchNavigationTo(if (addr.isNotBlank() && addr != "Home") addr else "Home")
            }
            "com.pulsarnav.action.NAV_WORK" -> {
                val addr = appSettings.workAddress
                launchNavigationTo(if (addr.isNotBlank() && addr != "Work") addr else "Work")
            }
            "com.pulsarnav.action.FIND_BIKE" -> {
                val walkingIntent = parkingManager.getWalkingDirectionsIntent()
                    ?: parkingManager.getFallbackWalkingDirectionsIntent()
                if (walkingIntent != null) {
                    try {
                        startActivity(walkingIntent)
                    } catch (_: Exception) {}
                }
            }
            "com.pulsarnav.action.LOG_REFUEL" -> {
                showAddFuelDialog()
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            provideHapticFeedback()
            when (item.itemId) {
                R.id.nav_home -> switchTab(binding.scrollCockpit)
                R.id.nav_fuel -> switchTab(binding.scrollFuel)
                R.id.nav_support -> switchTab(binding.scrollSupport)
                R.id.nav_account -> switchTab(binding.scrollSettings)
                else -> false
            }
        }
    }

    private fun switchTab(targetView: View): Boolean {
        binding.scrollCockpit.visibility = View.GONE
        binding.scrollFuel.visibility = View.GONE
        binding.scrollSupport.visibility = View.GONE
        binding.scrollSettings.visibility = View.GONE
        binding.scrollTrips.visibility = View.GONE

        targetView.visibility = View.VISIBLE
        return true
    }

    private fun showRidingStats(showAllRidesTab: Boolean = false) {
        provideHapticFeedback()
        binding.scrollCockpit.visibility = View.GONE
        binding.scrollFuel.visibility = View.GONE
        binding.scrollSupport.visibility = View.GONE
        binding.scrollSettings.visibility = View.GONE
        binding.scrollTrips.visibility = View.VISIBLE

        selectStatsTab(if (showAllRidesTab) binding.tabStatsAllRides else binding.tabStatsHighlights)
    }

    private fun selectStatsTab(selectedTab: TextView) {
        val isHighlights = (selectedTab.id == R.id.tabStatsHighlights)
        binding.layoutStatsHighlights.visibility = if (isHighlights) View.VISIBLE else View.GONE
        binding.layoutStatsAllRides.visibility = if (isHighlights) View.GONE else View.VISIBLE

        binding.tabStatsHighlights.background = getPillChipDrawable(isHighlights, currentTheme)
        binding.tabStatsHighlights.setTextColor(if (isHighlights) currentTheme.accentColor else ContextCompat.getColor(this, R.color.text_secondary))

        binding.tabStatsAllRides.background = getPillChipDrawable(!isHighlights, currentTheme)
        binding.tabStatsAllRides.setTextColor(if (!isHighlights) currentTheme.accentColor else ContextCompat.getColor(this, R.color.text_secondary))
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

        if (dteKm <= 0 && fuelPercent <= 0) {
            binding.tvProjectedRange.text = "--"
            binding.tvDteSubtext.text = "Tap Recalibrate to sync"
            binding.tvFuelLevelText.text = "0% Tank Level • 0.0L / 12.0L"
            binding.progressFuelLevel.progress = 0
        } else {
            binding.tvProjectedRange.text = if (dteKm > 0) dteKm.toString() else "--"
            binding.tvDteSubtext.text = "~${"%.1f".format(fuelLiters)}L in tank"
            binding.tvFuelLevelText.text = "${fuelPercent}% Tank Level • ${"%.1f".format(fuelLiters)}L / 12.0L"
            binding.progressFuelLevel.progress = fuelPercent
        }

        updateSyncTimeDisplay()
    }

    private fun updateBikeInfoDisplay() {
        binding.tvBikeName.text = appSettings.bikeName.ifBlank { "Pulsar NS200" }
        binding.tvBikeReg.text = if (appSettings.bikeRegNumber.isNotBlank()) {
            "${appSettings.bikeRegNumber} • ${appSettings.bikeModelSpec}"
        } else {
            "Add Number Plate • ${appSettings.bikeModelSpec}"
        }
        binding.tvProfileName.text = appSettings.riderName.ifBlank { "Rider Profile" }
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

    private fun updateServiceHistorySummary() {
        val count = maintenanceManager.getServiceHistory().size
        binding.tvServiceHistorySummary.text = if (count > 0) {
            "$count services recorded • Tap to view & edit"
        } else {
            "0 services recorded • Tap to log service"
        }
    }

    private fun showClusterRecalibrateBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_recalibrate_cluster, null)
        dialog.setContentView(sheetView)

        val etDte = sheetView.findViewById<EditText>(R.id.etRecalibrateDte)
        val etOdo = sheetView.findViewById<EditText>(R.id.etRecalibrateOdo)
        val etFuel = sheetView.findViewById<EditText>(R.id.etRecalibrateFuelLiters)
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

    private fun setupStatsNavigation() {
        binding.btnViewAllRidingStats.setOnClickListener {
            showRidingStats(showAllRidesTab = false)
        }
        binding.btnViewPreviousRides.setOnClickListener {
            showRidingStats(showAllRidesTab = true)
        }
        binding.btnViewRideDetails.setOnClickListener {
            showRidingStats(showAllRidesTab = true)
        }
        binding.btnBackFromStats.setOnClickListener {
            provideHapticFeedback()
            switchTab(binding.scrollCockpit)
        }
        binding.tabStatsHighlights.setOnClickListener {
            provideHapticFeedback()
            selectStatsTab(binding.tabStatsHighlights)
        }
        binding.tabStatsAllRides.setOnClickListener {
            provideHapticFeedback()
            selectStatsTab(binding.tabStatsAllRides)
        }
        binding.btnShareRide.setOnClickListener {
            provideHapticFeedback()
            shareRideSummary()
        }
    }

    private fun shareRideSummary() {
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(
                Intent.EXTRA_TEXT,
                "🏍️ Pulsar NS200 Ride Log:\nDistance: 6.2 km • Time: 18 min • Avg Mileage: 38.5 km/l\nRecorded via PulsarNav Companion."
            )
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share Ride Summary")
        startActivity(shareIntent)
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

        // Quick Nearby Fuel on Home and Fuel Tabs
        binding.cardQuickFuel.setOnClickListener {
            launchNearbyFuelPumps()
        }
        binding.btnFindPumps.setOnClickListener {
            launchNearbyFuelPumps()
        }

        // Navigate to Bike (Walking Directions)
        binding.btnNavigateToBike.setOnClickListener {
            provideHapticFeedback()
            val loc = parkingManager.lastParkedLocation.value
            if (loc == null) {
                showFeedback("Parking spot not saved yet. Turn off bike ignition to save.")
                return@setOnClickListener
            }

            try {
                val intent = parkingManager.getWalkingDirectionsIntent()
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallback = parkingManager.getFallbackWalkingDirectionsIntent()
                    startActivity(fallback)
                } catch (e2: Exception) {
                    showFeedback("Could not open Maps navigation", isAlert = true)
                }
            }
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
        binding.btnStopSimulation.setOnClickListener {
            provideHapticFeedback()
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
            callManager.simulateIncomingCall("Dad", "+91 98765 43210")
            showFeedback("Simulating Incoming Call to Cluster")
        }

        binding.btnSimulateWhatsApp.setOnClickListener {
            provideHapticFeedback()
            val sampleSender = "Rahul Sharma"
            bleManager.sendWhatsAppAlert(sampleSender)
            binding.tvWhatsAppAlertBanner.text = "💬 WhatsApp Alert: $sampleSender (Showing 6s)"
            binding.tvWhatsAppAlertBanner.setTextColor(currentTheme.accentColor)
            showFeedback("WhatsApp alert sent to cluster (6s preview)")
        }

        binding.btnSimulateMissedCall.setOnClickListener {
            provideHapticFeedback()
            callManager.simulateEndCall()
            showFeedback("Call Ended / Idle")
        }

        // Parking Simulation
        binding.btnSimulatePark.setOnClickListener {
            provideHapticFeedback()
            parkingManager.saveLocation(19.0596, 72.8295, "Bandra Bandstand, Mumbai")
            showFeedback("Simulated parking location saved!")
        }

        // Simulate Ride
        binding.btnSimulateRide.setOnClickListener {
            provideHapticFeedback()
            rideTracker.simulateTrip(14.2, 1695, 84.0, 36.2, "Bandra to BKC Ride")
            maintenanceManager.addDistance(14.2)
            showFeedback("Simulated 14.2 km ride logged!")
        }

        // Edit Odometer
        binding.btnEditOdo.setOnClickListener {
            provideHapticFeedback()
            showEditOdometerDialog()
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
            showFeedback("Service interval reset to 5,000 km")
        }

        // Add Refuel Entry
        binding.btnAddFuel.setOnClickListener {
            provideHapticFeedback()
            showAddFuelDialog()
        }

        // Days of Ownership
        binding.layoutDaysOfOwnership.setOnClickListener {
            provideHapticFeedback()
            showEditOwnershipDaysDialog()
        }

        // Add Manual Ride
        binding.btnAddManualRide.setOnClickListener {
            provideHapticFeedback()
            showAddManualRideDialog()
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
                showFeedback("Could not open Google Maps", isAlert = true)
            }
        }
    }

    private fun launchNearbyFuelPumps() {
        provideHapticFeedback()
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
        // Bajaj Authorized Service Centers -> Launch Maps search for Bajaj Service Center
        val openBajajService = View.OnClickListener {
            provideHapticFeedback()
            try {
                val uri = Uri.parse("geo:0,0?q=Bajaj+authorized+service+center")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com?q=Bajaj+service+center")))
            }
        }
        binding.cardServiceCenters.setOnClickListener(openBajajService)

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

        // True Health
        binding.rowTrueHealth.setOnClickListener {
            provideHapticFeedback()
            showTrueHealthDialog()
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNameLabel)

        val etName = EditText(this).apply {
            setText(appSettings.bikeName)
            hint = "e.g. Nikhil's Pulsar NS200"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etName)

        // Registration Number (Plate)
        val tvRegLabel = TextView(this).apply {
            text = "\nRegistration Number (Plate):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvRegLabel)

        val etReg = EditText(this).apply {
            setText(appSettings.bikeRegNumber)
            hint = "e.g. MH02GC2593"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etReg)

        // Model / Spec
        val tvSpecLabel = TextView(this).apply {
            text = "\nModel & Spec Details:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvSpecLabel)

        val etSpec = EditText(this).apply {
            setText(appSettings.bikeModelSpec)
            hint = "e.g. BS6 DTS-i 4V Liquid-Cooled"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etSpec)

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle("🏍️ Edit Motorcycle Details")
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNameLabel)

        val etName = EditText(this).apply {
            setText(appSettings.riderName)
            hint = "e.g. Nikhil Maity"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etName)

        val tvPhoneLabel = TextView(this).apply {
            text = "\nPhone Number:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvPhoneLabel)

        val etPhone = EditText(this).apply {
            setText(appSettings.riderPhone)
            hint = "e.g. +91 98765 43210"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        layout.addView(etPhone)

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle("👤 Edit Rider Profile")
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

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val tvSummary = TextView(this).apply {
            text = "Total Services: ${records.size} • Next Due at %.0f km\nTap any record below to edit or delete:".format(
                odo + maintenanceManager.status.value.serviceRemainingKm
            )
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvSummary)

        if (records.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "No service records logged yet.\nTap 'Log New Service' below to record vehicle maintenance."
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
                text = "📅 ${rec.date} • 🛵 ${rec.odoKm} km • 🏢 ${rec.workshop}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                textSize = 11f
                setPadding(0, 6, 0, 6)
            }
            cardContent.addView(tvMeta)

            if (rec.notes.isNotBlank()) {
                val tvNotes = TextView(this).apply {
                    text = "📝 ${rec.notes}"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    textSize = 12f
                }
                cardContent.addView(tvNotes)
            }

            val rowActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                setPadding(0, 10, 0, 0)
            }

            val btnEdit = MaterialButton(this).apply {
                text = "✏️ Edit"
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
                text = "🗑️ Delete"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.wine_red_light))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    provideHapticFeedback()
                    maintenanceManager.deleteServiceRecord(rec.id)
                    recordSyncTime()
                    updateServiceHistorySummary()
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
            .setTitle("📄 Service History & Records")
            .setView(scrollView)
            .setPositiveButton("➕ Add Service Record") { _, _ ->
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvTitleLabel)

        val etTitle = EditText(this).apply {
            setText(existing?.title ?: "Periodic Maintenance")
            hint = "e.g. 4th Paid Service, Synthetic Oil Flush"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etTitle)

        // Date
        val tvDateLabel = TextView(this).apply {
            text = "\nService Date:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvDateLabel)

        val etDate = EditText(this).apply {
            val today = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
            setText(existing?.date ?: today)
            hint = "e.g. 29 Oct 2026"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etDate)

        // Odometer
        val currentOdo = maintenanceManager.status.value.totalOdometerKm
        val tvOdoLabel = TextView(this).apply {
            text = "\nOdometer Reading (km):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvOdoLabel)

        val etOdo = EditText(this).apply {
            setText(if (isEdit) "%.1f".format(existing!!.odoKm) else "%.1f".format(currentOdo))
            hint = "e.g. 14250.0"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(etOdo)

        // Workshop
        val tvWorkshopLabel = TextView(this).apply {
            text = "\nWorkshop / Service Center:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvWorkshopLabel)

        val etWorkshop = EditText(this).apply {
            setText(existing?.workshop ?: "Bajaj Authorized Service")
            hint = "e.g. Bajaj Authorized Service"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etWorkshop)

        // Cost
        val tvCostLabel = TextView(this).apply {
            text = "\nTotal Bill Cost (₹):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvCostLabel)

        val etCost = EditText(this).apply {
            setText(existing?.cost ?: "₹0")
            hint = "e.g. ₹550"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etCost)

        // Notes
        val tvNotesLabel = TextView(this).apply {
            text = "\nService Notes & Parts Replaced:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNotesLabel)

        val etNotes = EditText(this).apply {
            setText(existing?.notes ?: "")
            hint = "e.g. Synthetic oil replaced, chain slack adjusted, air filter cleaned"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etNotes)

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (isEdit) "✏️ Edit Service Record" else "➕ Add Service Record")
            .setView(scrollView)
            .setPositiveButton("Save Record") { _, _ ->
                val title = etTitle.text.toString().trim()
                val date = etDate.text.toString().trim()
                val odoVal = etOdo.text.toString().toDoubleOrNull() ?: currentOdo
                val workshop = etWorkshop.text.toString().trim()
                val cost = etCost.text.toString().trim()
                val notes = etNotes.text.toString().trim()

                val rec = existing?.copy(
                    title = if (title.isNotBlank()) title else "Periodic Service",
                    date = if (date.isNotBlank()) date else "Today",
                    odoKm = odoVal,
                    workshop = if (workshop.isNotBlank()) workshop else "Bajaj Service",
                    cost = if (cost.isNotBlank()) cost else "₹0",
                    notes = notes
                ) ?: com.pulsarnav.app.maintenance.ServiceRecord(
                    title = if (title.isNotBlank()) title else "Periodic Service",
                    date = if (date.isNotBlank()) date else "Today",
                    odoKm = odoVal,
                    workshop = if (workshop.isNotBlank()) workshop else "Bajaj Service",
                    cost = if (cost.isNotBlank()) cost else "₹0",
                    notes = notes
                )

                if (isEdit) {
                    maintenanceManager.updateServiceRecord(rec)
                } else {
                    maintenanceManager.addServiceRecord(rec)
                }

                recordSyncTime()
                updateServiceHistorySummary()
                showFeedback("Service record saved!")
                showServiceHistoryDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDocumentsDialog() {
        val hasRcFile = appSettings.docRcPath.isNotBlank() && File(appSettings.docRcPath).exists()
        val hasInsFile = appSettings.docInsurancePath.isNotBlank() && File(appSettings.docInsurancePath).exists()
        val hasPucFile = appSettings.docPucPath.isNotBlank() && File(appSettings.docPucPath).exists()

        val items = arrayOf(
            "🏍️ Registration Certificate (RC)\n   Number: ${appSettings.docRcNumber}\n   File: ${if (hasRcFile) "📎 Attached (Tap to manage)" else "⚠️ No file uploaded"}",
            "🛡️ Comprehensive Insurance\n   Policy: ${appSettings.docInsurancePolicy}\n   Expiry: ${appSettings.docInsuranceExpiry}\n   File: ${if (hasInsFile) "📎 Attached (Tap to manage)" else "⚠️ No file uploaded"}",
            "💨 Pollution Under Control (PUC)\n   Cert: ${appSettings.docPucCert}\n   Expiry: ${appSettings.docPucExpiry}\n   File: ${if (hasPucFile) "📎 Attached (Tap to manage)" else "⚠️ No file uploaded"}"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("📄 Digital Glovebox Documents")
            .setItems(items) { _, which ->
                provideHapticFeedback()
                when (which) {
                    0 -> showDocumentActionDialog("rc", "Registration Certificate (RC)", appSettings.docRcNumber, null, appSettings.docRcPath)
                    1 -> showDocumentActionDialog("insurance", "Comprehensive Insurance", appSettings.docInsurancePolicy, appSettings.docInsuranceExpiry, appSettings.docInsurancePath)
                    2 -> showDocumentActionDialog("puc", "Pollution Under Control (PUC)", appSettings.docPucCert, appSettings.docPucExpiry, appSettings.docPucPath)
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showDocumentActionDialog(
        docType: String,
        title: String,
        numberOrPolicy: String,
        expiry: String?,
        filePath: String
    ) {
        val hasFile = filePath.isNotBlank() && File(filePath).exists()
        val options = mutableListOf<String>()
        options.add("✏️ Edit Details & Expiry Dates")
        options.add("📎 Upload / Replace Document (Image/PDF)")
        if (hasFile) {
            options.add("👁️ View Uploaded Document")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, which ->
                provideHapticFeedback()
                when (which) {
                    0 -> showEditDocumentDetailsDialog(docType, title, numberOrPolicy, expiry)
                    1 -> {
                        pendingDocType = docType
                        try {
                            documentPicker.launch(arrayOf("image/*", "application/pdf"))
                        } catch (e: Exception) {
                            showFeedback("Could not open file picker: ${e.message}", isAlert = true)
                        }
                    }
                    2 -> {
                        if (hasFile) viewDocument(filePath, title)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNumLabel)

        val etNum = EditText(this).apply {
            setText(numberOrPolicy(docType))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etNum)

        val etExpiry = if (expiryRequired(docType)) {
            val tvExpLabel = TextView(this).apply {
                text = "\nExpiry Date:"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            layout.addView(tvExpLabel)

            val et = EditText(this).apply {
                setText(currentExpiry ?: "")
                hint = "e.g. 15 Nov 2027"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                setBackgroundResource(R.drawable.bg_obsidian_input)
                setPadding(30, 24, 30, 24)
            }
            layout.addView(et)
            et
        } else null

        MaterialAlertDialogBuilder(this)
            .setTitle("✏️ Edit $title")
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
            "📞 Bajaj 24x7 RSA Helpline (1800 233 2453)",
            "📞 National Highway Assistance (1033)",
            "📞 Emergency Services (112)"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("🚚 Emergency Roadside Assistance")
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
        MaterialAlertDialogBuilder(this)
            .setTitle("📖 Pulsar NS200 BS6 Quick Handbook")
            .setMessage(
                "⚙️ ENGINE & TRANSMISSION:\n" +
                "• 199.5 cc Triple Spark 4V Liquid-Cooled\n" +
                "• 24.5 PS @ 9750 RPM • 18.7 Nm @ 8000 RPM\n\n" +
                "🛞 TIRE PRESSURES (Cold):\n" +
                "• Front: 28 PSI (Solo & Pillion)\n" +
                "• Rear: 32 PSI (Solo) • 36 PSI (Pillion)\n\n" +
                "🛢️ FLUID SPECIFICATIONS:\n" +
                "• Engine Oil: 10W-50 Semi-Synthetic / Fully Synthetic (1200 ml)\n" +
                "• Coolant: Ready-mix Glycol Coolant (1000 ml)\n" +
                "• Brake Fluid: DOT 4\n\n" +
                "⛓️ DRIVE CHAIN & PLUGS:\n" +
                "• Free Slack: 25 - 35 mm (Lubricate every 500 km)\n" +
                "• Spark Plugs: Champion RG4HC (Center) + RG6HCC (Left & Right)\n" +
                "• Fuel Tank: 12.0 L (Reserve begins at 2.4 L)"
            )
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showSupportIssueDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("💬 PulsarNav Support & Diagnostic Logs")
            .setMessage("All Turn-by-Turn GATT packets and Bluetooth logs are stored locally. Would you like to share a diagnostic log or report an issue on GitHub?")
            .setPositiveButton("Report on GitHub") { _, _ ->
                provideHapticFeedback()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nikhilmaity00/pulsarnav/issues")))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showTrueHealthDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("🛡️ Pulsar NS200 True Health")
            .setMessage(
                "⚡ Electrical & Battery: 12.6V (Optimal)\n" +
                "❄️ Engine Cooling: Liquid coolant level normal\n" +
                "🛢️ Engine Oil: 4,880 km until next 5,000 km oil service\n" +
                "⛓️ Drive Chain: 380 km until next 500 km lube cycle\n" +
                "🛑 Braking System: Front & Rear Dual ABS Active\n" +
                "📡 Digital Instrument Cluster: BLE GATT Bridge Ready"
            )
            .setPositiveButton("Close", null)
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
    }

    private fun getThemeDefinition(themeKey: String): CockpitTheme {
        return when (themeKey) {
            AppSettings.THEME_AMOLED -> CockpitTheme(
                AppSettings.THEME_AMOLED, "AMOLED Black", "🖤 AMOLED Black",
                backgroundColor = Color.parseColor("#000000"),
                surfaceColor = Color.parseColor("#000000"),
                cardColor = Color.parseColor("#0B0B0E"),
                cardBorderColor = Color.parseColor("#262630"),
                primaryColor = Color.parseColor("#FFFFFF"),
                onPrimaryColor = Color.BLACK,
                accentColor = Color.parseColor("#FFFFFF"),
                subtleColor = Color.parseColor("#141418"),
                borderColor = Color.parseColor("#383844")
            )
            AppSettings.THEME_STEALTH -> CockpitTheme(
                AppSettings.THEME_STEALTH, "Stealth Mono", "⬛ Stealth Mono",
                backgroundColor = Color.parseColor("#131215"),
                surfaceColor = Color.parseColor("#1C1B20"),
                cardColor = Color.parseColor("#222128"),
                cardBorderColor = Color.parseColor("#32303B"),
                primaryColor = Color.parseColor("#475569"),
                onPrimaryColor = Color.WHITE,
                accentColor = Color.parseColor("#94A3B8"),
                subtleColor = Color.parseColor("#1E293B"),
                borderColor = Color.parseColor("#334155")
            )
            AppSettings.THEME_AMBER -> CockpitTheme(
                AppSettings.THEME_AMBER, "Neon Amber", "⚡ Neon Amber",
                backgroundColor = Color.parseColor("#131215"),
                surfaceColor = Color.parseColor("#1C1B20"),
                cardColor = Color.parseColor("#222128"),
                cardBorderColor = Color.parseColor("#32303B"),
                primaryColor = Color.parseColor("#D97706"),
                onPrimaryColor = Color.WHITE,
                accentColor = Color.parseColor("#F59E0B"),
                subtleColor = Color.parseColor("#2E1E08"),
                borderColor = Color.parseColor("#5C3D10")
            )
            AppSettings.THEME_MINT -> CockpitTheme(
                AppSettings.THEME_MINT, "Hyper Mint", "🟢 Hyper Mint",
                backgroundColor = Color.parseColor("#131215"),
                surfaceColor = Color.parseColor("#1C1B20"),
                cardColor = Color.parseColor("#222128"),
                cardBorderColor = Color.parseColor("#32303B"),
                primaryColor = Color.parseColor("#059669"),
                onPrimaryColor = Color.WHITE,
                accentColor = Color.parseColor("#10B981"),
                subtleColor = Color.parseColor("#0D2E21"),
                borderColor = Color.parseColor("#134E35")
            )
            AppSettings.THEME_BLUE -> CockpitTheme(
                AppSettings.THEME_BLUE, "Liquid Blue", "🔵 Liquid Blue",
                backgroundColor = Color.parseColor("#131215"),
                surfaceColor = Color.parseColor("#1C1B20"),
                cardColor = Color.parseColor("#222128"),
                cardBorderColor = Color.parseColor("#32303B"),
                primaryColor = Color.parseColor("#0284C7"),
                onPrimaryColor = Color.WHITE,
                accentColor = Color.parseColor("#38BDF8"),
                subtleColor = Color.parseColor("#0C253B"),
                borderColor = Color.parseColor("#144566")
            )
            else -> CockpitTheme(
                AppSettings.THEME_WINE_RED, "Wine Red", "🍷 Wine Red",
                backgroundColor = Color.parseColor("#131215"),
                surfaceColor = Color.parseColor("#1C1B20"),
                cardColor = Color.parseColor("#222128"),
                cardBorderColor = Color.parseColor("#32303B"),
                primaryColor = Color.parseColor("#9E1B32"),
                onPrimaryColor = Color.WHITE,
                accentColor = Color.parseColor("#C92A45"),
                subtleColor = Color.parseColor("#2C1218"),
                borderColor = Color.parseColor("#541B26")
            )
        }
    }

    private fun setupThemeSelector() {
        binding.cardThemeWineRed.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_WINE_RED)
            showFeedback("🍷 Wine Red theme active")
        }
        binding.cardThemeAmoled.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_AMOLED)
            showFeedback("🖤 AMOLED Pure Black active")
        }
        binding.cardThemeStealth.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_STEALTH)
            showFeedback("⬛ Stealth Mono theme active")
        }
        binding.cardThemeAmber.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_AMBER)
            showFeedback("⚡ Neon Amber theme active")
        }
        binding.cardThemeMint.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_MINT)
            showFeedback("🟢 Hyper Mint theme active")
        }
        binding.cardThemeBlue.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_BLUE)
            showFeedback("🔵 Liquid Blue theme active")
        }
    }

    private fun applyTheme(themeKey: String) {
        val theme = getThemeDefinition(themeKey)
        currentTheme = theme
        appSettings.appTheme = themeKey

        // 1. System Navigation & Status Bars
        window.statusBarColor = theme.backgroundColor
        window.navigationBarColor = theme.backgroundColor

        // 2. Root Canvases, ScrollViews & Dividers
        binding.rootLayout.setBackgroundColor(theme.backgroundColor)
        binding.layoutTopBar.setBackgroundColor(theme.backgroundColor)
        binding.layoutContentFrame.setBackgroundColor(theme.backgroundColor)
        binding.scrollCockpit.setBackgroundColor(theme.backgroundColor)
        binding.scrollFuel.setBackgroundColor(theme.backgroundColor)
        binding.scrollSupport.setBackgroundColor(theme.backgroundColor)
        binding.scrollSettings.setBackgroundColor(theme.backgroundColor)
        binding.scrollTrips.setBackgroundColor(theme.backgroundColor)
        binding.viewTopDivider.setBackgroundColor(theme.cardBorderColor)

        // 3. Content Cards across all Tabs
        val contentCards = listOf(
            binding.cardHeroRange,
            binding.cardConnection,
            binding.cardLastParked,
            binding.cardEmergencyHome,
            binding.cardLatestRide,
            binding.cardLifetimeStatsHome,
            binding.cardQuickHome,
            binding.cardQuickWork,
            binding.cardQuickFuel,
            binding.cardNearbyPumps,
            binding.cardAddFuel,
            binding.cardFuelBreakdown,
            binding.cardFuelLog,
            binding.cardServiceHistory,
            binding.cardServiceCenters,
            binding.cardChainLube,
            binding.cardService,
            binding.cardSupportOptions,
            binding.cardProfile,
            binding.cardMyBike,
            binding.cardOwnership,
            binding.cardHudMirror,
            binding.cardCallerId,
            binding.cardToggles,
            binding.cardQuickDestinations,
            binding.cardAboutLegal,
            binding.cardMonthSoFar,
            binding.cardLifetimeStats,
            binding.cardMetricFuel,
            binding.cardMetricMileage,
            binding.cardMetricSpirited
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
            binding.cardThemeBlue to (themeKey == AppSettings.THEME_BLUE)
        )
        for ((card, isSelected) in themeCards) {
            card.setCardBackgroundColor(theme.cardColor)
            card.strokeColor = if (isSelected) theme.accentColor else theme.cardBorderColor
            card.strokeWidth = if (isSelected) dpToPx(2) else dpToPx(1)
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
        binding.cardQuickFuel.strokeColor = theme.borderColor

        binding.layoutManeuverIcon.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.tvManeuverIcon.setTextColor(theme.accentColor)
        binding.tvDistance.setTextColor(theme.accentColor)
        binding.tvEtaBadge.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.tvEtaBadge.setTextColor(theme.accentColor)

        binding.tvParkedTime.setTextColor(theme.accentColor)
        binding.btnNavigateToBike.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnNavigateToBike.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnNavigateToBike.setTextColor(theme.accentColor)

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

        // Home Lifetime Highlights Banner
        binding.layoutLifetimeStatsHeader.setBackgroundColor(theme.subtleColor)
        binding.tvYouTravelledTitle.setTextColor(theme.accentColor)
        binding.tvLifetimeFuelHome.setTextColor(theme.accentColor)
        binding.btnViewPreviousRides.strokeColor = theme.cardBorderColor
        binding.btnViewAllRidingStats.strokeColor = theme.cardBorderColor
        binding.tvViewPreviousRidesArrow.setTextColor(theme.accentColor)
        binding.tvViewAllRidingStatsArrow.setTextColor(theme.accentColor)

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

        // Riding Stats Elements
        binding.btnBackFromStats.imageTintList = ColorStateList.valueOf(theme.accentColor)
        binding.tvTodayKmBadge.setTextColor(theme.accentColor)
        binding.tvHighlightMileage.setTextColor(theme.accentColor)
        binding.tvHighlightSpiritedRides.setTextColor(theme.accentColor)
        binding.tvHighlightLongestRide.setTextColor(Color.WHITE)
        binding.btnSimulateRide.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnSimulateRide.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnSimulateRide.setTextColor(theme.accentColor)

        binding.tvOdometer.setTextColor(theme.accentColor)
        binding.btnEditOdo.setTextColor(theme.accentColor)
        binding.btnEditOdo.strokeColor = ColorStateList.valueOf(theme.borderColor)

        binding.btnAddManualRide.setTextColor(theme.accentColor)
        binding.btnAddManualRide.strokeColor = ColorStateList.valueOf(theme.borderColor)

        // Support Tab
        binding.tvServiceCentersAction.setTextColor(theme.accentColor)
        binding.tvServiceHistoryAction.setTextColor(theme.accentColor)
        binding.tvServiceRemaining.setTextColor(theme.accentColor)
        binding.progressService.setIndicatorColor(theme.primaryColor)
        binding.progressService.trackColor = theme.cardBorderColor
        binding.btnMarkServiced.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnMarkServiced.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnMarkServiced.setTextColor(theme.accentColor)

        binding.tvChainRemaining.setTextColor(theme.accentColor)
        binding.progressChain.setIndicatorColor(theme.primaryColor)
        binding.progressChain.trackColor = theme.cardBorderColor

        binding.btnMarkChainLubed.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnMarkChainLubed.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnMarkChainLubed.setTextColor(theme.accentColor)

        binding.btnAddFuel.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnAddFuel.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnAddFuel.setTextColor(theme.accentColor)

        // Account Tab
        binding.ivProfileAvatar.imageTintList = ColorStateList.valueOf(theme.accentColor)
        binding.btnEditBikeInfo.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnEditBikeInfo.setTextColor(theme.accentColor)
        binding.tvDocumentsBadge.setTextColor(theme.accentColor)

        binding.btnConfigHome.setTextColor(theme.accentColor)
        binding.btnConfigWork.setTextColor(theme.accentColor)
        binding.btnSimulatePark.setTextColor(theme.accentColor)

        val testButtons = listOf(
            binding.btnTestLeft, binding.btnTestStraight, binding.btnTestRight,
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

        // Refresh stats tabs pill chips with current theme
        selectStatsTab(if (binding.layoutStatsHighlights.visibility == View.VISIBLE) binding.tabStatsHighlights else binding.tabStatsAllRides)

        // Switches
        val switches = listOf(
            binding.switchTwoWheeler,
            binding.switchAutoPark,
            binding.switchRideTracking,
            binding.switchMediaControl,
            binding.switchWhatsAppAlerts,
            binding.switchTelemetry
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
            updateTripList(rideTracker.tripHistory.value)
            updateFuelList(fuelLogManager.entries.value)
        } catch (_: Exception) {}

        updateHeroRangeDisplay()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setupQuickNav() {
        updateQuickNavButtons()

        binding.cardQuickHome.setOnClickListener {
            provideHapticFeedback()
            val addr = appSettings.homeAddress.ifBlank { "Home" }
            launchNavigationTo(addr)
        }
        binding.cardQuickHome.setOnLongClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Home", true)
            true
        }

        binding.cardQuickWork.setOnClickListener {
            provideHapticFeedback()
            val addr = appSettings.workAddress.ifBlank { "Work" }
            launchNavigationTo(addr)
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

    private fun updateQuickNavButtons() {
        val home = appSettings.homeAddress
        val work = appSettings.workAddress

        binding.tvQuickHomeTitle.text = "Home"
        binding.tvQuickWorkTitle.text = "Work"

        binding.tvSettingsHome.text = "🏠 Home: ${if (home.isNotBlank()) home else "Google Maps Saved Home"}"
        binding.tvSettingsWork.text = "🏢 Work: ${if (work.isNotBlank()) work else "Google Maps Saved Work"}"
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

        cardSos108?.strokeColor = currentTheme.cardBorderColor
        cardSos112?.strokeColor = currentTheme.cardBorderColor
        cardSosIce?.strokeColor = currentTheme.cardBorderColor
        cardFindHospitals?.strokeColor = currentTheme.cardBorderColor
        cardMedicalProfile?.strokeColor = currentTheme.cardBorderColor

        val icePhone = appSettings.emergencyContactPhone
        val iceName = appSettings.emergencyContactName
        if (icePhone.isNotBlank() && icePhone != "112") {
            tvSheetIceSubtitle?.text = "$iceName • $icePhone"
        } else {
            tvSheetIceSubtitle?.text = "Tap to configure ICE emergency contact"
        }

        val blood = if (appSettings.riderBloodGroup.isNotBlank() && appSettings.riderBloodGroup != "Not Specified") "🩸 ${appSettings.riderBloodGroup}" else "🩸 Blood Group --"
        tvSheetBloodGroup?.text = blood
        tvSheetBloodGroup?.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
        tvSheetBloodGroup?.setTextColor(currentTheme.accentColor)

        btnEditMedicalProfile?.setTextColor(currentTheme.accentColor)

        val med = appSettings.medicalNotes
        tvSheetMedicalNotes?.text = if (med.isNotBlank() && med != "No known allergies") med else "No allergies or medical notes recorded. Tap Edit to update."

        cardSos108?.setOnClickListener {
            provideHapticFeedback()
            dialEmergencyNumber("108")
        }

        cardSos112?.setOnClickListener {
            provideHapticFeedback()
            dialEmergencyNumber("112")
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
                    vaultFilePicker.launch(arrayOf("*/*"))
                } catch (e: Exception) {
                    showFeedback("File picker unavailable: ${e.message}", isAlert = true)
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

    private fun importVaultFromUri(uri: Uri) {
        try {
            val jsonString = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
            if (jsonString.isNotBlank()) {
                val result = DataVaultManager.getInstance(this).importVaultFromJson(jsonString)
                result.onSuccess { summary ->
                    showFeedback("Vault Restored: ${summary.tripsImported} trips, ${summary.fuelEntriesImported} fuel logs for ${summary.riderName}")
                    refreshAllUiAfterVaultImport()
                }.onFailure { err ->
                    showFeedback("Import failed: ${err.message}", isAlert = true)
                }
            } else {
                showFeedback("Selected vault file is empty", isAlert = true)
            }
        } catch (e: Exception) {
            showFeedback("Error reading file: ${e.message}", isAlert = true)
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
                            showFeedback("✅ $msg")
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
        updateBikeInfoDisplay()
        updateDocumentsSummary()
        updateServiceHistorySummary()
        updateHeroRangeDisplay()
        updateEmergencyHomeCard()
        updateOwnershipDays()
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
                showFeedback("Could not open Google Maps navigation", isAlert = true)
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
            .setTitle("🧭 Navigation Interceptor Access")
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

        val tvIcon = sheetView.findViewById<TextView>(R.id.tvSheetIcon)
        val tvTitle = sheetView.findViewById<TextView>(R.id.tvSheetTitle)
        val tvOptionMapsTitle = sheetView.findViewById<TextView>(R.id.tvOptionMapsTitle)
        val cardOptionMapsSaved = sheetView.findViewById<MaterialCardView>(R.id.cardOptionMapsSaved)
        val cardOptionPinGps = sheetView.findViewById<MaterialCardView>(R.id.cardOptionPinGps)
        val cardOptionCustom = sheetView.findViewById<MaterialCardView>(R.id.cardOptionCustom)
        val etCustom = sheetView.findViewById<EditText>(R.id.etCustomAddress)
        val btnSaveCustom = sheetView.findViewById<MaterialButton>(R.id.btnSaveCustomAddress)
        val btnDismiss = sheetView.findViewById<MaterialButton>(R.id.btnDismissSheet)

        tvIcon.text = if (isHome) "🏠" else "🏢"
        tvTitle.text = "Set $label Destination"
        tvOptionMapsTitle.text = "Google Maps Saved $label"

        val current = if (isHome) appSettings.homeAddress else appSettings.workAddress
        if (current.isNotBlank() && current != "Home" && current != "Work") {
            etCustom.setText(current)
        }

        btnSaveCustom.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
        btnSaveCustom.setTextColor(currentTheme.onPrimaryColor)
        cardOptionMapsSaved.strokeColor = currentTheme.cardBorderColor
        cardOptionPinGps.strokeColor = currentTheme.cardBorderColor
        cardOptionCustom.strokeColor = currentTheme.cardBorderColor

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
        val input = EditText(this).apply {
            setText("%.1f".format(current))
            hint = "Odometer in km"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(40, 30, 40, 30)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
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

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvDesc = TextView(this).apply {
            text = "Easy Refuel Wizard • Petrol Rate: ₹%.1f/L\nTap a ₹ preset to auto-compute litres:".format(petrolRate)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvDesc)

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        val odoInput = EditText(this).apply {
            hint = "Current Odometer (km)"
            setText("%.1f".format(currentOdo))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val costInput = EditText(this).apply {
            hint = "Total Paid ₹ (e.g. 500)"
            setText("500")
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val litersInput = EditText(this).apply {
            hint = "Fuel Litres"
            setText("%.2f".format(500.0 / petrolRate))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

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

        presetRow.addView(createPresetButton("₹200", 200.0))
        presetRow.addView(createPresetButton("₹500", 500.0))
        presetRow.addView(createPresetButton("₹1000", 1000.0))
        presetRow.addView(createPresetButton("Full ⛽", 0.0, isFull = true))
        layout.addView(presetRow)

        val tvOdoLabel = TextView(this).apply {
            text = "Odometer (km):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
        }
        layout.addView(tvOdoLabel)
        layout.addView(odoInput)

        val tvCostLabel = TextView(this).apply {
            text = "Total Cost (₹):"
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
            .setTitle("⛽ Log Refuel")
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
                    showFeedback("Logged %.2f L for ₹%.0f%s".format(liters, cost, kmplMsg))
                } else {
                    showFeedback("Please enter valid fuel details", isAlert = true)
                }
            }
            .setNeutralButton("Price: ₹%.1f/L".format(petrolRate)) { _, _ ->
                showEditPetrolPriceDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditPetrolPriceDialog() {
        val input = EditText(this).apply {
            setText("%.1f".format(appSettings.petrolPricePerLiter))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(40, 30, 40, 30)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val container = FrameLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Set Local Petrol Price (₹ / Litre)")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val p = input.text.toString().toDoubleOrNull()
                if (p != null && p > 50.0 && p < 200.0) {
                    appSettings.petrolPricePerLiter = p
                    showFeedback("Petrol price set to ₹%.1f/L".format(p))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAndStartScan(silent: Boolean = false) {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        required.add(Manifest.permission.READ_PHONE_STATE)
        required.add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            required.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            bleManager.startScan()
        } else if (!silent) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun observeBleState() {
        lifecycleScope.launch {
            bleManager.connectionState.collectLatest { state ->
                when (state) {
                    is BleConnectionState.Disconnected -> {
                        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(currentTheme.accentColor)
                        binding.tvConnectionStatus.text = "Disconnected"
                        binding.tvSubStatus.text = "Auto-reconnects when ignition turns ON"
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
                        binding.tvSubStatus.text = "Looking for Pulsar NS200 cluster"
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
                        binding.tvSubStatus.text = state.deviceName
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
                        binding.tvSubStatus.text = "Cluster synced (${state.address})"
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
                    val street = step.streetName.ifBlank { step.maneuver.description }
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
                        binding.tvCallerStatus.text = "📞 Incoming Call: ${call.callerName ?: "Unknown"} (${call.callerNumber.orEmpty()})"
                        binding.tvCallerStatus.setTextColor(currentTheme.accentColor)
                    }
                    CallState.ACTIVE_CALL -> {
                        binding.tvCallerStatus.text = "🟢 Active Call: ${call.callerName ?: "In Call"}"
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
                    binding.tvMediaBanner.text = "🎵 Media Control: $event"
                    binding.tvMediaBanner.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                } else {
                    binding.tvMediaBanner.text = "Handlebar Button: Click = Accept/Play • Hold = Reject/Next"
                    binding.tvMediaBanner.setTextColor(currentTheme.accentColor)
                }
            }
        }

        lifecycleScope.launch {
            GoogleMapsNotificationListener.lastWhatsAppAlert.collectLatest { alert ->
                if (alert != null) {
                    binding.tvWhatsAppAlertBanner.text = "💬 WhatsApp Alert: ${alert.sender} (Showing on cluster)"
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

    private fun observeParking() {
        lifecycleScope.launch {
            parkingManager.lastParkedLocation.collectLatest { loc ->
                if (loc != null) {
                    binding.tvParkedAddress.text = loc.address
                    binding.tvParkedTime.text = parkingManager.getElapsedParkedTimeFormatted()
                    binding.btnNavigateToBike.isEnabled = true
                } else {
                    binding.tvParkedAddress.text = "Turn off bike ignition to auto-save parking spot"
                    binding.tvParkedTime.text = "-- Not recorded"
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeTrips() {
        lifecycleScope.launch {
            rideTracker.lifetimeStats.collectLatest { stats ->
                binding.tvLifetimeRides.text = stats.totalTrips.toString()
                binding.tvLifetimeDistanceHome.text = "%.0f km".format(stats.totalDistanceKm)
            }
        }

        lifecycleScope.launch {
            rideTracker.tripHistory.collectLatest { trips ->
                updateTripList(trips)
                if (trips.isNotEmpty()) {
                    binding.cardLatestRide.visibility = View.VISIBLE
                    val latest = trips[0]
                    binding.tvLatestDuration.text = latest.formattedDuration
                    binding.tvLatestDistance.text = "%.1f km".format(latest.distanceKm)
                    val fuelStats = fuelLogManager.stats.value
                    binding.tvLatestEfficiency.text = if (fuelStats.averageKmpl > 0.0) "%.1f km/l".format(fuelStats.averageKmpl) else "-- km/l"
                    binding.tvLatestOrigin.text = latest.routeTitle
                    binding.tvLatestDest.text = "Destination • ${latest.formattedDate}"
                } else {
                    binding.cardLatestRide.visibility = View.GONE
                }
                updateHighlightsAndMonthlyStats(trips)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateHighlightsAndMonthlyStats(trips: List<Trip>) {
        val longestKm = trips.maxOfOrNull { it.distanceKm } ?: 0.0
        binding.tvHighlightLongestRide.text = if (longestKm > 0.0) "%.1f km".format(longestKm) else "-- km"

        val longestCruiseSec = trips.maxOfOrNull { it.durationSec } ?: 0L
        val longestMins = longestCruiseSec / 60
        binding.tvLifetimeLongestCruise.text = if (longestMins > 0L) "$longestMins mins" else "-- mins"

        val topSpeed = trips.maxOfOrNull { it.topSpeedKmh } ?: 0.0
        binding.tvLifetimeTopSpeed.text = if (topSpeed > 0.0) "%.0f km/h".format(topSpeed) else "-- km/h"

        val spiritedCount = trips.count { it.topSpeedKmh >= 70.0 }
        binding.tvHighlightSpiritedRides.text = spiritedCount.toString()

        val cal = Calendar.getInstance()
        val curMonth = cal.get(Calendar.MONTH)
        val curYear = cal.get(Calendar.YEAR)
        val todayDay = cal.get(Calendar.DAY_OF_YEAR)

        val monthTrips = trips.filter { t ->
            val c = Calendar.getInstance().apply { timeInMillis = t.startTimeMs }
            c.get(Calendar.MONTH) == curMonth && c.get(Calendar.YEAR) == curYear
        }
        val monthKm = monthTrips.sumOf { it.distanceKm }
        binding.tvMonthDistance.text = "%.0f km".format(monthKm)

        val activeDays = monthTrips.map { t ->
            Calendar.getInstance().apply { timeInMillis = t.startTimeMs }.get(Calendar.DAY_OF_MONTH)
        }.distinct().size
        binding.tvMonthDays.text = if (activeDays > 0) "in $activeDays days" else "this month"

        val todayTrips = trips.filter { t ->
            val c = Calendar.getInstance().apply { timeInMillis = t.startTimeMs }
            c.get(Calendar.DAY_OF_YEAR) == todayDay && c.get(Calendar.YEAR) == curYear
        }
        val todayKm = todayTrips.sumOf { it.distanceKm }
        binding.tvTodayKmBadge.text = "%.0f km today".format(todayKm)

        // Populate 7-day bar chart dynamically
        binding.chartBarContainer.removeAllViews()
        val dayTotals = DoubleArray(7)
        val now = Calendar.getInstance()
        for (i in 6 downTo 0) {
            val dCal = Calendar.getInstance().apply {
                timeInMillis = now.timeInMillis
                add(Calendar.DAY_OF_YEAR, -i)
            }
            val dDay = dCal.get(Calendar.DAY_OF_YEAR)
            val dYr = dCal.get(Calendar.YEAR)
            val km = trips.filter { t ->
                val c = Calendar.getInstance().apply { timeInMillis = t.startTimeMs }
                c.get(Calendar.DAY_OF_YEAR) == dDay && c.get(Calendar.YEAR) == dYr
            }.sumOf { it.distanceKm }
            dayTotals[6 - i] = km
        }

        val maxDayKm = dayTotals.maxOrNull() ?: 0.0
        for (i in 0 until 7) {
            val km = dayTotals[i]
            val isToday = (i == 6)
            val heightDp = if (maxDayKm > 0.0) {
                (12 + (km / maxDayKm * 68)).toInt()
            } else {
                8
            }
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(14), dpToPx(heightDp)).apply {
                    setMargins(dpToPx(6), 0, dpToPx(6), 0)
                }
                setBackgroundResource(R.drawable.pill_badge)
                val color = when {
                    isToday && km > 0.0 -> Color.parseColor("#F59E0B")
                    km > 0.0 -> Color.WHITE
                    else -> ContextCompat.getColor(this@MainActivity, R.color.surface_elevated)
                }
                backgroundTintList = ColorStateList.valueOf(color)
            }
            binding.chartBarContainer.addView(bar)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateTripList(trips: List<Trip>) {
        binding.layoutTripList.removeAllViews()
        if (trips.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No recorded trips yet. Rides will automatically appear here."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                textSize = 13f
                setPadding(10, 20, 10, 20)
            }
            binding.layoutTripList.addView(emptyView)
            return
        }

        for (trip in trips) {
            val view = LayoutInflater.from(this).inflate(R.layout.item_trip, binding.layoutTripList, false)
            (view as? com.google.android.material.card.MaterialCardView)?.let { card ->
                card.setCardBackgroundColor(currentTheme.cardColor)
                card.strokeColor = currentTheme.cardBorderColor
            }
            view.findViewById<TextView>(R.id.tvTripTitle).text = trip.routeTitle
            view.findViewById<TextView>(R.id.tvTripDate).text = trip.formattedDate
            val tvDist = view.findViewById<TextView>(R.id.tvTripDistance)
            tvDist.text = "%.1f km".format(trip.distanceKm)
            tvDist.setTextColor(currentTheme.accentColor)
            view.findViewById<TextView>(R.id.tvTripDuration).text = trip.formattedDuration
            view.findViewById<TextView>(R.id.tvTripSpeeds).text = "%.0f / %.0f km/h".format(trip.avgSpeedKmh, trip.topSpeedKmh)

            val btnEdit = view.findViewById<MaterialButton>(R.id.btnEditTrip)
            btnEdit.setTextColor(currentTheme.accentColor)
            btnEdit.strokeColor = ColorStateList.valueOf(currentTheme.borderColor)
            btnEdit.setOnClickListener {
                provideHapticFeedback()
                showEditTripDialog(trip)
            }

            view.setOnClickListener {
                provideHapticFeedback()
                showEditTripDialog(trip)
            }

            binding.layoutTripList.addView(view)
        }
    }

    private fun showEditTripDialog(trip: Trip) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvInfo = TextView(this).apply {
            text = "Recorded on ${trip.formattedDate}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        val tvTitleLabel = TextView(this).apply {
            text = "Route Title / Name:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
        }
        val etTitle = EditText(this).apply {
            setText(trip.routeTitle)
            hint = "e.g. Work Commute"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        layout.addView(tvTitleLabel)
        layout.addView(etTitle)

        val tvDistLabel = TextView(this).apply {
            text = "Distance (km):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
            setPadding(0, 12, 0, 0)
        }
        val etDistance = EditText(this).apply {
            setText("%.1f".format(trip.distanceKm))
            hint = "e.g. 14.2"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(tvDistLabel)
        layout.addView(etDistance)

        val tvDurationLabel = TextView(this).apply {
            text = "Duration (minutes):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
            setPadding(0, 12, 0, 0)
        }
        val etDuration = EditText(this).apply {
            val mins = (trip.durationSec / 60).coerceAtLeast(1)
            setText(mins.toString())
            hint = "e.g. 28"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(tvDurationLabel)
        layout.addView(etDuration)

        val tvSpeedLabel = TextView(this).apply {
            text = "Top Speed (km/h):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
            setPadding(0, 12, 0, 0)
        }
        val etSpeed = EditText(this).apply {
            setText("%.0f".format(trip.topSpeedKmh))
            hint = "e.g. 84"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(tvSpeedLabel)
        layout.addView(etSpeed)

        MaterialAlertDialogBuilder(this)
            .setTitle("✏️ Edit Trip Details")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = etTitle.text.toString().trim().ifBlank { "Pulsar Ride" }
                val newDist = etDistance.text.toString().toDoubleOrNull() ?: trip.distanceKm
                val newMins = etDuration.text.toString().toLongOrNull() ?: (trip.durationSec / 60)
                val newSecs = (newMins * 60).coerceAtLeast(1)
                val newSpeed = etSpeed.text.toString().toDoubleOrNull() ?: trip.topSpeedKmh

                val updated = rideTracker.updateTrip(
                    id = trip.id,
                    distanceKm = newDist,
                    durationSec = newSecs,
                    routeTitle = newTitle,
                    topSpeedKmh = newSpeed
                )

                if (updated) {
                    provideHapticFeedback()
                    recordSyncTime()
                    showFeedback("Trip details updated successfully")
                }
            }
            .setNeutralButton("Delete") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Ride Record?")
                    .setMessage("Are you sure you want to delete this trip record? This action cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        provideHapticFeedback()
                        rideTracker.deleteTrip(trip.id)
                        recordSyncTime()
                        showFeedback("Trip deleted")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun observeMaintenance() {
        lifecycleScope.launch {
            maintenanceManager.status.collectLatest { s ->
                binding.tvOdometer.text = "%.1f km".format(s.totalOdometerKm)
                binding.tvChainRemaining.text = "%.0f km left".format(s.chainLubeRemainingKm)
                binding.progressChain.progress = s.chainLubePercent

                binding.tvServiceRemaining.text = "%.0f km left".format(s.serviceRemainingKm)
                binding.progressService.progress = s.servicePercent

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
                if (stats.averageKmpl > 0.0) {
                    binding.tvAvgMileage.text = "%.1f km/l".format(stats.averageKmpl)
                    binding.tvHighlightMileage.text = "%.1f".format(stats.averageKmpl)
                } else {
                    binding.tvAvgMileage.text = "-- km/l"
                    binding.tvHighlightMileage.text = "--"
                }
                if (stats.costPerKm > 0.0) {
                    binding.tvCostPerKm.text = "₹%.1f / km".format(stats.costPerKm)
                } else {
                    binding.tvCostPerKm.text = "-- / km"
                }
            }
        }

        lifecycleScope.launch {
            fuelLogManager.entries.collectLatest { entries ->
                updateFuelList(entries)
                val totalLiters = entries.sumOf { it.liters }
                val totalCost = entries.sumOf { it.totalCostInr }
                binding.tvLifetimeFuelHome.text = "%.0f L • ₹%.0f spent".format(totalLiters, totalCost)
                binding.tvHighlightFuelUnits.text = if (totalLiters > 0.0) "%.0f L".format(totalLiters) else "-- L"
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
            view.findViewById<TextView>(R.id.tvFuelOdo).text = "Odo: %.1f km • %.1f L ✏️".format(entry.odometerKm, entry.liters)
            val tvCost = view.findViewById<TextView>(R.id.tvFuelCost)
            tvCost.text = "₹%.0f".format(entry.totalCostInr)
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

        val odoInput = EditText(this).apply {
            hint = "Odometer (km)"
            setText("%.1f".format(entry.odometerKm))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val litersInput = EditText(this).apply {
            hint = "Fuel Litres"
            setText("%.2f".format(entry.liters))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val costInput = EditText(this).apply {
            hint = "Total Cost (₹)"
            setText("%.0f".format(entry.totalCostInr))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
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

        addField("Odometer (km):", odoInput)
        addField("Fuel Litres:", litersInput)
        addField("Total Amount Paid (₹):", costInput)

        MaterialAlertDialogBuilder(this)
            .setTitle("✏️ Edit Refuel Entry")
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

    private fun updateOwnershipDays() {
        binding.tvDaysOfOwnership.text = "${appSettings.daysOfOwnership} Days 📅"
        binding.tvPurchaseDateSubtext.text = "Purchased: ${appSettings.formattedPurchaseDate}"
    }

    private fun showEditOwnershipDaysDialog() {
        provideHapticFeedback()
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = appSettings.purchaseDateMs
        }
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH)
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)

        val datePicker = android.app.DatePickerDialog(
            this,
            { _, selectedYear, selectedMonth, selectedDay ->
                val newCal = java.util.Calendar.getInstance().apply {
                    set(selectedYear, selectedMonth, selectedDay, 12, 0, 0)
                }
                appSettings.purchaseDateMs = newCal.timeInMillis
                updateOwnershipDays()
                recordSyncTime()
                provideHapticFeedback()
                showFeedback("Purchase date set to ${appSettings.formattedPurchaseDate} (${appSettings.daysOfOwnership} days of ownership)")
            },
            year,
            month,
            day
        )
        datePicker.datePicker.maxDate = System.currentTimeMillis()
        datePicker.setTitle("🗓️ Select Delivery / Purchase Date")
        datePicker.show()
    }

    private fun showAddManualRideDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvHint = TextView(this).apply {
            text = "Record a ride logged without phone navigation (e.g. local drop-off or neighborhood spin):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvHint)

        val titleInput = EditText(this).apply {
            hint = "Trip Route / Title (e.g. Local Market Run)"
            setText("Local Drop-off")
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val distInput = EditText(this).apply {
            hint = "Distance in km (e.g. 4.5)"
            setText("4.5")
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val durInput = EditText(this).apply {
            hint = "Duration in minutes (e.g. 15)"
            setText("15")
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val speedInput = EditText(this).apply {
            hint = "Top speed in km/h (optional, e.g. 60)"
            setText("58")
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val cbOdo = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Add distance to motorcycle odometer"
            isChecked = true
            textSize = 12f
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

        addField("Route Title:", titleInput)
        addField("Distance (km):", distInput)
        addField("Duration (Minutes):", durInput)
        addField("Top Speed (km/h):", speedInput)
        layout.addView(cbOdo)

        val scrollView = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle("🛵 Log Past / Manual Ride")
            .setView(scrollView)
            .setPositiveButton("Save Ride") { _, _ ->
                val dist = distInput.text.toString().toDoubleOrNull() ?: 0.0
                val durMin = durInput.text.toString().toLongOrNull() ?: 0L
                val topSpeed = speedInput.text.toString().toDoubleOrNull() ?: 0.0
                val title = titleInput.text.toString().trim().ifBlank { "Local Ride" }

                if (dist > 0 && durMin > 0) {
                    val durSec = durMin * 60
                    val avgSpeed = (dist / (durMin / 60.0)).coerceAtLeast(0.0)
                    rideTracker.addManualTrip(dist, durSec, topSpeed.coerceAtLeast(avgSpeed), avgSpeed, title)
                    if (cbOdo.isChecked) {
                        val newOdo = maintenanceManager.status.value.totalOdometerKm + dist
                        maintenanceManager.setOdometer(newOdo)
                    }
                    recordSyncTime()
                    provideHapticFeedback()
                    showFeedback("Logged \"$title\" (%.1f km, %d mins)".format(dist, durMin))
                } else {
                    showFeedback("Please enter valid distance and duration", isAlert = true)
                }
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

        val iceNameInput = EditText(this).apply {
            hint = "ICE Contact Name / Relation"
            setText(appSettings.emergencyContactName)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
        }
        val icePhoneInput = EditText(this).apply {
            hint = "ICE Phone Number"
            setText(appSettings.emergencyContactPhone)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val notesInput = EditText(this).apply {
            hint = "Medical Allergies / Conditions"
            setText(appSettings.medicalNotes)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
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
            .setTitle("🩸 Medical & Emergency Profile")
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
        val blood = if (appSettings.riderBloodGroup.isNotBlank() && appSettings.riderBloodGroup != "Not Specified") "🩸 ${appSettings.riderBloodGroup}" else "🩸 Blood Group --"
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
            Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT -> "🔄"
            Maneuver.DESTINATION_REACHED -> "🏁"
            else -> "⬆"
        }
    }
}
