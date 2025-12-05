import 'package:bluebubbles/database/models.dart';
import 'package:get/get.dart';


class HandleState {
  Handle model;

  RxString displayName = "Unknown".obs;

  HandleState(this.model) {
    reset();
  }

  setRedacted(bool value) {
    if (value) {
      displayName.value = model.fakeName;
    } else {
      displayName.value = model.displayName;
    }
  }

  update(Handle newHandle) {
    model = newHandle;
    reset();
  }

  reset() {
    displayName.value = model.displayName;
  }
}