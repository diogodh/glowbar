package pt.diogo.glowbar

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import pt.diogo.glowbar.overlay.GlowAccessibilityService
import pt.diogo.glowbar.overlay.GlowOverlayService
import pt.diogo.glowbar.overlay.GlowController

class MainActivity : ComponentActivity() {
    private val resumeVersion = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = SettingsRepository(this)

        // A interface da Glowbar usa edge-to-edge, mas o conteúdo
        // é protegido das system bars através de systemBarsPadding().
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Ícones brancos na status bar e na navigation bar.
        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            val refreshKey = resumeVersion.intValue

            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) {
                    darkColorScheme()
                } else {
                    lightColorScheme()
                }
            ) {
                Surface(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                ) {
                    var showPrivate by remember {
                        mutableStateOf(false)
                    }

                    if (showPrivate) {
                        PrivateAppsScreen(
                            onBack = {
                                showPrivate = false
                            }
                        )
                    } else {
                        SettingsScreen(
                            repo = repo,
                            refreshKey = refreshKey,
                            onOpenPrivateApps = {
                                showPrivate = true
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        reconcileRunningMode()

        // A Glowbar não deve aparecer por cima da própria interface
        // de definições da Glowbar.
        GlowController.active?.hideTemporarily(true)

        resumeVersion.intValue++
    }

    override fun onPause() {
        super.onPause()

        // Quando saímos das definições, volta a mostrar a Glowbar.
        GlowController.active?.hideTemporarily(false)
    }

    private fun reconcileRunningMode() {
        val settings = SettingsRepository(this).load()
        if (!settings.enabled) return

        if (settings.accessibilityMode) {
            stopService(Intent(this, GlowOverlayService::class.java))
            GlowAccessibilityService.instance?.refresh()
            return
        }

        GlowAccessibilityService.instance?.refresh()

        if (!Settings.canDrawOverlays(this)) return

        ContextCompat.startForegroundService(
            this,
            Intent(this, GlowOverlayService::class.java)
                .setAction(GlowOverlayService.ACTION_START)
        )
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java) ?: return false

    val mode = ops.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )

    return mode == AppOpsManager.MODE_ALLOWED
}

private fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    refreshKey: Int,
    onOpenPrivateApps: () -> Unit
) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(repo.load()) }

    LaunchedEffect(refreshKey) {
        s = repo.load()
    }

    val startClassicOrRequestOverlay: () -> Unit = {
        val current = repo.load()

        if (current.enabled && !current.accessibilityMode) {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.packageName)
                    )
                )
            } else {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GlowOverlayService::class.java)
                        .setAction(GlowOverlayService.ACTION_START)
                )
            }
        }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startClassicOrRequestOverlay()
    }

    fun apply(new: GlowSettings) {
        val wasEnabled = s.enabled
        val wasAccessibilityMode = s.accessibilityMode

        s = new
        repo.save(new)

        if (!new.enabled) {
            GlowAccessibilityService.instance?.refresh()
            context.stopService(Intent(context, GlowOverlayService::class.java))
            return
        }

        if (new.accessibilityMode) {
            context.stopService(Intent(context, GlowOverlayService::class.java))

            val svc = GlowAccessibilityService.instance

            if (svc == null) {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            } else {
                svc.refresh()
            }

            return
        }

        GlowAccessibilityService.instance?.refresh()

        val enteringClassicMode = !wasEnabled || wasAccessibilityMode

        if (
            enteringClassicMode &&
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        startClassicOrRequestOverlay()
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            "Glowbar",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(Modifier.height(20.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Mostrar Glowbar",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )

            Switch(
                checked = s.enabled,
                onCheckedChange = {
                    apply(s.copy(enabled = it))
                }
            )
        }

        StatusPanel(s, context)

        Section("Forma")

        ChipRow(
            listOf(
                Shape.BAR to "Barra",
                Shape.CUTOUT to "Cutout"
            ),
            s.shape
        ) {
            apply(s.copy(shape = it))
        }

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
            ) {
                apply(s.copy(position = it))
            }

            Section("Comprimento: " + s.widthDp + "dp")

            Slider(
                value = s.widthDp.toFloat(),
                onValueChange = {
                    s = s.copy(widthDp = it.toInt())
                },
                onValueChangeFinished = {
                    apply(s)
                },
                valueRange = 40f..400f
            )

            Section("Espessura: " + s.thicknessDp + "dp")

            Slider(
                value = s.thicknessDp.toFloat(),
                onValueChange = {
                    s = s.copy(thicknessDp = it.toInt())
                },
                onValueChangeFinished = {
                    apply(s)
                },
                valueRange = 2f..24f
            )

            Stepper(
                "Deslocamento ao longo da margem",
                s.offsetAlongDp
            ) {
                apply(s.copy(offsetAlongDp = it))
            }

            Stepper(
                "Distância à margem",
                s.offsetFromEdgeDp
            ) {
                apply(
                    s.copy(
                        offsetFromEdgeDp = it.coerceAtLeast(0)
                    )
                )
            }
        } else {
            Section("Stroke: " + s.strokeDp + "dp")

            Slider(
                value = s.strokeDp.toFloat(),
                onValueChange = {
                    s = s.copy(strokeDp = it.toInt())
                },
                onValueChangeFinished = {
                    apply(s)
                },
                valueRange = 2f..12f
            )

            Section(
                "Escala horizontal: " +
                    String.format("%.2f", s.hScale) +
                    "×"
            )

            Slider(
                value = s.hScale,
                onValueChange = {
                    s = s.copy(hScale = it)
                },
                onValueChangeFinished = {
                    apply(s)
                },
                valueRange = 0.5f..2.0f
            )

            Section(
                "Escala vertical: " +
                    String.format("%.2f", s.vScale) +
                    "×"
            )

            Slider(
                value = s.vScale,
                onValueChange = {
                    s = s.copy(vScale = it)
                },
                onValueChangeFinished = {
                    apply(s)
                },
                valueRange = 0.5f..2.0f
            )

            Stepper(
                "Deslocar esquerda/direita",
                s.cutoutOffsetXDp
            ) {
                apply(s.copy(cutoutOffsetXDp = it))
            }

            Stepper(
                "Deslocar cima/baixo",
                s.cutoutOffsetYDp
            ) {
                apply(s.copy(cutoutOffsetYDp = it))
            }

            TextButton(
                onClick = {
                    apply(
                        s.copy(
                            strokeDp = 4,
                            hScale = 1f,
                            vScale = 1f,
                            cutoutOffsetXDp = 0,
                            cutoutOffsetYDp = 0
                        )
                    )
                }
            ) {
                Text("Repor tamanho e posição")
            }
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
        ) {
            apply(s.copy(animMode = it))
        }

        Section("Velocidade")

        ChipRow(
            listOf(
                AnimSpeed.SLOW to "Lenta",
                AnimSpeed.MEDIUM to "Média",
                AnimSpeed.FAST to "Rápida"
            ),
            s.animSpeed
        ) {
            apply(s.copy(animSpeed = it))
        }

        Section("Funcionalidades")

        ToggleRow(
            "Indicador de bateria",
            "O vermelho avança à medida que a bateria desce; verde a carregar",
            s.batteryIndicator
        ) {
            apply(s.copy(batteryIndicator = it))
        }

        ToggleRow(
            "Notification glow",
            "Flash breve quando chega uma notificação",
            s.notificationGlow
        ) {
            apply(s.copy(notificationGlow = it))
        }

        ToggleRow(
            "Notification progress",
            "A barra torna-se indicador de progresso em downloads/uploads",
            s.notificationProgress
        ) {
            apply(s.copy(notificationProgress = it))
        }

        ToggleRow(
            "Arrancar após reiniciar",
            "Repõe a Glowbar depois de reiniciares o telefone, se estava ligada",
            s.autoStart
        ) {
            apply(s.copy(autoStart = it))
        }

        ToggleRow(
            "Esconder em fullscreen",
            "Oculta o glow durante vídeos e jogos em ecrã inteiro",
            s.autoHideFullscreen
        ) {
            apply(s.copy(autoHideFullscreen = it))
        }

        Section("Aparência em repouso")

        ToggleRow(
            "Apagar quando não há avisos",
            "A barra fica cinzenta e quase transparente; só ganha cor e brilho " +
                "quando chega notificação de uma app escolhida",
            s.idleDim
        ) {
            apply(s.copy(idleDim = it))
        }

        if (s.idleDim) {
            Section(
                "Intensidade em repouso: " +
                    s.idleAlphaPct +
                    "%"
            )

            Slider(
                value = s.idleAlphaPct.toFloat(),
                onValueChange = {
                    s = s.copy(idleAlphaPct = it.toInt())
                },
                onValueChangeFinished = {
                    apply(s)
                },
                valueRange = 0f..60f
            )
        }

        Section("Notificações da própria Glowbar")

        ToggleRow(
            "Modo sem notificações",
            "Usa um serviço de acessibilidade em vez da permissão de " +
                "sobreposição: desaparecem o aviso do sistema e a notificação " +
                "permanente. Também dispensa o acesso ao histórico de utilização",
            s.accessibilityMode
        ) {
            apply(s.copy(accessibilityMode = it))
        }

        if (s.accessibilityMode) {
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Abrir definições de acessibilidade")
            }

            Text(
                "Ativa lá a Glowbar. Sem isso, a barra não aparece neste modo.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Section("Avisos das apps privadas")

        ToggleRow(
            "Manter aviso até abrir a app",
            "A barra respira nas cores das apps com avisos por resolver, em vez " +
                "de um flash que passa despercebido",
            s.pendingUntilOpened
        ) {
            apply(s.copy(pendingUntilOpened = it))
        }

        ToggleRow(
            "Tocar na barra abre a app",
            "Com avisos pendentes, um toque abre a app e limpa o sinal",
            s.tapToOpen
        ) {
            apply(s.copy(tapToOpen = it))
        }

        Section(
            if (s.pendingTimeoutSec == 0)
                "Duração do aviso: até abrir a app"
            else
                "Duração do aviso: " +
                    formatTimeout(s.pendingTimeoutSec)
        )

        ChipRow(
            listOf(
                0 to "Até abrir",
                5 to "5s",
                10 to "10s",
                30 to "30s",
                60 to "1min",
                300 to "5min",
                1800 to "30min"
            ),
            s.pendingTimeoutSec
        ) {
            apply(s.copy(pendingTimeoutSec = it))
        }

        ToggleRow(
            "Acordar o ecrã",
            "Liga o ecrã por alguns segundos quando chega aviso de uma app " +
                "privada, para veres o glow com o telefone pousado",
            s.wakeScreen
        ) {
            apply(s.copy(wakeScreen = it))
        }

        if (s.wakeScreen) {
            Section(
                "Ecrã ligado durante: " +
                    s.wakeScreenSec +
                    "s"
            )

            Slider(
                value = s.wakeScreenSec.toFloat(),
                onValueChange = {
                    s = s.copy(wakeScreenSec = it.toInt())
                },
                onValueChangeFinished = {
                    apply(s)
                },
                valueRange = 2f..30f
            )

            Text(
                "Se o glow aparece por cima do ecrã de bloqueio depende do " +
                    "fabricante do telefone. Usar isto com frequência gasta bateria.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (
            s.pendingUntilOpened &&
            !s.accessibilityMode &&
            !hasUsageAccess(context)
        ) {
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Permitir acesso ao histórico de utilização")
            }

            Text(
                "Necessário para a Glowbar perceber que abriste a app e apagar o aviso",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(20.dp))

        OutlinedButton(
            onClick = onOpenPrivateApps,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Aplicações privadas…")
        }

        Text(
            "Notificações destas apps aparecem só na Glowbar, nunca no sistema",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(40.dp))
    }
}

/**
 * Mostra em texto claro o que falta para a app funcionar.
 */
@Composable
private fun StatusPanel(
    s: GlowSettings,
    context: Context
) {
    val rules = remember {
        AppRulesRepository(context)
    }

    val privateCount = rules.privatePackages().size

    val needsNotifAccess =
        s.notificationGlow ||
            s.notificationProgress ||
            privateCount > 0

    val hasNotif =
        hasNotificationAccess(context)

    val a11yOn =
        GlowAccessibilityService.instance != null

    val missingOverlayPermission =
        s.enabled &&
            !s.accessibilityMode &&
            !Settings.canDrawOverlays(context)

    val problems =
        mutableListOf<String>()

    if (needsNotifAccess && !hasNotif) {
        problems.add(
            "Falta o acesso às notificações — sem ele as apps privadas e o glow de notificações não funcionam."
        )
    }

    if (s.accessibilityMode && !a11yOn) {
        problems.add(
            "O modo sem notificações está ligado, mas a Glowbar ainda não foi ativada nas definições de acessibilidade."
        )
    }

    if (missingOverlayPermission) {
        problems.add(
            "Falta a permissão para mostrar a Glowbar por cima das outras aplicações."
        )
    }

    if (!s.enabled) {
        problems.add(
            "A Glowbar está desligada no interruptor acima."
        )
    }

    if (problems.isEmpty()) return

    Spacer(Modifier.height(16.dp))

    Card {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Estado",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(Modifier.height(6.dp))

            problems.forEach {
                Text(
                    "• " + it,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(4.dp))
            }

            if (needsNotifAccess && !hasNotif) {
                Spacer(Modifier.height(6.dp))

                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Conceder acesso às notificações")
                }
            }

            if (s.accessibilityMode && !a11yOn) {
                Spacer(Modifier.height(6.dp))

                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Abrir definições de acessibilidade")
                }
            }

            if (missingOverlayPermission) {
                Spacer(Modifier.height(6.dp))

                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse(
                                    "package:" + context.packageName
                                )
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Conceder permissão de sobreposição")
                }
            }
        }
    }
}

private fun formatTimeout(sec: Int): String =
    when {
        sec < 60 ->
            sec.toString() + "s"

        sec % 60 == 0 && sec < 3600 ->
            (sec / 60).toString() + "min"

        else ->
            (sec / 60).toString() + "min"
    }

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))

    Text(
        title,
        style = MaterialTheme.typography.titleSmall
    )

    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = {
                    onSelect(value)
                },
                label = {
                    Text(label)
                }
            )
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    unit: String = "dp",
    onChange: (Int) -> Unit
) {
    Spacer(Modifier.height(12.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedButton(
            onClick = {
                onChange(value - 2)
            }
        ) {
            Text("−")
        }

        Text(
            value.toString() + unit,
            Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedButton(
            onClick = {
                onChange(value + 2)
            }
        ) {
            Text("+")
        }
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

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.weight(1f)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall
            )

            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onChange
        )
    }
}