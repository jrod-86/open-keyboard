// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickBack: () -> Unit,
    onClickTheme: () -> Unit = {},
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Open Keyboard: grouped rounded cards, like Gboard's settings
                val ctx = LocalContext.current
                SettingsGroup {
                    GroupItem(
                        name = stringResource(R.string.language_and_layouts_title),
                        description = enabledSubtypes.joinToString(", ") { it.displayName() },
                        onClick = onClickLanguage,
                        icon = R.drawable.ic_settings_languages
                    ) { NextScreenIcon() }
                    GroupItem(
                        name = stringResource(R.string.settings_screen_preferences),
                        onClick = onClickPreferences,
                        icon = R.drawable.ic_settings_preferences
                    ) { NextScreenIcon() }
                    GroupItem(
                        name = "Theme",
                        onClick = onClickTheme,
                        icon = R.drawable.ic_settings_appearance
                    ) { NextScreenIcon() }
                    GroupItem(
                        name = "Size & style",
                        description = "Keyboard height, key shape, fonts",
                        onClick = onClickAppearance,
                        icon = R.drawable.ic_settings_layout
                    ) { NextScreenIcon() }
                }
                SettingsGroup {
                    GroupItem(
                        name = stringResource(R.string.settings_screen_correction),
                        onClick = onClickTextCorrection,
                        icon = R.drawable.ic_settings_correction
                    ) { NextScreenIcon() }
                    if (JniUtils.sHaveGestureLib)
                        GroupItem(
                            name = stringResource(R.string.settings_screen_gesture),
                            onClick = onClickGestureTyping,
                            icon = R.drawable.ic_settings_gesture
                        ) { NextScreenIcon() }
                    // we don't even show the menu if data gathering phase ended more than 2 weeks ago
                    if (JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS)
                        GroupItem(
                            name = stringResource(R.string.gesture_data_screen),
                            onClick = onClickDataGathering,
                            icon = R.drawable.ic_settings_gesture
                        ) { NextScreenIcon() }
                    GroupItem(
                        name = "Voice typing",
                        description = "Microphone, speech models and accuracy check",
                        onClick = { ctx.startActivity(android.content.Intent(ctx, helium314.keyboard.voice.VoiceSetupActivity::class.java)) },
                        icon = R.drawable.sym_keyboard_voice_rounded
                    ) { NextScreenIcon() }
                    GroupItem(
                        name = "Stickers and GIFs",
                        description = "Built-in stickers, and GIF search you can switch on",
                        onClick = { ctx.startActivity(android.content.Intent(ctx, helium314.keyboard.media.GifSetupActivity::class.java)) },
                        icon = R.drawable.ic_sticker
                    ) { NextScreenIcon() }
                    GroupItem(
                        name = stringResource(R.string.settings_screen_toolbar),
                        description = "Emoji, clipboard and other buttons above the keys",
                        onClick = onClickToolbar,
                        icon = R.drawable.ic_settings_toolbar
                    ) { NextScreenIcon() }
                }
                SettingsGroup {
                    GroupItem(
                        name = stringResource(R.string.dictionary_settings_category),
                        onClick = onClickDictionaries,
                        icon = R.drawable.ic_dictionary
                    ) { NextScreenIcon() }
                    GroupItem(
                        name = stringResource(R.string.settings_screen_secondary_layouts),
                        onClick = onClickLayouts,
                        icon = R.drawable.ic_settings_layout
                    ) { NextScreenIcon() }
                }
                SettingsGroup {
                    GroupItem(
                        name = stringResource(R.string.settings_screen_advanced),
                        onClick = onClickAdvanced,
                        icon = R.drawable.ic_settings_advanced
                    ) { NextScreenIcon() }
                    GroupItem(
                        name = stringResource(R.string.settings_screen_about),
                        onClick = onClickAbout,
                        icon = R.drawable.ic_settings_about
                    ) { NextScreenIcon() }
                }
            }
        }
    }
}

/** A rounded card holding related settings, each row on its own tinted strip like Gboard. */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
}

@Composable
private fun GroupItem(
    name: String,
    onClick: () -> Unit,
    description: String? = null,
    icon: Int? = null,
    value: @Composable (androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) = Preference(
    name = name,
    onClick = onClick,
    modifier = Modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(vertical = 8.dp, horizontal = 8.dp),
    description = description,
    icon = icon,
    value = value,
)

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
