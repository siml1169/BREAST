package de.breast.telemetry

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface TelemetryStore {
    suspend fun recordSnapshot(snapshot: HeatPumpTelemetrySnapshot)
    suspend fun getRecentSnapshots(limit: Int = 25000): List<HeatPumpTelemetrySnapshot>
    suspend fun getSnapshotsSince(epochSeconds: Long): List<HeatPumpTelemetrySnapshot>
    suspend fun getSnapshotsForDate(dateStr: String): List<HeatPumpTelemetrySnapshot>
    suspend fun getSnapshotCount(): Int
    suspend fun clearAll()
    suspend fun purgeSnapshotsWithSetpointAbove(thresholdC: Double): Int
    suspend fun exportAsCsv(): String
    suspend fun exportAsJson(): String
    suspend fun computeWindowAverages(limit: Int = 25000): TelemetryWindowAverage?
}

data class TelemetryWindowAverage(
    val sampleCount: Int,
    val avgOutdoorTempC: Double,
    val avgSupplyTempC: Double,
    val avgReturnTempC: Double,
    val avgDeltaTC: Double,
    val avgModulationPct: Double,
    val calculatedEpochSeconds: Long = System.currentTimeMillis() / 1000L
)

class SqliteTelemetryStore(
    context: Context,
    dbName: String = DB_NAME
) : SQLiteOpenHelper(context, dbName, null, DB_VERSION), TelemetryStore {

    companion object {
        const val DB_NAME = "breast_telemetry.db"
        const val DB_VERSION = 1

        const val TABLE_SNAPSHOTS = "telemetry_snapshots"
        const val COL_ID = "id"
        const val COL_TIMESTAMP_ISO = "timestamp_iso"
        const val COL_EPOCH_SECONDS = "epoch_seconds"
        const val COL_OUTDOOR_TEMP = "outdoor_temp"
        const val COL_SUPPLY_TEMP = "supply_temp"
        const val COL_RETURN_TEMP = "return_temp"
        const val COL_CONDENSER_TEMP = "condenser_temp"
        const val COL_SETPOINT_TEMP = "setpoint_temp"
        const val COL_MODULATION_PCT = "modulation_pct"
        const val COL_DEMAND = "demand"
        const val COL_CH_STATUS = "ch_status"
        const val COL_SPOT_GROSS_CT = "spot_gross_ct"
        const val COL_SPOT_NET_CT = "spot_net_ct"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SNAPSHOTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP_ISO TEXT NOT NULL,
                $COL_EPOCH_SECONDS INTEGER NOT NULL,
                $COL_OUTDOOR_TEMP REAL,
                $COL_SUPPLY_TEMP REAL,
                $COL_RETURN_TEMP REAL,
                $COL_CONDENSER_TEMP REAL,
                $COL_SETPOINT_TEMP REAL,
                $COL_MODULATION_PCT REAL,
                $COL_DEMAND TEXT,
                $COL_CH_STATUS TEXT,
                $COL_SPOT_GROSS_CT REAL,
                $COL_SPOT_NET_CT REAL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_telemetry_epoch ON $TABLE_SNAPSHOTS ($COL_EPOCH_SECONDS)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migration logic for future schema updates
    }

    override suspend fun recordSnapshot(snapshot: HeatPumpTelemetrySnapshot) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(COL_TIMESTAMP_ISO, snapshot.timestampIso)
            put(COL_EPOCH_SECONDS, snapshot.epochSeconds)
            snapshot.outdoorTempC?.let { put(COL_OUTDOOR_TEMP, it) } ?: putNull(COL_OUTDOOR_TEMP)
            snapshot.supplyTempC?.let { put(COL_SUPPLY_TEMP, it) } ?: putNull(COL_SUPPLY_TEMP)
            snapshot.returnTempC?.let { put(COL_RETURN_TEMP, it) } ?: putNull(COL_RETURN_TEMP)
            snapshot.condenserTempC?.let { put(COL_CONDENSER_TEMP, it) } ?: putNull(COL_CONDENSER_TEMP)
            snapshot.setpointTempC?.let { put(COL_SETPOINT_TEMP, it) } ?: putNull(COL_SETPOINT_TEMP)
            snapshot.modulationPct?.let { put(COL_MODULATION_PCT, it) } ?: putNull(COL_MODULATION_PCT)
            snapshot.demand?.let { put(COL_DEMAND, it) } ?: putNull(COL_DEMAND)
            snapshot.chStatus?.let { put(COL_CH_STATUS, it) } ?: putNull(COL_CH_STATUS)
            snapshot.spotPriceGrossCt?.let { put(COL_SPOT_GROSS_CT, it) } ?: putNull(COL_SPOT_GROSS_CT)
            snapshot.spotPriceNetCt?.let { put(COL_SPOT_NET_CT, it) } ?: putNull(COL_SPOT_NET_CT)
        }
        writableDatabase.insert(TABLE_SNAPSHOTS, null, values)
        Unit
    }

    override suspend fun getRecentSnapshots(limit: Int): List<HeatPumpTelemetrySnapshot> = withContext(Dispatchers.IO) {
        val list = mutableListOf<HeatPumpTelemetrySnapshot>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_SNAPSHOTS ORDER BY $COL_EPOCH_SECONDS DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToSnapshot(cursor))
            }
        }
        list.reversed() // return chronological order
    }

    override suspend fun getSnapshotsSince(epochSeconds: Long): List<HeatPumpTelemetrySnapshot> = withContext(Dispatchers.IO) {
        val list = mutableListOf<HeatPumpTelemetrySnapshot>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_SNAPSHOTS WHERE $COL_EPOCH_SECONDS >= ? ORDER BY $COL_EPOCH_SECONDS ASC",
            arrayOf(epochSeconds.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToSnapshot(cursor))
            }
        }
        list
    }

    override suspend fun getSnapshotsForDate(dateStr: String): List<HeatPumpTelemetrySnapshot> = withContext(Dispatchers.IO) {
        val list = mutableListOf<HeatPumpTelemetrySnapshot>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_SNAPSHOTS WHERE $COL_TIMESTAMP_ISO LIKE ? ORDER BY $COL_EPOCH_SECONDS ASC",
            arrayOf("$dateStr%")
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToSnapshot(cursor))
            }
        }
        list
    }

    override suspend fun getSnapshotCount(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_SNAPSHOTS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    override suspend fun computeWindowAverages(limit: Int): TelemetryWindowAverage? = withContext(Dispatchers.IO) {
        val query = """
            SELECT 
                COUNT(*), 
                AVG($COL_OUTDOOR_TEMP), 
                AVG($COL_SUPPLY_TEMP), 
                AVG($COL_RETURN_TEMP), 
                AVG(CASE WHEN $COL_SUPPLY_TEMP IS NOT NULL AND $COL_RETURN_TEMP IS NOT NULL THEN ($COL_SUPPLY_TEMP - $COL_RETURN_TEMP) ELSE NULL END),
                AVG($COL_MODULATION_PCT)
            FROM (
                SELECT * FROM $TABLE_SNAPSHOTS 
                ORDER BY $COL_EPOCH_SECONDS DESC 
                LIMIT ?
            )
        """.trimIndent()
        readableDatabase.rawQuery(query, arrayOf(limit.toString())).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) > 0) {
                TelemetryWindowAverage(
                    sampleCount = cursor.getInt(0),
                    avgOutdoorTempC = cursor.getDouble(1),
                    avgSupplyTempC = cursor.getDouble(2),
                    avgReturnTempC = cursor.getDouble(3),
                    avgDeltaTC = cursor.getDouble(4),
                    avgModulationPct = cursor.getDouble(5)
                )
            } else null
        }
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_SNAPSHOTS, null, null)
        Unit
    }

    override suspend fun purgeSnapshotsWithSetpointAbove(thresholdC: Double): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_SNAPSHOTS, "$COL_SETPOINT_TEMP > ?", arrayOf(thresholdC.toString()))
    }

    override suspend fun exportAsCsv(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine(HeatPumpTelemetrySnapshot.CSV_HEADER)
        val all = getRecentSnapshots(limit = 10000)
        for (snap in all) {
            sb.appendLine(snap.toCsvRow())
        }
        sb.toString()
    }

    override suspend fun exportAsJson(): String = withContext(Dispatchers.IO) {
        val all = getRecentSnapshots(limit = 10000)
        json.encodeToString(all)
    }

    private fun cursorToSnapshot(cursor: Cursor): HeatPumpTelemetrySnapshot {
        fun getDoubleOrNull(col: String): Double? {
            val idx = cursor.getColumnIndexOrThrow(col)
            return if (cursor.isNull(idx)) null else cursor.getDouble(idx)
        }

        fun getStringOrNull(col: String): String? {
            val idx = cursor.getColumnIndexOrThrow(col)
            return if (cursor.isNull(idx)) null else cursor.getString(idx)
        }

        return HeatPumpTelemetrySnapshot(
            timestampIso = cursor.getString(cursor.getColumnIndexOrThrow(COL_TIMESTAMP_ISO)),
            epochSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(COL_EPOCH_SECONDS)),
            outdoorTempC = getDoubleOrNull(COL_OUTDOOR_TEMP),
            supplyTempC = getDoubleOrNull(COL_SUPPLY_TEMP),
            returnTempC = getDoubleOrNull(COL_RETURN_TEMP),
            condenserTempC = getDoubleOrNull(COL_CONDENSER_TEMP),
            setpointTempC = getDoubleOrNull(COL_SETPOINT_TEMP),
            modulationPct = getDoubleOrNull(COL_MODULATION_PCT),
            demand = getStringOrNull(COL_DEMAND),
            chStatus = getStringOrNull(COL_CH_STATUS),
            spotPriceGrossCt = getDoubleOrNull(COL_SPOT_GROSS_CT),
            spotPriceNetCt = getDoubleOrNull(COL_SPOT_NET_CT)
        )
    }
}
