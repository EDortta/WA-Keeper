package br.com.wanotifkeeper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detecta "em movimento" sem Google Play Services e sem localização.
 *
 * Preferimos o sensor de hardware [Sensor.TYPE_SIGNIFICANT_MOTION]: ele é disparado
 * pelo próprio chip quando o aparelho se desloca de forma relevante (andar, veículo),
 * com custo de bateria praticamente nulo porque não fica amostrando nada. É um
 * sensor "one-shot": cada disparo consome o registro, então re-registramos a cada
 * vez e mantemos uma janela de [WINDOW_MS] em que consideramos que há movimento.
 *
 * Quando o aparelho não tem esse sensor (raro em celulares modernos), caímos para o
 * acelerômetro amostrado em taxa baixa, comparando a magnitude com a gravidade.
 *
 * Não é classificação "em veículo" (isso só existe via Play Services); é um proxy de
 * deslocamento físico, que é o que a leitura em voz alta no carro precisa.
 */
class MotionDetector(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val significantMotion: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private val accelerometer: Sensor? =
        if (significantMotion == null)
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        else null

    @Volatile private var inMotionUntil = 0L
    @Volatile private var started = false

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            refreshWindow()
            // Sensor one-shot: re-arma para o próximo deslocamento.
            significantMotion?.let { sensorManager?.requestTriggerSensor(this, it) }
        }
    }

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = event.values
            val magnitude = sqrt(x * x + y * y + z * z)
            if (abs(magnitude - SensorManager.GRAVITY_EARTH) > ACCEL_THRESHOLD) {
                refreshWindow()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (started || sensorManager == null) return
        started = true
        when {
            significantMotion != null ->
                sensorManager.requestTriggerSensor(triggerListener, significantMotion)
            accelerometer != null ->
                sensorManager.registerListener(
                    accelListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL
                )
        }
    }

    fun stop() {
        if (!started || sensorManager == null) return
        started = false
        significantMotion?.let { sensorManager.cancelTriggerSensor(triggerListener, it) }
        if (accelerometer != null) sensorManager.unregisterListener(accelListener)
    }

    fun isInMotion(): Boolean = System.currentTimeMillis() < inMotionUntil

    private fun refreshWindow() {
        inMotionUntil = System.currentTimeMillis() + WINDOW_MS
    }

    companion object {
        /** Após um disparo de movimento, seguimos "em movimento" por este tempo. */
        private const val WINDOW_MS = 5 * 60 * 1000L

        /** Desvio de gravidade (m/s²) que conta como movimento no fallback. */
        private const val ACCEL_THRESHOLD = 1.8f
    }
}
