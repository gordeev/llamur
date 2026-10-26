# Локальный API и Aider

Настройка сервера находится в нижней вкладке «Локальный API»: выбранная
локальная модель и профиль, отдельные публичные model ID и имя, порт, loopback
или Wi-Fi/TLS, запуск/стоп, копирование URL/токена и экспорт сертификата.
`/v1/models` отдаёт публичные ID/name; переименование не меняет исходный GGUF.
Входящие запросы API не становятся сохранёнными диалогами приложения.

Здесь описан **сервер llamur**: компьютер обращается к модели на телефоне.
Исходящие подключения OpenRouter/OpenAI/Claude/custom добавлены отдельно в0.2.0-dev:
[внешние провайдеры](providers.md). Этот сервер обслуживает только локальный GGUF;
он не является proxy облачных ключей/моделей.

Включение: загрузите модель → нижняя вкладка «Локальный API». Сервер по умолчанию
выключен, не восстанавливается после смерти процесса. Один движок обслуживает
чаты и API; конкурирующий запрос получает 429, очередь не создаётся.

Loopback: `http://127.0.0.1:8080/v1` и случайный Bearer token. Loopback не является
изоляцией от других приложений телефона, поэтому token обязателен. Для компьютера
без ADB включите Wi-Fi: приложение привяжет HTTPS только к конкретному IPv4
адресу Wi-Fi. При потере сети/адреса сервер выключается. В интернет его не открывать.

Экспортируйте публичный PEM из настроек API и настройте доверие клиента. Private
P-256 ключ остаётся в Android Keystore; TLS 1.2/1.3, проверку сертификатов отключать
не нужно. Сертификат содержит SAN для Wi-Fi IPv4, localhost и127.0.0.1. Случайный
API token живёт только в RAM текущего сервера. Копирование token — явное действие;
clipboard помечается sensitive. Ни access logs, ни тела запросов не сохраняются.

Поддержанный текстовый subset:

- `GET /v1/models`: ID загруженной модели и фактический настроенный контекст.
- `POST /v1/chat/completions`: system/user/assistant, строковый content,
  assistant reasoning_content, stream/non-stream, temperature0..5, uint32 seed,
  один из max_tokens/max_completion_tokens, n=1, top_p=1, до4 stop-строк по256 UTF-8
  байт. Stop действует на канал content; reasoning также входит в бюджет генерации.
- SSE: role/content/reasoning_content, finish_reason stop/length, необязательный
  `stream_options.include_usage`, завершающий `[DONE]`, heartbeat во время prefill.
  Usage считает полный логический вход и все вычисленные выходные токены, включая
  reasoning; reuse не выдаётся за заново обработанные токены.
- Тела до2МиБ, заголовки до16КиБ, строгий UTF-8/JSON без дубликатов ключей; ошибки
  model/auth/overflow/unsupported parameters явные. История не сокращается.
  Tools, images, JSON schema, embeddings, batches и logprobs не поддержаны.
- Необязательный `X-llamur-Session: <1..80 ASCII letters/digits/_/->` выделяет
  namespace для экспериментального RAM prefix cache. Без заголовка API cache
  не используется. UI-диалоги имеют отдельные namespaces.
- Disconnect отменяет только свой активный запрос; Stop/выгрузка прекращают работу
  движка. Смена модели/контекста между discovery и admission даёт409. Нет fallback
  на облако, другой GGUF или второй экземпляр весов.

## Aider

Проверена **0.86.2**: настоящий Aider coder, два последовательных изменения файла,
оба применены с нулём reflections; итоговые проверки выполнены отдельно в
read-only контейнере без сети. Ornith:110.33 и616.87сек, второй ответ содержал
около1.5K токенов рассуждения/ответа. Это функциональная совместимость, не обещание
интерактивной скорости или корректного кода в произвольной задаче. Артефакт:
`bench/results/aider-phone-20261026T034552Z/report.json`.

Прямое подключение к реальному Wi-Fi IP с TLS hostname/CA validation проверено.
Изолированный macOS клиент затем использовал локальный непрозрачный TCP relay с
единственным фиксированным Wi-Fi назначением; TLS оставался end-to-end до Android.
Это ограничение test sandbox, обычному клиенту relay не нужен. Никаких ADB forward,
облачных main/weak/editor моделей или коммитов в проверке не было.

Финальный повтор на Linuxd281 (все14nativeбиблиотек byte-identical release3af31)
тоже **PASS**: `bench/results/aider-phone-20261026T120319Z/report.json`.
Две правки156.30 и651.09сек,0reflections, оба изменения применены и итоговые
проверки кода прошли. Профиль теста8K/CPU4/F16KV, reserve2048/thinkingON,
cache128MiB/transientOFF; это отдельный профиль, не4K/cacheOFF default timing.

В этом повторе Mac и телефон были в разных подсетях. Использован **явный
developer USB relay** через авторизованный `adb shell -T toybox nc` к выбранному
Wi-Fi адресу самого телефона. Через relay проходили только TLSбайты; Python/
Aider проверяли экспортированный CA и SAN127.0.0.1, проверка TLS не отключалась.
Это подтверждает финальные kernels/API/Aider через USB, не новую достижимость
LAN. Предыдущий прямой Wi-Fi результат сохранён отдельно. Ошибочные preflight
попытки114607Z/115119Z сохранены; во второй исправлен недопустимый `nc -q 0`.
Обычному пользователю ADB не нужен: компьютер и телефон должны иметь взаимную
достижимость в Wi-Fi сети, без изоляции клиентов. HarnessUSB — developer-проверка.

Получите ID через `/v1/models`, скопируйте token из приложения. В окружении клиента:

```sh
export OPENAI_API_BASE=https://PHONE_WIFI_IP:8080/v1
# OPENAI_API_KEY задайте своим token из приложения; не коммитьте его в config.
export SSL_CERT_FILE=/absolute/path/motoai-api.pem
export REQUESTS_CA_BUNDLE=/absolute/path/motoai-api.pem
export LITELLM_LOCAL_MODEL_COST_MAP=True
```

В `.aider.model.settings.yml` подставьте фактический ID:

```yaml
- name: openai/MODEL_ID
  edit_format: whole
  weak_model_name: openai/MODEL_ID
  editor_model_name: openai/MODEL_ID
  use_repo_map: false
  extra_params:
    max_tokens: 2048
    timeout: 1800
    seed: 42
```

Параметры запуска: `--model openai/MODEL_ID --weak-model openai/MODEL_ID
--editor-model openai/MODEL_ID --edit-format whole --no-auto-commits
--no-dirty-commits --no-auto-lint --no-auto-test --no-analytics --no-check-update`.
Metadata клиента должна отражать настроенные input/output limits; сервер всегда
считает фактические токены своим tokenizer. Для нашего8K/2K теста max_input_tokens
6144, max_output_tokens2048, max_tokens8192. Настройки другого контекста нужно
изменить явно. Успешная проверка Aider не означает автоматическую совместимость
со всеми клиентами. [Настройка OpenAI-compatible endpoint](https://aider.chat/docs/llms/openai-compat.html),
[параметры моделей](https://aider.chat/docs/config/adv-model-settings.html).

Фон с выключенным экраном проверяется отдельно. Deep Doze может приостанавливать
CPU/network даже при FGS; приложение даёт ссылку на ручные настройки батареи,
а не обещает обход ограничений ОС. [Android Doze](https://developer.android.com/training/monitoring-device-state/doze-standby).
