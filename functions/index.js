// NOVA GHOST FAMILY — server-side authority. All membership/permission checks live here and in firestore.rules.
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated, onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

function requireAuth(req) {
  if (!req.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  return req.auth.uid;
}
async function getRoom(roomId) {
  const snap = await db.collection("rooms").doc(String(roomId)).get();
  if (!snap.exists) throw new HttpsError("not-found", "Room not found.");
  return snap;
}
function audit(roomId, actorUid, action, target) {
  return db.collection("rooms").doc(roomId).collection("audit").add({
    actorUid, action, target, at: admin.firestore.FieldValue.serverTimestamp(),
  });
}

exports.createInvite = onCall(async (req) => {
  const uid = requireAuth(req);
  const room = await getRoom(req.data.roomId);
  if (!room.data().parents.includes(uid)) throw new HttpsError("permission-denied", "Parents only.");
  const ttl = Math.min(Math.max(parseInt(req.data.ttlMinutes) || 15, 1), 60);
  let code = "";
  for (let i = 0; i < 8; i++) code += ALPHABET[crypto.randomInt(ALPHABET.length)];
  const expiresAt = Date.now() + ttl * 60000;
  await db.collection("invites").doc(code).set({ roomId: room.id, createdBy: uid, expiresAt, used: false });
  await audit(room.id, uid, "invite.create", code.slice(0, 2) + "******");
  return { code, expiresAt };
});

exports.redeemInvite = onCall(async (req) => {
  const uid = requireAuth(req);
  const code = String(req.data.code || "").toUpperCase();
  const name = String(req.data.deviceName || "Child device").slice(0, 40);
  const userSnap = await db.collection("users").doc(uid).get();
  if (!userSnap.exists || userSnap.data().role !== "CHILD") throw new HttpsError("permission-denied", "Only child accounts can join.");
  const invRef = db.collection("invites").doc(code);
  const result = await db.runTransaction(async (tx) => {
    const inv = await tx.get(invRef);
    if (!inv.exists || inv.data().used || inv.data().expiresAt < Date.now())
      throw new HttpsError("failed-precondition", "Invitation is invalid or expired.");
    const roomRef = db.collection("rooms").doc(inv.data().roomId);
    const room = await tx.get(roomRef);
    if (!room.exists) throw new HttpsError("not-found", "Room no longer exists.");
    if (room.data().parents.includes(uid)) throw new HttpsError("failed-precondition", "A parent cannot join as a child.");
    const devRef = roomRef.collection("devices").doc();
    tx.update(roomRef, { children: admin.firestore.FieldValue.arrayUnion(uid) });
    tx.set(devRef, {
      childUid: uid, name, battery: -1, charging: false, lastSeen: null,
      consent: { usageReports: false, reminders: false, downtime: false, screenShare: false },
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });
    tx.update(invRef, { used: true, usedBy: uid });
    return { roomId: roomRef.id, deviceId: devRef.id, roomName: room.data().name };
  });
  await audit(result.roomId, uid, "device.join", result.deviceId);
  return result;
});

async function deleteDeviceData(roomRef, deviceId) {
  const usage = await roomRef.collection("usage").where("deviceId", "==", deviceId).get();
  const batch = db.batch();
  usage.docs.forEach((d) => batch.delete(d.ref));
  batch.delete(roomRef.collection("policies").doc(deviceId));
  batch.delete(roomRef.collection("tokens").doc(deviceId));
  batch.delete(roomRef.collection("devices").doc(deviceId));
  await batch.commit();
  await db.recursiveDelete(roomRef.collection("shares").doc(deviceId));
  const rem = await roomRef.collection("reminders").where("deviceId", "==", deviceId).get();
  await Promise.all(rem.docs.map((d) => d.ref.delete()));
}

exports.unlinkDevice = onCall(async (req) => {
  const uid = requireAuth(req);
  const room = await getRoom(req.data.roomId);
  const devRef = room.ref.collection("devices").doc(String(req.data.deviceId));
  const dev = await devRef.get();
  if (!dev.exists) throw new HttpsError("not-found", "Device not found.");
  const isParent = room.data().parents.includes(uid);
  const isOwnerChild = dev.data().childUid === uid;
  if (!isParent && !isOwnerChild) throw new HttpsError("permission-denied", "Not allowed.");
  const childUid = dev.data().childUid;
  await deleteDeviceData(room.ref, devRef.id);
  const others = await room.ref.collection("devices").where("childUid", "==", childUid).limit(1).get();
  if (others.empty) await room.ref.update({ children: admin.firestore.FieldValue.arrayRemove(childUid) });
  await audit(room.id, uid, "device.unlink", devRef.id);
  return { ok: true };
});

exports.deleteRoom = onCall(async (req) => {
  const uid = requireAuth(req);
  const room = await getRoom(req.data.roomId);
  if (room.data().ownerUid !== uid) throw new HttpsError("permission-denied", "Only the room owner can delete it.");
  const inv = await db.collection("invites").where("roomId", "==", room.id).get();
  await Promise.all(inv.docs.map((d) => d.ref.delete()));
  await db.recursiveDelete(room.ref);
  return { ok: true };
});

async function dispatch(reminderSnap) {
  const r = reminderSnap.data();
  const roomRef = reminderSnap.ref.parent.parent;
  const dev = await roomRef.collection("devices").doc(r.deviceId).get();
  if (!dev.exists || !(dev.data().consent && dev.data().consent.reminders)) {
    return reminderSnap.ref.update({ status: "blocked" });
  }
  const tok = await roomRef.collection("tokens").doc(r.deviceId).get();
  if (!tok.exists) return reminderSnap.ref.update({ status: "failed" });
  try {
    await admin.messaging().send({
      token: tok.data().token,
      android: { priority: "high" },
      data: { type: "reminder", reminderId: reminderSnap.id, message: String(r.message), roomId: roomRef.id },
    });
    return reminderSnap.ref.update({ status: "sent" });
  } catch (e) {
    return reminderSnap.ref.update({ status: "failed" });
  }
}

exports.onReminderCreated = onDocumentCreated("rooms/{roomId}/reminders/{id}", async (event) => {
  const snap = event.data;
  if (snap.data().status === "pending" && snap.data().scheduledAt.toMillis() <= Date.now() + 5000) await dispatch(snap);
});

exports.dispatchScheduled = onSchedule("every 1 minutes", async () => {
  const due = await db.collectionGroup("reminders")
    .where("status", "==", "pending").where("scheduledAt", "<=", admin.firestore.Timestamp.now()).limit(200).get();
  await Promise.all(due.docs.map(dispatch));
});

exports.onShareRequested = onDocumentWritten("rooms/{roomId}/shares/{deviceId}", async (event) => {
  const after = event.data.after.exists ? event.data.after.data() : null;
  const before = event.data.before.exists ? event.data.before.data() : null;
  if (!after || after.status !== "requested" || (before && before.status === "requested")) return;
  const roomRef = db.collection("rooms").doc(event.params.roomId);
  const dev = await roomRef.collection("devices").doc(event.params.deviceId).get();
  if (!dev.exists || !(dev.data().consent && dev.data().consent.screenShare)) {
    return event.data.after.ref.set({ status: "declined" }, { merge: true });
  }
  const tok = await roomRef.collection("tokens").doc(event.params.deviceId).get();
  if (tok.exists) {
    await admin.messaging().send({ token: tok.data().token, android: { priority: "high" }, data: { type: "share_request" } });
  }
});
