package com.example.iptvplayer

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.net.URL

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String
)

val groupRegex = Regex("group-title=\"([^\"]*)\"")
val logoRegex = Regex("tvg-logo=\"([^\"]*)\"")

fun parseM3u(text: String): List<Channel> {
    val list = mutableListOf<Channel>()
    var name: String? = null
    var group = ""
    var logo = ""
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF")) {
            name = line.substringAfterLast(",").trim()
            group = groupRegex.find(line)?.groupValues?.get(1)?.trim() ?: ""
            logo = logoRegex.find(line)?.groupValues?.get(1)?.trim() ?: ""
        } else if (line.isNotEmpty() && !line.startsWith("#")) {
            list.add(Channel(if (name.isNullOrBlank()) line else name, line, group, logo))
            name = null
            group = ""
            logo = ""
        }
    }
    return list
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { IptvApp() }
        }
    }
}

@Composable
fun CompactInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = MaterialTheme.colorScheme.outline

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = textColor, fontSize = 14.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(36.dp)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp)),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = hintColor,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                innerTextField()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IptvApp() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val prefs = remember { context.getSharedPreferences("iptv", Context.MODE_PRIVATE) }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var link by remember { mutableStateOf(prefs.getString("link", "") ?: "") }
    var channels by remember { mutableStateOf(listOf<Channel>()) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var status by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var fullscreen by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf("All") }
    var favs by remember {
        mutableStateOf((prefs.getStringSet("favs", emptySet()) ?: emptySet()).toSet())
    }

    fun toggleFav(url: String) {
        val updated = if (url in favs) favs - url else favs + url
        favs = updated
        prefs.edit().putStringSet("favs", updated.toSet()).apply()
    }

    fun loadLink() {
        val url = link.trim()
        if (url.isEmpty()) return
        prefs.edit().putString("link", url).apply()
        status = "Load ho raha hai..."
        Thread {
            try {
                val result = parseM3u(URL(url).readText())
                channels = result
                status = "${result.size} channels mile"
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            }
        }.start()
    }

    LaunchedEffect(Unit) { loadLink() }

    LaunchedEffect(selected) {
        selected?.let {
            player.setMediaItem(MediaItem.fromUri(it.url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    LaunchedEffect(fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = fullscreen) { fullscreen = false }

    if (fullscreen && selected != null) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxSize()
            )
            Button(
                onClick = { fullscreen = false },
                modifier = Modifier.padding(16.dp)
            ) { Text("Exit fullscreen") }
        }
        return
    }

    val groups = listOf("All", "Favorites") +
        channels.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()

    val filtered = channels.filter { ch ->
        val inGroup = when (group) {
            "All" -> true
            "Favorites" -> ch.url in favs
            else -> ch.group == group
        }
        inGroup && ch.name.contains(query, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Edge-to-edge compact video player
        if (selected != null) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
            )
        }

        // Baaki UI ke liye compact outer padding
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            if (selected != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = selected!!.name,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp)
                    )
                    Button(
                        onClick = { fullscreen = true },
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Text("Fullscreen", fontSize = 12.sp)
                    }
                }
            }

            // M3U link aur Load button ek hi slim row me
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactInput(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = "M3U link yahan daalo",
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp)
                )
                Button(
                    onClick = { loadLink() },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text("Load", fontSize = 12.sp)
                }
            }

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }

            // Compact channel search box
            CompactInput(
                value = query,
                onValueChange = { query = it },
                placeholder = "Channel search",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )

            // Slim category chips
            LazyRow(Modifier.padding(vertical = 2.dp)) {
                items(groups) { g ->
                    FilterChip(
                        selected = group == g,
                        onClick = { group = g },
                        label = {
                            Text(
                                text = if (g == "Favorites") "★ Favorites" else g,
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier
                            .height(30.dp)
                            .padding(end = 4.dp)
                    )
                }
            }

            // Compact channel list
            LazyColumn(Modifier.weight(1f)) {
                items(filtered) { ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = ch }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (ch.logo.isNotBlank()) {
                            AsyncImage(
                                model = ch.logo,
                                contentDescription = ch.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(36.dp)
                            )
                        } else {
                            Spacer(Modifier.size(36.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = ch.name,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        )
                        Text(
                            text = if (ch.url in favs) "★" else "☆",
                            fontSize = 18.sp,
                            modifier = Modifier
                                .clickable { toggleFav(ch.url) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
