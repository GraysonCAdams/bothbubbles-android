package com.bothbubbles.ui.chatcreator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.ui.components.common.Avatar

/**
 * M3 InputChip for displaying a selected recipient with platform color-coding.
 * Uses Material 3 InputChip for proper semantics and accessibility.
 */
@Composable
fun RecipientChip(
    recipient: SelectedRecipient,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isIMessage = recipient.service.equals("iMessage", ignoreCase = true)
    val containerColor = if (isIMessage) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isIMessage) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    InputChip(
        selected = true,
        onClick = onRemove,
        label = {
            Text(
                text = recipient.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingIcon = {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove ${recipient.displayName}",
                modifier = Modifier.size(18.dp)
            )
        },
        colors = InputChipDefaults.inputChipColors(
            selectedContainerColor = containerColor,
            selectedLabelColor = contentColor,
            selectedTrailingIconColor = contentColor
        ),
        border = null,
        modifier = modifier
    )
}

/**
 * M3 ListItem for displaying a contact in the contacts list.
 * Uses Material 3 ListItem for proper semantics and accessibility.
 *
 * @param contact The contact to display
 * @param isSelected Whether the contact is currently selected
 * @param showCheckbox Whether to show a checkbox (for group selection mode)
 * @param onClick Callback when the contact is clicked
 */
@Composable
fun ContactTile(
    contact: ContactUiModel,
    isSelected: Boolean = false,
    showCheckbox: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isIMessage = contact.service.equals("iMessage", ignoreCase = true)
    val selectedColor = if (isIMessage) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    ListItem(
        headlineContent = {
            Text(
                text = contact.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = contact.formattedAddress,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            // Avatar with selection/favorite overlay
            Box(modifier = Modifier.size(48.dp)) {
                Avatar(
                    name = contact.displayName,
                    avatarPath = contact.avatarPath,
                    size = 48.dp,
                    hasContactInfo = true // These are saved contacts
                )

                // Selection checkmark indicator
                if (isSelected && !showCheckbox) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(
                                if (isIMessage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = if (isIMessage) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSecondary
                            },
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Favorite indicator - star icon in top right
                if (contact.isFavorite && !isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Favorite",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        },
        trailingContent = if (showCheckbox) {
            {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null, // Handled by ListItem click
                    colors = CheckboxDefaults.colors(
                        checkedColor = if (isIMessage) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                )
            }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) {
                selectedColor.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
                role = if (showCheckbox) Role.Checkbox else Role.Button
            )
    )
}

/**
 * M3 ListItem for starting a conversation with a manually entered address.
 * Uses Material 3 ListItem for proper semantics and accessibility.
 */
@Composable
fun ManualAddressTile(
    address: String,
    service: String,
    isCheckingAvailability: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isIMessage = service == "iMessage"
    val containerColor = if (isIMessage) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isIMessage) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    ListItem(
        headlineContent = {
            Text(
                text = "Send to $address",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = if (isCheckingAvailability) {
                    "Checking availability..."
                } else {
                    "Start new conversation"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(containerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        trailingContent = {
            if (isCheckingAvailability) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Surface(
                    color = containerColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = service,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !isCheckingAvailability, onClick = onClick)
    )
}

/**
 * M3 ListItem for the "Create group" action at the top of contact list.
 */
@Composable
fun CreateGroupListItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = {
            Text(
                text = "Create group",
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            Text(
                text = "Start a group conversation",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.GroupAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}

/**
 * M3 Recipient field that combines InputChips with a text input field.
 * Supports inline chips with text input, wrapping across multiple lines.
 *
 * @param recipients List of currently selected recipients displayed as InputChips
 * @param searchQuery Current text in the search field
 * @param onSearchQueryChange Callback when search text changes
 * @param onRemoveRecipient Callback when a recipient chip is removed
 * @param onRemoveLastRecipient Callback when backspace is pressed on empty field
 * @param onDone Callback when Done is pressed on keyboard
 * @param focusRequester FocusRequester for the text field
 * @param placeholder Placeholder text when no recipients and empty query
 * @param addMorePlaceholder Placeholder text when recipients exist but query is empty
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun M3RecipientField(
    recipients: List<SelectedRecipient>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onRemoveRecipient: (String) -> Unit,
    onRemoveLastRecipient: () -> Unit,
    onDone: () -> Unit,
    focusRequester: FocusRequester,
    placeholder: String = "Type name, phone number, or email",
    addMorePlaceholder: String = "Add another recipient...",
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // "To:" label
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .padding(end = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "To:",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Recipient chips
            recipients.forEach { recipient ->
                RecipientChip(
                    recipient = recipient,
                    onRemove = { onRemoveRecipient(recipient.address) }
                )
            }

            // Text input field
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 80.dp)
                    .height(32.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onKeyEvent { event ->
                            // Handle backspace when input is empty to remove last recipient
                            if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.Backspace &&
                                searchQuery.isEmpty() &&
                                recipients.isNotEmpty()
                            ) {
                                onRemoveLastRecipient()
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onDone() }
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = if (recipients.isEmpty()) placeholder else addMorePlaceholder,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }
    }
}

/**
 * Card shown when the app doesn't have contacts permission.
 * Displays a message explaining the need for permission and a button to open settings.
 */
@Composable
fun ContactsPermissionCard(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Contacts,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Title
            Text(
                text = "See your contacts here",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Description
            Text(
                text = "Enable contacts to quickly find and message people. You can always type phone numbers or email addresses instead.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // Button
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Enable Contacts")
            }
        }
    }
}

/**
 * A list item for displaying a popular chat in the vertical Popular section.
 * Shows avatar, display name, and indicates if it's a group or 1:1 chat.
 * Groups show a group icon badge, 1:1 chats show iMessage/SMS service badge.
 */
@Composable
fun PopularChatListItem(
    popularChat: PopularChatUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isIMessage = popularChat.service.equals("iMessage", ignoreCase = true)
    val containerColor = if (isIMessage) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isIMessage) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    ListItem(
        headlineContent = {
            Text(
                text = popularChat.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = if (!popularChat.isGroup && popularChat.identifier != null) {
            {
                Text(
                    text = popularChat.identifier,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else null,
        leadingContent = {
            Box(modifier = Modifier.size(48.dp)) {
                Avatar(
                    name = popularChat.displayName,
                    avatarPath = popularChat.avatarPath,
                    size = 48.dp,
                    hasContactInfo = popularChat.avatarPath != null
                )

                // Badge for group indicator
                if (popularChat.isGroup) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GroupAdd,
                            contentDescription = "Group",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        },
        trailingContent = {
            // Service badge for 1:1 chats
            if (!popularChat.isGroup && popularChat.service.isNotEmpty()) {
                Surface(
                    color = containerColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isIMessage) "iMessage" else "SMS",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}

/**
 * A section displaying a preview of the conversation with selected recipients.
 * Uses the shared MessagePreviewList component for consistent rendering with the main chat.
 *
 * Features shown in preview:
 * - Full message bubbles with reactions
 * - Sender names and avatars in group chats
 * - Attachment thumbnails
 */
@Composable
fun ConversationPreviewSection(
    previewState: ConversationPreviewState,
    modifier: Modifier = Modifier
) {
    // Convert ChatCreator's ConversationPreviewState to the shared MessagePreviewListState
    val listState = when (previewState) {
        is ConversationPreviewState.Loading ->
            com.bothbubbles.ui.components.message.MessagePreviewListState.Loading
        is ConversationPreviewState.NewConversation ->
            com.bothbubbles.ui.components.message.MessagePreviewListState.NewConversation
        is ConversationPreviewState.Existing ->
            com.bothbubbles.ui.components.message.MessagePreviewListState.Existing(
                chatGuid = previewState.chatGuid,
                messages = previewState.messages,
                isGroup = previewState.isGroup
            )
    }

    com.bothbubbles.ui.components.message.MessagePreviewList(
        previewState = listState,
        modifier = modifier
    )
}
