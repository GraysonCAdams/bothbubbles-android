# FCM Setup Guide: Adding BothBubbles to Existing Firebase Project

This guide walks through adding the `com.bothbubbles.messaging` Android package to the existing BlueBubbles Firebase project so FCM (Firebase Cloud Messaging) delivers push notifications to both apps.

---

## Prerequisites

- [ ] Access to the existing BlueBubbles Firebase project in [Firebase Console](https://console.firebase.google.com/)
- [ ] Admin access to your BlueBubbles server
- [ ] The existing `google-services.json` from your server (for reference)

---

## Step 1: Add BothBubbles App to Firebase Project

### 1.1 Open Firebase Console
1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select the **existing BlueBubbles project** (the one your server uses)
3. You should see `com.bluebubbles.messaging` listed under "Your apps"

### 1.2 Add New Android App
1. Click the **gear icon** → **Project Settings**
2. Scroll to **"Your apps"** section
3. Click **"Add app"** button
4. Select the **Android** icon
5. Fill in the registration form:

| Field | Value |
|-------|-------|
| Android package name | `com.bothbubbles.messaging` |
| App nickname | BothBubbles |
| Debug signing certificate SHA-1 | (optional, see below) |

### 1.3 Add SHA-1 Fingerprints (Optional but Recommended)

```bash
# Get debug SHA-1
keytool -list -v \
  -keystore ~/.android/debug.keystore \
  -alias androiddebugkey \
  -storepass android \
  -keypass android

# Get release SHA-1 (use your actual keystore path)
keytool -list -v \
  -keystore /path/to/your-release.keystore \
  -alias your-alias
```

### 1.4 Complete Registration
1. Click **"Register app"**
2. Firebase will prompt you to download `google-services.json`
3. **Download this file** - it now contains BOTH apps

---

## Step 2: Verify Configuration

After registration, verify both apps appear in the project:

1. Go to **Project Settings** → **General**
2. Under "Your apps", you should see:
   - `com.bluebubbles.messaging` (original BlueBubbles app)
   - `com.bothbubbles.messaging` (newly added)

Both apps share:
- **Project ID** (same)
- **Project Number / Sender ID** (same)
- **Web API Key** (same)

Only the **App ID** (`mobilesdk_app_id`) is different per app.

---

## Step 3: Update BlueBubbles Server

The server's `google-services.json` must include both app configurations.

### Option A: Replace the File (Recommended)

1. Locate your server's `google-services.json` file
   - Typically in the BlueBubbles server FCM configuration directory
2. **Backup** the existing file
3. **Replace** it with the newly downloaded file (contains both apps)
4. **Restart** the BlueBubbles server

### Option B: Manually Edit the File

If you can't replace the file, add the new client entry manually:

**Before** (single app):
```json
{
  "project_info": {
    "project_number": "123456789012",
    "firebase_url": "https://your-project.firebaseio.com",
    "project_id": "your-project-id",
    "storage_bucket": "your-project.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:123456789012:android:abc123",
        "android_client_info": {
          "package_name": "com.bluebubbles.messaging"
        }
      },
      "api_key": [{ "current_key": "AIzaSyXXXXXXXXXXXXXXXXXXXXX" }]
    }
  ]
}
```

**After** (both apps):
```json
{
  "project_info": {
    "project_number": "123456789012",
    "firebase_url": "https://your-project.firebaseio.com",
    "project_id": "your-project-id",
    "storage_bucket": "your-project.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:123456789012:android:abc123",
        "android_client_info": {
          "package_name": "com.bluebubbles.messaging"
        }
      },
      "api_key": [{ "current_key": "AIzaSyXXXXXXXXXXXXXXXXXXXXX" }]
    },
    {
      "client_info": {
        "mobilesdk_app_id": "1:123456789012:android:def456",
        "android_client_info": {
          "package_name": "com.bothbubbles.messaging"
        }
      },
      "api_key": [{ "current_key": "AIzaSyXXXXXXXXXXXXXXXXXXXXX" }]
    }
  ]
}
```

> **Note**: The `mobilesdk_app_id` values are different for each app. Get the correct value from Firebase Console → Project Settings → Your apps → BothBubbles.

---

## Step 4: Server Credentials (No Changes Needed)

The service account JSON (Firebase Admin SDK credentials) does **NOT** need modification:

- Service accounts are **project-level**, not app-level
- The existing admin credential works for **ALL** apps in the project
- FCM can send to any registered app using the same credentials

---

## How It Works

### FCM Token Routing

When the server sends an FCM message, Firebase automatically routes to the correct app:

```
Server sends to ALL registered device tokens
          ↓
Firebase receives tokens and routes by token:
  ├── Token from com.bluebubbles.messaging → BlueBubbles app
  └── Token from com.bothbubbles.messaging → BothBubbles app
```

**Key insight**: FCM tokens are app-specific. Firebase knows which app generated each token and routes messages accordingly.

### Registration Flow

```
1. BothBubbles app launches
2. App fetches Firebase config from server: GET /api/v1/fcm/client
3. App initializes Firebase with config
4. App requests FCM token from Firebase
5. App registers token with server: POST /api/v1/fcm/device
6. Server stores token in database
7. When server broadcasts, Firebase routes to BothBubbles
```

---

## Testing

### Test 1: Verify Server FCM Service
Check server logs after restart:
```
FCM Service started successfully
```

### Test 2: Verify Device Registration
1. Install and open BothBubbles app
2. Complete server setup
3. Check server logs for:
```
Device registered: [DeviceName] (token: abc123...)
```

### Test 3: Test Push Notification
1. **Background** the BothBubbles app (socket disconnects in FCM mode)
2. Send a test message from another device/iMessage
3. You should receive an FCM notification within seconds

### Test 4: Verify in App
Check that FCM is properly configured:
- Settings → About → should show FCM status
- Or check DataStore for `fcmToken` and `fcmTokenRegistered = true`

---

## Troubleshooting

### "FirebaseApp not initialized"
**Cause**: App can't get Firebase config from server

**Fix**:
1. Verify server has updated `google-services.json` with both apps
2. Test endpoint: `GET https://your-server/api/v1/fcm/client`
3. Check app logs for `FirebaseConfigManager` errors

### "SENDER_ID_MISMATCH"
**Cause**: App's sender ID doesn't match server's Firebase project

**Fix**:
1. Verify `project_number` in server's `google-services.json`
2. Compare with Firebase Console → Project Settings → Project Number
3. They must match exactly

### Notifications Not Received
**Debug checklist**:
- [ ] Firebase project has **Cloud Messaging API** enabled
- [ ] Device has Google Play Services installed
- [ ] App's notification provider setting is "fcm" (not "foreground")
- [ ] Server has valid service account credentials
- [ ] Server was restarted after config update

### "messaging/registration-token-not-registered"
**Cause**: FCM token expired or app was uninstalled

**Fix**: This is normal - server ignores these errors. Device will re-register on next app launch.

---

## Security Best Practices

1. **Never commit** `google-services.json` or service account JSON to git
2. **Rotate service account keys** periodically in Firebase Console
3. **Restrict API key** in Google Cloud Console:
   - Go to [Google Cloud Console](https://console.cloud.google.com/) → APIs & Services → Credentials
   - Edit your API key → Application restrictions → Android apps
   - Add your SHA-1 fingerprint and package name (`com.bothbubbles.messaging`)

---

## Summary Checklist

- [ ] Added `com.bothbubbles.messaging` app in Firebase Console
- [ ] Downloaded new `google-services.json` (contains both apps)
- [ ] Updated server's `google-services.json`
- [ ] Restarted BlueBubbles server
- [ ] Verified both apps appear in Firebase Console
- [ ] Tested FCM notification delivery to BothBubbles app
