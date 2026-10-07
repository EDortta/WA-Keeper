# Processo de release

## 1. Desenvolvimento

O trabalho nasce em uma feature branch ou diretamente no fluxo de desenvolvimento autorizado.

Destino primário:
- development

## 2. Seleção para Play

Cada funcionalidade candidata é classificada:

- PLAY-SAFE
- EXPERIMENTAL
- BLOQUEADA
- EM REVISÃO

Somente PLAY-SAFE entra em play.

## 3. Candidate

Na branch play:

1. executar publisher/scripts/check-play-readiness.sh;
2. executar scripts/build.sh --store --bundle;
3. validar artefato;
4. registrar evidências em publisher/evidence/;
5. subir para Internal Testing;
6. validar instalação, atualização e fluxos críticos.

## 4. Publicação

Após aprovação:

- merge play -> main;
- criar tag da versão publicada;
- registrar commit, versionCode, versionName e hash do AAB;
- manter cópia das declarações feitas na Play Console.

## 5. Hotfix

Hotfix publicado segue:

hotfix/* -> play -> main

Depois deve ser retroportado para development quando aplicável.
