package com.spenmer.tripguard

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Entity(tableName = "trusted_contacts")
data class TrustedContactRow(@PrimaryKey val id: String, val name: String, val phone: String)

@Entity(tableName = "completed_journeys")
data class CompletedJourneyRow(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val destination: String, val startedAt: Long, val endedAt: Long)

@Dao
abstract class LocalDao {
    @Query("SELECT * FROM vehicles ORDER BY name, id") abstract fun vehicles(): Flow<List<VehicleRow>>
    @Query("SELECT * FROM inspections ORDER BY id") abstract fun inspections(): Flow<List<InspectionRow>>
    @Query("SELECT * FROM journeys WHERE slot = 1") abstract fun journey(): Flow<JourneyRow?>
    @Query("SELECT * FROM trusted_contacts ORDER BY name, id") abstract fun contacts(): Flow<List<TrustedContactRow>>
    @Query("SELECT * FROM completed_journeys ORDER BY id DESC") abstract fun completedJourneys(): Flow<List<CompletedJourneyRow>>
    @Upsert abstract suspend fun saveContact(row: TrustedContactRow)
    @Query("DELETE FROM trusted_contacts WHERE id = :id") abstract suspend fun deleteContact(id: String)
    @Query("SELECT * FROM journeys WHERE slot = 1") protected abstract suspend fun activeJourney(): JourneyRow?
    @Insert protected abstract suspend fun archive(row: CompletedJourneyRow)
    @Upsert abstract suspend fun saveVehicle(row: VehicleRow)
    @Query("DELETE FROM vehicles WHERE id = :id") abstract suspend fun deleteVehicle(id: String)
    @Insert abstract suspend fun record(row: InspectionRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun start(row: JourneyRow): Long
    @Query("DELETE FROM journeys") protected abstract suspend fun clearActiveJourney()
    @Query("DELETE FROM trusted_contacts") protected abstract suspend fun clearContacts()
    @Query("DELETE FROM completed_journeys") protected abstract suspend fun clearCompletedJourneys()
    @Transaction open suspend fun end(endedAt: Long) {
        val active = activeJourney() ?: return
        archive(CompletedJourneyRow(destination = active.destination, startedAt = active.startedAt, endedAt = endedAt))
        clearActiveJourney()
    }
    @Query("DELETE FROM vehicles") protected abstract suspend fun clearVehicles()
    @Query("DELETE FROM inspections") protected abstract suspend fun clearInspections()
    @Transaction open suspend fun clearAll() {
        clearVehicles()
        clearInspections()
        clearActiveJourney()
        clearContacts()
        clearCompletedJourneys()
    }
}

@Database(entities = [VehicleRow::class, InspectionRow::class, JourneyRow::class, TrustedContactRow::class, CompletedJourneyRow::class], version = 2, exportSchema = true)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `trusted_contacts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `completed_journeys` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `destination` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL)")
            }
        }
        @Volatile private var instance: LocalDatabase? = null
        fun get(context: Context): LocalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalDatabase::class.java,
                "tripguard-local.db").addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
