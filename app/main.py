import os
from pathlib import Path
from typing import List, Optional
from urllib import request as urlrequest
from urllib.error import URLError

from fastapi import FastAPI, File, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles

from app.schemas import (
    AuthLoginRequest,
    AuthUser,
    BookingCreate,
    BookingRecord,
    InsuranceInfo,
    ScanCreate,
    ScanRecord,
    ShareCardResponse,
    SosCreate,
    SosEvent,
    TripCreate,
    TripRecord,
    VehicleCreate,
    VehicleHistoryEntry,
    VehicleRecord,
    Workshop,
)
from app.store import (
    create_booking,
    create_scan,
    create_sos,
    create_trip,
    create_vehicle,
    get_share_card,
    get_vehicle,
    list_bookings,
    list_trips,
    list_vehicles,
    list_workshops,
    login_or_register_user,
    update_vehicle_insurance,
    add_vehicle_history,
)

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
            import json

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


@app.post("/api/v1/auth/login", response_model=AuthUser)
def auth_login(payload: AuthLoginRequest):
    return login_or_register_user(payload)


@app.get("/api/v1/vehicles", response_model=List[VehicleRecord])
def get_vehicles(user_id: Optional[str] = Query(default=None)):
    return list_vehicles(user_id=user_id)


@app.post("/api/v1/vehicles", response_model=VehicleRecord, status_code=201)
def add_vehicle(payload: VehicleCreate):
    try:
        return create_vehicle(payload)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/v1/vehicles/{vehicle_id}", response_model=VehicleRecord)
def read_vehicle(vehicle_id: str):
    v = get_vehicle(vehicle_id)
    if not v:
        raise HTTPException(status_code=404, detail="Vehicle not found")
    return v


@app.put("/api/v1/vehicles/{vehicle_id}/insurance", response_model=VehicleRecord)
def put_vehicle_insurance(vehicle_id: str, payload: InsuranceInfo):
    try:
        return update_vehicle_insurance(vehicle_id, payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle not found") from exc


@app.post("/api/v1/vehicles/{vehicle_id}/history", response_model=VehicleRecord)
def post_vehicle_history(vehicle_id: str, payload: VehicleHistoryEntry):
    try:
        return add_vehicle_history(vehicle_id, payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle not found") from exc


@app.post("/api/v1/scans", response_model=ScanRecord, status_code=201)
def post_scan(payload: ScanCreate):
    try:
        return create_scan(payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle not found") from exc


@app.get("/api/v1/trips", response_model=List[TripRecord])
def get_trips(user_id: Optional[str] = Query(default=None)):
    return list_trips(user_id=user_id)


@app.post("/api/v1/trips", response_model=TripRecord, status_code=201)
def post_trip(payload: TripCreate):
    try:
        return create_trip(payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle not found") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/v1/sos", response_model=SosEvent, status_code=201)
def post_sos(payload: SosCreate):
    try:
        return create_sos(payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Trip or vehicle not found") from exc


@app.get("/api/v1/workshops", response_model=List[Workshop])
def get_workshops(vehicle_class: Optional[str] = Query(default=None)):
    return list_workshops(vehicle_class=vehicle_class)


@app.get("/api/v1/bookings", response_model=List[BookingRecord])
def get_bookings(user_id: Optional[str] = Query(default=None)):
    return list_bookings(user_id=user_id)


@app.post("/api/v1/bookings", response_model=BookingRecord, status_code=201)
def post_booking(payload: BookingCreate):
    try:
        return create_booking(payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle or workshop not found") from exc


@app.get("/api/v1/share-card/{vehicle_id}", response_model=ShareCardResponse)
def read_share_card(vehicle_id: str, hide_sensitive: bool = Query(default=True)):
    try:
        return get_share_card(vehicle_id=vehicle_id, hide_sensitive=hide_sensitive)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle not found") from exc
