package com.cin.bilateralsleep.data

import android.content.Context
import com.cin.bilateralsleep.audio.SynthParams
import org.json.JSONObject

/** A named parameter set. */
data class Preset(val name: String, val params: SynthParams)

/** Built-in, plain-language presets. */
object BuiltInPresets {
    val all: List<Preset> = listOf(
        preset("Drift", 0.7, 12.0, 1500.0, 4.0, 0.25),
        preset("Deep", 0.5, 8.0, 900.0, 3.0, 0.50),
        preset("Light", 0.9, 18.0, 2200.0, 6.0, 0.15),
        preset("Ladder", 0.6, 24.0, 1500.0, 8.0, 0.20),
    )

    private fun preset(
        name: String, altRate: Double, density: Double,
        center: Double, q: Double, brown: Double,
    ) = Preset(
        name,
        SynthParams().apply {
            this.altRate = altRate
            this.crackleDensity = density
            this.carrierCenter = center
            this.carrierQ = q
            this.brownLevel = brown
        },
    )
}

/** Persists up to [SLOTS] user-saved presets in SharedPreferences. */
class PresetStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("bilateral_presets", Context.MODE_PRIVATE)

    fun isFilled(slot: Int): Boolean = prefs.contains(key(slot))

    fun save(slot: Int, p: SynthParams) {
        val json = JSONObject().apply {
            put("altRate", p.altRate)
            put("crackleDensity", p.crackleDensity)
            put("carrierCenter", p.carrierCenter)
            put("carrierQ", p.carrierQ)
            put("brownLevel", p.brownLevel)
            put("panShapeHard", p.panShapeHard)
            put("volume", p.volume)
            put("binauralHz", p.binauralHz)
        }
        prefs.edit().putString(key(slot), json.toString()).apply()
    }

    fun load(slot: Int): SynthParams? {
        val raw = prefs.getString(key(slot), null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            SynthParams().apply {
                altRate = j.getDouble("altRate")
                crackleDensity = j.getDouble("crackleDensity")
                carrierCenter = j.getDouble("carrierCenter")
                carrierQ = j.getDouble("carrierQ")
                brownLevel = j.getDouble("brownLevel")
                panShapeHard = j.getBoolean("panShapeHard")
                volume = j.getDouble("volume")
                binauralHz = j.getDouble("binauralHz")
            }
        }.getOrNull()
    }

    private fun key(slot: Int) = "slot_$slot"

    companion object {
        const val SLOTS = 3
    }
}
