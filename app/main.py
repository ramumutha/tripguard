import json
import os
from pathlib import Path
from typing import Any, List, Optional
from urllib import request as urlrequest
from urllib.error import URLError

from fastapi import FastAPI, File, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles

import app.schemas as schemas
import app.store as store

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


def _pick_model(*candidates: str) -> Any:
    for name in candidates:
        if hasattr(schemas, name):
            return getattr(schemas, name)
    return Any


def _call_store(func_names: List[str], *args: Any, **kwargs: Any) -> Any:
    for name in func_names:
        fn = getattr(store, name, None)
        if callable(fn):
            try:
                return fn(*args, **kwargs)
            except TypeError:
                return fn(*args)
    raise HTTPException(status_code=501, detail=f"Store handler not found for {func_names}")


AuthLoginReq = _pick_model("AuthLoginRequest", "LoginRequest", "UserLoginRequest", "AuthRequest")
AuthUserResp = _pick_model("AuthUser", "UserProfile", "UserResponse", "AuthResponse")
VehicleCreateReq = _pick_model("VehicleCreate", "VehicleIn", "CreateVehicleRequest")
VehicleResp = _pick_model("VehicleRecord", "Vehicle", "VehicleResponse")
InsuranceReq = _pick_model("InsuranceInfo", "InsuranceUpdate", "InsuranceCreate", "Insurance")
HistoryReq = _pick_model("VehicleHistoryEntry", "HistoryCreate", "ServiceHistoryCreate", "HistoryEntry")
ScanCreateReq = _pick_model("ScanCreate", "ScanRequest", "HealthScanCreate")
ScanResp = _pick_model("ScanRecord", "ScanResult", "ScanResponse")
TripCreateReq = _pick_model("TripCreate", "TripRequest", "CreateTripRequest")
TripResp = _pick_model("TripRecord", "Trip", "TripResponse")
SosCreateReq = _pick_model("SosCreate", "SosRequest", "CreateSosRequest")
SosResp = _pick_model("SosEvent", "SosRecord", "SosResponse")
WorkshopResp = _pick_model("Workshop", "WorkshopRecord", "WorkshopInfo")
BookingCreateReq = _pick_model("BookingCreate", "BookingRequest", "CreateBookingRequest")
BookingResp = _pick_model("BookingRecord", "Booking", "BookingResponse")
ShareCardResp = _pick_model("ShareCardResponse", "ShareCard", "ShareCardOut")


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


@app.post("/api/v1/auth/login", response_model=AuthUserResp)
def auth_login(payload: AuthLoginReq):
    return _call_store(["login_or_register_user", "auth_login", "login_user"], payload)


@app.get("/api/v1/vehicles", response_model=List[VehicleResp])
def get_vehicles(user_id: Optional[str] = Query(default=None)):
    return _call_store(["list_vehicles", "get_vehicles"], user_id=user_id)


@app.post("/api/v1/vehicles", response_model=VehicleResp, status_code=201)
def add_vehicle(payload: VehicleCreateReq):
    try:
        return _call_store(["create_vehicle", "add_vehicle"], payload)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/v1/vehicles/{vehicle_id}", response_model=VehicleResp)
def read_vehicle(vehicle_id: str):
    v = _call_store(["get_vehicle", "read_vehicle"], vehicle_id)
    if not v:
        raise HTTPException(status_code=404, detail="Vehicle not found")
    return v


@app.put("/api/v1/vehicles/{vehicle_id}/insurance", response_model=VehicleResp)
def put_vehicle_insurance(vehicle_id: str, payload: InsuranceReq):
    try:
        return _call_store(["update_vehicle_insurance", "set_vehicle_insurance"], vehicle_id, payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle not found") from exc


@app.post("/api/v1/vehicles/{vehicle_id}/history", response_model=VehicleResp)
def post_vehicle_history(vehicle_id: str, payload: HistoryReq):
    try:
        return _call_store(["add_vehicle_history", "append_vehicle_history"], vehicle_id, payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle not found") from exc


@app.post("/api/v1/scans", response_model=ScanResp, status_code=201)
def post_scan(payload: ScanCreateReq):
    try:
        return _call_store(["create_scan", "run_scan"], payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle not found") from exc


@app.get("/api/v1/trips", response_model=List[TripResp])
def get_trips(user_id: Optional[str] = Query(default=None)):
    return _call_store(["list_trips", "get_trips"], user_id=user_id)


@app.post("/api/v1/trips", response_model=TripResp, status_code=201)
def post_trip(payload: TripCreateReq):
    try:
        return _call_store(["create_trip", "add_trip"], payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle not found") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/v1/sos", response_model=SosResp, status_code=201)
def post_sos(payload: SosCreateReq):
    try:
        return _call_store(["create_sos", "trigger_sos"], payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Trip or vehicle not found") from exc


@app.get("/api/v1/workshops", response_model=List[WorkshopResp])
def get_workshops(vehicle_class: Optional[str] = Query(default=None)):
    return _call_store(["list_workshops", "get_workshops"], vehicle_class=vehicle_class)


@app.get("/api/v1/bookings", response_model=List[BookingResp])
def get_bookings(user_id: Optional[str] = Query(default=None)):
    return _call_store(["list_bookings", "get_bookings"], user_id=user_id)


@app.post("/api/v1/bookings", response_model=BookingResp, status_code=201)
def post_booking(payload: BookingCreateReq):
    try:
        return _call_store(["create_booking", "add_booking"], payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle or workshop not found") from exc


@app.get("/api/v1/share-card/{vehicle_id}", response_model=ShareCardResp)
def read_share_card(vehicle_id: str, hide_sensitive: bool = Query(default=True)):
    try:
        return _call_store(["get_share_card", "build_share_card"], vehicle_id=vehicle_id, hide_sensitive=hide_sensitive)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Vehicle not found") from exc
