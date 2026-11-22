# UML

`class_diagram.puml` і `sequence_diagram.puml` відображають реалізацію індексу та Thread Pool. Поточний `IndexBuilder` використовує власний `CustomThreadPool`; N передається в `buildParallel(documents, N)`. Послідовний `build(documents)` збережено. Старі формулювання про `ExecutorService` у діаграмах і архіві є історичними.

Актуальні діаграми описують реалізовані REST, Ollama, WebSocket, CLI, статичний Web UI та Docker Compose. Архівні версії збережені в `diagrams/archive/`. Їхні `buildIndex(N)` — історичні/цільові позначення, а не поточні Java-сигнатури.

Усі початкові `.puml` і PNG збережено в `archive/initial/`. PNG у корені також залишено без змін: це початкові зображення, а не актуальні рендери змінених PUML. Актуальне джерело — PUML.

REST-етап: `class_diagram.puml`, `sequence_rest_indexing_implemented.puml`, `sequence_rest_statistics_implemented.puml`, `sequence_statistics3.puml`, `sequence_server_health_check.puml` та `sequence_rest_search.puml` відображають реалізований API. Версії перед REST збережено в `archive/before-rest/`. Окремий StatisticsService не додано: статистика читає той самий узгоджений State у SearchService без дублювання зберігання.

Ollama-етап: `class_diagram.puml`, `sequence_search2.puml` та `sequence_server_health_check.puml` описують реалізовану інтеграцію. Версії до інтеграції збережено в `archive/before-ollama/`. Для WebSocket актуальними є `sequence_webSocket_Search_progress_implemented.puml` і `sequence_websocket_keyword_search_implemented.puml`; файл без суфікса `_implemented` збережено як історичний цільовий варіант.

## Відповідність Use Case і Sequence Diagram

Use Case Diagram містить дев’ять варіантів: побудова індексу, налаштування кількості потоків, перегляд статистики, перевірка стану сервера, пошук документів, LLM-розширення запиту, keyword-пошук, перегляд результатів і відстеження прогресу. Їх покриття:

- побудова індексу та кількість потоків — `sequence_rest_indexing_implemented.puml` і `sequence_diagram.puml`;
- статистика — `sequence_rest_statistics_implemented.puml` та `sequence_statistics3.puml`;
- health — `sequence_server_health_check.puml`;
- keyword-пошук і результати — `sequence_rest_search.puml` та `sequence_websocket_keyword_search_implemented.puml`;
- LLM-розширення — `sequence_search2.puml`;
- прогрес пошуку — `sequence_webSocket_Search_progress_implemented.puml`.

Окремий відсутній функціональний Use Case не виявлено. Нові діаграми додають деталізовані реалізаційні представлення для REST-індексації, статистики та keyword WebSocket, не вводячи нових функцій.
