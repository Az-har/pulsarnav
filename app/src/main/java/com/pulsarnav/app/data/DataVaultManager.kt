package com.pulsarnav.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.pulsarnav.app.location.ParkingManager
import com.pulsarnav.app.maintenance.FuelEntry
import com.pulsarnav.app.maintenance.FuelLogManager
import com.pulsarnav.app.maintenance.MaintenanceManager
import com.pulsarnav.app.maintenance.ServiceRecord
import com.pulsarnav.app.tracking.RideTracker
import com.pulsarnav.app.tracking.Trip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.EnumMap
import java.util.Locale

data class VaultImportSummary(
    val riderName: String,
    val bikeName: String,
    val tripsImported: Int,
    val fuelEntriesImported: Int,
    val serviceRecordsImported: Int,
    val totalOdometerKm: Double
)

class VaultPassphraseRequiredException : Exception("This .pnav backup is encrypted. Enter your passphrase to decrypt.")

class DataVaultManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DataVaultManager"
        const val VAULT_SCHEMA_VERSION = 2
        const val QR_MAGIC_HEADER = "PNAV_SETUP"
        private val EXCLUDED_SETTINGS_KEYS = setOf(
            AppSettings.KEY_DOC_RC_PATH,
            AppSettings.KEY_DOC_INSURANCE_PATH,
            AppSettings.KEY_DOC_PUC_PATH
        )

        @Volatile
        private var instance: DataVaultManager? = null

        fun getInstance(context: Context): DataVaultManager {
            return instance ?: synchronized(this) {
                instance ?: DataVaultManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val settings = AppSettings.getInstance(context)
    private val fuelManager = FuelLogManager.getInstance(context)
    private val rideTracker = RideTracker.getInstance(context)
    private val maintenanceManager = MaintenanceManager.getInstance(context)
    private val parkingManager = ParkingManager.getInstance(context)

    // =========================================================================
    // 1. FULL VAULT EXPORT (JSON BUNDLE & SHARE INTENT)
    // =========================================================================

    /**
     * Serializes all 5 data stores into a standardized, versioned JSON vault string.
     */
    fun exportVaultJson(): String {
        val root = JSONObject()

        // Vault Header
        val header = JSONObject().apply {
            put("schemaVersion", VAULT_SCHEMA_VERSION)
            put("appVersion", com.pulsarnav.app.BuildConfig.VERSION_NAME)
            put("exportTimestampMs", System.currentTimeMillis())
            put("deviceModel", Build.MODEL ?: "Android")
        }
        root.put("header", header)

        // 1. AppSettings (exclude device-local filesystem paths)
        val settingsObj = JSONObject()
        for ((key, value) in settings.getAllSettingsMap()) {
            if (value != null && key !in EXCLUDED_SETTINGS_KEYS) {
                settingsObj.put(key, value)
            }
        }
        root.put("settings", settingsObj)

        // 2. Fuel Log Entries
        val fuelArray = JSONArray()
        for (f in fuelManager.entries.value) {
            val fObj = JSONObject().apply {
                put("id", f.id)
                put("timestampMs", f.timestampMs)
                put("odometerKm", f.odometerKm)
                put("liters", f.liters)
                put("totalCostInr", f.totalCostInr)
                put("isFullTank", f.isFullTank)
                if (f.calculatedKmpl != null) put("calculatedKmpl", f.calculatedKmpl)
            }
            fuelArray.put(fObj)
        }
        root.put("fuel_entries", fuelArray)

        // 3. Trip Records
        val tripArray = JSONArray()
        for (t in rideTracker.tripHistory.value) {
            val tObj = JSONObject().apply {
                put("id", t.id)
                put("startTimeMs", t.startTimeMs)
                put("endTimeMs", t.endTimeMs)
                put("distanceKm", t.distanceKm)
                put("durationSec", t.durationSec)
                put("topSpeedKmh", t.topSpeedKmh)
                put("avgSpeedKmh", t.avgSpeedKmh)
                put("routeTitle", t.routeTitle)
            }
            tripArray.put(tObj)
        }
        root.put("trips", tripArray)

        // 4. Service History & Maintenance Status
        val serviceArray = JSONArray()
        for (s in maintenanceManager.getServiceHistory()) {
            val sObj = JSONObject().apply {
                put("id", s.id)
                put("date", s.date)
                put("odoKm", s.odoKm)
                put("title", s.title)
                put("workshop", s.workshop)
                put("notes", s.notes)
                put("cost", s.cost)
                val itemsArr = JSONArray()
                for (item in s.items) {
                    itemsArr.put(JSONObject().apply {
                        put("name", item.name)
                        put("cost", item.cost)
                    })
                }
                put("items", itemsArr)
            }
            serviceArray.put(sObj)
        }
        root.put("service_records", serviceArray)

        val maintObj = JSONObject().apply {
            put("totalOdometerKm", maintenanceManager.status.value.totalOdometerKm)
            put("lastChainLubeKm", maintenanceManager.getLastChainLubeKm())
            put("lastServiceKm", maintenanceManager.getLastServiceKm())
            put("nextServiceTargetOdoKm", maintenanceManager.getNextServiceTargetOdoKm())
        }
        root.put("maintenance", maintObj)

        // 5. Last Parked Location
        parkingManager.lastParkedLocation.value?.let { p ->
            val parkObj = JSONObject().apply {
                put("lat", p.latitude)
                put("lng", p.longitude)
                put("address", p.address)
                put("timestampMs", p.timestampMs)
            }
            root.put("parking", parkObj)
        }

        return root.toString(2)
    }

    /**
     * Writes the vault to a timestamped `.pnav` (AES-256-GCM encrypted when passphrase supplied)
     * or `.pnav.json` file in cacheDir/vault/, pruning stale cached exports first.
     */
    fun exportVaultToFile(passphrase: String? = null): File {
        val vaultDir = File(context.cacheDir, "vault").apply { mkdirs() }
        vaultDir.listFiles()?.forEach { oldFile ->
            if (oldFile.isFile) oldFile.delete()
        }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val json = exportVaultJson()
        val encrypted = !passphrase.isNullOrBlank()
        val payload = if (encrypted) VaultCrypto.encrypt(json, passphrase!!) else json
        val ext = if (encrypted) "pnav" else "pnav.json"
        val file = File(vaultDir, "pulsarnav_vault_$timeStamp.$ext")
        FileOutputStream(file).use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
        }
        return file
    }

    /**
     * Builds an Android Intent for Quick Share, Drive, Files, or WhatsApp.
     */
    fun createVaultShareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = if (file.name.endsWith(".pnav", ignoreCase = true)) {
            "application/octet-stream"
        } else {
            "application/json"
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PulsarNav Data Vault Backup")
            putExtra(
                Intent.EXTRA_TEXT,
                "PulsarNav Data Vault backup for ${settings.bikeName}. Open this file in PulsarNav to restore."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(shareIntent, "Transfer Data Vault (Quick Share / Drive)")
    }

    // =========================================================================
    // 2. FULL VAULT IMPORT & RESTORE
    // =========================================================================

    fun isEncryptedBackup(rawContent: String): Boolean = VaultCrypto.isEncryptedPayload(rawContent)

    /**
     * Imports either an AES-256-GCM encrypted `.pnav` backup (using `passphrase`)
     * or a legacy plaintext `.pnav.json` backup.
     */
    fun importVaultFromInput(rawContent: String, passphrase: String? = null): Result<VaultImportSummary> {
        if (VaultCrypto.isEncryptedPayload(rawContent)) {
            if (passphrase.isNullOrBlank()) {
                return Result.failure(VaultPassphraseRequiredException())
            }
            val decrypted = VaultCrypto.decrypt(rawContent, passphrase).getOrElse { err ->
                return Result.failure(err)
            }
            return importVaultFromJson(decrypted)
        }
        return importVaultFromJson(rawContent)
    }

    /**
     * Restores all data from a plaintext vault JSON string.
     */
    fun importVaultFromJson(jsonString: String): Result<VaultImportSummary> {
        return try {
            val root = JSONObject(jsonString)

            // Validate header / content
            val header = root.optJSONObject("header")
            val schema = header?.optInt("schemaVersion", 1) ?: 1
            if (schema > 10) {
                Log.w(TAG, "Importing vault with higher schema version: $schema")
            }
            if (!root.has("settings") && !root.has("trips") && !root.has("fuel_entries") && !root.has("service_records") && !root.has("maintenance")) {
                return Result.failure(IllegalArgumentException("Invalid or unrecognized PulsarNav backup file."))
            }

            // 1. Restore AppSettings
            val settingsObj = root.optJSONObject("settings")
            if (settingsObj != null) {
                val map = mutableMapOf<String, Any>()
                val keys = settingsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k !in EXCLUDED_SETTINGS_KEYS) {
                        map[k] = settingsObj.get(k)
                    }
                }
                settings.restoreSettingsMap(map)
            }

            // 2. Restore Fuel Log Entries
            val fuelArray = root.optJSONArray("fuel_entries")
            var fuelCount = 0
            if (fuelArray != null) {
                val importedFuel = mutableListOf<FuelEntry>()
                for (i in 0 until fuelArray.length()) {
                    val o = fuelArray.getJSONObject(i)
                    importedFuel.add(
                        FuelEntry(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            timestampMs = o.optLong("timestampMs", System.currentTimeMillis()),
                            odometerKm = o.optDouble("odometerKm", 0.0),
                            liters = o.optDouble("liters", 0.0),
                            totalCostInr = o.optDouble("totalCostInr", 0.0),
                            calculatedKmpl = if (o.has("calculatedKmpl") && !o.isNull("calculatedKmpl")) o.optDouble("calculatedKmpl") else null,
                            isFullTank = o.optBoolean("isFullTank", true)
                        )
                    )
                }
                fuelCount = fuelManager.importFuelEntries(importedFuel)
            }

            // 3. Restore Trips
            val tripArray = root.optJSONArray("trips")
            var tripCount = 0
            if (tripArray != null) {
                val importedTrips = mutableListOf<Trip>()
                for (i in 0 until tripArray.length()) {
                    val o = tripArray.getJSONObject(i)
                    importedTrips.add(
                        Trip(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            startTimeMs = o.optLong("startTimeMs", System.currentTimeMillis()),
                            endTimeMs = o.optLong("endTimeMs", System.currentTimeMillis()),
                            distanceKm = o.optDouble("distanceKm", 0.0),
                            durationSec = o.optLong("durationSec", 0L),
                            topSpeedKmh = o.optDouble("topSpeedKmh", 0.0),
                            avgSpeedKmh = o.optDouble("avgSpeedKmh", 0.0),
                            routeTitle = o.optString("routeTitle", "Pulsar Ride")
                        )
                    )
                }
                tripCount = rideTracker.importTrips(importedTrips)
            }

            // 4. Restore Service History & Odometer
            var serviceCount = 0
            val serviceArray = root.optJSONArray("service_records")
            if (serviceArray != null) {
                val importedServices = mutableListOf<ServiceRecord>()
                for (i in 0 until serviceArray.length()) {
                    val o = serviceArray.getJSONObject(i)
                    val itemsList = mutableListOf<com.pulsarnav.app.maintenance.ServiceItem>()
                    val itemsArr = o.optJSONArray("items")
                    if (itemsArr != null) {
                        for (j in 0 until itemsArr.length()) {
                            val itmObj = itemsArr.getJSONObject(j)
                            itemsList.add(
                                com.pulsarnav.app.maintenance.ServiceItem(
                                    name = itmObj.optString("name", ""),
                                    cost = itmObj.optDouble("cost", 0.0)
                                )
                            )
                        }
                    }
                    importedServices.add(
                        ServiceRecord(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            date = o.optString("date", "Today"),
                            odoKm = o.optDouble("odoKm", 0.0),
                            title = o.optString("title", "Periodic Service"),
                            workshop = o.optString("workshop", "Bajaj Service"),
                            notes = o.optString("notes", ""),
                            cost = o.optString("cost", "₹0"),
                            items = itemsList
                        )
                    )
                }
                val currentServices = maintenanceManager.getServiceHistory()
                val existingIds = currentServices.map { it.id }.toSet()
                for (s in importedServices) {
                    if (s.id !in existingIds) {
                        currentServices.add(s)
                        serviceCount++
                    }
                }
                if (serviceCount > 0) {
                    maintenanceManager.saveServiceHistory(currentServices)
                }
            }

            val maintObj = root.optJSONObject("maintenance")
            if (maintObj != null) {
                val totalOdo = maintObj.optDouble("totalOdometerKm", -1.0)
                if (totalOdo >= 0.0) {
                    maintenanceManager.setOdometer(totalOdo)
                }
                val lastLube = maintObj.optDouble("lastChainLubeKm", -1.0)
                if (lastLube >= 0.0) {
                    maintenanceManager.setLastChainLubeKm(lastLube)
                }
                val lastServ = maintObj.optDouble("lastServiceKm", -1.0)
                if (lastServ >= 0.0) {
                    maintenanceManager.setLastServiceKm(lastServ)
                }
                val targetOdo = maintObj.optDouble("nextServiceTargetOdoKm", -1.0)
                if (targetOdo > 0.0) {
                    maintenanceManager.setNextServiceTargetOdo(targetOdo)
                }
            }

            // 5. Restore Last Parked Location
            val parkObj = root.optJSONObject("parking")
            if (parkObj != null && parkObj.has("lat") && parkObj.has("lng")) {
                val addr: String? = if (parkObj.has("address") && !parkObj.isNull("address")) parkObj.getString("address") else null
                parkingManager.saveLocation(
                    lat = parkObj.optDouble("lat", 0.0),
                    lng = parkObj.optDouble("lng", 0.0),
                    customAddress = addr
                )
            }

            settings.isSetupCompleted = true
            val displayRiderName = settings.riderName.ifBlank { settings.bikeName.ifBlank { "Active Rider" } }
            Result.success(
                VaultImportSummary(
                    riderName = displayRiderName,
                    bikeName = settings.bikeName,
                    tripsImported = tripCount,
                    fuelEntriesImported = fuelCount,
                    serviceRecordsImported = serviceCount,
                    totalOdometerKm = maintenanceManager.status.value.totalOdometerKm
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =========================================================================
    // 3. COMPACT QR CODE MIGRATION (INSTANT SETUP SCAN)
    // =========================================================================

    /**
     * Generates a compact payload representing essential setup credentials.
     */
    fun createSetupQrPayload(): String {
        val obj = JSONObject().apply {
            put("m", QR_MAGIC_HEADER)
            put("r", settings.riderName)
            put("p", settings.riderPhone)
            put("bg", settings.riderBloodGroup)
            put("in", settings.emergencyContactName)
            put("ip", settings.emergencyContactPhone)
            put("mn", settings.medicalNotes)
            put("bn", settings.bikeName)
            put("mac", settings.lastConnectedBikeMac)
            put("reg", settings.bikeRegNumber)
            put("th", settings.appTheme)
            put("ha", settings.homeAddress)
            put("wa", settings.workAddress)
            put("pnav", settings.preferredNavApp)
            put("mid", settings.selectedModelId)
            put("cc", settings.selectedCountryCode)
            put("fpr", settings.petrolPricePerLiter)
            put("dm", settings.isDarkMode)
            put("ac", settings.isAutoConnectEnabled)
            put("odo", maintenanceManager.status.value.totalOdometerKm)
            put("dte", settings.projectedDteKm)
        }
        return obj.toString()
    }

    /**
     * Renders an obsidian/wine-red styled QR Code Bitmap from the setup payload.
     */
    fun generateSetupQrBitmap(width: Int = 600, height: Int = 600): Bitmap {
        val payload = createSetupQrPayload()
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, 2)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
        }

        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, width, height, hints)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Obsidian card background (#1C1B20) with crisp white QR modules (#F8FAFC)
        val colorDark = Color.parseColor("#F8FAFC")
        val colorLight = Color.parseColor("#1C1B20")
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) colorDark else colorLight
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Parses and restores from a scanned QR Code payload.
     */
    fun importFromQrPayload(payload: String): Result<String> {
        return try {
            val obj = JSONObject(payload)
            if (obj.optString("m") != QR_MAGIC_HEADER) {
                return Result.failure(IllegalArgumentException("Not a valid PulsarNav Migration QR code."))
            }

            fun JSONObject.optNonNullString(key: String): String? =
                if (has(key) && !isNull(key)) getString(key) else null

            val mid = obj.optNonNullString("mid")
            val cc = obj.optNonNullString("cc")
            if (mid != null && cc != null) {
                settings.applyBikeAndCountrySelection(mid, cc)
            } else {
                if (mid != null) settings.selectedModelId = mid
                if (cc != null) settings.selectedCountryCode = cc
            }

            obj.optNonNullString("r")?.let { settings.riderName = it }
            obj.optNonNullString("p")?.let { settings.riderPhone = it }
            obj.optNonNullString("bg")?.let { settings.riderBloodGroup = it }
            obj.optNonNullString("in")?.let { settings.emergencyContactName = it }
            obj.optNonNullString("ip")?.let { settings.emergencyContactPhone = it }
            obj.optNonNullString("mn")?.let { settings.medicalNotes = it }
            obj.optNonNullString("bn")?.let { settings.bikeName = it }
            obj.optNonNullString("mac")?.let { settings.lastConnectedBikeMac = it }
            obj.optNonNullString("reg")?.let { settings.bikeRegNumber = it }
            obj.optNonNullString("th")?.let { settings.appTheme = it }
            obj.optNonNullString("ha")?.let { settings.homeAddress = it }
            obj.optNonNullString("wa")?.let { settings.workAddress = it }
            obj.optNonNullString("pnav")?.let { settings.preferredNavApp = it }
            if (obj.has("fpr") && !obj.isNull("fpr")) settings.petrolPricePerLiter = obj.getDouble("fpr")
            if (obj.has("dm") && !obj.isNull("dm")) settings.isDarkMode = obj.getBoolean("dm")
            if (obj.has("ac") && !obj.isNull("ac")) settings.isAutoConnectEnabled = obj.getBoolean("ac")

            val odo = obj.optDouble("odo", -1.0)
            if (odo > 0) maintenanceManager.setOdometer(odo)

            val dte = obj.optInt("dte", -1)
            if (dte > 0) settings.projectedDteKm = dte

            settings.isSetupCompleted = true
            Result.success("Profile restored for ${settings.riderName} (${settings.bikeName})")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
