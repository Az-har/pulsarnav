// Protocol & parser reference verification script (matches OtcPacketBuilder.kt & GoogleMapsNotificationParser.kt)
const assert = require("assert");

const Maneuver = {
  WRONG_WAY: 66,           // 'B'
  TURN_SLIGHT_LEFT: 67,    // 'C'
  TURN_SLIGHT_RIGHT: 68,   // 'D'
  TURN_SHARP_LEFT: 69,     // 'E'
  TURN_SHARP_RIGHT: 70,    // 'F'
  STRAIGHT: 71,            // 'G'
  DESTINATION_REACHED: 72, // 'H'
  TURN_LEFT: 73,           // 'I'
  TURN_RIGHT: 74,          // 'J'
  RAMP_LEFT: 75,           // 'K'
  RAMP_RIGHT: 76,          // 'L'
  ROUNDABOUT_RIGHT: 78,    // 'N'
  U_TURN_LEFT: 79,         // 'O'
  U_TURN_RIGHT: 80,        // 'P'
  FORK_LEFT: 81,           // 'Q'
  FORK_RIGHT: 82,          // 'R'
  FERRY_TRAIN: 84,         // 'T'
  ROUNDABOUT_LEFT: 85,     // 'U'
  MERGE: 86,               // 'V'
  KEEP_RIGHT: 88,          // 'X'
  FERRY: 89,               // 'Y'
  KEEP_LEFT: 90,           // 'Z'
};

function transliterateAscii(str) {
  if (!str) return "";
  return str.normalize("NFD").replace(/[\u0300-\u036f]/g, "");
}

function normalizeStreetName(str) {
  let clean = transliterateAscii(str)
    .replace(/[^0-9a-zA-Z.]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (clean.length > 31) {
    clean = clean.substring(0, 30).trimEnd() + ".";
  }
  return clean;
}

function formatDistance(d) {
  const clamped = Math.max(0, d);
  let unit, i, frac;
  if (clamped < 999.5) {
    unit = 1;
    i = Math.round(clamped);
    frac = 0;
  } else {
    unit = 0;
    const totalHundredths = Math.round((clamped / 1000.0) * 100.0);
    i = Math.floor(totalHundredths / 100);
    frac = totalHundredths % 100;
  }
  return [
    unit & 0xff,
    (i >> 8) & 0xff,
    i & 0xff,
    (frac >> 8) & 0xff,
    frac & 0xff,
  ];
}

function buildOtcTbtFrame({
  type = 1,
  maneuverIcon,
  isAm = true,
  stepMeters,
  totalMeters,
  etaHour = 12,
  etaMinute = 0,
  roundaboutExit = 0,
  streetText = "",
}) {
  const b = Buffer.alloc(48, 0);
  const step = formatDistance(Math.max(0, stepMeters));
  const total = formatDistance(Math.max(0, totalMeters));
  const cleanStreet = normalizeStreetName(streetText);

  let b0 = type & 0x01;
  b0 |= (step[0] & 0x01) << 4;
  b0 |= (isAm ? 0 : 1) << 7;
  b[0] = b0 & 0xff;

  let icon = maneuverIcon;
  if (type !== 0 && stepMeters >= 0 && stepMeters <= 99 && icon > 0 && icon !== Maneuver.DESTINATION_REACHED && icon < 98) {
    icon += 32;
  }
  b[1] = icon & 0xff;

  b[2] = step[4];
  b[3] = step[3];
  b[4] = step[2];
  b[5] = step[1];
  b[6] = Math.max(0, Math.min(59, etaMinute)) & 0xff;

  const validHour = type === 0 ? Math.max(0, Math.min(12, etaHour)) : (etaHour >= 1 && etaHour <= 12 ? etaHour : 12);
  b[7] = ((validHour & 0x0f) | ((roundaboutExit & 0x0f) << 4)) & 0xff;

  b[8] = total[4];
  b[9] = total[3];
  b[10] = total[2];
  b[11] = total[1];

  const gpsActive = type === 0 ? 0 : 1;
  b[12] = ((total[0] & 0x01) | (gpsActive << 2)) & 0xff;
  b[13] = 0;

  if (cleanStreet.length > 0) {
    const streetBytes = Buffer.from(cleanStreet, "ascii");
    const n = Math.min(streetBytes.length, 31);
    b[14] = n & 0xff;
    streetBytes.copy(b, 15, 0, n);
  }

  let sum = 0;
  for (let idx = 0; idx < 47; idx++) {
    sum = (sum + b[idx]) & 0xff;
  }
  b[47] = sum;
  return b;
}

function parseDistanceMeters(raw) {
  if (!raw) return -1;
  const m = raw.match(/(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:[.,]\d+)?)\s*(km|mi|ft|yd|m)\b/i);
  if (!m) return -1;
  const rawNum = m[1];
  const numStr = /^\d{1,3}(?:,\d{3})+(?:\.\d+)?$/.test(rawNum) ? rawNum.replace(/,/g, "") : rawNum.replace(",", ".");
  const val = parseFloat(numStr);
  const unit = m[2].toLowerCase();
  if (unit === "m") return Math.floor(val);
  if (unit === "km") return Math.floor(val * 1000);
  if (unit === "mi") return Math.floor(val * 1609.34);
  if (unit === "ft") return Math.floor(val * 0.3048);
  if (unit === "yd") return Math.floor(val * 0.9144);
  return -1;
}

function parseGoogleMapsNotification(title, text, subtext = "") {
  let stepDist = parseDistanceMeters(title);
  if (stepDist < 0) stepDist = parseDistanceMeters(text);
  const clampedStep = Math.max(0, stepDist);

  let totalDist = parseDistanceMeters(subtext);
  if (totalDist < 0) totalDist = clampedStep;

  const s = `${title || ""} ${text || ""}`.toLowerCase();
  let opcode = Maneuver.STRAIGHT;
  let roundaboutExit = 0;
  const hasTurn = /turn left|turn right|keep left|keep right|slight|sharp|fork|ramp|roundabout|rotary|u-turn|uturn/.test(s);
  const explicitArrived = /you have arrived|you've arrived|arrived at destination|your destination is/.test(s);
  if (explicitArrived || (!hasTurn && stepDist >= 0 && stepDist <= 25 && /(your destination|arrived|reached)/.test(s))) {
    opcode = Maneuver.DESTINATION_REACHED;
  } else if (/u-turn|uturn/.test(s)) {
    opcode = /right/.test(s) ? Maneuver.U_TURN_RIGHT : Maneuver.U_TURN_LEFT;
  } else if (/roundabout|rotary/.test(s)) {
    opcode = /\bleft\b/.test(s) ? Maneuver.ROUNDABOUT_LEFT : Maneuver.ROUNDABOUT_RIGHT;
    const ex = s.match(/(\d+)(?:st|nd|rd|th)?\s+exit|exit\s+(\d+)/i);
    if (ex) roundaboutExit = parseInt(ex[1] || ex[2], 10);
  } else if (/turn left|left onto/.test(s)) {
    opcode = Maneuver.TURN_LEFT;
  } else if (/turn right|right onto/.test(s)) {
    opcode = Maneuver.TURN_RIGHT;
  } else if (/keep right/.test(s)) {
    opcode = Maneuver.KEEP_RIGHT;
  } else if (/keep left/.test(s)) {
    opcode = Maneuver.KEEP_LEFT;
  }

  return { opcode, distanceMeters: clampedStep, totalDistanceMeters: totalDist, roundaboutExit, streetName: normalizeStreetName(text || "") };
}

console.log("=== RUNNING PROTOCOL & PARSER VERIFICATION ===");

const p1 = parseGoogleMapsNotification("1,200 m", "Turn left onto Outer Ring Rd", "15 km");
assert.strictEqual(p1.opcode, Maneuver.TURN_LEFT);
assert.strictEqual(p1.distanceMeters, 1200);

const p2 = parseGoogleMapsNotification("1,2 km", "Keep right toward Airport Rd", "8,5 km");
assert.strictEqual(p2.opcode, Maneuver.KEEP_RIGHT);
assert.strictEqual(p2.distanceMeters, 1200);
assert.strictEqual(p2.totalDistanceMeters, 8500);

const p3 = parseGoogleMapsNotification("500 m", "Take the 2nd exit at the roundabout", "3 km");
assert.strictEqual(p3.opcode, Maneuver.ROUNDABOUT_RIGHT);
assert.strictEqual(p3.roundaboutExit, 2);

const p4 = parseGoogleMapsNotification("You have arrived", "Your destination is on the right", "0 m");
assert.strictEqual(p4.opcode, Maneuver.DESTINATION_REACHED);

const p5 = parseGoogleMapsNotification("Outer Ring Rd", "Turn left onto Destination Rd", "5 km");
assert.strictEqual(p5.opcode, Maneuver.TURN_LEFT);

const frame = buildOtcTbtFrame({
  type: 1,
  maneuverIcon: Maneuver.TURN_LEFT,
  isAm: true,
  stepMeters: 80,
  totalMeters: 1400,
  etaHour: 9,
  etaMinute: 30,
  streetText: "Avenida José"
});
assert.strictEqual(frame.length, 48);
assert.strictEqual(frame[1], Maneuver.TURN_LEFT + 32); // blink <100m
let sum = 0;
for (let i = 0; i < 47; i++) sum = (sum + frame[i]) & 0xff;
assert.strictEqual(frame[47], sum);

console.log("All reference protocol & parser checks passed.");
