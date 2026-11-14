# Parallel Search System

Курсова робота: «Дослідження та реалізація паралельних обчислень на прикладі побудови пошукової системи з інтеграцією LLM».

## Поточний стан

Реалізовано Spring Boot каркас, `GET /api/health` із реальною перевіркою Ollama, UTF-8 завантажувач документів, Unicode-токенізацію, власний інвертований індекс на масиві buckets і списках та послідовну й паралельну побудову індексу. Пошук у Java API виконується за одним нормалізованим терміном.

REST індексації, keyword-пошуку та статистики реалізовано. Ollama query expansion, WebSocket-пошук, CLI-клієнт, Web UI та Docker Compose реалізовано.

## Конфігурація і тести

Цільова Java — 21; Spring Boot — 4.1.1; Maven Wrapper — 3.10.0. Встановлений Maven не потрібний. Перший запуск потребує мережі для завантаження Maven та залежностей.

З кореня репозиторію (macOS/Linux):

```sh
mkdir -p backend/target
MAVEN_USER_HOME="$PWD/.maven-cache" TMPDIR="$PWD/backend/target" ./backend/mvnw -Dmaven.repo.local="$PWD/.maven-cache/repository" -Djava.io.tmpdir="$PWD/backend/target" -f backend/pom.xml test -B -ntp
```

Ця команда зберігає Maven, залежності та тимчасові файли тестів усередині проєкту. `.maven-cache/` і `target/` виключені з Git.

## Запуск усієї системи через Docker Compose

Потрібні Docker Engine і Docker Compose v2. З кореня свіжого клону виконайте:

```sh
docker compose up --build
```

Compose запускає окремі сервіси `backend`, `frontend`, `ollama` та одноразовий `ollama-model`. Перший запуск завантажує модель `llama3.2:1b`; моделі зберігаються у named volume `coursework_parallel_computing_ollama_models`, тому наступні перезапуски не завантажують її повторно. Backend використовує адресу `http://ollama:11434` всередині compose-мережі, а nginx у frontend проксує `/api/*` і WebSocket `/ws/search` до сервісу `backend`.

Після старту відкрийте [http://localhost:4173](http://localhost:4173). Перевірки:

```sh
curl http://localhost:8080/api/health
curl http://localhost:11434/api/tags
curl -X POST http://localhost:8080/api/index \
  -H 'Content-Type: application/json' -d '{"threads":4}'
```

Порти можна змінити, якщо вони зайняті: `BACKEND_PORT=18080 FRONTEND_PORT=14173 OLLAMA_PORT=11435 docker compose up --build`. Зупинка без видалення volume: `docker compose down`; для повторного завантаження моделі використовуйте `docker compose down -v`.

## Використання індексу з Java

```java
var documents = new DatasetLoader().load(Path.of("datasets/sample"));
var builder = new IndexBuilder();
builder.build(documents);
var ids = builder.getIndex().search("пошук");
```

Імпорти: `com.coursework.backend.document.DatasetLoader`, `com.coursework.backend.index.IndexBuilder`, `java.nio.file.Path`. Шлях у прикладі відносний до кореня репозиторію; з каталогу backend використовуйте `../datasets/sample`.

`build` замінює попередній індекс після успішного завершення. ID документів мають бути унікальними. Порожні документи враховуються. `getLastBuildTime()` повертає наносекунди, включаючи створення індексу й токенізацію, але без читання датасету. Це технічна метрика, а не завершений benchmark.

Формат датасету: [datasets/README.md](datasets/README.md). Алгоритми та уточнення UML: [docs/sequential-index.md](docs/sequential-index.md).

## Паралельна індексація

```java
builder.buildParallel(documents, 4);
var snapshot = builder.getSnapshot();
double milliseconds = snapshot.elapsedMillis();
```

`build(documents)` залишається послідовною базою; `buildParallel(documents, N)` використовує власний `CustomThreadPool` навіть для N=1. Деталі потокобезпечності, тести та вимірювання: [docs/parallel-index.md](docs/parallel-index.md). Повна збірка: у команді тестування вище замініть `test` на `verify`.

`CustomThreadPool` має власну потокобезпечну чергу, фіксовані daemon workers, `execute/submit`, `shutdown`, `shutdownNow` та `awaitTermination`; він не делегує виконання іншому пулу. Документи розподіляються задачами за позиціями `offset + i * N`, bucket-и синхронізуються окремо, а `volatile BuildSnapshot` публікується лише після успішного завершення всіх workers. Помилка або переривання залишає попередній snapshot.

Benchmark `2026-11-14` (2000 документів, 150 токенів, 2 warmups, 5 повторів, Java 24, 8 processors) збережено в [benchmarks/results/2026-11-14/index-times-custom-pool.csv](benchmarks/results/2026-11-14/index-times-custom-pool.csv): sequential — 117.156 ms; pool 1/2/4/8 — 105.171/51.663/33.093/64.748 ms; speedup — 1.114×/2.268×/3.540×/1.809×; efficiency — 111.4%/113.4%/88.5%/22.6%. Це один локальний запуск, не універсальна гарантія прискорення.

Розширений сценарій для 500, 2000, 5000 і 10000 документів (2 прогрівання, 5 вимірювань для кожної конфігурації 1/2/4/8) запускається окремо:

```sh
MAVEN_USER_HOME="$PWD/.maven-cache" backend/mvnw -o \
  -Dmaven.repo.local="$PWD/.maven-cache/repository" \
  -Djava.io.tmpdir="$PWD/backend/target" \
  -Dtest=ExtendedPerformanceScenario -f backend/pom.xml test -B -ntp
```

Raw вимірювання, summary (mean, median, speedup, efficiency) та SVG-графік збережені в [benchmarks/results/2026-11-14](benchmarks/results/2026-11-14). Сценарій перевіряє еквівалентність послідовного і паралельного індексів на кожному вимірюванні. Для повторного створення SVG використовуйте `python3 benchmarks/plot_extended_benchmark.py`.

### Throughput keyword search

HTTP-сценарій [benchmarks/search_throughput.py](benchmarks/search_throughput.py) вимірює саме `POST /api/search` у режимі `keyword` на одному незмінному попередньо побудованому індексі. Він використовує п'ять однакових запитів, 100 запитів на клієнта, 100 warmup-запитів і 5 незалежних повторів для 1/2/4/8/16 одночасних клієнтів. Throughput обчислюється як загальна кількість запитів, поділена на wall-clock тривалість concurrent round; кожна відповідь додатково перевіряється за очікуваним набором document ID.

Запуск із готовим JAR і автоматичним стартом backend:

```sh
python3 benchmarks/search_throughput.py --start-server
```

Raw-запити, repeat summary, агрегований summary та графік збережені в [benchmarks/results/2026-11-14/search-throughput](benchmarks/results/2026-11-14/search-throughput). Фактичний запуск на Java 24 / macOS / sample із 4 документів дав:

| Клієнти | Throughput, req/s | Mean, ms | Median, ms | P95, ms | P99, ms | Успішність / коректність |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 627.83 | 1.592 | 1.569 | 1.981 | 2.509 | 100% / 100% |
| 2 | 1083.65 | 1.929 | 1.835 | 2.909 | 3.712 | 100% / 100% |
| 4 | 1837.56 | 2.114 | 1.973 | 3.210 | 5.121 | 100% / 100% |
| 8 | 2261.22 | 3.237 | 2.977 | 5.434 | 8.537 | 100% / 100% |
| 16 | 2359.27 | 6.270 | 5.752 | 11.672 | 17.460 | 100% / 100% |

Ці значення характеризують локальний HTTP/CPU запуск на малому sample, а не граничну продуктивність великого корпусу.

## REST API (реалізовано)

Запуск з **кореня репозиторію** після `verify`:

```sh
java -Djava.io.tmpdir="$PWD/backend/target" -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

Порт типово 8080. Датасет задається `DATASET_PATH` або `--search.dataset-path=/absolute/path`; типово backend знаходить `datasets/sample` як відносно поточного каталогу, так і під час запуску з `backend/` (пошук виконується вгору до кореня репозиторію). Для іншого розташування задайте абсолютний `DATASET_PATH`. Індексація запускається явно через POST, не при старті сервера. Клієнт не передає довільний шлях до файлів.

Усі відповіді — JSON із `status`: `ok` або `error`. Для POST потрібен `Content-Type: application/json`. Нижче час і кількість термінів у прикладах статистики ілюстративні: залежать від датасету й запуску.

Окремий frontend на `http://localhost:4173` за замовчуванням звертається до `http://localhost:8080`. Іншу адресу можна задати через `frontend/config.js` (`window.APP_CONFIG.backendUrl`) або query-параметр `?backend=http://host:port`; query-параметр має пріоритет. Backend дозволяє локальні origins `http://localhost:4173` і `http://127.0.0.1:4173`; список можна змінити через `FRONTEND_ALLOWED_ORIGINS`.

### GET /api/health

```sh
curl -sS http://localhost:8080/api/health
```

HTTP 200:

```json
{"status":"ok","ollama":false}
```

`status`: string; `ollama`: boolean. Сумісний із попереднім HealthController. Виконується GET /api/tags з таймаутом: true означає доступний сервіс із коректним JSON models; false — недоступність, таймаут або некоректна відповідь. Це не гарантія наявності налаштованої моделі.

### POST /api/index

Синхронно читає всі UTF-8 `.txt` з налаштованого датасету та викликає `IndexBuilder.buildParallel`. Обов’язкове поле `threads`: JSON integer, 1–64; дробові числа, числові рядки та null не допускаються. Межа 64 — обмеження REST API; Java API builder збережено.

```sh
curl -sS -X POST http://localhost:8080/api/index \
  -H 'Content-Type: application/json' -d '{"threads":4}'
```

HTTP 200 (приклад):

```json
{"status":"ok","documents":4,"unique_terms":40,"index_build_time_ms":3.25,"threads_used":4}
```

`documents`, `unique_terms`, `threads_used`: integer; `index_build_time_ms`: number (мілісекунди). Час вимірюється наявним builder через System.nanoTime(), без читання файлів. `threads_used` — заданий розмір пулу; при меншій кількості документів активних workers буде менше. Порожній датасет успішно публікує порожній індекс. Повторні запити індексації виконуються по черзі; відповідь очікує завершення.

### POST /api/search

Обов’язкові поля: `query` — string довжиною 1–4096 символів Java (UTF-16), не порожній і з хоча б одним токеном; `mode` — string: `keyword` або `llm`.

```sh
curl -sS -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' -d '{"query":"JAVA java","mode":"keyword"}'
```

HTTP 200 для демонстраційного датасету:

```json
{"status":"ok","query":"JAVA java","mode":"keyword","terms":["java"],"total":1,"results":[{"document_id":"03-java.txt","score":1,"snippet":"Java supports threads and sequential processing. Java applications can search documents.\n"}],"stages":["keyword_search","done"],"fallback":false,"warning":null}
```

`query`, `mode`: string; `terms`: array of string; `total`: integer; `results`: array of objects. Кожний результат: `document_id` string (відносний шлях), `score` integer (кількість різних термінів запиту, знайдених у документі), `snippet` string (перші максимум 200 Unicode code points тексту).

Використовується та сама токенізація, що при індексації. Семантика OR: достатньо збігу одного терміна. Повтори запиту не підвищують score. Сортування: score за спаданням, потім document_id за зростанням. Стемінгу й пагінації поки немає. LLM-режим описано нижче. Порожній індекс або відсутність збігів — HTTP 200, `total: 0`, `results: []`.

### GET /api/stats

```sh
curl -sS http://localhost:8080/api/stats
```

HTTP 200, ті самі поля, що у відповіді індексації. До першої індексації:

```json
{"status":"ok","documents":0,"unique_terms":0,"index_build_time_ms":0.0,"threads_used":1}
```

Початкове `threads_used: 1` успадковане від початкового snapshot builder; це не означає, що індексація вже відбулася.

### Помилки

Єдина форма:

```json
{"status":"error","code":"INVALID_PARAMETER","message":"threads має бути цілим числом від 1 до 64"}
```

Усі три поля — string.

| HTTP | code | Причина |
|---|---|---|
| 400 | INVALID_PARAMETER | Пропущені/некоректні threads, query, mode |
| 400 | HTTP_400 | Пошкоджений JSON, порожнє тіло, неправильний тип поля |
| 404 | HTTP_404 | Невідомий маршрут |
| 405 | HTTP_405 | Непідтримуваний HTTP-метод |
| 415 | HTTP_415 | Непідтримуваний Content-Type |
| 500 | DATASET_READ_FAILED | Відсутній/недоступний каталог, помилка читання або UTF-8 |
| 503 | INDEX_BUILD_FAILED | Помилка worker або переривання побудови |
| 500 | INTERNAL_ERROR | Непередбачена внутрішня помилка |

```sh
curl -i -X POST http://localhost:8080/api/index \
  -H 'Content-Type: application/json' -d '{"threads":0}'
```

### Узгодженість і тести API

SearchService тримає один volatile State: завершений BuildSnapshot, незмінний список документів і кількість термінів. Читач захоплює State один раз; тому snippets та postings належать до однієї версії. Невдала індексація не замінює State. Додаткових блокувань навколо всього пошуку немає. Індекс не видається назовні через REST і сервіс його після публікації не модифікує.

RestApiTests використовує Spring MVC/JSON-конфігурацію застосунку, тимчасові UTF-8 файли та справжній SearchService/IndexBuilder. Перевіряються маршрути, валідація, порожній індекс, повторна побудова, збереження старого стану після помилки читання та конкурентні пошук/індексація.

Перевірка REST-етапу: `Maven verify` — **37 тестів, 0 failures/errors/skipped, BUILD SUCCESS**, JDK 24 / release 21. Зібраний JAR перевірено реальними HTTP-запитами: health, початкова статистика, порожній пошук, індексація, keyword-пошук, статистика, threads=0 і повторна індексація. Sample: 4 документи, 38 унікальних термінів.

Для повторення HTTP smoke-перевірки після verify, з кореня репозиторію (порт 18080 має бути вільний; використовується типовий sample):

```sh
python3 backend/src/test/scripts/http_smoke.py
```

Скрипт запускає і зупиняє власний процес сервера. Результати: `backend/target/http-smoke-results.json`, журнал: `backend/target/http-smoke-server.log`. У середовищах із забороною локальних портів або Java-agent attach для Mockito потрібен дозвіл на відповідні операції.

## Ollama та LLM-пошук

Використовується вже завантажена модель **llama3.2:1b**. Backend не завантажує модель і не запускає Ollama самостійно. Перевірка локальної інсталяції:

```sh
curl --max-time 3 http://localhost:11434/api/tags
# Якщо Ollama ще не запущена:
ollama serve
```

### Конфігурація

| Властивість | Змінна середовища | Типове значення |
|---|---|---|
| ollama.base-url | OLLAMA_BASE_URL | http://localhost:11434 |
| ollama.model | OLLAMA_MODEL | llama3.2:1b |
| ollama.connect-timeout | OLLAMA_CONNECT_TIMEOUT | 2s |
| ollama.generation-timeout | OLLAMA_GENERATION_TIMEOUT | 30s |
| ollama.health-timeout | OLLAMA_HEALTH_TIMEOUT | 1s |
| ollama.max-expanded-terms | OLLAMA_MAX_EXPANDED_TERMS | 8 |

Таймаути — додатні Duration, наприклад `500ms`, `2s`, `30s`. max-expanded-terms: 1–32, ліміт **додаткових** токенів; початкові терміни ніколи не обрізаються. Адреса — HTTP(S) base URL; для майбутнього контейнерного запуску можна задати `http://ollama:11434`, без змін коду. Generation/health timeout обмежує очікування всієї HTTP-відповіді; незавершений future скасовується. Повторних спроб немає.

```sh
OLLAMA_BASE_URL=http://localhost:11434 OLLAMA_MODEL=llama3.2:1b \
java -Djava.io.tmpdir="$PWD/backend/target" -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

OllamaService надсилає `POST /api/generate` із model, початковим запитом у prompt, інструкцією system, `stream:false`, `format:"json"`, `temperature:0`, `num_predict:48`, `num_ctx:2048`, `keep_alive:"5m"`. Очікується envelope із `done:true` і рядком response, що містить `{"terms":["слово"]}`. Протокол: [офіційна документація Ollama](https://github.com/ollama/ollama/blob/main/docs/api.md).

Відповідь перевіряється структурно: непорожній масив до 64 елементів, лише рядки до 128 символів, кожен має 1–4 токени. Відхиляються пошкоджений JSON, незавершена генерація, порожні/нетекстові елементи, надмірно довгі пояснення. Для прийнятих елементів застосовується наявний Tokenizer; дублікати й повтори оригіналу видаляються, додавання обмежуються конфігурацією. Семантична релевантність синонімів не гарантована: це результат LLM, а не перевірений словник.

### Режими та нові поля відповіді

`keyword` не викликає Ollama. `llm` виконує query_expansion → keyword_search → done, використовуючи той самий власний індекс, OR-пошук і ранжування. Усі попередні поля SearchResponse збережено; до **обох** режимів додано:

| Поле | Тип | Значення |
|---|---|---|
| stages | array of string | llm: query_expansion, keyword_search, done; keyword: keyword_search, done |
| fallback | boolean | true, якщо expansion не вдалося і використано лише оригінальні терміни |
| warning | string або null | код причини fallback; null при нормальному виконанні |

`mode` залишається запитаним режимом навіть при fallback. `terms` — фактичні токени пошуку. Етапи повертаються у фінальному JSON, не передаються в реальному часі.

```sh
curl -sS -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' -d '{"query":"parallel computing","mode":"llm"}'
curl -sS -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' -d '{"query":"parallel computing","mode":"keyword"}'
```

Приклад схеми відповіді (терміни та результати залежать від моделі й датасету):

```json
{"status":"ok","query":"parallel computing","mode":"llm","terms":["parallel","computing","threads"],"total":0,"results":[],"stages":["query_expansion","keyword_search","done"],"fallback":false,"warning":null}
```

### Відмови Ollama

Обрана поведінка: **HTTP 200 із keyword-результатами за оригінальними термінами**, `fallback:true`, warning з таблиці. Backend продовжує працювати, індекс не змінюється. Звичайні помилки REST-валідації, як і раніше, повертають HTTP 400.

| warning | Причина |
|---|---|
| OLLAMA_UNAVAILABLE | Помилка з’єднання/транспорту |
| OLLAMA_TIMEOUT | Перевищено час очікування |
| OLLAMA_HTTP_ERROR | Неуспішний HTTP-код, зокрема відсутня модель |
| OLLAMA_INVALID_RESPONSE | Некоректна структура/вміст відповіді |
| OLLAMA_INTERRUPTED | Потік, що очікує відповідь, перервано; interrupt flag збережено |

```json
{"status":"ok","query":"java","mode":"llm","terms":["java"],"total":0,"results":[],"stages":["query_expansion","keyword_search","done"],"fallback":true,"warning":"OLLAMA_TIMEOUT"}
```

### Перевірки

Maven verify не потребує локальної Ollama: OllamaServiceTests імітує HttpClient/HttpResponse та незавершені futures, REST-тести підміняють OllamaService. Перевіряються нормалізація, ліміти, збереження початкових термінів, некоректний JSON, HTTP-помилки, недоступність, таймаути, переривання, health, llm-пошук, fallback і відсутність викликів Ollama у keyword-режимі.

Реальна перевірка окремо, після verify й запуску Ollama:

```sh
python3 backend/src/test/scripts/http_smoke.py --llm
```

Скрипт записує справжній результат, включно з fallback/warning, у backend/target/http-smoke-results.json. Fallback означає, що HTTP-пошук працює, але не є доказом успішної генерації.

Фактична перевірка Ollama-етапу: **50 тестів, 0 failures/errors/skipped, Maven verify BUILD SUCCESS**. Локальна `/api/tags` відповіла й підтвердила `llama3.2:1b`; збережений backend health насправді мав `ollama:false` (попереднє текстове твердження про true було неточним). Реальний LLM-запит `parallel computing` двічі перевищив налаштований час (30s і 60s): backend повернув HTTP 200, початкові terms, `fallback:true`, `warning:"OLLAMA_TIMEOUT"`. Отже, транспорт/health і резервний пошук перевірено реально, але успішну генерацію цієї локальної моделі в цих запусках **не підтверджено**. Тестові backend-процеси зупинені; Ollama не зупинялася. Для повільної моделі таймаут можна збільшити; це не усуває потреби перевірити її власну швидкодію.


### Діагностика та оптимізація повільної Ollama

Модель не змінено: llama3.2:1b. Генерація тепер просить 3–5 ключових слів короткою інструкцією, використовує `num_predict:48` замість 160, `num_ctx:2048`, `temperature:0`, `stream:false`, `format:"json"`, `keep_alive:"5m"`. keep_alive утримує **модель**, а не HTTP-з’єднання; 5m відповідає типовій поведінці Ollama й саме по собі не є доведеним прискоренням. Ліміт токенів може обірвати JSON — у такому разі залишається безпечний fallback.

HTTP-клієнт очікує повне тіло одного JSON і завершує future одразу після отримання. Немає sleep, повторних спроб або очікування закриття keep-alive TCP після завершеного HTTP body. generation-timeout залишається 30s; health-timeout 1s. Не слід компенсувати завислий Docker нескінченним очікуванням.

Після відновлення Docker можна окремо прогріти модель, не змінюючи таймаут пошуку:

```sh
curl --max-time 90 http://localhost:11434/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"model":"llama3.2:1b","prompt":"","stream":false,"keep_alive":"5m","options":{"num_ctx":2048}}'
```

Для вимірювань з кореня проєкту: `python3 benchmarks/ollama/direct_probe.py`. Скрипт записує wall time, повний request і, якщо доступна відповідь, метрики Ollama `load_duration`, `eval_duration`, `total_duration` у JSON. Другий запит можна називати прогрітим лише якщо перший успішно завантажив модель.

Деталі запуску й фактичні обмеження: [docs/ollama-verification.md](docs/ollama-verification.md).


Остання діагностика (13–14 листопада 2026): після перезапуску лише контейнера Ollama та окремого прогріву **реальні генерації і REST LLM-пошук успішні**. Прогрів: 42.861s; прямі генерації: 41.594s / 39.120s. Java-контроль: 34.463s (із завантаженням), 8.181s (повторний запит). Це різні умови запуску, а не доказ сталого прискорення.

## WebSocket-пошук

Реалізовано `ws://localhost:8080/ws/search` для прогресу keyword та LLM-пошуку. JSON-контракт, події, fallback і приклади: [docs/websocket.md](docs/websocket.md). Потокова передача виконується в реальному часі; REST `SearchResponse` міститься у `done.data`.

Для перевіреного повільного Docker-середовища використано:

```sh
OLLAMA_CONNECT_TIMEOUT=5s OLLAMA_HEALTH_TIMEOUT=3s OLLAMA_GENERATION_TIMEOUT=60s \
java -Djava.io.tmpdir="$PWD/backend/target" -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

Фактичний POST /api/search для java завершився за **21.775s**, повернув 8 додаткових термінів, документ 03-java.txt, `fallback:false`, `warning:null`. Maven verify: **51 тест, 0 помилок/пропусків, BUILD SUCCESS**. Деталі й сирі дані наведено в docs/ollama-verification.md та benchmarks/ollama/. Попередні записи про невдалу генерацію вище — історія першого етапу, а не поточний підсумок.

## CLI-клієнт

Окремий клієнт розташований у `cli/` і працює поверх тих самих REST та WebSocket endpoint backend. Він не містить логіки індексації або пошуку. Потрібні Java 21+ і Maven.

Збірка та запуск fat JAR:

```bash
MAVEN_USER_HOME="$PWD/.maven-cache" backend/mvnw \
  -Dmaven.repo.local="$PWD/.maven-cache/repository" -f cli/pom.xml package
java -jar cli/target/parallel-search-cli-0.0.1-SNAPSHOT.jar help
```

Типова адреса backend — `http://localhost:8080`. Її можна змінити глобальним параметром `--backend`, що також сумісно з майбутнім Docker Compose:

```bash
java -jar cli/target/parallel-search-cli-0.0.1-SNAPSHOT.jar \
  --backend http://localhost:8080 health
java -jar cli/target/parallel-search-cli-0.0.1-SNAPSHOT.jar index --threads 4
java -jar cli/target/parallel-search-cli-0.0.1-SNAPSHOT.jar stats
java -jar cli/target/parallel-search-cli-0.0.1-SNAPSHOT.jar \
  search --query "parallel computing" --mode keyword
java -jar cli/target/parallel-search-cli-0.0.1-SNAPSHOT.jar \
  search --query "parallel computing" --mode llm --live
```

Команди `health`, `index`, `stats` і `search` виводять JSON-відповідь backend у читабельному форматі. `index` приймає `--threads` від 1 до 64 і показує поля `index_build_time_ms`, `documents`, `unique_terms`, `threads_used`. `search` підтримує `keyword` і `llm`; `--live` підключається до `/ws/search` і друкує етапи `started`, `query_expansion`, `keyword_search`, `done` або `error`. Без `--live` використовується POST `/api/search`.

Некоректні параметри, HTTP-помилки, таймаути та недоступний backend перетворюються на зрозуміле повідомлення й код завершення, без stack trace або аварійного завершення JVM. `llm` зберігає backend fallback на keyword-пошук, тому CLI показує `fallback` і `warning` у відповіді/подіях.

CLI-тести запускаються окремо: `backend/mvnw -o -Dmaven.repo.local="$PWD/.maven-cache/repository" -f cli/pom.xml verify`. Вони використовують підмінний gateway з імітованими HTTP і WebSocket JSON-відповідями й не потребують Ollama.
