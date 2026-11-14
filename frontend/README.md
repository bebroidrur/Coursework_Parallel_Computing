# Parallel Search Web UI

Статичний frontend для вже реалізованого Spring Boot backend. Frontend не індексує документи самостійно: він викликає REST API та `/ws/search`.

## Запуск

З кореня репозиторію:

```bash
python3 -m http.server 4173 --directory frontend
```

Відкрийте `http://localhost:4173/?backend=http://localhost:8080`. У deployment-конфігурації можна задати `window.APP_CONFIG.backendUrl` у `config.js`; порожнє значення використовує same-origin, що сумісно з reverse proxy або майбутнім Docker Compose.

## Перевірка

```bash
npm test
```

Тести не потребують запущених backend або Ollama. Дані графіка статистики — копія фактичного CSV `benchmarks/results/2026-11-13/index-times.csv`.
