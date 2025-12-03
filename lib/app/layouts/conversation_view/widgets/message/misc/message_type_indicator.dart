import 'package:bluebubbles/database/models.dart';
import 'package:bluebubbles/helpers/helpers.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';

/// Shows a centered label ("iMessage" or "Text Message") when the message type
/// changes between the current message and the older message.
class MessageTypeIndicator extends StatelessWidget {
  const MessageTypeIndicator({
    super.key,
    required this.message,
    this.olderMessage,
    required this.chat,
  });

  final Message message;
  final Message? olderMessage;
  final Chat chat;

  /// Whether the given message is an SMS/MMS message
  bool _isSmsMessage(Message msg) {
    return msg.isLocalSms || chat.isTextForwarding;
  }

  @override
  Widget build(BuildContext context) {
    // Don't show for group events
    if (message.isGroupEvent) return const SizedBox.shrink();

    // Check if this is the first message or if the type changed
    final currentIsSms = _isSmsMessage(message);
    final previousIsSms = olderMessage != null ? _isSmsMessage(olderMessage!) : null;

    // Show indicator if:
    // 1. This is the first message in the conversation (olderMessage is null)
    // 2. The message type changed from the previous message
    // Only show if not in SMS-only mode (where all messages are SMS)
    final shouldShow = (olderMessage == null || olderMessage!.isGroupEvent || currentIsSms != previousIsSms);

    if (!shouldShow) return const SizedBox.shrink();

    final label = currentIsSms ? "Text Message" : "iMessage";
    final color = currentIsSms ? Colors.green : Colors.blue;

    return Padding(
      padding: const EdgeInsets.only(top: 8.0, bottom: 8.0),
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12.0, vertical: 4.0),
          decoration: BoxDecoration(
            color: color.withOpacity(0.15),
            borderRadius: BorderRadius.circular(12.0),
          ),
          child: Text(
            label,
            style: context.theme.textTheme.labelSmall?.copyWith(
              color: color,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }
}
