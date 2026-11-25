package com.example.bustrack.gtfs.update

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.util.Log
import com.example.bustrack.gtfs.GtfsStaticDb
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Aggiornamento automatico del GTFS statico: Roma Mobilità ripubblica spesso lo zip (validità di
 * poche settimane). Si confronta l'MD5 pubblicato con quello del database in uso; se è cambiato si
 * scarica lo zip, se ne verifica l'MD5, si costruisce il nuovo database sul telefono
 * ([GtfsDbBuilder]), lo si controlla e lo si installa al posto del vecchio.
 */
class GtfsUpdater(private val context: Context) {

    sealed interface Result {
        /** Il database in uso è già l'ultimo pubblicato. */
        data object UpToDate : Result
        /** C'è un GTFS nuovo ma la rete è a consumo e i dati attuali non scadono a breve: si aspetta il Wi-Fi. */
        data object WaitingForWifi : Result
        data class Updated(val feedEndDate: String) : Result
    }

    /** [force] = ricostruisce anche se l'MD5 non è cambiato (prove). */
    fun run(force: Boolean = false): Result {
        val remoteMd5 = fetchText(MD5_URL).trim().substringBefore(' ').lowercase()
        require(remoteMd5.matches(Regex("[0-9a-f]{32}"))) { "MD5 pubblicato non valido: $remoteMd5" }
        val current = GtfsStaticDb.currentInfo(context)
        Log.i(TAG, "GTFS: pubblicato $remoteMd5, in uso ${current?.sourceMd5} (valido fino al ${current?.feedEndDate})")
        if (!force && current?.sourceMd5 == remoteMd5) return Result.UpToDate
        val wifiOnly = com.example.bustrack.settings.AppSettings.get(context).gtfsWifiOnly.value
        if (!force && wifiOnly && isMetered() && !expiresSoon(current?.feedEndDate)) return Result.WaitingForWifi

        val dir = File(context.noBackupFilesDir, "gtfs-update").apply { mkdirs() }
        val zip = File(dir, "rome_static_gtfs.zip")
        val built = File(dir, "gtfs-new.db")
        try {
            download(ZIP_URL, zip)
            val zipMd5 = md5(zip)
            if (zipMd5 != remoteMd5) throw IOException("MD5 dello zip $zipMd5 diverso da quello pubblicato $remoteMd5")
            GtfsDbBuilder(zip, zipMd5).apply { onProgress = { Log.i(TAG, "GTFS: $it") } }.build(built)
            val info = validate(built)
            GtfsStaticDb.installDownloaded(context, built)
            Log.i(TAG, "GTFS aggiornato: valido fino al ${info.feedEndDate}")
            return Result.Updated(info.feedEndDate)
        } finally {
            zip.delete()
            built.delete()
        }
    }

    /** Controlli prima di installare: schema giusto, dati presenti, non già scaduto. */
    private fun validate(file: File): GtfsStaticDb.Companion.DbInfo {
        val info = GtfsStaticDb.readInfo(file) ?: throw IOException("Database costruito illeggibile")
        check(info.schemaVersion == GtfsDbBuilder.SCHEMA_VERSION) { "Schema ${info.schemaVersion} inatteso" }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
            check(count("stops") > 1000) { "Troppe poche fermate" }
            check(count("routes") > 50) { "Troppe poche linee" }
            check(count("trips") > 10_000) { "Troppe poche corse" }
            check(count("route_patterns") > 50) { "Troppi pochi percorsi" }
        }
        check(!expired(info.feedEndDate)) { "Il GTFS pubblicato è già scaduto (${info.feedEndDate})" }
        return info
    }

    private fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    private fun fetchText(url: String): String = connect(url).use { it.inputStream.bufferedReader().readText() }

    private fun download(url: String, out: File) {
        connect(url).use { conn ->
            val tmp = File(out.path + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            val expected = conn.contentLengthLong
            if (expected > 0 && tmp.length() != expected) throw IOException("Download incompleto: ${tmp.length()} di $expected byte")
            if (!tmp.renameTo(out)) throw IOException("Impossibile salvare lo zip")
        }
    }

    private class Conn(val conn: HttpURLConnection) : AutoCloseable {
        val inputStream get() = conn.inputStream
        val contentLengthLong get() = conn.contentLengthLong
        override fun close() = conn.disconnect()
    }

    private fun connect(url: String): Conn {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "BusTrack")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("HTTP $code da $url")
        }
        return Conn(conn)
    }

    companion object {
        private const val TAG = "BusTrack"
        private const val BASE = "https://romamobilita.it/wp-content/uploads/shared/"
        const val ZIP_URL = BASE + "rome_static_gtfs.zip"
        const val MD5_URL = BASE + "rome_static_gtfs.zip.md5"
        private val ROME: ZoneId = ZoneId.of("Europe/Rome")
        private val YMD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        private fun endDate(yyyymmdd: String?): LocalDate? =
            yyyymmdd?.takeIf { it.length == 8 }?.let { runCatching { LocalDate.parse(it, YMD) }.getOrNull() }

        /** Scade entro 3 giorni (o data sconosciuta): si aggiorna anche su rete a consumo. */
        fun expiresSoon(yyyymmdd: String?): Boolean {
            val end = endDate(yyyymmdd) ?: return true
            return !end.isAfter(LocalDate.now(ROME).plusDays(3))
        }

        fun expired(yyyymmdd: String?): Boolean {
            val end = endDate(yyyymmdd) ?: return false
            return end.isBefore(LocalDate.now(ROME))
        }

        fun md5(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
