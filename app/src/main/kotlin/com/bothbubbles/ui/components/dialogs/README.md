# Dialog Components

## Purpose

Dialog, bottom sheet, and popup components for user interactions.

## Files

| File | Description |
|------|-------------|
| `ContactQuickActionsPopup.kt` | Quick actions popup for contacts |
| `ForwardMessageDialog.kt` | Forward message to chat dialog |
| `SnoozeDurationDialog.kt` | Select snooze duration |
| `VCardOptionsDialog.kt` | vCard export options |

## Required Patterns

### Dialog Structure

```kotlin
@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    dismissText: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) {
                Text(
                    confirmText,
                    color = if (isDestructive) MaterialTheme.colorScheme.error else Color.Unspecified
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        }
    )
}
```

### Bottom Sheet

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsBottomSheet(
    options: List<OptionItem>,
    onSelect: (OptionItem) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn {
            items(options) { option ->
                ListItem(
                    headlineContent = { Text(option.title) },
                    leadingContent = { Icon(option.icon, null) },
                    modifier = Modifier.clickable {
                        onSelect(option)
                        onDismiss()
                    }
                )
            }
        }
    }
}
```

### Popup

```kotlin
@Composable
fun QuickActionsPopup(
    expanded: Boolean,
    anchorBounds: IntRect,
    actions: List<Action>,
    onAction: (Action) -> Unit,
    onDismiss: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(anchorBounds.left.dp, anchorBounds.bottom.dp)
    ) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.title) },
                onClick = {
                    onAction(action)
                    onDismiss()
                },
                leadingIcon = { Icon(action.icon, null) }
            )
        }
    }
}
```

## Best Practices

1. Always provide dismiss callback
2. Use appropriate dialog type (AlertDialog vs BottomSheet)
3. Handle back press for dismissal
4. Mark destructive actions clearly
5. Support accessibility
