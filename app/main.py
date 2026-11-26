from typing import Optional
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware

from app.schemas import (
    BookingRequestCreate,
    HealthScanRequest,
    HealthScanResponse,
    OfflineTripPack,
    ShareCardResponse,
    SosAlertRequest,
    TripCreateRequest,
    VehicleCreateRequest,
    VehicleRecord,
)
from app.store import SAMPLE_WORKSHOPS, InMemoryStore

app = FastAPI(title="TripGuard MVP API", version="0.2.0")

# Allow local web/mobile/Stitch previews to call the API during development.
# Tighten allow_origins to your exact frontend domain(s) before production.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

store = InMemoryStore(seed=True)


@app.get("/health")
def health_check() -> dict:
    return {"status": "ok", "vehicles_count": len(store.vehicles)}


@app.get("/api/v1/vehicles", response_model=list[VehicleRecord])
def list_vehicles() -> list[VehicleRecord]:
    return list(store.vehicles.values())


@app.post("/api/v1/vehicles", response_model=VehicleRecord, status_code=201)
def create_vehicle(payload: VehicleCreateRequest) -> VehicleRecord:
    try:
        return store.register_vehicle(payload)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/v1/scans", response_model=HealthScanResponse, status_code=201)
def create_scan(payload: HealthScanRequest) -> HealthScanResponse:
    try:
        return store.create_scan(payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.post("/api/v1/trips", response_model=OfflineTripPack, status_code=201)
def create_trip(payload: TripCreateRequest) -> OfflineTripPack:
    try:
        return store.create_trip_pack(payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/v1/sos", status_code=201)
def trigger_sos(payload: SosAlertRequest) -> dict:
    try:
        return store.trigger_sos(payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.get("/api/v1/workshops")
def list_workshops(vehicle_class: Optional[str] = Query(default=None)) -> list[dict]:
    if not vehicle_class:
        return SAMPLE_WORKSHOPS
    vc = vehicle_class.upper()
    return [w for w in SAMPLE_WORKSHOPS if vc in w["supported_classes"]]


@app.post("/api/v1/bookings", status_code=201)
def create_booking(payload: BookingRequestCreate) -> dict:
    try:
        return store.create_booking_request(payload)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.get("/api/v1/share-card/{vehicle_id}", response_model=ShareCardResponse)
def get_share_card(
    vehicle_id: str,
    trip_id: Optional[str] = Query(default=None),
    hide_sensitive: bool = Query(default=True),
) -> ShareCardResponse:
    try:
        return store.build_share_card(vehicle_id=vehicle_id, trip_id=trip_id, hide_sensitive=hide_sensitive)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc