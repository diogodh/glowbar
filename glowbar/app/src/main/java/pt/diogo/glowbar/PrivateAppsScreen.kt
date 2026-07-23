package pt.diogo.glowbar

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppInfo(val pkg: String, val label: String)

private suspend fun loadApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(intent, 0)
        .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.pkg != context.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

@Composable
fun PrivateAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rules = remember { AppRulesRepository(context) }

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(rules.privatePackages()) }
    var colorDialogFor by remember { mutableStateOf<AppInfo?>(null) }
    var version by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { apps = loadApps(context) }

    val visible = apps.filter { it.label.contains(filter, ignoreCase = true) }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onBack) { Text("‹ Voltar") }

        Text("Aplicações privadas", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "As notificações destas apps são removidas do sistema assim que " +
                "chegam. Só recebes o sinal da Glowbar — sem ícone na barra de " +
                "estado e sem pré-visualização no ecrã de bloqueio.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        Card {
            Text(
                "Importante: o Android toca o som antes de a app receber o evento. " +
                    "Para o silêncio ser total, define as notificações destas apps " +
                    "como silenciosas nas definições do sistema.",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Procurar aplicação") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.pkg }) { app ->
                @Suppress("UNUSED_EXPRESSION") version
                val isOn = selected.contains(app.pkg)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                    }

                    if (isOn) {
                        ColorDot(rules.colorFor(app.pkg)) { colorDialogFor = app }
                        Spacer(Modifier.size(12.dp))
                    }

                    Switch(
                        checked = isOn,
                        onCheckedChange = {
                            rules.setPrivate(app.pkg, it)
                            selected = rules.privatePackages()
                        }
                    )
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                RecentEvents(rules, apps) { version++ }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    colorDialogFor?.let { app ->
        AlertDialog(
            onDismissRequest = { colorDialogFor = null },
            title = { Text("Cor do glow — " + app.label) },
            text = {
                Column {
                    AppRulesRepository.PALETTE.forEach { (value, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    rules.setColor(app.pkg, value)
                                    version++
                                    colorDialogFor = null
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            ColorDot(if (value == 0) null else value) { }
                            Spacer(Modifier.size(12.dp))
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { colorDialogFor = null }) { Text("Fechar") }
            }
        )
    }
}

@Composable
private fun ColorDot(color: Int?, onClick: () -> Unit) {
    val brush = if (color == null) {
        Brush.horizontalGradient(
            listOf(
                Color(0xFF4285F4), Color(0xFF34A853),
                Color(0xFFFBBC05), Color(0xFFEA4335)
            )
        )
    } else {
        Brush.horizontalGradient(listOf(Color(color), Color(color)))
    }
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(brush)
            .clickable { onClick() }
    )
}

@Composable
private fun RecentEvents(
    rules: AppRulesRepository,
    apps: List<AppInfo>,
    onChanged: () -> Unit
) {
    val events = rules.recentEvents()
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    Text("Avisos recebidos", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Apenas a aplicação e a hora. O conteúdo das notificações nunca é " +
            "lido nem guardado.",
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(8.dp))

    if (events.isEmpty()) {
        Text("Ainda sem registos.", style = MaterialTheme.typography.bodySmall)
        return
    }

    events.forEach { (pkg, ts) ->
        val label = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(fmt.format(Date(ts)), style = MaterialTheme.typography.bodySmall)
        }
    }

    TextButton(onClick = {
        rules.clearEvents()
        onChanged()
    }) { Text("Limpar registo") }
}
