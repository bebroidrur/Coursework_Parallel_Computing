# Паралельна індексація

## Пул і задачі

IndexBuilder.build(documents) — послідовна база. buildParallel(documents, N) приймає будь-яке додатне N та створює власний `CustomThreadPool`. Для порожнього корпусу пул не потрібний. Виконується min(N, кількість документів) задач: задача i обробляє документи i, i+tasks, i+2*tasks. Це обмежує кількість Future і пам’ять черги, але нерівномірні за розміром документи можуть створювати дисбаланс. N=1 також використовує пул.

CustomThreadPool володіє worker-потоками та blocking queue самостійно. `shutdown()` завершує вже подані задачі й будить workers poison-сигналами; `shutdownNow()` очищає чергу та перериває workers. `IndexBuilder` очікує кожну completion, перетворює помилки в `IndexBuildException` і публікує новий `BuildSnapshot` лише після успішної зупинки пулу.

CompletionService повертає завершені задачі незалежно від порядку подання. ExecutionException перетворюється на IndexBuildException зі збереженою причиною. Переривання caller відновлює interrupt flag. На виході shutdownNow перериває залишкові задачі, а awaitTermination очікує завершення worker-потоків. Перевірки interrupt виконуються перед документом та кожним терміном. Токенізація одного документа не переривається посеред regex-обробки; скасування дочекається її завершення.

## Синхронізація

Bucket.insert і Bucket.find синхронізовані на конкретному bucket. Різні buckets не використовують спільного блокування. PostingList додатково синхронізує вставку, читання копії та розмір; TermEntry не видає внутрішню структуру назовні, а доступ до неї відбувається під bucket monitor.

Публічні registerDocument/addTerm захищають реєстр документів окремим монітором. Builder реєструє всі ID до запуску workers і використовує package-private addRegisteredTerm, тому worker не захоплює глобальний lock для кожного токена. Структура збережена: Bucket[], ArrayList<TermEntry>, ArrayList<String>. TreeSet використовується лише для копії результатів пошуку.

Методи побудови synchronized: одночасні перебудови одного builder виконуються по черзі. getIndex/getSnapshot не чекають завершення побудови. Volatile BuildSnapshot публікує індекс, час та N одним присвоєнням після успіху; помилки не змінюють попередній snapshot. Для узгодженого читання метаданих слід використовувати getSnapshot().

Snapshot містить посилання на змінюваний InvertedIndex: клієнти мають використовувати його для читання, якщо потрібна незмінність опублікованого результату. Публічний addTerm потокобезпечний, але операції count і search не є спільною транзакцією під час ручних записів.

## Час

getLastBuildTime() зберігає попередній контракт наносекунд. getLastBuildTimeMillis() і BuildSnapshot.elapsedMillis() повертають double мілісекунд через різницю System.nanoTime(). Вимір включає копіювання/валідацію входу, реєстрацію ID, створення індексу, токенізацію, pool startup/cleanup і очікування; читання файлів та очікування монітора builder не включені.

## Перевірки та відтворення

З кореня репозиторію:

```sh
MAVEN_USER_HOME="$PWD/.maven-cache" TMPDIR="$PWD/backend/target" ./backend/mvnw -Dmaven.repo.local="$PWD/.maven-cache/repository" -Djava.io.tmpdir="$PWD/backend/target" -f backend/pom.xml verify -B -ntp
MAVEN_USER_HOME="$PWD/.maven-cache" TMPDIR="$PWD/backend/target" ./backend/mvnw -Dmaven.repo.local="$PWD/.maven-cache/repository" -Djava.io.tmpdir="$PWD/backend/target" -f backend/pom.xml -Dtest=IndexPerformanceScenario test -B -ntp
```

ParallelIndexTests перевіряє всі терміни корпусу й кількість документів, включно з порожніми, для shuffled input і колізій. Окремо перевірено конкурентні записи/читання, дублікати, помилки, переривання та повторні побудови. Timeout — захист від зависання, а не вимога швидкодії.

IndexPerformanceScenario запускається лише явно: 2000 документів по 150 токенів, словник 1000 термінів, 1024 buckets, 2 прогріви і 5 вимірів на режим. Порядок режимів циклічно змінюється. Кожен результат звіряється з послідовним індексом поза вимірюваним інтервалом. CSV і середовище записуються в backend/target/benchmarks. Тест не має тверджень про прискорення. Один JVM і синтетичний корпус не замінюють дослідження різних корпусів із кількома JVM forks.

## UML

Актуальні class_diagram.puml і sequence_diagram.puml показують реалізацію. Початкові джерела та PNG архівовано; інші діаграми позначені як цільові сценарії. Див. diagrams/README.md.

## Фактичний запуск 2026-11-13

Maven verify: 28 тестів, 0 failures/errors/skipped, BUILD SUCCESS; створено Spring Boot JAR. Окремий IndexPerformanceScenario: 1 тест, 0 failures/errors/skipped. JDK 24, компіляція release 21, macOS 26.6.1 aarch64, 8 доступних процесорів, max heap 2 GiB.

| Режим | Медіана, мс | Мін–макс, мс |
|---|---:|---:|
| sequential, N=1 | 85.062 | 84.534–87.744 |
| pool, N=1 | 85.248 | 84.671–96.590 |
| pool, N=2 | 48.665 | 47.801–49.440 |
| pool, N=4 | 27.829 | 25.800–31.553 |
| pool, N=8 | 23.764 | 20.588–24.279 |

Сирі виміри та середовище: [benchmarks/results/2026-11-13](../benchmarks/results/2026-11-13). На цьому корпусі медіана для N=8 нижча за послідовну приблизно у 3.58 раза. Це результат одного локального запуску, не гарантія масштабування для інших даних і машин. Поточна послідовна база також використовує той самий синхронізований індекс.
