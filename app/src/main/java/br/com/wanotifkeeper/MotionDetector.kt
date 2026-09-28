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
 * renovar o estado somente com atividade consistente. A saída usa histerese: cerca de 12 s de
 * repouso claro encerram o estado, e existe ainda um timeout duro de 35 s sem movimento
 * confirmado. Picos isolados de vibração não renovam mais a sessão.
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

    private val tracker = MotionStateTracker()

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
            val activeThreshold =
                if (usesLinearAcceleration) LINEAR_ACTIVE_THRESHOLD else ACCEL_ACTIVE_THRESHOLD
            val quietThreshold =
                if (usesLinearAcceleration) LINEAR_QUIET_THRESHOLD else ACCEL_QUIET_THRESHOLD
            val now = SystemClock.elapsedRealtime()

            tracker.observeSample(
                active = activity >= activeThreshold,
                quiet = activity <= quietThreshold,
                now = now
            )

            // Com sensor significativo, o contínuo serve apenas para acompanhar o episódio.
            // Quando o repouso foi confirmado, desliga e volta ao one-shot barato.
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
        /** Histerese: acima do limiar ativo conta para um burst; abaixo do quieto confirma repouso. */
        private const val LINEAR_ACTIVE_THRESHOLD = 0.55f
        private const val LINEAR_QUIET_THRESHOLD = 0.12f

        /** Fallback com acelerômetro bruto, já descontando a gravidade no cálculo da magnitude. */
        private const val ACCEL_ACTIVE_THRESHOLD = 0.70f
        private const val ACCEL_QUIET_THRESHOLD = 0.20f
    }
}
