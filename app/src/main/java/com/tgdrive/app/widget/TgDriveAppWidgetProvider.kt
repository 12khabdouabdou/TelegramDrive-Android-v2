package com.tgdrive.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tgdrive.app.MainActivity
import com.tgdrive.app.R
import com.tgdrive.app.RootViewModel

/**
 * Home screen quick-actions widget providing 1-tap access to Search,
 * Private Vault, Cloud Folders, and AutoBackup.
 */
class TgDriveAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_tgdrive_quick)
            views.setOnClickPendingIntent(
                R.id.widget_btn_search,
                shortcutIntent(context, "search", 1001),
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_vault,
                shortcutIntent(context, "vault", 1002),
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_folders,
                shortcutIntent(context, "folders", 1003),
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_backup,
                shortcutIntent(context, "backup", 1004),
            )
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun shortcutIntent(context: Context, route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(RootViewModel.EXTRA_SHORTCUT_ROUTE, route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
