# Діагностика Ollama — 2026-11-13–14

## Підтверджені факти

- Модель збережена: `llama3.2:1b`, Q8_0, файл приблизно 1.32 GB. Модель не замінювали.
- Ollama 0.40.3 працює в контейнері `ollama`, образ `ollama/ollama`, порт 11434. На початку контейнер працював близько чотирьох тижнів.
- Docker Engine повідомив 8 CPU та 4 106 604 544 bytes RAM (~3.825 GiB). Окремі container limits: Memory=0, NanoCpus=0, тобто додаткових контейнерних лімітів немає; це не скасовує обмеження Docker VM.
- Початковий `/api/ps` повернув порожній список моделей. Тому початковий запит включав холодне завантаження.
- Docker logs показав CPU model buffers ~266+1252 MiB, KV 64 MiB при num_ctx=2048, compute buffer ~66 MiB. Це не повний вимір пікового RSS.
- Журнал містить `client connection closed before llama-server finished loading, aborting load` та `timed out waiting for llama-server to start: context canceled`. Отже, принаймні частина таймаутів відбулася під час завантаження/прогріву, а не розбору готової відповіді Java.
- Під час діагностики зависали також `/api/ps`, `docker inspect` і `docker logs`. Нестачу RAM чи OOM не доведено; стан Docker/Ollama був нестабільним.

## Вимірювання до перезапуску

| Перевірка | Фактичний час | Результат |
|---|---:|---|
| Прямий POST /api/generate, Python, короткий промпт, 48 токенів | 45.083 s | timeout, JSON не отримано |
| Повторний такий самий POST | 45.108 s | timeout, JSON не отримано |
| Java GET /api/tags, HTTP/1.1 | 4110.812 ms | timeout |
| Java GET /api/tags, HTTP/2 preference | 4009.615 ms | timeout |
| docker inspect / logs | по 8 s | diagnostic timeout |
| curl /api/ps | 3.005 s | timeout без байтів відповіді |

Це час очікування до таймауту, **не час успішної генерації**. У сирому JSON другий запуск має історичний ярлик `warm`, але модель не завершила перший запит, тому прогрітий стан не підтверджено. Порівняння HTTP/1.1 та HTTP/2 не виявило переваги; змінювати протокол Java-клієнта без доказів не стали.

Сирі дані: `benchmarks/ollama/direct-results.json`, `http-protocol-results.txt`, `runtime-diagnostics.json`.

## Дії та параметри

Перезапущено **лише контейнер ollama** командою docker restart --timeout 10 ollama; exit code 0. Дані, модель і глобальні налаштування Docker не змінювали. Перший backend smoke після restart повернув `OLLAMA_UNAVAILABLE`; пізніше прямі /api/tags та /api/version знову відповіли.

Оптимізація OllamaService:

- короткий system prompt просить 3–5 слів;
- num_predict: 160 → 48;
- num_ctx: явно 2048;
- temperature: 0;
- keep_alive: явно 5m (типове значення Ollama, не окрема гарантія прискорення);
- stream:false і format:json збережено;
- backend generation timeout: 30s, health:1s, connect:2s;
- fallback з початковими термінами збережено, обрізаний/некоректний JSON відхиляється.

HttpClient.sendAsync + BodyHandlers.ofString завершується після повного HTTP body; future.get не додає фіксованого очікування після завершення. Streaming, sleep і додатковий retry відсутні. Успішні імітовані відповіді парсяться одразу. До прогріву готової відповіді не було; після прогріву прямі виміри нижче показують, що більшість часу витрачається всередині Ollama.

## Перевірки

Maven verify: **51 тест, 0 failures/errors/skipped, BUILD SUCCESS**. Попередні 50 тестів збережено. Новий тест читає фактичний HTTP body publisher і перевіряє модель, stream=false, format=json, короткий prompt, num_predict, num_ctx та keep_alive. JDK 24, target release 21. JAR зібрано.

Реальний POST /api/search через оптимізований JAR після restart повернув HTTP 200 із `fallback:true`, `warning:OLLAMA_UNAVAILABLE`, термінами `parallel`, `computing` та порожніми результатами. Це підтверджує резервний шлях, **не успішне розширення**. Тестовий backend зупинено.

## Що робити із середовищем

1. Якщо Docker CLI знову зависає, відновити Docker Desktop (його глобальний restart тут не виконувався), дочекатися стабільних docker info і /api/tags.
2. Перевірити Activity Monitor та Docker memory usage під час окремого прогріву. Лише якщо підтверджено memory pressure і на хості достатньо вільної RAM, збільшити Docker Desktop → Settings → Resources → Memory, наприклад до 6–8 GiB. Це умовна рекомендація, не встановлена необхідна вимога цієї моделі; автоматично її не застосовано.
3. На macOS Docker Desktop не передає Apple GPU в контейнер Ollama, тож CPU-only inference очікуваний. Для локальної швидкодії можна окремо погодити нативну Ollama на Mac з тією самою моделлю. У цьому завданні її не встановлювали й модель не переносили. Джерело: [офіційний Ollama FAQ](https://github.com/ollama/ollama/blob/main/docs/faq.mdx).
4. Після успішного прогріву повторити direct_probe.py та HTTP smoke; порівняти load_duration, eval_duration, total_duration і wall time. Формат метрик та keep_alive: [офіційний Ollama API](https://github.com/ollama/ollama/blob/main/docs/api.md).

## Уточнення попереднього звіту

Попередні перевірки з таймаутами 30/60s не підтвердили генерацію. У збереженому backend health було `ollama:false`; твердження старого README про true виправлено. Прямий /api/tags і backend health виконувалися в різні моменти, тому їх не слід ототожнювати.

## Окремий прогрів після restart

POST /api/generate з порожнім prompt, stream:false, keep_alive:5m і num_ctx:2048 завершився **за 42.861048 s**, повернувши `done:true`, `done_reason:"load"`. Це справжня відповідь Ollama, але ще не генерація термінів. Сирий результат: `benchmarks/ollama/preload-result.json`. Для цього діагностичного прогріву задано окремий дедлайн 90s; пошуковий API залишився з 30s. Факт пояснює, чому 30s може бути замало для холодного запуску; попередні збої при 60s не можна приписати лише цьому завантаженню, оскільки Docker також зависав.

## Успішна справжня генерація після прогріву

| Запуск | Wall, s | Ollama total, s | Load, s | Prompt eval, s | Generation, s | Токенів |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 41.594 | 40.812 | 0.039 | 2.462 | 38.230 | 26 |
| 2 | 39.120 | 39.089 | 0.007 | 2.299 | 36.768 | 26 |

Обидві відповіді: done=true, done_reason=stop, повний валідний JSON:

```json
{"terms":["parallel computing","multi-core processor","high-performance computing","cloud computing","big data"]}
```

Сирі виміри: benchmarks/ollama/direct-after-preload.json. Генерація ~0.68–0.71 токена/с у цьому середовищі. Навіть після завантаження 26 токенів потребують 36.8–38.2s, тож 30s замало для цього запиту. Це підтверджена повільна генерація CPU-runner; конкретну причину низької швидкості (memory pressure, VM scheduling тощо) не встановлено. Немає виміряної успішної бази зі старими 160 токенами, тому кількісне прискорення від змін параметрів не заявляємо.

Для цього Docker-середовища доцільно виконати окремий прогрів і задати OLLAMA_GENERATION_TIMEOUT=60s. Ліміт залишається скінченним; типовий 30s у конфігурації не змінено для швидших середовищ.

## Повторна перевірка Java health після стабілізації

Усі чотири варіанти повернули HTTP 200: localhost HTTP/1.1 — 636.379ms, localhost HTTP/2 preference — 27.663ms, 127.0.0.1 HTTP/1.1 — 10.863ms, 127.0.0.1 HTTP/2 preference — 15.160ms. Це послідовні одиничні запуски, не benchmark протоколів. Вони не підтверджують проблему DNS/IPv6. Сирі дані: benchmarks/ollama/http-protocol-after-restart.txt.

## Java POST: перевірка формату й протоколу

Ідентичний оптимізованому запит із query=java через Java HttpClient.sendAsync:

| Режим | Wall, s | Ollama total, s | Load, s | Generation, s | Вихідних токенів |
|---|---:|---:|---:|---:|---:|
| HTTP/1.1 | 34.463 | 32.738 | 30.241 | 2.139 | 39 |
| HTTP/2 preference | 8.181 | 6.492 | 0.024 | 5.712 | 39 |

Обидва — HTTP 200, done=true, done_reason=stop, валідний JSON terms. Сирий файл: benchmarks/ollama/java-generation-results.txt. Транспортний overhead цих запусків близько 1.7s, а не попередні 30–60s очікування. Версії протоколу не порівнюються як benchmark: перший запит включав повторне завантаження. Java-клієнт не має підтвердженого дефекту обробки stream:false.

Промпт просив 3–5 ключових слів, але модель повернула 10 коротких фраз. Це показує, чому потрібні незалежні обмеження у коді: максимум 48 генерованих токенів, валідація фраз та максимум 8 додаткових нормалізованих токенів. Сам промпт не є гарантією кількості.

Додано журнал транспортних ExecutionException без query/prompt, щоб надалі відрізняти недоступність, переривання з’єднання й інші HTTP-помилки. Після цього повторний Maven verify також завершився успішно: 51 тест.

## Успішний повний REST-пошук

Фінальний запит POST /api/search, query=java, mode=llm, модель llama3.2:1b. Конфігурація: connect=5s, health=3s, generation=60s, max expanded terms=8, num_predict=48, num_ctx=2048.

```json
{
  "path": "/api/search",
  "request": {
    "query": "java",
    "mode": "llm"
  },
  "http": 200,
  "response": {
    "status": "ok",
    "query": "java",
    "mode": "llm",
    "terms": [
      "java",
      "programming",
      "developer",
      "tutorials",
      "online",
      "courses",
      "coding",
      "development",
      "language"
    ],
    "total": 1,
    "results": [
      {
        "document_id": "03-java.txt",
        "score": 1,
        "snippet": "Java supports threads and sequential processing. Java applications can search documents.\n"
      }
    ],
    "stages": [
      "query_expansion",
      "keyword_search",
      "done"
    ],
    "fallback": false,
    "warning": null
  },
  "wall_seconds": 21.775386208901182
}
```

Отримано справжні розширені terms, fallback=false, warning=null, документ 03-java.txt через власний індекс. Усі базові HTTP smoke-перевірки також пройшли; тестовий backend зупинено. Сирий результат: benchmarks/ollama/backend-success-results.json. Глобальні налаштування Docker не змінено.
