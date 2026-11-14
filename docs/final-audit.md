# Фінальний аудит проєкту

Дата перевірки: **2026-11-14**. Аудит виконано для робочої копії Coursework_Parallel_Computing на macOS (Apple Silicon), JDK 24 із компіляцією `--release 21`.

## Git-відтворюваність

Поточна гілка — `main`; `HEAD` і `origin/main` вказують на `290d3cf`. Remote `origin` налаштований на `https://github.com/bebroidrur/Coursework_Parallel_Computing.git`. Робоче дерево має незакомічені зміни: backend, frontend, CLI, datasets, docs, benchmarks, Docker-файли та частина UML є untracked, а README і наявні UML змінені.

Тимчасовий клон саме з `HEAD` (без копіювання робочої директорії) не містив `backend/`, `frontend/`, `cli/`, `datasets/`, `docs/`, `benchmarks/`, `docker-compose.yml` або Dockerfile. У ньому `docker compose config` повернув `no configuration file provided: not found`. Отже, поточна робоча директорія функціональна, але GitHub-версія не відтворює її до додавання цих файлів у коміт.

`.gitignore` правильно виключає `target/`, `.maven-cache/`, `.env`, IDE-файли та build-каталоги. Перевірка не виявила файлів понад 10 MB поза цими виключеннями, а потрібні вихідні файли не ігноруються. Перед публікацією потрібно додати реалізаційні каталоги та `.dockerignore`, перевірити staged-список і лише потім створити коміт вручну.

## Обсяг і структура

Backend містить Spring Boot 4.1.1, власні класи індексу в `backend/src/main/java/com/coursework/backend/index`, завантаження UTF-8 документів, REST-контролери, OllamaService і WebSocket. `frontend/` — окремий статичний Web UI, `cli/` — Maven CLI-клієнт. `datasets/sample/` містить чотири UTF-8 демонстраційні документи. Початкові та актуалізовані PlantUML/PNG-діаграми збережені в `diagrams/archive` і `diagrams/`.

Docker-конфігурацію додано: `backend/Dockerfile`, `frontend/Dockerfile`, `frontend/nginx.conf` і кореневий `docker-compose.yml`. Ollama працює окремим сервісом, модель завантажується init-сервісом `ollama-model`, а named volume зберігає її між перезапусками.

## Перевірка індексу і паралельності

`InvertedIndex` зберігає `Bucket[]`; кожен bucket використовує `ArrayList<TermEntry>` із separate chaining. `TermEntry` має власний `PostingList`, а posting list — відсортований `ArrayList<String>` з бінарним пошуком і видаленням дублікатів. У production-коді немає `HashMap`/`ConcurrentHashMap` як сховища індексу. Колізії перевірені тестом із одним bucket.

`Bucket.insert` і `Bucket.find` синхронізовані на конкретному bucket, тому записи в різні bucket-и не блокують один одного. `PostingList` синхронізує додавання, копіювання та розмір. Пошук повертає незалежну незмінну копію `TreeSet`, тому клієнт не бачить змін внутрішнього списку.

`IndexBuilder` розподіляє документи між N задачами за схемою `offset + i * N`. Для виконання використовується власний `CustomThreadPool` з `LinkedBlockingQueue`, фіксованими daemon worker-потоками та `ExecutorCompletionService`; стандартний пул не використовується в main-коді. `shutdown`, `shutdownNow`, `awaitTermination`, переривання та відхилення задач реалізовані в самому пулі.

Побудова відбувається у новому індексі. Поле `volatile BuildSnapshot` публікується лише після завершення всіх worker-задач; помилка, переривання або дубльований ID залишають попередній snapshot. `SearchService` захоплює один immutable state на операцію пошуку, тому індекс, snippets і статистика належать одній версії.

Перевірка `rg` підтвердила, що використання `Executors` і `ConcurrentHashMap` лишилося тільки в тестовому harness і WebSocket-координаторі сесій; основне сховище індексу та пул індексації їх не використовують.

## Тести

Фактично виконано:

| Компонент | Команда | Результат |
|---|---|---|
| Backend | `MAVEN_USER_HOME="$PWD/.maven-cache" backend/mvnw -o -Dmaven.repo.local="$PWD/.maven-cache/repository" -Djava.io.tmpdir="$PWD/backend/target" -f backend/pom.xml verify -B -ntp` | 64 тести, 0 failures/errors/skipped, BUILD SUCCESS |
| CLI | Maven verify у `cli/` | 4 тести, успішно |
| Frontend | `npm test --prefix frontend` | 3 тести, успішно |
| Frontend syntax | `node --check frontend/src/app.js` | успішно |
| Розширений benchmark | `-Dtest=ExtendedPerformanceScenario` | 1 сценарій, 0 failures/errors; кожний вимір перевірив еквівалентність індексів |

Backend-тести охоплюють REST, WebSocket, Ollama mock, DatasetPathResolver, послідовну/паралельну індексацію, колізії, конкурентних читачів/писачів, повторну побудову, помилки, переривання та CustomThreadPool. Для JDK 24 у `backend/pom.xml` додано test-only `byte-buddy-agent` і Surefire `-javaagent`: це усуває помилку self-attach Mockito у середовищах без дозволу dynamic attach. Реальний HTTP smoke із JAR також виконувався раніше: health, index, keyword search і stats повернули коректні відповіді для sample (4 документи, 38 термінів). Реальна Ollama залежить від локального процесу й не потрібна Maven-тестам.

## Benchmark

Сценарій `backend/src/test/java/com/coursework/backend/index/ExtendedPerformanceScenario.java` генерує однакову формулу корпусу для 500, 2 000, 5 000 і 10 000 документів (150 токенів, vocabulary 1000), виконує 2 прогрівання та 5 вимірювань для sequential і CustomThreadPool 1/2/4/8, а також записує кожний результат. Raw CSV: [extended-index-times.csv](../benchmarks/results/2026-11-14/extended-index-times.csv). Підсумок: [extended-index-summary.csv](../benchmarks/results/2026-11-14/extended-index-summary.csv). Графік: [extended-performance.svg](../benchmarks/results/2026-11-14/extended-performance.svg).

Середній час (ms; speedup та efficiency обчислені щодо sequential):

| Документи | Sequential | Pool 1 | Pool 2 | Pool 4 | Pool 8 |
|---:|---:|---:|---:|---:|---:|
| 500 | 61.072 | 41.918 / 1.457× | 21.269 / 2.871× | 37.598 / 1.624× | 11.248 / 5.429× |
| 2 000 | 122.958 | 87.096 / 1.412× | 46.247 / 2.659× | 31.132 / 3.950× | 27.484 / 4.474× |
| 5 000 | 268.567 | 231.745 / 1.159× | 130.162 / 2.063× | 74.088 / 3.625× | 62.863 / 4.272× |
| 10 000 | 501.394 | 493.794 / 1.015× | 283.961 / 1.766× | 149.715 / 3.349× | 123.566 / 4.058× |

Значення залежать від навантаження системи; efficiency для 8 потоків падає через накладні витрати й конкуренцію за ресурси. Це фактичний один запуск, а не гарантія для іншого обладнання.

## Документація і регресії

README містить команди запуску backend/CLI/frontend, REST і WebSocket-контракти, Ollama, тести та посилання на benchmark. `docs/parallel-index.md` описує CustomThreadPool, синхронізацію і snapshot. UML class diagram містить `CustomThreadPool`; початкові версії не видалені. CORS, шлях `datasets/sample`, REST API, WebSocket і frontend-конфігурація перевірені попередніми smoke/unit сценаріями.

## Docker-перевірка

`docker compose config` завершився успішно. `docker compose up --build` також завершився успішно після вибору вільних host-портів (`BACKEND_PORT=18080`, `FRONTEND_PORT=14173`, `OLLAMA_PORT=11435`), оскільки 8080 і 11434 були зайняті локальними процесами. Усі три довгоживучі контейнери стали `healthy`; `ollama-model` завершився успішно після `ollama pull llama3.2:1b`. Перевірено:

- `GET /api/health` напряму до backend і через frontend nginx — `{"status":"ok","ollama":true}`;
- `GET /api/tags` — модель `llama3.2:1b` присутня;
- `POST /api/index` з `threads=4` — 4 документи, 38 термінів;
- keyword search — результат із `01-parallel.txt`;
- WebSocket keyword через frontend reverse proxy — `started → keyword_search → done`;
- frontend HTML і nginx healthcheck.

Коротка реальна Ollama generation на CPU не відповіла за 60 секунд. Backend коректно застосував fallback у LLM-пошуку: `fallback: true`, `warning: OLLAMA_TIMEOUT`, HTTP-запит завершився за 34 секунди без аварії.

## Залишок перед здачею

Контейнеризація тепер реалізована і перевірена. Перед здачею бажано повторити smoke на чистому клоні та додати до записки скриншоти Web UI/CLI і фактичний статус Ollama на демонстраційній машині. На CPU LLM generation може перевищувати 30-секундний timeout, тому для демонстрації слід використовувати fallback або налаштувати ресурси/timeout через змінні середовища.
