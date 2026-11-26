# Bus Track

App Android per muoversi a **Roma** con i mezzi pubblici: arrivi in tempo reale alle fermate, posizione dei bus sulla mappa, avvisi di servizio e un **navigatore da un punto A a un punto B** che calcola i percorsi direttamente sul telefono, con le indicazioni a piedi strada per strada.

I dati sono quelli aperti di **Roma Mobilità** (orari GTFS e feed in tempo reale GTFS-Realtime); mappe e rete pedonale vengono da **OpenStreetMap**.

> Progetto personale, non affiliato a Roma Mobilità, ATAC o altri gestori del trasporto pubblico.

---

## Cosa fa

### Home
- **Ricerca** di linee, fermate e numeri di palina, con le **ricerche recenti**.
- **Preferiti**: linee (in una direzione) e fermate, riordinabili tenendo premuto e trascinando.
- **Fermate vicine** alla posizione attuale, con le linee che vi passano.
- Tasti **Casa** e **Lavoro**: avviano la navigazione verso quel luogo con un tocco (si nasconde quello vicino a cui ti trovi).

### Fermata
- **Prossimi arrivi**: previsioni live (in verde) dove disponibili, altrimenti orari programmati.
- Le corse "fantasma" (in orario ma non effettuate) vengono scartate confrontando l'orario con le corse realmente in servizio.
- **Avviso di arrivo**: notifica quando il bus scelto è a pochi minuti, anche ad app chiusa.
- **Naviga fino a qui**, aggiungi ai preferiti, aggiorna.

### Linea
- Elenco delle fermate con il tracciato e la **posizione dei mezzi** lungo il percorso.
- **Orari di partenza** dal capolinea e **tracciato sulla mappa**.

### Mappa
- Tutti i **bus in tempo reale**, con la freccia verso il capolinea, oppure le **fermate** (raggruppate a bassa scala).
- Filtro per linea e direzione, posizione dell'utente.
- **Tenendo premuto un punto** si può scegliere come meta della navigazione.

### Navigazione da A a B
- Partenza dalla posizione attuale o da un luogo scelto; arrivo cercando una fermata, un indirizzo o un luogo, oppure scegliendolo sulla mappa.
- Percorsi calcolati **sul telefono** con l'algoritmo **RAPTOR**, usando gli orari del giorno **corretti con i ritardi in tempo reale**.
- **Tratti a piedi sulla rete pedonale reale** (OpenStreetMap), con **indicazioni strada per strada** ("Gira a destra in Via Milano e prosegui per 120 m").
- **Preferenze**: meno tratti a piedi, meno cambi, evita la metro, evita il tram, evita le scale.
- **Avvisi di servizio** sulle linee e paline del percorso.
- Durante il viaggio:
  - elenco dei passi con attese, orari live e posizione del mezzo;
  - sulla mappa il percorso colorato per linea (metro A/B/C nei loro colori, verde se il mezzo è tracciato dal vivo, grigio se no);
  - **notifica fissa** con il passo attuale, espandibile con i passi successivi o le fermate fino alla discesa;
  - **avvisi con vibrazione**: "Esci ora" per non perdere il mezzo e "Scendi alla prossima";
  - **ricalcolo automatico** se si esce dal percorso o si perde il mezzo;
  - la navigazione **riprende** se il sistema chiude l'app.

### Altro
- **Avvisi** di servizio attivi, con ricerca per linea; notifiche dei nuovi avvisi su linee e fermate preferite.
- **Widget** "prossimi passaggi" di una fermata per la schermata principale.
- **Scorciatoie dall'icona** dell'app: "Vai a casa" e "Vai a lavoro".
- **Funziona anche senza rete** per orari, fermate e calcolo dei percorsi (solo i dati live e la ricerca degli indirizzi richiedono la connessione).
- **Aggiornamento automatico degli orari** quando Roma Mobilità ne pubblica di nuovi (anche solo in Wi-Fi).
- Tema chiaro e scuro con i colori dinamici di Android.

---

## Scaricare l'app

L'app non è sul Play Store: si installa scaricando il pacchetto (APK) dalle **Release** di GitHub.

1. Dal telefono apri **[l'ultima versione](https://github.com/francescomarcari24-creator/Bus_Track/releases/latest)** e scarica il file `BusTrack-x.y.z.apk` (sezione *Assets*).
2. Apri il file scaricato. La prima volta Android chiede di **consentire l'installazione da questa fonte** (il browser o l'app File): consentila e torna indietro.
3. Tocca **Installa**.

Per **aggiornare**, scarica e installa la versione nuova allo stesso modo: si installa sopra quella vecchia e preferiti e impostazioni restano.

Serve Android **14** o successivo su un telefono con processore **arm64** (praticamente tutti quelli recenti). Ogni versione è firmata con la stessa chiave: Android rifiuta un aggiornamento che non provenga da qui.

---

## Come funziona

| Parte | Come |
|---|---|
| Orari e fermate | GTFS statico di Roma Mobilità, trasformato in un database SQLite compatto (`app/src/main/assets/gtfs.db`) e ricostruito sul telefono quando escono nuovi orari |
| Tempo reale | Feed GTFS-Realtime di Roma Mobilità (posizioni dei mezzi, previsioni, avvisi), scaricati appena la centrale li pubblica (~ogni 30 s) |
| Calcolo dei percorsi | RAPTOR (Round-bAsed Public Transit Optimized Router) sulle corse del giorno, con cambi a piedi reali tra fermate; orari corretti con i ritardi live |
| Rete pedonale | Grafo delle strade percorribili a piedi di Roma da OpenStreetMap (~280 000 incroci, ~364 000 tratti) in un file binario compatto (`walk.bin`), letto in memoria |
| Mappa | MapLibre con gli stili vettoriali di OpenFreeMap (dati OpenStreetMap) |
| Indirizzi | Geocoder di Android, limitato all'area di Roma |

## Tecnologie

- **Kotlin** e **Jetpack Compose** (Material 3)
- **MapLibre Android** per la mappa
- **Glance** per il widget, **WorkManager** per gli aggiornamenti in background
- Servizi in primo piano per la navigazione e l'avviso di arrivo
- Nessun server e nessun account: tutto gira sul telefono, i dati live arrivano direttamente da Roma Mobilità

## Requisiti

- Android **14** o successivo, processore **arm64** (praticamente tutti i telefoni recenti)
- Permessi: posizione (fermate vicine, navigazione), notifiche (avvisi e navigazione)

## Compilare e installare

Serve **Android Studio** (o JDK 17+ e Android SDK con API 37).

```bash
# APK di prova
./gradlew :app:assembleDebug

# APK ottimizzata (più fluida)
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk

# Installazione su un telefono collegato
adb install -r app/build/outputs/apk/release/app-release.apk

# Test
./gradlew :app:testDebugUnitTest
```

La versione release è firmata con la chiave di debug, così si può installare sopra quella di prova; per una pubblicazione va sostituita con una chiave di rilascio.

## Rigenerare i dati

Gli strumenti in `tools/` (Python 3) ricostruiscono i dati inclusi nell'app:

```bash
# Orari e fermate dal GTFS statico di Roma Mobilità
python tools/build_gtfs_db.py <rome_static_gtfs.zip> app/src/main/assets/gtfs.db

# Rete pedonale da un estratto OpenStreetMap (serve: pip install osmium)
python tools/build_walk_graph.py <estratto.osm.pbf> app/src/main/assets/gtfs.db <tmp>/walk.db
python tools/walk_db_to_bin.py <tmp>/walk.db app/src/main/assets/walk.bin
```

L'estratto OpenStreetMap dell'Italia centrale si scarica da [Geofabrik](https://download.geofabrik.de/europe/italy/centro.html).

## Struttura del progetto

```
app/src/main/java/com/example/bustrack/
├── gtfs/          dati statici e in tempo reale (database, feed, modelli)
├── routing/       motore dei percorsi: RAPTOR, grafo pedonale, indicazioni a piedi
├── navigation/    servizio della navigazione, notifica, scorciatoie
├── ui/            schermate Compose (Home, Mappa, Nav, Avvisi, linea, fermata)
├── widget/        widget "prossimi passaggi"
├── watch/         avviso di arrivo e notifiche degli avvisi
├── favorites/     preferiti e ricerche recenti
└── settings/      impostazioni
tools/             script Python per rigenerare orari e rete pedonale
```

## Privacy

L'app non ha account, server o statistiche d'uso. La posizione si usa solo sul telefono (fermate vicine, navigazione) e non viene inviata a nessuno. Le uniche connessioni sono verso i feed di Roma Mobilità, le mappe di OpenFreeMap e il geocoder di Android per cercare gli indirizzi.

## Dati e attribuzioni

- Orari, fermate, tempo reale e avvisi: **Roma Mobilità**, dati aperti ([romamobilita.it](https://romamobilita.it/)), secondo le loro condizioni d'uso.
- Mappe e rete pedonale: © **i contributori di OpenStreetMap**, dati disponibili con licenza [ODbL](https://www.openstreetmap.org/copyright).
- Stili e tile della mappa: [OpenFreeMap](https://openfreemap.org/).
- Icone: Material Icons di Google (licenza Apache 2.0).

## Licenza

Il codice dell'app è distribuito con licenza **MIT**: vedi il file [LICENSE](LICENSE). I dati inclusi (orari di Roma Mobilità, rete pedonale di OpenStreetMap) restano soggetti alle rispettive condizioni, indicate sopra.
