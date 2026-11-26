from datetime import datetime
from enum import Enum
from typing import Literal, Optional
from pydantic import BaseModel, Field


class VehicleClass(str, Enum):
    TWO_WHEELER = "2W"
    CAR = "4W"
    COMMERCIAL = "COMMERCIAL"


class VehicleCreateRequest(BaseModel):
    owner_name: str = Field(..., min_length=1)
    emergency_contact: str = Field(..., min_length=5)
    vehicle_class: VehicleClass
    make: str
    model: str
    year: int = Field(..., ge=1980, le=2030)
    fuel_type: Literal["PETROL", "DIESEL", "EV", "HYBRID", "CNG"]
    odometer_km: int = Field(..., ge=0)
    last_service_km_ago: Optional[int] = Field(default=None, ge=0)
    rc_registration_number: str = Field(..., min_length=4)
    plate_scanned_number: str = Field(..., min_length=4)
    accepts_advisory_disclaimer: bool


class VehicleRecord(BaseModel):
    vehicle_id: str
    owner_name: str
    emergency_contact: str
    vehicle_class: VehicleClass
    make: str
    model: str
    year: int
    fuel_type: str
    odometer_km: int
    last_service_km_ago: Optional[int]
    normalized_rc_plate: str
    normalized_scanned_plate: str
    plate_mismatch: bool
    verification_notice: str
    disclaimer: str
    created_at: datetime


class ScanItemInput(BaseModel):
    item_key: str
    label: str
    status: Literal["OK", "CHECK", "ALERT"]
    photo_url: Optional[str] = None
    note: Optional[str] = None


class HealthScanRequest(BaseModel):
    vehicle_id: str
    trip_distance_km: int = Field(default=100, ge=1)
    is_ghat_route: bool = False
    items: list[ScanItemInput]


class ServiceAdvisory(BaseModel):
    advisory_id: str
    title: str
    urgency: Literal["BEFORE_TRIP", "SOON", "ROUTINE"]
    plain_explanation: str
    recommended_action: str


class HealthScanResponse(BaseModel):
    scan_id: str
    vehicle_id: str
    readiness_score: int = Field(..., ge=0, le=100)
    overall_status: Literal["READY", "ADVISORY_NEEDED", "URGENT_CHECK"]
    advisories: list[ServiceAdvisory]
    disclaimer: str
    created_at: datetime


class TripCreateRequest(BaseModel):
    vehicle_id: str
    origin: str
    destination: str
    route_type: Literal["HIGHWAY", "GHAT", "LONG_RIDE", "CITY"]
    departure_at: datetime
    opt_in_trip_protection: bool = False
    protection_disclaimer_acknowledged: bool = False


class OfflineTripPack(BaseModel):
    trip_id: str
    vehicle_id: str
    origin: str
    destination: str
    route_type: str
    emergency_contact: str
    protection_selected: bool
    protection_status: Literal["NOT_SELECTED", "OPTED_IN_PENDING_PARTNER"]
    cached_workshops: list[dict]
    offline_safety_steps: list[str]
    protection_disclaimer: str


class SosAlertRequest(BaseModel):
    trip_id: str
    latitude: float
    longitude: float
    issue_summary: str
    network_available: bool = False


class BookingRequestCreate(BaseModel):
    vehicle_id: str
    workshop_id: str
    preferred_slot: str
    scan_id: Optional[str] = None
    attach_scan_summary: bool = True
    notes: Optional[str] = None


class ShareCardResponse(BaseModel):
    card_id: str
    vehicle_label: str
    trip_summary: str
    readiness_badge: str
    highlights: list[str]
    privacy_masked: bool
    share_text: str