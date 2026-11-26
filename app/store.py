import re
from datetime import datetime, timezone
from uuid import uuid4

from app.schemas import (
    BookingRequestCreate,
    HealthScanRequest,
    HealthScanResponse,
    OfflineTripPack,
    ServiceAdvisory,
    ShareCardResponse,
    SosAlertRequest,
    TripCreateRequest,
    VehicleClass,
    VehicleCreateRequest,
    VehicleRecord,
)

RC_PLATE_DISCLAIMER = (
    "Disclaimer: Extracted RC and license plate details are for profile convenience and basic "
    "consistency checks only. This app is not an official RTO/VAHAN or legal authority, and "
    "OCR/camera scans may misread text or miss mechanical/compliance issues."
)

SCAN_ADVISORY_DISCLAIMER = (
    "Disclaimer: Health scan scores and service advisories are informational guidance only, "
    "not a certified safety inspection or guarantee against breakdowns."
)

PROTECTION_DISCLAIMER = (
    "Disclaimer: Optional trip or roadside protection is subject to partner terms, exclusions, "
    "and applicable regulations; it is an optional add-on, not a breakdown or accident guarantee."
)

SAMPLE_WORKSHOPS = [
    {
        "workshop_id": "ws-101",
        "name": "Apex EV & Multi-Brand TechCare",
        "area": "HSR Layout Sector 2",
        "distance_km": 1.8,
        "supported_classes": ["2W", "4W", "COMMERCIAL"],
        "pre_brief_supported": True,
    },
    {
        "workshop_id": "ws-102",
        "name": "Nexus Pro Wheels & Tyre Lounge",
        "area": "Koramangala",
        "distance_km": 2.4,
        "supported_classes": ["2W", "4W"],
        "pre_brief_supported": True,
    },
    {
        "workshop_id": "ws-103",
        "name": "Garuda Precision Auto Works",
        "area": "Bellandur",
        "distance_km": 4.1,
        "supported_classes": ["4W", "COMMERCIAL"],
        "pre_brief_supported": True,
    },
]


def normalize_plate(raw: str) -> str:
    return re.sub(r"[^A-Za-z0-9]", "", raw).upper()


class InMemoryStore:
    def __init__(self, seed: bool = True) -> None:
        self.vehicles: dict[str, VehicleRecord] = {}
        self.scans: dict[str, HealthScanResponse] = {}
        self.trips: dict[str, OfflineTripPack] = {}
        self.bookings: list[dict] = []
        self.sos_logs: list[dict] = []
        if seed:
            self._seed_sample_data()

    def _seed_sample_data(self) -> None:
        now = datetime.now(timezone.utc)

        v1 = VehicleRecord(
            vehicle_id="veh_demo_4w",
            owner_name="Ramu Mutha",
            emergency_contact="+91 98765 43210",
            vehicle_class=VehicleClass.CAR,
            make="Tata",
            model="Nexon EV Max",
            year=2024,
            fuel_type="EV",
            odometer_km=14820,
            last_service_km_ago=4200,
            normalized_rc_plate="KA01AB1234",
            normalized_scanned_plate="KA01AB1234",
            plate_mismatch=False,
            verification_notice="RC number and scanned plate match.",
            disclaimer=RC_PLATE_DISCLAIMER,
            created_at=now,
        )
        v2 = VehicleRecord(
            vehicle_id="veh_demo_2w",
            owner_name="Society Rider",
            emergency_contact="+91 98765 00011",
            vehicle_class=VehicleClass.TWO_WHEELER,
            make="Royal Enfield",
            model="Himalayan 450",
            year=2025,
            fuel_type="PETROL",
            odometer_km=6350,
            last_service_km_ago=2800,
            normalized_rc_plate="KA05MN4500",
            normalized_scanned_plate="KA05MN4500",
            plate_mismatch=False,
            verification_notice="RC number and scanned plate match.",
            disclaimer=RC_PLATE_DISCLAIMER,
            created_at=now,
        )
        self.vehicles[v1.vehicle_id] = v1
        self.vehicles[v2.vehicle_id] = v2

        scan1 = HealthScanResponse(
            scan_id="scan_demo_01",
            vehicle_id=v1.vehicle_id,
            readiness_score=82,
            overall_status="URGENT_CHECK",
            advisories=[
                ServiceAdvisory(
                    advisory_id="adv_01",
                    title="Front-Left Tyre Tread Wear & Pressure (28 PSI)",
                    urgency="BEFORE_TRIP",
                    plain_explanation="Front-left tyre is at 28 PSI (below 33 PSI cold) with inner wear.",
                    recommended_action="Inflate to recommended PSI and inspect tread/alignment before ghat trip.",
                ),
                ServiceAdvisory(
                    advisory_id="adv_02",
                    title="Thermal Loop Reservoir Near MIN",
                    urgency="SOON",
                    plain_explanation="Coolant/thermal reservoir is near minimum level.",
                    recommended_action="Top up to recommended level when cool before sustained climbs.",
                ),
            ],
            disclaimer=SCAN_ADVISORY_DISCLAIMER,
            created_at=now,
        )
        self.scans[scan1.scan_id] = scan1

        trip1 = OfflineTripPack(
            trip_id="trip_demo_01",
            vehicle_id=v1.vehicle_id,
            origin="Bengaluru (HSR Layout)",
            destination="Madikeri, Coorg",
            route_type="GHAT",
            emergency_contact=v1.emergency_contact,
            protection_selected=False,
            protection_status="NOT_SELECTED",
            cached_workshops=SAMPLE_WORKSHOPS,
            offline_safety_steps=[
                "Pull safely off the road away from blind curves or ghat traffic and turn on hazard lights.",
                "If engine/inverter heat or smoke occurs, switch off ignition/AC and allow cooling—do not open a hot coolant cap.",
                "Use cached emergency contacts or SMS fallback with GPS coordinates if mobile data drops.",
            ],
            protection_disclaimer=PROTECTION_DISCLAIMER,
        )
        self.trips[trip1.trip_id] = trip1

    def register_vehicle(self, payload: VehicleCreateRequest) -> VehicleRecord:
        if not payload.accepts_advisory_disclaimer:
            raise ValueError("Please accept the advisory/OCR disclaimer before saving vehicle details.")

        norm_rc = normalize_plate(payload.rc_registration_number)
        norm_plate = normalize_plate(payload.plate_scanned_number)
        mismatch = norm_rc != norm_plate

        notice = (
            "Mismatch detected: Scanned plate does not match uploaded RC number. "
            "Please verify you registered and scanned the right vehicle."
            if mismatch
            else "RC number and scanned plate match."
        )

        record = VehicleRecord(
            vehicle_id=f"veh_{uuid4().hex[:8]}",
            owner_name=payload.owner_name,
            emergency_contact=payload.emergency_contact,
            vehicle_class=payload.vehicle_class,
            make=payload.make,
            model=payload.model,
            year=payload.year,
            fuel_type=payload.fuel_type,
            odometer_km=payload.odometer_km,
            last_service_km_ago=payload.last_service_km_ago,
            normalized_rc_plate=norm_rc,
            normalized_scanned_plate=norm_plate,
            plate_mismatch=mismatch,
            verification_notice=notice,
            disclaimer=RC_PLATE_DISCLAIMER,
            created_at=datetime.now(timezone.utc),
        )
        self.vehicles[record.vehicle_id] = record
        return record

    def create_scan(self, payload: HealthScanRequest) -> HealthScanResponse:
        if payload.vehicle_id not in self.vehicles:
            raise KeyError("Vehicle not found")

        vehicle = self.vehicles[payload.vehicle_id]
        score = 100
        advisories: list[ServiceAdvisory] = []

        for item in payload.items:
            if item.status == "ALERT":
                score -= 25
                advisories.append(
                    ServiceAdvisory(
                        advisory_id=f"adv_{uuid4().hex[:6]}",
                        title=f"Attention needed: {item.label}",
                        urgency="BEFORE_TRIP",
                        plain_explanation=item.note or f"{item.label} flagged as an alert during scan.",
                        recommended_action="Get this checked by a technician before starting your trip.",
                    )
                )
            elif item.status == "CHECK":
                score -= 10
                advisories.append(
                    ServiceAdvisory(
                        advisory_id=f"adv_{uuid4().hex[:6]}",
                        title=f"Inspect {item.label}",
                        urgency="SOON" if payload.is_ghat_route or payload.trip_distance_km >= 250 else "ROUTINE",
                        plain_explanation=item.note or f"{item.label} needs a closer look.",
                        recommended_action="Verify during pre-trip check or schedule a workshop visit.",
                    )
                )

        if vehicle.last_service_km_ago and vehicle.last_service_km_ago >= 8000:
            score -= 10
            advisories.append(
                ServiceAdvisory(
                    advisory_id=f"adv_{uuid4().hex[:6]}",
                    title="Service interval due soon",
                    urgency="BEFORE_TRIP" if payload.trip_distance_km >= 300 else "SOON",
                    plain_explanation=f"Last recorded service was ~{vehicle.last_service_km_ago} km ago.",
                    recommended_action="Book a preventive check or fluid/filter inspection before long travel.",
                )
            )

        score = max(0, min(100, score))
        if any(a.urgency == "BEFORE_TRIP" for a in advisories) or score < 60:
            status = "URGENT_CHECK"
        elif advisories:
            status = "ADVISORY_NEEDED"
        else:
            status = "READY"

        resp = HealthScanResponse(
            scan_id=f"scan_{uuid4().hex[:8]}",
            vehicle_id=payload.vehicle_id,
            readiness_score=score,
            overall_status=status,
            advisories=advisories,
            disclaimer=SCAN_ADVISORY_DISCLAIMER,
            created_at=datetime.now(timezone.utc),
        )
        self.scans[resp.scan_id] = resp
        return resp

    def create_trip_pack(self, payload: TripCreateRequest) -> OfflineTripPack:
        if payload.vehicle_id not in self.vehicles:
            raise KeyError("Vehicle not found")
        if payload.opt_in_trip_protection and not payload.protection_disclaimer_acknowledged:
            raise ValueError("Please acknowledge the protection terms and disclaimer before opting in.")

        vehicle = self.vehicles[payload.vehicle_id]
        matching_workshops = [
            w for w in SAMPLE_WORKSHOPS if vehicle.vehicle_class.value in w["supported_classes"]
        ]

        pack = OfflineTripPack(
            trip_id=f"trip_{uuid4().hex[:8]}",
            vehicle_id=vehicle.vehicle_id,
            origin=payload.origin,
            destination=payload.destination,
            route_type=payload.route_type,
            emergency_contact=vehicle.emergency_contact,
            protection_selected=payload.opt_in_trip_protection,
            protection_status=(
                "OPTED_IN_PENDING_PARTNER" if payload.opt_in_trip_protection else "NOT_SELECTED"
            ),
            cached_workshops=matching_workshops,
            offline_safety_steps=[
                "Pull safely off the road away from blind curves or ghat traffic and turn on hazard lights.",
                "If engine/inverter heat or smoke occurs, switch off ignition/AC and allow cooling—do not open a hot coolant cap.",
                "Use cached emergency contacts or SMS fallback with GPS coordinates if mobile data is unavailable.",
            ],
            protection_disclaimer=PROTECTION_DISCLAIMER,
        )
        self.trips[pack.trip_id] = pack
        return pack

    def trigger_sos(self, payload: SosAlertRequest) -> dict:
        if payload.trip_id not in self.trips:
            raise KeyError("Trip not found")
        trip = self.trips[payload.trip_id]
        channel = "DATA_AND_SMS" if payload.network_available else "QUEUED_SMS_FALLBACK"
        event = {
            "sos_id": f"sos_{uuid4().hex[:8]}",
            "trip_id": payload.trip_id,
            "notified_contact": trip.emergency_contact,
            "coordinates": {"lat": payload.latitude, "lng": payload.longitude},
            "issue_summary": payload.issue_summary,
            "delivery_channel": channel,
            "recorded_at": datetime.now(timezone.utc).isoformat(),
        }
        self.sos_logs.append(event)
        return event

    def create_booking_request(self, payload: BookingRequestCreate) -> dict:
        if payload.vehicle_id not in self.vehicles:
            raise KeyError("Vehicle not found")
        workshop = next((w for w in SAMPLE_WORKSHOPS if w["workshop_id"] == payload.workshop_id), None)
        if not workshop:
            raise KeyError("Workshop not found")

        scan = self.scans.get(payload.scan_id) if payload.scan_id else None
        booking = {
            "booking_id": f"bkg_{uuid4().hex[:8]}",
            "vehicle_id": payload.vehicle_id,
            "workshop": workshop,
            "preferred_slot": payload.preferred_slot,
            "status": "REQUEST_SENT_FOR_CONFIRMATION",
            "shared_scan_summary": (
                {
                    "scan_id": scan.scan_id,
                    "readiness_score": scan.readiness_score,
                    "advisory_count": len(scan.advisories),
                }
                if (payload.attach_scan_summary and scan)
                else None
            ),
            "notes": payload.notes,
            "created_at": datetime.now(timezone.utc).isoformat(),
        }
        self.bookings.append(booking)
        return booking

    def build_share_card(self, vehicle_id: str, trip_id: Optional[str], hide_sensitive: bool) -> ShareCardResponse:
        if vehicle_id not in self.vehicles:
            raise KeyError("Vehicle not found")
        v = self.vehicles[vehicle_id]
        plate = v.normalized_scanned_plate
        masked_plate = f"{plate[:4]}••••{plate[-2:]}" if (hide_sensitive and len(plate) > 6) else plate

        trip_label = "Routine Vehicle Check"
        if trip_id and trip_id in self.trips:
            t = self.trips[trip_id]
            trip_label = f"{t.origin} → {t.destination} ({t.route_type})"

        highlights = [f"Class: {v.vehicle_class.value} • {v.make} {v.model}"]
        if not hide_sensitive:
            highlights.append(f"Odometer: {v.odometer_km:,} km")

        latest_scan = next((s for s in reversed(self.scans.values()) if s.vehicle_id == vehicle_id), None)
        if latest_scan:
            highlights.append(f"Readiness: {latest_scan.readiness_score}% ({latest_scan.overall_status})")

        share_text = (
            f"TripGuard Check | {masked_plate} | {trip_label} | "
            f"{' • '.join(highlights)} (Advisory check, not an RTO/safety guarantee)."
        )

        return ShareCardResponse(
            card_id=f"card_{uuid4().hex[:8]}",
            vehicle_label=f"{masked_plate} • {v.make} {v.model}",
            trip_summary=trip_label,
            readiness_badge=latest_scan.overall_status if latest_scan else "CHECKED & LOGGED",
            highlights=highlights,
            privacy_masked=hide_sensitive,
            share_text=share_text,
        )