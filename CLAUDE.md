# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Environment Setup

Copy `.env.example` to `.env` and populate the values before running Claude Code:

```bash
cp .env.example .env
source .env
```

Required environment variables:
- `AWS_BEARER_TOKEN_BEDROCK` — AWS Bedrock bearer token for Claude API access
- `AWS_REGION` — AWS region (default: `us-east-1`)
- `CLAUDE_CODE_USE_BEDROCK` — Set to `1` to route Claude Code through Bedrock

## Project

An employee directory REST API for Gexa Energy. Users look up employees by role, team, and office. The core focus is a public-facing API with rate limiting as a cross-cutting concern.

Full design spec: `docs/superpowers/specs/employee-directory-api-design.md`

## Tech Stack

- **Language:** Java 21
- **Framework:** Spring Boot 3.x
- **Build tool:** Gradle
- **Database:** PostgreSQL
- **ORM:** Spring Data JPA
- **Rate limiting:** Bucket4j (token bucket, in-memory for MVP)
- **Testing:** JUnit 5, Testcontainers, MockMvc

## Architecture

```
HTTP Request
     │
     ▼
RateLimitInterceptor     ← Spring HandlerInterceptor; token bucket via Bucket4j
     │
     ▼
EmployeeController       ← @RestController; input validation
     │
     ▼
EmployeeService          ← Business logic; entity ↔ DTO mapping
     │
     ▼
EmployeeRepository       ← Spring Data JPA
     │
     ▼
PostgreSQL
```

Rate limiter is behind a `RateLimiterService` interface. MVP uses `InMemoryRateLimiterService`. A future `RedisRateLimiterService` swaps in with no changes to the interceptor.

## Package Structure

```
com.example.apimanager
├── controller
├── service
│   └── ratelimit        ← RateLimiterService interface + implementations
├── interceptor
├── repository
├── model                ← JPA entities
├── dto                  ← API request/response objects
├── exception
└── config
```

## API

Base path: `/api/v1/employees`

- `GET /employees` — list all (paginated), open to all
- `GET /employees/{id}` — get by UUID, open to all
- `POST /employees` — create, requires `X-API-Key` header
- `PUT /employees/{id}` — full replace, requires `X-API-Key` header
- `PATCH /employees/{id}` — partial update, requires `X-API-Key` header
- `DELETE /employees/{id}` — delete, requires `X-API-Key` header

## Coding Approach

- Keep layers strictly separated — controllers call services, services call repositories, nothing skips a layer
- Program to interfaces, not implementations (especially for `RateLimiterService`)
- Entity and DTO are always separate classes — never return a JPA entity directly from a controller
- All error responses use the standard envelope: `{ status, error, message, timestamp }`
- Write unit tests for service and rate limiter logic; integration tests with Testcontainers for end-to-end flows
