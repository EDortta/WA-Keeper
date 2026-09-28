# Backup e restauração do WA Keeper no Android

O WA Keeper usa `android:allowBackup="false"` porque o banco contém conversas e outros dados privados. Por isso, desinstalar o app apaga os dados locais e o backup padrão do Android não é utilizado.

Para manutenção via ADB existe:

`scripts/android-data-backup.sh`

## Backup sem remover o app

```bash
bash scripts/android-data-backup.sh backup
```

O arquivo é salvo por padrão em:

`~/wa-keeper-backups/`

## Backup e desinstalação segura

```bash
bash scripts/android-data-backup.sh backup-remove
```

A desinstalação só acontece depois de:

1. copiar os dados privados;
2. testar o gzip;
3. testar o tar;
4. gerar checksums;
5. validar o pacote final.

Se qualquer etapa falhar, o script aborta sem desinstalar o WA Keeper.

## Restaurar

```bash
bash scripts/android-data-backup.sh restore ~/wa-keeper-backups/wa-keeper-backup-AAAA-MM-DD-HH-MM-SS.tar.gz
```

A restauração repõe:

- banco Room;
- SharedPreferences;
- arquivos privados, incluindo mídia copiada pelo WA Keeper;
- permissões runtime que estavam concedidas, quando o Android permitir.

Acessibilidade e Acesso a notificações são acessos especiais do Android. Depois de uma desinstalação, podem precisar ser reativados manualmente.

## Como funciona sem root

A build `release` é deliberadamente `non-debuggable`, portanto `run-as` não pode ler seus dados. O script compila e instala temporariamente a build `debug` por cima da release.

Neste projeto a release e a debug usam a mesma chave histórica de assinatura e o mesmo `applicationId`. A atualização preserva o diretório de dados. Enquanto a build debug está instalada, `run-as br.com.wanotifkeeper` consegue copiar/restaurar os arquivos privados.

Ao final de um backup sem remoção ou de uma restauração, o script reinstala a release non-debuggable.

## Segurança

O backup fica no computador local e pode conter conversas e mídia privada. Ele não deve ser commitado no repositório.
