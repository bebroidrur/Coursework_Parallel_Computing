# Великі корпуси для перевірки датасету

Генератор [generate_corpus.py](../benchmarks/generate_corpus.py) є opt-in сценарієм. Він не запускається під час Maven, Docker або старту backend і не створює файлів без явного виклику. Один документ — один UTF-8 файл `doc-*.txt`; тексти англомовні, тематичні та детерміновано повторювані. Суфікси `M` і `G` означають двійкові одиниці: MiB і GiB.

Перед генерацією спочатку перевірте план без запису на диск:

```sh
python3 benchmarks/generate_corpus.py --output /tmp/coursework-corpus-100m \
  --documents 10000 --size 100M --dry-run
```

Підготовлені команди для окремих корпусів:

```sh
python3 benchmarks/generate_corpus.py --output datasets/generated-100m \
  --documents 10000 --size 100M
python3 benchmarks/generate_corpus.py --output datasets/generated-500m \
  --documents 10000 --size 500M
python3 benchmarks/generate_corpus.py --output datasets/generated-1g \
  --documents 10000 --size 1G
python3 benchmarks/generate_corpus.py --output datasets/generated-2g \
  --documents 10000 --size 2G
```

Ці команди є opt-in. У межах експерименту 500M було згенеровано окремо;
результати індексації збережені в
`benchmarks/coursework-results/report.md`. Корпус 2G не запускався.

## Перевірка корпусу

```sh
find datasets/generated-2g -type f -name 'doc-*.txt' | wc -l
du -sh datasets/generated-2g
python3 - <<'PY'
from pathlib import Path
root = Path("datasets/generated-2g")
files = sorted(root.glob("doc-*.txt"))
print("documents:", len(files))
print("bytes:", sum(path.stat().st_size for path in files))
PY
```

Очікується 10 000 документів і не менше `2 * 1024**3` байтів тексту. `generation-manifest.txt` містить параметри генерації, але не є документом і не враховується loader-ом.

## Перевірка індексації

Production-індексація використовує `DatasetLoader.loadRefs`: у пам’яті зберігаються ідентифікатори та шляхи, а `IndexBuilder.buildParallelRefs` читає один UTF-8 файл у worker за раз. `SearchService` зберігає каталог посилань, а snippet читається потоково лише для знайденого документа. Старий `DatasetLoader.load` і `IndexBuilder.build(List<Document>)` залишені для сумісності малих корпусів і тестів. Сам `InvertedIndex` залишається in-memory, тому 2-GiB корпус не можна вважати підтриманим до фактичного запуску з вимірюванням heap.

Для контрольованої спроби використовуйте окремий процес і heap, погоджений із доступною RAM:

```sh
DATASET_PATH="$PWD/datasets/generated-2g" \
  java -Xmx8g -jar backend/target/backend-0.0.1-SNAPSHOT.jar \
  --search.dataset-path="$PWD/datasets/generated-2g"
curl -s -X POST http://localhost:8080/api/index \
  -H 'Content-Type: application/json' -d '{"threads":8}'
curl -s http://localhost:8080/api/stats
```

Фактичним доказом є `documents=10000` у відповіді, `index_build_time_ms`, відсутність `OutOfMemoryError`, peak heap/RSS процесу та збережені логи. Цю команду не запускали під час підготовки документа.

### Поступова перевірка з контролем пам'яті

Спочатку зберіть backend і перевірте кожен корпус окремим запуском. Не
переходьте до наступного етапу, доки попередній не завершився без
`OutOfMemoryError`:

```sh
MAVEN_USER_HOME="$PWD/.maven-cache" backend/mvnw -o \
  -Dmaven.repo.local="$PWD/.maven-cache/repository" \
  -f backend/pom.xml package -DskipTests -B -ntp
```

У першому терміналі для 100 МБ, а потім для 500 МБ запустіть backend:

```sh
DATASET_PATH="$PWD/datasets/generated-100m" \
  java -Xmx4g -jar backend/target/backend-0.0.1-SNAPSHOT.jar \
  --server.port=18080 --search.dataset-path="$PWD/datasets/generated-100m"
```

У другому терміналі виконайте індексацію та перевірте статистику (для 500 МБ
замініть `generated-100m` на `generated-500m`):

```sh
curl --fail -s -X POST http://localhost:18080/api/index \
  -H 'Content-Type: application/json' -d '{"threads":4}'
curl --fail -s http://localhost:18080/api/stats
```

Для 2 ГіБ використовуйте окремий запуск із heap, який не перевищує доступну
RAM, і контролюйте процес засобами ОС:

```sh
DATASET_PATH="$PWD/datasets/generated-2g" \
  java -Xmx8g -jar backend/target/backend-0.0.1-SNAPSHOT.jar \
  --server.port=18080 --search.dataset-path="$PWD/datasets/generated-2g"
```

Після завершення індексації зупиніть процес і зафіксуйте його peak RSS (на
macOS `ps -o rss= -p <PID>`, на Linux `/usr/bin/time -v` або `jcmd <PID>
GC.heap_info`). У другому терміналі виконайте:

```sh
curl --fail -s -X POST http://localhost:18080/api/index \
  -H 'Content-Type: application/json' -d '{"threads":8}'
curl --fail -s http://localhost:18080/api/stats
```

На Linux для peak RSS використайте `/usr/bin/time -v` або `jcmd` і збережіть
`Maximum resident set size`. Для кожного етапу зафіксуйте кількість файлів,
сумарні байти, `documents`, `unique_terms`, `index_build_time_ms`, peak RSS,
heap та результат пошуку. 100 MiB контрольний запуск і 500 MiB експеримент
виконані; 2 GiB у межах поточного етапу не запускався.

### Протокол 2 ГіБ для 1/2/4/8 потоків

Після успішної перевірки малого корпусу запустіть backend із
`datasets/generated-2g`, відкрийте Web UI і послідовно натисніть індексацію
для `threads=1`, `2`, `4` та `8`. Перед кожним вимірюванням дочекайтеся
завершення попереднього запиту. Для кожного запуску збережіть JSON-відповідь
`POST /api/index` і зовнішнє вимірювання пам’яті. Рекомендований CSV має
заголовок:

```text
threads,documents,unique_terms,index_build_time_ms,memory_usage,status
```

`status=ok` дозволено записувати лише для HTTP 200 із `documents=10000` і без
`OutOfMemoryError`; невдалий запуск записуйте як `status=error` із повідомленням
у журналі. Після отримання всіх чотирьох фактичних рядків обчисліть
`speedup = time_1 / time_N` та `efficiency = speedup / N`. Графіки будуйте
лише з цього CSV, наприклад окремим Python/Matplotlib-скриптом; до запуску
експерименту таблиця і графіки не заповнюються теоретичними значеннями.

## Обмеження та мінімальний напрям оптимізації

Потокове читання усуває збереження всіх 2 ГіБ тексту і snippets у RAM, але повний індекс і тимчасові токени все одно залишаються в пам’яті. Для corpus із великим словником posting lists можуть перевищити heap. Збільшення `-Xmx` може бути лише експериментальним тимчасовим кроком.

Мінімальна архітектурна оптимізація для надійної підтримки великих корпусів — потокове/пакетне читання, зберігання в `Document` шляху та метаданих замість повного content, а також читання короткого snippet за запитом. Якщо posting lists також перевищують RAM, потрібні сегменти індексу на диску та їх злиття. Ці зміни в поточному етапі не реалізовувалися.
