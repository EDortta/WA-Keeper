# Godofredo benchmark

Status: EM VALIDAÇÃO.

Objetivo: separar o reconhecimento de voz do restante do WA Keeper para diagnosticar o Godofredo sem interferir no aplicativo principal.

## Fase 1 — instrução para transcrição

O APK paralelo `br.com.wanotifkeeper.godofredo.benchmark` executa uma única sessão on-device por toque. Ele mede criação do recognizer, `startListening`, prontidão, começo/fim da fala, resultados parciais e finais, alternativas, confidências, latências e erros.

Nesta fase não há parser de comandos, acesso a conversas, TTS, wake word nem loop de mãos livres. Primeiro caracterizamos se a instrução chega ao reconhecedor e como é transcrita.

## Execução

```bash
git pull
bash scripts/godofredo-benchmark-install-start.sh
```

Faça várias tentativas no aparelho. Depois:

```bash
bash scripts/godofredo-benchmark-collect.sh
```

A captura completa, incluindo a transcrição reconhecida, fica apenas em `diagnostics/godofredo-device-benchmark/**/raw/`, ignorada pelo Git.

O script também cria evidência sanitizada em `publisher/evidence/godofredo/`, própria para versionamento. Ela contém contagens, taxa de sucesso, latências e erros, mas não texto falado.

## Fase 2 — mãos livres

Só depois da Fase 1 estar caracterizada adicionamos wake word e microfone contínuo. Assim não confundimos falha do reconhecimento com falha da ativação.
