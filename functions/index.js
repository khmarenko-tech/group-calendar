const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

exports.pushInbox = onDocumentCreated("users/{uid}/inbox/{noticeId}", async (event) => {
  const data = event.data?.data();
  if (!data) return;

  const uid = event.params.uid;
  const tokensSnap = await getFirestore().collection(`users/${uid}/tokens`).get();
  const tokens = tokensSnap.docs.map((doc) => doc.id).filter(Boolean);
  if (tokens.length === 0) return;

  const response = await getMessaging().sendEachForMulticast({
    tokens,
    notification: {
      title: String(data.title || "Календарь группы"),
      body: String(data.body || ""),
    },
    data: {
      groupId: String(data.groupId || ""),
      concertId: String(data.concertId || ""),
    },
    android: {
      priority: "high",
      notification: {
        channelId: "sostav",
      },
    },
  });

  const batch = getFirestore().batch();
  let deletes = 0;
  response.responses.forEach((result, index) => {
    if (result.success) return;
    const code = result.error?.code || "";
    if (
      code.includes("registration-token-not-registered") ||
      code.includes("invalid-registration-token")
    ) {
      batch.delete(tokensSnap.docs[index].ref);
      deletes += 1;
    }
  });
  if (deletes > 0) await batch.commit();
});
