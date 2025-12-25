package com.bothbubbles.ui.components.dialogs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.ui.components.common.GroupAvatar
import com.bothbubbles.util.PhoneNumberFormatter

/**
 * Data class containing contact information for quick actions
 */
data class ContactInfo(
    val chatGuid: String,
    val displayName: String,  // May include "Maybe:" prefix for inferred names
    val rawDisplayName: String,  // Never includes "Maybe:" - use for contact intents and avatars
    val avatarPath: String?,
    val address: String,  // Phone number or email
    val isGroup: Boolean,
    val participantNames: List<String> = emptyList(),
    val participantAvatarPaths: List<String?> = emptyList(),  // Avatar paths for group participants
    val chatAvatarPath: String? = null,  // Custom group photo (takes precedence over participant collage)
    val hasContact: Boolean = false,  // True if this person is a saved contact
    val isStarred: Boolean = false,  // True if this contact is starred (favorite) in Android Contacts
    val hasInferredName: Boolean = false  // True if displayName includes "Maybe:" prefix
)

/**
 * A Material Design 3 Modal Bottom Sheet for contact quick actions.
 * Shows when tapping on a contact's avatar in the conversation list.
 *
 * Features:
 * - Drag handle for easy dismissal
 * - Large centered avatar with dynamic theming
 * - FilledTonalButtons with labels for actions
 * - TertiaryContainer Card for inferred name section
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactQuickActionsPopup(
    contactInfo: ContactInfo,
    onDismiss: () -> Unit,
    onMessageClick: () -> Unit,
    onDismissInferredName: () -> Unit = {},  // Called when user dismisses the inferred name
    onContactAdded: () -> Unit = {},  // Called when user returns from adding a contact
    onSetGroupPhoto: () -> Unit = {},  // Called when user wants to set/change group photo
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Add contact launcher - calls onContactAdded when returning from contacts app
    val addContactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onContactAdded()
    }

    // Entrance animation state for "expand from list" effect
    var animationStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animationStarted = true }

    // Avatar expand animation (simulates shared element transition)
    val avatarScale by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0.4f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "avatarScale"
    )

    // Content fade-in animation
    val contentAlpha by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "contentAlpha"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Large avatar with expand animation (simulates shared element)
            // Priority: chatAvatarPath (custom group photo) > GroupAvatar (collage) > Avatar (contact)
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(avatarScale)
                    .clip(RoundedCornerShape(24.dp))
            ) {
                when {
                    // Custom group photo takes precedence
                    contactInfo.isGroup && contactInfo.chatAvatarPath != null -> {
                        Avatar(
                            name = contactInfo.displayName,
                            avatarPath = contactInfo.chatAvatarPath,
                            size = 120.dp,
                            hasContactInfo = contactInfo.hasContact
                        )
                    }
                    // Group chat without custom photo - show participant collage
                    contactInfo.isGroup -> {
                        GroupAvatar(
                            names = contactInfo.participantNames.ifEmpty {
                                listOf(contactInfo.displayName)
                            },
                            avatarPaths = contactInfo.participantAvatarPaths,
                            size = 120.dp
                        )
                    }
                    // 1:1 chat - show contact photo
                    else -> {
                        Avatar(
                            name = contactInfo.rawDisplayName,
                            avatarPath = contactInfo.avatarPath,
                            size = 120.dp,
                            hasContactInfo = contactInfo.hasContact
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content below avatar with fade-in animation
            Column(
                modifier = Modifier.graphicsLayer { alpha = contentAlpha },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Display name (Headline Medium) with optional starred indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = contactInfo.displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (contactInfo.isStarred && !contactInfo.isGroup) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Favorite contact",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Phone/email subtitle (Body Large, OnSurfaceVariant)
                if (contactInfo.address != contactInfo.displayName && !contactInfo.isGroup) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = PhoneNumberFormatter.format(contactInfo.address),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons row - FilledTonalButtons with labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    // Message button
                    ActionButtonWithLabel(
                        icon = Icons.AutoMirrored.Filled.Message,
                        label = "Message",
                        onClick = {
                            onMessageClick()
                            onDismiss()
                        }
                    )

                    // Set group photo (only for group chats)
                    if (contactInfo.isGroup) {
                        ActionButtonWithLabel(
                            icon = Icons.Default.Image,
                            label = "Photo",
                            onClick = {
                                onSetGroupPhoto()
                                onDismiss()
                            }
                        )
                    }

                    // Call (only for non-group chats)
                    if (!contactInfo.isGroup) {
                        ActionButtonWithLabel(
                            icon = Icons.Default.Call,
                            label = "Call",
                            onClick = {
                                launchDialer(context, contactInfo.address)
                                onDismiss()
                            }
                        )
                    }

                    // Contact info / Add contact button (for non-group chats only)
                    if (!contactInfo.isGroup) {
                        if (contactInfo.hasContact) {
                            ActionButtonWithLabel(
                                icon = Icons.Default.Info,
                                label = "Info",
                                onClick = {
                                    viewContact(context, contactInfo.address)
                                    onDismiss()
                                }
                            )
                        } else {
                            ActionButtonWithLabel(
                                icon = Icons.Default.PersonAdd,
                                label = "Add",
                                onClick = {
                                    val intent = createAddContactIntent(contactInfo.address, contactInfo.rawDisplayName)
                                    addContactLauncher.launch(intent)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }  // Close Row for action buttons

                // Inferred name confirmation section - TertiaryContainer Card
                if (contactInfo.hasInferredName && !contactInfo.isGroup) {
                    Spacer(modifier = Modifier.height(24.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "We think this might be ${contactInfo.rawDisplayName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Dismiss button - OutlinedButton
                                OutlinedButton(
                                    onClick = {
                                        onDismissInferredName()
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Not them")
                                }

                                // Save as contact button - FilledTonalButton
                                FilledTonalButton(
                                    onClick = {
                                        launchAddContact(context, contactInfo.address, contactInfo.rawDisplayName)
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary,
                                        contentColor = MaterialTheme.colorScheme.onTertiary
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.PersonAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save contact")
                                }
                            }
                        }
                    }
                }
            }  // Close animated content Column
        }
    }
}

/**
 * MD3-style action button with icon and label.
 * Uses FilledTonalButton styling for better touch targets and visual hierarchy.
 */
@Composable
private fun ActionButtonWithLabel(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Launch the phone dialer with the given number
 */
private fun launchDialer(context: Context, phoneNumber: String) {
    val intent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Handle case where no dialer app is available
    }
}

/**
 * Create an intent to add a contact with pre-filled info
 */
private fun createAddContactIntent(address: String, name: String): Intent {
    return Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE

        // Check if address looks like a phone number or email
        if (address.contains("@")) {
            putExtra(ContactsContract.Intents.Insert.EMAIL, address)
        } else {
            putExtra(ContactsContract.Intents.Insert.PHONE, address)
        }

        // Only set name if it's different from the address (i.e., not just a number)
        if (name != address) {
            putExtra(ContactsContract.Intents.Insert.NAME, name)
        }
    }
}

/**
 * Launch the add contact intent with pre-filled info
 */
private fun launchAddContact(context: Context, address: String, name: String) {
    val intent = createAddContactIntent(address, name)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Handle case where no contacts app is available
    }
}

/**
 * View an existing contact by looking up their phone number or email
 */
private fun viewContact(context: Context, address: String) {
    try {
        // Look up contact by phone number or email
        val contactUri = if (address.contains("@")) {
            // Email lookup
            Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
        } else {
            // Phone number lookup
            Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
        }

        val projection = arrayOf(ContactsContract.Contacts._ID)
        val cursor = context.contentResolver.query(contactUri, projection, null, null, null)

        cursor?.use {
            if (it.moveToFirst()) {
                val contactId = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val contactViewUri = Uri.withAppendedPath(
                    ContactsContract.Contacts.CONTENT_URI,
                    contactId.toString()
                )
                val intent = Intent(Intent.ACTION_VIEW, contactViewUri)
                context.startActivity(intent)
            }
        }
    } catch (e: Exception) {
        // Handle case where contact lookup fails or no contacts app is available
    }
}
