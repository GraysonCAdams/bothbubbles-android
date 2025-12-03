import 'dart:math';

import 'package:bluebubbles/helpers/ui/theme_helpers.dart';
import 'package:bluebubbles/app/layouts/conversation_list/pages/conversation_list.dart';
import 'package:bluebubbles/app/layouts/setup/pages/page_template.dart';
import 'package:bluebubbles/app/layouts/setup/setup_view.dart';
import 'package:bluebubbles/app/wrappers/stateful_boilerplate.dart';
import 'package:bluebubbles/services/services.dart';
import 'package:confetti/confetti.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:shimmer/shimmer.dart';

class SmsSyncProgress extends StatefulWidget {
  @override
  State<SmsSyncProgress> createState() => _SmsSyncProgressState();
}

class _SmsSyncProgressState extends OptimizedState<SmsSyncProgress> {
  final confettiController = ConfettiController(duration: const Duration(milliseconds: 500));
  final controller = Get.find<SetupViewController>();

  bool hasPlayed = false;
  bool syncStarted = false;
  bool showDefaultSmsPrompt = true;
  bool showPermissionError = false;
  String? errorMessage;
  int? totalMessages;

  @override
  void initState() {
    super.initState();
    // Check if already default SMS app
    sms.checkDefaultSmsStatus().then((_) {
      if (sms.isDefaultSmsApp.value) {
        setState(() {
          showDefaultSmsPrompt = false;
        });
        _startSync();
      }
    });
  }

  @override
  void dispose() {
    confettiController.dispose();
    super.dispose();
  }

  Future<void> _startSync() async {
    if (syncStarted) return;
    syncStarted = true;

    final result = await smsSyncService.importAllSms();

    if (result.success && !hasPlayed) {
      setState(() {
        hasPlayed = true;
        totalMessages = result.messagesImported;
      });
      confettiController.play();
    } else if (!result.success) {
      setState(() {
        showPermissionError = result.error?.contains("permission") ?? false;
        errorMessage = result.error;
        syncStarted = false; // Allow retry
      });
    }
  }

  void _onSetDefaultSmsApp() async {
    await sms.requestDefaultSmsApp();
    // Wait a moment for the system dialog to complete
    await Future.delayed(const Duration(milliseconds: 500));
    await sms.checkDefaultSmsStatus();

    if (sms.isDefaultSmsApp.value) {
      setState(() {
        showDefaultSmsPrompt = false;
      });
      _startSync();
    }
  }


  void _finishSetup() async {
    ss.settings.finishedSetup.value = true;
    await ss.settings.saveAsync();

    Get.offAll(
      () => ConversationList(
        showArchivedChats: false,
        showUnknownSenders: false,
      ),
      routeName: "",
      duration: Duration.zero,
      transition: Transition.noTransition,
    );
    Get.delete<SetupViewController>(force: true);
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      alignment: Alignment.topCenter,
      children: [
        SetupPageTemplate(
          title: _getTitle(),
          subtitle: "",
          customMiddle: _buildMiddle(context),
          customButton: _buildButton(context),
        ),
        ConfettiWidget(
          confettiController: confettiController,
          blastDirection: pi / 2,
          blastDirectionality: BlastDirectionality.explosive,
          emissionFrequency: 0.35,
        ),
      ],
    );
  }

  String _getTitle() {
    if (showDefaultSmsPrompt) {
      return "Set Default SMS App";
    } else if (showPermissionError) {
      return "Permission Required";
    } else if (hasPlayed) {
      return "SMS Import Complete!";
    } else {
      return "Importing SMS...";
    }
  }

  Widget _buildMiddle(BuildContext context) {
    if (showDefaultSmsPrompt) {
      return _buildDefaultSmsPrompt(context);
    } else if (showPermissionError) {
      return _buildPermissionError(context);
    } else if (hasPlayed) {
      return null ?? const SizedBox.shrink();
    } else {
      return _buildSyncProgress(context);
    }
  }

  Widget _buildPermissionError(BuildContext context) {
    return Column(
      children: [
        Icon(
          Icons.sms_failed,
          size: 64,
          color: Colors.orange,
        ),
        const SizedBox(height: 20),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Text(
            errorMessage ?? "SMS permission is required to import messages.",
            style: context.theme.textTheme.bodyLarge,
            textAlign: TextAlign.center,
          ),
        ),
        const SizedBox(height: 30),
        Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(25),
            gradient: LinearGradient(
              begin: AlignmentDirectional.topStart,
              colors: [Colors.orange, Colors.orange.shade300],
            ),
          ),
          height: 40,
          child: ElevatedButton(
            style: ButtonStyle(
              shape: WidgetStateProperty.all<RoundedRectangleBorder>(
                RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(20.0),
                ),
              ),
              backgroundColor: WidgetStateProperty.all(Colors.transparent),
              shadowColor: WidgetStateProperty.all(Colors.transparent),
              maximumSize: WidgetStateProperty.all(Size(context.width * 2 / 3, 36)),
              minimumSize: WidgetStateProperty.all(Size(context.width * 2 / 3, 36)),
            ),
            onPressed: () async {
              setState(() {
                showPermissionError = false;
                errorMessage = null;
              });
              _startSync();
            },
            child: const Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(Icons.refresh, color: Colors.white, size: 20),
                SizedBox(width: 10),
                Text(
                  "Try Again",
                  style: TextStyle(color: Colors.white, fontSize: 16),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildDefaultSmsPrompt(BuildContext context) {
    return Column(
      children: [
        Icon(
          Icons.textsms,
          size: 64,
          color: context.theme.colorScheme.primary,
        ),
        const SizedBox(height: 20),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Text(
            "To import and sync your SMS messages, BlueBubbles needs to be set as your default SMS app.",
            style: context.theme.textTheme.bodyLarge,
            textAlign: TextAlign.center,
          ),
        ),
        const SizedBox(height: 30),
        Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(25),
            gradient: LinearGradient(
              begin: AlignmentDirectional.topStart,
              colors: [Colors.green, Colors.green.shade300],
            ),
          ),
          height: 40,
          child: ElevatedButton(
            style: ButtonStyle(
              shape: WidgetStateProperty.all<RoundedRectangleBorder>(
                RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(20.0),
                ),
              ),
              backgroundColor: WidgetStateProperty.all(Colors.transparent),
              shadowColor: WidgetStateProperty.all(Colors.transparent),
              maximumSize: WidgetStateProperty.all(Size(context.width * 2 / 3, 36)),
              minimumSize: WidgetStateProperty.all(Size(context.width * 2 / 3, 36)),
            ),
            onPressed: _onSetDefaultSmsApp,
            child: const Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(Icons.check, color: Colors.white, size: 20),
                SizedBox(width: 10),
                Text(
                  "Set as Default",
                  style: TextStyle(color: Colors.white, fontSize: 16),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSyncProgress(BuildContext context) {
    return Column(
      children: [
        // Large percentage display
        Obx(() => Text(
          "${(smsSyncService.syncProgress.value * 100).toInt()}%",
          style: context.theme.textTheme.displayMedium?.copyWith(
            color: context.theme.colorScheme.onBackground,
            fontWeight: FontWeight.bold,
          ),
        )),
        const SizedBox(height: 15),
        // Progress bar
        Obx(() => Padding(
          padding: EdgeInsets.symmetric(horizontal: ns.width(context) / 4),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(20),
            child: LinearProgressIndicator(
              value: smsSyncService.syncProgress.value == 0
                  ? null
                  : smsSyncService.syncProgress.value,
              backgroundColor: context.theme.colorScheme.outline,
              valueColor: AlwaysStoppedAnimation<Color>(
                context.theme.colorScheme.primary,
              ),
              minHeight: 8,
            ),
          ),
        )),
        const SizedBox(height: 25),
        // Message count
        Obx(() => Text(
          "${smsSyncService.messagesImported.value} messages imported",
          style: context.theme.textTheme.titleMedium?.copyWith(
            color: context.theme.colorScheme.onBackground,
          ),
        )),
        const SizedBox(height: 8),
        // Thread count
        Obx(() => Text(
          "${smsSyncService.threadsImported.value} conversations",
          style: context.theme.textTheme.bodyLarge?.copyWith(
            color: context.theme.colorScheme.outline,
          ),
        )),
        const SizedBox(height: 20),
        // Status text
        Obx(() => Text(
          smsSyncService.syncStatus.value.isNotEmpty
              ? smsSyncService.syncStatus.value
              : "Please wait...",
          style: context.theme.textTheme.bodyMedium?.copyWith(
            color: context.theme.colorScheme.outline,
          ),
          textAlign: TextAlign.center,
        )),
      ],
    );
  }

  Widget _buildButton(BuildContext context) {
    if (showDefaultSmsPrompt || showPermissionError || !hasPlayed) {
      return const SizedBox.shrink();
    }

    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(25),
        gradient: LinearGradient(
          begin: AlignmentDirectional.topStart,
          colors: [HexColor('2772C3'), HexColor('5CA7F8').darkenPercent(5)],
        ),
      ),
      height: 40,
      child: ElevatedButton(
        style: ButtonStyle(
          shape: WidgetStateProperty.all<RoundedRectangleBorder>(
            RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(20.0),
            ),
          ),
          backgroundColor: WidgetStateProperty.all(Colors.transparent),
          shadowColor: WidgetStateProperty.all(Colors.transparent),
          maximumSize: WidgetStateProperty.all(Size(context.width * 2 / 3, 36)),
          minimumSize: WidgetStateProperty.all(Size(context.width * 2 / 3, 36)),
        ),
        onPressed: _finishSetup,
        child: Shimmer.fromColors(
          baseColor: Colors.white70,
          highlightColor: Colors.white,
          child: const Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.check, color: Colors.white, size: 25),
              SizedBox(width: 10),
              Text(
                "Finish",
                style: TextStyle(color: Colors.white, fontSize: 16),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
