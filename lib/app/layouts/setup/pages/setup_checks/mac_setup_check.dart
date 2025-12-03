import 'package:bluebubbles/helpers/helpers.dart';
import 'package:bluebubbles/app/layouts/setup/pages/page_template.dart';
import 'package:bluebubbles/app/layouts/setup/setup_view.dart';
import 'package:bluebubbles/services/services.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:shimmer/shimmer.dart';
import 'package:universal_io/io.dart';
import 'package:url_launcher/url_launcher.dart';

class MacSetupCheck extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return SetupPageTemplate(
      title: "Setup Check",
      subtitle: "Please ensure you have set up the BlueBubbles Server on macOS before proceeding.\n\nAdditionally, please ensure iMessage is signed into your Apple ID on macOS.",
      belowSubtitle: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 13),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Align(
              alignment: Alignment.centerLeft,
              child: Container(
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
                    maximumSize: WidgetStateProperty.all(const Size(300, 36)),
                    minimumSize: WidgetStateProperty.all(const Size(30, 30)),
                  ),
                  onPressed: () async {
                    await launchUrl(Uri(scheme: "https", host: "bluebubbles.app", path: "install"), mode: LaunchMode.externalApplication);
                  },
                  child: Shimmer.fromColors(
                    baseColor: Colors.white70,
                    highlightColor: Colors.white,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          "Server setup instructions",
                          style: context.theme.textTheme.bodyLarge!.apply(fontSizeFactor: 1.1, color: Colors.white)
                        ),
                        const SizedBox(width: 10),
                        const Icon(Icons.arrow_forward, color: Colors.white, size: 20),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            // SMS-only mode option (Android only)
            if (!kIsWeb && !kIsDesktop && Platform.isAndroid)
              Padding(
                padding: const EdgeInsets.only(top: 25.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      "No Mac?",
                      style: context.theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
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
                          maximumSize: WidgetStateProperty.all(const Size(300, 36)),
                          minimumSize: WidgetStateProperty.all(const Size(30, 30)),
                        ),
                        onPressed: () async {
                          final confirm = await showDialog<bool>(
                            context: context,
                            builder: (context) => AlertDialog(
                              backgroundColor: context.theme.colorScheme.properSurface,
                              title: Text("SMS-Only Mode", style: context.theme.textTheme.titleLarge),
                              content: Text(
                                "This will set up BlueBubbles as an SMS-only messaging app.\n\n"
                                "You can send and receive SMS/MMS messages without connecting to a Mac server.\n\n"
                                "You can connect to a BlueBubbles server for iMessage later from Settings.",
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
                            ss.settings.smsOnlyMode.value = true;
                            await ss.settings.saveAsync();
                            // Navigate to SMS sync progress page (skip server credential pages)
                            final controller = Get.find<SetupViewController>();
                            // Jump to the SMS sync progress page (index 7 on Android mobile)
                            controller.pageController.animateToPage(
                              7, // SMS sync progress page index for mobile (after SyncProgress)
                              duration: const Duration(milliseconds: 300),
                              curve: Curves.easeInOut,
                            );
                          }
                        },
                        child: Shimmer.fromColors(
                          baseColor: Colors.white70,
                          highlightColor: Colors.white,
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              const Icon(Icons.textsms, color: Colors.white, size: 20),
                              const SizedBox(width: 10),
                              Text(
                                "Use SMS-only mode",
                                style: context.theme.textTheme.bodyLarge!.apply(fontSizeFactor: 1.1, color: Colors.white)
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      "Use as a standalone SMS/MMS app",
                      style: context.theme.textTheme.bodySmall?.copyWith(
                        color: context.theme.colorScheme.outline,
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}
