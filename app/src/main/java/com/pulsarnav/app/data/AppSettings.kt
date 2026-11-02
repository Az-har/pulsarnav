package com.pulsarnav.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsarnav_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_AUTO_PARK = "feature_auto_park"
        const val KEY_RIDE_TRACKING = "feature_ride_tracking"
        const val KEY_MEDIA_CONTROL = "feature_media_control"
        const val KEY_WHATSAPP_ALERTS = "feature_whatsapp_alerts"
        const val KEY_MAINTENANCE_ALERTS = "feature_maintenance_alerts"
        const val KEY_TELEMETRY_SYNC = "feature_telemetry_sync"
        const val KEY_TWO_WHEELER_MODE = "feature_two_wheeler_mode"

        const val KEY_HOME_ADDRESS = "quicknav_home_address"
        const val KEY_WORK_ADDRESS = "quicknav_work_address"

        const val KEY_APP_THEME = "app_theme"
        const val KEY_PETROL_PRICE = "petrol_price_per_liter"
        const val KEY_LAST_BIKE_MAC = "last_connected_bike_mac"

        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        const val KEY_SELECTED_COUNTRY_CODE = "selected_country_code"
        const val KEY_IS_SETUP_COMPLETED = "is_setup_completed"
        const val KEY_PREFERRED_NAV_APP = "preferred_nav_app"
        const val KEY_SKIP_CLUSTER_NAV_WHEN_HEADUNIT = "skip_cluster_nav_when_headunit"

        const val NAV_APP_GOOGLE_MAPS = "google_maps"
        const val NAV_APP_WAZE = "waze"

        const val KEY_BIKE_NAME = "bike_name"
        const val KEY_BIKE_REG = "bike_reg_number"
        const val KEY_BIKE_SPEC = "bike_model_spec"
        const val KEY_RIDER_NAME = "rider_name"
        const val KEY_RIDER_PHONE = "rider_phone"

        const val KEY_PROJECTED_DTE = "projected_dte_km"
        const val KEY_FUEL_PERCENT = "fuel_level_percent"
        const val KEY_FUEL_LITERS = "fuel_level_liters"
        const val KEY_LAST_SYNC_MS = "last_synced_timestamp_ms"
        const val KEY_DAYS_OF_OWNERSHIP = "days_of_ownership"
        const val KEY_PURCHASE_DATE_MS = "bike_purchase_date_ms"

        const val KEY_RIDER_BLOOD_GROUP = "rider_blood_group"
        const val KEY_EMERGENCY_CONTACT_NAME = "ice_contact_name"
        const val KEY_EMERGENCY_CONTACT_PHONE = "ice_contact_phone"
        const val KEY_MEDICAL_NOTES = "medical_notes"

        const val KEY_DOC_RC_NUMBER = "doc_rc_number"
        const val KEY_DOC_RC_PATH = "doc_rc_path"
        const val KEY_DOC_INSURANCE_POLICY = "doc_insurance_policy"
        const val KEY_DOC_INSURANCE_EXPIRY = "doc_insurance_expiry"
        const val KEY_DOC_INSURANCE_PATH = "doc_insurance_path"
        const val KEY_DOC_PUC_CERT = "doc_puc_cert"
        const val KEY_DOC_PUC_EXPIRY = "doc_puc_expiry"
        const val KEY_DOC_PUC_PATH = "doc_puc_path"

        const val THEME_WINE_RED = "wine_red"
        const val THEME_AMOLED = "amoled"
        const val THEME_STEALTH = "stealth"
        const val THEME_AMBER = "amber"
        const val THEME_MINT = "mint"
        const val THEME_BLUE = "blue"

        @Volatile
        private var instance: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _autoParkFlow = MutableStateFlow(prefs.getBoolean(KEY_AUTO_PARK, true))
    val autoParkFlow: StateFlow<Boolean> = _autoParkFlow.asStateFlow()

    private val _rideTrackingFlow = MutableStateFlow(prefs.getBoolean(KEY_RIDE_TRACKING, true))
    val rideTrackingFlow: StateFlow<Boolean> = _rideTrackingFlow.asStateFlow()

    private val _mediaControlFlow = MutableStateFlow(prefs.getBoolean(KEY_MEDIA_CONTROL, true))
    val mediaControlFlow: StateFlow<Boolean> = _mediaControlFlow.asStateFlow()

    private val _whatsappAlertsFlow = MutableStateFlow(prefs.getBoolean(KEY_WHATSAPP_ALERTS, true))
    val whatsappAlertsFlow: StateFlow<Boolean> = _whatsappAlertsFlow.asStateFlow()

    private val _maintenanceAlertsFlow = MutableStateFlow(prefs.getBoolean(KEY_MAINTENANCE_ALERTS, true))
    val maintenanceAlertsFlow: StateFlow<Boolean> = _maintenanceAlertsFlow.asStateFlow()

    private val _telemetrySyncFlow = MutableStateFlow(prefs.getBoolean(KEY_TELEMETRY_SYNC, true))
    val telemetrySyncFlow: StateFlow<Boolean> = _telemetrySyncFlow.asStateFlow()

    private val _twoWheelerModeFlow = MutableStateFlow(prefs.getBoolean(KEY_TWO_WHEELER_MODE, true))
    val twoWheelerModeFlow: StateFlow<Boolean> = _twoWheelerModeFlow.asStateFlow()

    var isAutoParkEnabled: Boolean
        get() = _autoParkFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_PARK, value).apply()
            _autoParkFlow.value = value
        }

    var isRideTrackingEnabled: Boolean
        get() = _rideTrackingFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_RIDE_TRACKING, value).apply()
            _rideTrackingFlow.value = value
        }

    var isMediaControlEnabled: Boolean
        get() = _mediaControlFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_MEDIA_CONTROL, value).apply()
            _mediaControlFlow.value = value
        }

    var isWhatsAppAlertsEnabled: Boolean
        get() = _whatsappAlertsFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_WHATSAPP_ALERTS, value).apply()
            _whatsappAlertsFlow.value = value
        }

    var isMaintenanceAlertsEnabled: Boolean
        get() = _maintenanceAlertsFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_MAINTENANCE_ALERTS, value).apply()
            _maintenanceAlertsFlow.value = value
        }

    var isTelemetrySyncEnabled: Boolean
        get() = _telemetrySyncFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_TELEMETRY_SYNC, value).apply()
            _telemetrySyncFlow.value = value
        }

    var isTwoWheelerMode: Boolean
        get() = _twoWheelerModeFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_TWO_WHEELER_MODE, value).apply()
            _twoWheelerModeFlow.value = value
        }

    var homeAddress: String
        get() = prefs.getString(KEY_HOME_ADDRESS, "Home") ?: "Home"
        set(value) {
            prefs.edit().putString(KEY_HOME_ADDRESS, value).apply()
        }

    var workAddress: String
        get() = prefs.getString(KEY_WORK_ADDRESS, "Work") ?: "Work"
        set(value) {
            prefs.edit().putString(KEY_WORK_ADDRESS, value).apply()
        }

    var appTheme: String
        get() = prefs.getString(KEY_APP_THEME, THEME_WINE_RED) ?: THEME_WINE_RED
        set(value) {
            prefs.edit().putString(KEY_APP_THEME, value).apply()
        }

    var petrolPricePerLiter: Double
        get() = prefs.getFloat(KEY_PETROL_PRICE, 104.0f).toDouble()
        set(value) {
            prefs.edit().putFloat(KEY_PETROL_PRICE, value.toFloat()).apply()
        }

    var lastConnectedBikeMac: String
        get() = prefs.getString(KEY_LAST_BIKE_MAC, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_BIKE_MAC, value).apply()
        }

    var selectedModelId: String
        get() = prefs.getString(KEY_SELECTED_MODEL_ID, "ns200") ?: "ns200"
        set(value) {
            prefs.edit().putString(KEY_SELECTED_MODEL_ID, value).apply()
        }

    var selectedCountryCode: String
        get() = prefs.getString(KEY_SELECTED_COUNTRY_CODE, "IN") ?: "IN"
        set(value) {
            prefs.edit().putString(KEY_SELECTED_COUNTRY_CODE, value).apply()
        }

    var isSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_IS_SETUP_COMPLETED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_SETUP_COMPLETED, value).apply()
        }

    var preferredNavApp: String
        get() = prefs.getString(KEY_PREFERRED_NAV_APP, NAV_APP_GOOGLE_MAPS) ?: NAV_APP_GOOGLE_MAPS
        set(value) {
            prefs.edit().putString(KEY_PREFERRED_NAV_APP, value).apply()
        }

    var isSkipClusterNavWhenHeadunit: Boolean
        get() = prefs.getBoolean(KEY_SKIP_CLUSTER_NAV_WHEN_HEADUNIT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SKIP_CLUSTER_NAV_WHEN_HEADUNIT, value).apply()
        }

    fun getActiveBikeProfile(): BikeModelProfile {
        return VehicleCatalog.getModelById(selectedModelId)
    }

    fun getActiveCountryProfile(): CountryProfile {
        return VehicleCatalog.getCountryByCode(selectedCountryCode)
    }

    fun applyBikeAndCountrySelection(modelId: String, countryCode: String) {
        selectedModelId = modelId
        selectedCountryCode = countryCode
        isSetupCompleted = true

        val model = VehicleCatalog.getModelById(modelId)
        val country = VehicleCatalog.getCountryByCode(countryCode)

        bikeName = VehicleCatalog.getLocalizedBikeName(model, country)
        bikeModelSpec = model.engineSpec

        if (emergencyContactPhone.isBlank() || emergencyContactPhone == "112" || emergencyContactPhone == "911" || emergencyContactPhone == "123") {
            emergencyContactPhone = country.sosNumber
            emergencyContactName = "${country.name} SOS (${country.sosNumber})"
        }
    }

    var bikeName: String
        get() = prefs.getString(KEY_BIKE_NAME, "Pulsar NS200") ?: "Pulsar NS200"
        set(value) {
            prefs.edit().putString(KEY_BIKE_NAME, value).apply()
        }

    var bikeRegNumber: String
        get() = prefs.getString(KEY_BIKE_REG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_BIKE_REG, value).apply()
        }

    var bikeModelSpec: String
        get() = prefs.getString(KEY_BIKE_SPEC, "BS6 DTS-i 200cc") ?: "BS6 DTS-i 200cc"
        set(value) {
            prefs.edit().putString(KEY_BIKE_SPEC, value).apply()
        }

    var riderName: String
        get() = prefs.getString(KEY_RIDER_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_RIDER_NAME, value).apply()
        }

    var riderPhone: String
        get() = prefs.getString(KEY_RIDER_PHONE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_RIDER_PHONE, value).apply()
        }

    var projectedDteKm: Int
        get() = prefs.getInt(KEY_PROJECTED_DTE, 0)
        set(value) {
            prefs.edit().putInt(KEY_PROJECTED_DTE, value).apply()
        }

    var fuelLevelPercent: Int
        get() = prefs.getInt(KEY_FUEL_PERCENT, 0)
        set(value) {
            prefs.edit().putInt(KEY_FUEL_PERCENT, value).apply()
        }

    var fuelLevelLiters: Float
        get() = prefs.getFloat(KEY_FUEL_LITERS, 0.0f)
        set(value) {
            prefs.edit().putFloat(KEY_FUEL_LITERS, value).apply()
        }

    var lastSyncedTimestampMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_MS, value).apply()
        }

    var purchaseDateMs: Long
        get() = prefs.getLong(KEY_PURCHASE_DATE_MS, System.currentTimeMillis())
        set(value) {
            prefs.edit().putLong(KEY_PURCHASE_DATE_MS, value).apply()
        }

    val daysOfOwnership: Int
        get() {
            val diffMs = (System.currentTimeMillis() - purchaseDateMs).coerceAtLeast(0L)
            return (diffMs / 86400000L).toInt()
        }

    val formattedPurchaseDate: String
        get() = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(purchaseDateMs))

    var riderBloodGroup: String
        get() = prefs.getString(KEY_RIDER_BLOOD_GROUP, "Not Specified") ?: "Not Specified"
        set(value) {
            prefs.edit().putString(KEY_RIDER_BLOOD_GROUP, value).apply()
        }

    var emergencyContactName: String
        get() = prefs.getString(KEY_EMERGENCY_CONTACT_NAME, "Emergency Contact") ?: "Emergency Contact"
        set(value) {
            prefs.edit().putString(KEY_EMERGENCY_CONTACT_NAME, value).apply()
        }

    var emergencyContactPhone: String
        get() = prefs.getString(KEY_EMERGENCY_CONTACT_PHONE, "112") ?: "112"
        set(value) {
            prefs.edit().putString(KEY_EMERGENCY_CONTACT_PHONE, value).apply()
        }

    var medicalNotes: String
        get() = prefs.getString(KEY_MEDICAL_NOTES, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_MEDICAL_NOTES, value).apply()
        }

    var docRcNumber: String
        get() = prefs.getString(KEY_DOC_RC_NUMBER, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DOC_RC_NUMBER, value).apply()
        }

    var docRcPath: String
        get() = prefs.getString(KEY_DOC_RC_PATH, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DOC_RC_PATH, value).apply()
        }

    var docInsurancePolicy: String
        get() = prefs.getString(KEY_DOC_INSURANCE_POLICY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DOC_INSURANCE_POLICY, value).apply()
        }

    var docInsuranceExpiry: String
        get() = prefs.getString(KEY_DOC_INSURANCE_EXPIRY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DOC_INSURANCE_EXPIRY, value).apply()
        }

    var docInsurancePath: String
        get() = prefs.getString(KEY_DOC_INSURANCE_PATH, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DOC_INSURANCE_PATH, value).apply()
        }

    var docPucCert: String
        get() = prefs.getString(KEY_DOC_PUC_CERT, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DOC_PUC_CERT, value).apply()
        }

    var docPucExpiry: String
        get() = prefs.getString(KEY_DOC_PUC_EXPIRY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DOC_PUC_EXPIRY, value).apply()
        }

    var docPucPath: String
        get() = prefs.getString(KEY_DOC_PUC_PATH, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DOC_PUC_PATH, value).apply()
        }

    fun getAllSettingsMap(): Map<String, *> = prefs.all

    fun restoreSettingsMap(map: Map<String, *>) {
        val editor = prefs.edit()
        for ((k, v) in map) {
            when (v) {
                is Boolean -> editor.putBoolean(k, v)
                is String -> editor.putString(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Float -> editor.putFloat(k, v)
                is Double -> editor.putFloat(k, v.toFloat())
            }
        }
        editor.apply()

        // Reload StateFlows for instant UI response
        _autoParkFlow.value = prefs.getBoolean(KEY_AUTO_PARK, true)
        _rideTrackingFlow.value = prefs.getBoolean(KEY_RIDE_TRACKING, true)
        _mediaControlFlow.value = prefs.getBoolean(KEY_MEDIA_CONTROL, true)
        _whatsappAlertsFlow.value = prefs.getBoolean(KEY_WHATSAPP_ALERTS, true)
        _maintenanceAlertsFlow.value = prefs.getBoolean(KEY_MAINTENANCE_ALERTS, true)
        _telemetrySyncFlow.value = prefs.getBoolean(KEY_TELEMETRY_SYNC, true)
        _twoWheelerModeFlow.value = prefs.getBoolean(KEY_TWO_WHEELER_MODE, true)
    }
}
