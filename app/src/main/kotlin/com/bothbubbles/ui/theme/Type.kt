package com.bothbubbles.ui.theme

// Re-export from core:design for backward compatibility
// Feature modules and app code can import from either location
import com.bothbubbles.core.design.theme.GoogleSansFlexFamily as CoreGoogleSansFlexFamily
import com.bothbubbles.core.design.theme.PlusJakartaFamily as CorePlusJakartaFamily
import com.bothbubbles.core.design.theme.KumbhSansFamily as CoreKumbhSansFamily
import com.bothbubbles.core.design.theme.GoogleSansFamily as CoreGoogleSansFamily
import com.bothbubbles.core.design.theme.AppTypography

// Re-exported font families
val GoogleSansFlexFamily = CoreGoogleSansFlexFamily
val PlusJakartaFamily = CorePlusJakartaFamily
val KumbhSansFamily = CoreKumbhSansFamily
val GoogleSansFamily = CoreGoogleSansFamily

// Re-exported typography
val Typography = AppTypography
