# Employee Directory API

Knowledge is often scattered across teams, making it difficult to know who to reach out to for a specific problem. This is a public-facing REST API for an employee directory at Gexa Energy, allowing users to look up employees by role, team, and office to quickly identify the right person for a given inquiry.

## Tech Stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Database | PostgreSQL |
| ORM | Spring Data JPA |
| Rate Limiting | Bucket4j |
| Build Tool | Gradle |

## Core Feature — Rate Limiting

Every request passes through a rate limiting interceptor before reaching the API. Rate limiting is implemented using a **token bucket algorithm**: each client has a bucket of tokens, one token is consumed per request, and tokens refill at a steady rate. Clients who exhaust their tokens receive a `429 Too Many Requests` response. If the bucket is full, excess refilled tokens are discarded — capacity is never exceeded.

Two tiers:

| Tier | Identified By | Limit |
|---|---|---|
| Anonymous | IP address | 30 requests/minute |
| Authenticated | API key (`X-API-Key` header) | 100 requests/minute |

The rate limiter is decoupled behind an interface (`RateLimiterService`), making the implementation swappable — the in-memory store can be replaced with a Redis-backed distributed implementation with no changes to the interceptor.

## Endpoints

| Method | Path | Auth Required |
|---|---|---|
| `GET` | `/api/v1/employees` | No |
| `GET` | `/api/v1/employees/{id}` | No |
| `POST` | `/api/v1/employees` | Yes |
| `PUT` | `/api/v1/employees/{id}` | Yes |
| `PATCH` | `/api/v1/employees/{id}` | Yes |
| `DELETE` | `/api/v1/employees/{id}` | Yes |

## Running Locally

**Prerequisites:** Java 21, PostgreSQL, Gradle

1. Clone the repo
   ```bash
   git clone https://github.com/marcuslin123/api-manager.git
   cd api-manager
   ```

2. Configure the database in `src/main/resources/application.properties`
   ```properties
   spring.datasource.url=jdbc:postgresql://localhost:5432/employee_directory
   spring.datasource.username=your_username
   spring.datasource.password=your_password
   ```

3. Run the app
   ```bash
   ./gradlew bootRun
   ```

The API will be available at `http://localhost:8080`.
