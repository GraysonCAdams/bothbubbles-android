import 'package:bluebubbles/database/database.dart';
import 'package:bluebubbles/services/states/handle_state.dart';
import 'package:get/get.dart';

// ignore: library_private_types_in_public_api, non_constant_identifier_names
_GlobalHandleService GlobalHandleService = Get.isRegistered<_GlobalHandleService>() ? Get.find<_GlobalHandleService>() : Get.put(_GlobalHandleService());

class _GlobalHandleService extends GetxService {
  final List<HandleState> handles = [];

  @override
  void onInit() {
    super.onInit();
    watchHandles();
  }

  HandleState? getHandle(String address) {
    return handles.firstWhereOrNull((handle) => handle.model.address == address);
  }

  void watchHandles() {
    final query = Database.handles.query().watch(triggerImmediately: true);
    query.listen((event) {
      final newHandles = event.find();
      
      // Add or update handles
      for (int i = 0; i < newHandles.length; i++) {
        final newHandle = newHandles[i];
        final existingHandleIndex = handles.indexWhere((h) => h.model.address == newHandle.address);
        
        if (existingHandleIndex == -1) {
          handles.add(HandleState(newHandle));
        } else {
          handles[existingHandleIndex].update(newHandle);
        }
      }
    });
  }
}