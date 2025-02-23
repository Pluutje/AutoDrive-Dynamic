package app.aaps.core.interfaces.aps

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)
@Serializable
data class OapsProfileDynamic(

    var max_iob: Double,
    var max_daily_basal: Double,
    var max_basal: Double,
    var min_bg: Double,
    var max_bg: Double,
    var target_bg: Double,

    var sens: Double,

    var adv_target_adjustments: Boolean,

    var skip_neutral_temps: Boolean,
    var remainingCarbsCap: Int,

//    var A52_risk_enable: Boolean,
    var SMBInterval: Int,

    var allowSMB_with_high_temptarget: Boolean,
    var enableSMB_always: Boolean,

    var maxSMBBasalMinutes: Int,
    var maxUAMSMBBasalMinutes: Int,
    var bolus_increment: Double,

    var current_basal: Double,
    var temptargetSet: Boolean,
    var autosens_max: Double,
    var out_units: String,
    var lgsThreshold: Int?,

    //eigen
    var BolusBoostSterkte: Int,
    var BolusBoostDeltaT: Int,
    var PersistentDagDrempel: Double,
    var PersistentNachtDrempel: Double,
    var PersistentGrens: Double,
    var bg_PercOchtend: Int,
    var bg_PercMiddag: Int,
    var bg_PercAvond: Int,
    var bg_PercNacht: Int,
    var BoostPerc: Int,
    var minBoostPerc: Int,
    var maxBoostPerc: Int,
    var Stappen: Boolean,
    var newuamboostDrempel: Double,
    var newuamboostPerc: Int,
    var hypoPerc: Int,
    var BgIOBPerc: Int,

//    var GebruikAutoSens: Boolean,
    var resistentie: Boolean,
    var minResistentiePerc: Int,
    var maxResistentiePerc: Int,
    var dagResistentiePerc: Int,
    var dagResistentieTarget: Double,
    var nachtResistentiePerc: Int,
    var nachtResistentieTarget: Double,
    var ResistentieDagen: Int,
    var ResistentieUren: Int,
    var resbasalPerc: Int,

    var SMBversterkerPerc: Int,
    var SMBversterkerWachttijd: Int,
    var stapactiviteteitPerc: Int,
    var stap5minuten: Int,
    var stapretentie: Int,


    var WeekendDagen: String,
    var OchtendStart: String,
    var OchtendStartWeekend: String,
    var MiddagStart: String,
    var AvondStart: String,
    var NachtStart: String,
    var TirTitr: String,
)