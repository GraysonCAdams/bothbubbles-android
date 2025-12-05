import 'dart:async';

import 'package:bluebubbles/database/models.dart';
import 'package:bluebubbles/helpers/types/helpers/message_helper.dart';
import 'package:get/get.dart';

enum ChatLifecycleState {
  ACTIVE,  // Chat is active and visible
  HIDDEN,  // Chat is hidden, but still active
  INACTIVE,  // Chat is inactive and not visible
}

class ChatState {
  Chat model;

  final RxnString title = RxnString();
  final RxBool isDeleted = false.obs;
  final RxBool isArchived = false.obs;
  final RxBool isUnread = false.obs;
  final RxBool isPinned = false.obs;
  final RxnInt pinIndex = RxnInt();
  final RxnString muteType = RxnString();
  final RxnString muteArgs = RxnString();
  final RxnString customAvatarPath = RxnString();
  final RxList<String> participants = <String>[].obs;
  final Rxn<Message> latestMessage = Rxn<Message>();
  final RxBool isHighlighted = false.obs;
  final RxBool isPartiallyHighlighted = false.obs;
  final RxBool isObscured = false.obs;
  final RxBool lockChatName = false.obs;
  final RxBool lockChatIcon = false.obs;
  final RxnBool autoSendReadReceipts = RxnBool();
  final RxnBool autoSendTypingIndicators = RxnBool();
  final RxDouble sendProgress = 0.0.obs;
  final RxnString lastMessagePreview = RxnString();

  // These don't need to be reactive.
  ChatLifecycleState lifecycleState = ChatLifecycleState.INACTIVE;

  ChatState({required this.model}) {
    reset();
  }

  setRedacted(bool value) {
    if (value) {
      title.value = model.fakeName;
    } else {
      title.value = model.getTitle();

      // TODO: Set Last Message Preview
    }
  }

  setLastMessage(Message? message) {
    latestMessage.value = message;
    if (message != null) {
      lastMessagePreview.value = MessageHelper.getNotificationText(latestMessage.value!);
      model.latestMessage = message;
    } else {
      lastMessagePreview.value = "[ No messages ]";
    }
  }

  setTitle(String? value) {
    title.value = value;
    model.title = value;
    model.save();
  }

  setIsObscured(bool value) {
    isObscured.value = value;
  }

  setSendProgress(double value) {
    if (value < 0) value = 0;
    if (value > 1) value = 1;
    sendProgress.value = value;

    if (value == 1) {
      Timer(const Duration(milliseconds: 500), () {
        setSendProgress(0);
      });
    }
  }

  setIsHighlighted(bool value) {
    isHighlighted.value = value;
  }

  setIsPartiallyHighlighted(bool value) {
    isPartiallyHighlighted.value = value;
  }

  setIsUnread(bool value, {bool force = false}) {
    isUnread.value = value;
    model.toggleHasUnread(value, force: force);
  }

  togglePinned(bool value) {
    model.togglePin(value);
    isPinned.value = value;
  }

  setPinIndex(int? index) {
    pinIndex.value = index;
    model.pinIndex = index;
    model.save(updatePinIndex: true);
    // TODO
    // chats.updateChat(this);
    // chats.sort();
  }

  toggleMute(bool value) {
    model.toggleMute(value);
    muteType.value = model.muteType;
    muteArgs.value = model.muteArgs;
  }

  setMuteType(String? type, {bool save = true}) {
    muteType.value = type;
    model.muteType = type;

    if (save) {
      model.save(updateMuteType: true);
    }
  }

  setMuteArgs(String? args, {bool save = true}) {
    muteArgs.value = args;
    model.muteArgs = args;

    if (save) {
      model.save(updateMuteArgs: true);
    }
  }

  setCustomAvatarPath(String? path) {
    customAvatarPath.value = path;
    model.customAvatarPath = path;
    model.save(updateCustomAvatarPath: true);
  }

  setLockChatName(bool value) {
    lockChatName.value = value;
    model.lockChatName = value;
    model.save(updateLockChatName: true);
  }

  setLockChatIcon(bool value) {
    lockChatIcon.value = value;
    model.lockChatIcon = value;
    model.save(updateLockChatIcon: true);
  }

  setAutoSendReadReceipts(bool? value) {
    autoSendReadReceipts.value = value;
    model.toggleAutoRead(value);
  }

  setAutoSendTypingIndicators(bool? value) {
    autoSendTypingIndicators.value = value;
    model.toggleAutoType(value);
  }

  softDelete() {
    isDeleted.value = true;
    Chat.softDelete(model);
  }

  unDelete() {
    isDeleted.value = false;
    Chat.unDelete(model);
  }

  setIsDeleted(bool value) {
    isDeleted.value = value;

    if (value) {
      Chat.softDelete(model);
    } else {
      Chat.unDelete(model);
    }
  }

  update(Chat newChat) {
    model = newChat;
    reset();
  }

  reset() {
    title.value = model.getTitle();
    isUnread.value = model.hasUnreadMessage ?? false;
    isPinned.value = model.isPinned ?? false;
    pinIndex.value = model.pinIndex;
    muteType.value = model.muteType;
    muteArgs.value = model.muteArgs;
    customAvatarPath.value = model.customAvatarPath;
    isArchived.value = model.isArchived ?? false;
    isDeleted.value = model.dateDeleted != null;
    lockChatName.value = model.lockChatName;
    lockChatIcon.value = model.lockChatIcon;
    autoSendReadReceipts.value = model.autoSendReadReceipts;
    autoSendTypingIndicators.value = model.autoSendTypingIndicators;
    participants.value = model.participants.map((e) => e.address).toList();
    latestMessage.value = model.latestMessage;
  }

  static int sort(ChatState? a, ChatState? b) {
    // If they both are pinned & ordered, reflect the order
    if (a!.model.isPinned! && b!.model.isPinned! && a.model.pinIndex != null && b.model.pinIndex != null) {
      return a.model.pinIndex!.compareTo(b.model.pinIndex!);
    }

    // If b is pinned & ordered, but a isn't either pinned or ordered, return accordingly
    if (b!.model.isPinned! && b.model.pinIndex != null && (!a.model.isPinned! || a.model.pinIndex == null)) return 1;
    // If a is pinned & ordered, but b isn't either pinned or ordered, return accordingly
    if (a.model.isPinned! && a.model.pinIndex != null && (!b.model.isPinned! || b.model.pinIndex == null)) return -1;

    // Compare when one is pinned and the other isn't
    if (!a.model.isPinned! && b.model.isPinned!) return 1;
    if (a.model.isPinned! && !b.model.isPinned!) return -1;

    // Compare latest message dates (if not null)
    if (a.model.latestMessage == null && b.model.latestMessage != null) return 1;
    if (a.model.latestMessage != null && b.model.latestMessage == null) return -1;

    // Compare the last message dates
    return -(a.model.latestMessage!.dateCreated)!.compareTo(b.model.latestMessage!.dateCreated!);
  }
}