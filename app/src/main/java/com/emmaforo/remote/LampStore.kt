package com.emmaforo.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LampStore(context: Context) {
    private val prefs = context.getSharedPreferences("emmaforo", Context.MODE_PRIVATE)

    var lightLevel: Int
        get() = prefs.getInt("lightLevel", 100)
        set(value) = prefs.edit().putInt("lightLevel", value.coerceIn(0, 100)).apply()

    val lampOn: Boolean get() = prefs.getBoolean("lampOn", false)
    val lampColor: String get() = prefs.getString("lampColor", "") ?: ""
    val lampBlink: Boolean get() = prefs.getBoolean("lampBlink", false)
    val lampLevel: Int get() = prefs.getInt("lampLevel", 255).coerceIn(0, 255)
    val lampToken: Int get() = prefs.getInt("lampToken", 0)

    var peripheralAddress: String
        get() = prefs.getString("peripheral", "") ?: ""
        set(value) = prefs.edit().putString("peripheral", value).apply()

    var colorsDirty: Boolean
        get() = prefs.getBoolean("colorsDirty", false)
        set(value) = prefs.edit().putBoolean("colorsDirty", value).apply()

    fun saveLamp(on: Boolean, color: String, blink: Boolean, level: Int) {
        prefs.edit()
            .putBoolean("lampOn", on)
            .putString("lampColor", color)
            .putBoolean("lampBlink", blink)
            .putInt("lampLevel", level.coerceIn(0, 255))
            .putInt("lampToken", lampToken + 1)
            .apply()
    }

    fun loadSlots(): List<SlotColor?> {
        val raw = prefs.getString("slots", null) ?: return List(colorSlotCount) { null }
        return decodeSlots(raw)
    }

    fun saveSlots(slots: List<SlotColor?>) {
        prefs.edit().putString("slots", encodeSlots(slots)).apply()
    }

    fun loadChains(): List<ColorChain> {
        val raw = prefs.getString("chains", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val steps = item.optJSONArray("steps") ?: JSONArray()
                ColorChain(
                    id = item.optString("id", java.util.UUID.randomUUID().toString()),
                    name = item.optString("name", ""),
                    loop = item.optBoolean("loop", true),
                    steps = (0 until steps.length()).map { at ->
                        val step = steps.getJSONObject(at)
                        ChainStep(
                            id = step.optString("id", java.util.UUID.randomUUID().toString()),
                            red = step.optDouble("r", 0.0).toFloat(),
                            green = step.optDouble("g", 0.0).toFloat(),
                            blue = step.optDouble("b", 0.0).toFloat(),
                            fadeTenths = step.optInt("fade", 10),
                            holdTenths = step.optInt("hold", 30),
                            sound = step.optInt("sound", 0),
                        )
                    },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveChains(chains: List<ColorChain>) {
        val array = JSONArray()
        chains.forEach { chain ->
            val steps = JSONArray()
            chain.steps.forEach { step ->
                steps.put(
                    JSONObject()
                        .put("id", step.id)
                        .put("r", step.red.toDouble())
                        .put("g", step.green.toDouble())
                        .put("b", step.blue.toDouble())
                        .put("fade", step.fadeTenths)
                        .put("hold", step.holdTenths)
                        .put("sound", step.sound)
                )
            }
            array.put(JSONObject().put("id", chain.id).put("name", chain.name).put("loop", chain.loop).put("steps", steps))
        }
        prefs.edit().putString("chains", array.toString()).apply()
    }

    /** The sentence written in each voice slot; the firmware keeps only the samples. */
    fun loadPhrases(): List<String> {
        val raw = prefs.getString("phrases", null) ?: return defaultPhrases
        return try {
            val array = JSONArray(raw)
            List(voiceSlotCount) { index -> if (index < array.length()) array.optString(index, "") else "" }
        } catch (_: Exception) {
            defaultPhrases
        }
    }

    fun savePhrases(phrases: List<String>) {
        val array = JSONArray()
        phrases.take(voiceSlotCount).forEach { array.put(it) }
        prefs.edit().putString("phrases", array.toString()).apply()
    }

    private fun encodeSlots(slots: List<SlotColor?>): String {
        val array = JSONArray()
        padded(slots).forEach { slot ->
            if (slot == null) {
                array.put(JSONObject.NULL)
            } else {
                array.put(
                    JSONObject()
                        .put("id", slot.id)
                        .put("r", slot.red.toDouble())
                        .put("g", slot.green.toDouble())
                        .put("b", slot.blue.toDouble())
                )
            }
        }
        return array.toString()
    }

    private fun decodeSlots(raw: String): List<SlotColor?> {
        return try {
            val array = JSONArray(raw)
            val slots = MutableList<SlotColor?>(colorSlotCount) { null }
            for (index in 0 until minOf(array.length(), colorSlotCount)) {
                if (array.isNull(index)) continue
                val item = array.getJSONObject(index)
                slots[index] = SlotColor(
                    id = item.optString("id", java.util.UUID.randomUUID().toString()),
                    red = item.optDouble("r", 0.0).toFloat(),
                    green = item.optDouble("g", 0.0).toFloat(),
                    blue = item.optDouble("b", 0.0).toFloat(),
                )
            }
            slots
        } catch (_: Exception) {
            List(colorSlotCount) { null }
        }
    }

    private fun padded(slots: List<SlotColor?>): List<SlotColor?> {
        val copy = slots.take(colorSlotCount).toMutableList()
        while (copy.size < colorSlotCount) copy += null
        return copy
    }
}
