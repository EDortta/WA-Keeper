# Modo Banco — implementação da issue #57

O Modo Banco é um fluxo explícito e controlado pelo usuário. Ele não tenta ocultar o WA Keeper nem contornar mecanismos antifraude.

Ao ativar:

- persiste `bank_mode_enabled=true`;
- interrompe leitura/TTS/áudio em andamento;
- encerra a escuta por voz e timers manuais;
- interrompe o processamento de novas notificações pelo WA Keeper;
- pausa a automação de envio de mídia por Acessibilidade;
- cancela temporariamente os alarmes de mensagens agendadas, sem apagar a fila;
- mantém banco local, histórico e configurações intactos.

A tela então verifica separadamente:

1. serviço de Acessibilidade do WA Keeper;
2. acesso especial de Notification Listener.

Como o Android não permite ao app revogar/reconceder esses acessos especiais de forma confiável, o usuário recebe atalhos para as telas oficiais do sistema e precisa desligá-los manualmente.

O estado **Seguro para tentar abrir o app bancário** só aparece quando:

- Modo Banco está ativo;
- Acessibilidade do WA Keeper está desativada;
- Acesso a notificações do WA Keeper está desativado.

Ao tocar em **Retomar WA Keeper**:

- `bank_mode_enabled` volta a `false`;
- mensagens agendadas continuam preservadas e o próximo alarme é reprogramado;
- a tela informa se Acessibilidade ou acesso a notificações ainda precisam ser religados manualmente.

## Validação pendente no Android real

A pergunta da issue continua experimental e precisa ser respondida no aparelho:

> desativar somente Acessibilidade já libera o Sicredi, ou também é necessário desligar o Notification Listener?

Registrar o resultado em evidência antes de transformar qualquer uma dessas etapas em recomendação específica para um banco.

## Fora de escopo

Não há ADB, root, Shizuku, alteração privilegiada de settings, mascaramento de serviço ou tentativa de enganar SDK antifraude.
