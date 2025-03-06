package app.aaps.core.interfaces.aps

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)
@Serializable
data class OapsProfileDynamicAuto(

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
    var AutoBolusBoostSterkte: Int,
    var AutoBolusBoostDeltaT: Int,
    var AutoPersistentDagDrempel: Double,
    var AutoPersistentNachtDrempel: Double,
    var AutoPersistentGrens: Double,
    var Autobg_PercOchtend: Int,
    var Autobg_PercMiddag: Int,
    var Autobg_PercAvond: Int,
    var Autobg_PercNacht: Int,
    var AutoBoostPerc: Int,
    var AutominBoostPerc: Int,
    var AutomaxBoostPerc: Int,
    var AutoStappen: Boolean,
    var AutonewuamboostDrempel: Double,
    var AutonewuamboostPerc: Int,
    var AutohypoPerc: Int,
    var AutoBgIOBPerc: Int,

//    var GebruikAutoSens: Boolean,
    var Autoresistentie: Boolean,
    var AutominResistentiePerc: Int,
    var AutomaxResistentiePerc: Int,
    var AutodagResistentiePerc: Int,
    var AutodagResistentieTarget: Double,
    var AutonachtResistentiePerc: Int,
    var AutonachtResistentieTarget: Double,
    var AutoResistentieDagen: Int,
    var AutoResistentieUren: Int,
    var AutoresbasalPerc: Int,

    var AutoSMBversterkerPerc: Int,
    var AutoSMBversterkerWachttijd: Int,
    var AutostapactiviteteitPerc: Int,
    var Autostap5minuten: Int,
    var Autostapretentie: Int,


    var AutoWeekendDagen: String,
    var AutoOchtendStart: String,
    var AutoOchtendStartWeekend: String,
    var AutoMiddagStart: String,
    var AutoAvondStart: String,
    var AutoNachtStart: String,
    var TirTitr: String,

    var AutoGemTDD: Double,
    var AutoBasaalTDDPerc: Int,
    var AutoPiekverschuiving: Double,
    var AutoBasaalmin: Double,
    var AutoBasaalmax: Double,

)