# Benchmark térmico ASR — desempenho

- Android: SM-A175F
- Modelo: sherpa-onnx Whisper small INT8.
- Execução autônoma no próprio celular; ADB não participa durante as rodadas.

## Resumo

| Variante | Rodadas | Duração | Mediana | Média | Melhor | Pior | RTF vs original | Temp. média após | Thermal máx. |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Original | 3 | 100.0% | 260.1s | 260.2s | 258.1s | 262.4s | 1.63x | 32.5 °C | 1 |
| Silêncio removido + 1,15x | 3 | 80.1% | 268.0s | 267.2s | 263.9s | 269.7s | 1.67x | 32.8 °C | 1 |

## Recomendação automática

Original

## Execuções

| Rodada | Ordem | Variante | Duração | Tempo | Temp. antes | Temp. depois | Thermal antes | Thermal depois |
|---:|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | 1 | Original | 159.6s | 260.1s | 31.5 | 31.5 | 0 | 1 |
| 1 | 2 | Silêncio removido + 1,15x | 127.8s | 268.0s | 31.5 | 32.6 | 1 | 1 |
| 2 | 1 | Silêncio removido + 1,15x | 127.8s | 263.9s | 32.6 | 32.8 | 1 | 1 |
| 2 | 2 | Original | 159.6s | 262.4s | 32.8 | 33.0 | 1 | 1 |
| 3 | 1 | Original | 159.6s | 258.1s | 33.0 | 33.0 | 1 | 1 |
| 3 | 2 | Silêncio removido + 1,15x | 127.8s | 269.7s | 33.0 | 32.9 | 1 | 1 |
