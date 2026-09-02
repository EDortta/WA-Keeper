# Napkin Lessons — WA-Keeper

Lições curtas e acionáveis, capturadas no fechamento de sessão.
Uma lição por entrada; sem narrativa.

## Formato
- `[YYYY-MM-DD] <work_id> - <lição>`
- `Action next time: <comportamento concreto a repetir/evitar>`

## Entradas
- `[2026-08-19] voice-commands-fase-0-3 - Nome estrangeiro como palavra de ativação ("Jeeves") transcreve de forma inconsistente no reconhecedor on-device pt-BR ("deeps", "diz", "açude"); uma palavra real do dicionário ("Godofredo") transcreve estável.`
- `Action next time: escolher palavra de ativação que exista no dicionário do idioma do reconhecedor, nunca um nome importado.`
- `[2026-08-19] voice-commands-fase-0-3 - RecognizerIntent.EXTRA_LANGUAGE precisa da string literal "pt-BR"; Locale.forLanguageTag("pt-BR").toString() produz "pt_BR" e cai em inglês silenciosamente.`
- `Action next time: passar tag BCP-47 literal para APIs de reconhecimento, e verificar o idioma efetivo no log em vez de assumir que o Locale converteu certo.`
- `[2026-09-01] epic3-image-review - Concílio de entrega, 6 lentes em 2 rodadas: 31 achados levantados, 13 corrigidos, 5 viraram teste unitário, 10 vivos ao fim da rodada 2, 4 perguntas parqueadas para o operador. Duas das 13 correções da rodada 1 abriram problema novo, e a rodada 2 pegou as duas — com as duas lentes chegando nelas de forma independente.`
- `Action next time: tratar toda correção de rodada 1 como código não revisado, e reservar a rodada 2 para verificá-las uma a uma antes de procurar achado novo — foi exatamente isso que pegou S1 e S2.`
- `[2026-09-01] epic3-image-review - Gatilho de captura de mídia por "o texto contém a palavra foto" (containsMatchIn) anexa imagem alheia a mensagem de texto por coincidência temporal. Corrigir com âncora (emoji no início, ou o texto ser só o rótulo) resolve; mas o strip de prefixo "Remetente: " acrescentado logo depois, para não perder grupo, reabriu o buraco em "Ana: fotos".`
- `Action next time: gatilho de captura de mídia nasce ancorado, e todo relaxamento posterior ("só para pegar o caso do grupo") precisa do seu próprio teste de falso positivo no mesmo commit — senão desfaz a âncora sem que nada acuse.`
- `[2026-09-01] epic3-image-review - Janela de tempo, carência de estabilização de arquivo e escada de retry foram escritas em três momentos, cada uma coerente sozinha e as três contraditórias juntas: a janela declarada de 12 s virou 9 s úteis, e a primeira varredura só conseguia achar arquivo ANTERIOR à notificação.`
- `Action next time: quando três constantes de tempo governam o mesmo fluxo, derivar umas das outras em código (ceiling = último retry − settle), nunca alinhá-las por comentário.`
- `[2026-09-01] epic3-image-review - Um worktree criado de um commit antigo não tem a infraestrutura de teste que já existe em development (app/src/test, testImplementation junit): o agente conclui "o repo não tem teste" e escreve sem rede.`
- `Action next time: ao abrir frente em worktree, conferir o merge-base e o que development ganhou depois dele ANTES de decidir o que existe no projeto.`
- `[2026-09-01] epic4-scheduled-msg - A correção do BLOCKER da rodada 1 desarmou, no mesmo movimento, o item do Definition of Done que ela queria reforçar: devolver a tentativa no caso "notificação sem ação de resposta" zerou attempts, e a tela só mostrava o erro com attempts > 0 — a impossibilidade que a issue manda registrar sumiu da interface, com três KDocs afirmando o contrário.`
- `Action next time: depois de corrigir um blocker, reler o item do DoD que ele protegia contra a UI, não contra o KDoc — auto-revisão lê o que se quis dizer, não o que se escreveu.`
- `[2026-09-01] epic4-scheduled-msg - 11 dos 18 achados da rodada 2 foram INTRODUZIDOS pelas correções da rodada 1; sem a classificação obrigatória (antigo / pré-existente recém-visto / introduzido), a contagem de achados vira ruído e não indica quando parar.`
- `Action next time: rodar a rodada 2 contra o artefato corrigido e obrigar cada achado a se classificar; proporção alta de "introduzido" é sinal de parar e levar ao operador, não de corrigir de novo.`
- `[2026-09-01] coordenação - O helper awt cria dentro do worktree um .credentials/store que é symlink para ~/.config/credentials/personal, mais um .env — e nenhum dos dois estava no .gitignore, num repo cuja seção SEC-0066 existe exatamente para isso.`
- `Action next time: ao adotar helper que materializa credencial dentro da árvore de trabalho, fechar o .gitignore no mesmo passo, antes de qualquer git add.`
- `[2026-09-01] teste-campo - Cinco minutos de adb dumpsys notification --noredact no aparelho responderam quatro premissas que uma rodada inteira de dois agentes tinha deixado parqueadas: a ação "Responder" existe e é startService (não cai na restrição de background activity launch), o Person vem preenchido em conversa 1-a-1, a notificação traz uri content:// com type=image/jpeg, e o texto é literalmente "📷 Foto".`
- `Action next time: quando a premissa é sobre o que o SO entrega, pedir o aparelho ANTES de projetar em cima de suposição — o dump é barato e decide o desenho.`
- `[2026-09-01] teste-campo - O resumo do grupo de notificações (flags=GROUP_SUMMARY) repete o texto da própria mensagem quando há uma conversa com uma mensagem não lida, e vira "Novas mensagens: N" só a partir da segunda. Por isso a mesma mensagem era lida em voz alta duas vezes, e o padrão relatado era "lê a primeira duas vezes e depois a segunda".`
- `Action next time: em NotificationListenerService, descartar GROUP_SUMMARY antes de qualquer processamento — ele nunca traz informação que a notificação filha não traga.`
- `[2026-09-01] teste-campo - Mover um guard para depois do código que ele protegia deixou o comentário explicativo órfão no lugar antigo, ainda afirmando a proteção. A leitura em voz alta ficou desprotegida por horas e só apareceu porque o operador ouviu.`
- `Action next time: ao mover uma guarda, mover o comentário junto na mesma edição — comentário órfão que ainda afirma a garantia é pior que comentário nenhum, porque a próxima leitura acredita nele.`
- `[2026-09-01] teste-campo - O aviso sonoro do reconhecedor on-device toca a cada REINÍCIO de sessão, e reiniciar indefinidamente só faz sentido para quem espera palavra de ativação. Microfone aberto por toque tem que ser sessão única: um aviso no início, um no fim, silêncio encerra em vez de reagendar.`
- `Action next time: separar "vigiar" de "atender um pedido" no motor de voz — herdar o ciclo da vigilância para um comando pedido por toque produz barulho que nenhuma API silencia.`
- `[2026-09-01] teste-campo - Identidade de mensagem por "mesmo texto em 2 s" não distingue repost de repetição intencional. O timestamp da mensagem no MessagingStyle é estável entre reposts e diferente a cada mensagem nova.`
- `Action next time: para idempotência de notificação, usar o timestamp da MENSAGEM como chave, e contar a validade da primeira vez que ela foi vista, nunca renovando a cada repost.`
