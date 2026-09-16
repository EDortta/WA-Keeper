package br.com.wanotifkeeper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detecta "em movimento" sem Google Play Services e sem localização.
 *
 * Entrada: quando disponível, TYPE_SIGNIFICANT_MOTION continua sendo o gatilho barato e rápido.
 * Saída: depois da entrada, amostramos LINEAR_ACCELERATION (ou acelerômetro como fallback) para
 * renovar o estado somente enquanto existe atividade física real. Sem atividade por 45 s, o
 * estado encerra. Isso substitui a janela cega de 5 minutos que sabia começar, mas não sabia
 * reconhecer adequadamente que o deslocamento terminou.
 *
 * Em aparelhos sem TYPE_SIGNIFICANT_MOTION, o sensor de atividade fica registrado continuamente
 * em taxa normal e cumpre também o papel de detectar a entrada.
 */
class MotionDetector(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val significantMotion: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private val linearAcceleration: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val activitySensor: Sensor? = linearAcceleration ?: accelerometer
    private val usesLinearAcceleration = linearAcceleration != null

    private val tracker = MotionStateTracker(STILLNESS_MS)

    @Volatile private var started = false
    @Volatile private var activitySampling = false

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            tracker.markActivity(SystemClock.elapsedRealtime())
            ensureActivitySampling()
            // TYPE_SIGNIFICANT_MOTION é one-shot: rearma para detectar um novo episódio.
            significantMotion?.let { sensorManager?.requestTriggerSensor(this, it) }
        }
    }

    private val activityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val activity = activityMagnitude(event)
            val threshold = if (usesLinearAcceleration) LINEAR_ACTIVITY_THRESHOLD else ACCEL_ACTIVITY_THRESHOLD
            val now = SystemClock.elapsedRealtime()

            if (activity >= threshold) tracker.markActivity(now)

            // Com o sensor significativo presente, a amostragem contínua só é necessária
            // enquanto estamos validando que o movimento continua. Ao confirmar a parada,
            // desliga novamente o sensor de maior consumo e volta ao one-shot barato.
            if (significantMotion != null && !tracker.isInMotion(now)) {
                stopActivitySampling()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (started || sensorManager == null) return
        started = true
        tracker.reset()

        if (significantMotion != null) {
            sensorManager.requestTriggerSensor(triggerListener, significantMotion)
        } else {
            // Sem one-shot, este sensor precisa observar tanto entrada quanto saída.
            ensureActivitySampling()
        }
    }

    fun stop() {
        if (!started || sensorManager == null) return
        started = false
        significantMotion?.let { sensorManager.cancelTriggerSensor(triggerListener, it) }
        stopActivitySampling()
        tracker.reset()
    }

    fun isInMotion(): Boolean = tracker.isInMotion(SystemClock.elapsedRealtime())

    @Synchronized
    private fun ensureActivitySampling() {
        if (!started || activitySampling || sensorManager == null || activitySensor == null) return
        activitySampling = sensorManager.registerListener(
            activityListener,
            activitySensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    @Synchronized
    private fun stopActivitySampling() {
        if (!activitySampling || sensorManager == null) return
        sensorManager.unregisterListener(activityListener)
        activitySampling = false
    }

    private fun activityMagnitude(event: SensorEvent): Float {
        val x = event.values.getOrElse(0) { 0f }
        val y = event.values.getOrElse(1) { 0f }
        val z = event.values.getOrElse(2) { 0f }
        val magnitude = sqrt(x * x + y * y + z * z)
        return if (usesLinearAcceleration) {
            magnitude
        } else {
            abs(magnitude - SensorManager.GRAVITY_EARTH)
        }
    }

    companion object {
        /** Tempo sem atividade suficiente para confirmar que o deslocamento terminou. */
        private const val STILLNESS_MS = 45_000L

        /** Movimento mínimo no sensor já descontado da gravidade. */
        private const val LINEAR_ACTIVITY_THRESHOLD = 0.25f

        /** Desvio mínimo da gravidade quando só existe acelerômetro bruto. */
        private const val ACCEL_ACTIVITY_THRESHOLD = 0.35f
    }
}
