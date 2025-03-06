package app.aaps.plugins.aps.DYN_AUTO

import android.os.Environment
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileDynamicAuto
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.openAPSAIMI.StepService
import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Scanner
import javax.inject.Inject
import javax.inject.Singleton

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.stats.TddCalculator



@Singleton
data class Bolus_Basaal_Auto(val BolusViaBasaal: Boolean, val BasaalStand: Float , val ResterendeTijd: Float)
data class Extra_Insuline_Auto(val ExtraIns_AanUit: Boolean, val ExtraIns_waarde: Float ,val CfTijd: Double,val CfIns: Float, val ExtraIns_tijd: Int)
data class uur_minuut1_Auto(val uur: Int, val minuut: Int)
data class Resistentie_class_Auto(val resistentie: Double, val log: String)
data class Basaal_class_Auto(val basaal: Double, val log: String)
data class gemTDD_class_Auto(val gemTDD: Double, val log: String)

class DetermineBasalDynAUTOSMB @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    val persistenceLayer: PersistenceLayer,
    val dateUtil: DateUtil
) {
    @Inject lateinit var tirCalculator: TirCalculator
    @Inject lateinit var tddCalculator: TddCalculator

// openapsaimi settings
    private var StapRetentie: Int = 0

    private var SMBWachtTrigger: Long = System.currentTimeMillis()/(60 * 1000)
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/")
    private val ActExtraIns = File(externalDir, "ANALYSE/Act-extra-ins.txt")
    private val BolusViaBasaal = File(externalDir, "ANALYSE/Bolus-via-basaal.txt")
    private val csvfile = File(externalDir, "ANALYSE/analyse.csv")
 //   private val calendarInstance = Calendar.getInstance()
    private val consoleError = mutableListOf<String>()
    private val consoleLog = mutableListOf<String>()


    private fun Double.toFixed2(): String = DecimalFormat("0.00#").format(round(this, 2))

    fun round_basal(value: Double): Double = value

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }

    fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)

    fun round(value: Double): Int {
        if (value.isNaN()) return 0
        val scale = 10.0.pow(2.0)
        return (Math.round(value * scale) / scale).toInt()
    }

    fun refreshTime() : uur_minuut1_Auto {
        val calendarInstance = Calendar.getInstance() // Nieuwe tijd ophalen

        val uur = calendarInstance[Calendar.HOUR_OF_DAY]
        val minuut = calendarInstance[Calendar.MINUTE]
        return uur_minuut1_Auto(uur,minuut)
    }


    fun Calc_Cf_IOB(IOB: Double, min_Cf: Double, max_Cf: Double, offset: Double, slope: Double, delta15: Double): Double {
        val halfSlope = slope / 2
        val adjustedIOB = (IOB + halfSlope) / (offset + halfSlope)
        val cf_iob = min_Cf + (max_Cf - min_Cf) / (1 + Math.pow(adjustedIOB, slope))
        val cf_delta = (1 + (2 * delta15) / 100).coerceIn(0.5,1.0)
        return cf_iob * cf_delta
    }

    fun gem_TDD(profile: OapsProfileDynamicAuto): gemTDD_class_Auto {

        // Bereken TDD-waarden en vermenigvuldig tddLast12H met 2 indien niet null
        val tddValues = listOf(
            tddCalculator.calculateDaily(-12, 0)?.totalAmount?.times(2), // tddLast12H
            tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount, // tdd1D
            tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount, // tdd2D
            tddCalculator.averageTDD(tddCalculator.calculate(3, allowMissingDays = false))?.data?.totalAmount  // tdd3D
        )

        // Vervang null-waarden door tdd_prof en bereken gemiddelde
        val TDD_gem = tddValues.map { it ?: profile.AutoGemTDD }.average()
        val log_TDD = buildString {
            tddValues.forEachIndexed { index, value ->
            appendLine(" ● TDD ${if (index == 0) "12 uur" else "${index} dag(en)"}: ${round(value ?: profile.AutoGemTDD, 1)}")
            }
        }

        return gemTDD_class_Auto(TDD_gem, log_TDD)
    }

    fun Auto_Basaal(profile: OapsProfileDynamicAuto): Basaal_class_Auto {


        // Tijd berekeningen
        val (uurVanDag, minuut) = refreshTime()
        val minuutTxt = "%02d".format(minuut)
        val tijd = uurVanDag + minuut / 60.0
        val tijd_corr = 24 / (2 * Math.PI)
        val verschuiving_corr = profile.AutoPiekverschuiving / tijd_corr
        val cos_Xwaarde = Math.cos((tijd / tijd_corr) - verschuiving_corr)

        // Basaal berekeningen
        val (gemTDD,log_TDD) = gem_TDD(profile)
        val tdd_uur = gemTDD / 24.0
        val basis_basaal = tdd_uur * (profile.AutoBasaalTDDPerc / 100.0)
        val berekend_basaal = basis_basaal + (basis_basaal * (cos_Xwaarde / 2.5))

        // Logging met StringBuilder (sneller dan string concatenation)
        var log_new_basaal = buildString {
            appendLine(" \n ﴿ new basaal  ﴾")
            appendLine(" ● Tijd: $uurVanDag:$minuutTxt")
            appendLine(" ● Berekend basaal: ${round(berekend_basaal, 2)} eh/u")
            appendLine(" ● Gem TDD: ${round(gemTDD, 1)} eh/dag")
        //    appendLine(" ● Cosinus: ${round(cos_Xwaarde, 1)}")
                }
        log_new_basaal = log_new_basaal + log_TDD
        return Basaal_class_Auto(berekend_basaal, log_new_basaal)
    }


    fun calculateCorrectionFactor(bgGem: Double, targetProfiel: Double, macht: Double, rel_std: Int): Double {
        var rel_std_cf = 1.0
        if (bgGem > targetProfiel) {
            rel_std_cf = 1.0/rel_std + 1.0
        }
        var cf = Math.pow(bgGem / (targetProfiel), macht) * rel_std_cf
        if (cf < 0.1) cf = 1.0

        return cf
    }

    fun logBgHistoryWithStdDev(startHour: Long, endHour: Long, uren: Long): Pair<Double, Double> {
        // Constants
        val MIN_READINGS_PER_HOUR = 8
        val MG_DL_TO_MMOL_L_CONVERSION = 18.0

        // Bereken start- en eindtijd
        val now = dateUtil.now()
        val startTime = now - T.hours(hour = startHour).msecs()
        val endTime = now - T.hours(hour = endHour).msecs()

        // Haal bloedglucosewaarden op
        val bgReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(startTime, endTime, false)

        // Controleer of er voldoende data is
        if (bgReadings.size < MIN_READINGS_PER_HOUR * uren) {
            return Pair(0.0, 0.0) // Onvoldoende data
        }

        // Bereken gemiddelde in mmol/L
        val totalBgValue = bgReadings.sumOf { it.value }
        val bgAverage = (totalBgValue / bgReadings.size) / MG_DL_TO_MMOL_L_CONVERSION

        // Bereken variantie en standaarddeviatie
        val variance = bgReadings.sumOf {
            val bgInMmol = it.value / MG_DL_TO_MMOL_L_CONVERSION
            (bgInMmol - bgAverage) * (bgInMmol - bgAverage)
        } / bgReadings.size

        val stdDev = Math.sqrt(variance)

        return Pair(bgAverage, stdDev)
    }

    fun Resistentie(profile: OapsProfileDynamicAuto): Resistentie_class_Auto {
        var log_resistentie = " ﴿ Resistentie Correctie ﴾" + "\n"

        if (!profile.Autoresistentie) {
            log_resistentie = log_resistentie + " → resistentie aan/uit: uit " + "\n"
            return Resistentie_class_Auto(1.0,log_resistentie)
        }
        log_resistentie = log_resistentie + " → resistentie aan/uit: aan " + "\n"

        var ResistentieCfEff = 0.0
        var resistentie_percentage = 100
        var target_profiel = 5.2
        val (uurVanDag,minuten) = refreshTime()

        val (NachtStartUur, NachtStartMinuut) = profile.AutoNachtStart.split(":").map { it.toInt() }
        val (OchtendStartUur, OchtendStartMinuut) = profile.AutoOchtendStart.split(":").map { it.toInt() }
        if (isInTijdBereik(uurVanDag, minuten, NachtStartUur, NachtStartMinuut, OchtendStartUur, OchtendStartMinuut)) {
            resistentie_percentage = profile.AutonachtResistentiePerc
            target_profiel = profile.AutonachtResistentieTarget
            log_resistentie = log_resistentie + " ● Resistentie sterkte (nacht): " + resistentie_percentage + "%" + "\n"
            log_resistentie = log_resistentie + "                            Target: " + target_profiel + " mmol/l" + "\n"
        } else {
            resistentie_percentage = profile.AutodagResistentiePerc
            target_profiel = profile.AutodagResistentieTarget
            log_resistentie = log_resistentie + " ● Resistentie sterkte (dag): " + resistentie_percentage + "%" + "\n"
            log_resistentie = log_resistentie + "                          Target: " + target_profiel + " mmol/l" + "\n"
        }

        val macht =  Math.pow(resistentie_percentage.toDouble(), 1.4)/2800
        val numPairs = profile.AutoResistentieDagen // Hier kies je hoeveel paren je wilt gebruiken
        val uren = profile.AutoResistentieUren

        val x = uren.toLong()         // Constante waarde voor ± x
        val intervals = mutableListOf<Pair<Long, Long>>()

        for (i in 1..numPairs) {
            val base = (24 * i).toLong()  // Verhoogt telkens met 24: 24, 48, 72, ...
            intervals.add(Pair(base , base - x))
        }

        val correctionFactors = mutableListOf<Double>()

        for ((index, interval) in intervals.take(numPairs).withIndex()) {
            // val bgGem = logBgHistory(interval.first, interval.second, x)
            val (bgGem, bgStdDev) = logBgHistoryWithStdDev(interval.first, interval.second, x)
            val rel_std = (bgStdDev/bgGem*100).toInt()
            val cf = calculateCorrectionFactor(bgGem, target_profiel, macht, rel_std)
            log_resistentie += " → Dag ${(index + 1)} : correctie percentage = " + (cf * 100).toInt() + "%" + "\n"
            log_resistentie += "   ϟ Bg gem: ${round(bgGem, 1)}     ϟ Rel StdDev: $rel_std %.\n"

            correctionFactors.add(cf)

        }
// Bereken CfEff met het gekozen aantal correctiefactoren
        var tot_gew_gem = 0
        for (i in 0 until numPairs) {
            val divisor = when (i) {
                0   -> 70
                1   -> 25
                2   -> 5
                3   -> 3
                4   -> 2
                else -> 1 // Aanpassen voor extra correctiefactoren indien nodig
            }
            ResistentieCfEff += correctionFactors[i] * divisor
            tot_gew_gem += divisor
        }

        ResistentieCfEff = ResistentieCfEff / tot_gew_gem.toDouble()

        val minRes = profile.AutominResistentiePerc.toDouble()/100
        val maxRes = profile.AutomaxResistentiePerc.toDouble()/100

        ResistentieCfEff = ResistentieCfEff.coerceIn(minRes, maxRes)

        if (ResistentieCfEff > minRes && ResistentieCfEff < maxRes){
            log_resistentie = log_resistentie + "\n" + " »» Cf_eff = " + (ResistentieCfEff * 100).toInt() + "%" + "\n"
        } else {
            log_resistentie = log_resistentie + "\n" + " »» Cf_eff (begrensd) = " + (ResistentieCfEff * 100).toInt() + "%" + "\n"
        }

        return Resistentie_class_Auto(ResistentieCfEff,log_resistentie)

    }

    fun ActExtraIns(profile: OapsProfileDynamicAuto): Extra_Insuline_Auto {

        val tijdNu = System.currentTimeMillis()/(60 * 1000)
        var extra_insuline_tijdstip = "0"
        var extra_insuline_tijd = "0"
        var extra_insuline_percentage = "0"
        val extra_insuline: Boolean
        val corr_factor: Float
        val Cf_overall: Float
        val Cf_tijd : Double


        var extra_insuline_check = "0"

        try {
            val sc = Scanner(ActExtraIns)
            var teller = 1
            while (sc.hasNextLine()) {
                val line = sc.nextLine()
                if (teller == 1) { extra_insuline_check = line}
                if (teller == 2) { extra_insuline_tijdstip = line}
                if (teller == 3) { extra_insuline_tijd = line}
                if (teller == 4) { extra_insuline_percentage = line}

                teller += 1
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val verstreken_tijd = (tijdNu - extra_insuline_tijdstip.toFloat()).toInt()
        var resterendeTijd = extra_insuline_tijd.toInt() - verstreken_tijd


        if (verstreken_tijd < extra_insuline_tijd.toInt() && extra_insuline_check == "checked") {
            extra_insuline = true
            corr_factor = (extra_insuline_percentage.toFloat())

            val Max_Cf_tijd = 1.0 + profile.AutoBolusBoostSterkte.toDouble()/100
            val Min_Cf_tijd = 1.0 - profile.AutoBolusBoostSterkte.toDouble()/100
            val Offset_tijd = extra_insuline_tijd.toFloat()/2 + profile.AutoBolusBoostDeltaT.toFloat()
            val Slope_tijd = 3
            Cf_tijd = Min_Cf_tijd + (Max_Cf_tijd - Min_Cf_tijd)/(1 + Math.pow((verstreken_tijd.toDouble() / Offset_tijd) , Slope_tijd.toDouble()))
            Cf_overall = round(corr_factor * Cf_tijd,2).toFloat()
        } else {
            extra_insuline = false
            Cf_overall = 100.0f
            Cf_tijd = 1.0
            corr_factor = 100.0f
            resterendeTijd = 0
        }

        return Extra_Insuline_Auto(extra_insuline,Cf_overall,Cf_tijd,corr_factor,resterendeTijd)
    }

    fun BolusViaBasaal(): Bolus_Basaal_Auto {

        val tijdNu = System.currentTimeMillis()/(60 * 1000)
        var bolus_basaal_check = "0"
        var bolus_basaal_tijdstip = "0"
        var bolus_basaal_tijd = "0"
        var insuline = "0"

        val bolus_via_basaal: Boolean
        val temp_basaal: Float


        try {
            val sc = Scanner(BolusViaBasaal)
            var teller = 1
            while (sc.hasNextLine()) {
                val line = sc.nextLine()
                if (teller == 1) { bolus_basaal_check = line}
                if (teller == 2) { bolus_basaal_tijdstip = line}
                if (teller == 3) { bolus_basaal_tijd = line}
                if (teller == 4) { insuline = line}

                teller += 1
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val verstreken_tijd_bolus = tijdNu.toInt() - bolus_basaal_tijdstip.toInt()
        val rest_tijd = bolus_basaal_tijd.toInt() - verstreken_tijd_bolus
        if (verstreken_tijd_bolus <= ((bolus_basaal_tijd.toInt())+1) && bolus_basaal_check == "checked") {
            bolus_via_basaal = true
            temp_basaal = insuline.toFloat() * 60 / bolus_basaal_tijd.toFloat()

        } else {
            bolus_via_basaal = false
            temp_basaal = 0.0f
        }

        return Bolus_Basaal_Auto(bolus_via_basaal,temp_basaal,rest_tijd.toFloat())

    }


    // Functie om te controleren of de huidige tijd binnen het tijdsbereik valt
    fun isInTijdBereik(hh: Int, mm: Int, startUur: Int, startMinuut: Int, eindUur: Int, eindMinuut: Int): Boolean {
        val startInMinuten = startUur * 60 + startMinuut
        val eindInMinuten = eindUur * 60 + eindMinuut
        val huidigeTijdInMinuten = hh * 60 + mm

        // Als het eindtijdstip voor middernacht is (bijvoorbeeld van 23:00 tot 05:00), moeten we dat apart behandelen
        return if (eindInMinuten < startInMinuten) {
            // Tijdsbereik over de middernacht (bijvoorbeeld 23:00 tot 05:00)
            huidigeTijdInMinuten >= startInMinuten || huidigeTijdInMinuten < eindInMinuten
        } else {
            // Normale tijdsbereik (bijvoorbeeld van 08:00 tot 17:00)
            huidigeTijdInMinuten in startInMinuten..eindInMinuten
        }
    }

    // we expect BG to rise or fall at the rate of BGI,
    // adjusted by the rate at which BG would need to rise /
    // fall to get eventualBG to target over 2 hours
    fun calculate_expected_delta(targetBg: Double, eventualBg: Double, bgi: Double): Double {
        // (hours * mins_per_hour) / 5 = how many 5 minute periods in 2h = 24
        val fiveMinBlocks = (2 * 60) / 5
        val targetDelta = targetBg - eventualBg
        return /* expectedDelta */ round(bgi + (targetDelta / fiveMinBlocks), 1)
    }

    fun convert_bg(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")
    //DecimalFormat("0.#").format(profileUtil.fromMgdlToUnits(value))
    //if (profile.out_units === "mmol/L") round(value / 18, 1).toFixed(1);
    //else Math.round(value);

    fun enable_smb(profile: OapsProfileDynamicAuto, microBolusAllowed: Boolean, meal_data: MealData, target_bg: Double): Boolean {
        // disable SMB when a high temptarget is set
        if (!microBolusAllowed) {
            consoleError.add("SMB disabled (!microBolusAllowed)")
            return false
        } else if (!profile.allowSMB_with_high_temptarget && profile.temptargetSet && target_bg > 100) {
            consoleError.add("SMB disabled due to high temptarget of $target_bg")
            return false
        }

        // enable SMB/UAM if always-on (unless previously disabled for high temptarget)
        if (profile.enableSMB_always) {
            consoleError.add("SMB altijd geactiveerd")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) if a low temptarget is set
        if (true && (profile.temptargetSet && target_bg < 100)) {
            consoleError.add("SMB enabled for temptarget of ${convert_bg(target_bg)}")
            return true
        }

        consoleError.add("SMB disabled (no enableSMB preferences active or no condition satisfied)")
        return false
    }

    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        consoleError.add(msg)
    }

    private fun getMaxSafeBasal(profile: OapsProfileDynamicAuto): Double =
        min(profile.max_basal, min(125.0 * profile.max_daily_basal, 125.0 * profile.current_basal))

    fun setTempBasal(_rate: Double, duration: Int, profile: OapsProfileDynamicAuto, rT: RT, currenttemp: CurrentTemp): RT {
        //var maxSafeBasal = Math.min(profile.max_basal, 3 * profile.max_daily_basal, 4 * profile.current_basal);

        val maxSafeBasal = getMaxSafeBasal(profile)
        var rate = _rate
        if (rate < 0) rate = 0.0
        else if (rate > maxSafeBasal) rate = maxSafeBasal

        val suggestedRate = round_basal(rate)
        if (currenttemp.duration > (duration - 10) && currenttemp.duration <= 120 && suggestedRate <= currenttemp.rate * 1.2 && suggestedRate >= currenttemp.rate * 0.8 && duration > 0) {
            rT.reason.append(" ${currenttemp.duration}m left and ${currenttemp.rate.withoutZeros()} ~ req ${suggestedRate.withoutZeros()}U/hr: no temp required")
            return rT
        }

        if (suggestedRate == profile.current_basal) {
            if (profile.skip_neutral_temps) {
                if (currenttemp.duration > 0) {
                    reason(rT, "Suggested rate is same as profile rate, a temp basal is active, canceling current temp")
                    rT.duration = 0
                    rT.rate = 0.0
                    return rT
                } else {
                    reason(rT, "Suggested rate is same as profile rate, no temp basal is active, doing nothing")
                    return rT
                }
            } else {
                reason(rT, "Setting neutral temp basal of ${profile.current_basal}U/hr")
                rT.duration = duration
                rT.rate = suggestedRate
                return rT
            }
        } else {
            rT.duration = duration
            rT.rate = suggestedRate
            return rT
        }
    }

    fun determine_basal(
        glucose_status: GlucoseStatus, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfileDynamicAuto, autosens_data: AutosensResult, meal_data: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, dynIsfMode: Boolean
    ): RT {
        consoleError.clear()
        consoleLog.clear()
        var rT = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )

    // Dag - Nacht
        val (uurVanDag,minuut) = refreshTime()
        val minuutTxt = String.format("%02d", minuut)

        val dayMapping = mapOf(
            "ma" to Calendar.MONDAY,
            "di" to Calendar.TUESDAY,
            "wo" to Calendar.WEDNESDAY,
            "do" to Calendar.THURSDAY,
            "vr" to Calendar.FRIDAY,
            "za" to Calendar.SATURDAY,
            "zo" to Calendar.SUNDAY
        )

// Converteer de invoerstring naar een lijst van Calendar-dagen
        val weekendString = profile.AutoWeekendDagen
        val weekendDays = weekendString.split(",")
            .mapNotNull { dayMapping[it.trim()] } // Map afkortingen naar Calendar-waarden en filter null-waarden

// Wijs de lijst toe aan profile.WeekendDays
        val calendarInstance = Calendar.getInstance() // Nieuwe tijd ophalen
        val dayOfWeek = calendarInstance[Calendar.DAY_OF_WEEK]
        val weekend = dayOfWeek in weekendDays

 // Split de tijden in uren en minuten voor de verschillende delen van de dag
 // Determine morning start time based on weekday or weekend
        val (OchtendStartUur, OchtendStartMinuut) = if (weekend) {
            profile.AutoOchtendStartWeekend.split(":").map { it.toInt() }
        } else {
            profile.AutoOchtendStart.split(":").map { it.toInt() }
        }

        val (MiddagStartUur, MiddagStartMinuut) = profile.AutoMiddagStart.split(":").map { it.toInt() }
        val (AvondStartUur, AvondStartMinuut) = profile.AutoAvondStart.split(":").map { it.toInt() }
        val (NachtStartUur, NachtStartMinuut) = profile.AutoNachtStart.split(":").map { it.toInt() }

// Boolean variabelen voor de verschillende dagdelen
        var isOchtend = false
        var isMiddag = false
        var isAvond = false
        var isNacht = false
        var DeelvanDag = ""

// Controleer welk tijdsinterval van toepassing is en zet de booleans
        when {
            isInTijdBereik(uurVanDag, minuut, OchtendStartUur, OchtendStartMinuut, MiddagStartUur, MiddagStartMinuut) -> {
                isOchtend = true
                DeelvanDag = "Ochtend"
            }
            isInTijdBereik(uurVanDag, minuut, MiddagStartUur, MiddagStartMinuut, AvondStartUur, AvondStartMinuut) -> {
                isMiddag = true
                DeelvanDag = "Middag"
            }
            isInTijdBereik(uurVanDag, minuut, AvondStartUur, AvondStartMinuut, NachtStartUur, NachtStartMinuut) -> {
                isAvond = true
                DeelvanDag = "Avond"
            }
            isInTijdBereik(uurVanDag, minuut, NachtStartUur, NachtStartMinuut, OchtendStartUur, OchtendStartMinuut) -> {
                isNacht = true
                DeelvanDag = "Nacht"
            }
        }

        var log_DagNacht = " ﴿ Dag Nacht Weekend ﴾" + "\n"
        if (weekend) {
            log_DagNacht += " ● Ochtendstart tijd: " + profile.AutoOchtendStartWeekend +  "\n"
        } else {
            log_DagNacht += " ● Ochtendstart tijd: " + profile.AutoOchtendStart +  "\n"
        }
        log_DagNacht +=  " ● Middagstart tijd: " + profile.AutoMiddagStart +  "\n"
        log_DagNacht +=  " ● Avondstart tijd: " + profile.AutoAvondStart +  "\n"
        log_DagNacht +=  " ● Nachtstart tijd: " + profile.AutoNachtStart +  "\n"
        log_DagNacht += " ~ Weekend dagen: " + weekendString + " → " + weekend +  "\n"
        log_DagNacht +=  " ● Tijd: " + uurVanDag.toString() + ":" + minuutTxt + " → Deel v/d Dag = " + DeelvanDag + "\n"
        log_DagNacht +=  " \n"

     val    DeelvanDag_factor = when {
            isOchtend -> profile.Autobg_PercOchtend.toDouble() / 100
            isMiddag -> profile.Autobg_PercMiddag.toDouble() / 100
            isAvond -> profile.Autobg_PercAvond.toDouble() / 100
            isNacht -> profile.Autobg_PercNacht.toDouble() / 100
            else -> 1.0  // Als geen enkele voorwaarde waar is, stel het in op de standaardwaarde 1.0
        }
// Einde Dag Nacht

        var (auto_basaal,log_basaal) = Auto_Basaal(profile)

        val (resistentie_factor,log_res) = Resistentie(profile)
        val res_basal_cf = profile.AutoresbasalPerc
        val resistentie_basal_factor = ((resistentie_factor - 1)*res_basal_cf/100 + 1) * DeelvanDag_factor
        val ResistentieCfEff_info = (resistentie_factor *100).toInt()

        // var log_BasalCorr = " ﴿ Basaal correctie ﴾" + "\n"
        log_basaal += " ● resistentie correctie: " + (((resistentie_factor - 1)*res_basal_cf/100 + 1)*100).toInt() + "%" + "\n"
        log_basaal += " ● deel vd dag correctie: " + (DeelvanDag_factor * 100).toInt() + "%" + "\n"
        log_basaal += " → basaal correctie effectief: " + (resistentie_basal_factor * 100).toInt() + "%" + "\n"

        var new_basaal = (auto_basaal  * resistentie_basal_factor).coerceIn(profile.AutoBasaalmin, profile.AutoBasaalmax)
        //basal = round_basal(basal)
        if (new_basaal != auto_basaal) {
            log_basaal += " → basaal van: " + round(auto_basaal,2) + "eh/u naar:" + round(new_basaal,2) + "eh/u" + "\n"
          } else {
            log_basaal += " → basaal ongewijzigd: " + round(new_basaal,2) + "eh/u" + "\n"
        }

        // TODO eliminate
        val deliverAt = currentTime

        // TODO eliminate
      //  val profile_current_basal = round_basal(profile.current_basal) // * resistentie_basal_factor)
       // var basal = profile_current_basal

        // TODO eliminate
        val systemTime = currentTime

        // TODO eliminate
        val bgTime = glucose_status.date
        val minAgo = round((systemTime - bgTime) / 60.0 / 1000.0, 1)
        // TODO eliminate
        val bg = glucose_status.glucose
        // TODO eliminate
        val noise = glucose_status.noise

        val (bolus_basaal_AanUit,bolus_basaal_waarde, rest_tijd_basaal) = BolusViaBasaal()
        val (extraIns_AanUit,extraIns_Factor,Cf_tijd,Cf_Ins :Float ,rest_tijd) = ActExtraIns(profile)

        if (bolus_basaal_AanUit) {
            new_basaal = round_basal(bolus_basaal_waarde.toDouble())
            consoleError.add(" ﴿―― Bolus via basaal ――﴾")
            consoleError.add(" → basaal: " + round(new_basaal,2) + "(u/h)")
            consoleError.add(" → nog $rest_tijd_basaal minuten resterend")
            consoleError.add(" ﴿―――――――――――――――﴾")

            rT.reason.append("=> Insuline via basaal:  $bolus_basaal_waarde u/h")
            rT.deliverAt = deliverAt
            rT.duration = 30
            rT.rate = new_basaal
            return rT
        }

        // 38 is an xDrip error state that usually indicates sensor failure
        // all other BG values between 11 and 37 mg/dL reflect non-error-code BG values, so we should zero temp for those
        if (bg <= 10 || bg == 38.0 || noise >= 3) {  //Dexcom is in ??? mode or calibrating, or xDrip reports high noise
            rT.reason.append("CGM is calibrating, in ??? state, or noise is high")
        }
        if (minAgo > 12 || minAgo < -5) { // Dexcom data is too old, or way in the future
            rT.reason.append("If current system time $systemTime is correct, then BG data is too old. The last BG data was read ${minAgo}m ago at $bgTime")
            // if BG is too old/noisy, or is changing less than 1 mg/dL/5m for 45m, cancel any high temps and shorten any long zero temps
        } else if (bg > 60 && flatBGsDetected) {
            rT.reason.append("Error: CGM data is unchanged for the past ~45m")
        }
        if (bg <= 10 || bg == 38.0 || noise >= 3 || minAgo > 12 || minAgo < -5 || (bg > 60 && flatBGsDetected)) {
            if (currenttemp.rate > new_basaal) { // high temp is running
                rT.reason.append(". Replacing high temp basal of ${currenttemp.rate} with neutral temp of $new_basaal")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = new_basaal
                return rT
            } else if (currenttemp.rate == 0.0 && currenttemp.duration > 30) { //shorten long zero temps to 30m
                rT.reason.append(". Shortening " + currenttemp.duration + "m long zero temp to 30m. ")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = 0.0
                return rT
            } else { //do nothing.
                rT.reason.append(". Temp ${currenttemp.rate} <= current basal ${round(new_basaal, 2)}U/hr; doing nothing. ")
                return rT
            }
        }


        // if min and max are set, then set target to their average
        var target_bg = (profile.min_bg + profile.max_bg) / 2
        var min_bg = profile.min_bg
        var max_bg = profile.max_bg



        // adjust min, max, and target BG for sensitivity, such that 50% increase in ISF raises target from 100 to 120
        if (profile.temptargetSet) {

        } else {
            if (false && autosens_data.ratio < 1 || false && autosens_data.ratio > 1) {
                // with a target of 100, default 0.7-1.2 autosens min/max range would allow a 93-117 target range
                min_bg = round((min_bg - 60) / autosens_data.ratio, 0) + 60
                max_bg = round((max_bg - 60) / autosens_data.ratio, 0) + 60
                var new_target_bg = round((target_bg - 60) / autosens_data.ratio, 0) + 60
                // don't allow target_bg below 80
                new_target_bg = max(80.0, new_target_bg)
                if (target_bg == new_target_bg)
                    consoleLog.add("target_bg unchanged: $new_target_bg; ")
                else
                    consoleLog.add("target_bg from $target_bg to $new_target_bg; ")

                target_bg = new_target_bg

            }
        }

// Stappen
        var log_Stappen = " ﴿ Stappen ﴾" + "\n"
        val min5Stap = profile.Autostap5minuten
        val min30Stap = ((min5Stap * 30 / 5)/1.6).toInt()
        //   val min60Stap = ((min5Stap * 60 / 5)/2.6).toInt()
        var stap_perc = 100f
// Variabelen om de actieve duur en huidige status bij te houden
        if (profile.AutoStappen) {
            val thresholds = mapOf(
                " 5 minuten" to min5Stap,
                "30 minuten" to min30Stap  //,
                //  "60 minuten" to min60Stap
            )
            var allThresholdsMet = true

            // Controleer de drempels
            thresholds.forEach { (label, threshold) ->
                val steps = when (label) {
                    " 5 minuten" -> StepService.getRecentStepCount5Min()
                    "30 minuten" -> StepService.getRecentStepCount30Min()
                    //   "60 minuten" -> StepService.getRecentStepCount60Min()
                    else -> 0
                }
                log_Stappen += " ● $label: $steps stappen ${if (steps >= threshold) ">= drempel ($threshold)" else "< drempel ($threshold)"}\n"
                if (steps < threshold) allThresholdsMet = false
            }

            if (allThresholdsMet) {
                StapRetentie = (StapRetentie + 1).coerceAtMost(profile.Autostapretentie) // Limiteer
                log_Stappen += " ↗ Drempel overschreden. ($StapRetentie maal).\n"
            } else {
                log_Stappen += " → Drempel niet overschreden.\n"
                if (StapRetentie > 0) {
                    StapRetentie = StapRetentie -1

                } // Verlaag actieve duur als deze nog actief is
            }

            // Verhoog target
            if (StapRetentie > 0) {
                if (allThresholdsMet) {
                    stap_perc = profile.AutostapactiviteteitPerc.toFloat()
                    log_Stappen += " ● Overschrijding drempels → Insuline perc. $stap_perc %.\n"
                    target_bg += 9
                } else {
                    stap_perc = profile.AutostapactiviteteitPerc.toFloat()
                    log_Stappen += " ● nog $StapRetentie * retentie → Insuline perc. $stap_perc %.\n"
                    target_bg += 9
                }
            } else {
                stap_perc = 100f
                log_Stappen += " ● Geen activiteit → Insuline perc. $stap_perc %.\n"
            }
        } else {
            log_Stappen += " ● Stappen correctie uitgeschakeld.\n"
        }
        val display_Stap_perc = stap_perc.toInt()


   // einde stappen

        val iobArray = iob_data_array
        val iob_data = iobArray[0]

        val tick: String

        tick = if (glucose_status.delta > -0.5) {
            "+" + round(glucose_status.delta)
        } else {
            round(glucose_status.delta).toString()
        }
        val minDelta = min(glucose_status.delta, glucose_status.shortAvgDelta)
        val minAvgDelta = min(glucose_status.shortAvgDelta, glucose_status.longAvgDelta)
        val maxDelta = max(glucose_status.delta, max(glucose_status.shortAvgDelta, glucose_status.longAvgDelta))


        val startTime = dateUtil.now() -  T.mins(min = 50).msecs()     //T.hours(hour = 1).msecs()
        val endTime = dateUtil.now()
        val bgReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(startTime, endTime, false)
        var delta15 = 0.0
        var delta15_oud = 0.0
        var bg_act = round(bgReadings[0].value/18,2)
        var delta5 = 0f
        var delta30 = 0f
        if (bgReadings.size >= 7) {
            delta15 = (bgReadings[0].value - bgReadings[3].value)
            delta15_oud = (bgReadings[1].value - bgReadings[4].value)
            bg_act = round(bgReadings[0].value/18,2)
            delta5 = (bgReadings[0].value - bgReadings[1].value).toFloat()
            delta30 = (bgReadings[0].value - bgReadings[6].value).toFloat()
        }

        var max_iob = profile.max_iob // maximum amount of non-bolus IOB OpenAPS will ever deliver
        var log_maxIOB = " ﴿ max IOB ﴾" + "\n"
        log_maxIOB +=  " ● max IOB : " + max_iob + "eh"
        if (delta15 > 18) {
            max_iob = max_iob + Math.max(0.0 , ((bg_act - 9.0)/5))
            log_maxIOB +=  " verhoogd → " + round(max_iob,1) + "eh"
        }
        log_maxIOB +=  "\n"


        val Persistent_ISF_perc: Double
        val Display_Persistent_perc: Int
        val Persistent_Drempel: Double
        val extraNachtrange: Double
        val NachtDrempelCorr: Double
        val extraNachtPerc: Double
        if (!isNacht) {
            Persistent_Drempel = profile.AutoPersistentDagDrempel
            extraNachtrange = 0.0
            NachtDrempelCorr = 0.0
            extraNachtPerc = 1.0
        } else {
            Persistent_Drempel = profile.AutoPersistentNachtDrempel
            extraNachtrange = 0.5
            extraNachtPerc = 1.1
            NachtDrempelCorr = 0.5
        }

        val Persistent_info: String
        val Pers_grensL = profile.AutoPersistentGrens * 18 - extraNachtrange
        val Pers_grensH = (profile.AutoPersistentGrens * 18) + 2 + extraNachtrange
        var log_Persistent = " ﴿ Persistent hoog ﴾" + "\n"
        if (delta5>-Pers_grensL && delta5<Pers_grensH && delta15>-Pers_grensL-1 && delta15<Pers_grensH+1 && delta30>-Pers_grensL-2 && delta30<Pers_grensH+2 && bg_act > Persistent_Drempel) {
            Persistent_ISF_perc = (((bg_act - Persistent_Drempel-NachtDrempelCorr) / 10.0) + 1.0)*100*extraNachtPerc
            Display_Persistent_perc = Persistent_ISF_perc.toInt()
            log_Persistent += " → Persistent hoge Bg gedetecteerd" + "\n"
            log_Persistent +=  " ● " + DeelvanDag + " → Drempel = " + round(Persistent_Drempel,1) + "\n"
            log_Persistent +=  " ● Bg= " + round(bg_act, 1) + " → Insuline perc = " + Display_Persistent_perc + "%" + "\n"

            Persistent_info = " ● Persistent hoge Bg → Insuline perc = " + Display_Persistent_perc + "%"
        } else {
            Persistent_ISF_perc = 100.0
            Display_Persistent_perc = Persistent_ISF_perc.toInt()
            log_Persistent +=  " ● geen Persistent hoge Bg gedetecteerd" + "\n"
            log_Persistent +=  " ● " + DeelvanDag + " → Drempel = " + round(Persistent_Drempel,1) + "\n"

            Persistent_info = " ● Bg niet Persistent hoog → perc = 100%"
        }

        var log_BgIOB = " ﴿ Bg - IOB ﴾" + "\n"

        var Bg_ISF_perc: Double
        var Display_Bg_ISF_perc: Int

        var Cf_IOB = Calc_Cf_IOB(iob_data.iob,0.8,1.2,1.5,6.0, delta15)

        val Delta_Target = bg_act - (target_bg/18)
        val helling = (10 + Delta_Target) * 1.0
        val Bg_factor = (Delta_Target/helling * 1.5 + 1)
        val Bg_ISF_factor = Bg_factor * DeelvanDag_factor * Cf_IOB

        Bg_ISF_perc = Bg_ISF_factor * 100
        Display_Bg_ISF_perc = Bg_ISF_perc.toInt()
        log_BgIOB +=  " ● Bg = " + bg_act + " → Bg Cf = " + round(Bg_factor,2) + "\n"
        log_BgIOB +=  " ● IOB = " + round(iob_data.iob,2) + " → IOB Cf = " + round(Cf_IOB,2) + "\n"
        log_BgIOB +=  " ● Deel v/d dag = " + DeelvanDag + " → Cf = " + round(DeelvanDag_factor,2) + "\n"
        log_BgIOB +=  " ● Bg/IOB corr eff = " + Display_Bg_ISF_perc + "%" + "\n"

        // UAM-boost Bg en hypo correctie en IOB correctie

        val min_Cf_UamBg = 0.9
        val max_Cf_UamBg = 1.1
        val offset_UamBg = (target_bg/18 + 1)
        val slope_UamBg = 7.0

        val Cf_UAMBoost_Bg: Double
        Cf_UAMBoost_Bg = max_Cf_UamBg + (min_Cf_UamBg - max_Cf_UamBg)/(1 + Math.pow((bg_act / offset_UamBg) , slope_UamBg))


        Cf_IOB = Calc_Cf_IOB(iob_data.iob,0.6,1.1,2.0,6.0,delta15)

        // einde UAM-boost Bg en hypo correctie

        // code nieuwe uam-boost
        var uam_boost_percentage = profile.AutonewuamboostPerc.toDouble()

        val drempel_uam = profile.AutonewuamboostDrempel * 18
        val  rest_uam: Double
        val display_UAM_Perc: Int
        var log_UAMBoost = " ﴿ UAM boost ﴾" + "\n"
        if (delta15 >= drempel_uam && !isNacht) {
            rest_uam = delta15 - drempel_uam
            val extra = 10 * Math.pow((delta15 - delta15_oud), (1.0 / 3.0))
            uam_boost_percentage = uam_boost_percentage + rest_uam.toInt() + extra.toInt()
            uam_boost_percentage = uam_boost_percentage * Cf_UAMBoost_Bg
            uam_boost_percentage = uam_boost_percentage * Cf_IOB
            display_UAM_Perc = uam_boost_percentage.toInt()

            log_UAMBoost +=  " ● UAM-Boost perc = " + display_UAM_Perc + "%" + "\n"
            log_UAMBoost +=  " ● ∆15=" + round(delta15/18,2) + " >= " + round(drempel_uam/18,2) + "\n"
            log_UAMBoost +=  " ● Bg correctie = " + round(Cf_UAMBoost_Bg,2) + "\n"
            log_UAMBoost +=  " ● IOB correctie = " + round(Cf_IOB,2) + "\n"

        } else {
            if (delta15 < 0 && !isNacht) {
                uam_boost_percentage = 100.0 + (delta15 * 2.5).coerceIn(-50.0, 0.0)
                display_UAM_Perc = uam_boost_percentage.toInt()
                log_UAMBoost += " ● ∆15 = " + round(delta15/18,2) + " < 0 "  + "\n"

            } else {
                if (isNacht) {
                    uam_boost_percentage = 100.0
                    display_UAM_Perc = uam_boost_percentage.toInt()
                    log_UAMBoost += " ● Nacht → UAM Boost Niet geactiveerd" + "\n"
                } else {
                    uam_boost_percentage = 100.0
                    display_UAM_Perc = uam_boost_percentage.toInt()
                    log_UAMBoost += " ● 0 < ∆15 (" + round(delta15/18,2) + ") < " + round(drempel_uam/18,2) + " → Niet geactiveerd" + "\n"
                }
            }

            log_UAMBoost += " ● UAM-Boost perc = " + display_UAM_Perc + "%" + "\n"
        }

        //einde code nieuwe uam-boost

        // hypo correctie code
        var log_hypo = " ﴿ Hypo Correctie ﴾" + "\n"
        var hypo_corr_percentage = 100
        if (bg_act < target_bg/18){
            hypo_corr_percentage = profile.AutohypoPerc
            log_hypo += " ● Bg (" + round(bg_act,1).toString() + ") < Target (" + round(target_bg/18,1).toString() + ") → Hypo % = " + hypo_corr_percentage.toString() +"%"
        } else {
            hypo_corr_percentage = 100
            log_hypo += " ● Geen Hypo detectie → perc = " + hypo_corr_percentage.toString() +"%"
        }
        log_hypo += "\n"

        // einde hypo correctie


// IOB - BG code
        var log_BG_IOB = " ﴿ ↑Bg ↓IOB Correctie ﴾" + "\n"
        var BG_IOB_percentage = 100
        if ((bg_act-5)/(iob_data.iob+1) > 2){
            BG_IOB_percentage = profile.AutoBgIOBPerc
            log_BG_IOB += " ● Hoge Bg (" + round(bg_act,1).toString() + ") Lage IOB (" + round(iob_data.iob,1).toString() + ") → Bg_IOB % = " + BG_IOB_percentage.toString() +"%"
        } else {
            BG_IOB_percentage = 100
            log_BG_IOB += " ● Geen ↑Bg ↓IOB corr. → perc = " + BG_IOB_percentage.toString() +"%"
        }
        log_BG_IOB += "\n"


// Einde IOB - Bg code


        var info_cf = 100

        if (extraIns_AanUit) {
            info_cf = extraIns_Factor.toInt()
            log_UAMBoost += " Bolus-Boost actief → perc.=$info_cf%" + "\n"

            if (uam_boost_percentage > 100) {
                log_UAMBoost += " UAM-Boost zou " + display_UAM_Perc + " % geweest zijn" + "\n"
            }

        } else {
            info_cf = uam_boost_percentage.toInt()
            if (isNacht) {
                log_UAMBoost += " ● Nachttijd → Boost niet actief" + "\n"
            }

        }

        var log_ExtraIns = ""
        if (extraIns_AanUit) {
            val display_Cf_Ins = Cf_Ins.toInt()
            log_ExtraIns = " \n" + " ﴿ Extra bolus insuline  ―――――﴾" + "\n"
            log_ExtraIns +=  " → Nog $rest_tijd minuten resterend" + "\n"
            log_ExtraIns +=  " ● Opgegeven perc = " + display_Cf_Ins + "%" + "\n"
            log_ExtraIns +=  " ● Dynamische factor = " + round(Cf_tijd,2) + "\n"
            log_ExtraIns +=  " ● Overall perc = $info_cf%" + "\n"

            uam_boost_percentage = 100.0
        }

        // Extra SMB code test
        val SMB_perc = profile.AutoSMBversterkerPerc
        val SMB_wachttijd = profile.AutoSMBversterkerWachttijd
        var log_SMB_versterker = " ﴿ SMB versterker ﴾" + "\n"
        var smb_factor = 1.0
        if ((System.currentTimeMillis()/(60 * 1000) - SMBWachtTrigger) > SMB_wachttijd && delta15 >= 15 && delta15 >= (delta15_oud + 3)  && bg_act > (target_bg/18 + 0)){
            SMBWachtTrigger = System.currentTimeMillis()/(60 * 1000)

            log_SMB_versterker +=  "Geactiveerd SMB perc: $SMB_perc %" + "\n"
            smb_factor = SMB_perc.toDouble() /100
        } else {
            var SMB_tijd = SMB_wachttijd - (System.currentTimeMillis()/(60 * 1000) - SMBWachtTrigger)
            if (SMB_tijd >= 0) {
                log_SMB_versterker += " Nog " + (SMB_tijd).toString() + " min. tot volgende versterking" + "\n"
            }  else {
                SMB_tijd = SMB_tijd * -1
                log_SMB_versterker += (SMB_tijd).toString() + " min. sinds laatste versterking" + "\n"
            }
        }

        val Bg_ISF_cf = Bg_ISF_perc/100
        val Persistent_ISF_cf = Persistent_ISF_perc/100
        val uam_boost_cf = uam_boost_percentage/100
        val extraIns_cf = extraIns_Factor.toDouble()/100
        val stap_cf = stap_perc.toDouble()/100

        val hypo_corr_cf = hypo_corr_percentage.toDouble() /100
        val bg_iob_cf = BG_IOB_percentage.toDouble() /100


        var overall_cf = Bg_ISF_cf * Persistent_ISF_cf * uam_boost_cf * extraIns_cf * resistentie_factor * stap_cf * hypo_corr_cf * bg_iob_cf


        overall_cf = overall_cf.coerceIn(profile.AutominBoostPerc.toDouble()/100, profile.AutomaxBoostPerc.toDouble()/100)

        val Display_overall_perc = (overall_cf *100).toInt()

        var sens = profile.sens
        sens = sens / overall_cf




        consoleError.add(" ")
        consoleError.add("֎======== Auto =========֎")
        consoleError.add(" ")
        consoleError.add("֎====== ISF ֎۝֎ INFO ======֎")
        consoleError.add(" ")

        consoleError.add(" ● Bg= " + round(bg_act, 1) + " IOB= " + round(iob_data.iob,1) + " → perc = " + Display_Bg_ISF_perc + "%")
        consoleError.add(" ● ↑Bg ↓IOB correctie: → perc = " + BG_IOB_percentage + "%")
        consoleError.add(Persistent_info)
        consoleError.add(" ● UAM/delta correctie: → perc = " + info_cf + "%")
        consoleError.add(" ● Hypo perc = " + hypo_corr_percentage + "%")
        consoleError.add(" ● Resistentie correctie: → perc = " + ResistentieCfEff_info + "%")
        consoleError.add(" ● Stappen correctie: → perc = " + display_Stap_perc + "%")
        consoleError.add("    ~~~~~~~~~~~~~~~~~~~~~~")
        consoleError.add(" → Eff. perc = " + Display_overall_perc + "%  (" + profile.AutominBoostPerc + "% - " +profile.AutomaxBoostPerc + "%)" )
        consoleError.add(" ● Profiel ISF: " + round(profile.sens/18, 1) + " Aangepast naar: " + round(sens/18, 1))
        consoleError.add(" ")
        consoleError.add("֎===========֎۝֎===========֎")
        consoleError.add(" ")
        consoleError.add("֎======== DETAILS ========֎")
        consoleError.add(" ")
        consoleError.add(log_maxIOB)
        consoleError.add(log_DagNacht)
     //   consoleError.add(log_BasalCorr)
        consoleError.add(log_BgIOB)
        consoleError.add(log_BG_IOB)
        consoleError.add(log_Persistent)
        consoleError.add(log_UAMBoost)
        consoleError.add(log_hypo)
        consoleError.add(log_res)
        consoleError.add(log_Stappen)
        consoleError.add(log_SMB_versterker)
        consoleError.add(log_ExtraIns)
        consoleError.add(" ﴿――――――――――――――――――﴾" + "\n")
        consoleError.add(log_basaal)
        consoleError.add(" ﴿――――――――――――――――――﴾" + "\n")


// log data in csv

        val iob_act = iob_data.iob.toString()
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).toString()
        val isf = round((sens/18),2).toString()
        val delta15txt = delta15.toString()
        val delta15oudtxt = delta15_oud.toString()
        val BgFactortxt = Display_Bg_ISF_perc.toString()
        val bolusBoosttxt = extraIns_Factor.toInt()
        val uamBoosttxt = display_UAM_Perc.toString()
        val BgIOBtxt = BG_IOB_percentage.toString()
        val persBoosttxt = Display_Persistent_perc.toString()

        val smbFactortxt = round(smb_factor,2).toString()
        val stapPerctxt = display_Stap_perc.toString()
        val overalltxt = (overall_cf*100).toInt().toString()
        val resistentietxt = ResistentieCfEff_info.toString()
        val hypoperctxt = hypo_corr_percentage.toString()


        val headerRow = "datum,bg,isf,iob,delta15,delta15oud,Bgperc,maaltijdBoost,uamBoost,Bg_IOB,persistent,hypo,resistentie,stap,overall,smbF\n"
        val valuesToRecord = "$dateStr,$bg_act,$isf,$iob_act,$delta15txt,$delta15oudtxt,$BgFactortxt,$bolusBoosttxt,$uamBoosttxt,$BgIOBtxt,$persBoosttxt,$hypoperctxt,$resistentietxt,$stapPerctxt,$overalltxt,$smbFactortxt"

        if (!csvfile.exists()) {
            csvfile.createNewFile()
            csvfile.appendText(headerRow)
        }
        csvfile.appendText(valuesToRecord + "\n")


// einde log data in csv


        //calculate BG impact: the amount BG "should" be rising or falling based on insulin activity alone
        val bgi = round((-iob_data.activity * sens * 5), 2)
        // project deviations for 30 minutes
        var deviation = round(30 / 5 * (minDelta - bgi))
        // don't overreact to a big negative delta: use minAvgDelta if deviation is negative
        if (deviation < 0) {
            deviation = round((30 / 5) * (minAvgDelta - bgi))
            // and if deviation is still negative, use long_avgdelta
            if (deviation < 0) {
                deviation = round((30 / 5) * (glucose_status.longAvgDelta - bgi))
            }
        }

        // calculate the naive (bolus calculator math) eventual BG based on net IOB and sensitivity
        val naive_eventualBG =
             if (iob_data.iob > 0) round(bg - (iob_data.iob * sens), 0)
             else  // if IOB is negative, be more conservative and use the lower of sens, profile.sens
                 round(bg - (iob_data.iob * min(sens, profile.sens)), 0)

        // and adjust it for the deviation above
        var eventualBG = naive_eventualBG + deviation

        // raise target for noisy / raw CGM data
        if (bg > max_bg && profile.adv_target_adjustments && !profile.temptargetSet) {
            // with target=100, as BG rises from 100 to 160, adjustedTarget drops from 100 to 80
            val adjustedMinBG = round(max(80.0, min_bg - (bg - min_bg) / 3.0), 0)
            val adjustedTargetBG = round(max(80.0, target_bg - (bg - target_bg) / 3.0), 0)
            val adjustedMaxBG = round(max(80.0, max_bg - (bg - max_bg) / 3.0), 0)
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedMinBG, don’t use it

            if (eventualBG > adjustedMinBG && naive_eventualBG > adjustedMinBG && min_bg > adjustedMinBG) {
                consoleLog.add("Adjusting targets for high BG: min_bg from $min_bg to $adjustedMinBG; ")
                min_bg = adjustedMinBG
            } else {
                consoleLog.add("min_bg unchanged: $min_bg; ")
            }
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedTargetBG, don’t use it
            if (eventualBG > adjustedTargetBG && naive_eventualBG > adjustedTargetBG && target_bg > adjustedTargetBG) {
                consoleLog.add("target_bg from $target_bg to $adjustedTargetBG; ")
                target_bg = adjustedTargetBG
            } else {
                consoleLog.add("target_bg unchanged: $target_bg; ")
            }
            // if eventualBG, naive_eventualBG, and max_bg aren't all above adjustedMaxBG, don’t use it
            if (eventualBG > adjustedMaxBG && naive_eventualBG > adjustedMaxBG && max_bg > adjustedMaxBG) {
                consoleError.add("max_bg from $max_bg to $adjustedMaxBG")
                max_bg = adjustedMaxBG
            } else {
                consoleError.add("max_bg unchanged: $max_bg")
            }
        }

        val expectedDelta = calculate_expected_delta(target_bg, eventualBG, bgi)

        // min_bg of 90 -> threshold of 65, 100 -> 70 110 -> 75, and 130 -> 85
        var threshold = min_bg - 0.5 * (min_bg - 40)
        if (profile.lgsThreshold != null) {
            val lgsThreshold = profile.lgsThreshold ?: error("lgsThreshold missing")
            if (lgsThreshold > threshold) {
                consoleError.add("Threshold set from ${convert_bg(threshold)} to ${convert_bg(lgsThreshold.toDouble())}; ")
                threshold = lgsThreshold.toDouble()
            }
        }


        rT = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            bg = bg,
            tick = tick,
            eventualBG = eventualBG,
            targetBG = target_bg,
            insulinReq = 0.0,
            deliverAt = deliverAt, // The time at which the microbolus should be delivered

            consoleLog = consoleLog,
            consoleError = consoleError,
            variable_sens = sens // profile.variable_sens
        )

        // generate predicted future BGs based on IOB, COB, and current absorption rate

        var COBpredBGs = mutableListOf<Double>()
        var aCOBpredBGs = mutableListOf<Double>()
        var IOBpredBGs = mutableListOf<Double>()
        var UAMpredBGs = mutableListOf<Double>()
        var ZTpredBGs = mutableListOf<Double>()
        COBpredBGs.add(bg)
        aCOBpredBGs.add(bg)
        IOBpredBGs.add(bg)
        ZTpredBGs.add(bg)
        UAMpredBGs.add(bg)

        var enableSMB = enable_smb(profile, microBolusAllowed, meal_data, target_bg)

        // enable UAM (if enabled in preferences)
        val enableUAM = true

        // carb impact and duration are 0 unless changed below
        var ci: Double
        val cid: Double
        // calculate current carb absorption rate, and how long to absorb all carbs
        // CI = current carb impact on BG in mg/dL/5m
        ci = round((minDelta - bgi), 1)
        val uci = round((minDelta - bgi), 1)
        // ISF (mg/dL/U) / CR (g/U) = CSF (mg/dL/g)

        // TODO: remove commented-out code for old behavior
        //if (profile.temptargetSet) {
        // if temptargetSet, use unadjusted profile.sens to allow activity mode sensitivityRatio to adjust CR
        //var csf = profile.sens / profile.carb_ratio;
        //} else {
        // otherwise, use autosens-adjusted sens to counteract autosens meal insulin dosing adjustments
        // so that autotuned CR is still in effect even when basals and ISF are being adjusted by autosens
        //var csf = sens / profile.carb_ratio;
        //}
        // use autosens-adjusted sens to counteract autosens meal insulin dosing adjustments so that
        // autotuned CR is still in effect even when basals and ISF are being adjusted by TT or autosens
        // this avoids overdosing insulin for large meals when low temp targets are active
        val csf = round(sens / 7.0,1)

        val maxCarbAbsorptionRate = 30 // g/h; maximum rate to assume carbs will absorb if no CI observed
        // limit Carb Impact to maxCarbAbsorptionRate * csf in mg/dL per 5m
        val maxCI = round(maxCarbAbsorptionRate * csf * 5 / 60, 1)
        if (ci > maxCI) {
            //           consoleError.add("Limiting carb impact from $ci to $maxCI mg/dL/5m ( $maxCarbAbsorptionRate g/h )")
            ci = maxCI
        }
        var remainingCATimeMin = 3.0 // h; duration of expected not-yet-observed carb absorption
        // adjust remainingCATime (instead of CR) for autosens if sensitivityRatio defined
    //    remainingCATimeMin = remainingCATimeMin / sensitivityRatio
        remainingCATimeMin = remainingCATimeMin / 1
        // 20 g/h means that anything <= 60g will get a remainingCATimeMin, 80g will get 4h, and 120g 6h
        // when actual absorption ramps up it will take over from remainingCATime
        val assumedCarbAbsorptionRate = 20 // g/h; maximum rate to assume carbs will absorb if no CI observed
        var remainingCATime = remainingCATimeMin
        if (meal_data.carbs != 0.0) {
            // if carbs * assumedCarbAbsorptionRate > remainingCATimeMin, raise it
            // so <= 90g is assumed to take 3h, and 120g=4h
            remainingCATimeMin = Math.max(remainingCATimeMin, meal_data.mealCOB / assumedCarbAbsorptionRate)
            val lastCarbAge = round((systemTime - meal_data.lastCarbTime) / 60000.0)
            val fractionCOBAbsorbed = (meal_data.carbs - meal_data.mealCOB) / meal_data.carbs
            remainingCATime = remainingCATimeMin + 1.5 * lastCarbAge / 60
            remainingCATime = round(remainingCATime, 1)
            consoleError.add("Last carbs " + lastCarbAge + "minutes ago; remainingCATime:" + remainingCATime + "hours;" + round(fractionCOBAbsorbed * 100) + "% carbs absorbed")
        }

        // calculate the number of carbs absorbed over remainingCATime hours at current CI
        // CI (mg/dL/5m) * (5m)/5 (m) * 60 (min/hr) * 4 (h) / 2 (linear decay factor) = total carb impact (mg/dL)
        val totalCI = Math.max(0.0, ci / 5 * 60 * remainingCATime / 2)
        // totalCI (mg/dL) / CSF (mg/dL/g) = total carbs absorbed (g)
        val totalCA = totalCI / csf
        val remainingCarbsCap: Int // default to 90
        remainingCarbsCap = min(90, profile.remainingCarbsCap)
        var remainingCarbs = max(0.0, meal_data.mealCOB - totalCA)
        remainingCarbs = Math.min(remainingCarbsCap.toDouble(), remainingCarbs)
        // assume remainingCarbs will absorb in a /\ shaped bilinear curve
        // peaking at remainingCATime / 2 and ending at remainingCATime hours
        // area of the /\ triangle is the same as a remainingCIpeak-height rectangle out to remainingCATime/2
        // remainingCIpeak (mg/dL/5m) = remainingCarbs (g) * CSF (mg/dL/g) * 5 (m/5m) * 1h/60m / (remainingCATime/2) (h)
        val remainingCIpeak = remainingCarbs * csf * 5 / 60 / (remainingCATime / 2)


        // calculate peak deviation in last hour, and slope from that to current deviation
        val slopeFromMaxDeviation = round(meal_data.slopeFromMaxDeviation, 2)
        // calculate lowest deviation in last hour, and slope from that to current deviation
        val slopeFromMinDeviation = round(meal_data.slopeFromMinDeviation, 2)
        // assume deviations will drop back down at least at 1/3 the rate they ramped up
        val slopeFromDeviations = Math.min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)


        val aci = 10
        //5m data points = g * (1U/10g) * (40mg/dL/1U) / (mg/dL/5m)
        // duration (in 5m data points) = COB (g) * CSF (mg/dL/g) / ci (mg/dL/5m)
        // limit cid to remainingCATime hours: the reset goes to remainingCI
        if (ci == 0.0) {
            // avoid divide by zero
            cid = 0.0
        } else {
            cid = min(remainingCATime * 60 / 5 / 2, Math.max(0.0, meal_data.mealCOB * csf / ci))
        }
        val acid = max(0.0, meal_data.mealCOB * csf / aci)
        // duration (hours) = duration (5m) * 5 / 60 * 2 (to account for linear decay)

        var minIOBPredBG = 999.0
        var minCOBPredBG = 999.0
        var minUAMPredBG = 999.0
        var minGuardBG: Double
        var minCOBGuardBG = 999.0
        var minUAMGuardBG = 999.0
        var minIOBGuardBG = 999.0
        var minZTGuardBG = 999.0
        var minPredBG: Double
        var avgPredBG: Double
        var IOBpredBG: Double = eventualBG
        var maxIOBPredBG = bg
        var maxCOBPredBG = bg

        val lastIOBpredBG: Double
        var lastCOBpredBG: Double? = null
        var lastUAMpredBG: Double? = null

        var UAMduration = 0.0
        var remainingCItotal = 0.0
        val remainingCIs = mutableListOf<Int>()
        val predCIs = mutableListOf<Int>()
        var UAMpredBG: Double? = null
        var COBpredBG: Double? = null
        var aCOBpredBG: Double?
        iobArray.forEach { iobTick ->

            val predBGI: Double = round((-iobTick.activity * sens * 5), 2)
            val IOBpredBGI: Double = predBGI

            iobTick.iobWithZeroTemp ?: error("iobTick.iobWithZeroTemp missing")
            // try to find where is crashing https://console.firebase.google.com/u/0/project/androidaps-c34f8/crashlytics/app/android:info.nightscout.androidaps/issues/950cdbaf63d545afe6d680281bb141e5?versions=3.3.0-dev-d%20(1500)&time=last-thirty-days&types=crash&sessionEventKey=673BF7DD032300013D4704707A053273_2017608123846397475
            if (iobTick.iobWithZeroTemp!!.activity.isNaN() || sens.isNaN())
                fabricPrivacy.logCustom("iobTick.iobWithZeroTemp!!.activity=${iobTick.iobWithZeroTemp!!.activity} sens=$sens")
            val predZTBGI =  round((-iobTick.iobWithZeroTemp!!.activity * sens * 5), 2)
            val predUAMBGI = predBGI

            // for IOBpredBGs, predicted deviation impact drops linearly from current deviation down to zero
            // over 60 minutes (data points every 5m)
            val predDev: Double = ci * (1 - min(1.0, IOBpredBGs.size / (60.0 / 5.0)))
            IOBpredBG = IOBpredBGs[IOBpredBGs.size - 1] + IOBpredBGI + predDev
            // calculate predBGs with long zero temp without deviations
            val ZTpredBG = ZTpredBGs[ZTpredBGs.size - 1] + predZTBGI
            // for COBpredBGs, predicted carb impact drops linearly from current carb impact down to zero
            // eventually accounting for all carbs (if they can be absorbed over DIA)
            val predCI: Double = max(0.0, max(0.0, ci) * (1 - COBpredBGs.size / max(cid * 2, 1.0)))
            val predACI = max(0.0, max(0, aci) * (1 - COBpredBGs.size / max(acid * 2, 1.0)))
            // if any carbs aren't absorbed after remainingCATime hours, assume they'll absorb in a /\ shaped
            // bilinear curve peaking at remainingCIpeak at remainingCATime/2 hours (remainingCATime/2*12 * 5m)
            // and ending at remainingCATime h (remainingCATime*12 * 5m intervals)
            val intervals = Math.min(COBpredBGs.size.toDouble(), ((remainingCATime * 12) - COBpredBGs.size))
            val remainingCI = Math.max(0.0, intervals / (remainingCATime / 2 * 12) * remainingCIpeak)
            remainingCItotal += predCI + remainingCI
            remainingCIs.add(round(remainingCI))
            predCIs.add(round(predCI))

            COBpredBG = COBpredBGs[COBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predCI + remainingCI
            aCOBpredBG = aCOBpredBGs[aCOBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predACI
            // for UAMpredBGs, predicted carb impact drops at slopeFromDeviations
            // calculate predicted CI from UAM based on slopeFromDeviations
            val predUCIslope = max(0.0, uci + (UAMpredBGs.size * slopeFromDeviations))
            // if slopeFromDeviations is too flat, predicted deviation impact drops linearly from
            // current deviation down to zero over 3h (data points every 5m)
            val predUCImax = max(0.0, uci * (1 - UAMpredBGs.size / max(3.0 * 60 / 5, 1.0)))
            // predicted CI from UAM is the lesser of CI based on deviationSlope or DIA
            val predUCI = min(predUCIslope, predUCImax)
            if (predUCI > 0) {
                UAMduration = round((UAMpredBGs.size + 1) * 5 / 60.0, 1)
            }
            UAMpredBG = UAMpredBGs[UAMpredBGs.size - 1] + predUAMBGI + min(0.0, predDev) + predUCI

            // truncate all BG predictions at 4 hours
            if (IOBpredBGs.size < 48) IOBpredBGs.add(IOBpredBG)
            if (COBpredBGs.size < 48) COBpredBGs.add(COBpredBG!!)
            if (aCOBpredBGs.size < 48) aCOBpredBGs.add(aCOBpredBG!!)
            if (UAMpredBGs.size < 48) UAMpredBGs.add(UAMpredBG!!)
            if (ZTpredBGs.size < 48) ZTpredBGs.add(ZTpredBG)
            // calculate minGuardBGs without a wait from COB, UAM, IOB predBGs
            if (COBpredBG!! < minCOBGuardBG) minCOBGuardBG = round(COBpredBG!!).toDouble()
            if (UAMpredBG!! < minUAMGuardBG) minUAMGuardBG = round(UAMpredBG!!).toDouble()
            if (IOBpredBG < minIOBGuardBG) minIOBGuardBG = IOBpredBG
            if (ZTpredBG < minZTGuardBG) minZTGuardBG = round(ZTpredBG, 0)

            // set minPredBGs starting when currently-dosed insulin activity will peak
            // look ahead 60m (regardless of insulin type) so as to be less aggressive on slower insulins
            // add 30m to allow for insulin delivery (SMBs or temps)
            val insulinPeakTime = 90
            val insulinPeak5m = (insulinPeakTime / 60.0) * 12.0


            // wait 90m before setting minIOBPredBG
            if (IOBpredBGs.size > insulinPeak5m && (IOBpredBG < minIOBPredBG)) minIOBPredBG = round(IOBpredBG, 0)
            if (IOBpredBG > maxIOBPredBG) maxIOBPredBG = IOBpredBG
            // wait 85-105m before setting COB and 60m for UAM minPredBGs
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBGs.size > insulinPeak5m && (COBpredBG!! < minCOBPredBG)) minCOBPredBG = round(COBpredBG!!, 0)
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBG!! > maxIOBPredBG) maxCOBPredBG = COBpredBG as Double
            if (enableUAM && UAMpredBGs.size > 12 && (UAMpredBG!! < minUAMPredBG)) minUAMPredBG = round(UAMpredBG!!, 0)
            //if (enableUAM && UAMpredBG!! > maxIOBPredBG) maxUAMPredBG = UAMpredBG!!
        }
        // set eventualBG to include effect of carbs

        if (meal_data.mealCOB > 0) {
            consoleError.add("predCIs (mg/dL/5m):" + predCIs.joinToString(separator = " "))
            consoleError.add("remainingCIs:      " + remainingCIs.joinToString(separator = " "))
        }
        rT.predBGs = Predictions()
        IOBpredBGs = IOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in IOBpredBGs.size - 1 downTo 13) {
            if (IOBpredBGs[i - 1] != IOBpredBGs[i]) break
            else IOBpredBGs.removeAt(IOBpredBGs.lastIndex)
        }
        rT.predBGs?.IOB = IOBpredBGs.map { it.toInt() }
        lastIOBpredBG = round(IOBpredBGs[IOBpredBGs.size - 1]).toDouble()
        ZTpredBGs = ZTpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in ZTpredBGs.size - 1 downTo 7) {
            // stop displaying ZTpredBGs once they're rising and above target
            if (ZTpredBGs[i - 1] >= ZTpredBGs[i] || ZTpredBGs[i] <= target_bg) break
            else ZTpredBGs.removeAt(ZTpredBGs.lastIndex)
        }
        rT.predBGs?.ZT = ZTpredBGs.map { it.toInt() }
        if (meal_data.mealCOB > 0) {
            aCOBpredBGs = aCOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
            for (i in aCOBpredBGs.size - 1 downTo 13) {
                if (aCOBpredBGs[i - 1] != aCOBpredBGs[i]) break
                else aCOBpredBGs.removeAt(aCOBpredBGs.lastIndex)
            }
        }
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            COBpredBGs = COBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
            for (i in COBpredBGs.size - 1 downTo 13) {
                if (COBpredBGs[i - 1] != COBpredBGs[i]) break
                else COBpredBGs.removeAt(COBpredBGs.lastIndex)
            }
            rT.predBGs?.COB = COBpredBGs.map { it.toInt() }
            lastCOBpredBG = COBpredBGs[COBpredBGs.size - 1]
            eventualBG = max(eventualBG, round(COBpredBGs[COBpredBGs.size - 1], 0))
        }
        if (ci > 0 || remainingCIpeak > 0) {
            if (enableUAM) {
                UAMpredBGs = UAMpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
                for (i in UAMpredBGs.size - 1 downTo 13) {
                    if (UAMpredBGs[i - 1] != UAMpredBGs[i]) break
                    else UAMpredBGs.removeAt(UAMpredBGs.lastIndex)
                }
                rT.predBGs?.UAM = UAMpredBGs.map { it.toInt() }
                lastUAMpredBG = UAMpredBGs[UAMpredBGs.size - 1]
                eventualBG = max(eventualBG, round(UAMpredBGs[UAMpredBGs.size - 1], 0))
            }

            // set eventualBG based on COB or UAM predBGs
            rT.eventualBG = eventualBG
        }

        consoleError.add("UAM Impact: ${convert_bg(uci)} mmol/l per 5m" + "\n" + "UAM Duur: $UAMduration uur")
        consoleLog.add("EventualBG is $eventualBG ;")

        minIOBPredBG = max(39.0, minIOBPredBG)
        minCOBPredBG = max(39.0, minCOBPredBG)
        minUAMPredBG = max(39.0, minUAMPredBG)
        minPredBG = round(minIOBPredBG, 0)

        val fSensBG = min(minPredBG, bg)

        var future_sens = 0.0

        val fractionCarbsLeft = meal_data.mealCOB / meal_data.carbs
        // if we have COB and UAM is enabled, average both
        if (minUAMPredBG < 999 && minCOBPredBG < 999) {
            // weight COBpredBG vs. UAMpredBG based on how many carbs remain as COB
            avgPredBG = round((1 - fractionCarbsLeft) * UAMpredBG!! + fractionCarbsLeft * COBpredBG!!, 0)
            // if UAM is disabled, average IOB and COB
        } else if (minCOBPredBG < 999) {
            avgPredBG = round((IOBpredBG + COBpredBG!!) / 2.0, 0)
            // if we have UAM but no COB, average IOB and UAM
        } else if (minUAMPredBG < 999) {
            avgPredBG = round((IOBpredBG + UAMpredBG!!) / 2.0, 0)
        } else {
            avgPredBG = round(IOBpredBG, 0)
        }
        // if avgPredBG is below minZTGuardBG, bring it up to that level
        if (minZTGuardBG > avgPredBG) {
            avgPredBG = minZTGuardBG
        }

        // if we have both minCOBGuardBG and minUAMGuardBG, blend according to fractionCarbsLeft
        if ((cid > 0.0 || remainingCIpeak > 0)) {
            if (enableUAM) {
                minGuardBG = fractionCarbsLeft * minCOBGuardBG + (1 - fractionCarbsLeft) * minUAMGuardBG
            } else {
                minGuardBG = minCOBGuardBG
            }
        } else if (enableUAM) {
            minGuardBG = minUAMGuardBG
        } else {
            minGuardBG = minIOBGuardBG
        }
        minGuardBG = round(minGuardBG, 0)

        var minZTUAMPredBG = minUAMPredBG
        // if minZTGuardBG is below threshold, bring down any super-high minUAMPredBG by averaging
        // this helps prevent UAM from giving too much insulin in case absorption falls off suddenly
        if (minZTGuardBG < threshold) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
            // if minZTGuardBG is between threshold and target, blend in the averaging
        } else if (minZTGuardBG < target_bg) {
            // target 100, threshold 70, minZTGuardBG 85 gives 50%: (85-70) / (100-70)
            val blendPct = (minZTGuardBG - threshold) / (target_bg - threshold)
            val blendedMinZTGuardBG = minUAMPredBG * blendPct + minZTGuardBG * (1 - blendPct)
            minZTUAMPredBG = (minUAMPredBG + blendedMinZTGuardBG) / 2.0
            //minZTUAMPredBG = minUAMPredBG - target_bg + minZTGuardBG;
            // if minUAMPredBG is below minZTGuardBG, bring minUAMPredBG up by averaging
            // this allows more insulin if lastUAMPredBG is below target, but minZTGuardBG is still high
        } else if (minZTGuardBG > minUAMPredBG) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
        }
        minZTUAMPredBG = round(minZTUAMPredBG, 0)
           // if any carbs have been entered recently
        if (meal_data.carbs != 0.0) {

            // if UAM is disabled, use max of minIOBPredBG, minCOBPredBG
            if (!enableUAM && minCOBPredBG < 999) {
                minPredBG = round(max(minIOBPredBG, minCOBPredBG), 0)
                // if we have COB, use minCOBPredBG, or blendedMinPredBG if it's higher
            } else if (minCOBPredBG < 999) {
                // calculate blendedMinPredBG based on how many carbs remain as COB
                val blendedMinPredBG = fractionCarbsLeft * minCOBPredBG + (1 - fractionCarbsLeft) * minZTUAMPredBG
                // if blendedMinPredBG > minCOBPredBG, use that instead
                minPredBG = round(max(minIOBPredBG, max(minCOBPredBG, blendedMinPredBG)), 0)
                // if carbs have been entered, but have expired, use minUAMPredBG
            } else if (enableUAM) {
                minPredBG = minZTUAMPredBG
            } else {
                minPredBG = minGuardBG
            }
            // in pure UAM mode, use the higher of minIOBPredBG,minUAMPredBG
        } else if (enableUAM) {
            minPredBG = round(max(minIOBPredBG, minZTUAMPredBG), 0)
        }
        // make sure minPredBG isn't higher than avgPredBG
        minPredBG = min(minPredBG, avgPredBG)

        consoleLog.add("minPredBG: $minPredBG minIOBPredBG: $minIOBPredBG minZTGuardBG: $minZTGuardBG")
        if (minCOBPredBG < 999) {
            consoleLog.add(" minCOBPredBG: $minCOBPredBG")
        }
        if (minUAMPredBG < 999) {
            consoleLog.add(" minUAMPredBG: $minUAMPredBG")
        }

        consoleError.add("gem. voorspelde BG: ${convert_bg(avgPredBG)} ")  //COB: ${meal_data.mealCOB} / ${meal_data.carbs}")
        // But if the COB line falls off a cliff, don't trust UAM too much:
        // use maxCOBPredBG if it's been set and lower than minPredBG
        if (maxCOBPredBG > bg) {
            minPredBG = min(minPredBG, maxCOBPredBG)
        }

        rT.COB = meal_data.mealCOB
        rT.IOB = iob_data.iob
        rT.reason.append(
            "COB: ${round(meal_data.mealCOB, 1).withoutZeros()}, Dev: ${convert_bg(deviation.toDouble())}, BGI: ${convert_bg(bgi)}, ISF: ${convert_bg(sens)}, CR: ${
                round(7.0, 2)
                    .withoutZeros()
            }, Target: ${convert_bg(target_bg)}, minPredBG ${convert_bg(minPredBG)}, minGuardBG ${convert_bg(minGuardBG)}, IOBpredBG ${convert_bg(lastIOBpredBG)}"
        )
        if (lastCOBpredBG != null) {
            rT.reason.append(", COBpredBG " + convert_bg(lastCOBpredBG.toDouble()))
        }
        if (lastUAMpredBG != null) {
            rT.reason.append(", UAMpredBG " + convert_bg(lastUAMpredBG.toDouble()))
        }
        rT.reason.append("; ")
        // use naive_eventualBG if above 40, but switch to minGuardBG if both eventualBGs hit floor of 39
        var carbsReqBG = naive_eventualBG
        if (carbsReqBG < 40) {
            carbsReqBG = min(minGuardBG, carbsReqBG)
        }
        var bgUndershoot: Double = threshold - carbsReqBG
        // calculate how long until COB (or IOB) predBGs drop below min_bg
        var minutesAboveMinBG = 240
        var minutesAboveThreshold = 240
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            for (i in COBpredBGs.indices) {
               if (COBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in COBpredBGs.indices) {
               if (COBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        } else {
            for (i in IOBpredBGs.indices) {
               if (IOBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in IOBpredBGs.indices) {
                if (IOBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        }

        if (enableSMB && minGuardBG < threshold) {
            consoleError.add("minGuardBG ${convert_bg(minGuardBG)} projected below ${convert_bg(threshold)} - disabling SMB")
            enableSMB = false
        }
        if (maxDelta > 0.20 * bg) {
            consoleError.add("maxDelta ${convert_bg(maxDelta)} > 20% of BG ${convert_bg(bg)} - disabling SMB")
            rT.reason.append("maxDelta " + convert_bg(maxDelta) + " > 20% of BG " + convert_bg(bg) + ": SMB disabled; ")
            enableSMB = false
        }

        if (minutesAboveThreshold < 240 || minutesAboveMinBG < 60) {
            consoleError.add("voorspelde BG boven ${convert_bg(threshold)} gedurende $minutesAboveThreshold minutes")
        }
        // include at least minutesAboveThreshold worth of zero temps in calculating carbsReq
        // always include at least 30m worth of zero temp (carbs to 80, low temp up to target)
        val zeroTempDuration = minutesAboveThreshold
        // BG undershoot, minus effect of zero temps until hitting min_bg, converted to grams, minus COB
        val zeroTempEffectDouble = new_basaal * sens * zeroTempDuration / 60
        // don't count the last 25% of COB against carbsReq
        val COBforCarbsReq = max(0.0, meal_data.mealCOB - 0.25 * meal_data.carbs)
        val carbsReq = round(((bgUndershoot - zeroTempEffectDouble) / csf - COBforCarbsReq))
        val zeroTempEffect = round(zeroTempEffectDouble)
        if (carbsReq >= 50 && minutesAboveThreshold <= 45) {
            rT.carbsReq = carbsReq
            rT.carbsReqWithin = minutesAboveThreshold
            rT.reason.append("$carbsReq add\'l carbs req w/in ${minutesAboveThreshold}m; ")
        }

        // don't low glucose suspend if IOB is already super negative and BG is rising faster than predicted
        if (bg < threshold && iob_data.iob < -new_basaal * 20 / 60 && minDelta > 0 && minDelta > expectedDelta) {
            rT.reason.append("IOB ${iob_data.iob} < ${round(-new_basaal * 20 / 60, 2)}")
            rT.reason.append(" and minDelta ${convert_bg(minDelta)} > expectedDelta ${convert_bg(expectedDelta)}; ")
            // predictive low glucose suspend mode: BG is / is projected to be < threshold
        } else if (bg < threshold || minGuardBG < threshold) {
            rT.reason.append("minGuardBG " + convert_bg(minGuardBG) + "<" + convert_bg(threshold))
            bgUndershoot = target_bg - minGuardBG
            val worstCaseInsulinReq = bgUndershoot / sens
            var durationReq = round(60 * worstCaseInsulinReq / new_basaal)
            durationReq = round(durationReq / 30.0) * 30
            // always set a 30-120m zero temp (oref0-pump-loop will let any longer SMB zero temp run)
            durationReq = min(120, max(30, durationReq))
            return setTempBasal(0.0, durationReq, profile, rT, currenttemp)
        }

        // if not in LGS mode, cancel temps before the top of the hour to reduce beeping/vibration

        val minutes = Instant.ofEpochMilli(rT.deliverAt!!).atZone(ZoneId.systemDefault()).toLocalDateTime().minute
        if (profile.skip_neutral_temps && minutes >= 55) {
            rT.reason.append("; Canceling temp at " + minutes + "m past the hour. ")
            return setTempBasal(0.0, 0, profile, rT, currenttemp)
        }

        if (eventualBG < min_bg) { // if eventual BG is below target:
            rT.reason.append("Eventual BG ${convert_bg(eventualBG)} < ${convert_bg(min_bg)}")
            // if 5m or 30m avg BG is rising faster than expected delta
            if (minDelta > expectedDelta && minDelta > 0 && carbsReq == 0) {
                // if naive_eventualBG < 40, set a 30m zero temp (oref0-pump-loop will let any longer SMB zero temp run)
                if (naive_eventualBG < 40) {
                    rT.reason.append(", naive_eventualBG < 40. ")
                    return setTempBasal(0.0, 30, profile, rT, currenttemp)
                }
                if (glucose_status.delta > minDelta) {
                    rT.reason.append(", but Delta ${convert_bg(tick.toDouble())} > expectedDelta ${convert_bg(expectedDelta)}")
                } else {
                    rT.reason.append(", but Min. Delta ${minDelta.toFixed2()} > Exp. Delta ${convert_bg(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(new_basaal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + currenttemp.rate + " ~ req " + round(new_basaal, 2).withoutZeros() + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(new_basaal, 2)} as temp. ")
                    return setTempBasal(new_basaal, 30, profile, rT, currenttemp)
                }
            }

            // calculate 30m low-temp required to get projected BG up to target
            // multiply by 2 to low-temp faster for increased hypo safety
            var insulinReq = 2 * min(0.0, (eventualBG - target_bg) / sens)
            insulinReq = round(insulinReq, 2)
            // calculate naiveInsulinReq based on naive_eventualBG
            var naiveInsulinReq = min(0.0, (naive_eventualBG - target_bg) / sens)
            naiveInsulinReq = round(naiveInsulinReq, 2)
            if (minDelta < 0 && minDelta > expectedDelta) {
                // if we're barely falling, newinsulinReq should be barely negative
                val newinsulinReq = round((insulinReq * (minDelta / expectedDelta)), 2)

                insulinReq = newinsulinReq
            }
            // rate required to deliver insulinReq less insulin over 30m:
            var rate = new_basaal + (2 * insulinReq)
            rate = round_basal(rate)

            // if required temp < existing temp basal
            val insulinScheduled = currenttemp.duration * (currenttemp.rate - new_basaal) / 60
            // if current temp would deliver a lot (30% of basal) less than the required insulin,
            // by both normal and naive calculations, then raise the rate
            val minInsulinReq = Math.min(insulinReq, naiveInsulinReq)
            if (insulinScheduled < minInsulinReq - new_basaal * 0.3) {
                rT.reason.append(", ${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} is a lot less than needed. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }
            if (currenttemp.duration > 5 && rate >= currenttemp.rate * 0.8) {
                rT.reason.append(", temp ${currenttemp.rate} ~< req ${round(rate, 2)}U/hr. ")
                return rT
            } else {
                // calculate a long enough zero temp to eventually correct back up to target
                if (rate <= 0) {
                    bgUndershoot = (target_bg - naive_eventualBG)
                    val worstCaseInsulinReq = bgUndershoot / sens
                    var durationReq = round(60 * worstCaseInsulinReq / new_basaal)
                    if (durationReq < 0) {
                        durationReq = 0
                        // don't set a temp longer than 120 minutes
                    } else {
                        durationReq = round(durationReq / 30.0) * 30
                        durationReq = min(120, max(0, durationReq))
                    }
                    //console.error(durationReq);
                    if (durationReq > 0) {
                        rT.reason.append(", setting ${durationReq}m zero temp. ")
                        return setTempBasal(rate, durationReq, profile, rT, currenttemp)
                    }
                } else {
                    rT.reason.append(", setting ${round(rate, 2)}U/hr. ")
                }
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }
        }

        // if eventual BG is above min but BG is falling faster than expected Delta
        if (minDelta < expectedDelta) {
            // if in SMB mode, don't cancel SMB zero temp
            if (!(microBolusAllowed && enableSMB)) {
                if (glucose_status.delta < minDelta) {
                    rT.reason.append(
                        "Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Delta ${convert_bg(tick.toDouble())} < Exp. Delta ${
                            convert_bg(expectedDelta)
                        }"
                    )
                } else {
                    rT.reason.append("Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Min. Delta ${minDelta.toFixed2()} < Exp. Delta ${convert_bg(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(new_basaal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + currenttemp.rate + " ~ req " + round(new_basaal, 2).withoutZeros() + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(new_basaal, 2)} as temp. ")
                    return setTempBasal(new_basaal, 30, profile, rT, currenttemp)
                }
            }
        }
        // eventualBG or minPredBG is below max_bg
        if (min(eventualBG, minPredBG) < max_bg) {
            // if in SMB mode, don't cancel SMB zero temp
            if (!(microBolusAllowed && enableSMB)) {
                rT.reason.append("${convert_bg(eventualBG)}-${convert_bg(minPredBG)} in range: no temp required")
                if (currenttemp.duration > 15 && (round_basal(new_basaal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(new_basaal, 2).withoutZeros()}U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(new_basaal, 2)} as temp. ")
                    return setTempBasal(new_basaal, 30, profile, rT, currenttemp)
                }
            }
        }

        // eventual BG is at/above target
        // if iob is over max, just cancel any temps
        if (eventualBG >= max_bg) {
            rT.reason.append("Eventual BG " + convert_bg(eventualBG) + " >= " + convert_bg(max_bg) + ", ")
        }
        if (iob_data.iob > max_iob) {
            rT.reason.append("IOB ${round(iob_data.iob, 2)} > max_iob $max_iob")
            if (currenttemp.duration > 15 && (round_basal(new_basaal) == round_basal(currenttemp.rate))) {
                rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(new_basaal, 2).withoutZeros()}U/hr. ")
                return rT
            } else {
                rT.reason.append("; setting current basal of ${round(new_basaal, 2)} as temp. ")
                return setTempBasal(new_basaal, 30, profile, rT, currenttemp)
            }
        } else { // otherwise, calculate 30m high-temp required to get projected BG down to target
            // insulinReq is the additional insulin required to get minPredBG down to target_bg

            var insulinReq = round((min(minPredBG, eventualBG) - target_bg) / sens, 2)
            // if that would put us over max_iob, then reduce accordingly
            if (insulinReq > max_iob - iob_data.iob) {
                rT.reason.append("max_iob $max_iob, ")
                insulinReq = max_iob - iob_data.iob
            }

            // rate required to deliver insulinReq more insulin over 30m:
            var rate = new_basaal + (2 * insulinReq)
            rate = round_basal(rate)
            insulinReq = round(insulinReq, 3)

            rT.insulinReq = insulinReq
            //console.error(iob_data.lastBolusTime);
            //console.error(profile.temptargetSet, target_bg, rT.COB);
            // only allow microboluses with COB or low temp targets, or within DIA hours of a bolus
            val maxBolus: Double
            if (microBolusAllowed && enableSMB && bg > threshold) {
                // never bolus more than maxSMBBasalMinutes worth of basal
                val mealInsulinReq = round(meal_data.mealCOB / 7.0, 3)
                if (iob_data.iob > mealInsulinReq && iob_data.iob > 0) {
                  maxBolus = round(new_basaal * profile.maxUAMSMBBasalMinutes / 60, 1)
                } else {
                  maxBolus = round(new_basaal * profile.maxSMBBasalMinutes / 60, 1)
                }

                // bolus 1/2 the insulinReq, up to maxBolus, rounding down to nearest bolus increment
                val roundSMBTo = 1 / profile.bolus_increment
                var microBolus = Math.floor(Math.min(smb_factor * insulinReq / 2, maxBolus) * roundSMBTo) / roundSMBTo

                // calculate a long enough zero temp to eventually correct back up to target
                val smbTarget = target_bg
                val worstCaseInsulinReq = (smbTarget - (naive_eventualBG + minIOBPredBG) / 2.0) / sens
                var durationReq = round(60 * worstCaseInsulinReq / new_basaal)

                // if insulinReq > 0 but not enough for a microBolus, don't set an SMB zero temp
                if (insulinReq > 0 && microBolus < profile.bolus_increment) {
                    durationReq = 0
                }

                var smbLowTempReq = 0.0
                if (durationReq <= 0) {
                    durationReq = 0
                    // don't set an SMB zero temp longer than 60 minutes
                } else if (durationReq >= 30) {
                    durationReq = round(durationReq / 30.0) * 30
                    durationReq = min(60, max(0, durationReq))
                } else {
                    // if SMB durationReq is less than 30m, set a nonzero low temp
                    smbLowTempReq = round(new_basaal * durationReq / 30.0, 2)
                    durationReq = 30
                }
                rT.reason.append(" insulinReq $insulinReq")
                if (microBolus >= maxBolus) {
                    rT.reason.append("; maxBolus $maxBolus")
                }
                if (durationReq > 0) {
                    rT.reason.append("; setting ${durationReq}m low temp of ${smbLowTempReq}U/h")
                }
                rT.reason.append(". ")

                // seconds since last bolus
                val lastBolusAge = (systemTime - iob_data.lastBolusTime) / 1000.0

                // allow SMBIntervals between 1 and 10 minutes
                val SMBInterval = min(10, max(1, profile.SMBInterval)) * 60.0   // in seconds

                if (lastBolusAge > SMBInterval - 6.0) {   // 6s tolerance
                    if (microBolus > 0) {
                        rT.units = microBolus
                        rT.reason.append("Microbolusing ${microBolus}U. ")
                    }
                } else {
                    val nextBolusMins = (SMBInterval - lastBolusAge) / 60.0
                    val nextBolusSeconds = (SMBInterval - lastBolusAge) % 60
                    val waitingSeconds = round(nextBolusSeconds, 0) % 60
                    val waitingMins = round(nextBolusMins - waitingSeconds / 60.0, 0)
                    rT.reason.append("Waiting ${waitingMins.withoutZeros()}m ${waitingSeconds.withoutZeros()}s to microbolus again.")
                }
                //rT.reason += ". ";

                // if no zero temp is required, don't return yet; allow later code to set a high temp
                if (durationReq > 0) {
                    rT.rate = smbLowTempReq
                    rT.duration = durationReq
                    return rT
                }

            }

            val maxSafeBasal = getMaxSafeBasal(profile)

            if (rate > maxSafeBasal) {
                rT.reason.append("adj. req. rate: ${round(rate, 2)} to maxSafeBasal: ${maxSafeBasal.withoutZeros()}, ")
                rate = round_basal(maxSafeBasal)
            }

            val insulinScheduled = currenttemp.duration * (currenttemp.rate - new_basaal) / 60
            if (insulinScheduled >= insulinReq * 2) { // if current temp would deliver >2x more than the required insulin, lower the rate
                rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} > 2 * insulinReq. Setting temp basal of ${round(rate, 2)}U/hr. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }

            if (currenttemp.duration == 0) { // no temp is set
                rT.reason.append("no temp, setting " + round(rate, 2).withoutZeros() + "U/hr. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }

            if (currenttemp.duration > 5 && (round_basal(rate) <= round_basal(currenttemp.rate))) { // if required temp <~ existing temp basal
                rT.reason.append("temp ${(currenttemp.rate).toFixed2()} >~ req ${round(rate, 2).withoutZeros()}U/hr. ")
                return rT
            }

            // required temp > existing temp basal
            rT.reason.append("temp ${currenttemp.rate.toFixed2()} < ${round(rate, 2).withoutZeros()}U/hr. ")
            return setTempBasal(rate, 30, profile, rT, currenttemp)
        }
    }
}
