# Issue #56 — Diagnóstico ADB: identificar a chave real das conversas do WhatsApp

> Origem: https://github.com/EDortta/WA-Keeper/issues/56
>
> Execução esperada do agente no devel3: implementar o diagnóstico, executar contra o Android conectado, analisar as evidências, corrigir a identidade das conversas com base nos dados reais, rodar testes/CI local, compilar o APK e instalar no Android conectado. Não apagar o banco nem reinstalar do zero. Ao final, deixar as evidências e alterações no repositório para teste manual do usuário.

## Contexto

O Android está conectado fisicamente ao host **devel3**.

A versão atual do WA Keeper ainda mostra conversas duplicadas na home e também no seletor de associação de entidades, apesar das tentativas de normalização por título.

Exemplos reais observados:
- várias entradas visualmente iguais para `Esteban-VG...`
- várias entradas para `Pr.Esteban (...)`
- anteriormente, variantes como `Open`, `Open: Luís Henrique`, `Open: Você`, etc.

O problema já passou por CI e build, portanto não devemos continuar corrigindo por hipótese/regex sem observar os dados reais emitidos pelo WhatsApp no aparelho.

## Objetivo

Criar e executar no **devel3** um diagnóstico via ADB que capture a identidade real das notificações do WhatsApp e WhatsApp Business, para descobrir qual campo deve ser usado como chave estável de conversa.

## Implementação esperada

Criar um script resiliente e idempotente, preferencialmente:

`tools/diag-conversation-identity.sh`

O script deve:

1. confirmar que há exatamente um Android acessível via `adb devices`;
2. registrar modelo, Android version, package/version do WhatsApp e WhatsApp Business;
3. capturar `dumpsys notification --noredact` e/ou outra fonte ADB apropriada;
4. extrair, sempre que disponíveis, para notificações de:
   - `com.whatsapp`
   - `com.whatsapp.w4b`

   os campos:
   - package
   - notification key
   - tag
   - groupKey
   - shortcutId
   - channelId
   - `Notification.EXTRA_TITLE`
   - `Notification.EXTRA_TEXT`
   - `Notification.EXTRA_BIG_TEXT`
   - `Notification.EXTRA_CONVERSATION_TITLE`
   - indicador de group conversation
   - bundles de `Notification.EXTRA_MESSAGES`
   - sender/person de cada mensagem do MessagingStyle
   - timestamp
   - qualquer identificador adicional de conversa exposto pelo Android

5. nunca gravar credenciais;
6. preservar evidência bruta e também uma versão resumida;
7. gravar tudo em uma pasta versionável, por exemplo:

`evidence/conversation-identity/YYYY-MM-DD-HH-MM/`

com:
- `device.txt`
- `notifications-raw.txt`
- `notifications-summary.txt`
- `README.md` com instruções e interpretação

8. sanitizar apenas o que for necessário por segurança, sem remover os campos técnicos usados para comparar notificações;
9. poder ser executado várias vezes sem destruir evidências anteriores;
10. retornar exit code != 0 em erro real.

## Procedimento de teste no devel3

Com o telefone conectado:

1. deixar abertas/ativas algumas conversas que atualmente aparecem duplicadas no WA Keeper;
2. fazer chegarem mensagens nessas conversas, se necessário;
3. executar o script;
4. comparar notificações que o WA Keeper hoje considera conversas distintas mas que pertencem ao mesmo chat;
5. identificar qual campo permanece igual entre elas.

## Critério para a correção posterior

Não alterar novamente a regra de agrupamento até existir evidência concreta mostrando:

- qual campo é estável para a mesma conversa;
- se ele muda entre WhatsApp e WhatsApp Business;
- como tratar grupos;
- como tratar conversas individuais;
- qual fallback usar quando o campo estável não estiver disponível.

A implementação futura deve usar esse identificador como **conversation identity** e deixar título apenas para exibição.

## Regressão a proteger

Depois de encontrada a chave correta, criar fixture/teste com dados reais anonimizados do diagnóstico garantindo:

- uma conversa = uma entrada na home;
- uma conversa = uma entrada no seletor de entidades;
- mensagens antigas da mesma conversa continuam acessíveis;
- WhatsApp pessoal e Business continuam separados;
- grupos não colidem com contatos de mesmo nome.

## Importante

Não apagar o banco atual nem reinstalar o app durante o diagnóstico. Precisamos observar o estado real que produziu a duplicação.

## Entrega esperada do agente

Após obter as evidências:

1. implementar a correção baseada nos dados reais;
2. adicionar/atualizar testes de regressão;
3. executar `./gradlew test assembleDebug` ou fluxo equivalente;
4. compilar o APK no **devel3** usando a chave já utilizada nesse ambiente;
5. instalar/atualizar o APK no Android conectado via ADB, preservando dados;
6. registrar no repositório as evidências do diagnóstico e um resumo da decisão técnica;
7. deixar o aparelho pronto para o usuário realizar o teste manual final.
