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
