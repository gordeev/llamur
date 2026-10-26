# Эксперимент полного prefix-state в RAM

Включается явно через `prefix_cache_mib` (64–256 МиБ); default **0, выключен**.
Один снимок в памяти принадлежит одному загруженному Runtime. Файлов состояния
нет. Веса, tokenizer, шаблон, KV precision и полный вход не меняются.

Снимок содержит attention KV **и recurrent state**, полученные штатным полным
seq-state API. Простое удаление хвоста KV для Ornith не используется. Повторное
применение требует одинаковой непрозрачной `cache_session` и точного совпадения
всех token IDs сохранённого префикса. Смена сессии, модели или конфигурации
исключает reuse. Выгрузка освобождает снимок. Остальной вход вычисляется полностью;
снимок захватывается на прежней границе batch512 до конца промпта, поэтому для
актуальных logits всегда остаётся хотя бы один новый токен.

Текущий эксперимент ограничен CPU/native/qwen35, context≤8192, threads≤4,
batch512/ub128, без transient packing. App admission дополнительно разрешает
только закреплённый SHA Ornith и резервирует весь выбранный cache budget поверх
прежнего бюджета памяти. Предыдущий снимок освобождается до выделения нового.
При несовпадении или неудаче restore весь вход вычисляется заново. Снимок,
превышающий бюджет, не создаётся. Эту функцию нельзя выдавать за принятый профиль.

`prompt_tokens` — полный логический вход, `processed_prompt_tokens` — реально
вычисленная часть; `reused_prompt_tokens` показывается отдельно. TTFT включает
restore, prefill включает capture. Обычный prefill tokens/s нельзя считать по
полному входу при cache hit.

Host-проверки:

- [Прототип полного состояния](../bench/results/state-prefix-host-20261026/report.json):
  Ornith1024-token restore сохранил все raw logits и NLL26 фиксированных токенов
  точно. Это конечный набор, не доказательство для любого запроса.
- [Девять проверок через Runtime](../bench/results/prefix-cache-host-runtime-20261026/report.json):
  точные NLL/top8/IDs/output против cache-disabled; реальный hit1024;
  namespace/prefix misses, отмена по старому ticket, recovery и unload.
  Снимок86270772 байт. Host timings под параллельной сборкой не приняты как benchmark.
- Gemma:512-token seq-state точен,1024-token состояние после roundtrip имеет
  идентичные сериализованные байты, но logits/NLL расходятся. Whole-context
  restore тоже не прошёл. Возможная связь с расположением sliding-window KV
  пока не является установленной причиной или исправлением. Поэтому архитектура
  Gemma отклоняется для этого экспериментального cache до загрузки весов.

Следующая приёмка: `scripts/run-prefix-checks.py` на телефоне через обычный
RuntimeService — исходный расчёт, miss/hit, Stop после restore, recovery,
изоляция сессий и unload. Отдельно нужны matched TTFT, память, длинный/многоходовый
контекст и quality suite до включения в профиль продукта.

## Exact-layout candidate (26 October)

The legacy Gemma failure is caused by a compact state restore changing physical
KV slots/head while sliding-window kernels retain layout-sensitive execution.
Optional patches0016/0018 retain occupied physical slots (including currently
SWA-masked cells) and the head. A separate header rejects compact/exact-layout
flag mismatches before parsing cache metadata. Flag0 remains upstream-compatible.
Only one full host sequence0 is supported; PARTIAL/ON_DEVICE/multisequence
combinations are rejected. These are process-local RAM snapshots, never portable
files, and their bytes must not be accepted from a client.

Host Gemma1024 and Ornith1024 preserve all raw logits/NLL exactly after truncated
header/payload, invalid magic, mismatched flags, invalid sequence and multisequence
negative controls followed by valid restoration. The previous Gemma3072 exact
layout positive control is retained separately. New full Runtime and Android
service checks are still pending. Experimental cache stays off by default.

The new Runtime candidate captures completed512-token chunks progressively and
retains the last fitting prefix when a later snapshot exceeds its selected
64–256MiB budget. One allocation is reserved and reused in place; capture/restore
costs remain included in TTFT/prefill accounting. Namespace changes and unload
clear the snapshot. No text is dropped when caching is unavailable or fails.
