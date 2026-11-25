package com.example.bustrack.navigation

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import com.example.bustrack.MainActivity
import com.example.bustrack.R
import com.example.bustrack.ui.Place

/**
 * Scorciatoie dell'icona dell'app (tenendola premuta): "Vai a casa" e "Vai a lavoro", solo per i
 * luoghi impostati. Aprono la scheda Nav con i percorsi verso quel luogo già calcolati.
 */
object NavShortcuts {
    const val HOME = "home"
    const val WORK = "work"

    /** Allinea le scorciatoie a Casa e Lavoro impostati (chiamata all'avvio e a ogni loro cambio). */
    fun update(context: Context, home: Place?, work: Place?) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val list = listOfNotNull(
            home?.let { build(context, HOME, "Casa", "Vai a casa", R.drawable.ic_shortcut_home) },
            work?.let { build(context, WORK, "Lavoro", "Vai a lavoro", R.drawable.ic_shortcut_work) },
        )
        runCatching { sm.dynamicShortcuts = list }.onFailure { Log.e("BusTrack", "Scorciatoie dell'app", it) }
    }

    /** Scorciatoia usata: il sistema impara quali proporre per prime. */
    fun reportUsed(context: Context, id: String) {
        runCatching { context.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(id) }
    }

    private fun build(context: Context, id: String, short: String, long: String, icon: Int): ShortcutInfo =
        ShortcutInfo.Builder(context, id)
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(Icon.createWithResource(context, icon))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_NAV_TO, id)
            )
            .build()
}
