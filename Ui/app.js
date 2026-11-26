const API_BASE = window.location.origin;

const state = {
  vehicleClass: "4W",
  vehicleId: "veh_demo_4w",
  scanId: "scan_demo_01",
  tripId: "trip_demo_01",
  selectedWorkshopId: "ws-101",
  readinessScore: 82,
  overallStatus: "URGENT_CHECK"
};

function goScreen(num) {
  document.querySelectorAll(".screen").forEach((el, idx) => {
    el.classList.toggle("active", idx + 1 === num);
  });
  document.querySelectorAll(".nav-btn").forEach((btn, idx) => {
    btn.classList.toggle("active", idx + 1 === num);
  });
  if (num === 4) loadWorkshops();
  if (num === 5) refreshShareCard();
}

document.querySelectorAll("#classChips .chip").forEach((chip) => {
  chip.addEventListener("click", () => {
    document.querySelectorAll("#classChips .chip").forEach((c) => c.classList.remove("active"));
    chip.classList.add("active");
    state.vehicleClass = chip.dataset.val;
  });
});

async function checkApi() {
  const pill = document.getElementById("apiStatus");
  try {
    const res = await fetch(`${API_BASE}/health`);
    if (res.ok) {
      pill.textContent = "API: connected";
      return;
    }
  } catch (_) {}
  pill.textContent = "API: offline";
}

async function submitVehicle() {
  const payload = {
    owner_name: document.getElementById("ownerName").value,
    emergency_contact: document.getElementById("emergencyContact").value,
    vehicle_class: state.vehicleClass,
    make: document.getElementById("make").value,
    model: document.getElementById("model").value,
    year: Number(document.getElementById("year").value),
    fuel_type: document.getElementById("fuelType").value,
    odometer_km: Number(document.getElementById("odometer").value),
    last_service_km_ago: Number(document.getElementById("lastServiceKm").value) || null,
    rc_registration_number: document.getElementById("rcPlate").value,
    plate_scanned_number: document.getElementById("scannedPlate").value,
    accepts_advisory_disclaimer: document.getElementById("acceptDisclaimer").checked
  };

  const res = await fetch(`${API_BASE}/api/v1/vehicles`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  if (!res.ok) {
    alert(data.detail || "Error saving vehicle");
    return;
  }

  state.vehicleId = data.vehicle_id;
  document.getElementById("scanVehicleHeader").textContent =
    `Vehicle: ${data.vehicle_id} (${data.normalized_scanned_plate} • ${data.make} ${data.model})`;

  const banner = document.getElementById("mismatchBanner");
  banner.style.display = "block";
  banner.className = data.plate_mismatch ? "banner warn" : "banner ok";
  document.getElementById("mismatchTitle").textContent = data.plate_mismatch
    ? "Plate number doesn't match RC"
    : "RC & Plate match";
  document.getElementById("mismatchMsg").textContent = data.verification_notice;

  if (!data.plate_mismatch) {
    goScreen(2);
  }
}

async function submitScan() {
  const payload = {
    vehicle_id: state.vehicleId,
    trip_distance_km: Number(document.getElementById("tripDistance").value),
    is_ghat_route: document.getElementById("isGhat").value === "true",
    items: [
      { item_key: "tyres", label: "Tyres & Tread / Pressure", status: document.getElementById("itemTyres").value },
      { item_key: "fluids", label: "Coolant / Thermal / Brake Fluid", status: document.getElementById("itemFluids").value },
      { item_key: "brakes_lights", label: "Lights, Horn & Brakes", status: document.getElementById("itemBrakesLights").value }
    ]
  };

  const res = await fetch(`${API_BASE}/api/v1/scans`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  if (!res.ok) {
    alert(data.detail || "Scan error");
    return;
  }

  state.scanId = data.scan_id;
  state.readinessScore = data.readiness_score;
  state.overallStatus = data.overall_status;

  document.getElementById("readinessScoreText").textContent = `${data.readiness_score}%`;
  const badge = document.getElementById("readinessBadge");
  badge.textContent = data.overall_status;
  badge.className = `badge ${data.overall_status === "READY" ? "ok" : data.overall_status === "URGENT_CHECK" ? "err" : "warn"}`;

  const box = document.getElementById("advisoriesBox");
  box.innerHTML = data.advisories.length
    ? data.advisories.map((a) => `<div class="banner warn" style="margin-bottom:8px;"><strong>${a.title}</strong> (${a.urgency})<br/>${a.plain_explanation}</div>`).join("")
    : `<div class="banner ok">No advisories generated.</div>`;
}

async function submitTrip() {
  const payload = {
    vehicle_id: state.vehicleId,
    origin: document.getElementById("origin").value,
    destination: document.getElementById("destination").value,
    route_type: document.getElementById("routeType").value,
    departure_at: new Date(Date.now() + 86400000).toISOString(),
    opt_in_trip_protection: document.getElementById("optProtection").checked,
    protection_disclaimer_acknowledged: document.getElementById("ackProtection").checked
  };

  const res = await fetch(`${API_BASE}/api/v1/trips`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  if (!res.ok) {
    alert(data.detail || "Trip error");
    return;
  }

  state.tripId = data.trip_id;
  document.getElementById("offlinePackBox").innerHTML = `
    <div class="banner ok">
      <strong>Offline Trip Pack Cached (${data.trip_id})</strong><br/>
      Protection status: ${data.protection_status}<br/>
      Cached workshops: ${data.cached_workshops.length} • Emergency contact: ${data.emergency_contact}
    </div>
  `;
}

async function triggerSos() {
  const payload = {
    trip_id: state.tripId,
    latitude: 12.4244,
    longitude: 75.7382,
    issue_summary: "Pilot SOS test (low/no signal)",
    network_available: false
  };
  const res = await fetch(`${API_BASE}/api/v1/sos`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  document.getElementById("offlinePackBox").innerHTML = `
    <div class="banner err">
      <strong>SOS Recorded (${data.delivery_channel})</strong><br/>
      Notified: ${data.notified_contact} • GPS: ${data.coordinates.lat}, ${data.coordinates.lng}
    </div>
  `;
}

async function loadWorkshops() {
  const res = await fetch(`${API_BASE}/api/v1/workshops?vehicle_class=${encodeURIComponent(state.vehicleClass)}`);
  const list = await res.json();
  const container = document.getElementById("workshopsList");
  container.innerHTML = list.map((w) => `
    <div class="workshop-item ${w.workshop_id === state.selectedWorkshopId ? "selected" : ""}">
      <strong>${w.name}</strong> (${w.distance_km} km)<br/>
      <span class="sub">${w.area || ""} • Supports: ${w.supported_classes.join(", ")}</span>
      <button type="button" class="btn-secondary" onclick="selectAndBook('${w.workshop_id}')">
        Select & Request Slot
      </button>
    </div>
  `).join("");
}

async function selectAndBook(workshopId) {
  state.selectedWorkshopId = workshopId;
  const payload = {
    vehicle_id: state.vehicleId,
    workshop_id: workshopId,
    preferred_slot: document.getElementById("bookingSlot").value,
    scan_id: state.scanId,
    attach_scan_summary: document.getElementById("attachScanSummary").checked,
    notes: "Submitted via TripGuard Phase 1 UI"
  };
  const res = await fetch(`${API_BASE}/api/v1/bookings`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  if (!res.ok) {
    alert(data.detail || "Booking error");
    return;
  }
  document.getElementById("bookingResult").innerHTML = `
    <div class="banner ok">
      <strong>${data.status}</strong> (${data.booking_id})<br/>
      Workshop: ${data.workshop.name} • Slot: ${data.preferred_slot}
    </div>
  `;
}

let latestShareText = "";

async function refreshShareCard() {
  const masked = document.getElementById("maskPrivacy").checked;
  const url = `${API_BASE}/api/v1/share-card/${encodeURIComponent(state.vehicleId)}?trip_id=${encodeURIComponent(state.tripId)}&hide_sensitive=${masked}`;
  const res = await fetch(url);
  if (!res.ok) return;
  const data = await res.json();
  latestShareText = data.share_text;
  document.getElementById("cardVehicle").textContent = data.vehicle_label;
  document.getElementById("cardRoute").textContent = data.trip_summary;
  document.getElementById("cardBadge").textContent = `${state.readinessScore}% • ${data.readiness_badge}`;
  document.getElementById("cardHighlights").innerHTML = data.highlights.map((h) => `<li>${h}</li>`).join("");
}

function copyShareText() {
  if (!latestShareText) return;
  navigator.clipboard?.writeText(latestShareText);
  alert("Copied share text:\n" + latestShareText);
}

checkApi();