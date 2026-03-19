# Video Meta API

Spring Boot API для получения метаданных видео (размер, длительность, доступность) и генерации thumbnail.

Thumbnail-файлы отдаются через nginx напрямую с диска. Spring-приложение больше не раздаёт `/thumbnail/**`.

## API Endpoints

Публичные ручки:

- `POST /api/video/info` - получить метаданные видео и при необходимости инициировать генерацию thumbnail
- `POST /api/file/info` - получить базовые метаданные файла без генерации thumbnail
- `GET /status` - простой health-check приложения
- `GET /version` - агрегированная статистика запросов по версиям клиента

Защищённые ручки (`Basic Auth`):

- `GET /actuator/health` - health-check Spring Boot Actuator
- `GET /errors/source` - посмотреть последние ошибки удалённых источников в браузере или через `curl`
- `POST /errors/source` - получить последние ошибки удалённых источников с `limit` в JSON body
- `DELETE /api/video/remove` - удалить сохранённые метаданные видео и связанные thumbnail
- `GET /actuator/info` - информация Actuator
- `GET /actuator/metrics` - список доступных runtime-метрик
- `GET /actuator/metrics/{name}` - конкретная runtime-метрика, например `http.server.requests`

Для защищённых ручек используется `Basic Auth`:

- username: `admin`
- password: текущее значение `admin key` из файла `app.admin.key.path`

Примеры:

```bash
curl http://localhost:8080/status
curl -H "Kemonos: 1.0" -H "Content-Type: application/json" \
  -d '{"site":"kemono","path":"/data/..."}' \
  http://localhost:8080/api/video/info

curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/actuator/health
curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/errors/source
curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/actuator/metrics
curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/actuator/metrics/http.server.requests
```

### `POST /api/video/info`

Headers:

- `Kemonos: 1.0`
- `Content-Type: application/json`

Request:

```json
{
  "site": "kemono",
  "server": "https://kemono.su",
  "path": "/data/5b/64/5b64f0f7f8d8c9f4/video.mp4"
}
```

Response:

```json
{
  "site": "kemono",
  "server": "https://kemono.su",
  "path": "/data/5b/64/5b64f0f7f8d8c9f4/video.mp4",
  "ext": "mp4",
  "sizeBytes": 10485760,
  "durationSeconds": 123,
  "lastStatusCode": 200,
  "available": true,
  "createdAt": "2026-03-15T17:00:00Z"
}
```

### `POST /api/file/info`

Headers:

- `Kemonos: 1.0`
- `Content-Type: application/json`

Request:

```json
{
  "site": "kemono",
  "server": "https://kemono.su",
  "path": "/data/aa/bb/file.zip"
}
```

Response:

```json
{
  "site": "kemono",
  "server": "https://kemono.su",
  "path": "/data/aa/bb/file.zip",
  "ext": "zip",
  "sizeBytes": 73400320,
  "durationSeconds": 0,
  "lastStatusCode": 200,
  "available": true,
  "createdAt": "2026-03-15T17:00:00Z"
}
```

### `DELETE /api/video/remove`

Auth:

- `Basic Auth`

Headers:

- `Kemonos: 1.0`
- `Content-Type: application/json`

Request:

```json
{
  "site": "kemono",
  "server": "https://kemono.su",
  "path": "/data/5b/64/5b64f0f7f8d8c9f4/video.mp4"
}
```

Response:

```json
{
  "removed": true,
  "resolvedUrl": "https://kemono.su/data/5b/64/5b64f0f7f8d8c9f4/video.mp4",
  "removedThumbnails": true
}
```

### `GET /status`

Response:

```json
{
  "status": "UP",
  "time": "2026-03-15T17:00:00Z"
}
```

### `GET /version`

Response:

```json
{
  "totalRequests": 42,
  "versions": [
    {
      "version": "1.0",
      "requests": 30
    },
    {
      "version": "1.1",
      "requests": 12
    }
  ]
}
```

### `GET /errors/source`

Auth:

- `Basic Auth`

Query params:

- `limit` - optional, default `100`

Response:

```json
{
  "total": 2,
  "items": [
    {
      "clientVersion": "1.0",
      "endpoint": "/api/video/info",
      "site": "kemono",
      "requestValue": "/data/5b/64/video.mp4",
      "requestedUrl": "https://kemono.su/data/5b/64/video.mp4",
      "sourceUrl": "https://n3.kemono.su/data/5b/64/video.mp4",
      "stage": "DOWNLOAD",
      "statusCode": 502,
      "errorMessage": "Upstream timeout",
      "retary": 1,
      "requests": 3,
      "createdAt": "2026-03-15T17:00:00Z"
    }
  ]
}
```

### `POST /errors/source`

Auth:

- `Basic Auth`

Headers:

- `Content-Type: application/json`

Request:

```json
{
  "limit": 50
}
```

Response:

```json
{
  "total": 2,
  "items": [
    {
      "clientVersion": "1.0",
      "endpoint": "/api/video/info",
      "site": "kemono",
      "requestValue": "/data/5b/64/video.mp4",
      "requestedUrl": "https://kemono.su/data/5b/64/video.mp4",
      "sourceUrl": "https://n3.kemono.su/data/5b/64/video.mp4",
      "stage": "DOWNLOAD",
      "statusCode": 502,
      "errorMessage": "Upstream timeout",
      "retary": 1,
      "requests": 3,
      "createdAt": "2026-03-15T17:00:00Z"
    }
  ]
}
```

### `GET /actuator/health`

Auth:

- `Basic Auth`

Response:

```json
{
  "status": "UP"
}
```

### `GET /actuator/info`

Auth:

- `Basic Auth`

Response:

```json
{}
```

### `GET /actuator/metrics`

Auth:

- `Basic Auth`

Response:

```json
{
  "names": [
    "http.server.requests",
    "jvm.memory.used",
    "process.cpu.usage"
  ]
}
```

### `GET /actuator/metrics/{name}`

Auth:

- `Basic Auth`

Example request:

```bash
curl -u admin:YOUR_ADMIN_KEY \
  http://localhost:8080/actuator/metrics/http.server.requests
```

Example response:

```json
{
  "name": "http.server.requests",
  "baseUnit": "seconds",
  "measurements": [
    {
      "statistic": "COUNT",
      "value": 12.0
    },
    {
      "statistic": "TOTAL_TIME",
      "value": 1.348
    },
    {
      "statistic": "MAX",
      "value": 0.322
    }
  ],
  "availableTags": [
    {
      "tag": "method",
      "values": ["GET", "POST"]
    },
    {
      "tag": "status",
      "values": ["200", "401", "500"]
    }
  ]
}
```

## Базы данных

Приложение использует три отдельные SQLite базы:

- `/data/video-meta.db` - основная БД с `video_meta`
- `/data/video-meta-statistics.db` - статистика запросов и агрегация по версиям клиента
- `/data/video-meta-source-errors.db` - журнал ошибочных запросов к удалённым источникам

Пути можно переопределить через env:

```env
SPRING_DATASOURCE_URL=jdbc:sqlite:/data/video-meta.db
APP_STATISTICS_DATASOURCE_URL=jdbc:sqlite:/data/video-meta-statistics.db
APP_SOURCE_ERROR_LOG_DATASOURCE_URL=jdbc:sqlite:/data/video-meta-source-errors.db
```

Для error-логов работает автоматический retry:

- каждую минуту берутся самые старые записи
- при успехе запись удаляется
- при неуспехе увеличивается `retary`
- после достижения `retary=3` запись удаляется даже если retry снова неуспешен

Настройки:

```env
APP_SOURCE_ERROR_LOG_RETRY_INITIAL_DELAY_MILLIS=60000
APP_SOURCE_ERROR_LOG_RETRY_FIXED_DELAY_MILLIS=60000
APP_SOURCE_ERROR_LOG_RETRY_BATCH_SIZE=20
APP_SOURCE_ERROR_LOG_RETRY_MAX_RETARY=3
```

Для inspect-запросов действует справедливая очередь по IP:

- `/api/video/info` одновременно обрабатывает до `5` запросов
- `/api/file/info` одновременно обрабатывает до `3` запросов
- для каждого IP запросы ставятся в отдельную очередь и выбираются round-robin, чтобы один клиент не забирал все слоты
- в очереди может ждать не более `60` inspect-запросов (`video/file`) на один IP

Настройки:

```properties
app.inspect.video.max-concurrent-requests=6
app.inspect.file.max-concurrent-requests=4
app.inspect.max-queued-requests-per-user=60
```

## Установка Docker + Compose на VPS (Ubuntu/Debian)

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable docker
sudo systemctl start docker
```

Проверка:

```bash
docker --version
docker compose version
sudo docker run --rm hello-world
```

Опционально (без `sudo`):

```bash
sudo usermod -aG docker $USER
newgrp docker
```

## Запуск проекта в Docker Compose

Используется один режим:

- `cloudflared -> nginx -> api`
- nginx работает только как внутренний reverse proxy и static file server для `/thumbnail/**`
- TLS и домен завершаются на стороне Cloudflare, не в nginx

1. Клонируйте проект на VPS и перейдите в директорию проекта.
2. Создайте `.env`:

```bash
cp .env.example .env
```

3. Заполните `.env`:

```env
CLOUDFLARE_TUNNEL_TOKEN=...
```

4. В панели Cloudflare Tunnel укажите origin на `http://nginx:8080`.
5. Запустите:

```bash
docker compose up -d --build
```

6. Проверка:

```bash
docker compose ps
docker compose logs -f cloudflared
```

В этой схеме `/thumbnail/**` обслуживается напрямую nginx через `alias /data/thumbnail/`.

Пример:

```bash
curl https://your-domain/thumbnail/path/to/file/25.webp
```

## Логи

Логи пишутся в файлы:

- `/data/logs/app.log` — общие логи приложения и ошибки
- `/data/logs/access.log` — все HTTP-запросы
- `/data/logs/badrequest.log` — запросы с `4xx` (включая `400/403/429`)

Посмотреть:

```bash
docker compose exec api sh -lc 'ls -lah /data/logs && tail -n 100 /data/logs/app.log'
docker compose exec api sh -lc 'tail -n 100 /data/logs/access.log'
docker compose exec api sh -lc 'tail -n 100 /data/logs/badrequest.log'
```

## Полезные команды

```bash
docker compose logs -f
docker compose restart
docker compose down
docker compose up -d --build
```

Важно:

- Не публикуйте `80/443` на VPS: наружу должен смотреть только Cloudflare Tunnel.
- Tunnel должен ходить на `http://nginx:8080`, а не прямо на `api:8080`.
- `/thumbnail/**` не раздаётся Spring-приложением. Для thumbnail нужен nginx перед API.
