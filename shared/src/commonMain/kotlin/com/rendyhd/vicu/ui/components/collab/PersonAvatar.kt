package com.rendyhd.vicu.ui.components.collab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.model.toCollabUser
import com.rendyhd.vicu.util.Collaboration
import io.ktor.http.encodeURLPathPart

/** Every avatar is asked for at this size, so one cached image serves every place it is drawn. */
private const val AVATAR_FETCH_PX = 128

/**
 * The server's avatar of a user (`GET avatar/{username}?size=N`). The placeholder host is rewritten
 * to the configured server and authenticated by the app's image interceptor; the image loader caches
 * it on disk.
 */
internal fun avatarModel(username: String): String =
    "http://localhost/avatar/${username.encodeURLPathPart()}?size=$AVATAR_FETCH_PX"

/**
 * A person's avatar (docs/design-system-v1.md section 4): the server's image over the fallback, the
 * first letter of the display name on `secondaryContainer`. The fallback shows while the image loads
 * and stays when it fails. With [ringColor] a ring of 1.5 dp in that colour separates it from the
 * avatar it overlaps. Decoration: it has no accessible name of its own.
 */
@Composable
fun PersonAvatar(
    user: User,
    size: Dp,
    modifier: Modifier = Modifier,
    ringColor: Color? = null,
) {
    val initial = remember(user.id, user.name, user.username) { Collaboration.avatarInitial(user.toCollabUser()) }
    // The letter is sized with the circle, not with the font scale: it is a picture of the person.
    val letterSize = with(LocalDensity.current) { (size * 0.45f).coerceAtLeast(9.dp).toSp() }
    Box(
        modifier = modifier
            .size(size)
            .then(if (ringColor != null) Modifier.background(ringColor, CircleShape).padding(1.5.dp) else Modifier)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = letterSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (user.username.isNotEmpty()) {
            AsyncImage(
                model = avatarModel(user.username),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Overlapping avatars of [people] (already in contract order): at most [max], each after the first
 * overlapping the one before by 5 dp with a ring in [ringColor], then "+N" for the rest.
 */
@Composable
fun AvatarStack(
    people: List<User>,
    size: Dp,
    ringColor: Color,
    modifier: Modifier = Modifier,
    max: Int = 2,
) {
    if (people.isEmpty()) return
    val shown = people.take(max)
    val rest = people.size - shown.size
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
            shown.forEachIndexed { index, person ->
                // Ringed avatars are drawn 1.5 dp larger on each side, so the circles line up.
                if (index == 0) {
                    PersonAvatar(person, size, modifier = Modifier.padding(1.5.dp))
                } else {
                    PersonAvatar(person, size + 3.dp, ringColor = ringColor)
                }
            }
        }
        if (rest > 0) {
            Text(
                text = "+$rest",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
