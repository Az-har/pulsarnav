package com.pulsarnav.app.data

data class BikeModelProfile(
    val id: String,
    val name: String,
    val series: String, // "NS Series", "N Series", "Classic Series", "Dominar Series"
    val engineSpec: String,
    val tankCapacityLiters: Float,
    val reserveCapacityLiters: Float,
    val coldTireFrontPsiSolo: Int,
    val coldTireFrontPsiPillion: Int,
    val coldTireRearPsiSolo: Int,
    val coldTireRearPsiPillion: Int,
    val tireFrontSize: String,
    val tireRearSize: String,
    val engineOilSpec: String,
    val coolantSpec: String,
    val chainSlackMm: String,
    val sparkPlugSpec: String
)

data class CountryProfile(
    val code: String,
    val name: String,
    val flag: String,
    val dialCode: String,
    val sosNumber: String,
    val ambulanceNumber: String,
    val policeNumber: String,
    val roadsideAssistance: String,
    val usesRouserBrand: Boolean = false // Rouser in LatAm markets
)

object VehicleCatalog {

    val COUNTRIES = listOf(
        CountryProfile(
            code = "IN",
            name = "India",
            flag = "🇮🇳",
            dialCode = "+91",
            sosNumber = "112",
            ambulanceNumber = "108",
            policeNumber = "100",
            roadsideAssistance = "1033",
            usesRouserBrand = false
        ),
        CountryProfile(
            code = "CO",
            name = "Colombia",
            flag = "🇨🇴",
            dialCode = "+57",
            sosNumber = "123",
            ambulanceNumber = "132",
            policeNumber = "112",
            roadsideAssistance = "#767",
            usesRouserBrand = true
        ),
        CountryProfile(
            code = "MX",
            name = "Mexico",
            flag = "🇲🇽",
            dialCode = "+52",
            sosNumber = "911",
            ambulanceNumber = "065",
            policeNumber = "911",
            roadsideAssistance = "078",
            usesRouserBrand = true
        ),
        CountryProfile(
            code = "AR",
            name = "Argentina",
            flag = "🇦🇷",
            dialCode = "+54",
            sosNumber = "911",
            ambulanceNumber = "107",
            policeNumber = "911",
            roadsideAssistance = "0800-888-2677",
            usesRouserBrand = true
        ),
        CountryProfile(
            code = "PE",
            name = "Peru",
            flag = "🇵🇪",
            dialCode = "+51",
            sosNumber = "911",
            ambulanceNumber = "106",
            policeNumber = "105",
            roadsideAssistance = "110",
            usesRouserBrand = true
        ),
        CountryProfile(
            code = "BR",
            name = "Brazil",
            flag = "🇧🇷",
            dialCode = "+55",
            sosNumber = "190",
            ambulanceNumber = "192",
            policeNumber = "190",
            roadsideAssistance = "191",
            usesRouserBrand = false
        ),
        CountryProfile(
            code = "PH",
            name = "Philippines",
            flag = "🇵🇭",
            dialCode = "+63",
            sosNumber = "911",
            ambulanceNumber = "143",
            policeNumber = "117",
            roadsideAssistance = "911",
            usesRouserBrand = false
        ),
        CountryProfile(
            code = "TR",
            name = "Turkey",
            flag = "🇹🇷",
            dialCode = "+90",
            sosNumber = "112",
            ambulanceNumber = "112",
            policeNumber = "112",
            roadsideAssistance = "112",
            usesRouserBrand = false
        ),
        CountryProfile(
            code = "EG",
            name = "Egypt",
            flag = "🇪🇬",
            dialCode = "+20",
            sosNumber = "122",
            ambulanceNumber = "123",
            policeNumber = "122",
            roadsideAssistance = "126",
            usesRouserBrand = false
        ),
        CountryProfile(
            code = "ZA",
            name = "South Africa",
            flag = "🇿🇦",
            dialCode = "+27",
            sosNumber = "112",
            ambulanceNumber = "10177",
            policeNumber = "10111",
            roadsideAssistance = "112",
            usesRouserBrand = false
        ),
        CountryProfile(
            code = "NP",
            name = "Nepal",
            flag = "🇳🇵",
            dialCode = "+977",
            sosNumber = "100",
            ambulanceNumber = "102",
            policeNumber = "100",
            roadsideAssistance = "103",
            usesRouserBrand = false
        ),
        CountryProfile(
            code = "BD",
            name = "Bangladesh",
            flag = "🇧🇩",
            dialCode = "+880",
            sosNumber = "999",
            ambulanceNumber = "999",
            policeNumber = "999",
            roadsideAssistance = "999",
            usesRouserBrand = false
        ),
        CountryProfile(
            code = "GB",
            name = "United Kingdom / EU",
            flag = "🇬🇧",
            dialCode = "+44",
            sosNumber = "999",
            ambulanceNumber = "999",
            policeNumber = "112",
            roadsideAssistance = "112",
            usesRouserBrand = false
        )
    )

    val MODELS = listOf(
        BikeModelProfile(
            id = "ns200",
            name = "Pulsar NS200",
            series = "NS Series",
            engineSpec = "199.5cc Triple Spark 4V Liquid-Cooled (24.5 PS / 18.7 Nm)",
            tankCapacityLiters = 12.0f,
            reserveCapacityLiters = 2.4f,
            coldTireFrontPsiSolo = 28,
            coldTireFrontPsiPillion = 28,
            coldTireRearPsiSolo = 32,
            coldTireRearPsiPillion = 36,
            tireFrontSize = "100/80-17 Tubeless",
            tireRearSize = "130/70-17 Tubeless",
            engineOilSpec = "10W-50 Semi-Synthetic / Fully Synthetic (1200 ml)",
            coolantSpec = "Ready-mix Glycol Coolant (1000 ml)",
            chainSlackMm = "25 - 35 mm (Lube every 500 km)",
            sparkPlugSpec = "Champion RG4HC (Center) + RG6HCC (x2)"
        ),
        BikeModelProfile(
            id = "ns400z",
            name = "Pulsar NS400Z",
            series = "NS Series",
            engineSpec = "373.3cc DOHC 4V Liquid-Cooled (40 PS / 35 Nm)",
            tankCapacityLiters = 12.0f,
            reserveCapacityLiters = 2.0f,
            coldTireFrontPsiSolo = 29,
            coldTireFrontPsiPillion = 29,
            coldTireRearPsiSolo = 33,
            coldTireRearPsiPillion = 37,
            tireFrontSize = "110/70-17 Tubeless",
            tireRearSize = "140/70-17 Tubeless",
            engineOilSpec = "10W-50 Fully Synthetic (1700 ml)",
            coolantSpec = "High-performance Glycol Coolant (1200 ml)",
            chainSlackMm = "20 - 30 mm (Lube every 500 km)",
            sparkPlugSpec = "Bosch VR5NEU Triple Electrode"
        ),
        BikeModelProfile(
            id = "ns160",
            name = "Pulsar NS160",
            series = "NS Series",
            engineSpec = "160.3cc Twin Spark 4V Oil-Cooled (17.2 PS / 14.6 Nm)",
            tankCapacityLiters = 12.0f,
            reserveCapacityLiters = 2.4f,
            coldTireFrontPsiSolo = 28,
            coldTireFrontPsiPillion = 28,
            coldTireRearPsiSolo = 32,
            coldTireRearPsiPillion = 36,
            tireFrontSize = "100/80-17 Tubeless",
            tireRearSize = "130/70-17 Tubeless",
            engineOilSpec = "10W-40 / 20W-50 (1150 ml)",
            coolantSpec = "Oil Radiator Assisted",
            chainSlackMm = "25 - 35 mm (Lube every 500 km)",
            sparkPlugSpec = "Champion PRG6C / Bosch UR4AC"
        ),
        BikeModelProfile(
            id = "ns125",
            name = "Pulsar NS125",
            series = "NS Series",
            engineSpec = "124.45cc DTS-i Single Spark (12.0 PS / 11.0 Nm)",
            tankCapacityLiters = 12.0f,
            reserveCapacityLiters = 2.0f,
            coldTireFrontPsiSolo = 25,
            coldTireFrontPsiPillion = 25,
            coldTireRearPsiSolo = 28,
            coldTireRearPsiPillion = 32,
            tireFrontSize = "80/100-17 Tubeless",
            tireRearSize = "100/90-17 Tubeless",
            engineOilSpec = "20W-50 4T Motorcycle Oil (1000 ml)",
            coolantSpec = "Air Cooled",
            chainSlackMm = "25 - 35 mm (Lube every 500 km)",
            sparkPlugSpec = "Champion RG4HC Single Spark"
        ),
        BikeModelProfile(
            id = "n250",
            name = "Pulsar N250 / F250",
            series = "N Series",
            engineSpec = "249.07cc Twin Spark 2V Oil-Cooled (24.5 PS / 21.5 Nm)",
            tankCapacityLiters = 14.0f,
            reserveCapacityLiters = 2.5f,
            coldTireFrontPsiSolo = 28,
            coldTireFrontPsiPillion = 28,
            coldTireRearPsiSolo = 32,
            coldTireRearPsiPillion = 36,
            tireFrontSize = "110/70-17 Tubeless",
            tireRearSize = "140/70-17 Tubeless",
            engineOilSpec = "10W-50 Semi-Synthetic (1300 ml)",
            coolantSpec = "Oil Cooler Radiator",
            chainSlackMm = "25 - 35 mm (Lube every 500 km)",
            sparkPlugSpec = "Champion PRG6C Dual Spark"
        ),
        BikeModelProfile(
            id = "n160",
            name = "Pulsar N160 / N150",
            series = "N Series",
            engineSpec = "164.82cc DTS-i Oil-Cooled (16.0 PS / 14.65 Nm)",
            tankCapacityLiters = 14.0f,
            reserveCapacityLiters = 2.5f,
            coldTireFrontPsiSolo = 25,
            coldTireFrontPsiPillion = 25,
            coldTireRearPsiSolo = 28,
            coldTireRearPsiPillion = 32,
            tireFrontSize = "100/80-17 Tubeless",
            tireRearSize = "130/70-17 Tubeless",
            engineOilSpec = "10W-40 4T Semi-Synthetic (1200 ml)",
            coolantSpec = "Oil Cooler Radiator",
            chainSlackMm = "25 - 35 mm (Lube every 500 km)",
            sparkPlugSpec = "Champion RG4HC"
        ),
        BikeModelProfile(
            id = "pulsar150",
            name = "Pulsar 150 / 125 (Digital BT)",
            series = "Classic Series",
            engineSpec = "149.5cc Twin Spark DTS-i (14.0 PS / 13.25 Nm)",
            tankCapacityLiters = 11.5f,
            reserveCapacityLiters = 2.0f,
            coldTireFrontPsiSolo = 25,
            coldTireFrontPsiPillion = 25,
            coldTireRearPsiSolo = 28,
            coldTireRearPsiPillion = 32,
            tireFrontSize = "80/100-17 Tubeless",
            tireRearSize = "100/90-17 Tubeless",
            engineOilSpec = "20W-50 4T Motorcycle Oil (1000 ml)",
            coolantSpec = "Natural Air Cooled",
            chainSlackMm = "25 - 35 mm (Lube every 500 km)",
            sparkPlugSpec = "Champion RG4HC Dual Spark"
        ),
        BikeModelProfile(
            id = "pulsar220f",
            name = "Pulsar 220F (Digital BT)",
            series = "Classic Series",
            engineSpec = "220cc 4-Stroke DTS-i Oil-Cooled (20.4 PS / 18.5 Nm)",
            tankCapacityLiters = 15.0f,
            reserveCapacityLiters = 3.2f,
            coldTireFrontPsiSolo = 28,
            coldTireFrontPsiPillion = 28,
            coldTireRearPsiSolo = 32,
            coldTireRearPsiPillion = 36,
            tireFrontSize = "90/90-17 Tubeless",
            tireRearSize = "120/80-17 Tubeless",
            engineOilSpec = "20W-50 4T Semi-Synthetic (1150 ml)",
            coolantSpec = "Oil Cooler Radiator",
            chainSlackMm = "25 - 35 mm (Lube every 500 km)",
            sparkPlugSpec = "Champion RG4HC Dual Spark"
        ),
        BikeModelProfile(
            id = "dominar400",
            name = "Dominar 400",
            series = "Dominar Series",
            engineSpec = "373.3cc DOHC Liquid-Cooled Triple Spark (40 PS / 35 Nm)",
            tankCapacityLiters = 13.0f,
            reserveCapacityLiters = 2.5f,
            coldTireFrontPsiSolo = 29,
            coldTireFrontPsiPillion = 29,
            coldTireRearPsiSolo = 33,
            coldTireRearPsiPillion = 36,
            tireFrontSize = "110/70-17 Tubeless",
            tireRearSize = "150/60-17 Tubeless",
            engineOilSpec = "10W-50 Fully Synthetic (1700 ml)",
            coolantSpec = "Glycol Liquid Cooling (1200 ml)",
            chainSlackMm = "20 - 30 mm (Lube every 500 km)",
            sparkPlugSpec = "Bosch VR5NEU Triple Electrode"
        ),
        BikeModelProfile(
            id = "dominar250",
            name = "Dominar 250",
            series = "Dominar Series",
            engineSpec = "248.77cc DOHC Liquid-Cooled (27 PS / 23.5 Nm)",
            tankCapacityLiters = 13.0f,
            reserveCapacityLiters = 2.5f,
            coldTireFrontPsiSolo = 29,
            coldTireFrontPsiPillion = 29,
            coldTireRearPsiSolo = 33,
            coldTireRearPsiPillion = 36,
            tireFrontSize = "100/80-17 Tubeless",
            tireRearSize = "130/70-17 Tubeless",
            engineOilSpec = "10W-50 Semi-Synthetic (1500 ml)",
            coolantSpec = "Glycol Liquid Cooling (1100 ml)",
            chainSlackMm = "20 - 30 mm (Lube every 500 km)",
            sparkPlugSpec = "Champion RG4HC"
        )
    )

    fun getModelById(id: String): BikeModelProfile {
        return MODELS.find { it.id.equals(id, ignoreCase = true) } ?: MODELS.first()
    }

    fun getCountryByCode(code: String): CountryProfile {
        return COUNTRIES.find { it.code.equals(code, ignoreCase = true) } ?: COUNTRIES.first()
    }

    fun getLocalizedBikeName(model: BikeModelProfile, country: CountryProfile): String {
        return if (country.usesRouserBrand) {
            model.name.replace("Pulsar", "Rouser")
        } else {
            model.name
        }
    }
}
