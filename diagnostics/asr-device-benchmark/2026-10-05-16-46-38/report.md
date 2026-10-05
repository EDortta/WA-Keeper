# Benchmark ASR — desempenho

- Android: SM-A175F
- Modelo: sherpa-onnx Whisper small INT8.
- Execução autônoma no próprio celular; ADB não participa durante as rodadas.

## Resumo

| Variante | Rodadas | Duração | Mediana | Média | Melhor | Pior | RTF vs original | Temp. média após | Thermal máx. |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Whisper base INT8 | 3 | 100.0% | 77.7s | 78.8s | 75.9s | 82.8s | 0.49x | 33.4 °C | 1 |
| Whisper small INT8 | 3 | 100.0% | 239.9s | 268.9s | 215.8s | 350.9s | 1.69x | 31.7 °C | 1 |

## Recomendação automática

Whisper base INT8

## Execuções

| Rodada | Ordem | Variante | Duração | Tempo | Temp. antes | Temp. depois | Thermal antes | Thermal depois |
|---:|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | 1 | Whisper small INT8 | 159.6s | 350.9s | 28.8 | 30.0 | 0 | 0 |
| 1 | 4 | Whisper base INT8 | 159.6s | 82.8s | 32.5 | 32.5 | 0 | 0 |
| 2 | 2 | Whisper small INT8 | 159.6s | 239.9s | 31.2 | 32.5 | 0 | 1 |
| 2 | 5 | Whisper base INT8 | 159.6s | 75.9s | 32.5 | 33.8 | 0 | 0 |
| 3 | 3 | Whisper small INT8 | 159.6s | 215.8s | 32.5 | 32.5 | 0 | 1 |
| 3 | 6 | Whisper base INT8 | 159.6s | 77.7s | 33.8 | 33.8 | 0 | 1 |
