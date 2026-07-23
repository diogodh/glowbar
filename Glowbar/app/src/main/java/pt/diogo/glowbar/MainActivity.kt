package pt.diogo.glowbar

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pt.diogo.glowbar.overlay.GlowOverlayService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(this)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) {
                    var showPrivate by remember { mutableStateOf(false) }
                    if (showPrivate) {
                        PrivateAppsScreen(onBack = { showPrivate = false })
                    } else {
                        SettingsScreen(repo, onOpenPrivateApps = { showPrivate = true })
                    }
                }
            }
        }
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = ops.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(repo: SettingsRepository, onOpenPrivateApps: () -> Unit) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(repo.load()) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun apply(new: GlowSettings) {
        s = new
        repo.save(new)
        val i = Intent(context, GlowOverlayService::class.java)
        if (new.enabled) {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.packageName)
                    )
                )
                return
            }
            i.action = GlowOverlayService.ACTION_START
            ContextCompat.startForegroundService(context, i)
        } else {
            i.action = GlowOverlayService.ACTION_TURN_OFF
            context.startService(i)
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Glowbar", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Mostrar Glowbar",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            Switch(checked = s.enabled, onCheckedChange = { apply(s.copy(enabled = it)) })
        }

        Section("Forma")
        ChipRow(
            listOf(Shape.BAR to "Barra", Shape.CUTOUT to "Cutout"),
            s.shape
        ) { apply(s.copy(shape = it)) }

        if (s.shape == Shape.BAR) {
            Section("Posição")
            ChipRow(
                listOf(
                    Position.TOP to "Topo",
                    Position.BOTTOM to "Fundo",
                    Position.LEFT to "Esquerda",
                    Position.RIGHT to "Direita"
                ),
                s.position
            ) { apply(s.copy(position = it)) }

            Section("Comprimento: " + s.widthDp + "dp")
            Slider(
                value = s.widthDp.toFloat(),
                onValueChange = { s = s.copy(widthDp = it.toInt()) },
                onValueChangeFinished = { apply(s) },
                valueRange = 40f..400f
            )

            Section("Espessura: " + s.thicknessDp + "dp")
            Slider(
                value = s.thicknessDp.toFloat(),
                onValueChange = { s = s.copy(thicknessDp = it.toInt()) },
                onValueChangeFinished = { apply(s) },
                valueRange = 2f..24f
            )

            Stepper("Deslocamento ao longo da margem", s.offsetAlongDp) {
                apply(s.copy(offsetAlongDp = it))
            }
            Stepper("Distância à margem", s.offsetFromEdgeDp) {
                apply(s.copy(offsetFromEdgeDp = it.coerceAtLeast(0)))
            }
        } else {
            Section("Stroke: " + s.strokeDp + "dp")
            Slider(
                value = s.strokeDp.toFloat(),
                onValueChange = { s = s.copy(strokeDp = it.toInt()) },
                onValueChangeFinished = { apply(s) },
                valueRange = 2f..12f
            )

            Section("Escala horizontal: " + String.format("%.2f", s.hScale) + "×")
            Slider(
                value = s.hScale,
                onValueChange = { s = s.copy(hScale = it) },
                onValueChangeFinished = { apply(s) },
                valueRange = 0.5f..2.0f
            )

            Section("Escala vertical: " + String.format("%.2f", s.vScale) + "×")
            Slider(
                value = s.vScale,
                onValueChange = { s = s.copy(vScale = it) },
                onValueChangeFinished = { apply(s) },
                valueRange = 0.5f..2.0f
            )

            Stepper("Deslocar esquerda/direita", s.cutoutOffsetXDp) {
                apply(s.copy(cutoutOffsetXDp = it))
            }
            Stepper("Deslocar cima/baixo", s.cutoutOffsetYDp) {
                apply(s.copy(cutoutOffsetYDp = it))
            }

            TextButton(onClick = {
                apply(
                    s.copy(
                        strokeDp = 4, hScale = 1f, vScale = 1f,
                        cutoutOffsetXDp = 0, cutoutOffsetYDp = 0
                    )
                )
            }) { Text("Repor tamanho e posição") }
        }

        Section("Modo de animação")
        ChipRow(
            listOf(
                AnimMode.NONE to "Nenhuma",
                AnimMode.FLOW to "Flow",
                AnimMode.PULSE to "Pulse",
                AnimMode.BREATHE to "Breathe"
            ),
            s.animMode
        ) { apply(s.copy(animMode = it)) }

        Section("Velocidade")
        ChipRow(
            listOf(
                AnimSpeed.SLOW to "Lenta",
                AnimSpeed.MEDIUM to "Média",
                AnimSpeed.FAST to "Rápida"
            ),
            s.animSpeed
        ) { apply(s.copy(animSpeed = it)) }

        Section("Funcionalidades")
        ToggleRow(
            "Indicador de bateria",
            "O vermelho avança à medida que a bateria desce; verde a carregar",
            s.batteryIndicator
        ) { apply(s.copy(batteryIndicator = it)) }

        ToggleRow(
            "Notification glow",
            "Flash breve quando chega uma notificação",
            s.notificationGlow
        ) { apply(s.copy(notificationGlow = it)) }

        ToggleRow(
            "Notification progress",
            "A barra torna-se indicador de progresso em downloads/uploads",
            s.notificationProgress
        ) { apply(s.copy(notificationProgress = it)) }

        ToggleRow(
            "Arrancar após reiniciar",
            "Repõe a Glowbar depois de reiniciares o telefone, se estava ligada",
            s.autoStart
        ) { apply(s.copy(autoStart = it)) }

        ToggleRow(
            "Esconder em fullscreen",
            "Oculta o glow durante vídeos e jogos em ecrã inteiro",
            s.autoHideFullscreen
        ) { apply(s.copy(autoHideFullscreen = it)) }

        Section("Avisos das apps privadas")
        ToggleRow(
            "Manter aviso até abrir a app",
            "A barra respira nas cores das apps com avisos por resolver, em vez " +
                "de um flash que passa despercebido",
            s.pendingUntilOpened
        ) { apply(s.copy(pendingUntilOpened = it)) }

        ToggleRow(
            "Tocar na barra abre a app",
            "Com avisos pendentes, um toque abre a app e limpa o sinal",
            s.tapToOpen
        ) { apply(s.copy(tapToOpen = it)) }

        Stepper(
            if (s.pendingTimeoutMin == 0) "Expirar sozinho: nunca"
            else "Expirar sozinho: " + s.pendingTimeoutMin + " min",
            s.pendingTimeoutMin
        ) { apply(s.copy(pendingTimeoutMin = it.coerceIn(0, 120))) }

        if (s.pendingUntilOpened && !hasUsageAccess(context)) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Permitir acesso ao histórico de utilização") }
            Text(
                "Necessário para a Glowbar perceber que abriste a app e apagar o aviso",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = onOpenPrivateApps,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Aplicações privadas…") }
        Text(
            "Notificações destas apps aparecem só na Glowbar, nunca no sistema",
            style = MaterialTheme.typography.bodySmall
        )

        if ((s.notificationGlow || s.notificationProgress) && !hasNotificationAccess(context)) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Conceder acesso às notificações") }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Spacer(Modifier.height(12.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onChange(value - 2) }) { Text("−") }
        Text(
            value.toString() + "dp",
            Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(onClick = { onChange(value + 2) }) { Text("+") }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
