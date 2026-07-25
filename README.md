# Glowbar 1.2.0 (clone pessoal)

Overlay luminoso persistente para Android — barra numa margem do ecrã ou anel
em redor da câmara frontal — com animações Flow/Pulse/Breathe, indicador de
bateria, notification glow, notification progress, autostart após reboot,
autohide em fullscreen e definições separadas por ecrã (foldables).
Tudo desbloqueado, sem versão Premium.

## Compilar com GitHub Actions

1. Cria um repositório no GitHub e envia o conteúdo desta pasta `Glowbar`.
2. Abre **Actions → Build Glowbar APK → Run workflow**.
3. Quando terminar, abre a execução e descarrega o artefacto
   **Glowbar-1.2.0-debug**.
4. Dentro do artefacto está `app-debug.apk`, pronto para instalar.

O workflow instala JDK 17, Android SDK 35 e Gradle 8.7, executa `lintDebug`,
`testDebugUnitTest` e `assembleDebug`, e só publica o APK se todas as etapas
terminarem sem erro.

## Compilar localmente

O projeto usa Gradle 8.7 e JDK 17. Como o ZIP não inclui o binário do Gradle,
instala Gradle 8.7 localmente ou abre o projeto no Android Studio e seleciona
essa versão em **Gradle settings**. Depois executa:

```bash
gradle clean lintDebug testDebugUnitTest assembleDebug
```

O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

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

## Aplicações privadas (só glow, sem sistema)

Em **Aplicações privadas…** escolhes as apps cujas notificações devem ser
removidas do sistema mal chegam: sem ícone na barra de estado, sem
pré-visualização no ecrã de bloqueio, sem entrada no painel. Fica apenas o
flash da Glowbar, com cor à tua escolha por aplicação.

No diálogo de cor, o botão **Testar** permite confirmar imediatamente o flash
sem criar nem cancelar uma notificação real.

### Aviso persistente

Em vez de um flash que passa despercebido, o aviso **fica**: a barra divide-se
em segmentos — um por aplicação com aviso por resolver — cada um na cor dessa
app, com respiração lenta e desfasada. Dois segmentos = duas apps à espera.

O aviso desaparece quando:

- **abres a app** — deteção via `UsageStatsManager` (exige "acesso ao histórico
  de utilização", concedido nas definições do sistema);
- **tocas na barra** — abre a app mais recente e limpa o sinal; a janela só fica
  sensível ao toque enquanto houver pendências;
- **expira o tempo definido**, se tiveres configurado um (0 = nunca).

Limites honestos desta funcionalidade:

- **O som e a vibração já dispararam** quando a app recebe o evento; não é
  possível intercetá-los. Para silêncio total, define as notificações dessas
  apps como silenciosas em Definições → Apps → [app] → Notificações.
- Pode haver um piscar de milissegundos do pop-up antes do cancelamento.
- Notificações **persistentes/não-dispensáveis** não podem ser canceladas.
- A supressão só atua com a Glowbar **ligada**; caso contrário ficarias sem
  notificação e sem sinal luminoso.
- Sem o acesso ao histórico de utilização, o aviso só se limpa por toque ou por
  tempo — o resto funciona à mesma.
- O toque na barra só é captado com avisos pendentes; ainda assim, com a barra
  no topo pode apanhar o gesto de puxar o painel de notificações. Se te
  incomodar, desliga "Tocar na barra abre a app" ou usa outra margem.
- A notificação suprimida **perde-se** — não fica no histórico do sistema. A app
  guarda apenas o nome do pacote e a hora, para saberes que app te procurou;
  o conteúdo nunca é lido nem guardado.

## Modo sem notificações (acessibilidade)

A notificação "está a sobrepor-se a outras apps" é gerada pelo **Android**, não
pela app: aparece sempre que existe um overlay via permissão SYSTEM_ALERT_WINDOW,
e nenhuma app a consegue apagar por código.

Alternativa implementada: um `AccessibilityService` pode criar janelas
`TYPE_ACCESSIBILITY_OVERLAY` **sem** essa permissão, e não precisa de foreground
service. Resultado: zero notificações. Ativa em **Notificações da própria
Glowbar → Modo sem notificações**, e depois liga a Glowbar nas definições de
acessibilidade do Android.

Neste modo também deixa de ser necessário o acesso ao histórico de utilização,
porque o próprio serviço reporta a app em primeiro plano.

Contrapartida honesta: a permissão de acessibilidade é poderosa (permitiria ler
o ecrã). Esta app declara `canRetrieveWindowContent="false"` e só usa o evento
de mudança de janela para saber o nome do pacote em primeiro plano — o código
está todo aqui para verificares. Ainda assim, é uma permissão que só faz sentido
conceder a software que controlas.

## Aparência em repouso

Em **Aparência em repouso → Apagar quando não há avisos**, a barra fica cinzenta
e quase transparente (intensidade configurável, 0–60%) enquanto não há nada
pendente, e só ganha cor e halo quando chega notificação de uma app escolhida.
Nota: com este modo ligado, o indicador de bateria não colore a barra em repouso.

## Anel do cutout: 1.00x/1.00x é sempre um círculo

O `boundingRect` que o Android reporta para o punch-hole da câmara frontal
pode ter largura e altura ligeiramente diferentes mesmo em câmaras
verdadeiramente circulares (uma particularidade de alguns fabricantes). Por
isso o raio base do anel usa sempre a **largura** do cutout detetado para as
duas dimensões — a 1.00x/1.00x o resultado é sempre um círculo perfeito.
`Escala horizontal`/`Escala vertical` continuam disponíveis para ajustares
manualmente formas realmente ovais (câmaras "pill") ou fazeres correções
finas.

## Acordar o ecrã e duração do aviso

**Duração do aviso** define quanto tempo o sinal fica na barra: *Até abrir*
(fica até abrires a app ou tocares na barra), ou 5s / 10s / 30s / 1min / 5min /
30min. Definições antigas guardadas em minutos são convertidas automaticamente.

A contagem só corre com o **ecrã ligado**: com o telefone pousado o aviso fica
em pausa e só começa a expirar quando pegas no telefone. Sem isto, uma duração
curta faria desaparecer a notificação sem nunca a teres visto.

**Acordar o ecrã** liga o ecrã durante alguns segundos (2–30, configurável)
quando chega aviso de uma app privada, para veres o glow com o telefone
pousado. Usa um wake lock com timeout, que se liberta sozinho.

Limites honestos:

- Se o overlay aparece **por cima do ecrã de bloqueio** depende do fabricante.
  Nalguns modelos aparece; noutros o keyguard tapa-o e só verás o glow depois de
  desbloqueares. Não há forma de o garantir a partir da app — testa no teu.
- **Não é possível** desenhar no Always-On Display: essa camada é controlada
  pelo fabricante e não está acessível a apps de terceiros.
- Acordar o ecrã com frequência **gasta bateria**. Se receberes muitas
  notificações privadas, considera um tempo curto (2–5s).

## Se as apps privadas não fizerem nada

O **acesso às notificações é obrigatório** para esta funcionalidade — sem ele o
Android nem sequer liga o `NotificationListenerService`, portanto a app não fica
a saber que a notificação existiu e não a consegue esconder nem acender a barra.
O painel "Estado" no ecrã principal avisa quando falta.

Nota: depois de **reinstalar o APK**, o Android por vezes desliga o acesso às
notificações. Se deixar de funcionar após uma atualização, desliga e volta a
ligar a Glowbar em Definições → Notificações → Acesso a notificações.

## Consumo de recursos

A app fica sempre em memória, por isso o desenho foi optimizado para o processo
estar **ocioso** na maior parte do tempo:

- **O animador só corre quando há mesmo movimento.** Antes desenhava 60x por
  segundo permanentemente. Agora pára quando: o ecrã está apagado, a barra está
  escondida (fullscreen/Esconder), o modo é *Nenhuma* sem avisos pendentes, ou
  está em repouso apagado sem Pulse/Breathe. Em modo estático desenha uma vez e
  fica quieto.
- **Gradientes em cache.** Eram recriados a cada frame; agora só se refazem
  quando a geometria muda, o que reduz muito o trabalho do garbage collector.
- **Camada de software só quando é precisa.** O halo (`BlurMaskFilter`) obriga a
  render por software, bem mais caro. Em repouso apagado não há halo, por isso
  usa-se aceleração por hardware.
- **Sondagem da app em primeiro plano** só acontece enquanto há avisos
  pendentes, e nunca no modo de acessibilidade (aí o sistema avisa diretamente).

Memória: a Activity de definições é destruída ao fechar; o que fica residente é
apenas o serviço e uma View pequena. Se quiseres reduzir ainda mais o consumo,
o modo *Nenhuma* com *Apagar quando não há avisos* é a combinação mais barata —
nesse estado a app praticamente não usa CPU até chegar uma notificação.

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
