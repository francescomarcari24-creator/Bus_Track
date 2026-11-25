package com.example.bustrack.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.bustrack.gtfs.GtfsStaticDb
import com.example.bustrack.gtfs.update.GtfsUpdateWorker
import com.example.bustrack.ui.BackLabel
import com.example.bustrack.ui.BackButtonPadding
import com.example.bustrack.ui.theme.BusTrackTheme
import com.example.bustrack.ui.theme.CardCorner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Impostazioni: raggio delle fermate vicine, minuti proposti per l'avviso di arrivo,
 * aggiornamento degli orari (validità, solo Wi-Fi, controllo immediato) e fonti dei dati.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BusTrackTheme {
                Surface(Modifier.fillMaxSize()) { SettingsScreen(onBack = ::finish) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val radius by settings.nearbyRadiusM.collectAsStateWithLifecycle()
    val thresholds by settings.watchThresholds.collectAsStateWithLifecycle()
    val wifiOnly by settings.gtfsWifiOnly.collectAsStateWithLifecycle()
    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(GtfsUpdateWorker.NOW_NAME)
        .collectAsStateWithLifecycle(emptyList())
    val lastCheck = workInfos.lastOrNull()
    // Info sui dati in uso: rilette dopo ogni controllo concluso (potrebbero essere cambiati).
    var dbInfo by remember { mutableStateOf<GtfsStaticDb.Companion.DbInfo?>(null) }
    LaunchedEffect(lastCheck?.state?.isFinished) {
        dbInfo = withContext(Dispatchers.IO) { runCatching { GtfsStaticDb.currentInfo(context) }.getOrNull() }
    }

    Column(
        Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        TextButton(onClick = onBack, contentPadding = BackButtonPadding, modifier = Modifier.padding(top = 8.dp)) {
            BackLabel("Indietro")
        }
        Text("Impostazioni", style = MaterialTheme.typography.headlineSmall)

        Section("Fermate vicine")
        Text("Raggio di ricerca", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.RADIUS_OPTIONS.forEach { m ->
                FilterChip(
                    selected = radius == m,
                    onClick = { settings.setNearbyRadius(m) },
                    label = { Text(AppSettings.formatDistance(m)) },
                )
            }
        }

        Section("Avviso di arrivo")
        Text(
            "Minuti di preavviso proposti toccando la campanella di un passaggio",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.THRESHOLD_OPTIONS.forEach { m ->
                FilterChip(
                    selected = m in thresholds,
                    onClick = { settings.toggleThreshold(m) },
                    label = { Text("$m min") },
                )
            }
        }

        Section("Avvisi sulle tue linee")
        val favoriteAlerts by settings.favoriteAlerts.collectAsStateWithLifecycle()
        var hasPermission by remember {
            mutableStateOf(
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            )
        }
        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { hasPermission = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Notifica i nuovi avvisi", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Deviazioni, scioperi o fermate soppresse sulle linee e fermate preferite. " +
                        "Controllo ogni 15 minuti.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = favoriteAlerts,
                onCheckedChange = { on ->
                    settings.setFavoriteAlerts(on)
                    if (on) {
                        com.example.bustrack.watch.FavoriteAlertsWorker.schedule(context)
                        if (!hasPermission) permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        com.example.bustrack.watch.FavoriteAlertsWorker.cancel(context)
                    }
                },
            )
        }
        if (favoriteAlerts && !hasPermission) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Le notifiche dell'app non sono consentite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Consenti")
                }
            }
        }

        Section("Orari e fermate")
        Card(Modifier.fillMaxWidth(), shape = CardCorner) {
            Column(Modifier.padding(16.dp)) {
                val info = dbInfo
                Text(
                    if (info == null) "Leggo i dati in uso…"
                    else "Dati validi fino al ${formatYmd(info.feedEndDate)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                info?.builtAt?.takeIf { it.length >= 10 }?.let {
                    Text(
                        "Preparati il ${it.substring(8, 10)}/${it.substring(5, 7)}/${it.substring(0, 4)} · " +
                            "l'app controlla ogni giorno se Roma Mobilità ne ha pubblicati di nuovi",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Scarica i nuovi orari solo in Wi‑Fi", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Sono circa 48 MB. Se i dati stanno per scadere si scaricano comunque.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = wifiOnly, onCheckedChange = settings::setGtfsWifiOnly)
        }
        Spacer(Modifier.height(8.dp))
        val running = lastCheck?.state == WorkInfo.State.RUNNING || lastCheck?.state == WorkInfo.State.ENQUEUED
        FilledTonalButton(onClick = { GtfsUpdateWorker.runNow(context, force = false) }, enabled = !running) {
            Text("Controlla aggiornamenti ora")
        }
        checkMessage(lastCheck)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        Section("Fonti dei dati")
        Text(
            "Orari, fermate, posizioni dei mezzi e avvisi: Roma Mobilità (dati aperti GTFS e GTFS-Realtime).\n" +
                "Mappa: © OpenStreetMap contributors, stile e servizio OpenFreeMap, visualizzazione MapLibre.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 12.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** Esito dell'ultimo controllo chiesto a mano. */
private fun checkMessage(info: WorkInfo?): String? = when (info?.state) {
    null, WorkInfo.State.CANCELLED -> null
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Controllo in attesa della rete…"
    WorkInfo.State.RUNNING -> "Controllo in corso… se ci sono orari nuovi li scarico e li preparo (circa un minuto)."
    WorkInfo.State.SUCCEEDED -> when (info.outputData.getString(GtfsUpdateWorker.KEY_OUTCOME)) {
        GtfsUpdateWorker.OUTCOME_UPDATED -> "Orari aggiornati."
        GtfsUpdateWorker.OUTCOME_WAITING_WIFI -> "Ci sono orari nuovi: li scarico appena sei in Wi‑Fi."
        else -> "Gli orari sono già aggiornati."
    }
    WorkInfo.State.FAILED -> "Controllo non riuscito: riprova più tardi."
}

/** "20261231" → "31/12/2026". */
private fun formatYmd(yyyymmdd: String): String =
    if (yyyymmdd.length == 8) "${yyyymmdd.substring(6, 8)}/${yyyymmdd.substring(4, 6)}/${yyyymmdd.substring(0, 4)}"
    else "data sconosciuta"
