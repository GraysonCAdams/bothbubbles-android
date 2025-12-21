package com.bothbubbles.ui.setup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhonelinkOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bothbubbles.util.HapticUtils

@Composable
internal fun SmsSetupPage(
    uiState: SetupUiState,
    onSmsEnabledChange: (Boolean) -> Unit,
    getMissingSmsPermissions: () -> Array<String>,
    getDefaultSmsAppIntent: () -> Intent,
    onSmsPermissionsResult: () -> Unit,
    onDefaultSmsAppResult: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val smsStatus = uiState.smsCapabilityStatus

    // Track if we should auto-prompt for default SMS after permissions granted
    var shouldPromptDefaultSms by remember { mutableStateOf(false) }

    // Launcher to set as default SMS app
    val setDefaultSmsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onDefaultSmsAppResult()
    }

    // Permission launcher for SMS permissions
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onSmsPermissionsResult()
        // If all permissions were granted, prompt for default SMS app
        if (results.values.all { it }) {
            shouldPromptDefaultSms = true
        }
    }

    // Auto-launch default SMS prompt after permissions are granted
    LaunchedEffect(shouldPromptDefaultSms, smsStatus) {
        if (shouldPromptDefaultSms &&
            smsStatus?.missingPermissions?.isEmpty() == true &&
            smsStatus.isDefaultSmsApp == false) {
            shouldPromptDefaultSms = false
            setDefaultSmsLauncher.launch(getDefaultSmsAppIntent())
        }
    }

    // On initial page load, check if we should prompt for default SMS app
    // (permissions already granted but not default app yet)
    var hasPromptedOnLoad by remember { mutableStateOf(false) }
    LaunchedEffect(smsStatus, uiState.smsEnabled) {
        if (!hasPromptedOnLoad &&
            uiState.smsEnabled &&
            smsStatus?.missingPermissions?.isEmpty() == true &&
            smsStatus.isDefaultSmsApp == false) {
            hasPromptedOnLoad = true
            // Small delay to let the UI settle
            kotlinx.coroutines.delay(500)
            setDefaultSmsLauncher.launch(getDefaultSmsAppIntent())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SMS/MMS Setup",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enable SMS/MMS as a fallback or primary messaging option",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // SMS toggle card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Sms,
                    contentDescription = null,
                    tint = if (uiState.smsEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable SMS/MMS",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Send and receive text messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                val haptic = LocalHapticFeedback.current
                Switch(
                    checked = uiState.smsEnabled,
                    onCheckedChange = {
                        HapticUtils.onConfirm(haptic)
                        onSmsEnabledChange(it)
                    }
                )
            }
        }

        // SMS capability status
        if (uiState.smsEnabled && smsStatus != null) {
            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = when {
                    smsStatus.isFullyFunctional -> MaterialTheme.colorScheme.primaryContainer
                    !smsStatus.deviceSupportsSms -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when {
                                smsStatus.isFullyFunctional -> Icons.Default.CheckCircle
                                !smsStatus.deviceSupportsSms -> Icons.Default.PhonelinkOff
                                else -> Icons.Default.Warning
                            },
                            contentDescription = null,
                            tint = when {
                                smsStatus.isFullyFunctional -> MaterialTheme.colorScheme.primary
                                !smsStatus.deviceSupportsSms -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.tertiary
                            }
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = when {
                                smsStatus.isFullyFunctional -> "SMS Fully Configured"
                                !smsStatus.deviceSupportsSms -> "Device Doesn't Support SMS"
                                else -> "SMS Setup Required"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = when {
                                smsStatus.isFullyFunctional -> MaterialTheme.colorScheme.onPrimaryContainer
                                !smsStatus.deviceSupportsSms -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onTertiaryContainer
                            }
                        )
                    }

                    // Status indicators
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatusIndicator("Read", smsStatus.canReadSms)
                        StatusIndicator("Send", smsStatus.canSendSms)
                        StatusIndicator("Receive", smsStatus.canReceiveSms)
                    }

                    // Show setup buttons if needed
                    if (smsStatus.needsSetup) {
                        Spacer(modifier = Modifier.height(16.dp))

                        // Request permissions if missing
                        if (smsStatus.missingPermissions.isNotEmpty()) {
                            Button(
                                onClick = {
                                    smsPermissionLauncher.launch(getMissingSmsPermissions())
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Grant SMS Permissions (${smsStatus.missingPermissions.size} needed)")
                            }
                        }

                        // Request default SMS app if we have receive permission but aren't default
                        if (!smsStatus.isDefaultSmsApp && smsStatus.hasReceivePermission) {
                            if (smsStatus.missingPermissions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Button(
                                onClick = {
                                    setDefaultSmsLauncher.launch(getDefaultSmsAppIntent())
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Sms, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Set as Default SMS App")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // SMS usage explanation
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "When to use SMS/MMS",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                val useCases = listOf(
                    "When BlueBubbles server is unreachable",
                    "Messaging non-iMessage contacts",
                    "As a backup for important messages",
                    "When you don't have a Mac available"
                )

                useCases.forEach { useCase ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = useCase,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back")
            }

            if (uiState.isConnectionSuccessful) {
                // If server is connected, go to sync
                Button(onClick = onNext) {
                    Text("Continue")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            } else {
                // If server skipped, finish setup
                Button(onClick = onFinish) {
                    Text("Finish Setup")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.Check, contentDescription = null)
                }
            }
        }
    }
}

@Composable
internal fun StatusIndicator(label: String, enabled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            if (enabled) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    }
}
