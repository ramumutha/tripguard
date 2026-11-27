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
  const tripDateEl = document.getElementById("tripDate");
  const hDateEl = document.getElementById("hDate");
  if (tripDateEl) tripDateEl.value = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
  if (hDateEl) hDateEl.value = new Date().toISOString().slice(0, 10);

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

function setupOcrUploaders() {
  const rcInput = document.getElementById("mRcFile");
  const plateInput = document.getElementById("mPlateFile");

  if (plateInput) {
    plateInput.addEventListener("change", async (e) => {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      showImagePreview(file, "mPlatePreview");
      setOcrStatus("mPlateStatus", `Selected "${file.name}" — detecting plate...`, "info");
      await extractFromPlateImage(file);
    });
  }

  if (rcInput) {
    rcInput.addEventListener("change", async (e) => {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      showImagePreview(file, "mRcPreview");
      setOcrStatus("mRcStatus", `Selected "${file.name}" — scanning RC...`, "info");
      await extractFromRcImage(file);
    });
  }
}

function setOcrStatus(elId, msg, type = "info") {
  const el = document.getElementById(elId);
  if (!el) return;
  const color = type === "ok" ? "#166534" : type === "warn" ? "#92400e" : "#1d4ed8";
  el.style.color = color;
  el.style.fontWeight = "600";
  el.textContent = msg;
}

function showImagePreview(file, containerId) {
  const el = document.getElementById(containerId);
  if (!el) return;
  const reader = new FileReader();
  reader.onload = () => {
    el.innerHTML = `<img src="${reader.result}" alt="Preview" style="width:100%; max-height:96px; object-fit:contain; background:#f8fafc; border-radius:8px; border:1px solid #cbd5e1;" />`;
  };
  reader.readAsDataURL(file);
}

function formatIndianPlate(raw) {
  const upper = (raw || "").toUpperCase();
  const compact = upper.replace(/[^A-Z0-9]/g, " ");
  const indMatch = compact.match(/\b([A-Z]{2})\s*0?([0-9]{1,2})\s*([A-Z]{1,3})\s*([0-9]{3,4})\b/);
  if (indMatch) {
    return `${indMatch[1]} ${indMatch[2].padStart(2, "0")} ${indMatch[3]} ${indMatch[4]}`;
  }
  const alnum = upper.replace(/[^A-Z0-9]/g, "");
  const fallback = alnum.match(/[A-Z]{2}[0-9]{1,2}[A-Z]{1,3}[0-9]{3,4}/);
  return fallback ? fallback[0] : alnum;
}

async function callBackendPlateRecognizer(file) {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch(`${API}/api/v1/ocr/plate`, {
    method: "POST",
    body: form
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || `Plate API status ${res.status}`);
  }
  return await res.json();
}

function createCroppedBlob(img, sxRatio, syRatio, swRatio, shRatio) {
  return new Promise((resolve) => {
    try {
      const sx = Math.max(0, Math.round(img.width * sxRatio));
      const sy = Math.max(0, Math.round(img.height * syRatio));
      const sw = Math.max(10, Math.round(img.width * swRatio));
      const sh = Math.max(10, Math.round(img.height * shRatio));
      const scale = Math.min(4, Math.max(2, 1200 / sw));
      const canvas = document.createElement("canvas");
      canvas.width = Math.round(sw * scale);
      canvas.height = Math.round(sh * scale);
      const ctx = canvas.getContext("2d");
      ctx.filter = "grayscale(1) contrast(1.6)";
      ctx.drawImage(img, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height);
      canvas.toBlob((blob) => resolve(blob), "image/png");
    } catch {
      resolve(null);
    }
  });
}

async function runFallbackTesseract(file) {
  if (!window.Tesseract || typeof window.Tesseract.recognize !== "function") {
    return "";
  }
  const dataUrl = await new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
  const img = await new Promise((resolve, reject) => {
    const im = new Image();
    im.onload = () => resolve(im);
    im.onerror = reject;
    im.src = dataUrl;
  });

  const crops = [
    await createCroppedBlob(img, 0.2, 0.25, 0.6, 0.45),
    await createCroppedBlob(img, 0.15, 0.45, 0.7, 0.5),
    file
  ].filter(Boolean);

  for (const crop of crops) {
    const { data } = await window.Tesseract.recognize(crop, "eng");
    const plate = formatIndianPlate(data.text || "");
    if (plate && /^[A-Z]{2}\s?\d{2}\s?[A-Z]{1,3}\s?\d{3,4}$/.test(plate)) {
      return plate;
    }
  }
  return "";
}

async function extractFromPlateImage(file) {
  const plateInput = document.getElementById("mScanPlate");
  const makeEl = document.getElementById("mMake");

  try {
    const apiResult = await callBackendPlateRecognizer(file);
    if (apiResult && apiResult.plate) {
      const formatted = formatIndianPlate(apiResult.plate);
      if (plateInput) plateInput.value = formatted;
      const rcInput = document.getElementById("mRcPlate");
      if (rcInput && !rcInput.value.trim()) rcInput.value = formatted;
      if (makeEl && !makeEl.value.trim() && apiResult.make) {
        makeEl.value = apiResult.make;
      }
      const pct = apiResult.score ? ` (${Math.round(apiResult.score * 100)}% match)` : "";
      setOcrStatus("mPlateStatus", `Detected plate: ${formatted}${pct}`, "ok");
      return;
    }
  } catch (apiErr) {
    console.info("[TripGuard Plate API fallback]:", apiErr.message);
  }

  try {
    const fallbackPlate = await runFallbackTesseract(file);
    if (fallbackPlate) {
      if (plateInput) plateInput.value = fallbackPlate;
      setOcrStatus("mPlateStatus", `Detected plate (local scan): ${fallbackPlate} — verify`, "ok");
    } else {
      setOcrStatus("mPlateStatus", "Configure Plate Recognizer token or enter plate manually.", "warn");
    }
  } catch {
    setOcrStatus("mPlateStatus", "Could not auto-read plate — please type number manually.", "warn");
  }
}

async function extractFromRcImage(file) {
  const rcInput = document.getElementById("mRcPlate");
  try {
    const plate = await runFallbackTesseract(file);
    if (plate && rcInput) {
      rcInput.value = plate;
      setOcrStatus("mRcStatus", `Extracted RC number: ${plate} — verify details`, "ok");
    } else {
      setOcrStatus("mRcStatus", "Could not read RC cleanly — please enter details manually.", "warn");
    }
  } catch {
    setOcrStatus("mRcStatus", "Could not read RC cleanly — please enter details manually.", "warn");
  }
}

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

function goTab(tab) {
  document.querySelectorAll(".screen").forEach((s) => s.classList.remove("active"));
  document.getElementById(`screen-${tab}`).classList.add("active");
  document.querySelectorAll(".nav-btn").forEach((b) => {
    b.classList.toggle("active", b.dataset.tab === tab);
  });
  if (tab === "service") renderWorkshopsForSelected();
  if (tab === "share") loadShareCard();
}

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

// --- RC & Plate OCR enhancement (auto-appended) ---
const KNOWN_MAKES = [
  "HYUNDAI", "MARUTI SUZUKI", "MARUTI", "SUZUKI", "TATA", "MAHINDRA",
  "HONDA", "TOYOTA", "KIA", "MG", "VOLKSWAGEN", "SKODA", "RENAULT",
  "NISSAN", "FORD", "JEEP", "ROYAL ENFIELD", "HERO", "BAJAJ", "TVS",
  "ATHER", "OLA", "YAMAHA"
];

const KNOWN_MODELS = [
  "I20", "GRAND I10", "NIOS", "VENUE", "CRETA", "VERNA", "EXTER", "AURA",
  "SWIFT", "BALENO", "DZIRE", "BREZZA", "ERTIGA", "ALTO", "WAGON R", "FRONX",
  "NEXON", "PUNCH", "HARRIER", "SAFARI", "ALTROZ", "TIAGO", "TIGOR",
  "THAR", "SCORPIO", "XUV700", "XUV300", "XUV 3XO", "BOLERO",
  "CITY", "AMAZE", "ELEVATE", "INNOVA", "FORTUNER", "GLANZA", "URBAN CRUISER",
  "SELTOS", "SONET", "CARENS", "HECTOR", "ASTOR", "COMET",
  "VIRTUS", "TAIGUN", "POLO", "SLAVIA", "KUSHAQ", "KIGER", "MAGNITE"
];

let _plateApiConfiguredCache = null;

async function isPlateApiConfigured() {
  if (_plateApiConfiguredCache !== null) return _plateApiConfiguredCache;
  try {
    const r = await fetch(`${API}/health`);
    if (!r.ok) return false;
    const data = await r.json();
    _plateApiConfiguredCache = Boolean(data && data.plate_recognizer_configured);
    return _plateApiConfiguredCache;
  } catch {
    return false;
  }
}

function parseRcFieldsFromText(rawText) {
  const upper = (rawText || "").toUpperCase();
  const cleanText = upper.replace(/[^A-Z0-9\s./-]/g, " ");
  const out = {
    plate: formatIndianPlate(upper),
    make: "",
    model: "",
    year: "",
    fuel: ""
  };

  if (out.plate && !/^[A-Z]{2}\s?\d{2}\s?[A-Z]{1,3}\s?\d{3,4}$/.test(out.plate)) {
    out.plate = "";
  }

  // Fuel detection
  if (/\b(ELECTRIC|EV|BOV|BATTERY)\b/.test(cleanText)) {
    out.fuel = "ELECTRIC";
  } else if (/\bCNG\b/.test(cleanText)) {
    out.fuel = "CNG";
  } else if (/\bDIESEL\b/.test(cleanText)) {
    out.fuel = "DIESEL";
  } else if (/\bHYBRID\b/.test(cleanText)) {
    out.fuel = "HYBRID";
  } else if (/\bPETROL\b/.test(cleanText)) {
    out.fuel = "PETROL";
  }

  // Year detection (4-digit year between 2000 and 2026)
  const years = cleanText.match(/\b(20[0-2][0-9])\b/g);
  if (years && years.length > 0) {
    const validYears = years.map(Number).filter((y) => y >= 2000 && y <= 2026);
    if (validYears.length > 0) {
      out.year = String(validYears[validYears.length - 1]);
    }
  }

  // Make detection
  for (const mk of KNOWN_MAKES) {
    if (cleanText.includes(mk)) {
      out.make = mk === "MARUTI" || mk === "SUZUKI" ? "Maruti Suzuki" : toTitleCase(mk);
      break;
    }
  }

  // Model detection
  for (const mdl of KNOWN_MODELS) {
    const escaped = mdl.replace(/\s+/g, "\\s+");
    if (new RegExp(`\\b${escaped}\\b`, "i").test(cleanText)) {
      out.model = mdl.length <= 3 ? mdl.toUpperCase() : toTitleCase(mdl);
      break;
    }
  }

  return out;
}

function toTitleCase(str) {
  return (str || "")
    .toLowerCase()
    .replace(/\b([a-z])/g, (_, ch) => ch.toUpperCase());
}

async function runFullImageOcrText(file) {
  if (!window.Tesseract || typeof window.Tesseract.recognize !== "function") {
    return "";
  }
  const { data } = await window.Tesseract.recognize(file, "eng");
  return (data && data.text) || "";
}

// Override extractFromRcImage & extractFromPlateImage with enhanced versions
extractFromRcImage = async function (file) {
  const rcInput = document.getElementById("mRcPlate");
  const scanPlateInput = document.getElementById("mScanPlate");
  const makeEl = document.getElementById("mMake");
  const modelEl = document.getElementById("mModel");
  const yearEl = document.getElementById("mYear");
  const fuelEl = document.getElementById("mFuel");

  try {
    const [fullText, plateCrop] = await Promise.all([
      runFullImageOcrText(file).catch(() => ""),
      runFallbackTesseract(file).catch(() => "")
    ]);

    const parsed = parseRcFieldsFromText(fullText);
    const detectedPlate = parsed.plate || plateCrop;
    const filled = [];

    if (detectedPlate) {
      if (rcInput) rcInput.value = detectedPlate;
      if (scanPlateInput && !scanPlateInput.value.trim()) {
        scanPlateInput.value = detectedPlate;
      }
      filled.push(`RC ${detectedPlate}`);
    }

    if (parsed.make && makeEl && !makeEl.value.trim()) {
      makeEl.value = parsed.make;
      filled.push(`Make ${parsed.make}`);
    }

    if (parsed.model && modelEl && !modelEl.value.trim()) {
      modelEl.value = parsed.model;
      filled.push(`Model ${parsed.model}`);
    }

    if (parsed.year && yearEl) {
      yearEl.value = parsed.year;
      filled.push(`Year ${parsed.year}`);
    }

    if (parsed.fuel && fuelEl) {
      const hasOption = Array.from(fuelEl.options || []).some((o) => o.value === parsed.fuel);
      if (hasOption) {
        fuelEl.value = parsed.fuel;
        filled.push(`Fuel ${parsed.fuel}`);
      }
    }

    if (filled.length > 0) {
      setOcrStatus("mRcStatus", `Extracted ${filled.join(" • ")} — please verify`, "ok");
    } else {
      setOcrStatus("mRcStatus", "Could not read RC fields clearly — please enter details manually.", "warn");
    }
  } catch {
    setOcrStatus("mRcStatus", "Could not read RC cleanly — please enter details manually.", "warn");
  }
};

extractFromPlateImage = async function (file) {
  const plateInput = document.getElementById("mScanPlate");
  const makeEl = document.getElementById("mMake");
  const rcInput = document.getElementById("mRcPlate");

  const apiReady = await isPlateApiConfigured();
  if (apiReady) {
    try {
      const apiResult = await callBackendPlateRecognizer(file);
      if (apiResult && apiResult.plate) {
        const formatted = formatIndianPlate(apiResult.plate);
        if (plateInput) plateInput.value = formatted;
        if (rcInput && !rcInput.value.trim()) rcInput.value = formatted;
        if (makeEl && !makeEl.value.trim() && apiResult.make) {
          makeEl.value = apiResult.make;
        }
        const pct = apiResult.score ? ` (${Math.round(apiResult.score * 100)}% match)` : "";
        setOcrStatus("mPlateStatus", `Detected plate: ${formatted}${pct}`, "ok");
        return;
      }
    } catch (apiErr) {
      console.info("[TripGuard Plate API fallback]:", apiErr.message);
    }
  }

  try {
    const fallbackPlate = await runFallbackTesseract(file);
    if (fallbackPlate) {
      if (plateInput) plateInput.value = fallbackPlate;
      if (rcInput && !rcInput.value.trim()) rcInput.value = fallbackPlate;
      setOcrStatus("mPlateStatus", `Detected plate (local scan): ${fallbackPlate} — verify`, "ok");
    } else if (rcInput && rcInput.value.trim()) {
      const fromRc = formatIndianPlate(rcInput.value.trim());
      if (plateInput && !plateInput.value.trim()) plateInput.value = fromRc;
      setOcrStatus("mPlateStatus", `Using RC plate ${fromRc} (no separate plate API needed) — verify`, "ok");
    } else {
      setOcrStatus("mPlateStatus", "Enter plate number manually or upload RC photo.", "warn");
    }
  } catch {
    setOcrStatus("mPlateStatus", "Could not auto-read plate — please type number manually.", "warn");
  }
};
