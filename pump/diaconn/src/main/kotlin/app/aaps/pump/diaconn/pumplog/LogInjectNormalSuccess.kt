package app.aaps.pump.diaconn.pumplog

import okhttp3.internal.and
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
* 일반주입 성공
*/
@Suppress("SpellCheckingInspection")
class LogInjectNormalSuccess private constructor(
    val data: String,
    val dttm: String,
    typeAndKind: Byte,    // 47.5=4750
    private val setAmount: Short,    // 47.5=4750
    val injectAmount: Short,    // 1분단위 주입시간(124=124분=2시간4분)
    val injectTime: Byte,
    val batteryRemain: Byte
) {

    val type: Byte = PumpLogUtil.getType(typeAndKind)
    val kind: Byte = PumpLogUtil.getKind(typeAndKind)
    fun getInjectTime(): Int {
        return injectTime and 0xff
    }

    override fun toString(): String {
        val sb = StringBuilder("LOG_INJECT_NORMAL_SUCCESS{")
        sb.append("LOG_KIND=").append(LOG_KIND.toInt())
        sb.append(", data='").append(data).append('\'')
        sb.append(", dttm='").append(dttm).append('\'')
        sb.append(", type=").append(type.toInt())
        sb.append(", kind=").append(kind.toInt())
        sb.append(", setAmount=").append(setAmount.toInt())
        sb.append(", injectAmount=").append(injectAmount.toInt())
        sb.append(", injectTime=").append(injectTime and 0xff)
        sb.append(", batteryRemain=").append(batteryRemain.toInt())
        sb.append('}')
        return sb.toString()
    }

    companion object {

        const val LOG_KIND: Byte = 0x0A
        fun parse(data: String): LogInjectNormalSuccess {
            val bytes = PumpLogUtil.hexStringToByteArray(data)
            val buffer = ByteBuffer.wrap(bytes)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            return LogInjectNormalSuccess(
                data,
                PumpLogUtil.getDttm(buffer),
                PumpLogUtil.getByte(buffer),
                PumpLogUtil.getShort(buffer),
                PumpLogUtil.getShort(buffer),
                PumpLogUtil.getByte(buffer),
                PumpLogUtil.getByte(buffer)
            )
        }
    }

}