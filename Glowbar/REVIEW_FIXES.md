# Glowbar 1.1.0 — revisão integral e correções

## Problema principal confirmado

O evento da notificação chegava corretamente ao `NotificationListenerService` e a
notificação privada era cancelada. A falha estava sobretudo no desenho:

1. em repouso apagado, `GlowView` desenhava primeiro a barra cinzenta;
2. o flash desenhava depois apenas um halo com `BlurMaskFilter`;
3. o corpo da barra continuava cinzento, pelo que a alteração de cor podia ser
   impercetível ou parecer inexistente;
4. a opção de cor **Gradiente** era convertida em branco por `color ?: WHITE`.

Agora o flash desenha **halo + corpo sólido/gradiente**, tanto na barra como no
anel, e `null` representa realmente o gradiente animado.

## Correções efetuadas

### Renderização e estado visual

- O flash colore o corpo inteiro, não apenas o halo.
- A cor “Gradiente” usa o gradiente real, em vez de branco.
- Os avisos privados têm prioridade visual sobre notificações de progresso.
- Segmentos e arcos continuam válidos mesmo com muitas apps pendentes.
- O mesmo comportamento foi implementado para `BAR` e `CUTOUT`.
- Foi adicionado **Testar** ao diálogo de escolha de cor.

### Avisos privados e persistência

- Avisos pendentes são persistidos apenas com pacote, hora e cor; nunca é
  guardado título, texto ou conteúdo da notificação.
- Uma recriação do serviço/processo já não apaga o único sinal depois de a
  notificação do Android ter sido suprimida.
- Nova notificação da mesma app renova corretamente a hora e o timeout.
- Um timeout antigo já não pode apagar uma notificação mais recente.
- Alterar a cor de uma app atualiza também um aviso já pendente.
- Desmarcar uma app privada elimina qualquer aviso pendente, mesmo com o overlay
  desligado.
- Registos inválidos de apps desinstaladas ou já não privadas são removidos ao
  restaurar o estado.
- O aviso só é apagado pelo toque quando a app é realmente aberta com sucesso.

### Deteção de abertura da app

- O código anterior consultava os últimos 10 segundos e podia usar um evento de
  primeiro plano anterior à notificação, apagando imediatamente um aviso novo.
- Agora são considerados os timestamps de `ACTIVITY_RESUMED`/
  `MOVE_TO_FOREGROUND`, e o aviso só é limpo quando o evento ocorreu depois da
  notificação correspondente.

### NotificationListenerService

- Só cancela notificações privadas quando existe um overlay efetivamente ativo.
- Agrega summary/child da mesma app durante 750 ms para evitar flash e registo
  duplicados, embora tente cancelar ambos.
- Ignora group summaries no glow genérico.
- Requisita rebind quando o listener é desligado pelo sistema.
- Corrigida a lógica do primeiro evento no controlo de duplicados.

### Overlay, serviços e ciclo de vida

- Há um único `GlowController.active`, independentemente do modo clássico ou de
  acessibilidade.
- Ao trocar de modo, o controlador anterior é parado para evitar overlays
  duplicados.
- Uma vista antiga só é removida depois de a nova ser adicionada com sucesso.
- Se a criação inicial do overlay falhar, a app deixa de se considerar ativa e
  não suprime notificações sem apresentar sinal.
- Estado de pendências e progresso é reaplicado ao reconstruir a vista.
- Registo do receiver de bateria e callbacks pendentes é limpo corretamente.
- As ações Esconder/Mostrar atualizam agora o texto e a ação da notificação do
  foreground service.

### Permissões e interface

- Ao regressar da permissão “Mostrar sobre outras apps”, `MainActivity.onResume`
  volta a reconciliar o estado e arranca o serviço se o interruptor já estava
  ligado.
- O painel de estado indica falta de overlay, notification listener ou serviço
  de acessibilidade.
- A troca entre modo clássico e acessibilidade para o outro modo imediatamente.
- A lista de apps ocupa o ecrã todo e a pesquisa aceita nome ou package name.
- O seletor de timeout mostra minutos em vez de `dp`.

### Robustez adicional

- Enums e valores geométricos lidos de `SharedPreferences` são validados.
- O autostart respeita a opção correspondente e não tenta iniciar o foreground
  service quando está selecionado o modo de acessibilidade.
- `BootReceiver` não é exportado.
- Versão atualizada para `versionCode 2`, `versionName 1.1.0`.

## Teste recomendado no telefone

1. Instalar a nova versão e abrir a app.
2. Ativar **Mostrar Glowbar** e confirmar que aparece.
3. Em **Aparência em repouso**, ativar “Apagar quando não há avisos” e escolher
   0–15%.
4. Abrir **Aplicações privadas**, escolher uma app e uma cor.
5. Tocar em **Testar**: a barra deve ganhar essa cor imediatamente.
6. Com essa app fechada, provocar uma notificação:
   - a notificação do Android deve desaparecer;
   - a Glowbar deve fazer flash colorido;
   - com “Manter aviso até abrir a app”, deve ficar um segmento/arco a respirar.
7. Abrir a app notificada: o segmento deve desaparecer.
8. Repetir com “Gradiente”: deve surgir o gradiente, não branco.
9. Repetir com duas apps: devem aparecer dois segmentos/arcos.
10. Reiniciar o processo/telefone com um aviso pendente e confirmar que o sinal é
    restaurado quando a Glowbar volta a iniciar.

## Validação efetuada neste ambiente

- Todos os ficheiros Kotlin, Gradle, manifest e recursos foram revistos.
- Os XML foram analisados estruturalmente sem erros.
- O diff foi verificado sem problemas de whitespace.
- Não foi possível executar uma compilação Android real neste ambiente porque
  não existe SDK/Gradle local e a rede de execução não permitiu descarregá-los.
  A validação final de compilação deve ser feita pelo Android Studio ou pelo
  workflow GitHub Actions já incluído no projeto.
