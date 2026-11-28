package com.spenmer.tripguard

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LocalStoreTest {
    private suspend fun withDatabase(db: LocalDatabase, action: suspend (LocalDatabase) -> Unit) {
        try { action(db) } finally { db.close() }
    }

    @Test fun persistsAcrossDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-" + UUID.randomUUID() + ".db"
        fun open() = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        try {
            withDatabase(open()) { db ->
                db.dao().saveVehicle(VehicleRow("v1", "Test car", "CAR", "PETROL"))
                db.dao().record(InspectionRow(vehicle = "Test car", finding = "CRITICAL", completed = 1, total = 6, recordedAt = 100))
                db.dao().start(JourneyRow(destination = "Test destination", startedAt = 100))
            }
            withDatabase(open()) { db ->
                assertEquals("v1", db.dao().vehicles().first().single().id)
                assertEquals("CRITICAL", db.dao().inspections().first().single().finding)
                assertEquals("Test destination", db.dao().journey().first()?.destination)
                assertEquals(-1L, db.dao().start(JourneyRow(destination = "Duplicate", startedAt = 200)))
                assertEquals("Test destination", db.dao().journey().first()?.destination)
                db.dao().clearAll()
                assertTrue(db.dao().vehicles().first().isEmpty())
                assertTrue(db.dao().inspections().first().isEmpty())
                assertNull(db.dao().journey().first())
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun migratesVersionOneAndArchivesJourney() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-" + UUID.randomUUID() + ".db"
        try {
            val old = context.openOrCreateDatabase(name, 0, null)
            try {
                old.execSQL("CREATE TABLE vehicles (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, propulsion TEXT NOT NULL)")
                old.execSQL("CREATE TABLE inspections (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, vehicle TEXT NOT NULL, finding TEXT NOT NULL, completed INTEGER NOT NULL, total INTEGER NOT NULL, recordedAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE journeys (slot INTEGER NOT NULL PRIMARY KEY, destination TEXT NOT NULL, startedAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                old.execSQL("INSERT INTO room_master_table VALUES (42, '1c6e326a609c53e1d4e3777816b74318')")
                old.execSQL("INSERT INTO vehicles VALUES ('v1','Existing car','CAR','PETROL')")
                old.execSQL("INSERT INTO inspections (vehicle,finding,completed,total,recordedAt) VALUES ('Existing car','CRITICAL',1,6,100)")
                old.execSQL("INSERT INTO journeys VALUES (1,'Existing destination',100)")
                old.version = 1
            } finally { old.close() }
            withDatabase(Room.databaseBuilder(context, LocalDatabase::class.java, name)
                .addMigrations(LocalDatabase.MIGRATION_1_2).build()) { db ->
                val dao = db.dao()
                assertEquals("Existing car", dao.vehicles().first().single().name)
                assertEquals("CRITICAL", dao.inspections().first().single().finding)
                assertEquals("Existing destination", dao.journey().first()?.destination)
                dao.end(200)
                dao.end(300)
                assertNull(dao.journey().first())
                assertEquals(200L, dao.completedJourneys().first().single().endedAt)
                dao.saveContact(TrustedContactRow("c1", "Test contact", "9876543210"))
                assertEquals("Test contact", dao.contacts().first().single().name)
                dao.clearAll()
                assertTrue(dao.contacts().first().isEmpty())
                assertTrue(dao.completedJourneys().first().isEmpty())
                assertTrue(dao.vehicles().first().isEmpty())
                assertTrue(dao.inspections().first().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun updatesAndDeletesVehicleWithoutErasingHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withDatabase(Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()) { db ->
            val dao = db.dao()
            dao.saveVehicle(VehicleRow("v1", "Old", "CAR", "PETROL"))
            dao.saveVehicle(VehicleRow("v1", "New", "CAR", "PETROL"))
            assertEquals("New", dao.vehicles().first().single().name)
            dao.record(InspectionRow(vehicle = "New", finding = "UNABLE_TO_ASSESS", completed = 0, total = 6, recordedAt = 100))
            dao.deleteVehicle("v1")
            assertTrue(dao.vehicles().first().isEmpty())
            assertEquals(1, dao.inspections().first().size)
        }
        Unit
    }
}
