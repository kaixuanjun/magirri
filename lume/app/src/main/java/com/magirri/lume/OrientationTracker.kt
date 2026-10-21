package com.magirri.lume

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * 用加速度计判断手机是竖着还是横着、朝哪边横。
 *
 * 为什么不用 `Display.getRotation()`：那个值受「自动旋转」开关和界面朝向影响，
 * 关掉自动旋转后横着拍，它仍然报 0，照片就会存成竖的。加速度计量的是重力方向，
 * 和设备怎么转无关，所以照片方向和预览都用它。
 *
 * 返回值和 `Surface.ROTATION_*` 对齐（0 / 90 / 180 / 270，逆时针为正）：
 *  - 竖持：0
 *  - 向左横（手机顶部朝左）：90
 *  - 倒置：180
 *  - 向右横：270
 */
class OrientationTracker(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 最近一次稳定下来的方向 */
    @Volatile
    var degrees: Int = Surface.ROTATION_0

    /** 连续横滚角（-45..45），水平仪用；0 = 手机横放水平 */
    @Volatile var rollDeg: Float = 0f
        private set

    /** 方向变化时回调（可能被传感器线程调用） */
    var onChanged: ((Int) -> Unit)? = null

    private var lastRaw = -1
    private var eventCount = 0
    private var stableCount = 0

    fun start() {
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {

        // 横屏拍摄时手机是立着的：x 轴（长边）水平、y 轴（短边）竖直，
        // 绕着镜头光轴（z）的横滚才是画面歪没歪。
        // 之前用 atan2(z, y) 是"屏幕朝下才平"，那是俯仰，不是横滚。
        var r = Math.toDegrees(
            Math.atan2(event.values[0].toDouble(), -event.values[1].toDouble())
        ).toFloat()
        // 两种横握方向都归一化到 ±90 内
        if (r > 90f) r -= 180f
        if (r < -90f) r += 180f
        rollDeg = r

        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        // 手机接近平放时（z 占主导）方向不可靠，保持上一次
        if (Math.abs(x) < 4f && Math.abs(y) < 4f) return

        val raw = quadrant(x, y)
        if (raw == lastRaw) {
            stableCount++
        } else {
            lastRaw = raw
            stableCount = 0
        }
        // 连续 3 次同象限才采纳，避免手臂抖动时来回跳
        if (stableCount >= 3 && raw != degrees) {
            degrees = raw
            onChanged?.invoke(raw)
        }
    }

    /** 重力分量 → 0/90/180/270 */
    private fun quadrant(x: Float, y: Float): Int {
        val deg = Math.toDegrees(Math.atan2(x.toDouble(), y.toDouble()))
        val q = Math.round(deg / 90.0).toInt() * 90
        return ((q % 360) + 360) % 360
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
