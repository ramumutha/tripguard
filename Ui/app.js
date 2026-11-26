const API = window.location.origin;

const state = {
  user: JSON.parse(localStorage.getItem("tg_user") || "null"),
  vehicles: [],
  trips: [],
  bookings: [],
  modalClass: "4W",
  modalSourceTab: "garage",
  activeDetailsVehicleId: null,
  latestShareText: "",
  scanItems: [
    { item_key: "tyres", label: "Tyres, Tread & Pressure", status: "OK" },
    { item_key: "brakes", label: "Brakes & Brake Fluid", status: "OK" },
    { item_key: "fluids", label: "Engine Oil / Coolant", status: "CHECK" },
    { item_key: "lights_battery", label: "Lights, Horn & Battery", status: "OK" }
  ]
};

async function init() {
  document.getElementById("tripDate").value = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
  document.getElementById("hDate").value = new Date().toISOString().slice(0, 10);
  setupModalChips();
  setupOcrUploaders();
  renderScanChecklist();
  checkHealth();

  if (state.user) {
    enterApp();
  }
}

async function checkHealth() {
  const el = document.getElementById("apiStatus");
  try {
    const r = await fetch(`${API}/health`);
    el.textContent = r.ok ? "Online • Ready" : "API error";
  } catch {
    el.textContent = "Offline";
  }
}

// --- OCR UPLOAD & EXTRACTION ---
function setupOcrUploaders() {
  const rcInput = document.getElementById("mRcFile");
  const plateInput = document.getElementById("mPlateFile");

  if (plateInput) {
    plateInput.addEventListener("change", async (e) => {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      showImagePreview(file, "mPlatePreview");
      await extractFromPlateImage(file);
    });
  }

  if (rcInput) {
    rcInput.addEventListener("change", async (e) => {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      showImagePreview(file, "mRcPreview");
      await extractFromRcImage(file);
    });
  }
}

function showImagePreview(file, containerId) {
  const el = document.getElementById(containerId);
  if (!el) return;
  const url = URL.createObjectURL(file);
  el.innerHTML = `<img src="${url}" alt="Preview" style="width:100%; max-height:96px; object-fit:cover; border-radius:8px; border:1px solid #cbd5e1;" />`;
}

function preprocessImageForOcr(file, scaleFactor = 2) {
  return new Promise((resolve) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      try {
        const w = Math.min(2200, Math.max(600, img.width * scaleFactor));
        const h = Math.round((img.height / img.width) * w);
        const canvas = document.createElement("canvas");
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext("2d");
        ctx.filter = "grayscale(1) contrast(1.45)";
        ctx.drawImage(img, 0, 0, w, h);
        canvas.toBlob((blob) => {
          URL.revokeObjectURL(url);
          resolve(blob || file);
        }, "image/png");
      } catch {
        URL.revokeObjectURL(url);
        resolve(file);
      }
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      resolve(file);
    };
    img.src = url;
  });
}

function normalizeOcrPlate(rawText) {
  const upper = (rawText || "").toUpperCase();
  const compact = upper.replace(/[^A-Z0-9]/g, " ");
  const indMatch = compact.match(/\b([A-Z]{2})\s*0?([0-9]{1,2})\s*([A-Z]{1,3})\s*([0-9]{3,4})\b/);
  if (indMatch) {
    return `${indMatch[1]} ${indMatch[2].padStart(2, "0")} ${indMatch[3]} ${indMatch[4]}`;
  }
  const alnum = upper.replace(/[^A-Z0-9]/g, "");
  const fallback = alnum.match(/[A-Z]{2}[0-9]{1,2}[A-Z]{1,3}[0-9]{3,4}/);
  return fallback ? fallback[0] : "";
}

async function runTesseractOcr(file) {
  if (!window.Tesseract) {
    throw new Error("OCR library not loaded (check internet connection).");
  }
  const processed = await preprocessImageForOcr(file);
  const { data } = await window.Tesseract.recognize(processed, "eng");
  return data.text || "";
}

async function extractFromPlateImage(file) {
  const statusEl = document.getElementById("mPlateStatus");
  const plateInput = document.getElementById("mScanPlate");
  statusEl.textContent = "Scanning license plate...";
  try {
    const text = await runTesseractOcr(file);
    const detected = normalizeOcrPlate(text);
    if (detected) {
      plateInput.value = detected;
      const rcInput = document.getElementById("mRcPlate");
      if (rcInput && !rcInput.value.trim()) {
        rcInput.value = detected;
      }
      statusEl.textContent = `Detected plate: ${detected} (please verify)`;
    } else {
      statusEl.textContent = "Couldn't read plate clearly — please type number manually.";
    }
  } catch (err) {
    statusEl.textContent = err.message || "OCR failed — please type plate manually.";
  }
}

async function extractFromRcImage(file) {
  const statusEl = document.getElementById("mRcStatus");
  const rcInput = document.getElementById("mRcPlate");
  statusEl.textContent = "Scanning RC details...";
  try {
    const text = await runTesseractOcr(file);
    const upper = text.toUpperCase();
    const detectedPlate = normalizeOcrPlate(upper);
    if (detectedPlate) {
      rcInput.value = detectedPlate;
    }

    const makeEl = document.getElementById("mMake");
    const fuelEl = document.getElementById("mFuel");
    const yearEl = document.getElementById("mYear");

    const knownMakes = [
      "TATA", "MARUTI", "SUZUKI", "HYUNDAI", "MAHINDRA", "KIA",
      "TOYOTA", "HONDA", "ROYAL ENFIELD", "BAJAJ", "TVS", "ATHER",
      "OLA", "MG", "SKODA", "VOLKSWAGEN"
    ];
    if (makeEl && !makeEl.value.trim()) {
      const foundMake = knownMakes.find((m) => upper.includes(m));
      if (foundMake) {
        makeEl.value = foundMake.toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
      }
    }

    if (fuelEl) {
      if (/\bEV\b|ELECTRIC/.test(upper)) fuelEl.value = "EV";
      else if (/DIESEL/.test(upper)) fuelEl.value = "DIESEL";
      else if (/PETROL/.test(upper)) fuelEl.value = "PETROL";
      else if (/HYBRID/.test(upper)) fuelEl.value = "HYBRID";
      else if (/CNG/.test(upper)) fuelEl.value = "CNG";
    }

    const yearMatch = upper.match(/\b(20[0-2][0-9]|199[0-9])\b/);
    if (yearEl && yearMatch) {
      yearEl.value = yearMatch[1];
    }

    statusEl.textContent = detectedPlate
      ? `Extracted RC: ${detectedPlate} (verify fields before saving)`
      : "RC scanned — no clear reg. number found; please verify fields.";
  } catch (err) {
    statusEl.textContent = err.message || "RC scan failed — please enter details manually.";
  }
}

// --- AUTH ---
function requestOtp() {
  const id = document.getElementById("loginIdentifier").value.trim();
  if (id.length < 5) return alert("Enter a valid mobile number or email ID.");
  document.getElementById("otpBox").style.display = "block";
  document.getElementById("btnSendOtp").style.display = "none";
  document.getElementById("btnVerifyOtp").style.display = "block";
}

async function verifyAndLogin() {
  const otp = document.getElementById("loginOtp").value.trim();
  if (otp !== "1234") return alert("For the pilot demo, use OTP 1234.");
  await doLogin(
    document.getElementById("loginIdentifier").value.trim(),
    document.getElementById("loginName").value.trim()
  );
}

async function quickDemoLogin() {
  await doLogin("+919876543210", "Demo Society Member");
}

async function doLogin(identifier, name) {
  const res = await fetch(`${API}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ identifier, name })
  });
  const data = await res.json();
  if (!res.ok) return alert(data.detail || "Sign-in failed");
  state.user = data;
  localStorage.setItem("tg_user", JSON.stringify(data));
  enterApp();
}

function logout() {
  localStorage.removeItem("tg_user");
  state.user = null;
  document.getElementById("bottomNav").classList.remove("visible");
  document.getElementById("userChip").style.display = "none";
  document.querySelectorAll(".screen").forEach((s) => s.classList.remove("active"));
  document.getElementById("screen-auth").classList.add("active");
}

async function enterApp() {
  document.getElementById("userChip").style.display = "inline-block";
  document.getElementById("userChip").textContent = `${state.user.name} • Sign Out`;
  document.getElementById("bottomNav").classList.add("visible");
  await refreshAllData();
  goTab("garage");
}

// --- NAVIGATION ---
function goTab(tab) {
  document.querySelectorAll(".screen").forEach((s) => s.classList.remove("active"));
  document.getElementById(`screen-${tab}`).classList.add("active");
  document.querySelectorAll(".nav-btn").forEach((b) => {
    b.classList.toggle("active", b.dataset.tab === tab);
  });
  if (tab === "service") renderWorkshopsForSelected();
  if (tab === "share") loadShareCard();
}

// --- DATA SYNC ---
async function refreshAllData(selectVehicleId = null) {
  const uid = encodeURIComponent(state.user.user_id);
  const [vRes, tRes, bRes] = await Promise.all([
    fetch(`${API}/api/v1/vehicles?user_id=${uid}`),
    fetch(`${API}/api/v1/trips?user_id=${uid}`),
    fetch(`${API}/api/v1/bookings?user_id=${uid}`)
  ]);
  state.vehicles = await vRes.json();
  state.trips = await tRes.json();
  state.bookings = await bRes.json();

  renderGarage();
  populateVehiclePickers(selectVehicleId);
  renderTripsList();
  renderBookingsList();
}

function vehicleById(id) {
  return state.vehicles.find((v) => v.vehicle_id === id);
}

function populateVehiclePickers(forceSelectId = null) {
  const ids = ["scanVehicleSelect", "tripVehicleSelect", "bookVehicleSelect", "shareVehicleSelect"];
  const optionsHtml = state.vehicles.length
    ? state.vehicles
        .map((v) => `<option value="${v.vehicle_id}">${v.normalized_scanned_plate} — ${v.make} ${v.model} (${v.vehicle_class})</option>`)
        .join("")
    : `<option value="">No vehicle registered yet — tap + New</option>`;

  ids.forEach((elId) => {
    const sel = document.getElementById(elId);
    const prev = sel.value;
    sel.innerHTML = optionsHtml;
    if (forceSelectId && state.vehicles.some((v) => v.vehicle_id === forceSelectId)) {
      sel.value = forceSelectId;
    } else if (prev && state.vehicles.some((v) => v.vehicle_id === prev)) {
      sel.value = prev;
    }
  });
}

// --- GARAGE ---
function renderGarage() {
  const el = document.getElementById("garageList");
  if (!state.vehicles.length) {
    el.innerHTML = `<div class="banner warn">No vehicles added yet. Tap <strong>+ Add Vehicle</strong> above to register your first 2W, car, or commercial vehicle.</div>`;
    return;
  }
  el.innerHTML = state.vehicles
    .map((v) => {
      const insText = v.insurance && v.insurance.expiry_date
        ? `${v.insurance.provider || "Policy"} • Exp: ${v.insurance.expiry_date}`
        : "Insurance not added";
      const insClass = v.insurance && v.insurance.expiry_date ? "ok" : "warn";
      return `
        <div class="veh-card" style="margin-top:10px;">
          <div class="card-head" style="margin:0;">
            <div>
              <span class="plate-tag">${v.normalized_scanned_plate}</span>
              <h3 style="margin-top:6px;">${v.make} ${v.model} (${v.year})</h3>
            </div>
            <span class="badge info">${v.vehicle_class} • ${v.fuel_type}</span>
          </div>
          <div class="meta-row">
            <span>Odo: ${v.odometer_km.toLocaleString()} km</span>
            <span>•</span>
            <span class="badge ${v.plate_mismatch ? "err" : "ok"}">${v.plate_mismatch ? "RC/Plate Mismatch" : "RC Matched"}</span>
            <span class="badge ${insClass}">${insText}</span>
          </div>
          <div class="action-row">
            <button type="button" class="btn-secondary" style="margin:0;" onclick="openDetailsModal('${v.vehicle_id}')">Insurance & History (${v.history.length})</button>
            <button type="button" class="btn-secondary" style="margin:0;" onclick="jumpToScan('${v.vehicle_id}')">Run Scan</button>
          </div>
        </div>
      `;
    })
    .join("");
}

function jumpToScan(vehicleId) {
  document.getElementById("scanVehicleSelect").value = vehicleId;
  goTab("scan");
}

// --- INLINE ADD VEHICLE MODAL ---
function setupModalChips() {
  document.querySelectorAll("#modalClassChips .chip").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll("#modalClassChips .chip").forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      state.modalClass = btn.dataset.val;
    });
  });
}

function openAddVehicleModal(sourceTab) {
  state.modalSourceTab = sourceTab;
  document.getElementById("mVehicleError").innerHTML = "";
  document.getElementById("mRcStatus").textContent = "";
  document.getElementById("mPlateStatus").textContent = "";
  document.getElementById("mRcPreview").innerHTML = "";
  document.getElementById("mPlatePreview").innerHTML = "";
  document.getElementById("addVehicleModal").classList.add("open");
}

function closeModal(id) {
  document.getElementById(id).classList.remove("open");
}

async function submitNewVehicle() {
  const rc = document.getElementById("mRcPlate").value.trim();
  const plate = document.getElementById("mScanPlate").value.trim() || rc;
  const make = document.getElementById("mMake").value.trim();
  const model = document.getElementById("mModel").value.trim();
  if (!rc || !make || !model) {
    document.getElementById("mVehicleError").innerHTML = `<div class="banner err">Please fill RC number, make, and model.</div>`;
    return;
  }

  const insProvider = document.getElementById("mInsProvider").value.trim();
  const insExpiry = document.getElementById("mInsExpiry").value;
  const payload = {
    user_id: state.user.user_id,
    owner_name: state.user.name,
    emergency_contact: document.getElementById("mEmerg").value.trim() || state.user.identifier,
    vehicle_class: state.modalClass,
    make,
    model,
    year: Number(document.getElementById("mYear").value) || 2024,
    fuel_type: document.getElementById("mFuel").value,
    odometer_km: Number(document.getElementById("mOdo").value) || 0,
    rc_registration_number: rc,
    plate_scanned_number: plate,
    accepts_advisory_disclaimer: document.getElementById("mDisclaimer").checked,
    insurance: insProvider || insExpiry ? { provider: insProvider, expiry_date: insExpiry, policy_type: "COMPREHENSIVE", policy_number: "" } : null
  };

  const res = await fetch(`${API}/api/v1/vehicles`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  if (!res.ok) {
    document.getElementById("mVehicleError").innerHTML = `<div class="banner err">${data.detail || "Could not save vehicle"}</div>`;
    return;
  }

  closeModal("addVehicleModal");
  ["mRcPlate", "mScanPlate", "mMake", "mModel", "mInsProvider", "mInsExpiry", "mRcFile", "mPlateFile"].forEach((id) => {
    const el = document.getElementById(id);
    if (el) el.value = "";
  });
  await refreshAllData(data.vehicle_id);
  if (state.modalSourceTab === "service") renderWorkshopsForSelected();
  if (data.plate_mismatch) alert(data.verification_notice);
}

// --- INSURANCE & HISTORY MODAL ---
function openDetailsModal(vehicleId) {
  const v = vehicleById(vehicleId);
  if (!v) return;
  state.activeDetailsVehicleId = vehicleId;
  document.getElementById("detailsTitle").textContent = `${v.make} ${v.model}`;
  document.getElementById("detailsSub").textContent = `${v.normalized_scanned_plate} • ${v.odometer_km.toLocaleString()} km`;

  const ins = v.insurance || {};
  document.getElementById("dInsProvider").value = ins.provider || "";
  document.getElementById("dInsPolicy").value = ins.policy_number || "";
  document.getElementById("dInsType").value = ins.policy_type || "COMPREHENSIVE";
  document.getElementById("dInsExpiry").value = ins.expiry_date || "";

  renderHistoryTimeline(v.history || []);
  document.getElementById("detailsModal").classList.add("open");
}

function renderHistoryTimeline(list) {
  const el = document.getElementById("detailsHistoryList");
  if (!list.length) {
    el.innerHTML = `<p class="sub">No history records yet.</p>`;
    return;
  }
  el.innerHTML = list
    .map((h) => `
      <div class="list-item">
        <div style="display:flex; justify-content:space-between; font-size:12px;">
          <strong>${h.record_type}</strong>
          <span style="color:#64748b;">${h.date}${h.odometer_km ? ` • ${h.odometer_km} km` : ""}</span>
        </div>
        <div style="font-size:13px; margin-top:4px;">${h.summary}</div>
        ${h.cost_inr ? `<div style="font-size:12px; color:#1d4ed8; margin-top:3px;">₹${h.cost_inr}</div>` : ""}
      </div>
    `)
    .join("");
}

async function saveInsurance() {
  const vid = state.activeDetailsVehicleId;
  const payload = {
    provider: document.getElementById("dInsProvider").value.trim(),
    policy_number: document.getElementById("dInsPolicy").value.trim(),
    policy_type: document.getElementById("dInsType").value,
    expiry_date: document.getElementById("dInsExpiry").value
  };
  const res = await fetch(`${API}/api/v1/vehicles/${vid}/insurance`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  if (!res.ok) return alert("Failed to save insurance");
  await refreshAllData(vid);
  openDetailsModal(vid);
}

async function saveHistoryEntry() {
  const vid = state.activeDetailsVehicleId;
  const summary = document.getElementById("hSummary").value.trim();
  if (!summary) return alert("Enter a short summary of service or repair.");
  const payload = {
    date: document.getElementById("hDate").value,
    record_type: "SERVICE",
    odometer_km: Number(document.getElementById("hOdo").value) || null,
    summary,
    cost_inr: Number(document.getElementById("hCost").value) || null
  };
  const res = await fetch(`${API}/api/v1/vehicles/${vid}/history`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  if (!res.ok) return alert("Failed to add history");
  document.getElementById("hSummary").value = "";
  document.getElementById("hCost").value = "";
  await refreshAllData(vid);
  openDetailsModal(vid);
}

// --- SCAN ---
function renderScanChecklist() {
  const el = document.getElementById("scanChecklist");
  el.innerHTML = state.scanItems
    .map((item, idx) => `
      <div class="scan-item">
        <div class="scan-item-top">
          <strong style="font-size:13px;">${item.label}</strong>
          <div class="seg-group">
            <button type="button" class="seg-btn ok ${item.status === "OK" ? "active" : ""}" onclick="setScanStatus(${idx}, 'OK')">OK</button>
            <button type="button" class="seg-btn check ${item.status === "CHECK" ? "active" : ""}" onclick="setScanStatus(${idx}, 'CHECK')">Check</button>
            <button type="button" class="seg-btn alert ${item.status === "ALERT" ? "active" : ""}" onclick="setScanStatus(${idx}, 'ALERT')">Alert</button>
          </div>
        </div>
      </div>
    `)
    .join("");
}

function setScanStatus(idx, status) {
  state.scanItems[idx].status = status;
  renderScanChecklist();
}

async function runScan() {
  const vid = document.getElementById("scanVehicleSelect").value;
  if (!vid) return openAddVehicleModal("scan");
  const payload = {
    vehicle_id: vid,
    trip_distance_km: Number(document.getElementById("scanDist").value) || 100,
    is_ghat_route: document.getElementById("scanGhat").value === "true",
    items: state.scanItems
  };
  const res = await fetch(`${API}/api/v1/scans`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  if (!res.ok) return alert(data.detail || "Scan failed");
  const cls = data.overall_status === "READY" ? "ok" : data.overall_status === "URGENT_CHECK" ? "err" : "warn";
  document.getElementById("scanOutput").innerHTML = `
    <div class="banner ${cls}">
      <strong>Readiness: ${data.readiness_score}% (${data.overall_status})</strong>
      ${data.advisories.map((a) => `<div style="margin-top:6px;">• <strong>${a.title}:</strong> ${a.plain_explanation}</div>`).join("") || "<div style='margin-top:4px;'>All checks look good.</div>"}
    </div>
  `;
  await refreshAllData(vid);
}

// --- TRIPS & SOS ---
async function saveTrip() {
  const vid = document.getElementById("tripVehicleSelect").value;
  if (!vid) return openAddVehicleModal("trips");
  const payload = {
    user_id: state.user.user_id,
    vehicle_id: vid,
    origin: document.getElementById("tripOrigin").value.trim(),
    destination: document.getElementById("tripDest").value.trim(),
    route_type: document.getElementById("tripRouteType").value,
    departure_at: document.getElementById("tripDate").value || new Date().toISOString().slice(0, 10),
    opt_in_trip_protection: document.getElementById("tripProtection").checked,
    protection_disclaimer_acknowledged: document.getElementById("tripProtAck").checked
  };
  const res = await fetch(`${API}/api/v1/trips`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  if (!res.ok) return alert(data.detail || "Trip save failed");
  document.getElementById("tripNotice").innerHTML = `<div class="banner ok">Trip saved & offline safety pack cached (${data.cached_workshops.length} workshops cached).</div>`;
  await refreshAllData(vid);
}

function renderTripsList() {
  const el = document.getElementById("tripsList");
  if (!state.trips.length) {
    el.innerHTML = `<p class="sub">No trips planned yet.</p>`;
    return;
  }
  el.innerHTML = state.trips
    .map((t) => {
      const v = vehicleById(t.vehicle_id);
      const vLabel = v ? `${v.normalized_scanned_plate} (${v.make} ${v.model})` : t.vehicle_id;
      return `
        <div class="list-item">
          <div style="display:flex; justify-content:space-between; align-items:center;">
            <strong>${t.origin} → ${t.destination}</strong>
            <span class="badge info">${t.route_type}</span>
          </div>
          <div class="sub" style="margin-top:4px;">${vLabel} • Departs: ${t.departure_at}</div>
          <button type="button" class="btn-danger" style="padding:9px; margin-top:8px;" onclick="sendSos('${t.trip_id}')">
            SOS Alert (Offline SMS Fallback)
          </button>
        </div>
      `;
    })
    .join("");
}

async function sendSos(tripId) {
  const payload = {
    trip_id: tripId,
    latitude: 12.9716,
    longitude: 77.5946,
    issue_summary: "Pilot SOS alert from mobile",
    network_available: false
  };
  const res = await fetch(`${API}/api/v1/sos`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  alert(`SOS Triggered (${data.delivery_channel})\nNotified: ${data.notified_contact}`);
}

// --- WORKSHOPS & APPOINTMENTS ---
async function renderWorkshopsForSelected() {
  const vid = document.getElementById("bookVehicleSelect").value;
  const v = vehicleById(vid);
  const query = v ? `?vehicle_class=${encodeURIComponent(v.vehicle_class)}` : "";
  const res = await fetch(`${API}/api/v1/workshops${query}`);
  const list = await res.json();
  document.getElementById("workshopsList").innerHTML = list
    .map((w) => `
      <div class="list-item">
        <div style="display:flex; justify-content:space-between;">
          <strong>${w.name}</strong>
          <span class="badge ok">★ ${w.rating}</span>
        </div>
        <div class="sub">${w.area} • ${w.distance_km} km • ${w.supported_classes.join(", ")}</div>
        <button type="button" class="btn-primary" style="padding:9px; margin-top:8px;" onclick="bookSlot('${w.workshop_id}')">
          Request Appointment
        </button>
      </div>
    `)
    .join("");
}

async function bookSlot(workshopId) {
  const vid = document.getElementById("bookVehicleSelect").value;
  if (!vid) return openAddVehicleModal("service");
  const payload = {
    user_id: state.user.user_id,
    vehicle_id: vid,
    workshop_id: workshopId,
    preferred_slot: document.getElementById("bookSlot").value.trim(),
    attach_scan_summary: document.getElementById("bookAttachScan").checked,
    notes: document.getElementById("bookNotes").value.trim() || null
  };
  const res = await fetch(`${API}/api/v1/bookings`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  if (!res.ok) return alert(data.detail || "Booking failed");
  document.getElementById("bookingNotice").innerHTML = `<div class="banner ok">Appointment request sent to ${data.workshop.name} for ${data.preferred_slot}.</div>`;
  await refreshAllData(vid);
}

function renderBookingsList() {
  const el = document.getElementById("bookingsList");
  if (!state.bookings.length) {
    el.innerHTML = `<p class="sub">No appointments requested yet.</p>`;
    return;
  }
  el.innerHTML = state.bookings
    .map((b) => {
      const v = vehicleById(b.vehicle_id);
      const vLabel = v ? `${v.normalized_scanned_plate} • ${v.make} ${v.model}` : b.vehicle_id;
      return `
        <div class="list-item">
          <div style="display:flex; justify-content:space-between;">
            <strong>${b.workshop.name}</strong>
            <span class="badge ok">${b.status}</span>
          </div>
          <div class="sub" style="margin-top:4px;">${vLabel} • ${b.preferred_slot}</div>
        </div>
      `;
    })
    .join("");
}

// --- SHARE CARD ---
async function loadShareCard() {
  const vid = document.getElementById("shareVehicleSelect").value;
  if (!vid) return;
  const masked = document.getElementById("shareMask").checked;
  const res = await fetch(`${API}/api/v1/share-card/${encodeURIComponent(vid)}?hide_sensitive=${masked}`);
  if (!res.ok) return;
  const d = await res.json();
  state.latestShareText = d.share_text;
  document.getElementById("shareScore").textContent = `${d.readiness_score}%`;
  document.getElementById("shareBadge").textContent = d.readiness_badge;
  document.getElementById("shareVehTitle").textContent = d.vehicle_label;
  document.getElementById("shareTripText").textContent = d.trip_summary;
  document.getElementById("shareHighlights").innerHTML = d.highlights.map((h) => `<li>${h}</li>`).join("");
}

async function shareNow() {
  if (!state.latestShareText) return;
  if (navigator.share) {
    try {
      await navigator.share({ title: "TripGuard Readiness Card", text: state.latestShareText });
      return;
    } catch {}
  }
  await navigator.clipboard?.writeText(state.latestShareText);
  alert("Copied share card summary:\n\n" + state.latestShareText);
}

init();