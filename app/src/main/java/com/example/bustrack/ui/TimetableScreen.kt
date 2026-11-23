package com.example.bustrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bustrack.gtfs.Departure
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val ROME_ZONE = ZoneId.of("Europe/Rome")

/** Orari di partenza dal capolinea, in formato "palina": una riga per ora con i minuti. */
@Composable
fun TimetableScreen(state: TimetableState, vm: TransitViewModel) {
    val today = LocalDate.now(ROME_ZONE)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TextButton(onClick = vm::closeTimetable, modifier = Modifier.padding(top = 8.dp)) {
            Text("← Linea ${state.route.label}")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RouteBadge(state.route.label)
            Text(
                "→ ${state.pattern.headsign ?: "Direzione ${state.pattern.directionId}"}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Text(
            "Partenze programmate da ${state.pattern.fromStopName ?: "capolinea"}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            FilterChip(
                selected = state.date == today,
                onClick = { vm.setTimetableDate(today) },
                label = { Text("Oggi") },
            )
            FilterChip(
                selected = state.date == today.plusDays(1),
                onClick = { vm.setTimetableDate(today.plusDays(1)) },
                label = { Text("Domani") },
            )
        }
        HorizontalDivider()
        when (val load = state.departures) {
            Load.Idle, Load.Loading -> CircularProgressIndicator(Modifier.padding(32.dp).align(Alignment.CenterHorizontally))
            is Load.Failed -> Text("Errore: ${load.message}", color = MaterialTheme.colorScheme.error)
            is Load.Ready -> if (load.value.isEmpty()) {
                Text("Nessuna partenza programmata in questo giorno", modifier = Modifier.padding(top = 16.dp))
            } else {
                HourGrid(load.value, mainHeadsign = state.pattern.headsign, key = state.date)
            }
        }
    }
}

private data class HourRow(val hour: Int, val departures: List<Departure>)

@Composable
private fun HourGrid(departures: List<Departure>, mainHeadsign: String?, key: LocalDate) {
    val now = System.currentTimeMillis() / 1000
    // Raggruppa per ora mantenendo l'ordine: le corse dopo mezzanotte (00, 01…) restano in fondo.
    val rows = mutableListOf<HourRow>()
    departures.forEach { d ->
        val hour = Instant.ofEpochSecond(d.time).atZone(ROME_ZONE).hour
        val last = rows.lastOrNull()
        if (last != null && last.hour == hour) rows[rows.lastIndex] = last.copy(departures = last.departures + d)
        else rows += HourRow(hour, listOf(d))
    }
    val next = departures.firstOrNull { it.time >= now }
    val variants = departures.any { it.headsign != null && it.headsign != mainHeadsign }

    val listState = rememberLazyListState()
    // Apre la lista sull'ora della prossima partenza.
    LaunchedEffect(key) {
        val index = rows.indexOfFirst { row -> row.departures.any { it == next } }
        if (index > 0) listState.scrollToItem(index - 1)
    }

    if (variants) {
        Text(
            "* corsa con destinazione diversa",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    LazyColumn(state = listState) {
        items(rows, key = { it.departures.first().tripId + it.departures.first().time }) { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Text(
                    "%02d".format(row.hour),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(44.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.departures.forEach { d -> MinuteCell(d, isPast = d.time < now, isNext = d == next, mainHeadsign) }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun MinuteCell(d: Departure, isPast: Boolean, isNext: Boolean, mainHeadsign: String?) {
    val minute = Instant.ofEpochSecond(d.time).atZone(ROME_ZONE).minute
    val marker = if (d.headsign != null && d.headsign != mainHeadsign) "*" else ""
    val text = "%02d".format(minute) + marker
    if (isNext) {
        // Prossima partenza evidenziata.
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                .padding(horizontal = 6.dp),
        )
    } else {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (isPast) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        )
    }
}
