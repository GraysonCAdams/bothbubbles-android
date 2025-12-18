# Adding BothBubbles to Your BlueBubbles Server FCM

BothBubbles uses Firebase Cloud Messaging (FCM) for push notifications. To receive notifications, you need to add BothBubbles to your existing BlueBubbles server's Firebase project.

## Prerequisites

- BlueBubbles Server already running and configured
- FCM already working with your existing BlueBubbles client
- Access to the Firebase project you created for BlueBubbles

## Steps

### 1. Open Firebase Console

Go to your [Firebase Console](https://console.firebase.google.com/) and select the project you use with BlueBubbles Server.

### 2. Add a New Android App

1. Click **Project Settings** (gear icon)
2. Under **Your apps**, click **Add app** → **Android**
3. Enter the following:
   - **Package name:** `com.bothbubbles`
   - **App nickname:** BothBubbles (optional)
4. Click **Register app**

### 3. Download the Config File

1. Download the `google-services.json` file
2. You don't need to add this to BothBubbles—the app already has FCM configured
3. Click **Continue** through the remaining steps

### 4. Verify Setup

Once registered, your Firebase project will show both the original BlueBubbles app and BothBubbles under **Your apps**. Both apps will receive push notifications from your server.

## That's It

No server restart required. BothBubbles will start receiving push notifications immediately after you sign in with your server URL and password.

## Troubleshooting

**Not receiving notifications?**
- Verify `com.bothbubbles` appears in your Firebase project's app list
- Check that notifications are enabled in Android settings for BothBubbles
- Ensure your server is online and connected

**Already have notifications working in BlueBubbles?**
- Your server's FCM setup is correct—you just need to add the BothBubbles package name as shown above
