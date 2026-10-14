package com.rendyhd.vicu.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.ProjectShares
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.model.toCollabUser
import com.rendyhd.vicu.ui.components.collab.LocalCollab
import com.rendyhd.vicu.ui.components.collab.PersonAvatar
import com.rendyhd.vicu.ui.screens.shared.ProjectActionResult
import com.rendyhd.vicu.ui.screens.shared.ProjectActions
import com.rendyhd.vicu.util.Collaboration
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.Permission
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** The copy above the share list, the same on both apps (cross-app semantics 9.7). */
internal const val SHARE_INTRO =
    "People you add see every task in this project. Assignees and comments appear on its tasks once it is shared. " +
        "Your other projects stay private."

internal const val SHARE_NO_MATCH =
    "No one with that username. Names and emails only match people who made themselves findable."

internal fun permissionLabel(permission: Int): String = when {
    permission >= Permission.ADMIN -> "Admin"
    permission >= Permission.WRITE -> "Can edit"
    else -> "Can view"
}

/** What typing a username found. */
private sealed interface Lookup {
    data object Idle : Lookup
    data object Searching : Lookup
    data object NoMatch : Lookup
    data object AlreadyMember : Lookup
    data class Match(val user: User) : Lookup
    data class Failed(val message: String) : Lookup
}

/**
 * "Shared with" in Edit project (design handoff M5), for project admins only: the owner, the people
 * and the teams the project is shared with, a permission per person, and a username field to add
 * someone. Every change goes to the server at once through [ProjectActions]; teams are listed only
 * (they are managed in Vikunja). Removing someone shows an Undo here, beside the list.
 */
@Composable
fun ProjectSharingSection(project: Project, modifier: Modifier = Modifier) {
    val collab = LocalCollab.current
    if (!collab.access(project.id).isAdmin) return
    val actions: ProjectActions = koinInject()
    val scope = rememberCoroutineScope()
    val me = collab.state.me

    var reload by remember { mutableIntStateOf(0) }
    var shares by remember { mutableStateOf<ProjectShares?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(project.id, reload) {
        when (val result = actions.shares(project.id)) {
            is NetworkResult.Success -> {
                shares = result.data
                loadError = null
            }
            is NetworkResult.Error -> loadError = result.message
            NetworkResult.Loading -> Unit
        }
    }

    var username by remember { mutableStateOf("") }
    var permission by remember { mutableIntStateOf(Permission.WRITE) }
    var lookup by remember { mutableStateOf<Lookup>(Lookup.Idle) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // The person just removed and what they could do, for Undo.
    var removed by remember { mutableStateOf<Pair<User, Int>?>(null) }

    val memberIds = remember(shares, collab.access(project.id).members) {
        buildSet {
            collab.access(project.id).members.forEach { add(it.id) }
            shares?.owner?.let { add(it.id) }
            shares?.users?.forEach { add(it.user.id) }
        }
    }
    LaunchedEffect(username, memberIds) {
        val query = username.trim().removePrefix("@")
        if (query.isEmpty()) {
            lookup = Lookup.Idle
            return@LaunchedEffect
        }
        lookup = Lookup.Searching
        delay(300)
        lookup = when (val result = actions.findUser(query)) {
            is NetworkResult.Success -> when (val user = result.data) {
                null -> Lookup.NoMatch
                else -> if (user.id in memberIds) Lookup.AlreadyMember else Lookup.Match(user)
            }
            is NetworkResult.Error -> Lookup.Failed(result.message)
            NetworkResult.Loading -> Lookup.Searching
        }
    }

    fun run(change: suspend () -> ProjectActionResult, after: (ProjectActionResult.Done) -> Unit = {}) {
        busy = true
        scope.launch {
            when (val result = change()) {
                is ProjectActionResult.Done -> {
                    after(result)
                    reload++
                }
                is ProjectActionResult.Failed -> {
                    notice = result.error
                    removed = null
                }
            }
            busy = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Shared with",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = SHARE_INTRO,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        val current = shares
        when {
            current == null && loadError != null -> Text(
                text = "Could not read who the project is shared with: $loadError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            current == null -> Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                current.owner?.let { owner ->
                    MemberRow(person = owner, me = me) {
                        Text("Owner", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                current.users.forEach { share ->
                    val name = Collaboration.displayName(share.user.toCollabUser())
                    MemberRow(person = share.user, me = me) {
                        PermissionMenu(
                            permission = share.permission,
                            enabled = !busy,
                            description = "Permission of $name",
                            onPick = { picked ->
                                if (picked != share.permission) run({ actions.updateShare(project, share.user, picked) })
                            },
                        )
                        IconButton(
                            onClick = {
                                run({ actions.unshare(project, share.user) }) { done ->
                                    notice = done.message
                                    removed = share.user to share.permission
                                }
                            },
                            enabled = !busy,
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove $name")
                        }
                    }
                }
                current.teams.forEach { team ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(team.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Team", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            permissionLabel(team.permission),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        notice?.let { text ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                removed?.let { (person, previous) ->
                    TextButton(
                        onClick = {
                            removed = null
                            run({ actions.share(project, person, previous) }) { notice = null }
                        },
                        enabled = !busy,
                    ) {
                        Text("Undo")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.weight(1f),
            )
            PermissionMenu(
                permission = permission,
                enabled = true,
                description = "Permission for the person you add",
                onPick = { permission = it },
            )
        }
        val status = when (val found = lookup) {
            Lookup.Idle, Lookup.Searching -> ""
            Lookup.NoMatch -> SHARE_NO_MATCH
            Lookup.AlreadyMember -> "Already has access."
            is Lookup.Match -> "${Collaboration.displayName(found.user.toCollabUser())} · @${found.user.username}"
            is Lookup.Failed -> found.message
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val match = (lookup as? Lookup.Match)?.user
            Button(
                onClick = {
                    val person = match ?: return@Button
                    run({ actions.share(project, person, permission) }) { done ->
                        username = ""
                        removed = null
                        notice = done.message
                    }
                },
                enabled = match != null && !busy,
            ) {
                Text("Share")
            }
        }
    }
}

/** A person with access: avatar, name (and "(you)"), @username, then [trailing]. */
@Composable
private fun MemberRow(person: User, me: Long?, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PersonAvatar(person, 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = Collaboration.displayName(person.toCollabUser()) + if (person.id == me) " (you)" else "",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "@${person.username}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/** Can view, Can edit or Admin, as a button that opens a menu. */
@Composable
private fun PermissionMenu(permission: Int, enabled: Boolean, description: String, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.semantics { this.contentDescription = "$description: ${permissionLabel(permission)}" },
        ) {
            Text(permissionLabel(permission))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(Permission.READ, Permission.WRITE, Permission.ADMIN).forEach { level ->
                DropdownMenuItem(
                    text = { Text(permissionLabel(level)) },
                    onClick = {
                        open = false
                        onPick(level)
                    },
                )
            }
        }
    }
}
