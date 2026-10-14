package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.data.local.ThemeMode

internal fun LazyListScope.accountSection(
    state: SettingsUiState,
    openDialog: (SettingsDialog) -> Unit,
) {
    item(key = "account_header") {
        SectionHeader(icon = Icons.Outlined.Person, title = "Account")
    }

    item(key = "account_info") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (state.username.isNotBlank()) {
                InfoRow(label = "Username", value = state.username)
            }
            if (state.email.isNotBlank()) {
                InfoRow(label = "Email", value = state.email)
            }
            InfoRow(
                label = "Auth method",
                value = when (state.authMethod) {
                    "oidc" -> "Signed in via SSO"
                    "password" -> "Password"
                    "api_token" -> "API Token"
                    else -> state.authMethod.ifBlank { "Unknown" }
                },
            )
            if (state.vikunjaUrl.isNotBlank()) {
                InfoRow(label = "Server", value = state.vikunjaUrl)
            }
        }
    }

    item(key = "account_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

internal fun LazyListScope.appearanceSection(
    state: SettingsUiState,
    useDeviceColors: Boolean,
    viewModel: SettingsViewModel,
) {
    item(key = "theme_header") {
        Text(
            text = "Appearance",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }

    item(key = "theme_picker") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Theme",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            @OptIn(ExperimentalMaterial3Api::class)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                val options = listOf(
                    ThemeMode.System to "System",
                    ThemeMode.Light to "Light",
                    ThemeMode.Dark to "Dark",
                )
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }

    item(key = "use_device_colors") {
        SwitchRow(
            label = "Use device colours",
            description = "Follow your wallpaper's Material You palette instead of the Vicu colours (Android 12 and later)",
            checked = useDeviceColors,
            onCheckedChange = viewModel::setUseDeviceColors,
        )
    }

    item(key = "theme_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

internal fun LazyListScope.signOutSection(
    openDialog: (SettingsDialog) -> Unit,
) {
    item(key = "logout") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { openDialog(SettingsDialog.SignOut) })
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ExitToApp,
                contentDescription = "Sign Out",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Sign Out",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    item(key = "general_bottom_spacer") {
        Spacer(modifier = Modifier.height(32.dp))
    }
}
