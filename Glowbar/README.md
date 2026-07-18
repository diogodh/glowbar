# Glowbar (clone pessoal)

Overlay luminoso persistente para Android — barra numa margem do ecrã ou anel
em redor da câmara frontal — com animações Flow/Pulse/Breathe, indicador de
bateria, notification glow, notification progress, autostart após reboot,
autohide em fullscreen e definições separadas por ecrã (foldables).
Tudo desbloqueado, sem versão Premium.

## Compilar no Windows

1. Instala o **Android Studio** (https://developer.android.com/studio) — inclui o JDK e o SDK.
2. **File → Open** e escolhe esta pasta `Glowbar`. Aceita a sincronização do Gradle
   (o Android Studio descarrega o Gradle 8.7 e as dependências automaticamente).
3. Liga o telemóvel por USB com a **depuração USB** ativa
   (Definições → Opções de programador) e carrega em **Run ▶**.
4. Alternativa sem cabo: **Build → Build APK(s)** e instala o APK gerado em
   `app/build/outputs/apk/debug/`.

## Permissões a conceder no telefone

- **Sobrepor a outras aplicações** — pedida automaticamente ao ligares o switch.
- **Notificações** (Android 13+) — para a notificação persistente com Esconder/Desligar.
- **Acesso às notificações** — só é pedido se ativares Notification glow/progress;
  o conteúdo das notificações não é lido nem guardado (apenas o campo de progresso
  e o evento de chegada).

## Arquitetura (resumo)

- `MainActivity` — UI de configuração em Jetpack Compose; grava em SharedPreferences
  e envia REFRESH ao serviço.
- `GlowOverlayService` — foreground service; cria a janela `TYPE_APPLICATION_OVERLAY`
  (NOT_FOCUSABLE + NOT_TOUCHABLE), gere a máquina de estados de visibilidade
  (normal / hidden temp / hidden fullscreen), a bateria e a notificação de controlo.
- `GlowView` — motor gráfico em Canvas: LinearGradient com matriz animada (Flow),
  alpha (Pulse), espessura (Breathe), BlurMaskFilter para o halo, SweepGradient no anel.
- `GlowNotificationListener` — NotificationListenerService que alimenta o flash
  e o modo progresso (EXTRA_PROGRESS / EXTRA_PROGRESS_MAX).
- `BootReceiver` — autostart após BOOT_COMPLETED.
- `SettingsRepository` — chaves geométricas com sufixo `smallestScreenWidthDp`,
  por isso um foldable mantém configurações distintas aberto/fechado.

## Limitações conhecidas (v1)

- **Autohide em fullscreen** usa a visibilidade da status bar via WindowInsets
  (API 30+); nalguns launchers/jogos o comportamento pode variar.
- **Deteção do cutout** usa o primeiro `boundingRect` do DisplayCutout; ajusta com
  as escalas/offsets se o anel não ficar perfeito no teu modelo.
- O animador corre continuamente enquanto a barra está visível; para poupar bateria
  com o modo "Nenhuma", uma otimização futura seria pausar o ValueAnimator.
- Em alguns OEM (Xiaomi/Huawei), o autostart após reboot pode exigir permitir
  "arranque automático" nas definições do sistema.

## Ideias de expansão (já previstas na especificação)

Cores personalizadas, regras por aplicação, cor por app que notifica,
visualizador de áudio, perfis, agendamento horário e proteção anti burn-in
(micro-deslocamentos periódicos em OLED).
