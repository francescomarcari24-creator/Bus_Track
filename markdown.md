# BusTrack — appunti di progetto

App Android per il trasporto pubblico di **Roma**: cerca linee e fermate, mostra arrivi e mezzi in tempo reale, orari programmati, avvisi.
Dati: **GTFS statico + GTFS-Realtime di Roma Mobilità**.

> **Regola:** ogni modifica al progetto va aggiunta a questo file (sezione *Registro modifiche*, e le altre sezioni se cambiano).

Ultimo aggiornamento: 2026-11-24

---

## 1. Come riprendere in 2 minuti

| Cosa | Comando / dove |
|---|---|
| Build debug | `./gradlew :app:assembleDebug` |
| Build release (quella da provare: molto più fluida) | `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (firmata con la chiave di debug, si installa sopra la debug) |
| Dopo l'installazione della release | `adb -s RFCTC0EWJAD shell cmd package compile -m speed-profile -f com.example.bustrack` (applica subito i profili di compilazione) |
| Test unitari (JVM) | `./gradlew :app:testDebugUnitTest` |
| Installa sul telefono | `adb -s RFCTC0EWJAD install -r app/build/outputs/apk/debug/app-debug.apk` |
| Avvia l'app | `adb -s RFCTC0EWJAD shell am start -n com.example.bustrack/.MainActivity` |
| Log dei download dei feed | `adb logcat -s BusTrack` |
| Rigenera il database statico | `python tools/build_gtfs_db.py <rome_static_gtfs.zip> app/src/main/assets/gtfs.db` |

- **Le prove si fanno sul telefono collegato** (Samsung Galaxy S23, `SM-S911B`, seriale adb `RFCTC0EWJAD`, 1080×2340, tema scuro, colori dinamici), **non sull'emulatore**.
- Per pilotare la UI via adb: toccare gli elementi cercando testo / `content-desc` con `uiautomator dump`, non a coordinate fisse. Per scrivere nella ricerca toccare `class="android.widget.EditText"`. **Chiudere la tastiera Samsung prima di scorrere** (`input keyevent 4` se `mInputShown=true`; ESC non la chiude).
- Git: branch `main` (rinominato da `master` il 2026-11-23), primo commit il 2026-11-23 (autore "Francesco Marcari", identità Git impostata globalmente). Fare commit e push solo se richiesto.
- GitHub: `origin` = https://github.com/francescomarcari24-creator/Bus_Track (push via HTTPS; la GitHub CLI `gh` è installata e autenticata).

## 2. Ambiente

- Android Studio, Gradle wrapper, AGP 9.5.2, Kotlin 2.4.21, Compose BOM 2026.11.01
- `compileSdk`/`targetSdk` 37, `minSdk` 34, Java 11
- Package: `com.example.bustrack`
- Dipendenze aggiunte:
  - `com.google.transit:gtfs-realtime-bindings:0.0.4` (l'ultima su Maven Central; usa protobuf-java 2.6.1)
  - `org.maplibre.gl:android-sdk:13.7.1` (mappa)
  - `androidx.lifecycle:lifecycle-runtime-compose` e `lifecycle-viewmodel-compose`
- Permessi: `INTERNET`, `ACCESS_FINE_LOCATION` e `ACCESS_COARSE_LOCATION` (posizione sulla mappa, chiesta a runtime)
- Python 3 (solo stdlib) per lo script del database.
- Build da terminale: serve `JAVA_HOME` sul JDK 25 di Android Studio (`C:\Program Files\Android\Android Studio1\jbr`) e `local.properties` con `sdk.dir=C:/Users/franc/AppData/Local/Android/Sdk` (non versionato). `adb` è in `C:\Users\franc\AppData\Local\Android\Sdk\platform-tools` (non nel PATH).

## 3. Fonti dati

### GTFS statico
- URL: `https://romamobilita.it/wp-content/uploads/shared/rome_static_gtfs.zip` (~48 MB zip, ~275 MB scompattato; `stop_times.txt` da solo 243 MB / 5,18 M righe, **righe di ogni corsa consecutive e in ordine**). Ripubblicato spesso (es. 24/11/2026 20:47).
- MD5 pubblicato in `rome_static_gtfs.zip.md5` (la HEAD risponde 403, la GET funziona: `<md5>  rsm/rome_static_gtfs.zip`).
- Database incluso nell'app: GTFS del 24/11/2026 (MD5 `2488580448c9a636e10191cb837e7fe3`), valido **21/10/2026 → 31/12/2026**.
- **Aggiornamento automatico sul telefono** (`gtfs/update/`): controllo giornaliero (WorkManager) dell'MD5; se cambia scarica lo zip (in Wi‑Fi, o su rete a consumo se i dati scadono entro 3 giorni), ne verifica l'MD5, ricostruisce il database con `GtfsDbBuilder` (traduzione Kotlin di `tools/build_gtfs_db.py`, ~25 s sul S23) e lo installa come `no_backup/gtfs-downloaded.db`; l'app usa il più recente tra questo e quello incluso. Verificato: database identico a quello dello script Python (tutte le tabelle, 5.175.470 orari).
- Prova manuale (solo build debug): `adb shell am broadcast -a com.example.bustrack.DEBUG_UPDATE_GTFS -p com.example.bustrack --ez force true`.

### GTFS-Realtime (live, aggiornati ~ogni 30 s)
Ritmo misurato il 2026-11-24 (download ogni 5 s per 6 min, confrontando `header.timestamp` e `Last-Modified`): **posizioni e arrivi** escono insieme ogni ~30 s (28–33 s), il file è pubblicato ~3 s dopo il timestamp; **avvisi** ogni 5 minuti esatti (:00, :05…), pubblicati ~10 s dopo. Nessun `Cache-Control`. L'app riscarica agganciandosi al timestamp del feed (`gtfs/FeedTiming.kt`).
Base: `https://romamobilita.it/wp-content/uploads/shared/` (i vecchi URL `/sites/default/files/` fanno redirect 301 qui)
- `rome_rtgtfs_trip_updates_feed.pb` → arrivi previsti
- `rome_rtgtfs_vehicle_positions_feed.pb` → posizioni mezzi
- `rome_rtgtfs_service_alerts_feed.pb` → avvisi

Gli ID di corse (`trip_id`, es. `0#575-13`) e fermate (`stop_id`) del live coincidono con quelli dello statico.

### Mappa
- Stile vettoriale **OpenFreeMap** `https://tiles.openfreemap.org/styles/liberty` (gratis, senza API key, dati OSM).
- Provati e scartati: tile di openstreetmap.org (403, policy contro le app) e CARTO (ora richiede API key).

## 4. Database statico `app/src/main/assets/gtfs.db`

Generato da `tools/build_gtfs_db.py` (o sul telefono da `GtfsDbBuilder`, identico). ~23 MB su disco. Schema **versione 5**.
Al primo avvio (e dopo ogni aggiornamento dell'app) viene copiato in `noBackupFilesDir` e aperto in sola lettura; se c'è un `gtfs-downloaded.db` più recente (aggiornamento automatico) si usa quello. Un'unica istanza condivisa (`GtfsStaticDb.shared`).

| Tabella | Contenuto |
|---|---|
| `meta` | `schema_version`, `feed_start_date`, `feed_end_date`, `built_at`, `source_md5` (MD5 dello zip di origine), `built_on` (`device` se costruito sul telefono) |
| `routes` | linee (`route_id`, nome breve/lungo, tipo, colori, agenzia) |
| `stops` | fermate (`stop_id`, codice palina, nome, lat/lon) |
| `trips` | corse: linea, capolinea (`headsign`), direzione, `service_id`, `profile_id`, `start_sec` |
| `profile_stops` | "profili" orari: sequenza fermate + offset in secondi dalla partenza |
| `calendar_dates` | giorni in cui ogni `service_id` è attivo (solo `exception_type = 1`) |
| `stop_routes` | quali linee passano da ogni fermata |
| `route_patterns` | percorso **più frequente** per linea+direzione, con capolinea e lista fermate |
| `route_shapes` | **tracciati** (da `shapes.txt`) per linea+direzione: il principale (`is_main = 1`, quello usato più spesso dalle corse del percorso tipico) e le **varianti** (≥ 2% delle corse e ≥ 2 corse, max 5), con `shape_id` e `trip_count`; semplificati (Douglas-Peucker 3 m) e salvati come *encoded polyline* di Google (precisione 1 m). 1.051 tracciati (719 principali) |

**Compressione degli orari:** molte corse fanno le stesse fermate con gli stessi tempi relativi → 15.796 profili (523k righe) invece di 5,15 M righe. Orario a una fermata = `trips.start_sec + profile_stops.offset_sec` + mezzanotte del giorno di servizio.
Gli orari GTFS possono superare 24:00; il riferimento del giorno è "mezzogiorno − 12 h" (corretto anche ai cambi d'ora).

## 5. Architettura del codice (`app/src/main/java/com/example/bustrack/`)

```
MainActivity.kt              → avvia BusTrackApp() nel tema
gtfs/
  Models.kt                  → Route, Stop, Trip, Arrival, StopArrivals, VehiclePosition,
                               ServiceAlert, RoutePattern, Departure, VehicleStopStatus…
  GtfsStaticDb.kt            → accesso SQLite: ricerca fermate/linee, percorsi, arrivi
                               programmati, partenze dal capolinea (interfaccia StaticLookup)
  GtfsRealtimeParser.kt      → protobuf → modelli, arricchiti coi nomi dello statico
  Polyline.kt                → decodifica delle polilinee codificate (tracciati); test con l'esempio ufficiale Google
  HtmlEntities.kt            → pulizia testi avvisi: entità HTML (&#039; → ') e caratteri C1 Windows-1252 (U+0092 → ’, U+0093/94 → “ ”); usare `clean()`
  TransitRepository.kt       → scarica i feed (HttpURLConnection), unisce live + programmato
favorites/Favorites.kt       → preferiti su SharedPreferences ("favorites"), ordine di aggiunta
ui/
  TransitViewModel.kt        → stato di tutte le schermate, polling, aggiornamenti manuali
  TransitScreens.kt          → BusTrackApp, Home (ricerca+preferiti), dettaglio fermata,
                               scheda Mezzi/Mappa, Avvisi, componenti comuni
  LineScreen.kt              → popup direzione + elenco fermate della linea con bus live
  TimetableScreen.kt         → orari di partenza dal capolinea (griglia per ora)
  VehicleMap.kt              → mappa MapLibre con due livelli alternativi: bus (live, verde) e fermate
                               (statiche, cluster nativi MapLibre); schede di mezzo e di fermata
  FloatingTabBar.kt          → barra in basso flottante (Home / Mappa / Avvisi)
  PredictiveBack.kt          → gesto indietro predittivo con anteprima
  ScrollMemory.kt            → conserva scroll liste, inquadratura mappa, vista/filtro/livello (bus o fermate) della mappa
  theme/LiveColors.kt        → verde "live" (chiaro/scuro), fisso anche con colori dinamici
tools/build_gtfs_db.py       → GTFS zip → gtfs.db
```

**Navigazione** (senza Navigation Compose, a stato):
- Schede: cronologia `history` in `BusTrackApp`; indietro torna alla scheda precedente.
- Pila della scheda Home ricavata dallo stato: `Home → Linea → Orari` oppure `Home/Linea → Fermata` (`StopsState.pageStack()`).
- Ogni livello è dentro un `PredictiveBackLayer`; l'anteprima è composta con `LocalInBackPreview = true` (niente popup, back handler o polling).

## 6. Funzionalità attuali

- **Home**: barra di ricerca (linee per numero, fermate per nome/palina) + **preferiti** (linee con direzione, fermate). Il tasto *Home* in basso torna sempre qui e svuota la ricerca.
- **Linea**: popup per scegliere la direzione (saltato per linee a direzione unica/circolari) → elenco fermate del percorso tipico con i **bus live evidenziati** ("alla fermata", "in arrivo", "in viaggio verso"); scorre al primo bus; cambia direzione; tocca una fermata per gli arrivi. In alto: ⏰ orari, ☆ preferito, ↻ aggiorna.
- **Orari dal capolinea**: griglia ora → minuti, Oggi/Domani, prossima partenza evidenziata, `*` = destinazione diversa.
- **Fermata**: arrivi live + **programmati quando manca il live** (prossime 2 h); se il feed non risponde mostra solo i programmati con avviso. ☆ preferito, ↻ aggiorna.
- **Mappa** (scheda Mappa): in alto uno **slider Bus / Fermate** (`SegmentedButton`). *Tracciato*: scrivendo un numero di linea nel filtro (o con l'icona mappa 🗺 nella schermata della linea, che apre la scheda Mappa già filtrata) si vede il tracciato della linea, una polilinea per direzione col bordo bianco e in colore tema, sotto a fermate e bus; la mappa si inquadra sul percorso una volta per linea (`ScrollMemory.fittedLineKey`). **Direzione scelta**: aprendo la linea dall'icona mappa si ricorda direzione e capolinea (`ScrollMemory.mapLineChoice`, valida finché il filtro resta su quella linea): quella direzione è più marcata, con bordo bianco e **frecce ">" nel verso di marcia** (layer `route-arrows`, testo su linea), l'altra è sottile e attenuata (`route-other`); la riga di stato indica «Direzione → capolinea». Se il filtro è scritto a mano si vedono entrambe le direzioni uguali. Vale in entrambi i livelli. *Bus*: mappa o lista dei mezzi live, filtro per linea, tocca un mezzo per i dettagli, aggiornamento automatico **60 s su mappa, 30 s su lista** (orologio monotono); la mappa si vede anche a feed vuoto, con il messaggio sopra. *Fermate*: tutte le 8.326 fermate, raggruppate in cerchi col numero (tocca un gruppo per ingrandire), singole con il nome da zoom 15; il filtro per linea mostra solo le fermate di quella linea; tocca una fermata → scheda con palina e linee → **«Apri fermata e arrivi»** porta alla pagina Home della fermata (indietro, gesto o pulsante «← Mappa», riporta **direttamente alla mappa** nella stessa posizione e con il tracciato, senza passare dalla Home: `StopsState.openedFromMap`, `pageStack()` con un solo livello, `LocalTabBack`). Il livello fermate è statico (colore tema, non verde) e sospende il polling dei mezzi.
- **Avvisi**: avvisi attivi con linee coinvolte, espandibili.
- **Verde = live**: tutto ciò che viene dal tracciamento live è verde (badge, minuti, punti mappa, righe evidenziate); il programmato usa i colori normali.
- **Aggiorna** (↻) in alto a destra su fermata, linea, mappa, avvisi; rotellina durante il caricamento, i dati vecchi restano visibili.
- **Gesto indietro predittivo** con anteprima della schermata precedente; **lo scroll e l'inquadratura mappa si conservano** tornando indietro o cambiando scheda (non tra un riavvio e l'altro).
- **Barra in basso** flottante a pillola, 48 dp, solo testo: Home / Mappa / Avvisi (sfondo `surfaceContainerHigh` con ombra). **Il rettangolo alla base dello schermo è trasparente**: ogni scheda arriva fino al bordo e le liste scorrono *sotto* la pillola; in fondo hanno uno spazio finale (`contentPadding` = `LocalBottomBarInset`) per portare l'ultima voce sopra la barra. Le colonne delle schermate non hanno margine inferiore.

## 7. Particolarità e problemi noti dei dati di Roma

- **Versioni d'orario sovrapposte**: il feed contiene `0#…` (fino al 22/11) e `1#…` (dal 23/11); di notte le stesse corse compaiono due volte → deduplica per linea + destinazione + orario.
- **Linea 8**: il GTFS pubblica due servizi alternativi (`0#35` e `0#57`), ma i mezzi live sono tutti di `0#57` (verificato il 24/11/2026): gli orari programmati dei servizi senza corse live vengono nascosti (`dropUntrackedSchedules`).
- **Tram 8** risulta con 16 fermate nel percorso più frequente (probabile percorso ridotto per lavori).
- **Ritardi/anticipi notturni**: molti bus notturni risultano "in anticipo 6–7 min"; è il valore del feed.
- **Feed mezzi vuoto**: a volte (es. ~03:00) il feed delle posizioni risponde HTTP 200 con 0 byte; l'app mostra "Nessun mezzo in servizio" sopra la mappa.
- La mappa di base (OpenFreeMap) disegna già le sue icone di fermata bus a zoom alto: non sono le nostre fermate (quelle sono i cerchi in colore tema).
- **Avvisi**: testi con entità HTML (`&#039;`) → decodificate in app. Il feed contiene anche virgolette/apostrofi Windows-1252 salvati come caratteri di controllo C1 (U+0092 ’, U+0093/U+0094 “ ”: ~22 occorrenze, es. "nell’area", avviso 058): il font li disegna come riquadri vuoti → rimappati in `HtmlEntities.clean()`. Un byte `0x9D` simile è rumore binario protobuf, non testo.
- 147 linee hanno una sola direzione nei dati (138 con un solo capolinea: circolari/a senso unico).
- Elenco fermate di una linea = solo il percorso più frequente; le varianti non sono elencate (i loro bus sono contati a parte). Orari dal capolinea = solo corse che partono dal primo stop del percorso tipico.

## 8. Test

- `GtfsRealtimeParserTest` — parsing dei 3 feed su snapshot reali in `app/src/test/resources/gtfs-rt/` (nessuna entità HTML né carattere C1 negli avvisi; l'avviso 058 ha titolo “Corri al Tiburtino”).
- `FavoriteTest` — codifica/decodifica dei preferiti.
- `PolylineTest` — decodifica polilinee (esempio ufficiale Google, uguale a quello usato per verificare l'encoder Python).
- `HtmlEntitiesTest` — decodifica entità e rimappatura dei caratteri C1 (nei sorgenti i C1 si scrivono con `c1(0x92)`, mai come caratteri letterali: sono invisibili).
- `GtfsStaticDb` usa SQLite Android: verificato sul dispositivo e con query Python sul `.db`, non con test JVM.

## 9. Idee / da fare

- Scaricare automaticamente il GTFS statico aggiornato (oggi è manuale).
- Nel livello fermate: evidenziare le fermate di una linea in ordine di percorso e/o fermate vicine alla posizione dell'utente.
- Tracciato: mostrare le varianti del percorso (oggi solo il tracciato più frequente); scegliere/cambiare la direzione direttamente dalla mappa; nascondere i bus dell'altra direzione quando ne è scelta una.

---

## Registro modifiche

Formato: data — cosa è cambiato (file principali).

### 2026-11-23
1. **Integrazione GTFS**: script `tools/build_gtfs_db.py` → `gtfs.db`; parser GTFS-RT; repository; UI a 3 schede (Fermate, Mezzi, Avvisi); permesso INTERNET; snapshot `.pb` come fixture di test.
2. **Mappa dei mezzi** (MapLibre + OpenFreeMap, dopo osmdroid/OSM e CARTO scartati) e **orari programmati** come fallback del live (profili compressi nel DB, schema v2). Fix: parametri SQLite passati come testo → `CAST(? AS INTEGER)`; deduplica versioni d'orario.
3. **Gesto indietro tra le schede** (cronologia schede); la scheda di un mezzo sulla mappa si chiude col back.
4. **Ricerca linee** → popup direzione → elenco fermate con bus live evidenziati (`route_patterns`, schema v3; `LineScreen.kt`).
5. **Tasto aggiorna** in alto a destra su ogni schermata con dati live (`RefreshButton`, chiavi `REFRESH_*`).
6. **Mappa: aggiornamento automatico ogni 60 s** (lista 30 s); età dei dati con orologio monotono; log `BusTrack` dei download.
7. **Verde per tutto ciò che è live** (`theme/LiveColors.kt`, contrasto verificato).
8. **Preferiti** (linee con direzione e fermate) in Home, stella ☆ in alto su linea e fermata (`favorites/Favorites.kt`).
9. **Tasto Fermate/Home in basso** riporta sempre alla schermata iniziale.
10. **Installazione sul telefono** collegato (Galaxy S23); da qui in poi prove solo sul telefono.
11. **Orari di partenza dal capolinea** (icona ⏰ nella schermata linea, `TimetableScreen.kt`).
12. **Gesto indietro predittivo** con anteprima (`PredictiveBack.kt`), sostituisce i vari `BackHandler`.
13. **Scroll conservato** tornando indietro/cambiando scheda; anche inquadratura mappa, vista e filtro Mezzi (`ScrollMemory.kt`, `LineState.openId`).
14. **Barra in basso flottante** a pillola, più bassa, solo testo "Home / Mappa / Avvisi" (`FloatingTabBar.kt`); logo/attribuzione mappa e scheda mezzo spostati sopra la barra.
15. **Entità HTML negli avvisi** convertite in caratteri normali (`gtfs/HtmlEntities.kt`, `HtmlEntitiesTest`).
16. Creato questo file `markdown.md`.
17. **Primo commit Git** di tutto il progetto (incluso `markdown.md` e `gtfs.db`, ~22 MB).
18. **Push su GitHub** (`origin/master`, repository `francescomarcari24-creator/Bus_Track`).
19. **Branch rinominato `master` → `main`**: `main` impostato come predefinito su GitHub, `origin/master` eliminato.
20. **Fascia alla base dello schermo trasparente** (`TransitScreens.kt`, `LineScreen.kt`, `TimetableScreen.kt`): prima le liste venivano tagliate sopra la barra lasciando un rettangolo vuoto; ora arrivano fino al bordo e scorrono sotto la pillola. Provata prima una barra interamente trasparente, poi annullata: ripristinato lo sfondo della pillola (`FloatingTabBar.kt`).
21. **Riquadri vuoti negli avvisi corretti** (`HtmlEntities.clean()`, `GtfsRealtimeParser`): virgolette e apostrofi Windows-1252 arrivati come caratteri di controllo C1 ora diventano ’ “ ”; test sullo snapshot reale.
22. **Mappa con livello Fermate** (`VehicleMap.kt`, `TransitScreens.kt`, `TransitViewModel.kt`, `GtfsStaticDb.allStops/stopIdsForLine`): slider Bus/Fermate, fermate raggruppate, filtro per linea, scheda fermata con apertura degli arrivi (`LocalOpenStop`); la mappa non viene più sostituita dal messaggio a feed vuoto.
23. **Tracciato della linea sulla mappa** (`tools/build_gtfs_db.py` → schema v4 con `route_shapes`, `gtfs/Polyline.kt`, `GtfsStaticDb.routeShapes`, `VehicleMap.kt` layer `route-*`, icona mappa in `LineScreen.kt`, `setMapLineFilter`): database 22,3 → 22,9 MB. Provato sul telefono con la linea 64 in entrambi i livelli.
24. **Direzione evidenziata sul tracciato e ritorno dalla fermata alla mappa** (`VehicleMap.kt` layer `route-other/casing/line/arrows`, `ScrollMemory.MapLineChoice`, `TransitViewModel.openStopFromMap/closeStopIfFromMap`, `TransitScreens.kt` `LocalTabBack`): provato sul telefono con la linea 64 in entrambe le direzioni e con il gesto indietro da una fermata aperta dalla mappa.

### 2026-11-24
1. **Posizione dell'utente sulla mappa** (`VehicleMap.kt`, `AndroidManifest.xml`, `ScrollMemory.locationAsked`): pallino GPS con cerchio di precisione (LocationComponent di MapLibre, motore di posizione di Android, senza Play Services). Il permesso si chiede una volta per sessione, quando la scheda Mappa diventa visibile (le schede sono composte tutte insieme: l'effetto dipende da `LocalInBackPreview`). Tasto "La mia posizione" in alto a destra (icona Material `my_location` disegnata a mano, non presente in `material-icons-core`): centra sulla posizione (zoom almeno 15,5) e la segue; spostando la mappa smette di seguirla e l'icona torna grigia. Senza permesso, il tasto lo richiede. Provato sul telefono.
2. **Warning di Gradle eliminati** (`TransitScreens.kt`, `LineScreen.kt`): i 7 `ListItem` usavano l'overload deprecato in Material3 1.5.0; il titolo (`headlineContent`) ora è la lambda finale `content`. Build senza warning Kotlin. Il nuovo overload ha angoli arrotondati per ogni voce e 12 dp (invece di 16) dopo il contenuto iniziale: il wrapper `ui/AppListItem.kt` ripristina l'aspetto di prima (verificato sul telefono confrontando le schermate con la versione precedente).
3. **Elenco fermate della linea dalla mappa** (`TransitScreens.kt`, `VehicleMap.kt`, `LineScreen.kt`, `TransitViewModel.openLineFromMap`, `StopsState.lineFromMap`): con il filtro su una linea valida, tasto "Fermate →" nella riga di stato (sempre popup della direzione sopra la mappa, dal 2026-11-24 anche con una direzione evidenziata: annullando si resta lì); nella scheda di un mezzo, "Fermate della linea →" nella sua direzione. La linea si apre nella scheda Home senza la Home sotto: "← Mappa" e il gesto indietro tornano alla mappa. Provato sul telefono con la linea 64.
4. **App bloccata in verticale** (`AndroidManifest.xml`, `screenOrientation="portrait"` su `MainActivity`). Con targetSdk 36+ Android ignora il blocco solo sugli schermi grandi (larghezza minima ≥ 600 dp, tablet/pieghevoli aperti), non sui telefoni.
5. **Fermate vicine nella Home** (`gtfs/Nearby.kt` + `NearbyTest`, `ui/UserLocation.kt`, `TransitViewModel.refreshNearby`/`NearbyState`, `TransitScreens.kt` `nearbySection`, `ScrollMemory.homeShowNearby`): pillola "Preferiti / Vicine" in alto, identica alla barra in basso per aspetto e comportamento (`PillSwitch`/`PillTabs` estratti da `FloatingTabBar.kt`: tocco o trascinamento dell'indicatore, elenchi che scorrono insieme con `SlidingPanes`, ciascuno in un riquadro leggero con 16 dp di stacco per distinguerli nello scorrimento); fermate entro 1 km (max 20) con distanza e linee, posizione dal motore di Android (ultima nota se < 2 min, altrimenti nuova lettura con timeout 15 s), ricerca ripetuta al massimo ogni 2 min o col tasto aggiorna. Permesso chiesto solo toccando "Vicine" o "Consenti"; messaggi per permesso negato e localizzazione spenta.
6. **Scelta "Preferiti / Vicine" ricordata alla riapertura** (`TransitScreens.kt` `BusTrackApp`): salvata nelle preferenze `ui` (chiave `home_show_nearby`) e riletta all'avvio.
6. **Scelta "Preferiti / Vicine" ricordata alla riapertura** (`TransitScreens.kt` `BusTrackApp`): salvata nelle preferenze `ui` (chiave `home_show_nearby`) e riletta all'avvio.
7. **Freccia verso il capolinea nei pallini dei mezzi** (`VehicleMap.kt` layer `vehicles-arrow` + `arrowBitmap`, `TransitViewModel.termini`/`loadTermini`, `gtfs/Nearby.kt` `bearingDegrees` + test): capolinea = ultima fermata del percorso tipico di linea e direzione (cache per sessione); freccia bianca ruotata dalla posizione del mezzo verso il capolinea, pallino portato da 6 a 7 dp con freccia da 10 dp. Nessuna freccia per circolari, mezzi senza direzione o a meno di 100 m dal capolinea.
8. **Fix: popup della direzione dal tasto "Fermate →" della mappa** (`TransitScreens.kt`): il `DirectionDialog` era dentro la scheda Home e veniva soppresso quando la Home non è visibile (`LocalInBackPreview` vale true per le schede nascoste), quindi dalla mappa non compariva. Spostato in `BusTrackApp`, fuori dalle schede. Il tasto ora chiede sempre la direzione. Provato sul telefono con la linea 64.
9. **Aggiornamento automatico agganciato alla centrale** (`gtfs/FeedTiming.kt` + `FeedTimingTest`, `Load.Ready.feedTimestamp`, `StopArrivals.feedTimestamp`, `TransitViewModel.loadFeed`/`liveDelayMs`, cicli in `TransitScreens.kt`): misurato il ritmo dei feed (vedi sezione 3), ora mappa, lista mezzi, linea e arrivi riscaricano a timestamp del feed + 30 s + 5 s di pubblicazione (prima: timer fissi di 30 s, 60 s sulla mappa); se il feed atteso non è ancora uscito si riprova dopo 5 s, se è fermo da più di un periodo si torna ogni 30 s. Avvisi: ogni 5 min + 15 s finché la scheda è visibile (prima solo all'apertura).
10. **Righe senza riquadro proprio nei pannelli della Home** (`AppListItem.kt` `LocalTransparentListItems`, `TransitScreens.kt`): dentro "Preferiti" e "Vicine" lo sfondo delle righe è trasparente, così resta visibile solo il riquadro grande; altrove (es. popup della direzione) le righe mantengono il loro sfondo.
11. **Pillola "Bus / Fermate" sulla mappa** (`TransitScreens.kt`): al posto dei `SegmentedButton`, lo stesso `PillSwitch` della barra in basso e di "Preferiti / Vicine" (aspetto, tocco e trascinamento dell'indicatore); la mappa sotto non scorre, cambia solo il livello.
12. **Fluidità** (`VehicleMap.kt` `MapViewLifecycle`, `FloatingTabBar.kt` `LocalTabOnScreen`, `TransitScreens.kt` `Spinner`, `app/build.gradle.kts`, `app/src/main/keepRules/protobuf.keep`): misurato con `dumpsys gfxinfo` che l'app ferma in Home disegnava ~120 fotogrammi/s. Cause: la mappa (TextureView) restava attiva nelle schede nascoste, con la pulsazione infinita del pallino posizione; e le rotelline di caricamento di schede/pannelli nascosti (Avvisi, Vicine) giravano sempre. Ora la mappa va in pausa fuori schermo, niente pulsazione, le rotelline girano solo se visibili: **0 fotogrammi/s a riposo**. Inoltre build release ottimizzata (R8 su tutta l'app: il `packageScope` del modello causava `IllegalAccessError` all'avvio; regole per protobuf/GTFS-RT in `keepRules`), firmata con la chiave di debug, più profili di compilazione: niente più fotogrammi saltati all'avvio (prima 34–53).
13. **Orari fantasma nascosti** (`gtfs/Models.kt` `dropUntrackedSchedules`/`serviceGroup`, `TransitRepository.arrivalsAt`, `GtfsStaticDb.serviceIds`, `UntrackedSchedulesTest`): verificato col feed live del 24/11 sera che le corse live della linea 8 sono tutte del servizio `0#57` e nessuna di `0#35`. Regola generale: se una linea ha corse live, gli orari programmati dei suoi gruppi di servizio senza corse live non si mostrano; senza dati live si vede tutto.
14. **Avvisi filtrati** (`TransitScreens.kt` `AlertsScreen`/`AlertsBanner`, `LineScreen.kt`, `ServiceAlert.concerns`, `TransitViewModel.ensureAlerts`): nella scheda Avvisi prima "Per i tuoi preferiti" poi "Tutti gli altri"; nelle pagine di linea e fermata un riquadro "⚠ N avvisi" apribile.
15. **Mappa** (`TransitScreens.kt`, `VehicleMap.kt`, `TransitViewModel.mapLinePatterns`): scelta della direzione direttamente sulla mappa (Entrambe / → capolinea) con mezzi e fermate dell'altra direzione nascosti; **varianti del percorso** tratteggiate (schema DB 5); **fermate vicine** (entro 1 km) evidenziate nel livello fermate, mai raggruppate.
16. **Avviso "il mio bus sta arrivando"** (`watch/ArrivalWatchService.kt`, campanella in `ArrivalRow`, `WatchDialog`): servizio in primo piano (`dataSync`) con notifica fissa dei minuti mancanti, aggiornata al ritmo della centrale, e notifica sonora a 1/3/5/10 min; annullabile, si ferma da solo se il bus passa o dopo 2 ore. Permessi `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE(_DATA_SYNC)`.
17. **Aggiornamento automatico del GTFS statico** (`gtfs/update/CsvReader.kt`, `ShapeTools.kt`, `GtfsDbBuilder.kt`, `GtfsUpdater.kt`, `GtfsUpdateWorker.kt`, `GtfsStaticDb.shared/installDownloaded`, `GtfsBuilderPartsTest`; dipendenza WorkManager 2.12.0): vedi sezione 3. Database incluso rigenerato col GTFS del 24/11/2026 (schema 5).
18. **Fix: avvisi aperti non scorrevoli** (`TransitScreens.kt` `alertsBanner`/`rememberAlertsOpen`, `LineScreen.kt`): aperto, il riquadro avvisi di fermata/linea copriva la pagina (che sopra l'elenco non scorre) e non si poteva scendere. Ora riquadro e avvisi sono voci in cima all'elenco di arrivi/fermate: un'unica area che scorre fino in fondo allo schermo (lo scorrimento automatico al primo mezzo della linea tiene conto delle voci in più).
19. **Mappa scura col tema scuro** (`VehicleMap.kt`): stile OpenFreeMap `dark` quando il telefono è in tema scuro (prima la mappa restava chiara); aloni dei testi e bordo del tracciato quasi neri sulla mappa scura, frecce di marcia sempre bianche. Lo stile si carica in un effetto che dipende dal tema e ricrea i livelli dell'app.
20. **Widget "Prossimi passaggi"** (`widget/StopWidget.kt`, `StopWidgetWorker.kt`, `StopWidgetConfigActivity.kt`, `res/xml/stop_widget_info.xml`, `keepRules/widget.keep`; Glance 1.2.0): si sceglie una fermata preferita all'aggiunta; mostra i prossimi 4 passaggi (badge verde se live, destinazione, **orario** e non minuti, perché Android aggiorna i widget al massimo ogni 15 min), ora dell'aggiornamento e tasto ↻; toccandolo apre l'app sugli arrivi della fermata (`MainActivity.EXTRA_OPEN_STOP_ID` → `PendingStopOpen` → `TransitViewModel.openStopById`).
21. **Mappa con angoli arrotondati** (`VehicleMap.kt` clip `CardCorner`, `TransitScreens.kt`): come gli altri riquadri, 28 dp su tutti gli angoli e 16 dp di margine ai lati; si ferma sopra la barra flottante (dentro la mappa `LocalBottomBarInset` = 0, quindi logo, schede e inquadrature non lasciano più spazio alla barra). Prima spigoli vivi e a tutta larghezza.
22. **Titoli allineati alle righe** (`TransitScreens.kt` `SectionTitle(alignToListItems)`, `ListItemInset`): "Linee preferite", "Fermate preferite", i titoli dei risultati di ricerca, la riga di stato di "Vicine" e il messaggio senza preferiti partono a 16 dp, allineati al badge della linea e all'inizio del nome della fermata.
23. **"Mappa" nella barra riporta alla mappa generale** (`ScrollMemory.resetMap`, `TransitScreens.kt`, `VehicleMap.kt`): come "Home", il tocco su "Mappa" toglie filtro e direzione, torna al livello Bus con tutti i mezzi e chiude le schede aperte (inquadratura invariata); il gesto indietro invece ritrova la mappa com'era.
24. **Ricerca negli avvisi** (`TransitScreens.kt` `AlertsScreen`, `ServiceAlert.matchesSearch`, `ScrollMemory.alertsQuery`, `AlertSearchTest`): campo "Cerca una linea" in cima alla scheda Avvisi; numero di linea esatto ("6" non trova il 64), da 3 caratteri anche nel testo; mostra "N avvisi per la linea X" o "Nessun avviso attivo per la linea X".
25. **"Avvisi" nella barra riporta all'elenco completo** (`TransitScreens.kt`): come "Home" e "Mappa", il tocco su "Avvisi" svuota la ricerca per linea.
26. **Fix: widget fermo su "Carico i passaggi…"** (`StopWidgetWorker.runNow/runNowIfAny`, `StopWidgetConfigActivity.kt`, `MainActivity.onStop`): il primo caricamento partiva nella schermata di scelta della fermata e veniva annullato chiudendola, e il primo giro periodico era già passato prima della scelta. Ora il primo caricamento è un lavoro WorkManager immediato; inoltre i widget si aggiornano quando si esce dall'app. Verificato sul telefono riconfigurando il widget.
27. **Impostazioni** (`settings/AppSettings.kt`, `settings/SettingsActivity.kt`, `PillButton` in `FloatingTabBar.kt`, tasto ⚙ in Home accanto alla pillola con spazi uguali bordo/pillola/tasto/bordo di 16 dp): raggio delle fermate vicine (500 m–2 km, usato anche per la mappa), minuti proposti per l'avviso di arrivo, orari solo in Wi‑Fi (`GtfsUpdater`), validità dei dati in uso e "Controlla aggiornamenti ora" con esito (`GtfsUpdateWorker` `KEY_OUTCOME`/`NOW_NAME`), fonti dei dati (Roma Mobilità, OpenStreetMap/OpenFreeMap/MapLibre). Preferenze in `settings`.
28. **Senza rete** (`settings/Connectivity.kt`, `OfflineBanner` in `TransitScreens.kt`, `TransitViewModel.onReconnected`): fascia "Sei offline" in cima all'app finché la rete (con accesso verificato) non torna; al ritorno si ricaricano subito mezzi, avvisi, arrivi e linea aperta.
29. **Notifiche degli avvisi sui preferiti** (`watch/FavoriteAlertsWorker.kt`, `AppSettings.favoriteAlerts`, sezione in Impostazioni, `MainActivity.EXTRA_OPEN_ALERTS`): ogni 15 minuti si notificano gli avvisi **nuovi** attivi che citano linee o fermate preferite (al primo controllo quelli già in corso si registrano senza notifica; si tengono gli id ancora attivi); il tocco apre la scheda Avvisi. Interruttore e richiesta del permesso notifiche nelle Impostazioni.
30. **Ricerche recenti** (`favorites/RecentSearches.kt`, `RecentSearchesPanel` in `TransitScreens.kt`, `TransitViewModel.openRouteById`): linee e fermate aperte dai risultati della ricerca (max 8). Toccando la barra di ricerca vuota scende sotto di essa un pannello largo come la barra e con gli stessi angoli, che si apre e risale con una molla (gli angoli restano arrotondati durante l'animazione); si chiude scrivendo, toccando fuori, col gesto indietro o scegliendo una voce; tasto "Cancella".
31. **Finestra della direzione animata** (`ui/DirectionDialog.kt`: `DialogOrigin`, `clickableAsDialogOrigin`): nasce dalla riga toccata (ricerche recenti e risultati della ricerca) con una trasformazione senza deformazioni: la finestra ha già la sua dimensione e se ne mostra un rettangolo con angoli da 28 dp che parte dalla riga e si allarga (ritaglio animato del `graphicsLayer`), stesso colore del pannello delle recenti; contenuto in dissolvenza a forma quasi completa, sfondo scurito animato; chiudendola rientra nella riga. Il pannello delle recenti e la tastiera si chiudono solo a finestra aperta (`onShown`), altrimenti il ridimensionamento spostava tutto durante l'animazione. Aperta da altri punti cresce dal centro. Chiudendola, se era nata dalle recenti, prima riscende il pannello (senza tastiera, così la riga torna dov'era) e poi la finestra rientra nella riga; il pannello resta aperto (`recentsPinned`) finché non si tocca fuori o si torna indietro.
32. **Righe senza effetto al tocco** (`clickableAsDialogOrigin(ripple = false)`, `RecentSearchesPanel`, risultati della ricerca in Home): l'onda di Material si vedeva sotto la finestra della direzione che nasce dalla riga; tolta da linee e fermate del pannello delle recenti e dei risultati della ricerca.
33. **Tasti "←" animati** (`PredictiveBack.kt` `LocalAnimatedBack`, `LineScreen.kt`, `TimetableScreen.kt`, pagina fermata): nelle pagine dentro Home (linea, orari, fermata) il tasto indietro esegue la stessa animazione del gesto di Android: la pagina scorre verso destra rimpicciolendosi e scopre la precedente (prima il cambio era istantaneo).
34. **Apertura delle pagine animata** (`PredictiveBackLayer(depth)`, `StopsScreen`): aprendo linea, fermata o orari dentro Home la nuova pagina entra da destra verso sinistra sopra la precedente, che si sposta un poco a sinistra e si scurisce; stessa durata e curva del tasto "←" (320 ms). Fix: la nuova pagina compariva un istante al suo posto prima di scorrere (l'animazione partiva un fotogramma dopo); ora è considerata fuori schermo già dal primo fotogramma (`settledDepth`/`opening`).
35. **Caricamento dopo l'animazione di apertura** (`TransitViewModel.PAGE_OPEN_MS`): aprendo linea, fermata o orari i dati si caricano solo a fine animazione (320 ms, stessa costante dell'animazione in `PredictiveBackLayer`), così lo scorrimento non viene rallentato; aggiornamenti successivi, tasto aggiorna e ritorno della rete restano immediati.
36. **Micro-scatti e lampi nelle animazioni** (misurati fotogramma per fotogramma con `dumpsys gfxinfo framestats` sul S23, 120 Hz = 8,3 ms per fotogramma):
    - `PageStack` (`PredictiveBack.kt`) al posto di `PredictiveBackLayer` per le pagine della Home: ogni pagina è un elemento stabile della pila (chiave = pagina), quindi aprendo/chiudendo quella sotto non viene più distrutta e ricostruita (causa degli scatti a inizio apertura e a fine chiusura, e del lampo della pillola). Le pagine sotto hanno `LocalInBackPreview`/`LocalTabOnScreen` spenti.
    - Indicatore della pillola (`PillTabs`) dimensionato e posizionato in fase di layout: c'è già nel primo fotogramma (prima mancava finché non si leggeva la larghezza → flicker).
    - Animazioni di apertura pagina e finestra della direzione avviate dopo il fotogramma pesante di costruzione (`awaitFrames(2)`): il primo fotogramma (~50 ms) passa a pagina fuori schermo e lo scorrimento non salta. Finestra della direzione invisibile finché non è misurata (niente lampo al centro).
    - Riquadri Preferiti/Vicine: fondo e bordo con la forma ma senza ritaglio arrotondato del contenuto (il ritaglio di due superfici a tutto schermo pesava sulla GPU): Preferiti→Vicine da 38 a 5 fotogrammi lenti. Pannello delle recenti senza ombra (un'ombra grande che cambia altezza era costosa; coerente con la barra di ricerca).
    - `TabPager`: nei salti tra schede non vicine col tocco sulla barra (Home↔Avvisi) si vedono solo le due schede coinvolte; prima si attraversava la mappa, che si risvegliava (lampo, 21 fotogrammi lenti → 1–5).
    - Risultato: scorrimenti di pagine, schede, pillole e finestra entro il budget salvo pochi fotogrammi; a riposo 0 fotogrammi/s.
37. **Finestra della direzione larga come il pannello delle recenti** (`DirectionDialog.kt`): 16 dp dai bordi come barra di ricerca e pannello (prima 24 dp).
38. **Fix: pillola della Home che non rispondeva** (`TabPager`, `PageStack`): dopo un salto Home↔Avvisi la scheda Mappa, esclusa dal salto, restava trasparente ma sopra la Home e ne intercettava i tocchi (finché non si apriva la mappa). Ora le schede escluse e le pagine nascoste della pila stanno fuori schermo, non solo trasparenti.
39. **Riquadri Preferiti/Vicine arrotondati anche in fondo** (`TransitScreens.kt` `HomePanelGap`): angoli da 28 dp su tutti i lati (`CardCorner`) e fine 12 dp sopra il fondo dello schermo, la stessa distanza dalla barra di ricerca; l'elenco tiene il suo spazio finale per non finire sotto la barra flottante.
40. **Fix: pannello delle recenti aperto alla seconda finestra** (`TransitScreens.kt`): dopo aver chiuso una finestra della direzione il pannello resta aperto senza tastiera (`recentsPinned`); aprendo la seconda si toglieva solo il focus e il pannello restava. Ora a finestra aperta si chiude sempre (`closeRecents`).
41. **Chiudendo la tastiera si chiude il pannello delle recenti** (`TransitScreens.kt`, `WindowInsets.ime`): prima la tastiera si chiudeva ma il campo restava selezionato e il pannello aperto; non quando la tastiera si chiude perché si apre la finestra della direzione.
42. **Avviso offline come pillola** (`OfflinePill` in `FloatingTabBar.kt`): al posto della fascia rossa in cima, una pillola con l'aspetto della barra in basso (colore, ombra, margini), appoggiata sopra di essa con icona di avviso e "Sei offline · orari programmati"; entra e esce scorrendo, senza spostare il contenuto.
43. **Scheda "Percorso"** (branch `prova-navigazione`; `ui/RoutePlannerScreen.kt`, `Tabs`, `ScrollMemory.routeFrom/routeTo`): quarta scheda tra Mappa e Avvisi (Home · Mappa · Percorso · Avvisi) per la navigazione da A a B; per ora solo struttura (campi Da/A, scambio ⇅, riquadro risultati) nello stile della Home, con le animazioni delle schede esistenti. Indici delle schede ora per nome (`Tabs.HOME/MAP/ROUTE/ALERTS`). Punto di ripristino: tag `prima-navigazione` su `main`.
44. **Percorso: interfaccia in basso** (`RoutePlannerScreen.kt` `DestinationAndOrigin`, `ScrollMemory.routeFromOpen`): campo "Dove vuoi andare?" sopra la barra delle schede (sopra la tastiera quando è aperta) con accanto un tasto tondo; toccandolo il campo della partenza sale dal tasto e poi si apre in larghezza (due molle in sequenza, forma con angoli arrotondati), richiudendolo si stringe e rientra nel tasto e la partenza torna la posizione attuale. Sopra, il riquadro dei percorsi. Piano per il calcolo: RAPTOR sul telefono con corse del giorno, tratti a piedi tra fermate vicine (griglia, linea d'aria ×1,3, 4,5 km/h), ritardi live, arrivo da fermate/geocoder Android/mappa.
45. **Scheda "Navigazione"** (ex "Percorso"; `RoutePlannerScreen.kt` `StopNavigationPill`, `ScrollMemory.navigationActive`): margini e titolo come Avvisi (senza ricerca); in alto a sinistra la pillola "Termina navigazione" (alta 48 dp come gli slider, accenti rossi) visibile solo durante una navigazione, a destra; titolo a sinistra. In navigazione: elenco passo passo (strade, paline, bus, attese, durate) qui e percorso con bus live e paline di salita/discesa/cambio sulla Mappa (da fare).
46. **Barra in basso: "Nav"** (`Tabs.LABELS`) per la scheda Navigazione (il titolo della pagina resta "Navigazione"); le voci della pillola stanno su una riga e si rimpiccioliscono se non ci stanno (`BasicText` con `autoSize`).
47. **Grafo pedonale** (`tools/build_walk_graph.py`, osmium, estratto Geofabrik Centro Italia del 24/11/2026): 279.855 incroci, 363.938 archi con nome/tipo/forma, 8.320/8.321 fermate agganciate, 78.532 cambi a piedi reali entro 500 m; 26,7 MB (da ridurre).
48. **Tastiera chiusa = niente cursore** (`BusTrackApp`): in tutta l'app, chiudendo la tastiera il campo perde il focus e il cursore sparisce; ricompare solo toccando un campo (eccezione: apertura della finestra della direzione dalle recenti).
49. **Motore dei percorsi** (`routing/`: `WalkGraph`, `Raptor`/`Timetable`, `RoutingData`, `RoutePlanner`, `RoutingEngine`; `RaptorTest`; asset `walk.db` schema 2 con `stop_nodes`): RAPTOR sulle corse del giorno con cambi a piedi reali, tratti a piedi sul grafo pedonale (Dijkstra), alternativa tutta a piedi entro 2,5 km. Verificato sul telefono: Termini→San Pietro = Metro A fino a Ottaviano + 930 m a piedi; Ostiense→Colosseo = Metro B da Piramide; ricerca 40–220 ms (debug). Comando di prova solo debug: `DEBUG_PLAN` (`DebugPlanReceiver`).
50. **Scheda Nav completa** (`ui/NavigationModel.kt`, `RoutePlannerScreen.kt`, `routing/WalkInstructions.kt`, mappa in `VehicleMap.kt`/`VehiclesScreen`): suggerimenti mentre si scrive (fermate dal GTFS e indirizzi/luoghi dal geocoder di Android limitato a Roma; per la partenza anche "La tua posizione"); percorsi con orari, durata, mezzi e metri a piedi; toccandone uno parte la navigazione: elenco dei passi con indicazioni strada per strada (svolte dall'angolo tra i tratti, tratti senza nome descritti per tipo, pezzetti < 25 m assorbiti), attese, paline di salita/discesa con orari live (TripUpdates) e posizione del mezzo; sulla Mappa il percorso (a piedi tratteggiato, in mezzo pieno), paline Sali/Cambia/Scendi, partenza e arrivo, e i bus live delle linee coinvolte. Campi nascosti durante la navigazione; il motore si prepara all'apertura della scheda.
51. **Preferenze sul percorso** (scheda Nav, tasto in alto a destra come le impostazioni della Home; `PlanOptions`, salvate in `AppSettings`): meno tratti a piedi (fermate entro 600 m, cambi a piedi entro 250 m, tutto a piedi solo entro 1 km, minuti a piedi pesati il doppio), meno cambi (al massimo 3 mezzi, ogni cambio pesa 10 minuti invece di 2), niente metro (route_type 1), niente tram (route_type 0). RAPTOR salta i percorsi esclusi e i cambi a piedi troppo lunghi; i viaggi si filtrano per arrivo/mezzi/metri a piedi e si ordinano per costo. Cambiando una preferenza si ricalcola. Sulla mappa "Termina" ha lo stesso aspetto della pillola "Termina navigazione".
52. **Preferenze: stessa animazione del campo della partenza** (`RouteOptionsPanel`): il riquadro nasce dal tasto tondo, scende sotto di lui e poi si apre in larghezza e altezza; chiudendo fa il contrario. Il tasto diventa una X mentre è aperto; tocco fuori o gesto indietro chiudono.
53. **Titolo della scheda Nav**: durante una navigazione diventa "Nav" (accanto a "Termina navigazione" e al tasto delle preferenze).
54. **Mappa della navigazione: colori e scritte** (`toMapData`, `addNavLayers`): tratti in mezzo colorati per linea: metro A arancione (#F7941D), B/B1 blu (#0067B1), C verde scuro (#00843D); altre linee verdi se la corsa è seguita dal vivo (mezzo nel feed o previsioni TripUpdates, aggiornate anche dalla scheda Mappa), grigie se no; paline dello stesso colore. Scritte senza sovrapposizioni (posizione variabile attorno al pallino, nascoste se non c'è posto, priorità a salite e cambi); discesa e salita successiva entro 60 m unite in un solo punto "Cambia".
55. **Ritardi live nel calcolo del percorso** (`Timetable.adjusted`, `LiveInfo`, `RoutePlanner.adjustedTimetable`): prima di ogni calcolo si scaricano le previsioni TripUpdates (insieme alla preparazione degli orari, massimo 8 s); ogni corsa tracciata parte spostata del ritardo della sua prossima fermata e le corse di ogni percorso si riordinano (un bus in ritardo può essere superato dal successivo). I risultati dicono se gli orari includono i ritardi o sono solo programmati. Test `liveDelaysReorderTripsAndPhantomsAreDropped`.
56. **Corse fantasma escluse dal calcolo**: stessa regola degli arrivi (`dropUntrackedSchedules`): per le linee con corse nel feed si tengono solo le corse dei gruppi di servizio in servizio, nella finestra da 3 ore prima a 2 ore dopo la partenza.
57. **Nomi degli indirizzi puliti** (`NavigationModel.placeOf`): titolo = nome del luogo oppure via e civico; dettaglio = via (se il titolo è un luogo), quartiere e città, senza CAP, "Città Metropolitana di Roma Capitale", regione e nazione; scartati i risultati che sono solo una città o una zona.
58. **Preferenze anche a navigazione avviata**: cambiandole si chiude la navigazione e si torna all'elenco dei percorsi, ricalcolato con le nuove preferenze.
59. **Ricalcolo durante il viaggio** (`NavigationModel.track`/`onLocation`/`reroute`, `NavTracking.kt`): con una navigazione in corso e l'app in primo piano si segue la posizione (ogni ~3 s, posizioni con precisione oltre 80 m ignorate). Il tratto in corso è il più vicino tra quello attuale e i due successivi (proiezione sulla sua spezzata); nell'elenco dei passi è evidenziato, quelli fatti sono attenuati, l'elenco ci scorre e a piedi si vedono i metri che mancano. Ricalcolo automatico dalla posizione (al massimo ogni 45 s) se si è fuori dal percorso a piedi per due posizioni di fila (oltre 50 m o 1,5 volte la precisione) o se il mezzo da prendere è partito da più di 90 s; in cima compare "Percorso ricalcolato alle … : motivo". Test `NavTrackingTest`.
60. **Ricerche recenti della navigazione** (`NavRecents`, `NavRecentsPanel`): ultimi 8 luoghi scelti (arrivi e partenze); con un campo selezionato e vuoto il pannello sale dal campo, come quello che scende dalla barra della Home, con "Cancella".
61. **Naviga fino a una fermata** (pagina della fermata, tasto accanto a stella e aggiorna; `LocalNavigateTo`, `NavigationModel.navigateTo`): apre la scheda Nav con la fermata come arrivo e i percorsi già calcolati (chiude un'eventuale navigazione in corso).
62. **Tasti tondi uniformi** (`RoundButton`): stella dei preferiti, aggiorna (linea, fermata, mezzi, avvisi), mappa della linea e orari di partenza ora hanno lo stesso aspetto dei tasti impostazioni e preferenze (48 dp, colore e ombra della barra), con 8 dp tra uno e l'altro; l'aggiorna dentro l'elenco delle fermate vicine resta semplice.
