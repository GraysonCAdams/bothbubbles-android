# Plan: Instant (Optimistic) Message Sending + Idempotent Reconciliation

**Last Updated:** December 15, 2025  
**Status:** Design + Implementation Plan (no code changes yet)  
**Scope:** Outgoing message UX latency, offline queue durability, send state UI (sending/delivered/failed), and dedupe across Socket/FCM/SMS/Room.

---

## 0) Goals / Non‑Goals

### Goals

- **Zero/near‑zero perceived latency** between hitting send and seeing the outgoing bubble.
- **Single, consistent timeline** for outgoing bubbles (text + attachments) that supports:
  - `sending → sent → delivered → read` and `failed`.
  - retry/cancel.
- **Offline-first durability**: pending sends survive navigation away, process death, device reboot.
- **Idempotent ingestion** from _all_ inbound sources (socket, FCM-triggered sync, SMS provider), preventing duplicates.
- Preserve existing paging/rendering performance characteristics (Compose + sparse paging + stable keys).

### Non‑Goals

- Redesigning the chat UI/UX.
- Changing server APIs.
- Rewriting the paging system.

---

## 1) Current State (as of today)

### Rendering

- Chat list renders only `ChatUiState.messages` (from `RoomMessageDataSource` → `messages` table).
- `ChatUiState.queuedMessages` (from `pending_messages`) is currently used for `SendingIndicatorBar`, not rendered as bubbles.

### Sending

- Primary send entry: `ChatSendDelegate.sendMessage()`.
- Today it **queues** via `PendingMessageRepository.queueMessage()` → inserts into `pending_messages` / `pending_attachments` and enqueues `MessageSendWorker`.
- `MessageSendWorker` later calls `MessageSendingService.sendUnified(..., tempGuid = pendingMessage.localId)`.

### Optimistic UI already exists (but only after WorkManager starts)

- `MessageSendingService.sendMessage()` and `sendIMessageWithAttachments()` already create **temporary records** in the `messages` and `attachments` tables keyed by `tempGuid`.
- Problem: that optimistic insert happens when the worker runs, not immediately when user presses send.

### Key pain

- WorkManager scheduling and startup adds **visible latency**, so the message bubble appears late.

---

## 2) Proposed Architecture: One Canonical Timeline

### Principle: DB is the UI source of truth; events are triggers

- UI renders **only** the `messages` table timeline (existing paging architecture).
- `pending_messages` remains the **durability / retry engine**, not the UI bubble source.

### New rule

- When the user presses send, we immediately create a **local echo** in `messages` (+ `attachments` if any), using a stable client-generated ID.
- Sending proceeds asynchronously (direct send or WorkManager), and we update/replace the local echo as we learn more.

### Identity & correlation

- Use a stable client id, referenced below as `clientGuid`.
- For iMessage/server sends: `clientGuid` is passed as API `tempGuid`.
- For SMS/MMS: `clientGuid` is stored and later correlated to the provider’s `smsId/mmsId` (preferred) or the created message row.

**Important:** Today `PendingMessageRepository` creates `localId = "pending-..."`.

- `MessageEntity.isSent` currently treats non-`temp-` as “sent”.
- Plan: **standardize** on `clientGuid` starting with `temp-` (or update the isSent heuristic).

---

## 3) Send State Model (How the UI distinguishes states)

### Minimal, derived state (no schema change required)

Derive an outgoing send state from existing message/attachment fields:

- **SENDING**
  - `message.guid.startsWith("temp-") && !message.hasError`
  - attachments may be `UPLOADING`/`WAITING`.
- **FAILED**
  - `message.hasError == true` OR any outgoing attachment `transferState == FAILED`.
- **SENT**
  - `!guid.startsWith("temp-") && !hasError`.
- **DELIVERED / READ**
  - derived from existing `dateDelivered` / `dateRead` mapping.

### UI behavior

- The bubble content is the same.
- Only the last outgoing message shows a status indicator:
  - Sending → small spinner/clock.
  - Sent/Delivered/Read → existing delivery indicator.
  - Failed → error badge + retry.

**Where:** `ChatScreen.kt` already computes `lastOutgoingIndex` and `showDeliveryIndicator`. We will extend it:

```kotlin
val isSending = message.isFromMe && message.guid.startsWith("temp-") && !message.hasError
val showSendingIndicator = message.isFromMe && index == lastOutgoingIndex && isSending

val showDeliveryIndicator = message.isFromMe && index == lastOutgoingIndex &&
	(!message.guid.startsWith("temp-") || message.hasError) // sent/failed
```

(Exact UI component decisions remain unchanged; this is just state derivation.)

---

## 4) Implementation Strategy (Phased)

### Phase A — Instant bubble on send (without breaking offline-first)

**Key move:** create the local echo at queue-time, not worker-time.

#### A1. Update `PendingMessageRepository.queueMessage()` to also insert local echo

- After inserting into `pending_messages` and persisting attachments, insert:
  - `MessageEntity(guid = clientGuid, …, isFromMe = true, messageSource = …, error=0)` into `messages`.
  - If attachments exist:
    - Insert `AttachmentEntity` rows linked to the temp message with `transferState = UPLOADING` (or `WAITING`) and `localPath` pointing to the persisted file.

Pseudo-code:

```kotlin
suspend fun queueMessage(...): Result<String> = runCatching {
	val clientGuid = "temp-${UUID.randomUUID()}" // replace current "pending-"

	val pendingId = pendingMessageDao.insert(PendingMessageEntity(localId = clientGuid, ...))

	val persisted = persistAttachmentsToInternalStorage(clientGuid, attachments)
	pendingAttachmentDao.insertAll(persisted)

	// NEW: local echo in canonical timeline
	messageDao.insertMessage(
		MessageEntity(
			guid = clientGuid,
			chatGuid = chatGuid,
			text = text,
			dateCreated = System.currentTimeMillis(),
			isFromMe = true,
			hasAttachments = persisted.isNotEmpty(),
			messageSource = inferredMessageSource(deliveryMode)
		)
	)

	persisted.forEachIndexed { index, p ->
		attachmentDao.insertAttachment(
			AttachmentEntity(
				guid = "$clientGuid-att-$index",
				messageGuid = clientGuid,
				mimeType = p.mimeType,
				transferName = p.fileName,
				isOutgoing = true,
				localPath = Uri.fromFile(File(p.persistedPath)).toString(),
				transferState = TransferState.UPLOADING.name,
				transferProgress = 0f
			)
		)
	}

	enqueueWorker(pendingId, clientGuid)
	clientGuid
}
```

**Why this solves latency:** the bubble appears as soon as Room emits the new `messages` row—no worker delay.

#### A2. Ensure `MessageSendWorker` uses the same `clientGuid`

- Already passes `tempGuid = pendingMessage.localId`.
- With the new `clientGuid` prefix, retries remain idempotent and the same local echo row is reused.

### Phase B — Make reconciliation idempotent (no duplicates)

#### B1. Ensure message insertion is always “upsert-like”

- Socket, FCM-triggered sync, and SMS provider ingestion must not create duplicates.
- Use the unique index on `messages.guid` as the primary guard.

#### B2. Hard requirement: safe `replaceGuid(tempGuid, serverGuid)`

We must verify/adjust `MessageDao.replaceGuid(...)` to handle:

- **Normal case:** temp row exists, server row doesn’t → update guid.
- **Race case:** server row already inserted (socket won) → merge then delete temp.
  - Preserve local attachment `localPath` and upload state where relevant.

**Checkpoints:**

- For attachments, `MessageSendingService.sendIMessageWithAttachments()` already preserves local paths before `replaceGuid`.
- We should ensure DAO logic does not cascade-delete needed local rows prematurely.

#### B3. Dedupe between `pending_messages` and `messages`

- Since local echo is now always in `messages`, do **not** render `queuedMessages` as bubbles.
- `queuedMessages` can remain for indicator bars / retry menus.

### Phase C — SMS/MMS parity

#### C1. Local SMS/MMS should also create a local echo immediately

Options:

1. Use the Phase A approach (always queue → always local echo) and let WorkManager handle local SMS too.
2. If local SMS is sent immediately (not via worker), still create the same local echo first.

#### C2. Correlate provider updates

- Prefer: update the same `MessageEntity` row with `smsId`/`smsStatus` once available.
- Dedupe key for provider ingestion: `smsId` / `mmsId`.

If the provider ingestion currently creates new `MessageEntity` rows, refactor it to:

- upsert by `smsId` and, if needed, merge with the existing local echo row.

---

## 5) Inbound Sources: Socket vs FCM vs DB vs SMS

### Design rule

All inbound sources must converge through a single “ingestion” API that writes to Room idempotently.

**Proposed internal interface (conceptual):**

```kotlin
interface IncomingMessageIngestor {
	suspend fun ingestServerMessage(dto: MessageDto, source: InboundSource)
	suspend fun ingestSmsProviderMessage(providerRow: SmsRow)
}

enum class InboundSource { SOCKET, FCM_SYNC, POLL_SYNC }
```

### Behavior

- **Socket:** low latency trigger; may contain enough payload to insert directly.
- **FCM:** treat as “sync trigger” (fetch + upsert) where possible.
- **Polling:** same as FCM path.
- **SMS provider:** upsert by `smsId/mmsId`.

**Idempotency requirements**

- `insertMessage(serverGuid)` must be safe if called N times.
- Attachment upserts must be safe if called N times.

---

## 6) Lifecycle / Crash / Reopen / Reboot

### What should happen if…

#### User leaves chat / app is killed immediately after send

- Local echo persists in `messages` and renders on reopen.
- `pending_messages` persists and WorkManager resumes.

#### App crashes while a pending message is “SENDING”

- On startup, existing logic in `PendingMessageRepository.reEnqueuePendingMessages()` resets stale SENDING → PENDING.
- Ensure the corresponding local echo bubble remains visible as “Sending” until retry or “Failed”.

#### Attachment URI permissions expire

- Avoid relying on original `content://` URIs for resumable sends.
- Phase A already persists attachments to internal storage and uses those persisted paths for the local echo.

#### Stuck sends

- Add/keep a “stale pending” policy:
  - If `pending_messages.sync_status == SENDING` for > X minutes, reset to PENDING.
  - If retries exceed max, mark FAILED and ensure UI reflects it.

---

## 7) Concrete File-Level Plan (What changes where)

### Must-touch files

- `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendDelegate.kt`

  - Keep “clear input immediately” behavior.
  - (Optionally) keep “always queue” behavior; Phase A can solve UX without direct-send.

- `app/src/main/kotlin/com/bothbubbles/data/repository/PendingMessageRepository.kt`

  - **Primary change:** create local echo in `messages` (+ temp `attachments`) during `queueMessage`.
  - Standardize `localId/clientGuid` prefix.

- `app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendWorker.kt`

  - Verify it always passes `tempGuid = localId`.

- `app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt`

  - Verify `sendUnified` local SMS/MMS paths also create local echo or are compatible with queue-created local echo.

- `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatScreen.kt`
  - Extend “delivery indicator” logic to show a “sending” indicator for temp messages.

### Likely follow-up files

- `MessageDao.replaceGuid(...)` implementation and related attachment DAO helpers.
- Incoming message handlers (socket/FCM/poll/SMS provider) to ensure upsert semantics.

---

## 8) Testing / Verification Plan

### Functional

- Send text iMessage: bubble appears instantly, transitions to sent.
- Send attachment iMessage: bubble appears instantly with upload progress; transitions to sent; no duplicates.
- Toggle network off → send: bubble appears instantly, shows pending; later network on → send completes.
- Force app kill after send: reopen shows pending bubble; eventually resolves.
- Simulate socket race (server message arrives before replace): verify only one bubble exists.

### Dedupe

- Re-deliver same socket event twice: only one DB row.
- FCM sync after socket insert: no duplicates.
- SMS provider emits same message twice: no duplicates.

### Performance

- Verify message list paging still stable (keys by `guid` remain stable through replace). If replace causes jank, consider keeping a separate stable key (see “Optional Enhancements”).

---

## 9) Optional Enhancements (If needed)

### Stable key independent of `guid`

Replacing `guid` changes the LazyColumn key, which can cause subtle item rebind/animation issues.
If this becomes visible, add a stable `client_guid` column to `messages`:

- Keep `guid` as server GUID.
- Keep `clientGuid` stable forever.
- Key the list by `clientGuid ?: guid`.

This is a schema migration and should be a later phase.

### Unified “SendState” mapping in UI models

Introduce a single computed field in `MessageUiModel` (e.g., `outgoingState`) to avoid duplicating heuristics.

---

## 10) Recommendation

**Best first step (lowest risk, highest impact):**

- Implement Phase A: queue-time local echo creation in `PendingMessageRepository.queueMessage()` and standardize `localId` prefix.

This preserves your offline-first architecture, avoids rendering two streams, and makes send feel instantaneous.
