# Parallel Search Web UI

Статичний frontend для вже реалізованого Spring Boot backend. Frontend не індексує документи самостійно: він викликає REST API та `/ws/search`.

## Запуск

З кореня репозиторію:

```bash
python3 -m http.server 4173 --directory frontend
```

Відкрийте `http://localhost:4173/`: для локального запуску frontend за замовчуванням звертається до backend на `http://localhost:18080`. Іншу адресу можна задати через `?backend=http://host:port` або через `window.APP_CONFIG.backendUrl` у `config.js`; query-параметр має пріоритет, а порожнє значення використовує same-origin для reverse proxy або Docker Compose.

## Перевірка

```bash
npm test
```

Тести не потребують запущених backend або Ollama. Дані графіка статистики — фактичні вимірювання 500 MiB корпусу з `benchmarks/coursework-results/index-500m-raw.csv`; summary та графіки збережені в тому самому каталозі.
