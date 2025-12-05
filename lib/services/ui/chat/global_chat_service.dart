import 'dart:async';

import 'package:bluebubbles/app/components/custom_text_editing_controllers.dart';
import 'package:bluebubbles/app/layouts/conversation_details/conversation_details.dart';
import 'package:bluebubbles/app/layouts/conversation_view/pages/conversation_view.dart';
import 'package:bluebubbles/database/database.dart';
import 'package:bluebubbles/database/models.dart';
import 'package:bluebubbles/helpers/network/metadata_helper.dart';
import 'package:bluebubbles/helpers/types/classes/aliases.dart';
import 'package:bluebubbles/helpers/types/extensions/extensions.dart';
import 'package:bluebubbles/services/backend/settings/settings_service.dart';
import 'package:bluebubbles/services/states/chat_state.dart';
import 'package:bluebubbles/services/states/handle_state.dart';
import 'package:bluebubbles/services/ui/chat/conversation_view_controller.dart';
import 'package:bluebubbles/services/ui/handle/global_handle_service.dart';
import 'package:bluebubbles/services/ui/message/messages_service.dart';
import 'package:bluebubbles/services/ui/navigator/navigator_service.dart';
import 'package:bluebubbles/utils/logger/logger.dart';
import 'package:collection/collection.dart';
import 'package:flutter/widgets.dart';
import 'package:get/get.dart';
import 'package:metadata_fetch/metadata_fetch.dart';

// ignore: library_private_types_in_public_api, non_constant_identifier_names
IGlobalChatService GlobalChatService = Get.isRegistered<IGlobalChatService>() ? Get.find<IGlobalChatService>() : Get.put(IGlobalChatService());

/// Global Chat Service
/// 
/// This service is responsible for managing all chats in the app.
/// It listens for changes to the database and updates the reactive chat objects accordingly.
/// It also provides a way to get the reactive chat object for a given chat GUID.
/// 
/// Anytime you need to interact with a chat, you should use this service.
/// If you do not, the UI may not update properly.
class IGlobalChatService extends GetxService {
  Timer? _syncAllDebounce;

  final Completer<void> _chatsLoaded = Completer<void>();
  bool get chatsLoaded => _chatsLoaded.isCompleted;
  Completer<void> get chatsLoadedFuture => _chatsLoaded;

  /// A master list of all chats
  final RxList<ChatState> chats = <ChatState>[].obs;

  /// A map of all reactive chats
  /// You can access the original Chat within the ReactiveChat object
  final Map<String, ChatState> _chatMap = <String, ChatState>{}.obs;

  final Rxn<ConversationViewController> _activeController = Rxn<ConversationViewController>();

  /// A counter to track the number of unread chats
  final RxInt _unreadCount = 0.obs;

  final RxnString _activeGuid = RxnString();

  bool get hasActiveChat => _activeGuid.value != null;

  Rxn<ConversationViewController> get activeController => _activeController;

  RxnString get activeGuid => _activeGuid;

  ChatState? get activeChat => _activeGuid.value == null ? null : _chatMap[_activeGuid.value!];

  /// Getter for the unread count.
  /// Calculates the unread count and upates the reactive variable if the count has changed.
  RxInt get unreadCount {
    updateUnreadCount();
    return _unreadCount;
  }

  /// Get a reactive chat object by the [chatGuid].
  /// Returns null if the chat does not exist.
  /// 
  /// You will be able to access the original Chat object within the ReactiveChat object.
  ChatState? getChat(ChatGuid chatGuid) {
    return _chatMap[chatGuid];
  }

  List<HandleState> getChatParticipants(ChatGuid chatGuid) {
    final chatState = _chatMap[chatGuid];
    if (chatState == null) return [];

    // Use the addresses and get the latest handles
    return chatState.participants.map((address) => GlobalHandleService.getHandle(address)).nonNulls.toList();
  }

  /// Our own init function so we can control when chats are loaded
  void init() {
    reloadChats();
    watchForChatUpdates();
    _chatsLoaded.complete();
  }

  /// Resyncs all of the chats directly from the database.
  Future<void> reloadChats() async {
    final chats = await Database.chats.getAllAsync();
    await syncChats(chats);
  }

  /// Syncs a list of [chats] with the global chat service.
  /// Updates the reactive chat objects accordingly, and therefore
  /// updates the UI as well.
  Future<void> syncChats(List<Chat> chats, {bool updateLatestMessage = false, bool test = false}) async {
    Logger.info("Syncing ${chats.length} chats with Global Chat Service");
    final stopwatch = Stopwatch()..start();

    bool isFirst = _chatMap.isEmpty;
    List<ChatState> updatedChats = [];
    for (Chat chat in chats) {
      // Save the chat globally
      if (!_chatMap.containsKey(chat.guid)) {
        _chatMap[chat.guid] = ChatState(model: chat);

        if (isFirst) {
          chat.getTitle();
        }

        sortChat(chat.guid);
      }

      ChatState existing = _chatMap[chat.guid]!;

      // Save the participants to the chat participants list
      // and create the reactive handles
      for (Handle participant in chat.participants) {
        if (!existing.participants.contains(participant.address)) {
          existing.participants.add(participant.address);
        }
      }

      // Detect changes and make updates
      
      List<bool> checks = [
        _evaluateTitleInfo(chat, existing),
        _evaluateUnreadInfo(chat, existing),
        _evaluateMuteInfo(chat, existing),
        _evaluateDeletedInfo(chat, existing),
      ];

      if (checks.any((element) => element)) {
        updatedChats.add(existing);
      }
    }

    if (isFirst || updateLatestMessage) {
      List<Chat> chatLastMessages = await Chat.getLatestMessages(chats.map((e) => e.id!).toList());
      print("GOT CHAT LAST MESSAGES: ${chatLastMessages.length}");
      for (Chat c in chatLastMessages) {
        ChatState existingChat = _chatMap[c.guid]!;
        bool updated = _evaluateLatestMessage(c, existingChat, force: test);
        if (updated && !updatedChats.contains(existingChat)) {
          updatedChats.add(existingChat);
        }
      }
    }

    if (updatedChats.isNotEmpty) {
      for (ChatState chat in updatedChats) {
        sortChat(chat.model.guid);
      }

      Database.chats.putMany(updatedChats.map((e) => e.model).toList(), mode: PutMode.update);
    }

    int newUnread = this.chats.where((chat) => chat.model.hasUnreadMessage ?? false).length;
    if (newUnread != unreadCount.value) {
      unreadCount.value = newUnread;
    }
    
    stopwatch.stop();
    Logger.info("Finished initializing chats in ${stopwatch.elapsedMilliseconds}ms");
  }

  /// Watches for changes to the Chat database.
  /// Whenever a change is detected, the chat list will be evaluated and updated accordingly.
  void watchForChatUpdates() {
    // final query = Database.chats.query().watch(triggerImmediately: false);
    // query.listen((event) async {
    //   final chats = await event.findAsync();
    //   _syncAllChats(chats);
    // });
  }

  // The same as syncChats but with a debounce of 500ms
  void _syncAllChats(List<Chat> chats) {
    if (_syncAllDebounce?.isActive ?? false) _syncAllDebounce?.cancel();
    _syncAllDebounce = Timer(const Duration(milliseconds: 500), () {
      syncChats(chats);
    });
  }

  /// Syncs a single [chat] with the global chat service.
  Future<void> syncChat(Chat chat) async {
    await syncChats([chat]);
  }

  /// Syncs a single chat by the [guid] with the global chat service.
  /// Since you are passing a GUID, it will fetch the chat from the database.
  Future<void> syncChatByGuid(String guid) async {
    final chat = await Database.chats.query(Chat_.guid.equals(guid)).build().findFirstAsync();
    if (chat != null) {
      await syncChat(chat);
    }
  }

  /// Adds a [message] to a chat by the [chatGuid].
  /// This will also fetch any metadata for the message if it has a URL.
  /// This method is essentially a wrapper for the Chat.addMessage method
  /// so that we can also update the latest message in the reactive chat object.
  /// 
  /// So long as the chat GUID exists, this method will always return the chat.
  /// If the chat does not exist, this method will return null.
  /// 
  /// If [changeUnreadStatus] is true, the unread status of the chat will be updated
  /// based on the message.
  /// 
  /// If [checkForMessageText] is true, the message will be evaluated to be updated as
  /// the chat's latestMessage. It will also un-delete a message that's been deleted.
  /// 
  /// If [clearNotificationsIfFromMe] is true, the chat's notifications will be cleared
  /// if the message is from the yourself
  Future<Chat?> addMessage(ChatGuid chatGuid, Message message, {bool changeUnreadStatus = true, bool checkForMessageText = true, bool clearNotificationsIfFromMe = true}) async {
    final chat = _chatMap[chatGuid];
    if (chat == null) return null;
  
    // If this is a message preview and we don't already have metadata for this, get it
    if (message.fullText.replaceAll("\n", " ").hasUrl && !MetadataHelper.mapIsNotEmpty(message.metadata) && !message.hasApplePayloadData) {
      MetadataHelper.fetchMetadata(message).then((Metadata? meta) async {
        // If the metadata is empty, don't do anything
        if (!MetadataHelper.isNotEmpty(meta)) return;

        // Save the metadata to the object
        message.metadata = meta!.toJson();
      });
    }

    if (chat.model.latestMessage == null) {
      chat.setLastMessage(message);
    } else if (message.dateCreated!.isAfter(chat.model.latestMessage!.dateCreated!)) {
      chat.setLastMessage(message);
    }

    return await chat.model.addMessage(message, changeUnreadStatus: changeUnreadStatus, checkForMessageText: checkForMessageText, clearNotificationsIfFromMe: clearNotificationsIfFromMe);
  }

  void markAllAsRead() {
    List<Chat> updated = [];
    for (ChatState chat in _chatMap.values) {
      if (chat.model.hasUnreadMessage ?? false) {
        chat.setIsUnread(false);
        updated.add(chat.model);
      }
    }

    if (updated.isNotEmpty) {
      Database.chats.putMany(updated, mode: PutMode.update);
    }

    updateUnreadCount();
  }

  removeChat(ChatGuid chatGuid, {bool softDelete = true, bool hardDelete = false}) {
    final chat = _chatMap[chatGuid];
    if (chat != null) {
      _chatMap.remove(chatGuid);
      chats.removeWhere((element) => element.model.guid == chatGuid);

      if (hardDelete) {
        Chat.deleteChat(chat.model);
      } else if (softDelete) {
        Chat.softDelete(chat.model); 
      }
    }
  }

  /// Sorts the chat by the Chat.sort static method,
  /// which compares two chats, returning 1, 0, or -1.
  sortChat(ChatGuid chatGuid) {
    final chat = _chatMap[chatGuid];
    if (chat == null) return;

    final index = chats.indexWhere((element) => [0, 1].contains(ChatState.sort(element, chat)));
    if (index != -1) {
      chats.remove(chat);
      chats.insert(index, chat);
    } else {
      chats.add(chat);
    }
  }

  sortAll() {
    chats.sort(ChatState.sort);
  }

  updateLatestMessage(ChatGuid chatGuid) {
    final chat = _chatMap[chatGuid];
    if (chat != null) {
      Message? latestMessage = Chat.getMessages(chat.model, limit: 1, getDetails: true).firstOrNull;
      if (latestMessage != null) {
        chat.setLastMessage(latestMessage);
      }
    }
  }

  updateChatPinIndex(int oldIndex, int newIndex) {
    final items = chats.bigPinHelper(true);
    final item = items[oldIndex];

    // Remove the item at the old index, and re-add it at the newIndex
    // We dynamically subtract 1 from the new index depending on if the newIndex is > the oldIndex
    items.removeAt(oldIndex);
    items.insert(newIndex + (oldIndex < newIndex ? -1 : 0), item);

    // Move the pinIndex for each of the chats, and save the pinIndex in the DB
    List<String> toSort = [];
    items.forEachIndexed((i, e) {
      e.model.pinIndex = i;
      e.model.save();
      toSort.add(e.model.guid);
    });
  
    for (String guid in toSort) {
      sortChat(guid);
    }
  }

  removePinIndices() {
    List<String> toSort = [];
    chats.bigPinHelper(true).where((e) => e.model.pinIndex != null).forEach((element) {
      element.model.pinIndex = null;
      element.model.save();
      toSort.add(element.model.guid);
    });

    for (String guid in toSort) {
      sortChat(guid);
    }
  }

  moveChat(ChatGuid chatGuid, int newIndex) {
    final chat = _chatMap[chatGuid];
    if (chat != null) {
      final index = chats.indexWhere((element) => element.model.guid == chatGuid);
      if (index != -1) {
        chats.removeAt(index);
        chats.insert(newIndex, chat);
      }
    }
  }

  Future<void> openChat(ChatGuid chatGuid, {
    BuildContext? context,
    MessagesService? customService,
    bool fromChatCreator = false,
    Function()? onInit,
    bool closeActiveChat = true,
    PageRoute? customRoute
  }) async {
    BuildContext? ctx = context ?? Get.context;
    if (ctx == null) throw Exception("No context provided to open chat");

    // Code won't run after this call until the route is popped.
    await ns.pushAndRemoveUntil(
      context ?? Get.context!,
      ConversationView(
        chatGuid: chatGuid,
        customService: customService,
        fromChatCreator: fromChatCreator,
        onInit: () {
          ChatGuid? currentActive = _activeGuid.value;
          // If the chat is not active, set it as active
          if (currentActive != chatGuid) {
            setActiveChat(chatGuid);

            if (currentActive != null) {
              
            }
          }

          // Call the passed onInit function
          if (onInit != null) onInit();
        }
      ),
      (route) => route.isFirst,
      closeActiveChat: closeActiveChat && _activeGuid.value != chatGuid,
      customRoute: customRoute
    );
  }

  Future<void> openNextChat(ChatGuid chatGuid, {BuildContext? context}) async {
    final index = GlobalChatService.chats.indexWhere((e) => e.model.guid == chatGuid);
    if (index > -1 && index < chats.length - 1) {
      final _chat = chats[index + 1];
      openChat(_chat.model.guid, context: context);
    }
  }

  Future<void> openPreviousChat(ChatGuid chatGuid, {BuildContext? context}) async {
    final index = GlobalChatService.chats.indexWhere((e) => e.model.guid == chatGuid);
    if (index > 0 && index < chats.length) {
      final _chat = chats[index - 1];
      openChat(_chat.model.guid, context: context);
    }
  }

  Future<void> closeChat(ChatGuid chatGuid, {bool clearNotifications = true}) async {
    if (_activeGuid.value != chatGuid) return;

    unsetActiveChat();
    await ss.prefs.remove('lastOpenedChat');
    toggleHighlightChat(chatGuid, false);
  }

  setActiveChat(ChatGuid chatGuid) {
    _activeGuid.value = chatGuid;
    _activeController.value = cvc(chatGuid);
  }

  unsetActiveChat() {
    _activeGuid.value = null;

    if (_activeController.value != null) {
      print("Closing active controller");
      _activeController.value!.close();
    }

    _activeController.value = null;
  }

  Future<void> closeActiveChat() async {
    if (_activeGuid.value == null) return;
    await closeChat(_activeGuid.value!);
  }

  openChatDetails(ChatGuid chatGuid, {BuildContext? context}) async {
    final ctx = context ?? Get.context;
    if (context == null) throw Exception("No context provided to open chat details");
    ns.push(ctx!, ConversationDetails(chatGuid: chatGuid));
  }

  clearHighlightedChats() {
    for (ChatState chat in _chatMap.values) {
      chat.isHighlighted.value = false;
    }
  }

  bool isChatActive(ChatGuid chatGuid) {
    return _activeGuid.value == chatGuid;
  }

  toggleHighlightChat(ChatGuid chatGuid, bool highlight) {
    final chat = _chatMap[chatGuid];
    if (chat != null && chat.isHighlighted.value != highlight) {
      chat.isHighlighted.value = highlight;
    }
  }

  /// Gets the mentionables (handles) for a chat by the [chatGuid].
  List<Mentionable> getMentionablesForChat(ChatGuid chatGuid) {
    final addresses = (_chatMap[chatGuid]?.model.participants ?? []).map((e) => e.address).toList();
    return addresses.map((address) {
      final handle = GlobalHandleService.getHandle(address);
      if (handle != null) return Mentionable(handle: handle.model);
    }).nonNulls.toList();
  }

  void updateUnreadCount() {
    int count = 0;
    for (ChatState chat in _chatMap.values) {
      if (chat.model.hasUnreadMessage ?? false) {
        count++;
      }
    }

    if (count != _unreadCount.value) {
      _unreadCount.value = count;
    }
  }

  /// Evaluates to see if a reactive [existingChat] needs to be updated with the [newChat] unread status.
  bool _evaluateUnreadInfo(Chat newChat, ChatState existingChat) {    
    // Set the default value
    if (newChat.hasUnreadMessage != null && existingChat.model.hasUnreadMessage != newChat.hasUnreadMessage) {
      Logger.debug("Updating Chat (${existingChat.model.guid}) Unread Status from ${existingChat.model.hasUnreadMessage} to ${newChat.hasUnreadMessage}");
      existingChat.setIsUnread(newChat.hasUnreadMessage!);
      return true;
    }

    return false;
  }

  /// Evaluates to see if a reactive [existingChat] needs to be updated with the [newChat] mute type.
  bool _evaluateMuteInfo(Chat newChat, ChatState existingChat) {
    bool typeChanges = false;
    bool typeArgs = false;
    if (existingChat.model.muteType != newChat.muteType) {
      Logger.debug("Updating Chat (${newChat.guid}) Mute Type from ${existingChat.model.muteType} to ${newChat.muteType}");
      typeChanges = true;
      existingChat.setMuteType(newChat.muteType, save: false);
    }

    if (existingChat.model.muteArgs != newChat.muteArgs) {
      Logger.debug("Updating Chat (${newChat.guid}) Mute Args from ${existingChat.model.muteArgs} to ${newChat.muteArgs}");
      typeArgs = true;
      existingChat.setMuteArgs(newChat.muteArgs, save: false);
    }

    if (typeChanges || typeArgs) return true;

    return false;
  }

  /// Evaluates to see if a reactive [existingChat] needs to be updated with the [newChat] title.
  bool _evaluateTitleInfo(Chat newChat, ChatState existingChat) {
    final newTitle = newChat.getTitle();
    if (existingChat.model.title != newTitle) {
      Logger.debug("Updating Chat (${newChat.guid}) Title from ${existingChat.title} to ${newChat.getTitle()}");
      existingChat.setTitle(newTitle);
      return true;
    }

    return false;
  }

  bool _evaluateDeletedInfo(Chat newChat, ChatState existingChat) {
    final isDeleted = (newChat.dateDeleted != null);
    if ((existingChat.model.dateDeleted != null) != isDeleted) {
      Logger.debug("Updating Chat (${newChat.guid}) Deleted Status from ${existingChat.model.dateDeleted != null} to $isDeleted");
      if (isDeleted) {
        existingChat.softDelete();
      } else {
        existingChat.unDelete();
      }
      return true;
    }

    return false;
  }

  bool _evaluateLatestMessage(Chat newChat, ChatState existingChat, {bool force = false}) {
    if (force || (existingChat.model.latestMessage == null && newChat.latestMessage != null)) {
      Logger.debug("Setting Chat (${newChat.guid}) Latest Message to ${newChat.latestMessage?.dateCreated}");
      existingChat.setLastMessage(newChat.latestMessage);
      return true;
    } else if (existingChat.model.latestMessage != null && newChat.latestMessage != null && newChat.latestMessage!.dateCreated!.isAfter(existingChat.model.latestMessage!.dateCreated!)) {
      Logger.debug("Updating Chat (${newChat.guid}) Latest Message from ${existingChat.model.latestMessage!.dateCreated} to ${newChat.latestMessage!.dateCreated}");
      existingChat.setLastMessage(newChat.latestMessage);
      return true;
    }

    return false;
  }

  void dispose() {
    _syncAllDebounce?.cancel();
    _chatMap.clear();
    _unreadCount.value = 0;
    _activeGuid.value = null;
    _activeController.value = null;
  }
}