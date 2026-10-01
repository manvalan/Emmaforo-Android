package com.emmaforo.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val colorKey = ActionParameters.Key<String>("color")

class EmmaforoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EmmaforoWidget()
}

class EmmaforoWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = LampStore(context)
        val density = context.resources.displayMetrics.density
        provideContent {
            Box(
                modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color.Transparent)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val housing = housingBitmap(density)
                    Box(
                        modifier = GlanceModifier.width(housing.dpWidth.dp).height(housing.dpHeight.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(ImageProvider(housing.bitmap), contentDescription = null)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            semaphoreLights.forEach { light ->
                                val active = store.lampOn && store.lampColor == light.id
                                val bitmap = discBitmap(density, light.color.toArgb(), active, active && store.lampBlink)
                                Image(
                                    provider = ImageProvider(bitmap),
                                    contentDescription = light.name,
                                    modifier = GlanceModifier.size(28.dp).clickable(
                                        actionRunCallback<LampAction>(actionParametersOf(colorKey to light.id))
                                    ),
                                )
                                if (light.id != "verde") Spacer(GlanceModifier.height(4.dp))
                            }
                        }
                    }
                    Spacer(GlanceModifier.height(2.dp))
                    Image(
                        provider = ImageProvider(baseBitmap(density)),
                        contentDescription = "Lampeggio",
                        modifier = GlanceModifier.size(36.dp, 22.dp).clickable(actionRunCallback<LampAction>()),
                    )
                }
            }
        }
    }
}

class LampAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val session = EmmaforoApp.session(context)
        val previousOn = session.store.lampOn
        val previousColor = session.store.lampColor
        val previousBlink = session.store.lampBlink
        val previousLevel = session.store.lampLevel
        val color = parameters[colorKey]
        val token = if (color != null) session.paintWidgetColor(color) else session.paintWidgetBlink()
        val send = session.widgetSend
        EmmaforoWidget().updateAll(context)
        if (send) {
            session.deliverPainted(token, previousOn, previousColor, previousBlink, previousLevel)
        }
    }
}

private class SizedBitmap(val bitmap: Bitmap, val dpWidth: Float, val dpHeight: Float)

private fun discBitmap(density: Float, color: Int, filled: Boolean, blinking: Boolean): Bitmap {
    val size = (28f * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val cx = size / 2f
    if (filled && blinking) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.11f
        canvas.drawCircle(cx, cx, size / 2f - paint.strokeWidth / 2f, paint)
        paint.strokeWidth = size * 0.07f
        canvas.drawCircle(cx, cx, size / 2f - size * 0.24f - paint.strokeWidth / 2f, paint)
    } else if (filled) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cx, size / 2f, paint)
    } else {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        canvas.drawCircle(cx, cx, size / 2f - paint.strokeWidth / 2f, paint)
    }
    return bitmap
}

private fun housingBitmap(density: Float): SizedBitmap {
    val disc = 28f
    val gap = 4f
    val rim = 4f
    val width = rim * 2 + disc
    val height = rim * 2 + disc * 3 + gap * 2
    val bitmap = Bitmap.createBitmap((width * density).toInt(), (height * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    val inset = paint.strokeWidth / 2f
    canvas.drawRoundRect(
        RectF(inset, inset, bitmap.width - inset, bitmap.height - inset),
        12f * density,
        12f * density,
        paint,
    )
    return SizedBitmap(bitmap, width, height)
}

private fun baseBitmap(density: Float): Bitmap {
    val width = (36f * density).toInt()
    val height = (22f * density).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
    val side = 15f * density
    val left = (width - side) / 2f
    canvas.drawRoundRect(RectF(left, 0f, left + side, side), 1.5f * density, 1.5f * density, paint)
    return bitmap
}
