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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val hasContact: Boolean = false,  // True if this person is a saved contact
    val isStarred: Boolean = false,  // True if this contact is starred (favorite) in Android Contacts
    val hasInferredName: Boolean = false  // True if displayName includes "Maybe:" prefix
)

/**
 * A Google Messages-style contact quick actions popup.
 * Shows when tapping on a contact's avatar in the conversation list.
 */
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

    // Add contact launcher - calls onContactAdded when returning from contacts app
    val addContactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onContactAdded()
    }

    // Entrance animation state
    var animationStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animationStarted = true }

    // Snappy scale + fade animation (Android 16 style)
    val scale by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "popupScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = tween(150),
        label = "popupAlpha"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = modifier
                .padding(horizontal = 40.dp)
                .wrapContentSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large avatar
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(24.dp))
                ) {
                    if (contactInfo.isGroup) {
                        GroupAvatar(
                            names = contactInfo.participantNames.ifEmpty {
                                listOf(contactInfo.displayName)
                            },
                            size = 120.dp
                        )
                    } else {
                        Avatar(
                            name = contactInfo.rawDisplayName,
                            avatarPath = contactInfo.avatarPath,
                            size = 120.dp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Display name with optional starred indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = contactInfo.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (contactInfo.isStarred && !contactInfo.isGroup) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Favorite contact",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Phone/email if different from display name
                if (contactInfo.address != contactInfo.displayName && !contactInfo.isGroup) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = PhoneNumberFormatter.format(contactInfo.address),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons row
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.wrapContentWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Message
                        QuickActionButton(
                            icon = Icons.AutoMirrored.Filled.Message,
                            contentDescription = "Message",
                            onClick = {
                                onMessageClick()
                                onDismiss()
                            }
                        )

                        // Set group photo (only for group chats)
                        if (contactInfo.isGroup) {
                            QuickActionButton(
                                icon = Icons.Default.Image,
                                contentDescription = "Set group photo",
                                onClick = {
                                    onSetGroupPhoto()
                                    onDismiss()
                                }
                            )
                        }

                        // Call (only for non-group chats)
                        if (!contactInfo.isGroup) {
                            QuickActionButton(
                                icon = Icons.Default.Call,
                                contentDescription = "Call",
                                onClick = {
                                    launchDialer(context, contactInfo.address)
                                    onDismiss()
                                }
                            )
                        }

                        // Contact info / Add contact button (for non-group chats only)
                        // Shows "Contact info" if person is a saved contact, "Add contact" otherwise
                        if (!contactInfo.isGroup) {
                            if (contactInfo.hasContact) {
                                QuickActionButton(
                                    icon = Icons.Default.Info,
                                    contentDescription = "Contact info",
                                    onClick = {
                                        viewContact(context, contactInfo.address)
                                        onDismiss()
                                    }
                                )
                            } else {
                                QuickActionButton(
                                    icon = Icons.Default.PersonAdd,
                                    contentDescription = "Add contact",
                                    onClick = {
                                        val intent = createAddContactIntent(contactInfo.address, contactInfo.rawDisplayName)
                                        addContactLauncher.launch(intent)
                                        onDismiss()
                                    }
                                )
                            }
                        }

                    }
                }

                // Inferred name confirmation section
                if (contactInfo.hasInferredName && !contactInfo.isGroup) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "We think this might be ${contactInfo.rawDisplayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Dismiss button
                        OutlinedButton(
                            onClick = {
                                onDismissInferredName()
                                onDismiss()
                            }
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Not them")
                        }

                        // Save as contact button
                        Button(
                            onClick = {
                                launchAddContact(context, contactInfo.address, contactInfo.rawDisplayName)
                                onDismiss()
                            }
                        ) {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save contact")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
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
