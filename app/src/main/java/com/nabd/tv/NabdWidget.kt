package com.nabd.tv

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * Home-screen widget (phones / tablets): shows the title the user is in the middle of and resumes it with one tap.
 * With nothing to resume it simply opens the app. Refreshed whenever watch progress is saved.
 */
class NabdWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(ctx)) }
    }

    companion object {
        private var last = 0L

        /** Re-draws every placed widget; throttled because progress is saved often. */
        fun refresh(ctx: Context, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - last < 30_000L) return
            last = now
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, NabdWidget::class.java))
                if (ids.isNotEmpty()) ids.forEach { mgr.updateAppWidget(it, build(ctx)) }
            }
        }

        private fun build(ctx: Context): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_continue)
            val item = runCatching {
                Store(ctx).history().firstOrNull { it.kind != Kind.Live && !it.finished && it.progress > 0.02f }
            }.getOrNull()
            val intent: Intent
            if (item != null) {
                views.setTextViewText(R.id.widget_title, item.name)
                views.setProgressBar(R.id.widget_progress, 100, (item.progress * 100).toInt(), false)
                views.setViewVisibility(R.id.widget_progress, View.VISIBLE)
                intent = WatchNext.resumeIntent(ctx, item.url)
            } else {
                views.setTextViewText(R.id.widget_title, "افتح التطبيق")
                views.setViewVisibility(R.id.widget_progress, View.GONE)
                intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
            }
            val pi = PendingIntent.getActivity(ctx, 77, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            return views
        }
    }
}
