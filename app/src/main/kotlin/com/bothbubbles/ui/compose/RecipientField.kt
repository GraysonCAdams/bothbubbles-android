package com.bothbubbles.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

/**
 * Apple-style recipient field with chips and inline text input.
 *
 * @param chips List of selected recipient chips
 * @param inputText Current text in the input field
 * @param effectiveService The effective service type for chip coloring
 * @param isLocked Whether the field is locked (group selected)
 * @param onInputChange Callback when input text changes
 * @param onChipRemove Callback when a chip's X is clicked
 * @param onEnterPressed Callback when Enter is pressed
 * @param focusRequester FocusRequester for auto-focusing
 * @param modifier Modifier for the field container
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipientField(
    chips: ImmutableList<RecipientChip>,
    inputText: String,
    effectiveService: RecipientService,
    isLocked: Boolean,
    onInputChange: (String) -> Unit,
    onChipRemove: (RecipientChip) -> Unit,
    onEnterPressed: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    // Auto-focus on mount
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // "To:" label
            Text(
                text = "To:",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Chips + input in a flow layout
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Render chips
                chips.forEach { chip ->
                    RecipientChipView(
                        chip = chip,
                        displayService = effectiveService,
                        onRemove = { onChipRemove(chip) }
                    )
                }

                // Input field (only if not locked)
                if (!isLocked) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .height(32.dp)
                            .width(if (chips.isEmpty()) 200.dp else 100.dp.coerceAtLeast(inputText.length.dp * 8))
                            .onKeyEvent { event ->
                                when {
                                    event.key == Key.Backspace && inputText.isEmpty() && chips.isNotEmpty() -> {
                                        // Remove last chip on backspace when input is empty
                                        onChipRemove(chips.last())
                                        true
                                    }
                                    event.key == Key.Enter -> {
                                        onEnterPressed()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { onEnterPressed() }
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (inputText.isEmpty() && chips.isEmpty()) {
                                    Text(
                                        text = "Name, phone, or email",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
}

/**
 * A single recipient chip with service-based coloring.
 */
@Composable
private fun RecipientChipView(
    chip: RecipientChip,
    displayService: RecipientService,
    onRemove: () -> Unit
) {
    val backgroundColor = remember(displayService) {
        when (displayService) {
            RecipientService.IMESSAGE -> Color(0xFF007AFF) // Apple blue
            RecipientService.SMS -> Color(0xFF34C759) // Apple green
            RecipientService.INVALID -> Color(0xFFFF3B30) // Apple red
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = chip.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )

            Spacer(modifier = Modifier.width(4.dp))

            // X button to remove chip
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.3f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove ${chip.displayName}",
                    modifier = Modifier.size(14.dp),
                    tint = Color.White
                )
            }
        }
    }
}
