import json
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

DATA_FILE = Path("data/db.json")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def normalize_plate(raw: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", (raw or "").upper())


def mask_plate(plate: str) -> str:
    clean = normalize_plate(plate)
    if len(clean) <= 6:
        return clean[:2] + "••••"
    return f"{clean[:4]} •••• {clean[-4:]}"


SEED_WORKSHOPS: List[Dict[str, Any]] = [
    {
        "workshop_id": "ws-101",
        "name": "Apex EV & Multi-Brand Care",
        "area": "HSR Layout, Bengaluru",
        "distance_km": 2.4,
        "supported_classes": ["4W"],
        "phone": "+91 80 4123 7788",
        "rating": 4.7,
    },
    {
        "workshop_id": "ws-102",
        "name": "Nexus Pro 2W & Touring Garage",
        "area": "Koramangala, Bengaluru",
        "distance_km": 3.1,
        "supported_classes": ["2W"],
        "phone": "+91 80 4199 2211",
        "rating": 4.8,
    },
    {
        "workshop_id": "ws-103",
        "name": "Garuda Auto & Commercial Works",
        "area": "Electronic City, Bengaluru",
        "distance_km": 4.8,
        "supported_classes": ["4W", "COMMERCIAL", "2W"],
        "phone": "+91 80 4511 9090",
        "rating": 4.5,
    },
]


class LocalJsonStore:
    def __init__(self, path: Path = DATA_FILE):
        self.path = path
        self.data: Dict[str, Any] = {
            "users": {},
            "vehicles": {},
            "scans": {},
            "trips": {},
            "bookings": {},
            "sos_events": [],
        }
        self._load()

    def _load(self) -> None:
        if self.path.exists():
            try:
                self.data = json.loads(self.path.read_text(encoding="utf-8"))
                return
            except Exception:
                pass
        self._seed_demo()
        self.save()

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.data, indent=2), encoding="utf-8")

    def _seed_demo(self) -> None:
        demo_user_id = "usr_demo_01"
        self.data["users"][demo_user_id] = {
            "user_id": demo_user_id,
            "identifier": "+919876543210",
            "identifier_type": "MOBILE",
            "name": "Demo Society Member",
            "created_at": utc_now(),
        }
        self.data["vehicles"]["veh_demo_4w"] = {
            "vehicle_id": "veh_demo_4w",
            "user_id": demo_user_id,
            "owner_name": "Demo Society Member",
            "emergency_contact": "+91 98765 00112",
            "vehicle_class": "4W",
            "make": "Tata",
            "model": "Nexon EV Max",
            "year": 2024,
            "fuel_type": "EV",
            "odometer_km": 14820,
            "last_service_km_ago": 4200,
            "normalized_rc": "KA01AB1234",
            "normalized_scanned_plate": "KA01AB1234",
            "plate_mismatch": False,
            "verification_notice": "RC and scanned plate match (advisory check only; not official RTO/VAHAN).",
            "insurance": {
                "provider": "HDFC ERGO",
                "policy_number": "MOT-2026-88412",
                "policy_type": "ZERO_DEP",
                "expiry_date": "2027-03-18",
            },
            "history": [
                {
                    "entry_id": "hist_seed_1",
                    "date": "2026-08-12",
                    "record_type": "SERVICE",
                    "odometer_km": 10620,
                    "summary": "10,000 km scheduled service, brake pad clean & wheel alignment",
                    "cost_inr": 4850,
                }
            ],
            "created_at": utc_now(),
        }
        self.data["vehicles"]["veh_demo_2w"] = {
            "vehicle_id": "veh_demo_2w",
            "user_id": demo_user_id,
            "owner_name": "Demo Society Member",
            "emergency_contact": "+91 98765 00112",
            "vehicle_class": "2W",
            "make": "Royal Enfield",
            "model": "Himalayan 450",
            "year": 2025,
            "fuel_type": "PETROL",
            "odometer_km": 6400,
            "last_service_km_ago": 1800,
            "normalized_rc": "KA05MN7744",
            "normalized_scanned_plate": "KA05MN7744",
            "plate_mismatch": False,
            "verification_notice": "RC and scanned plate match (advisory check only; not official RTO/VAHAN).",
            "insurance": {
                "provider": "ICICI Lombard",
                "policy_number": "TW-2026-55190",
                "policy_type": "COMPREHENSIVE",
                "expiry_date": "2026-12-28",
            },
            "history": [],
            "created_at": utc_now(),
        }

    def new_id(self, prefix: str) -> str:
        return f"{prefix}_{uuid.uuid4().hex[:8]}"

    def find_or_create_user(self, identifier: str, name: Optional[str] = None) -> Dict[str, Any]:
        cleaned = identifier.strip().lower()
        is_email = "@" in cleaned
        norm_id = cleaned if is_email else re.sub(r"[\s\-()]", "", cleaned)
        for user in self.data["users"].values():
            if user["identifier"].lower() == norm_id.lower():
                if name and name.strip():
                    user["name"] = name.strip()
                    self.save()
                return user
        uid = self.new_id("usr")
        user = {
            "user_id": uid,
            "identifier": norm_id,
            "identifier_type": "EMAIL" if is_email else "MOBILE",
            "name": (name.strip() if name and name.strip() else norm_id.split("@")[0]),
            "created_at": utc_now(),
        }
        self.data["users"][uid] = user
        self.save()
        return user

    def get_workshops(self, vehicle_class: Optional[str] = None) -> List[Dict[str, Any]]:
        if not vehicle_class:
            return SEED_WORKSHOPS
        return [w for w in SEED_WORKSHOPS if vehicle_class in w["supported_classes"]]

    def get_workshop(self, workshop_id: str) -> Optional[Dict[str, Any]]:
        return next((w for w in SEED_WORKSHOPS if w["workshop_id"] == workshop_id), None)


store = LocalJsonStore()