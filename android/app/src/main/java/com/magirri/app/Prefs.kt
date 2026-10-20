package com.magirri.app

import android.content.Context

/** 极简偏好存储：Log 版本、底栏玻璃档位。 */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("magirri", Context.MODE_PRIVATE)

    /** 荣耀还原 LUT 版本：1 或 2（默认 v2，覆盖新机型） */
    var logVersion: Int
        get() = sp.getInt("log_version", 2).coerceIn(1, 2)
        set(v) {
            sp.edit().putInt("log_version", v.coerceIn(1, 2)).apply()
        }

    /** 底栏外观：0=自动，1=毛玻璃，2=简洁（回退） */
    var glassMode: Int
        get() = sp.getInt("glass_mode", 0).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("glass_mode", v.coerceIn(0, 2)).apply()
        }

    /** HDR(HLG) 素材是否先转 SDR 再套 LUT。默认开——否则导出的画面会发灰。 */
    var hdrToSdr: Boolean
        get() = sp.getBoolean("hdr_to_sdr", true)
        set(v) {
            sp.edit().putBoolean("hdr_to_sdr", v).apply()
        }

    fun logVersionLabel(): String = if (logVersion == 2) "荣耀 v2" else "荣耀 v1"
}
