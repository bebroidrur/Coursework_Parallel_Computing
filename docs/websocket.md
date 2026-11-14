# WebSocket-пошук

Backend відкриває endpoint `ws://localhost:8080/ws/search`. Він використовує той самий `SearchService`, що й REST, тому індекс, ranking, snippets, LLM expansion і fallback не дублюються.

## Запит

Клієнт надсилає один JSON-об’єкт після встановлення з’єднання:

```json
{"requestId":"r-123","query":"parallel computing","mode":"llm"}
```

`requestId` — обов’язковий непорожній string до 128 символів; `query` — string до 4096 символів, який містить хоча б один токен; `mode` — `keyword` або `llm`.

## Події

Кожна відповідь має `requestId`, `stage`, ISO-8601 `timestamp` і `data`. Події одного з’єднання можуть належати різним запитам, тому клієнт повинен групувати їх за `requestId`.

Для keyword-пошуку порядок такий:

```text
started -> keyword_search -> done
```

Для llm-пошуку:

```text
started -> query_expansion -> keyword_search -> done
```

Приклад `started`:

```json
{"requestId":"r-123","stage":"started","timestamp":"2026-11-14T10:00:00Z","data":{"query":"parallel computing","mode":"llm"}}
```

Приклад `query_expansion`:

```json
{"requestId":"r-123","stage":"query_expansion","timestamp":"2026-11-14T10:00:01Z","data":{"terms":["parallel","computing","threads"],"fallback":false,"warning":null}}
```

Приклад `keyword_search`:

```json
{"requestId":"r-123","stage":"keyword_search","timestamp":"2026-11-14T10:00:01Z","data":{"resultCount":3}}
```

`done.data` має той самий формат, що REST `SearchResponse`: `status`, `query`, `mode`, `terms`, `total`, `results`, `stages`, `fallback`, `warning`. `results` містить `document_id`, `score`, `snippet`.

```json
{"requestId":"r-123","stage":"done","timestamp":"2026-11-14T10:00:01Z","data":{"status":"ok","query":"java","mode":"keyword","terms":["java"],"total":1,"results":[{"document_id":"03-java.txt","score":1,"snippet":"Java supports threads..."}],"stages":["keyword_search","done"],"fallback":false,"warning":null}}
```

Помилка має `stage: "error"`, а `data` — `code` і `message`:

```json
{"requestId":"r-123","stage":"error","timestamp":"2026-11-14T10:00:01Z","data":{"code":"INVALID_MESSAGE","message":"Повідомлення має містити requestId, query і mode keyword або llm"}}
```

Якщо Ollama недоступна, `query_expansion` містить `fallback:true` і warning (`OLLAMA_TIMEOUT`, `OLLAMA_UNAVAILABLE`, `OLLAMA_HTTP_ERROR` або `OLLAMA_INVALID_RESPONSE`), а `done` повертає результати за початковими термінами. WebSocket не завершується через таку помилку.

## Потокобезпечність і закриття

Тривалий `SearchService.search` запускається в daemon executor, тому WebSocket container thread не блокується Ollama. Відправлення в конкретну сесію серіалізоване на її monitor. Для кожної сесії зберігається окрема множина Future; після закриття з’єднання незавершені задачі скасовуються. `requestId` не генерується сервером і є відповідальністю клієнта.

## Перевірка

Підтримуваний низькорівневий клієнт — будь-який WebSocket-клієнт, який вміє надсилати текстові кадри. Наприклад, після запуску backend:

```sh
npx wscat -c ws://localhost:8080/ws/search
> {"requestId":"r-1","query":"java","mode":"keyword"}
```

Для `llm` потрібна доступна Ollama; Maven-тести її не потребують і підміняють `OllamaService`.
