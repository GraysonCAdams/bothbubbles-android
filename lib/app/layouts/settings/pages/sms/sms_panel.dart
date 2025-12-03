import 'package:bluebubbles/helpers/helpers.dart';
import 'package:bluebubbles/app/layouts/settings/widgets/settings_widgets.dart';
import 'package:bluebubbles/app/layouts/setup/setup_view.dart';
import 'package:bluebubbles/app/wrappers/stateful_boilerplate.dart';
import 'package:bluebubbles/services/services.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:universal_io/io.dart';

class SmsPanel extends StatefulWidget {
  @override
  State<StatefulWidget> createState() => _SmsPanelState();
}

class _SmsPanelState extends OptimizedState<SmsPanel> {
  final RxBool syncing = false.obs;
  final RxnDouble syncProgress = RxnDouble();

  @override
  void initState() {
    super.initState();
    // Refresh SIM info when panel opens
    if (Platform.isAndroid) {
      sms.refreshAvailableSims();
      sms.checkDefaultSmsStatus();
    }
  }

  @override
  Widget build(BuildContext context) {
    // Only show on Android
    if (!Platform.isAndroid) {
      return SettingsScaffold(
        title: "SMS/MMS Settings",
        initialHeader: null,
        iosSubtitle: iosSubtitle,
        materialSubtitle: materialSubtitle,
        tileColor: tileColor,
        headerColor: headerColor,
        bodySlivers: [
          SliverList(
            delegate: SliverChildListDelegate([
              Padding(
                padding: const EdgeInsets.all(16.0),
                child: Text(
                  "SMS/MMS features are only available on Android.",
                  style: context.theme.textTheme.bodyLarge,
                ),
              ),
            ]),
          ),
        ],
      );
    }

    return SettingsScaffold(
      title: "SMS/MMS Settings",
      initialHeader: ss.settings.smsOnlyMode.value ? "iMessage Setup" : "Default SMS App",
      iosSubtitle: iosSubtitle,
      materialSubtitle: materialSubtitle,
      tileColor: tileColor,
      headerColor: headerColor,
      bodySlivers: [
        SliverList(
          delegate: SliverChildListDelegate([
            // Show "Complete iMessage Setup" option when in SMS-only mode
            if (ss.settings.smsOnlyMode.value) ...[
              SettingsSection(
                backgroundColor: tileColor,
                children: [
                  SettingsTile(
                    title: "Complete iMessage Setup",
                    subtitle: "Connect to a Mac server to enable iMessage alongside SMS",
                    leading: const SettingsLeadingIcon(
                      iosIcon: CupertinoIcons.bubble_left_bubble_right_fill,
                      materialIcon: Icons.message,
                      containerColor: Colors.blue,
                    ),
                    trailing: const Icon(Icons.arrow_forward_ios, size: 16),
                    onTap: () async {
                      final confirm = await showDialog<bool>(
                        context: context,
                        builder: (context) => AlertDialog(
                          backgroundColor: context.theme.colorScheme.properSurface,
                          title: Text("Enable iMessage", style: context.theme.textTheme.titleLarge),
                          content: Text(
                            "This will guide you through connecting to a BlueBubbles server for iMessage support.\n\n"
                            "You'll need:\n"
                            "- A Mac running the BlueBubbles server\n"
                            "- Your server address and password\n\n"
                            "Your SMS messages will be preserved.",
                            style: context.theme.textTheme.bodyLarge,
                          ),
                          actions: [
                            TextButton(
                              onPressed: () => Navigator.pop(context, false),
                              child: Text("Cancel", style: context.theme.textTheme.bodyLarge!.copyWith(color: context.theme.colorScheme.primary)),
                            ),
                            TextButton(
                              onPressed: () => Navigator.pop(context, true),
                              child: Text("Continue", style: context.theme.textTheme.bodyLarge!.copyWith(color: context.theme.colorScheme.primary)),
                            ),
                          ],
                        ),
                      );

                      if (confirm == true) {
                        ss.settings.smsOnlyMode.value = false;
                        ss.settings.finishedSetup.value = false;
                        await ss.settings.saveAsync();
                        Get.offAll(() => SetupView());
                      }
                    },
                    backgroundColor: tileColor,
                  ),
                ],
              ),
              SettingsHeader(
                iosSubtitle: iosSubtitle,
                materialSubtitle: materialSubtitle,
                text: "Default SMS App",
              ),
            ],
            SettingsSection(
              backgroundColor: tileColor,
              children: [
                Obx(() => SettingsTile(
                  title: sms.isDefaultSmsApp.value
                      ? "BlueBubbles is your default SMS app"
                      : "Set as default SMS app",
                  subtitle: sms.isDefaultSmsApp.value
                      ? "Tap to check status"
                      : "Required to send and receive SMS/MMS messages",
                  trailing: sms.isDefaultSmsApp.value
                      ? Icon(Icons.check_circle, color: Colors.green)
                      : Icon(Icons.arrow_forward_ios, size: 16),
                  onTap: () async {
                    if (sms.isDefaultSmsApp.value) {
                      await sms.checkDefaultSmsStatus();
                      if (sms.isDefaultSmsApp.value) {
                        showSnackbar("Status", "BlueBubbles is still the default SMS app");
                      }
                    } else {
                      await sms.requestDefaultSmsApp();
                    }
                  },
                  backgroundColor: tileColor,
                )),
              ],
            ),
            SettingsHeader(
              iosSubtitle: iosSubtitle,
              materialSubtitle: materialSubtitle,
              text: "Dual SIM Selection",
            ),
            SettingsSection(
              backgroundColor: tileColor,
              children: [
                Obx(() {
                  if (sms.availableSims.isEmpty) {
                    return SettingsTile(
                      title: "No SIM cards detected",
                      subtitle: "Insert a SIM card to send SMS messages",
                      backgroundColor: tileColor,
                    );
                  }

                  return Column(
                    children: [
                      for (int i = 0; i < sms.availableSims.length; i++) ...[
                        if (i > 0)
                          const SettingsDivider(padding: EdgeInsets.only(left: 16.0)),
                        Obx(() {
                          final sim = sms.availableSims[i];
                          final isSelected = sms.defaultSim.value?.subscriptionId == sim.subscriptionId;
                          return SettingsTile(
                            title: sim.displayName.isNotEmpty ? sim.displayName : "SIM ${sim.simSlotIndex + 1}",
                            subtitle: sim.carrierName.isNotEmpty
                                ? "${sim.carrierName}${sim.number.isNotEmpty ? ' - ${sim.number}' : ''}"
                                : "Slot ${sim.simSlotIndex + 1}",
                            trailing: isSelected
                                ? Icon(Icons.check_circle, color: context.theme.colorScheme.primary)
                                : null,
                            onTap: () async {
                              await sms.setDefaultSim(sim);
                              showSnackbar("SIM Selected", "SMS will be sent using ${sim.displayName.isNotEmpty ? sim.displayName : 'SIM ${sim.simSlotIndex + 1}'}");
                            },
                            backgroundColor: tileColor,
                          );
                        }),
                      ],
                      if (sms.defaultSim.value != null) ...[
                        const SettingsDivider(padding: EdgeInsets.only(left: 16.0)),
                        SettingsTile(
                          title: "Use system default",
                          subtitle: "Let the system choose which SIM to use",
                          trailing: sms.defaultSim.value == null
                              ? Icon(Icons.check_circle, color: context.theme.colorScheme.primary)
                              : null,
                          onTap: () async {
                            await sms.setDefaultSim(null);
                            showSnackbar("SIM Selection", "Using system default SIM");
                          },
                          backgroundColor: tileColor,
                        ),
                      ],
                    ],
                  );
                }),
              ],
            ),
            // Only show auto-retry setting when not in SMS-only mode
            if (!ss.settings.smsOnlyMode.value) ...[
              SettingsHeader(
                iosSubtitle: iosSubtitle,
                materialSubtitle: materialSubtitle,
                text: "iMessage Fallback",
              ),
              SettingsSection(
                backgroundColor: tileColor,
                children: [
                  Obx(() => SettingsSwitch(
                    title: "Auto-retry failed messages as SMS",
                    subtitle: "Automatically send as SMS when iMessage fails",
                    initialVal: ss.settings.autoRetryFailedAsSms.value,
                    onChanged: (value) {
                      ss.settings.autoRetryFailedAsSms.value = value;
                      ss.settings.save();
                    },
                    backgroundColor: tileColor,
                  )),
                ],
              ),
            ],
            SettingsHeader(
              iosSubtitle: iosSubtitle,
              materialSubtitle: materialSubtitle,
              text: "SMS Sync",
            ),
            SettingsSection(
              backgroundColor: tileColor,
              children: [
                Obx(() {
                  // Build subtitle with detailed progress info
                  String subtitle;
                  if (syncing.value) {
                    final percent = (smsSyncService.syncProgress.value * 100).toStringAsFixed(0);
                    final messages = smsSyncService.messagesImported.value;
                    final threads = smsSyncService.threadsImported.value;
                    subtitle = "$percent% - $messages messages ($threads conversations)";
                  } else {
                    subtitle = "Import SMS/MMS messages from your phone into BlueBubbles";
                  }

                  return SettingsTile(
                    title: syncing.value ? "Syncing SMS messages..." : "Import existing SMS",
                    subtitle: subtitle,
                    trailing: syncing.value
                        ? SizedBox(
                            width: 24,
                            height: 24,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              value: smsSyncService.syncProgress.value > 0
                                  ? smsSyncService.syncProgress.value
                                  : null,
                            ),
                          )
                        : Icon(Icons.download, color: context.theme.colorScheme.primary),
                    onTap: syncing.value
                        ? null
                        : () async {
                            final confirm = await showDialog<bool>(
                              context: context,
                              builder: (context) => AlertDialog(
                                title: Text("Import SMS Messages"),
                                content: Text(
                                  "This will import your existing SMS/MMS messages from your phone into BlueBubbles.\n\n"
                                  "Messages will be merged with existing conversations where possible.\n\n"
                                  "This may take a few minutes depending on how many messages you have.",
                                ),
                                actions: [
                                  TextButton(
                                    onPressed: () => Navigator.pop(context, false),
                                    child: Text("Cancel"),
                                  ),
                                  TextButton(
                                    onPressed: () => Navigator.pop(context, true),
                                    child: Text("Import"),
                                  ),
                                ],
                              ),
                            );

                            if (confirm == true) {
                              syncing.value = true;
                              syncProgress.value = 0.0;
                              try {
                                await smsSyncService.importAllSms(
                                  onProgress: (current, total) {
                                    syncProgress.value = total > 0 ? current / total : 0.0;
                                  },
                                );
                                showSnackbar("Success", "SMS messages imported successfully");
                              } catch (e) {
                                showSnackbar("Error", "Failed to import SMS messages: $e");
                              } finally {
                                syncing.value = false;
                                syncProgress.value = null;
                              }
                            }
                          },
                    backgroundColor: tileColor,
                  );
                }),
              ],
            ),
            SettingsHeader(
              iosSubtitle: iosSubtitle,
              materialSubtitle: materialSubtitle,
              text: "About SMS/MMS",
            ),
            SettingsSection(
              backgroundColor: tileColor,
              children: [
                Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Text(
                    "When BlueBubbles is set as your default SMS app, it can:\n\n"
                    "- Send and receive SMS/MMS messages directly from your Android device\n"
                    "- Automatically fall back to SMS when iMessage is not available\n"
                    "- Show SMS messages with green bubbles to distinguish from iMessage (blue)\n"
                    "- Support dual-SIM devices\n\n"
                    "iMessage will always be prioritized when available. SMS is only used as a fallback for contacts who don't have iMessage.",
                    style: context.theme.textTheme.bodyMedium?.copyWith(
                      color: context.theme.colorScheme.onSurface.withOpacity(0.7),
                    ),
                  ),
                ),
              ],
            ),
          ]),
        ),
      ],
    );
  }
}
