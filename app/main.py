from datetime import date
from typing import List, Optional
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles

from app.schemas import (
    AdvisoryItem,
    BookingCreate,
    BookingRecord,
    InsuranceInfo,
    LoginRequest,
    ScanCreate,
    ScanRecord,
    SosRecord,
    SosRequest,
    TripCreate,
    TripRecord,
    UserProfile,
    VehicleCreate,
    VehicleHistoryCreate,
    VehicleHistoryEntry,
    VehicleRecord,
    Workshop,
)
from app.store import mask_plate, normalize_plate, store, utc_now

app = FastAPI(title="TripGuard Phase 1 API", version="2.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.mount("/app", StaticFiles(directory="ui", html=True), name="ui")


@app.get("/", include_in_schema=False)
def root_redirect():
    return RedirectResponse(url="/app/")


@app.get("/health")
def health():
    return {"status": "ok", "service": "tripguard-api", "version": "2.0.0"}


# --- AUTH / ACCOUNT ---
@app.post("/api/v1/auth/login", response_model=UserProfile)
def login_or_register(payload: LoginRequest):
    user = store.find_or_create_user(payload.identifier, payload.name)
    return user


# --- VEHICLES + INSURANCE + HISTORY ---
@app.get("/api/v1/vehicles", response_model=List[VehicleRecord])
def list_vehicles(user_id: Optional[str] = Query(default=None)):
    vehicles = list(store.data["vehicles"].values())
    if user_id:
        vehicles = [v for v in vehicles if v.get("user_id") == user_id]
    return vehicles


@app.post("/api/v1/vehicles", response_model=VehicleRecord)
def create_vehicle(payload: VehicleCreate):
    if not payload.accepts_advisory_disclaimer:
        raise HTTPException(status_code=400, detail="Advisory disclaimer must be accepted.")
    if payload.user_id not in store.data["users"]:
        raise HTTPException(status_code=404, detail="User account not found. Please sign in again.")

    norm_rc = normalize_plate(payload.rc_registration_number)
    norm_plate = normalize_plate(payload.plate_scanned_number)
    mismatch = norm_rc != norm_plate
    notice = (
        f"Mismatch detected: RC ({norm_rc}) vs Plate ({norm_plate}). Verify characters or re-scan. Advisory only; not official RTO/VAHAN."
        if mismatch
        else "RC and scanned plate match (advisory check only; not official RTO/VAHAN)."
    )

    vid = store.new_id("veh")
    record = {
        "vehicle_id": vid,
        "user_id": payload.user_id,
        "owner_name": payload.owner_name,
        "emergency_contact": payload.emergency_contact,
        "vehicle_class": payload.vehicle_class,
        "make": payload.make,
        "model": payload.model,
        "year": payload.year,
        "fuel_type": payload.fuel_type,
        "odometer_km": payload.odometer_km,
        "last_service_km_ago": payload.last_service_km_ago,
        "normalized_rc": norm_rc,
        "normalized_scanned_plate": norm_plate,
        "plate_mismatch": mismatch,
        "verification_notice": notice,
        "insurance": payload.insurance.model_dump() if payload.insurance else None,
        "history": [],
        "created_at": utc_now(),
    }
    store.data["vehicles"][vid] = record
    store.save()
    return record


@app.put("/api/v1/vehicles/{vehicle_id}/insurance", response_model=VehicleRecord)
def upsert_insurance(vehicle_id: str, payload: InsuranceInfo):
    vehicle = store.data["vehicles"].get(vehicle_id)
    if not vehicle:
        raise HTTPException(status_code=404, detail="Vehicle not found.")
    vehicle["insurance"] = payload.model_dump()
    vehicle["history"].insert(
        0,
        {
            "entry_id": store.new_id("hist"),
            "date": date.today().isoformat(),
            "record_type": "INSURANCE",
            "odometer_km": vehicle["odometer_km"],
            "summary": f"Insurance updated: {payload.provider} ({payload.policy_type}), valid till {payload.expiry_date}",
            "cost_inr": None,
        },
    )
    store.save()
    return vehicle


@app.post("/api/v1/vehicles/{vehicle_id}/history", response_model=VehicleHistoryEntry)
def add_vehicle_history(vehicle_id: str, payload: VehicleHistoryCreate):
    vehicle = store.data["vehicles"].get(vehicle_id)
    if not vehicle:
        raise HTTPException(status_code=404, detail="Vehicle not found.")
    entry = {"entry_id": store.new_id("hist"), **payload.model_dump()}
    vehicle["history"].insert(0, entry)
    if payload.odometer_km and payload.odometer_km > vehicle["odometer_km"]:
        vehicle["odometer_km"] = payload.odometer_km
    store.save()
    return entry


# --- PRE-TRIP HEALTH SCANS ---
@app.get("/api/v1/scans", response_model=List[ScanRecord])
def list_scans(vehicle_id: Optional[str] = None):
    scans = list(store.data["scans"].values())
    if vehicle_id:
        scans = [s for s in scans if s["vehicle_id"] == vehicle_id]
    return list(reversed(scans))


@app.post("/api/v1/scans", response_model=ScanRecord)
def create_scan(payload: ScanCreate):
    vehicle = store.data["vehicles"].get(payload.vehicle_id)
    if not vehicle:
        raise HTTPException(status_code=404, detail="Vehicle not found.")

    score = 100
    advisories: List[AdvisoryItem] = []

    for item in payload.items:
        if item.status == "ALERT":
            score -= 22
            advisories.append(
                AdvisoryItem(
                    title=f"{item.label}: Immediate Attention",
                    urgency="HIGH",
                    plain_explanation=item.note or f"Resolve {item.label.lower()} before starting your {payload.trip_distance_km} km drive.",
                )
            )
        elif item.status == "CHECK":
            score -= 10
            advisories.append(
                AdvisoryItem(
                    title=f"{item.label}: Quick Check Recommended",
                    urgency="MEDIUM",
                    plain_explanation=item.note or f"Inspect {item.label.lower()} at a nearby bay before highway or ghat travel.",
                )
            )

    if payload.is_ghat_route and score < 90:
        score -= 5
        advisories.append(
            AdvisoryItem(
                title="Ghat Route Extra Precaution",
                urgency="MEDIUM",
                plain_explanation="Hill descents stress brakes, tyres, and cooling—verify grip and brake feel before climbing.",
            )
        )

    score = max(20, min(100, score))
    overall = "READY" if score >= 85 else ("CHECK_RECOMMENDED" if score >= 65 else "URGENT_CHECK")

    sid = store.new_id("scan")
    scan = {
        "scan_id": sid,
        "vehicle_id": payload.vehicle_id,
        "trip_distance_km": payload.trip_distance_km,
        "is_ghat_route": payload.is_ghat_route,
        "readiness_score": score,
        "overall_status": overall,
        "advisories": [a.model_dump() for a in advisories],
        "items": [i.model_dump() for i in payload.items],
        "created_at": utc_now(),
    }
    store.data["scans"][sid] = scan
    vehicle["history"].insert(
        0,
        {
            "entry_id": store.new_id("hist"),
            "date": date.today().isoformat(),
            "record_type": "SCAN",
            "odometer_km": vehicle["odometer_km"],
            "summary": f"Pre-trip scan ({payload.trip_distance_km} km): {score}% — {overall}",
            "cost_inr": None,
        },
    )
    store.save()
    return scan


# --- TRIPS & OFFLINE SOS ---
@app.get("/api/v1/trips", response_model=List[TripRecord])
def list_trips(user_id: Optional[str] = None):
    trips = list(store.data["trips"].values())
    if user_id:
        trips = [t for t in trips if t.get("user_id") == user_id]
    return list(reversed(trips))


@app.post("/api/v1/trips", response_model=TripRecord)
def create_trip(payload: TripCreate):
    vehicle = store.data["vehicles"].get(payload.vehicle_id)
    if not vehicle:
        raise HTTPException(status_code=404, detail="Vehicle not found.")
    if payload.opt_in_trip_protection and not payload.protection_disclaimer_acknowledged:
        raise HTTPException(status_code=400, detail="Protection disclaimer must be acknowledged.")

    tid = store.new_id("trip")
    trip = {
        "trip_id": tid,
        "user_id": payload.user_id,
        "vehicle_id": payload.vehicle_id,
        "origin": payload.origin,
        "destination": payload.destination,
        "route_type": payload.route_type,
        "departure_at": payload.departure_at,
        "protection_status": "OPTED_IN_PENDING_PARTNER" if payload.opt_in_trip_protection else "NOT_SELECTED",
        "emergency_contact": vehicle["emergency_contact"],
        "cached_workshops": store.get_workshops(vehicle["vehicle_class"]),
        "created_at": utc_now(),
    }
    store.data["trips"][tid] = trip
    store.save()
    return trip


@app.post("/api/v1/sos", response_model=SosRecord)
def create_sos(payload: SosRequest):
    trip = store.data["trips"].get(payload.trip_id)
    if not trip:
        raise HTTPException(status_code=404, detail="Trip not found. Create a trip first.")
    event = {
        "sos_id": store.new_id("sos"),
        "trip_id": payload.trip_id,
        "vehicle_id": trip["vehicle_id"],
        "delivery_channel": "LIVE_API" if payload.network_available else "QUEUED_SMS_FALLBACK",
        "notified_contact": trip["emergency_contact"],
        "coordinates": {"lat": payload.latitude, "lng": payload.longitude},
        "issue_summary": payload.issue_summary,
        "created_at": utc_now(),
    }
    store.data["sos_events"].append(event)
    store.save()
    return event


# --- WORKSHOPS & APPOINTMENTS ---
@app.get("/api/v1/workshops", response_model=List[Workshop])
def list_workshops(vehicle_class: Optional[str] = None):
    return store.get_workshops(vehicle_class)


@app.get("/api/v1/bookings", response_model=List[BookingRecord])
def list_bookings(user_id: Optional[str] = None):
    bookings = list(store.data["bookings"].values())
    if user_id:
        bookings = [b for b in bookings if b.get("user_id") == user_id]
    return list(reversed(bookings))


@app.post("/api/v1/bookings", response_model=BookingRecord)
def create_booking(payload: BookingCreate):
    vehicle = store.data["vehicles"].get(payload.vehicle_id)
    if not vehicle:
        raise HTTPException(status_code=404, detail="Vehicle not found.")
    workshop = store.get_workshop(payload.workshop_id)
    if not workshop:
        raise HTTPException(status_code=404, detail="Workshop not found.")

    bid = store.new_id("bkg")
    booking = {
        "booking_id": bid,
        "user_id": payload.user_id,
        "vehicle_id": payload.vehicle_id,
        "workshop": workshop,
        "preferred_slot": payload.preferred_slot,
        "scan_id": payload.scan_id,
        "attach_scan_summary": payload.attach_scan_summary,
        "notes": payload.notes,
        "status": "REQUEST_SENT",
        "created_at": utc_now(),
    }
    store.data["bookings"][bid] = booking
    vehicle["history"].insert(
        0,
        {
            "entry_id": store.new_id("hist"),
            "date": date.today().isoformat(),
            "record_type": "BOOKING",
            "odometer_km": vehicle["odometer_km"],
            "summary": f"Appointment requested at {workshop['name']} ({payload.preferred_slot})",
            "cost_inr": None,
        },
    )
    store.save()
    return booking


# --- SHAREABLE TRIP CARD ---
@app.get("/api/v1/share-card/{vehicle_id}")
def get_share_card(vehicle_id: str, trip_id: Optional[str] = None, hide_sensitive: bool = True):
    vehicle = store.data["vehicles"].get(vehicle_id)
    if not vehicle:
        raise HTTPException(status_code=404, detail="Vehicle not found.")

    plate_display = mask_plate(vehicle["normalized_scanned_plate"]) if hide_sensitive else vehicle["normalized_scanned_plate"]
    scans = [s for s in store.data["scans"].values() if s["vehicle_id"] == vehicle_id]
    latest_scan = scans[-1] if scans else None

    trip = store.data["trips"].get(trip_id) if trip_id else None
    if not trip:
        user_trips = [t for t in store.data["trips"].values() if t["vehicle_id"] == vehicle_id]
        trip = user_trips[-1] if user_trips else None

    score = latest_scan["readiness_score"] if latest_scan else 88
    badge = latest_scan["overall_status"] if latest_scan else "READY"
    trip_summary = (
        f"{trip['origin']} → {trip['destination']} ({trip['route_type']})"
        if trip
        else "Upcoming Society / Weekend Drive"
    )
    ins = vehicle.get("insurance")
    highlights = [
        f"RC vs Plate: {'Mismatch Flagged' if vehicle['plate_mismatch'] else 'Matched'}",
        f"Insurance: {ins['provider']} (Valid till {ins['expiry_date']})" if ins and ins.get("expiry_date") else "Insurance: Not added yet",
        f"Pre-Trip Scan: {score}% ({badge})",
    ]
    if not hide_sensitive:
        highlights.append(f"Odometer: {vehicle['odometer_km']} km")

    share_text = (
        f"TripGuard Readiness Card\n"
        f"Vehicle: {plate_display} ({vehicle['make']} {vehicle['model']})\n"
        f"Trip: {trip_summary}\n"
        f"Score: {score}% [{badge}]\n"
        f"(Advisory check only — not an official RTO/VAHAN or safety guarantee)"
    )

    return {
        "vehicle_label": f"{plate_display} • {vehicle['make']} {vehicle['model']}",
        "vehicle_class": vehicle["vehicle_class"],
        "trip_summary": trip_summary,
        "readiness_score": score,
        "readiness_badge": badge,
        "highlights": highlights,
        "share_text": share_text,
    }