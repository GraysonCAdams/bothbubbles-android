# BlueBubbles

BlueBubbles is an open-source and cross-platform ecosystem of apps aimed to bring iMessage to Android, Windows, Linux, and the Web! With BlueBubbles, you'll be able to send messages, media, and much more to your friends and family.

**Note:** BlueBubbles requires a Mac and an Apple ID to function! A macOS VM on Windows or Linux can suffice as well.

## Features

- Send & receive texts, media, and location
- View tapbacks, reactions, stickers, and read/delivered timestamps
- Create new chats
- View replies (requires macOS 11+)
- Mute or archive conversations
- Google Messages-style Material Design interface (exact UI mirror)
- Lots of customizations and options to personalize your experience
- Full cross-platform support - Android, Linux, Windows, Web, and macOS

### Private API Features

- See and send typing indicators
- Send tapbacks, read receipts, subject messages, messages with effects, and replies
- Mark chats read on the server Mac
- Rename group chats
- Add and remove participants from group chats

**Private API Features require extra configuration. Learn more [here](https://docs.bluebubbles.app/helper-bundle/installation)**

## Quick Start

### Building from Source

```bash
# Clone the repository
git clone https://github.com/BlueBubblesApp/bluebubbles-app.git
cd bluebubbles-app

# Install dependencies
flutter pub get

# Build debug APK
flutter build apk --debug --flavor bothbubbles

# APK location: build/app/outputs/flutter-apk/app-bothbubbles-debug.apk
```

### Installing on Device

```bash
adb install -r build/app/outputs/flutter-apk/app-bothbubbles-debug.apk
```

For detailed build instructions and contribution guidelines, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Downloads

* Client releases: [here](https://github.com/BlueBubblesApp/bluebubbles-app/releases)
* Server releases: [here](https://github.com/BlueBubblesApp/BlueBubbles-Server/releases)

After downloading both, follow our [installation tutorial](https://bluebubbles.app/install/).

## Links

* Website: [bluebubbles.app](https://bluebubbles.app)
* Discord: [Join us](https://discord.gg/4F7nbf3) - Get help and connect with the community
* Documentation: [docs.bluebubbles.app](https://docs.bluebubbles.app)

## Contributing

We welcome contributions! Please read our [Contribution Guide](CONTRIBUTING.md) to get started.

## Project Structure

```
lib/
├── main.dart           # App entry point
├── v1/                 # Application code (UI, database, services)
└── v2/                 # Design system (Google Messages style theme)
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed project structure documentation.
