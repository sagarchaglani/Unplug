@file:OptIn(ExperimentalMaterial3Api::class)
package com.unplug.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { UnplugApp() } }
    }
}

@Composable
fun UnplugApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {  // seed sensible default keywords once
        if (!SessionPrefs.seeded(ctx)) {
            val dao = UnplugDb.get(ctx).dao()
            listOf("urgent", "emergency", "asap", "hospital", "accident").forEach { dao.insertRule(Rule(type = "KEYWORD", value = it)) }
            SessionPrefs.markSeeded(ctx)
        }
    }
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Home", "Rules", "Digest").forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) { 0 -> HomeScreen(); 1 -> RulesScreen(); else -> DigestScreen() }
        }
    }
}

@Composable
fun HomeScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var listenerOn by remember { mutableStateOf(false) }
    var minutes by remember { mutableIntStateOf(60) }
    var commit by remember { mutableStateOf(false) }
    var askExit by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        while (true) {
            now = System.currentTimeMillis()
            listenerOn = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            delay(1000)
        }
    }
    val end = SessionPrefs.end(ctx)
    val active = end > now

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Unplug", fontSize = 32.sp)
        Text("Leave your phone alone. Only real emergencies ring through.")

        if (!listenerOn) {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Unplug needs Notification Access to read notifications. Everything stays on your phone; nothing is uploaded.")
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Grant access") }
            } }
        }

        if (active) {
            val left = (end - now) / 1000
            Text("Session active", fontSize = 20.sp)
            Text("%d:%02d:%02d".format(left / 3600, left % 3600 / 60, left % 60), fontSize = 48.sp)
            OutlinedButton(onClick = {
                if (SessionPrefs.commitment(ctx)) askExit = true else SessionService.stop(ctx)
            }) { Text("End session") }
        } else {
            Text("Session length")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30m", 60 to "1h", 120 to "2h", 240 to "4h", 480 to "8h").forEach { (m, label) ->
                    FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = commit, onCheckedChange = { commit = it })
                Spacer(Modifier.width(12.dp))
                Text("Commitment mode (typed phrase to quit early)")
            }
            Button(enabled = listenerOn, modifier = Modifier.fillMaxWidth(),
                onClick = { SessionService.start(ctx, minutes, commit) }) { Text("Start session") }
        }
    }

    if (askExit) AlertDialog(
        onDismissRequest = { askExit = false },
        title = { Text("Ending early?") },
        text = { Column {
            Text("Type \"I choose to end\" to confirm.")
            OutlinedTextField(value = phrase, onValueChange = { phrase = it }, singleLine = true)
        } },
        confirmButton = {
            TextButton(enabled = phrase.trim().equals("I choose to end", true), onClick = {
                askExit = false; phrase = ""; SessionService.stop(ctx)
            }) { Text("End session") }
        },
        dismissButton = { TextButton(onClick = { askExit = false }) { Text("Stay focused") } }
    )
}

@Composable
fun RulesScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dao = remember { UnplugDb.get(ctx).dao() }
    val rules by dao.rules().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("VIP") }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Emergency rules", fontSize = 24.sp)
        Text("VIPs always ring (matched against the sender name). Keywords ring when found in a message. The same sender twice within 5 minutes, calls and alarms also always ring.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type == "VIP", onClick = { type = "VIP" }, label = { Text("VIP") })
            FilterChip(selected = type == "KEYWORD", onClick = { type = "KEYWORD" }, label = { Text("Keyword") })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                modifier = Modifier.weight(1f), label = { Text(if (type == "VIP") "Name, e.g. Mom" else "Word, e.g. urgent") })
            Button(enabled = input.isNotBlank(), onClick = {
                scope.launch { dao.insertRule(Rule(type = type, value = input.trim())) }; input = ""
            }) { Text("Add") }
        }
        LazyColumn {
            items(rules) { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.type}: ${r.value}", Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { dao.deleteRule(r) } }) { Text("Remove") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun DigestScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dao = remember { UnplugDb.get(ctx).dao() }
    val held by dao.held().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Digest (${held.size})", fontSize = 24.sp, modifier = Modifier.weight(1f))
            TextButton(enabled = held.isNotEmpty(), onClick = { scope.launch { dao.clearHeld() } }) { Text("Clear all") }
        }
        if (held.isEmpty()) Text("Nothing held back.")
        LazyColumn {
            held.groupBy { it.app }.forEach { (app, list) ->
                item { Text("$app (${list.size})", fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp)) }
                items(list) { h ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("${fmt.format(h.time)} · ${h.sender}")
                        Text(h.text, fontSize = 14.sp)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
