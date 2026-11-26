package com.example.bustrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.example.bustrack.ui.theme.CardCorner
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.example.bustrack.R
import com.example.bustrack.favorites.Favorite
import com.example.bustrack.ui.theme.LiveTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bustrack.gtfs.RoutePattern
import com.example.bustrack.gtfs.Stop
import com.example.bustrack.gtfs.VehiclePosition
import com.example.bustrack.gtfs.VehicleStopStatus
import java.time.Instant

/** Elenco delle fermate della linea nella direzione scelta, con i mezzi live evidenziati. */
@Composable
fun LineScreen(line: LineState, vm: TransitViewModel) {
    val vehicles = (line.vehicles as? Load.Ready)?.value.orEmpty()
    // fermata -> mezzi che sono lì o in arrivo lì
    val vehiclesByStop = vehicles.filter { it.currentStopId != null }.groupBy { it.currentStopId!! }
    val stopIds = line.stops.mapTo(HashSet()) { it.id }
    val offRoute = vehicles.count { it.currentStopId == null || it.currentStopId !in stopIds }

    // Lo scorrimento è legato a questa apertura della linea: tornando indietro da una fermata
    // o dagli orari si ritrova la lista dove la si era lasciata.
    val memory = LocalScrollMemory.current
    val listKey = "line:${line.openId}"
    val listState = memory.list(listKey)
    val inPreview = LocalInBackPreview.current
    // Avvisi che citano questa linea: in cima all'elenco delle fermate.
    LaunchedEffect(line.route.id) { vm.ensureAlerts() }
    val allAlerts = (vm.alerts.collectAsStateWithLifecycle().value as? Load.Ready)?.value.orEmpty()
    val lineAlerts = allAlerts.filter { it.concerns(listOf(line.route.label), emptyList()) }
    var alertsOpen by rememberAlertsOpen(lineAlerts)
    // Voci prima delle fermate nell'elenco (riquadro avvisi ed eventuali avvisi aperti).
    val headerItems = if (lineAlerts.isEmpty()) 0 else 1 + if (alertsOpen) lineAlerts.size else 0
    // Alla prima apertura scorre fino al primo mezzo del percorso (una volta sola).
    LaunchedEffect(listKey, vehiclesByStop.isNotEmpty(), line.stops.size) {
        if (inPreview || line.stops.isEmpty() || vehiclesByStop.isEmpty()) return@LaunchedEffect
        if (!memory.once(listKey)) return@LaunchedEffect
        val first = line.stops.indexOfFirst { it.id in vehiclesByStop }
        if (first >= 0) listState.scrollToItem((headerItems + first - 2).coerceAtLeast(0))
    }
    // Le liste di aperture precedenti della linea non servono più.
    LaunchedEffect(listKey) { if (!inPreview) memory.forgetAll("line:", except = listKey) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val refreshing by vm.refreshing.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            val stopsState = vm.stops.collectAsStateWithLifecycle().value
            val returnStop = stopsState.returnStop
            // Aperta dalla mappa: indietro torna alla scheda Mappa (la linea resta sotto, come una fermata dalla mappa).
            val backToMap = stopsState.lineFromMap && returnStop == null
            val tabBack = LocalTabBack.current
            val animatedBack = LocalAnimatedBack.current
            TextButton(
                onClick = { if (backToMap) tabBack() else animatedBack?.invoke() ?: vm.closeLine() },
                contentPadding = BackButtonPadding,
            ) {
                BackLabel(returnStop?.name ?: if (backToMap) "Mappa" else "Ricerca")
            }
            Spacer(Modifier.weight(1f))
            val showOnMap = LocalShowLineOnMap.current
            // Tasti tondi come quelli delle impostazioni e delle preferenze, con spazi uguali.
            RoundIconButton(R.drawable.ic_map, "Mostra la linea sulla mappa") {
                showOnMap(line.route.label, line.pattern.directionId, line.pattern.headsign)
            }
            Spacer(Modifier.width(RoundButtonGap))
            RoundIconButton(R.drawable.ic_schedule, "Orari di partenza dal capolinea", vm::openTimetable)
            Spacer(Modifier.width(RoundButtonGap))
            val favorites by vm.favoriteItems.collectAsStateWithLifecycle()
            FavoriteButton(Favorite.LineFav(line.route.id, line.pattern.directionId) in favorites) {
                vm.toggleFavoriteLine(line.route, line.pattern)
            }
            Spacer(Modifier.width(RoundButtonGap))
            RefreshButton(TransitViewModel.REFRESH_LINE in refreshing, onClick = vm::refreshLine)
        }
        // Aria tra i tasti tondi e il titolo.
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RouteBadge(line.route.label)
            Text(
                "→ ${line.pattern.headsign ?: "Direzione ${line.pattern.directionId}"}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
        }
        val status = when (val v = line.vehicles) {
            Load.Idle, Load.Loading -> "Cerco i mezzi in servizio…"
            is Load.Failed -> "Posizioni live non disponibili"
            is Load.Ready -> buildString {
                append(
                    when (v.value.size) {
                        0 -> "Nessun mezzo live in questa direzione"
                        1 -> "1 mezzo live"
                        else -> "${v.value.size} mezzi live"
                    }
                )
                if (offRoute > 0) append(" ($offRoute su varianti del percorso)")
                append(" · aggiornato alle " + HHMM.format(Instant.ofEpochMilli(v.fetchedAt)))
            }
        }
        val hasLive = (line.vehicles as? Load.Ready)?.value?.isNotEmpty() == true
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (hasLive) LiveTheme.colors.live else Color.Unspecified,
            fontWeight = if (hasLive) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (line.patterns.size > 1) {
            TextButton(onClick = vm::switchDirection, contentPadding = BackButtonPadding) { Text("⇄ Cambia direzione") }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        // Un solo elenco fino in fondo allo schermo: avvisi della linea (se ci sono), poi le fermate.
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = LocalBottomBarInset.current)) {
            alertsBanner(lineAlerts, "questa linea", alertsOpen) { alertsOpen = !alertsOpen }
            itemsIndexed(line.stops, key = { i, stop -> "$i-${stop.id}" }) { i, stop ->
                LineStopRow(
                    stop = stop,
                    isFirst = i == 0,
                    isLast = i == line.stops.lastIndex,
                    vehicles = vehiclesByStop[stop.id].orEmpty(),
                    onClick = { vm.selectStop(stop) },
                )
            }
        }
    }
}

@Composable
private fun LineStopRow(
    stop: Stop,
    isFirst: Boolean,
    isLast: Boolean,
    vehicles: List<VehiclePosition>,
    onClick: () -> Unit,
) {
    val highlighted = vehicles.isNotEmpty()
    val lineColor = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            // Riquadro verde smussato come gli altri dell'app (fondo con la forma, senza ritagliare:
            // il tracciato della linea resta continuo tra una riga e l'altra).
            .background(if (highlighted) LiveTheme.colors.liveContainer else Color.Transparent, CardCorner)
            .clickable(onClick = onClick)
    ) {
        // Tracciato verticale della linea con il pallino della fermata.
        Box(Modifier.width(32.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(4.dp).weight(1f).background(if (isFirst) Color.Transparent else lineColor))
                Box(Modifier.width(4.dp).weight(1f).background(if (isLast) Color.Transparent else lineColor))
            }
            Box(
                Modifier
                    .size(if (highlighted) 16.dp else 12.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(3.dp)
                    .background(if (highlighted) LiveTheme.colors.live else lineColor, CircleShape)
            )
        }
        Column(Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp).weight(1f)) {
            Text(
                stop.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (highlighted) LiveTheme.colors.onLiveContainer else Color.Unspecified,
                fontWeight = if (isFirst || isLast || highlighted) FontWeight.Bold else FontWeight.Normal,
            )
            vehicles.forEach { v ->
                val where = when (v.currentStatus) {
                    VehicleStopStatus.STOPPED_AT -> "alla fermata"
                    VehicleStopStatus.INCOMING_AT -> "in arrivo"
                    VehicleStopStatus.IN_TRANSIT_TO, null -> "in viaggio verso questa fermata"
                }
                Text(
                    listOfNotNull(where, v.label?.let { "vettura $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = LiveTheme.colors.onLiveContainer,
                )
            }
        }
    }
}
