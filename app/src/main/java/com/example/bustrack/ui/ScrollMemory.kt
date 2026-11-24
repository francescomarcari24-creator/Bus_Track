package com.example.bustrack.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.maplibre.android.camera.CameraPosition

/**
 * Posizioni di scorrimento (liste, mappa) e piccole scelte di vista che devono sopravvivere
 * quando una pagina esce dallo schermo e poi ci torna (gesto indietro, cambio scheda).
 *
 * Vive per tutta la sessione dell'app sopra la navigazione: pagina e anteprima del gesto
 * indietro usano lo stesso stato, così l'anteprima mostra la lista già al punto giusto.
 * Una pagina aperta "in avanti" usa una chiave nuova e quindi riparte dall'alto.
 */
class ScrollMemory {
    private val lists = HashMap<String, LazyListState>()
    private val done = HashSet<String>()

    fun list(key: String): LazyListState = lists.getOrPut(key) { LazyListState() }

    /** Esegue un'azione una sola volta per chiave (es. lo scorrimento automatico al primo bus). */
    fun once(key: String): Boolean = done.add(key)

    /** Dimentica le liste delle pagine non più raggiungibili (chiavi con quel prefisso). */
    fun forgetAll(prefix: String, except: String? = null) {
        lists.keys.removeAll { it.startsWith(prefix) && it != except }
        done.removeAll { it.startsWith(prefix) && it != except }
    }

    /** Ultima inquadratura della mappa dei mezzi. */
    var mapCamera: CameraPosition? = null

    /** Permesso di posizione già chiesto in questa sessione (si chiede una volta sola, aprendo la mappa). */
    var locationAsked = false

    /** Schermata iniziale: false = preferiti, true = fermate vicine. */
    var homeShowNearby by mutableStateOf(false)

    /** Scheda Mezzi: mappa o lista, e filtro per linea. */
    var vehiclesShowMap by mutableStateOf(true)

    /** Direzione scelta aprendo la linea sulla mappa: vale finché il filtro resta su quella linea. */
    var mapLineChoice by mutableStateOf<MapLineChoice?>(null)

    /** Linea di cui la mappa ha già adattato l'inquadratura al tracciato (per non rifarlo a ogni ritorno). */
    var fittedLineKey: String? = null

    /** Scheda Mappa: false = si vedono i bus, true = si vedono le fermate. */
    var mapShowStops by mutableStateOf(false)
    var vehiclesFilter by mutableStateOf("")

    /** Scheda Avvisi: ricerca per linea (o testo). */
    var alertsQuery by mutableStateOf("")

    /** Cresce a ogni ritorno alla mappa generale: la mappa chiude le schede aperte. */
    var mapResetCount by mutableStateOf(0)
        private set

    /** Mappa generale: tutti i bus, senza filtro né direzione, schede chiuse (inquadratura invariata). */
    fun resetMap() {
        vehiclesFilter = ""
        mapLineChoice = null
        fittedLineKey = null
        mapShowStops = false
        vehiclesShowMap = true
        mapResetCount++
    }
}

/** Linea e direzione (con capolinea) scelte nella schermata della linea e mostrate sulla mappa. */
data class MapLineChoice(val label: String, val directionId: Int, val headsign: String?)

val LocalScrollMemory = staticCompositionLocalOf { ScrollMemory() }
