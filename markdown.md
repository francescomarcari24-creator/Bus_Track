# BusTrack — appunti di progetto

App Android per il trasporto pubblico di **Roma**: cerca linee e fermate, mostra arrivi e mezzi in tempo reale, orari programmati, avvisi.
Dati: **GTFS statico + GTFS-Realtime di Roma Mobilità**.

> **Regola:** ogni modifica al progetto va aggiunta a questo file (sezione *Registro modifiche*, e le altre sezioni se cambiano).

Ultimo aggiornamento: 2026-11-23

---

## 1. Come riprendere in 2 minuti

| Cosa | Comando / dove |
|---|---|
| Build debug | `./gradlew :app:assembleDebug` |
| Test unitari (JVM) | `./gradlew :app:testDebugUnitTest` |
| Installa sul telefono | `adb -s RFCTC0EWJAD install -r app/build/outputs/apk/debug/app-debug.apk` |
| Avvia l'app | `adb -s RFCTC0EWJAD shell am start -n com.example.bustrack/.MainActivity` |
| Log dei download dei feed | `adb logcat -s BusTrack` |
| Rigenera il database statico | `python tools/build_gtfs_db.py <rome_static_gtfs.zip> app/src/main/assets/gtfs.db` |

- **Le prove si fanno sul telefono collegato** (Samsung Galaxy S23, `SM-S911B`, seriale adb `RFCTC0EWJAD`, 1080×2340, tema scuro, colori dinamici), **non sull'emulatore**.
- Per pilotare la UI via adb: toccare gli elementi cercando testo / `content-desc` con `uiautomator dump`, non a coordinate fisse. Per scrivere nella ricerca toccare `class="android.widget.EditText"`. **Chiudere la tastiera Samsung prima di scorrere** (`input keyevent 4` se `mInputShown=true`; ESC non la chiude).
- Git: branch `main` (rinominato da `master` il 2026-11-23), primo commit il 2026-11-23 (autore "Francesco Marcari", identità Git impostata globalmente). Fare commit e push solo se richiesto.
- GitHub: `origin` = https://github.com/francescomarcari24-creator/Bus_Track (push via HTTPS con Git Credential Manager; la GitHub CLI `gh` non è installata).

## 2. Ambiente

- Android Studio, Gradle wrapper, AGP 9.5.2, Kotlin 2.4.21, Compose BOM 2026.11.01
- `compileSdk`/`targetSdk` 37, `minSdk` 34, Java 11
- Package: `com.example.bustrack`
- Dipendenze aggiunte:
  - `com.google.transit:gtfs-realtime-bindings:0.0.4` (l'ultima su Maven Central; usa protobuf-java 2.6.1)
  - `org.maplibre.gl:android-sdk:13.7.1` (mappa)
  - `androidx.lifecycle:lifecycle-runtime-compose` e `lifecycle-viewmodel-compose`
- Permesso: `INTERNET`
- Python 3 (solo stdlib) per lo script del database.

## 3. Fonti dati

### GTFS statico
- File originale: `rome_static_gtfs.zip` (~47 MB zip, ~275 MB scompattato; `stop_times.txt` da solo 242 MB / 5,15 M righe).
- Copia usata: `C:\Users\franc\Downloads\rome_static_gtfs.zip` (MD5 `302eb7054c385bca0be67845b079d555`).
- Validità del feed attuale: **21/10/2026 → 28/12/2026** → dopo va scaricato quello nuovo e rigenerato il database.
- L'URL `…/sites/default/files/rome_static_gtfs.zip.md5` risponde 403: lo zip va scaricato a mano.

### GTFS-Realtime (live, aggiornati ~ogni 30 s)
Base: `https://romamobilita.it/wp-content/uploads/shared/` (i vecchi URL `/sites/default/files/` fanno redirect 301 qui)
- `rome_rtgtfs_trip_updates_feed.pb` → arrivi previsti
- `rome_rtgtfs_vehicle_positions_feed.pb` → posizioni mezzi
- `rome_rtgtfs_service_alerts_feed.pb` → avvisi

Gli ID di corse (`trip_id`, es. `0#575-13`) e fermate (`stop_id`) del live coincidono con quelli dello statico.

### Mappa
- Stile vettoriale **OpenFreeMap** `https://tiles.openfreemap.org/styles/liberty` (gratis, senza API key, dati OSM).
- Provati e scartati: tile di openstreetmap.org (403, policy contro le app) e CARTO (ora richiede API key).

## 4. Database statico `app/src/main/assets/gtfs.db`

Generato da `tools/build_gtfs_db.py`. ~22 MB su disco, ~6 MB compressi nell'APK. Schema **versione 3**.
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
  HtmlEntities.kt            → decodifica entità HTML nei testi degli avvisi (&#039; → ')
  TransitRepository.kt       → scarica i feed (HttpURLConnection), unisce live + programmato
favorites/Favorites.kt       → preferiti su SharedPreferences ("favorites"), ordine di aggiunta
ui/
  TransitViewModel.kt        → stato di tutte le schermate, polling, aggiornamenti manuali
  TransitScreens.kt          → BusTrackApp, Home (ricerca+preferiti), dettaglio fermata,
                               scheda Mezzi/Mappa, Avvisi, componenti comuni
  LineScreen.kt              → popup direzione + elenco fermate della linea con bus live
  TimetableScreen.kt         → orari di partenza dal capolinea (griglia per ora)
  VehicleMap.kt              → mappa MapLibre con i mezzi
  FloatingTabBar.kt          → barra in basso flottante (Home / Mappa / Avvisi)
  PredictiveBack.kt          → gesto indietro predittivo con anteprima
  ScrollMemory.kt            → conserva scroll liste, inquadratura mappa, vista/filtro Mezzi
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
- **Mappa** (scheda Mezzi): mappa o lista di tutti i mezzi live, filtro per linea, tocca un mezzo per i dettagli. Aggiornamento automatico **60 s su mappa, 30 s su lista** (orologio monotono).
- **Avvisi**: avvisi attivi con linee coinvolte, espandibili.
- **Verde = live**: tutto ciò che viene dal tracciamento live è verde (badge, minuti, punti mappa, righe evidenziate); il programmato usa i colori normali.
- **Aggiorna** (↻) in alto a destra su fermata, linea, mappa, avvisi; rotellina durante il caricamento, i dati vecchi restano visibili.
- **Gesto indietro predittivo** con anteprima della schermata precedente; **lo scroll e l'inquadratura mappa si conservano** tornando indietro o cambiando scheda (non tra un riavvio e l'altro).
- **Barra in basso** flottante a pillola, 48 dp, solo testo: Home / Mappa / Avvisi. Le liste finiscono sopra la barra, la mappa le passa sotto.

## 7. Particolarità e problemi noti dei dati di Roma

- **Versioni d'orario sovrapposte**: il feed contiene `0#…` (fino al 22/11) e `1#…` (dal 23/11); di notte le stesse corse compaiono due volte → deduplica per linea + destinazione + orario.
- **Linea 8 (settimana 23–27/11)**: due servizi attivi insieme (`1#35` regolare e `1#57` con anche "8BUS"), stesse corse sfalsate di 1 minuto. Mostrati entrambi; da verificare di giorno confrontando coi tram live.
- **Tram 8** risulta con 16 fermate nel percorso più frequente (probabile percorso ridotto per lavori).
- **Ritardi/anticipi notturni**: molti bus notturni risultano "in anticipo 6–7 min"; è il valore del feed.
- **Avvisi**: testi con entità HTML (`&#039;`) → decodificate in app.
- 147 linee hanno una sola direzione nei dati (138 con un solo capolinea: circolari/a senso unico).
- Elenco fermate di una linea = solo il percorso più frequente; le varianti non sono elencate (i loro bus sono contati a parte). Orari dal capolinea = solo corse che partono dal primo stop del percorso tipico.

## 8. Test

- `GtfsRealtimeParserTest` — parsing dei 3 feed su snapshot reali in `app/src/test/resources/gtfs-rt/` (nessuna entità HTML negli avvisi).
- `FavoriteTest` — codifica/decodifica dei preferiti.
- `HtmlEntitiesTest` — decodifica entità.
- `GtfsStaticDb` usa SQLite Android: verificato sul dispositivo e con query Python sul `.db`, non con test JVM.

## 9. Idee / da fare

- Verificare di giorno quale dei due orari della linea 8 è reale e, se serve, filtrare l'altro.
- Scaricare automaticamente il GTFS statico aggiornato (oggi è manuale).
- Mostrare il tracciato (`shapes.txt`) di una linea sulla mappa.
- Sostituire `ListItem` deprecato (nuova API Compose) per eliminare i warning.

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
