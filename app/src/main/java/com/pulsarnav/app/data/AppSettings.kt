package com.pulsarnav.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class BikeIssue(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val isResolved: Boolean = false,
    val category: String = "Mechanical",
    val dateLogged: String = ""
)

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
        const val KEY_AUTO_CONNECT = "feature_auto_connect"

        const val KEY_HOME_ADDRESS = "quicknav_home_address"
        const val KEY_WORK_ADDRESS = "quicknav_work_address"

        const val KEY_APP_THEME = "app_theme"
        const val KEY_DARK_MODE = "is_dark_mode"
        const val KEY_PETROL_PRICE = "petrol_price_per_liter"
        const val KEY_LAST_BIKE_MAC = "last_connected_bike_mac"
        const val KEY_BIKE_ISSUES_JSON = "bike_issues_checklist_json"

        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        const val KEY_SELECTED_COUNTRY_CODE = "selected_country_code"
        const val KEY_IS_SETUP_COMPLETED = "is_setup_completed"
        const val KEY_PREFERRED_NAV_APP = "preferred_nav_app"
        const val KEY_SKIP_CLUSTER_NAV_WHEN_HEADUNIT = "skip_cluster_nav_when_headunit"

        const val DEFAULT_REJECT_SMS_TEMPLATE = "I'm riding right now. I'll call you later."

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

        const val KEY_TFT_WIFI_SSID = "tft_wifi_ssid"
        const val KEY_TFT_WIFI_VIN = "tft_wifi_vin"
        const val KEY_TFT_WIFI_PASSPHRASE = "tft_wifi_passphrase"
        const val KEY_TFT_MIRROR_MODE = "tft_mirror_mode"
        const val KEY_TFT_CAST_LAYOUT = "tft_cast_layout"
        const val KEY_TFT_CAST_AUTO_OPEN_MAPS = "tft_cast_auto_open_maps"
        const val KEY_TFT_CAST_ALIGN_LEFT = "tft_cast_align_left"
        const val KEY_TFT_CAST_FORCE_LANDSCAPE = "tft_cast_force_landscape_v2"
        const val KEY_TFT_MAP_ZOOM = "tft_map_zoom"
        const val KEY_TFT_MAP_HEADING_UP = "tft_map_heading_up"
        const val KEY_AUTO_WIFI_MIRROR_ENABLED = "auto_wifi_mirror_enabled"
        const val KEY_CLUSTER_CONSOLE_TYPE = "cluster_console_type"

        const val CLUSTER_CONSOLE_AUTO = "auto"
        const val CLUSTER_CONSOLE_TFT = "tft"
        const val CLUSTER_CONSOLE_LCD = "lcd"

        const val TFT_MODE_LIVE_MAP = "live_map"
        const val TFT_MODE_HUD = "cockpit_hud"
        const val CAST_LAYOUT_FILL = "fill"
        const val CAST_LAYOUT_FIT = "fit"
        const val CAST_LAYOUT_ROT90 = "rot90"

        const val KEY_SMS_REJECT_ENABLED = "feature_sms_reject_enabled"
        const val KEY_SMS_REJECT_MESSAGE = "feature_sms_reject_message"
        const val KEY_FAV_PLACE_ADDRESS = "quicknav_fav_address"
        const val KEY_HOME_LAT = "quicknav_home_lat"
        const val KEY_HOME_LNG = "quicknav_home_lng"
        const val KEY_WORK_LAT = "quicknav_work_lat"
        const val KEY_WORK_LNG = "quicknav_work_lng"
        const val KEY_FAV_PLACE_LAT = "quicknav_fav_lat"
        const val KEY_FAV_PLACE_LNG = "quicknav_fav_lng"
        const val KEY_NAV_V2_ROUTE_MODE = "nav_v2_route_mode"
        const val KEY_NAV_V2_UNITS = "nav_v2_units"
        const val KEY_NAV_V2_AVOID_BITMASK = "nav_v2_avoid_bitmask"
        const val KEY_NAV_V2_RECENT_DESTS_JSON = "nav_v2_recent_dests_json"

        const val THEME_WINE_RED = "wine_red"
        const val THEME_AMOLED = "amoled"
        const val THEME_STEALTH = "stealth"
        const val THEME_AMBER = "amber"
        const val THEME_MINT = "mint"
        const val THEME_BLUE = "blue"
        const val THEME_CUSTOM = "custom"

        const val KEY_CUSTOM_ACCENT_RED = "custom_accent_red"
        const val KEY_CUSTOM_ACCENT_GREEN = "custom_accent_green"
        const val KEY_CUSTOM_ACCENT_BLUE = "custom_accent_blue"
        const val KEY_LAST_ACTIVE_TAB = "last_active_tab"
        const val KEY_ACTIVE_TAB_KEY = "active_tab_key"

        const val TAB_HOME = "home"
        const val TAB_FUEL = "fuel"
        const val TAB_SUPPORT = "support"
        const val TAB_ACCOUNT = "account"

        fun normalizeTabKey(raw: String?): String {
            return when (raw?.lowercase(Locale.US)) {
                TAB_FUEL -> TAB_FUEL
                TAB_SUPPORT -> TAB_SUPPORT
                TAB_ACCOUNT -> TAB_ACCOUNT
                else -> TAB_HOME
            }
        }

        internal fun coerceRestoreValue(
            key: String,
            value: Any,
            fileExists: (String) -> Boolean
        ): RestoreAction {
            if (key == KEY_LAST_ACTIVE_TAB || key == KEY_ACTIVE_TAB_KEY) {
                return RestoreAction.Skip
            }
            fun asInt(def: Int = 0): Int = when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: value.toDoubleOrNull()?.toInt() ?: def
                else -> def
            }
            fun asFloat(def: Float = 0f): Float = when (value) {
                is Number -> value.toFloat()
                is String -> value.toFloatOrNull() ?: def
                else -> def
            }
            fun asLong(def: Long = 0L): Long = when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: def
                else -> def
            }
            fun asBoolean(def: Boolean = false): Boolean = when (value) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true) || value == "1"
                is Number -> value.toInt() != 0
                else -> def
            }
            return when (key) {
                KEY_PETROL_PRICE, KEY_FUEL_LITERS -> RestoreAction.PutFloat(asFloat())
                KEY_PROJECTED_DTE, KEY_FUEL_PERCENT, KEY_DAYS_OF_OWNERSHIP -> RestoreAction.PutInt(asInt())
                KEY_CUSTOM_ACCENT_RED, KEY_CUSTOM_ACCENT_GREEN, KEY_CUSTOM_ACCENT_BLUE ->
                    RestoreAction.PutInt(asInt().coerceIn(0, 255))
                KEY_NAV_V2_ROUTE_MODE -> RestoreAction.PutInt(asInt().coerceIn(0, 3))
                KEY_NAV_V2_UNITS -> RestoreAction.PutInt(asInt().coerceIn(0, 1))
                KEY_NAV_V2_AVOID_BITMASK -> RestoreAction.PutInt(asInt() and 0x0F)
                KEY_TFT_MAP_ZOOM -> RestoreAction.PutInt(asInt().coerceIn(12, 18))
                KEY_LAST_SYNC_MS, KEY_PURCHASE_DATE_MS -> RestoreAction.PutLong(asLong())
                KEY_DOC_RC_PATH, KEY_DOC_INSURANCE_PATH, KEY_DOC_PUC_PATH -> {
                    val path = value.toString()
                    if (path.isNotBlank() && fileExists(path)) RestoreAction.PutString(path) else RestoreAction.Remove
                }
                KEY_HOME_LAT, KEY_HOME_LNG, KEY_WORK_LAT, KEY_WORK_LNG,
                KEY_FAV_PLACE_LAT, KEY_FAV_PLACE_LNG, KEY_SMS_REJECT_MESSAGE ->
                    RestoreAction.PutString(value.toString())
                KEY_AUTO_PARK, KEY_RIDE_TRACKING, KEY_MEDIA_CONTROL, KEY_WHATSAPP_ALERTS,
                KEY_MAINTENANCE_ALERTS, KEY_TELEMETRY_SYNC, KEY_TWO_WHEELER_MODE, KEY_AUTO_CONNECT,
                KEY_DARK_MODE, KEY_IS_SETUP_COMPLETED, KEY_SKIP_CLUSTER_NAV_WHEN_HEADUNIT,
                KEY_SMS_REJECT_ENABLED, KEY_TFT_MAP_HEADING_UP,
                KEY_TFT_CAST_AUTO_OPEN_MAPS, KEY_TFT_CAST_FORCE_LANDSCAPE -> RestoreAction.PutBoolean(asBoolean())
                else -> when (value) {
                    is Boolean -> RestoreAction.PutBoolean(value)
                    is Int -> RestoreAction.PutInt(value)
                    is Long -> RestoreAction.PutLong(value)
                    is Float -> RestoreAction.PutFloat(value)
                    is Double -> RestoreAction.PutFloat(value.toFloat())
                    else -> RestoreAction.PutString(value.toString())
                }
            }
        }

        @Volatile
        private var instance: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }

    fun getSafeFloat(key: String, defValue: Float): Float {
        return try {
            prefs.getFloat(key, defValue)
        } catch (_: ClassCastException) {
            try {
                val raw = prefs.all[key]
                val parsed = (raw as? Number)?.toFloat() ?: raw?.toString()?.toFloatOrNull() ?: defValue
                prefs.edit().putFloat(key, parsed).apply()
                parsed
            } catch (_: Exception) {
                defValue
            }
        }
    }

    fun getSafeInt(key: String, defValue: Int): Int {
        return try {
            prefs.getInt(key, defValue)
        } catch (_: ClassCastException) {
            try {
                val raw = prefs.all[key]
                val parsed = (raw as? Number)?.toInt() ?: raw?.toString()?.toIntOrNull() ?: defValue
                prefs.edit().putInt(key, parsed).apply()
                parsed
            } catch (_: Exception) {
                defValue
            }
        }
    }

    fun getSafeLong(key: String, defValue: Long): Long {
        return try {
            prefs.getLong(key, defValue)
        } catch (_: ClassCastException) {
            try {
                val raw = prefs.all[key]
                val parsed = (raw as? Number)?.toLong() ?: raw?.toString()?.toLongOrNull() ?: defValue
                prefs.edit().putLong(key, parsed).apply()
                parsed
            } catch (_: Exception) {
                defValue
            }
        }
    }

    fun getSafeBoolean(key: String, defValue: Boolean): Boolean {
        return try {
            prefs.getBoolean(key, defValue)
        } catch (_: ClassCastException) {
            try {
                val raw = prefs.all[key]
                val parsed = when (raw) {
                    is Boolean -> raw
                    is Number -> raw.toInt() != 0
                    is String -> raw.toBoolean()
                    else -> defValue
                }
                prefs.edit().putBoolean(key, parsed).apply()
                parsed
            } catch (_: Exception) {
                defValue
            }
        }
    }

    fun getSafeString(key: String, defValue: String): String {
        return try {
            prefs.getString(key, defValue) ?: defValue
        } catch (_: ClassCastException) {
            try {
                val parsed = prefs.all[key]?.toString() ?: defValue
                prefs.edit().putString(key, parsed).apply()
                parsed
            } catch (_: Exception) {
                defValue
            }
        }
    }

    var isAutoParkEnabled: Boolean
        get() = getSafeBoolean(KEY_AUTO_PARK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_PARK, value).apply()
        }

    var isRideTrackingEnabled: Boolean
        get() = getSafeBoolean(KEY_RIDE_TRACKING, true)
        set(value) {
            prefs.edit().putBoolean(KEY_RIDE_TRACKING, value).apply()
        }

    var isMediaControlEnabled: Boolean
        get() = getSafeBoolean(KEY_MEDIA_CONTROL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MEDIA_CONTROL, value).apply()
        }

    var isWhatsAppAlertsEnabled: Boolean
        get() = getSafeBoolean(KEY_WHATSAPP_ALERTS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WHATSAPP_ALERTS, value).apply()
        }

    var isMaintenanceAlertsEnabled: Boolean
        get() = getSafeBoolean(KEY_MAINTENANCE_ALERTS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MAINTENANCE_ALERTS, value).apply()
        }

    var isTelemetrySyncEnabled: Boolean
        get() = getSafeBoolean(KEY_TELEMETRY_SYNC, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TELEMETRY_SYNC, value).apply()
        }

    var isTwoWheelerMode: Boolean
        get() = getSafeBoolean(KEY_TWO_WHEELER_MODE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TWO_WHEELER_MODE, value).apply()
        }

    var isAutoConnectEnabled: Boolean
        get() = getSafeBoolean(KEY_AUTO_CONNECT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()
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

    var customAccentRed: Int
        get() = getSafeInt(KEY_CUSTOM_ACCENT_RED, 158)
        set(value) {
            prefs.edit().putInt(KEY_CUSTOM_ACCENT_RED, value.coerceIn(0, 255)).apply()
        }

    var customAccentGreen: Int
        get() = getSafeInt(KEY_CUSTOM_ACCENT_GREEN, 27)
        set(value) {
            prefs.edit().putInt(KEY_CUSTOM_ACCENT_GREEN, value.coerceIn(0, 255)).apply()
        }

    var customAccentBlue: Int
        get() = getSafeInt(KEY_CUSTOM_ACCENT_BLUE, 50)
        set(value) {
            prefs.edit().putInt(KEY_CUSTOM_ACCENT_BLUE, value.coerceIn(0, 255)).apply()
        }

    fun setCustomAccentRgb(red: Int, green: Int, blue: Int) {
        prefs.edit()
            .putInt(KEY_CUSTOM_ACCENT_RED, red.coerceIn(0, 255))
            .putInt(KEY_CUSTOM_ACCENT_GREEN, green.coerceIn(0, 255))
            .putInt(KEY_CUSTOM_ACCENT_BLUE, blue.coerceIn(0, 255))
            .apply()
    }

    fun getCustomAccentColor(): Int = android.graphics.Color.rgb(customAccentRed, customAccentGreen, customAccentBlue)

    var isDarkMode: Boolean
        get() = getSafeBoolean(KEY_DARK_MODE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DARK_MODE, value).apply()
        }

    var activeTabKey: String
        get() = normalizeTabKey(getSafeString(KEY_ACTIVE_TAB_KEY, TAB_HOME))
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_TAB_KEY, normalizeTabKey(value)).apply()
        }

    var lastActiveTab: Int
        get() = getSafeInt(KEY_LAST_ACTIVE_TAB, 0)
        set(value) {
            prefs.edit().putInt(KEY_LAST_ACTIVE_TAB, value).apply()
        }

    var petrolPricePerLiter: Double
        get() {
            val stored = getSafeFloat(KEY_PETROL_PRICE, -1f)
            if (stored > 0f) return stored.toDouble()
            return VehicleCatalog.getCountryByCode(selectedCountryCode).defaultFuelPrice
        }
        set(value) {
            prefs.edit().putFloat(KEY_PETROL_PRICE, value.toFloat()).apply()
        }

    val currentCurrencySymbol: String
        get() = VehicleCatalog.getCountryByCode(selectedCountryCode).currencySymbol

    val currentCurrencyCode: String
        get() = VehicleCatalog.getCountryByCode(selectedCountryCode).currencyCode

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
        get() = getSafeBoolean(KEY_IS_SETUP_COMPLETED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_SETUP_COMPLETED, value).apply()
        }

    var preferredNavApp: String
        get() = prefs.getString(KEY_PREFERRED_NAV_APP, NAV_APP_GOOGLE_MAPS) ?: NAV_APP_GOOGLE_MAPS
        set(value) {
            prefs.edit().putString(KEY_PREFERRED_NAV_APP, value).apply()
        }

    var isSkipClusterNavWhenHeadunit: Boolean
        get() = getSafeBoolean(KEY_SKIP_CLUSTER_NAV_WHEN_HEADUNIT, false)
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
        val previousCountry = selectedCountryCode
        selectedModelId = modelId
        selectedCountryCode = countryCode
        isSetupCompleted = true

        val model = VehicleCatalog.getModelById(modelId)
        val country = VehicleCatalog.getCountryByCode(countryCode)

        bikeName = VehicleCatalog.getLocalizedBikeName(model, country)
        bikeModelSpec = model.engineSpec

        if (previousCountry != countryCode) {
            petrolPricePerLiter = country.defaultFuelPrice
        }

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
        get() = getSafeInt(KEY_PROJECTED_DTE, 0)
        set(value) {
            prefs.edit().putInt(KEY_PROJECTED_DTE, value).apply()
        }

    var fuelLevelPercent: Int
        get() = getSafeInt(KEY_FUEL_PERCENT, 0)
        set(value) {
            prefs.edit().putInt(KEY_FUEL_PERCENT, value).apply()
        }

    var fuelLevelLiters: Float
        get() = getSafeFloat(KEY_FUEL_LITERS, 0.0f)
        set(value) {
            prefs.edit().putFloat(KEY_FUEL_LITERS, value).apply()
        }

    var lastSyncedTimestampMs: Long
        get() = getSafeLong(KEY_LAST_SYNC_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_MS, value).apply()
        }

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

    var tftWifiSsid: String
        get() = prefs.getString(KEY_TFT_WIFI_SSID, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TFT_WIFI_SSID, value.trim()).apply()
        }

    var tftWifiVin: String
        get() = prefs.getString(KEY_TFT_WIFI_VIN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TFT_WIFI_VIN, value.trim()).apply()
        }

    var tftWifiPassphrase: String
        get() = prefs.getString(KEY_TFT_WIFI_PASSPHRASE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TFT_WIFI_PASSPHRASE, value).apply()
        }

    var tftMirrorMode: String
        get() = when (getSafeString(KEY_TFT_MIRROR_MODE, TFT_MODE_LIVE_MAP)) {
            TFT_MODE_HUD -> TFT_MODE_HUD
            else -> TFT_MODE_LIVE_MAP
        }
        set(value) {
            val normalized = if (value == TFT_MODE_HUD) TFT_MODE_HUD else TFT_MODE_LIVE_MAP
            prefs.edit().putString(KEY_TFT_MIRROR_MODE, normalized).apply()
        }

    var tftCastLayoutMode: String
        get() = when (getSafeString(KEY_TFT_CAST_LAYOUT, CAST_LAYOUT_FIT)) {
            CAST_LAYOUT_FILL -> CAST_LAYOUT_FILL
            CAST_LAYOUT_ROT90 -> CAST_LAYOUT_ROT90
            else -> CAST_LAYOUT_FIT
        }
        set(value) {
            val normalized = when (value) {
                CAST_LAYOUT_FILL, CAST_LAYOUT_ROT90 -> value
                else -> CAST_LAYOUT_FIT
            }
            prefs.edit().putString(KEY_TFT_CAST_LAYOUT, normalized).apply()
        }

    var isAutoWifiMirrorEnabled: Boolean
        get() = getSafeBoolean(KEY_AUTO_WIFI_MIRROR_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_WIFI_MIRROR_ENABLED, value).apply()
        }

    var clusterConsoleType: String
        get() = when (getSafeString(KEY_CLUSTER_CONSOLE_TYPE, CLUSTER_CONSOLE_AUTO)) {
            CLUSTER_CONSOLE_TFT -> CLUSTER_CONSOLE_TFT
            CLUSTER_CONSOLE_LCD -> CLUSTER_CONSOLE_LCD
            else -> CLUSTER_CONSOLE_AUTO
        }
        set(value) {
            val normalized = when (value) {
                CLUSTER_CONSOLE_TFT, CLUSTER_CONSOLE_LCD -> value
                else -> CLUSTER_CONSOLE_AUTO
            }
            prefs.edit().putString(KEY_CLUSTER_CONSOLE_TYPE, normalized).apply()
        }

    fun cycleTftCastLayoutMode(): String {
        val next = when (tftCastLayoutMode) {
            CAST_LAYOUT_FIT -> CAST_LAYOUT_FILL
            CAST_LAYOUT_FILL -> CAST_LAYOUT_ROT90
            else -> CAST_LAYOUT_FIT
        }
        tftCastLayoutMode = next
        return next
    }

    var isTftCastAutoOpenMapsEnabled: Boolean
        get() = getSafeBoolean(KEY_TFT_CAST_AUTO_OPEN_MAPS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TFT_CAST_AUTO_OPEN_MAPS, value).apply()
        }

    var isTftCastAlignLeftEnabled: Boolean
        get() = getSafeBoolean(KEY_TFT_CAST_ALIGN_LEFT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TFT_CAST_ALIGN_LEFT, value).apply()
        }

    var isTftCastForceLandscapeEnabled: Boolean
        get() = getSafeBoolean(KEY_TFT_CAST_FORCE_LANDSCAPE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TFT_CAST_FORCE_LANDSCAPE, value).apply()
        }

    var tftMapZoom: Int
        get() = getSafeInt(KEY_TFT_MAP_ZOOM, 16).coerceIn(12, 18)
        set(value) {
            prefs.edit().putInt(KEY_TFT_MAP_ZOOM, value.coerceIn(12, 18)).apply()
        }

    var tftMapHeadingUp: Boolean
        get() = getSafeBoolean(KEY_TFT_MAP_HEADING_UP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TFT_MAP_HEADING_UP, value).apply()
        }

    var isSmsRejectEnabled: Boolean
        get() = getSafeBoolean(KEY_SMS_REJECT_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SMS_REJECT_ENABLED, value).apply()
        }

    var smsRejectMessage: String
        get() = prefs.getString(KEY_SMS_REJECT_MESSAGE, DEFAULT_REJECT_SMS_TEMPLATE)
            ?: DEFAULT_REJECT_SMS_TEMPLATE
        set(value) {
            prefs.edit().putString(KEY_SMS_REJECT_MESSAGE, value.trim()).apply()
        }

    var favPlaceAddress: String
        get() = prefs.getString(KEY_FAV_PLACE_ADDRESS, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAV_PLACE_ADDRESS, value.trim()).apply()
        }

    var homeLat: Double
        get() = getSafeString(KEY_HOME_LAT, "0.0").toDoubleOrNull() ?: 0.0
        set(value) { prefs.edit().putString(KEY_HOME_LAT, value.toString()).apply() }

    var homeLng: Double
        get() = getSafeString(KEY_HOME_LNG, "0.0").toDoubleOrNull() ?: 0.0
        set(value) { prefs.edit().putString(KEY_HOME_LNG, value.toString()).apply() }

    var workLat: Double
        get() = getSafeString(KEY_WORK_LAT, "0.0").toDoubleOrNull() ?: 0.0
        set(value) { prefs.edit().putString(KEY_WORK_LAT, value.toString()).apply() }

    var workLng: Double
        get() = getSafeString(KEY_WORK_LNG, "0.0").toDoubleOrNull() ?: 0.0
        set(value) { prefs.edit().putString(KEY_WORK_LNG, value.toString()).apply() }

    var favPlaceLat: Double
        get() = getSafeString(KEY_FAV_PLACE_LAT, "0.0").toDoubleOrNull() ?: 0.0
        set(value) { prefs.edit().putString(KEY_FAV_PLACE_LAT, value.toString()).apply() }

    var favPlaceLng: Double
        get() = getSafeString(KEY_FAV_PLACE_LNG, "0.0").toDoubleOrNull() ?: 0.0
        set(value) { prefs.edit().putString(KEY_FAV_PLACE_LNG, value.toString()).apply() }

    var navV2RouteMode: Int
        get() = getSafeInt(KEY_NAV_V2_ROUTE_MODE, 0)
        set(value) { prefs.edit().putInt(KEY_NAV_V2_ROUTE_MODE, value.coerceIn(0, 3)).apply() }

    var navV2Units: Int
        get() = getSafeInt(KEY_NAV_V2_UNITS, 0)
        set(value) { prefs.edit().putInt(KEY_NAV_V2_UNITS, value.coerceIn(0, 1)).apply() }

    var navV2AvoidBitmask: Int
        get() = getSafeInt(KEY_NAV_V2_AVOID_BITMASK, 0)
        set(value) { prefs.edit().putInt(KEY_NAV_V2_AVOID_BITMASK, value and 0x0F).apply() }

    var navV2RecentDestsJson: String
        get() = prefs.getString(KEY_NAV_V2_RECENT_DESTS_JSON, "[]") ?: "[]"
        set(value) { prefs.edit().putString(KEY_NAV_V2_RECENT_DESTS_JSON, value).apply() }

    fun getBikeIssues(): MutableList<BikeIssue> {
        val raw = prefs.getString(KEY_BIKE_ISSUES_JSON, null)
        if (raw.isNullOrBlank()) {
            return mutableListOf()
        }
        val list = mutableListOf<BikeIssue>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BikeIssue(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", ""),
                        isResolved = obj.optBoolean("isResolved", false),
                        category = obj.optString("category", "Mechanical"),
                        dateLogged = obj.optString("dateLogged", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveBikeIssues(issues: List<BikeIssue>) {
        val array = JSONArray()
        for (issue in issues) {
            val obj = JSONObject().apply {
                put("id", issue.id)
                put("title", issue.title)
                put("isResolved", issue.isResolved)
                put("category", issue.category)
                put("dateLogged", issue.dateLogged)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_BIKE_ISSUES_JSON, array.toString()).apply()
    }

    fun addBikeIssue(title: String, category: String = "Mechanical") {
        val current = getBikeIssues()
        val dateStr = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date())
        current.add(0, BikeIssue(title = title, category = category, dateLogged = dateStr))
        saveBikeIssues(current)
    }

    fun toggleBikeIssue(id: String) {
        val current = getBikeIssues()
        val idx = current.indexOfFirst { it.id == id }
        if (idx != -1) {
            val item = current[idx]
            current[idx] = item.copy(isResolved = !item.isResolved)
            saveBikeIssues(current)
        }
    }

    fun deleteBikeIssue(id: String) {
        val current = getBikeIssues()
        current.removeAll { it.id == id }
        saveBikeIssues(current)
    }

    fun getAllSettingsMap(): Map<String, *> = prefs.all
        .filterKeys { it != KEY_LAST_ACTIVE_TAB && it != KEY_ACTIVE_TAB_KEY }

    fun restoreSettingsMap(map: Map<String, *>) {
        val editor = prefs.edit()
        for ((k, v) in map) {
            if (v == null || v == org.json.JSONObject.NULL) continue
            when (val coerced = coerceRestoreValue(k, v) { path -> java.io.File(path).exists() }) {
                is RestoreAction.Skip -> continue
                is RestoreAction.Remove -> editor.remove(k)
                is RestoreAction.PutFloat -> editor.putFloat(k, coerced.value)
                is RestoreAction.PutInt -> editor.putInt(k, coerced.value)
                is RestoreAction.PutLong -> editor.putLong(k, coerced.value)
                is RestoreAction.PutBoolean -> editor.putBoolean(k, coerced.value)
                is RestoreAction.PutString -> editor.putString(k, coerced.value)
            }
        }
        editor.apply()
    }

    internal sealed class RestoreAction {
        object Skip : RestoreAction()
        object Remove : RestoreAction()
        data class PutFloat(val value: Float) : RestoreAction()
        data class PutInt(val value: Int) : RestoreAction()
        data class PutLong(val value: Long) : RestoreAction()
        data class PutBoolean(val value: Boolean) : RestoreAction()
        data class PutString(val value: String) : RestoreAction()
    }
}
