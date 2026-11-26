from datetime import datetime
from typing import Dict, List, Literal, Optional
from pydantic import BaseModel, Field

VehicleClass = Literal["2W", "4W", "COMMERCIAL"]
FuelType = Literal["PETROL", "DIESEL", "EV", "HYBRID", "CNG"]
CheckStatus = Literal["OK", "CHECK", "ALERT"]
RouteType = Literal["CITY", "HIGHWAY", "GHAT", "LONG_RIDE"]


class LoginRequest(BaseModel):
    identifier: str = Field(..., min_length=5, description="Mobile number or email ID")
    name: Optional[str] = None


class UserProfile(BaseModel):
    user_id: str
    identifier: str
    identifier_type: Literal["MOBILE", "EMAIL"]
    name: str
    created_at: str


class InsuranceInfo(BaseModel):
    provider: str = ""
    policy_number: str = ""
    policy_type: Literal["COMPREHENSIVE", "THIRD_PARTY", "ZERO_DEP"] = "COMPREHENSIVE"
    expiry_date: str = ""  # YYYY-MM-DD


class VehicleHistoryCreate(BaseModel):
    date: str
    record_type: Literal["SERVICE", "SCAN", "INSURANCE", "REPAIR", "BOOKING"] = "SERVICE"
    odometer_km: Optional[int] = None
    summary: str
    cost_inr: Optional[int] = None


class VehicleHistoryEntry(VehicleHistoryCreate):
    entry_id: str


class VehicleCreate(BaseModel):
    user_id: str
    owner_name: str
    emergency_contact: str
    vehicle_class: VehicleClass = "4W"
    make: str
    model: str
    year: int = Field(..., ge=1990, le=2030)
    fuel_type: FuelType = "PETROL"
    odometer_km: int = Field(0, ge=0)
    last_service_km_ago: Optional[int] = Field(None, ge=0)
    rc_registration_number: str
    plate_scanned_number: str
    accepts_advisory_disclaimer: bool
    insurance: Optional[InsuranceInfo] = None


class VehicleRecord(BaseModel):
    vehicle_id: str
    user_id: str
    owner_name: str
    emergency_contact: str
    vehicle_class: VehicleClass
    make: str
    model: str
    year: int
    fuel_type: FuelType
    odometer_km: int
    last_service_km_ago: Optional[int] = None
    normalized_rc: str
    normalized_scanned_plate: str
    plate_mismatch: bool
    verification_notice: str
    insurance: Optional[InsuranceInfo] = None
    history: List[VehicleHistoryEntry] = []
    created_at: str


class ScanItem(BaseModel):
    item_key: str
    label: str
    status: CheckStatus
    note: Optional[str] = None


class ScanCreate(BaseModel):
    vehicle_id: str
    trip_distance_km: int = Field(100, ge=1)
    is_ghat_route: bool = False
    items: List[ScanItem]


class AdvisoryItem(BaseModel):
    title: str
    urgency: Literal["HIGH", "MEDIUM", "LOW"]
    plain_explanation: str


class ScanRecord(BaseModel):
    scan_id: str
    vehicle_id: str
    trip_distance_km: int
    is_ghat_route: bool
    readiness_score: int
    overall_status: Literal["READY", "CHECK_RECOMMENDED", "URGENT_CHECK"]
    advisories: List[AdvisoryItem]
    items: List[ScanItem]
    created_at: str


class TripCreate(BaseModel):
    user_id: str
    vehicle_id: str
    origin: str
    destination: str
    route_type: RouteType = "HIGHWAY"
    departure_at: str
    opt_in_trip_protection: bool = False
    protection_disclaimer_acknowledged: bool = True


class Workshop(BaseModel):
    workshop_id: str
    name: str
    area: str
    distance_km: float
    supported_classes: List[VehicleClass]
    phone: str
    rating: float


class TripRecord(BaseModel):
    trip_id: str
    user_id: str
    vehicle_id: str
    origin: str
    destination: str
    route_type: RouteType
    departure_at: str
    protection_status: Literal["OPTED_IN_PENDING_PARTNER", "NOT_SELECTED"]
    emergency_contact: str
    cached_workshops: List[Workshop]
    created_at: str


class SosRequest(BaseModel):
    trip_id: str
    latitude: float
    longitude: float
    issue_summary: str
    network_available: bool = False


class SosRecord(BaseModel):
    sos_id: str
    trip_id: str
    vehicle_id: str
    delivery_channel: Literal["LIVE_API", "QUEUED_SMS_FALLBACK"]
    notified_contact: str
    coordinates: Dict[str, float]
    issue_summary: str
    created_at: str


class BookingCreate(BaseModel):
    user_id: str
    vehicle_id: str
    workshop_id: str
    preferred_slot: str
    scan_id: Optional[str] = None
    attach_scan_summary: bool = True
    notes: Optional[str] = None


class BookingRecord(BaseModel):
    booking_id: str
    user_id: str
    vehicle_id: str
    workshop: Workshop
    preferred_slot: str
    scan_id: Optional[str] = None
    attach_scan_summary: bool
    notes: Optional[str] = None
    status: Literal["REQUEST_SENT"] = "REQUEST_SENT"
    created_at: str