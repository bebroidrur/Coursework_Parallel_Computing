# UML

`class_diagram.puml` і `sequence_diagram.puml` відображають поточну реалізацію індексу та Thread Pool. Замість власного ThreadPool використано Java ExecutorService; N передається в buildParallel(documents, N). Послідовний build(documents) збережено.

Актуальні діаграми описують реалізовані REST, Ollama, WebSocket, CLI та статичний Web UI. Docker Compose залишається наступним етапом; архівні версії збережені в `diagrams/archive/`. Їхні buildIndex(N) — історичні/цільові позначення, а не поточні Java-сигнатури.

Усі початкові `.puml` і PNG збережено в `archive/initial/`. PNG у корені також залишено без змін: це початкові зображення, а не актуальні рендери змінених PUML. Актуальне джерело — PUML.

REST-етап: `class_diagram.puml`, `sequence_statistics3.puml`, `sequence_server_health_check.puml` та нова `sequence_rest_search.puml` відображають реалізований API. Версії перед REST збережено в `archive/before-rest/`. Окремий StatisticsService не додано: статистика читає той самий узгоджений State у SearchService без дублювання зберігання. Діаграми Ollama/WebSocket/deployment залишаються цільовими.

Ollama-етап: оновлено class_diagram, sequence_search2 та sequence_server_health_check. Версії до інтеграції: archive/before-ollama/. WebSocket-діаграма залишається цільовою; query_expansion/keyword_search/done поки повертаються лише у фінальній REST-відповіді.
