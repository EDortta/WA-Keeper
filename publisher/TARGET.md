# Alvo da versão Play

## Posicionamento

WA Keeper é um organizador pessoal de conversas e notificações, com foco em retenção local, busca, acessibilidade por voz, transcrição e organização por entidades.

## Mostrar

- histórico local organizado;
- busca;
- leitura por TTS;
- transcrição de áudio;
- entidades/contatos;
- agendamento quando compatível com a variante Play;
- controles de privacidade;
- processamento local quando de fato local.

## Evitar como mensagem principal

- "recuperar mensagens apagadas";
- "guardar antes que apaguem";
- vigilância de terceiros;
- linguagem anti-Play Store;
- promessas como "nada sai do aparelho" enquanto houver funções de rede, Drive ou serviços remotos.

## Estratégia de variantes

A versão sideload continua podendo ter capacidades que a Google Play não aceite.

A variante Play deve preservar o núcleo do produto e remover ou substituir apenas capacidades incompatíveis.

## Requisitos técnicos já conhecidos

Na branch development, em 2026-10-06:

- applicationId: `br.com.wanotifkeeper`
- minSdk: 26
- targetSdk atual: 34
- versionName atual: 1.0.9
- release atual usa `signingConfigs.debug`

Esses valores devem ser revalidados antes de cada submissão.
