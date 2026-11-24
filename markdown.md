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
- File originale: `rome_static_gtfs.zip` (~47 MB zip, ~275 MB scompattato; `stop_times.txt` da solo 242 MB / 5,15 M righe).
- Copia usata: `C:\Users\franc\Downloads\rome_static_gtfs.zip` (MD5 `302eb7054c385bca0be67845b079d555`).
- Validità del feed attuale: **21/10/2026 → 28/12/2026** → dopo va scaricato quello nuovo e rigenerato il database.
- L'URL `…/sites/default/files/rome_static_gtfs.zip.md5` risponde 403: lo zip va scaricato a mano.

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

Generato da `tools/build_gtfs_db.py`. ~22 MB su disco, ~6 MB compressi nell'APK. Schema **versione 4**.
Al primo avvio (e dopo ogni aggiornamento dell'app) viene copiato in `noBackupFilesDir` e aperto in sola lettura.

| Tabella | Contenuto |
|---|---|
| `meta` | `schema_version`, `feed_start_date`, `feed_end_date`, `built_at` |
| `routes` | linee (`route_id`, nome breve/lungo, tipo, colori, agenzia) |
| `stops` | fermate (`stop_id`, codice palina, nome, lat/lon) |
| `trips` | corse: linea, capolinea (`headsign`), direzione, `service_id`, `profile_id`, `start_sec` |
| `profile_stops` | "profili" orari: sequenza fermate + offset in secondi dalla partenza |
| `calendar_dates` | giorni in cui ogni `service_id` è attivo (solo `exception_type = 1`) |
| `stop_routes` | quali linee passano da ogni fermata |
| `route_patterns` | percorso **più frequente** per linea+direzione, con capolinea e lista fermate |
| `route_shapes` | **tracciato** (da `shapes.txt`) per linea+direzione: quello usato più spesso dalle corse del percorso tipico, semplificato (Douglas-Peucker 3 m: 360k → 126k punti) e salvato come *encoded polyline* di Google (precisione 1 m) |

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
- **Linea 8 (settimana 23–27/11)**: due servizi attivi insieme (`1#35` regolare e `1#57` con anche "8BUS"), stesse corse sfalsate di 1 minuto. Mostrati entrambi; da verificare di giorno confrontando coi tram live.
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

- Verificare di giorno quale dei due orari della linea 8 è reale e, se serve, filtrare l'altro.
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
