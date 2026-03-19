# Video Meta API

[Русская версия](./README.ru.md)

Video Meta API is a Spring Boot service that:

- inspects remote files and videos
- stores metadata in SQLite
- generates thumbnails for video files
- serves thumbnails through nginx
- exposes protected operational endpoints for health and source-error inspection

## Highlights

- Video and file inspection via JSON API
- Thumbnail generation with ffmpeg
- SQLite-based storage with separate databases for metadata, request stats, and source errors
- Per-client/IP request limiting and inspect queue limits
- Admin-only operational endpoints
- Docker Compose setup with nginx and optional Cloudflare Tunnel

## Architecture

```text
Client -> nginx -> Spring Boot API -> SQLite
                           |
                           -> ffmpeg/webp thumbnail pipeline
```

Public endpoints:

- `POST /api/video/info`
- `POST /api/file/info`
- `GET /status`

Protected endpoints:

- `GET /version`
- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/metrics`
- `GET /actuator/metrics/{name}`
- `GET /errors/source`
- `POST /errors/source`
- `DELETE /api/video/remove`

## Requirements

- Java 21
- ffmpeg available in `PATH`
- Docker and Docker Compose for containerized deployment

## Quick Start

### Local run

```bash
./gradlew bootRun
```

The application starts on `http://localhost:8080`.

### Docker Compose

```bash
cp .env.example .env
docker compose up --build
```

If you use the `cloudflared` service, set `CLOUDFLARE_TUNNEL_TOKEN` in `.env`.

## Configuration

The service is configured through environment variables. Defaults are defined in
[application.properties](./src/main/resources/application.properties)
and
[application-prod.properties](./src/main/resources/application-prod.properties).

Common variables:

| Variable | Purpose | Default |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | Main metadata database | `jdbc:sqlite:./dataTest/video-meta.db` |
| `APP_STATISTICS_DATASOURCE_URL` | Request statistics database | `jdbc:sqlite:./dataTest/video-meta-statistics.db` |
| `APP_SOURCE_ERROR_LOG_DATASOURCE_URL` | Source error log database | `jdbc:sqlite:./dataTest/video-meta-source-errors.db` |
| `APP_THUMBNAIL_ROOT` | Thumbnail output directory | `./dataTest/thumbnail` |
| `APP_ADMIN_KEY_PATH` | File containing the admin key | `./dataTest/.admin.key` |
| `APP_RATE_LIMIT_MAX_REQUESTS` | Requests allowed per rate-limit window | `60` |
| `APP_RATE_LIMIT_WINDOW_SECONDS` | Rate-limit window size | `10` |
| `APP_SOURCE_MAX_CONCURRENT_REQUESTS` | Concurrent upstream source requests | `4` |
| `APP_INSPECT_MAX_QUEUED_REQUESTS_PER_USER` | Per-client queued inspect requests | `60` |
| `APP_THUMBNAIL_GENERATION_TIMEOUT_SECONDS` | Thumbnail generation timeout | `30` |

Admin authentication:

- username: `admin`
- password: the current value stored in the file referenced by `APP_ADMIN_KEY_PATH`

## API Usage

All inspect endpoints expect the `Kemonos` client version header.

### Check service status

```bash
curl http://localhost:8080/status
```

### Inspect a video

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

Example response:

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

### Inspect a file without thumbnail generation

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

### Remove stored video metadata and thumbnails

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

### Read source error log

```bash
curl -u admin:YOUR_ADMIN_KEY \
  -H "Content-Type: application/json" \
  -d '{"limit":50}' \
  http://localhost:8080/errors/source
```

Or open it in a browser:

```text
http://localhost:8080/errors/source?limit=100
```

### Read runtime metrics

```bash
curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/version
curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/actuator/health
curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/actuator/metrics
curl -u admin:YOUR_ADMIN_KEY http://localhost:8080/actuator/metrics/http.server.requests
```

## Deployment Notes

- nginx serves `/thumbnail/**` directly from disk
- the API service should stay behind nginx in production
- if you use Cloudflare Tunnel, point the tunnel to `http://nginx:8080`
- mount your persistent data directory to `/data` in containers

## Repository Layout

```text
src/main/kotlin/.../application       application services
src/main/kotlin/.../config            security, rate limiting, request filters
src/main/kotlin/.../infrastructure    persistence, source access, thumbnails
src/main/kotlin/.../presentation      REST controllers and DTOs
src/main/resources                    Spring configuration
nginx/                                nginx config and container files
```

## Development

Run tests:

```bash
./gradlew test
```

Build a jar:

```bash
./gradlew build
```

## Notes

- Public domains such as `kemono.su`, `kemono.cr`, `coomer.st`, and `coomer.su` are intentionally referenced in the project logic and examples.
- Secrets are not stored in the repository. `.env`, admin key files, local databases, and logs are expected to stay outside git.
