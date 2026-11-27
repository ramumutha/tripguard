package com.spenmer.tripguard

import android.content.Context
import androidx.room.*
import com.spenmer.tripguard.domain.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vehicles")
data class VehicleRow(@PrimaryKey val id: String, val name: String, val kind: String, val propulsion: String) {
    fun domain() = Vehicle(id, name, VehicleClass.valueOf(kind), Propulsion.valueOf(propulsion))
}
@Entity(tableName = "inspections")
data class InspectionRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val vehicle: String,
    val finding: String, val completed: Int, val total: Int, val recordedAt: Long)
@Entity(tableName = "journeys")
data class JourneyRow(@PrimaryKey val slot: Int = 1, val destination: String, val startedAt: Long)

@Dao
abstract class LocalDao {
    @Query("SELECT * FROM vehicles ORDER BY name, id") abstract fun vehicles(): Flow<List<VehicleRow>>
    @Query("SELECT * FROM inspections ORDER BY id") abstract fun inspections(): Flow<List<InspectionRow>>
    @Query("SELECT * FROM journeys WHERE slot = 1") abstract fun journey(): Flow<JourneyRow?>
    @Upsert abstract suspend fun saveVehicle(row: VehicleRow)
    @Query("DELETE FROM vehicles WHERE id = :id") abstract suspend fun deleteVehicle(id: String)
    @Insert abstract suspend fun record(row: InspectionRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun start(row: JourneyRow): Long
    @Query("DELETE FROM journeys") abstract suspend fun end()
    @Query("DELETE FROM vehicles") protected abstract suspend fun clearVehicles()
    @Query("DELETE FROM inspections") protected abstract suspend fun clearInspections()
    @Transaction open suspend fun clearAll() {
        clearVehicles()
        clearInspections()
        end()
    }
}

@Database(entities = [VehicleRow::class, InspectionRow::class, JourneyRow::class], version = 1, exportSchema = true)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao
    companion object {
        @Volatile private var instance: LocalDatabase? = null
        fun get(context: Context): LocalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalDatabase::class.java,
                "tripguard-local.db").build().also { instance = it }
        }
    }
}
