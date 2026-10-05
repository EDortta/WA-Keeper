# Benchmark térmico ASR — desempenho

- Android: SM-A175F
- Modelo: sherpa-onnx Whisper small INT8.
- Execução autônoma no próprio celular; ADB não participa durante as rodadas.

## Resumo

| Variante | Rodadas | Duração | Mediana | Média | Melhor | Pior | RTF vs original | Temp. média após | Thermal máx. |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Original | 6 | 100.0% | 214.4s | 217.7s | 210.0s | 235.9s | 1.36x | 33.5 °C | 1 |

## Recomendação automática

Original

## Execuções

| Rodada | Ordem | Variante | Duração | Tempo | Temp. antes | Temp. depois | Thermal antes | Thermal depois |
|---:|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | 1 | Original | 159.6s | 218.5s | 32.2 | 32.2 | 0 | 0 |
| 2 | 1 | Original | 159.6s | 210.0s | 32.4 | 33.0 | 0 | 0 |
| 3 | 1 | Original | 159.6s | 235.9s | 33.2 | 33.4 | 0 | 1 |
| 4 | 1 | Original | 159.6s | 213.3s | 33.6 | 33.8 | 0 | 0 |
| 5 | 1 | Original | 159.6s | 213.1s | 33.9 | 34.0 | 0 | 1 |
| 6 | 1 | Original | 159.6s | 215.5s | 34.2 | 34.3 | 0 | 1 |
