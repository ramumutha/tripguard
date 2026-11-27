package com.spenmer.tripguard.domain

enum class VehicleClass(val label: String) {
    TWO_WHEELER("Motorcycle / scooter"), CAR("Car / SUV"), COMMERCIAL("Commercial")
}
enum class Propulsion { PETROL, DIESEL, CNG, HYBRID, ELECTRIC }
enum class Observation { UNKNOWN, NO_VISIBLE_CONCERN, POTENTIAL_CONCERN, CRITICAL, NOT_APPLICABLE }
enum class Finding { UNABLE_TO_ASSESS, NO_VISIBLE_CONCERN, POTENTIAL_CONCERN, CRITICAL }
data class Vehicle(val id: String, val name: String, val kind: VehicleClass, val propulsion: Propulsion)
data class CheckItem(val id: String, val title: String, val guidance: String)
object SafetyPolicy {
    fun checklist(vehicle: Vehicle): List<CheckItem> = buildList {
        add(CheckItem("tyres", "Tyres", "While parked, look for visible cuts, bulges and damage. Use the manufacturer's pressure specification."))
        add(CheckItem("brakes", "Brakes", "Check visible condition and known warning indications; arrange professional assessment if uncertain."))
        add(CheckItem("lights", "Lights and horn", "Check operation while parked in a safe place."))
        add(CheckItem("leaks", "Visible leaks", "Look for unusual fluid beneath the parked vehicle. Do not touch unknown fluids."))
        if (vehicle.propulsion != Propulsion.ELECTRIC) {
            add(CheckItem("oil", "Engine oil", "Follow the owner's manual, with the vehicle stationary and engine in the specified condition."))
        }
        add(CheckItem("cooling", "Cooling system (if fitted)", "When cold, check only accessible reservoir level and visible leaks per the manual. Never open a hot coolant cap."))
        if (vehicle.kind == VehicleClass.TWO_WHEELER)
            add(CheckItem("drive", "Drive chain / belt (if fitted)", "With the engine off, visually check condition; follow manufacturer guidance."))
    }
    fun assess(observations: Collection<Observation>): Finding = when {
        Observation.CRITICAL in observations -> Finding.CRITICAL
        Observation.POTENTIAL_CONCERN in observations -> Finding.POTENTIAL_CONCERN
        observations.isEmpty() || Observation.UNKNOWN in observations ||
            observations.none { it == Observation.NO_VISIBLE_CONCERN } -> Finding.UNABLE_TO_ASSESS
        else -> Finding.NO_VISIBLE_CONCERN
    }
    fun advice(finding: Finding): String = when (finding) {
        Finding.CRITICAL -> "Do not start the journey. If driving, stop safely and follow the vehicle manual. Seek qualified assistance."
        Finding.POTENTIAL_CONCERN -> "Arrange qualified assessment before travel. Follow manufacturer warnings."
        Finding.UNABLE_TO_ASSESS -> "Checks are incomplete or unsupported. No safety conclusion can be made."
        Finding.NO_VISIBLE_CONCERN -> "No concern was reported in the completed visual checks. Hidden defects remain possible; this is not a roadworthiness certificate."
    }
}
interface VehicleRepository {
    fun list(): List<Vehicle>
    fun save(vehicle: Vehicle)
    fun remove(id: String)
}
enum class ConnectionState { NOT_SUPPORTED, DISCONNECTED, CONNECTING, CONNECTED, STALE, ERROR }
data class Measurement(val source: String, val metric: String, val value: Double, val unit: String, val observedAtMillis: Long)
interface DeviceAdapter {
    val state: ConnectionState
    val supportedMetrics: Set<String>
    fun disconnect()
}
