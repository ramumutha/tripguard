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
