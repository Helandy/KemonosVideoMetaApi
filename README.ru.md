# Video Meta API

[English version](./README.md)

Video Meta API - это Spring Boot сервис, который:

- получает метаданные удалённых файлов и видео
- сохраняет результат в SQLite
- генерирует thumbnail для видео
- отдаёт thumbnail через nginx
- предоставляет защищённые служебные endpoints для health-check и просмотра ошибок источников

## Возможности

- Проверка видео и файлов через JSON API
- Генерация thumbnail через ffmpeg
- Отдельные SQLite-базы для метаданных, статистики запросов и ошибок источников
- Ограничение запросов и очередей по клиенту/IP
- Защищённые admin endpoints
- Docker Compose конфиг с nginx и опциональным Cloudflare Tunnel

## Архитектура

```text
Client -> nginx -> Spring Boot API -> SQLite
                           |
                           -> ffmpeg/webp pipeline для thumbnail
```

Публичные endpoints:

- `POST /api/video/info`
- `POST /api/file/info`
- `GET /status`

Защищённые endpoints:

- `GET /version`
- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/metrics`
- `GET /actuator/metrics/{name}`
- `GET /errors/source`
- `POST /errors/source`
- `DELETE /api/video/remove`

## Требования

- Java 21
- `ffmpeg` в `PATH`
- Docker и Docker Compose для контейнерного запуска

## Быстрый старт

### Локальный запуск

```bash
./gradlew bootRun
```

Сервис поднимется на `http://localhost:8080`.

### Docker Compose

```bash
cp .env.example .env
docker compose up --build
```

Если используешь `cloudflared`, заполни `CLOUDFLARE_TUNNEL_TOKEN` в `.env`.

## Конфигурация

Основные переменные задаются через окружение. Значения по умолчанию лежат в
[application.properties](./src/main/resources/application.properties)
и
[application-prod.properties](./src/main/resources/application-prod.properties).

Часто используемые переменные:

| Переменная | Назначение | Значение по умолчанию |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | Основная база метаданных | `jdbc:sqlite:./dataTest/video-meta.db` |
| `APP_STATISTICS_DATASOURCE_URL` | База статистики запросов | `jdbc:sqlite:./dataTest/video-meta-statistics.db` |
| `APP_SOURCE_ERROR_LOG_DATASOURCE_URL` | База ошибок источников | `jdbc:sqlite:./dataTest/video-meta-source-errors.db` |
| `APP_THUMBNAIL_ROOT` | Каталог для thumbnail | `./dataTest/thumbnail` |
| `APP_ADMIN_KEY_PATH` | Файл с admin key | `./dataTest/.admin.key` |
| `APP_RATE_LIMIT_MAX_REQUESTS` | Лимит запросов за окно | `60` |
| `APP_RATE_LIMIT_WINDOW_SECONDS` | Размер окна rate limit | `10` |
| `APP_SOURCE_MAX_CONCURRENT_REQUESTS` | Параллельные запросы к upstream | `4` |
| `APP_INSPECT_MAX_QUEUED_REQUESTS_PER_USER` | Максимальная очередь inspect на клиента | `60` |
| `APP_THUMBNAIL_GENERATION_TIMEOUT_SECONDS` | Таймаут генерации thumbnail | `30` |

Admin авторизация:

- username: `admin`
- password: текущее значение из файла, указанного в `APP_ADMIN_KEY_PATH`

## Использование API

Для inspect endpoints ожидается заголовок `Kemonos` с версией клиента.

### Проверка статуса

```bash
curl http://localhost:8080/status
```

### Получить метаданные видео

```bash
curl -H "Kemonos: 1.0" \
  -H "Content-Type: application/json" \
  -d '{
    "site": "kemono",
    "server": "https://kemono.su",
    "path": "/data/5b/64/example.mp4"
  }' \
  http://localhost:8080/api/video/info
```

Пример ответа:

```json
{
  "site": "kemono",
  "server": "https://kemono.su",
  "path": "/data/5b/64/example.mp4",
  "ext": "mp4",
  "sizeBytes": 10485760,
  "durationSeconds": 123,
  "lastStatusCode": 200,
  "available": true,
  "createdAt": "2026-03-15T17:00:00Z"
}
```

### Получить метаданные файла без генерации thumbnail

```bash
curl -H "Kemonos: 1.0" \
  -H "Content-Type: application/json" \
  -d '{
    "site": "kemono",
    "server": "https://kemono.su",
    "path": "/data/aa/bb/archive.zip"
  }' \
  http://localhost:8080/api/file/info
```

### Удалить сохранённые метаданные и thumbnail

```bash
curl -X DELETE \
  -u admin:YOUR_ADMIN_KEY \
  -H "Kemonos: 1.0" \
  -H "Content-Type: application/json" \
  -d '{
    "site": "kemono",
    "server": "https://kemono.su",
    "path": "/data/5b/64/example.mp4"
  }' \
  http://localhost:8080/api/video/remove
```

### Посмотреть журнал ошибок источников

```bash
curl -u admin:YOUR_ADMIN_KEY \
  -H "Content-Type: application/json" \
  -d '{"limit":50}' \
  http://localhost:8080/errors/source
```

Или открыть в браузере:

```text
http://localhost:8080/errors/source?limit=100
```

### Runtime метрики

```bash
curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/version
curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/actuator/health
curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/actuator/metrics
curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/actuator/metrics/http.server.requests
```

## Деплой

- nginx должен раздавать `/thumbnail/**` напрямую с диска
- в production API лучше держать за nginx
- если используешь Cloudflare Tunnel, направляй его на `http://nginx:8080`
- постоянные данные контейнера лучше монтировать в `/data`

## Структура репозитория

```text
src/main/kotlin/.../application       прикладные сервисы
src/main/kotlin/.../config            security, rate limit, фильтры
src/main/kotlin/.../infrastructure    persistence, source access, thumbnail generation
src/main/kotlin/.../presentation      REST controllers и DTO
src/main/resources                    Spring-конфиги
nginx/                                nginx-конфиг и container files
```

## Разработка

Запуск тестов:

```bash
./gradlew test
```

Сборка:

```bash
./gradlew build
```

## Примечания

- Домены `kemono.su`, `kemono.cr`, `coomer.st` и `coomer.su` намеренно используются в логике и примерах проекта.
- Секреты не должны храниться в репозитории. `.env`, admin key, локальные базы и логи должны оставаться вне git.
