# Glowbar 1.2.0 — correções e robustez

## Correções funcionais

- O `AccessibilityService` está agora exportado para o sistema, mantendo a
  proteção por `BIND_ACCESSIBILITY_SERVICE`.
- Compatibilidade real com Android 8/9: substituídas APIs exclusivas do Android
  10 na verificação de Usage Access e nos eventos de foreground.
- A permissão `POST_NOTIFICATIONS` é pedida antes de abrir a página da permissão
  de overlay, evitando perder o diálogo no primeiro arranque.
- O halo volta imediatamente para renderização por software quando começa um
  flash, aparece progresso ou existem avisos pendentes.
- Notificações privadas só são suprimidas quando o aviso pode ser mostrado ou
  persistido com segurança.
- O cancelamento de notificações privadas tem logs, verificação e uma segunda
  tentativa curta para fabricantes que reapresentem a mesma notificação.
- Notificações não dispensáveis deixam de ser tratadas como se tivessem sido
  escondidas.
- Uma app que já esteja em primeiro plano não cria um aviso persistente que
  ficaria preso.

## Overlay e interação

- A janela visual permanece sempre intocável.
- O toque para abrir usa uma segunda janela transparente e pequena, limitada à
  barra/anel. O modo cutout já não bloqueia uma faixa de 240 dp no topo.
- A área de toque desaparece com o ecrã apagado, fullscreen, esconder temporário
  ou quando não existem pendências.
- A altura da janela do cutout cresce quando são usados deslocamentos verticais
  positivos ou escalas grandes, reduzindo cortes do anel.
- O wake lock é reutilizado, não é reference-counted e é libertado ao parar.
- Os filtros de blur são reutilizados através de uma cache limitada, reduzindo
  alocações e pausas do garbage collector durante as animações.

## Expiração dos avisos (1.2.1)

- A contagem para expirar um aviso privado **fica em pausa com o ecrã apagado**
  e só retoma quando o ecrã liga. Antes, com uma duração curta (por exemplo 5s)
  e o telefone pousado, a notificação era suprimida do sistema e o aviso
  expirava sem o utilizador ter tido qualquer hipótese de o ver — ficava sem
  notificação e sem glow.
- Uma notificação nova da mesma app reinicia a contagem; caso contrário retoma-se
  apenas o tempo que faltava.

## Interface e privacidade

- O botão/gesto Voltar do Android regressa corretamente da lista de apps.
- Limpar o histórico atualiza imediatamente o ecrã.
- O backup automático foi desativado para não copiar regras e registos privados.
- O serviço de acessibilidade deixou de reler todas as preferências em cada
  mudança de janela.

## Build e CI

- Versão atualizada para `1.2.0` (`versionCode 3`).
- GitHub Actions preparado para push, pull request e execução manual.
- O workflow instala Android SDK 35 e Gradle 8.7.
- Executa lint, testes unitários e build do APK de debug.
- Publica `app-debug.apk` como artefacto descarregável.
