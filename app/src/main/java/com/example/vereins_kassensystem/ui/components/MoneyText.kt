package com.example.vereins_kassensystem.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneySmall

/**
 * Every amount the app shows goes through here.
 *
 * Two jobs: formatting is locale-correct in one place instead of at 26 call sites, and
 * the text style always carries tabular figures, so a column of prices lines up instead
 * of shivering as the cart changes.
 *
 * @param signed show a leading + or − because the direction is the point (balance
 *   movements, top-ups). Plain totals should leave this off.
 */
@Composable
fun MoneyText(
    amount: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MoneySmall,
    color: Color = Color.Unspecified,
    signed: Boolean = false,
    textAlign: TextAlign? = null,
    maxLines: Int = 1
) {
    Text(
        text = if (signed) Money.formatSigned(amount) else Money.format(amount),
        modifier = modifier,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines
    )
}

/**
 * Amount rendered in whatever style the surrounding text uses, but still with tabular
 * figures. For amounts that sit inline in a sentence or a dense row where a dedicated
 * money size would be too loud.
 */
@Composable
fun InlineMoneyText(
    amount: Double,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    signed: Boolean = false
) {
    Text(
        text = if (signed) Money.formatSigned(amount) else Money.format(amount),
        modifier = modifier,
        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        color = color,
        maxLines = 1
    )
}
