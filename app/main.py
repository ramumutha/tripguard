import json
import os
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib import request as urlrequest
from urllib.error import URLError

from fastapi import FastAPI, File, HTTPException, Query, UploadFile
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
from app.store import LocalJsonStore, mask_plate, normalize_plate, utc_now

app = FastAPI(title="TripGuard Phase 1 API", version="2.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

_ui_dir = Path("ui") if Path("ui").is_dir() else Path("Ui")
app.mount("/app", StaticFiles(directory=str(_ui_dir), html=True), name="ui")

db = LocalJsonStore()
if not isinstance(db.data, dict):
    db.data = {}
db.data.setdefault("users", [])
db.data.setdefault("vehicles", [])
db.data.setdefault("scans", [])
db.data.setdefault("trips", [])
db.data.setdefault("sos_events", [])
db.data.setdefault("bookings", [])


def _persist() -> None:
    db.save()


def _find_vehicle_raw(vehicle_id: str) -> Optional[Dict[str, Any]]:
    for v in db.data.get("vehicles", []):
        if v.get("vehicle_id") == vehicle_id:
            return v
    return None


def _build_vehicle_record(v: Dict[str, Any]) -> VehicleRecord:
    rc_norm = (
        v.get("normalized_rc")
        or v.get("normalized_rc_plate")
        or normalize_plate(v.get("rc_registration_number", ""))
    )
    scan_norm = v.get("normalized_scanned_plate") or normalize_plate(
        v.get("plate_scanned_number") or rc_norm
    )
    mismatch = bool(v.get("plate_mismatch", rc_norm != scan_norm))
    notice = v.get("verification_notice") or (
        f"RC plate ({rc_norm}) differs from scanned plate ({scan_norm}). Please verify."
        if mismatch
        else "Plate and RC match (advisory check)."
    )
    ins_data = v.get("insurance")
    ins_obj = InsuranceInfo(**ins_data) if isinstance(ins_data, dict) else None
    hist_objs = [VehicleHistoryEntry(**h) for h in v.get("history", [])]

    return VehicleRecord(
        vehicle_id=v["vehicle_id"],
        user_id=v["user_id"],
        owner_name=v["owner_name"],
        emergency_contact=v["emergency_contact"],
        vehicle_class=v["vehicle_class"],
        make=v["make"],
        model=v["model"],
        year=int(v.get("year", 2024)),
        fuel_type=v.get("fuel_type", "PETROL"),
        odometer_km=int(v.get("odometer_km", 0)),
        last_service_km_ago=v.get("last_service_km_ago"),
        normalized_rc=rc_norm,
        normalized_scanned_plate=scan_norm,
        plate_mismatch=mismatch,
        verification_notice=notice,
        insurance=ins_obj,
        history=hist_objs,
        created_at=v.get("created_at", utc_now()),
    )


@app.get("/")
def root():
    return RedirectResponse(url="/app/")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "tripguard-api",
        "version": "2.0.0",
        "plate_recognizer_configured": bool(os.getenv("PLATERECOGNIZER_API_TOKEN", "").strip()),
    }


@app.post("/api/v1/ocr/plate")
async def recognize_plate_image(file: UploadFile = File(...)):
    token = os.getenv("PLATERECOGNIZER_API_TOKEN", "").strip()
    if not token:
        raise HTTPException(
            status_code=503,
            detail="PLATERECOGNIZER_API_TOKEN is not configured on the server.",
        )

    raw_bytes = await file.read()
    if not raw_bytes:
        raise HTTPException(status_code=400, detail="Empty image upload.")

    boundary = "----TripGuardBoundary7MA4YWxkTrZu0gW"
    filename = file.filename or "plate.png"
    content_type = file.content_type or "image/png"

    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="upload"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8") + raw_bytes + f"\r\n--{boundary}--\r\n".encode("utf-8")

    req = urlrequest.Request(
        "https://api.platerecognizer.com/v1/plate-reader/",
        data=body,
        headers={
            "Authorization": f"Token {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )

    try:
        with urlrequest.urlopen(req, timeout=20) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except URLError as exc:
        raise HTTPException(status_code=502, detail=f"Plate Recognizer request failed: {exc}") from exc

    results = payload.get("results") or []
    if not results:
        return {"plate": None, "make": None, "score": 0.0}

    top = results[0]
    raw_plate = (top.get("plate") or "").upper().strip()
    score = float(top.get("score") or 0.0)

    make_guess = None
    vehicle_info = top.get("vehicle") or {}
    if isinstance(vehicle_info, dict):
        make_guess = vehicle_info.get("make")

    return {
        "plate": raw_plate or None,
        "make": make_guess,
        "score": score,
    }


@app.post("/api/v1/auth/login", response_model=UserProfile)
def auth_login(payload: LoginRequest):
    user_dict = db.find_or_create_user(identifier=payload.identifier, name=payload.name)
    _persist()
    return UserProfile(**user_dict)


@app.get("/api/v1/vehicles", response_model=List[VehicleRecord])
def get_vehicles(user_id: Optional[str] = Query(default=None)):
    items = db.data.get("vehicles", [])
    if user_id:
        items = [v for v in items if v.get("user_id") == user_id]
    return [_build_vehicle_record(v) for v in items]


@app.post("/api/v1/vehicles", response_model=VehicleRecord, status_code=201)
def add_vehicle(payload: VehicleCreate):
    if not payload.accepts_advisory_disclaimer:
        raise HTTPException(status_code=400, detail="Please accept the advisory disclaimer.")

    rc_norm = normalize_plate(payload.rc_registration_number)
    scan_plate_raw = payload.plate_scanned_number or payload.rc_registration_number
    scan_norm = normalize_plate(scan_plate_raw)
    mismatch = rc_norm != scan_norm
    notice = (
        f"RC plate ({rc_norm}) differs from scanned plate ({scan_norm}). Please verify."
        if mismatch
        else "Plate and RC match (advisory check)."
    )

    vid = db.new_id("veh")
    now_str = utc_now()
    history_entries: List[Dict[str, Any]] = []
    if payload.odometer_km > 0:
        history_entries.append(
            {
                "entry_id": db.new_id("hst"),
                "date": now_str[:10],
                "record_type": "ODOMETER",
                "odometer_km": payload.odometer_km,
                "summary": f"Registered at {payload.odometer_km:,} km",
                "cost_inr": None,
            }
        )

    raw_rec: Dict[str, Any] = {
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
        "last_service_km_ago": None,
        "normalized_rc": rc_norm,
        "normalized_scanned_plate": scan_norm,
        "plate_mismatch": mismatch,
        "verification_notice": notice,
        "insurance": payload.insurance.model_dump() if payload.insurance else None,
        "history": history_entries,
        "created_at": now_str,
    }
    db.data["vehicles"].append(raw_rec)
    _persist()
    return _build_vehicle_record(raw_rec)


@app.get("/api/v1/vehicles/{vehicle_id}", response_model=VehicleRecord)
def read_vehicle(vehicle_id: str):
    v = _find_vehicle_raw(vehicle_id)
    if not v:
        raise HTTPException(status_code=404, detail="Vehicle not found")
    return _build_vehicle_record(v)


@app.put("/api/v1/vehicles/{vehicle_id}/insurance", response_model=VehicleRecord)
def put_vehicle_insurance(vehicle_id: str, payload: InsuranceInfo):
    v = _find_vehicle_raw(vehicle_id)
    if not v:
        raise HTTPException(status_code=404, detail="Vehicle not found")
    v["insurance"] = payload.model_dump()
    _persist()
    return _build_vehicle_record(v)


@app.post("/api/v1/vehicles/{vehicle_id}/history", response_model=VehicleRecord)
def post_vehicle_history(vehicle_id: str, payload: VehicleHistoryCreate):
    v = _find_vehicle_raw(vehicle_id)
    if not v:
        raise HTTPException(status_code=404, detail="Vehicle not found")
    entry = VehicleHistoryEntry(
        entry_id=db.new_id("hst"),
        date=payload.date,
        record_type=payload.record_type,
        odometer_km=payload.odometer_km,
        summary=payload.summary,
        cost_inr=payload.cost_inr,
    )
    v.setdefault("history", []).insert(0, entry.model_dump())
    if payload.odometer_km and payload.odometer_km > int(v.get("odometer_km", 0)):
        v["odometer_km"] = int(payload.odometer_km)
    _persist()
    return _build_vehicle_record(v)


@app.post("/api/v1/scans", response_model=ScanRecord, status_code=201)
def post_scan(payload: ScanCreate):
    v = _find_vehicle_raw(payload.vehicle_id)
    if not v:
        raise HTTPException(status_code=404, detail="Vehicle not found")

    score = 100
    advisories: List[AdvisoryItem] = []

    for item in payload.items:
        st = (item.status or "OK").upper()
        if st == "ALERT":
            score -= 25
            advisories.append(
                AdvisoryItem(
                    severity="HIGH",
                    title=f"Attention needed: {item.label}",
                    plain_explanation=f"{item.label} was marked ALERT before a {payload.trip_distance_km} km trip.",
                    recommended_action="Inspect or service this item before starting the journey.",
                )
            )
        elif st == "CHECK":
            score -= 10
            advisories.append(
                AdvisoryItem(
                    severity="MEDIUM",
                    title=f"Verify {item.label}",
                    plain_explanation=f"{item.label} was flagged for routine check.",
                    recommended_action="Check level/condition and top up or adjust if needed.",
                )
            )

    if payload.is_ghat_route:
        score -= 5
        advisories.append(
            AdvisoryItem(
                severity="LOW",
                title="Ghat / Hill Route Advisory",
                plain_explanation="Steep gradients and hairpins increase brake and cooling load.",
                recommended_action="Use engine braking on descents and verify tyre tread and coolant.",
            )
        )

    score = max(10, min(100, score))
    if score >= 80:
        overall = "READY"
    elif score >= 55:
        overall = "CHECK_RECOMMENDED"
    else:
        overall = "URGENT_CHECK"

    scan_rec = ScanRecord(
        scan_id=db.new_id("scn"),
        vehicle_id=payload.vehicle_id,
        trip_distance_km=payload.trip_distance_km,
        is_ghat_route=payload.is_ghat_route,
        readiness_score=score,
        overall_status=overall,
        advisories=advisories,
        items=payload.items,
        created_at=utc_now(),
    )
    db.data["scans"].append(scan_rec.model_dump())
    _persist()
    return scan_rec


@app.get("/api/v1/trips", response_model=List[TripRecord])
def get_trips(user_id: Optional[str] = Query(default=None)):
    items = db.data.get("trips", [])
    if user_id:
        items = [t for t in items if t.get("user_id") == user_id]
    return [TripRecord(**t) for t in items]


@app.post("/api/v1/trips", response_model=TripRecord, status_code=201)
def post_trip(payload: TripCreate):
    v = _find_vehicle_raw(payload.vehicle_id)
    if not v:
        raise HTTPException(status_code=404, detail="Vehicle not found")
    if payload.opt_in_trip_protection and not payload.protection_disclaimer_acknowledged:
        raise HTTPException(status_code=400, detail="Please acknowledge optional protection terms.")

    veh_rec = _build_vehicle_record(v)
    cached_ws = db.get_workshops(vehicle_class=veh_rec.vehicle_class)
    prot_status = "OPTED_IN_PENDING_PARTNER" if payload.opt_in_trip_protection else "NOT_SELECTED"

    trip_rec = TripRecord(
        trip_id=db.new_id("trp"),
        user_id=payload.user_id,
        vehicle_id=payload.vehicle_id,
        origin=payload.origin,
        destination=payload.destination,
        route_type=payload.route_type,
        departure_at=payload.departure_at,
        protection_status=prot_status,
        emergency_contact=veh_rec.emergency_contact,
        cached_workshops=cached_ws,
        created_at=utc_now(),
    )
    db.data["trips"].append(trip_rec.model_dump())
    _persist()
    return trip_rec


@app.post("/api/v1/sos", response_model=SosRecord, status_code=201)
def post_sos(payload: SosRequest):
    trip = next((t for t in db.data.get("trips", []) if t.get("trip_id") == payload.trip_id), None)
    if not trip:
        raise HTTPException(status_code=404, detail="Trip not found")
    v = _find_vehicle_raw(trip["vehicle_id"])
    notified = (v or {}).get("emergency_contact") or trip.get("emergency_contact") or "+919876500112"
    channel = "LIVE_API" if payload.network_available else "QUEUED_SMS_FALLBACK"

    sos_rec = SosRecord(
        sos_id=db.new_id("sos"),
        trip_id=payload.trip_id,
        vehicle_id=trip["vehicle_id"],
        delivery_channel=channel,
        notified_contact=notified,
        coordinates={"latitude": payload.latitude, "longitude": payload.longitude},
        issue_summary=payload.issue_summary,
        created_at=utc_now(),
    )
    db.data["sos_events"].append(sos_rec.model_dump())
    _persist()
    return sos_rec


@app.get("/api/v1/workshops", response_model=List[Workshop])
def get_workshops(vehicle_class: Optional[str] = Query(default=None)):
    return db.get_workshops(vehicle_class=vehicle_class)


@app.get("/api/v1/bookings", response_model=List[BookingRecord])
def get_bookings(user_id: Optional[str] = Query(default=None)):
    items = db.data.get("bookings", [])
    if user_id:
        items = [b for b in items if b.get("user_id") == user_id]
    return [BookingRecord(**b) for b in items]


@app.post("/api/v1/bookings", response_model=BookingRecord, status_code=201)
def post_booking(payload: BookingCreate):
    v = _find_vehicle_raw(payload.vehicle_id)
    if not v:
        raise HTTPException(status_code=404, detail="Vehicle not found")
    ws = db.get_workshop(payload.workshop_id)
    if not ws:
        raise HTTPException(status_code=404, detail="Workshop not found")

    scan_id: Optional[str] = None
    scan_summary: Optional[str] = None
    if payload.attach_scan_summary:
        veh_scans = [s for s in db.data.get("scans", []) if s.get("vehicle_id") == payload.vehicle_id]
        if veh_scans:
            latest = veh_scans[-1]
            scan_id = latest.get("scan_id")
            scan_summary = f"Score {latest['readiness_score']}% ({latest['overall_status']})"

    booking_rec = BookingRecord(
        booking_id=db.new_id("bkg"),
        user_id=payload.user_id,
        vehicle_id=payload.vehicle_id,
        workshop=ws,
        preferred_slot=payload.preferred_slot,
        scan_id=scan_id,
        attach_scan_summary=payload.attach_scan_summary,
        notes=payload.notes,
        status="REQUEST_SENT",
        created_at=utc_now(),
    )
    db.data["bookings"].append(booking_rec.model_dump())
    _persist()
    return booking_rec


@app.get("/api/v1/share-card/{vehicle_id}")
def read_share_card(vehicle_id: str, hide_sensitive: bool = Query(default=True)):
    v = _find_vehicle_raw(vehicle_id)
    if not v:
        raise HTTPException(status_code=404, detail="Vehicle not found")
    veh = _build_vehicle_record(v)

    veh_scans = [s for s in db.data.get("scans", []) if s.get("vehicle_id") == vehicle_id]
    latest_scan = veh_scans[-1] if veh_scans else None
    score = int(latest_scan["readiness_score"]) if latest_scan else 85
    badge = latest_scan["overall_status"] if latest_scan else "READY"

    veh_trips = [t for t in db.data.get("trips", []) if t.get("vehicle_id") == vehicle_id]
    latest_trip = veh_trips[-1] if veh_trips else None
    trip_summary = (
        f"{latest_trip['origin']} -> {latest_trip['destination']} ({latest_trip['route_type']})"
        if latest_trip
        else "Local / Society Readiness Check"
    )

    plate_display = mask_plate(veh.normalized_scanned_plate) if hide_sensitive else veh.normalized_scanned_plate
    veh_label = f"{plate_display} • {veh.make} {veh.model} ({veh.year})"
    highlights = [
        f"Class: {veh.vehicle_class} • Fuel: {veh.fuel_type}",
        f"RC Check: {'Mismatch flagged' if veh.plate_mismatch else 'Matched'}",
    ]
    if not hide_sensitive:
        highlights.append(f"Odometer: {veh.odometer_km:,} km • Emergency: {veh.emergency_contact}")

    share_text = (
        f"TripGuard Readiness: {score}% ({badge})\n"
        f"Vehicle: {veh_label}\n"
        f"Trip: {trip_summary}\n"
        + "\n".join(f"- {h}" for h in highlights)
    )

    return {
        "vehicle_id": vehicle_id,
        "readiness_score": score,
        "readiness_badge": badge,
        "vehicle_label": veh_label,
        "trip_summary": trip_summary,
        "highlights": highlights,
        "share_text": share_text,
    }
