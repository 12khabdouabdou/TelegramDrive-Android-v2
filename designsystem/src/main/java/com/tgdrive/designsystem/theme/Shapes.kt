package com.tgdrive.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Component shape tokens — Material 3 Expressive rounded corners.
 *
 * Matches the Tailwind borderRadius config in the HTML mockups:
 *   - DEFAULT: 4dp (0.25rem) — small chips, inputs
 *   - lg: 8dp (0.5rem) — buttons, small cards
 *   - xl: 12dp (0.75rem) — list items, icon bubbles
 *   - 2xl: 16dp (1rem) — cards, panels, bottom sheets
 *   - 3xl: 24dp (1.5rem) — large hero cards, modals
 */
object TgShapes {
    val Small = RoundedCornerShape(4.dp)    // chips
    val Medium = RoundedCornerShape(8.dp)   // buttons
    val Large = RoundedCornerShape(12.dp)   // list items, icon bubbles
    val XLarge = RoundedCornerShape(16.dp)  // cards, panels
    val XXLarge = RoundedCornerShape(24.dp) // hero cards, bottom sheets
    val Pill = RoundedCornerShape(50)

    // Backward compat aliases
    val Card = XLarge
    val CardLarge = XXLarge
    val BottomSheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    val MaterialShapes = Shapes(
        small = Small,
        medium = Large,
        large = XLarge,
        extraLarge = XXLarge,
    )
}
