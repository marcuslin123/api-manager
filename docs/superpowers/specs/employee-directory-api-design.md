# Employee Directory REST API — Design Spec

**Date:** 2026-05-24
**Status:** Approved
**Scope:** MVP — public REST API with rate limiting. Auth/RBAC are documented stretch goals.

---

## 1. Purpose

### Problem

Knowledge is often scattered across teams within a company, making it difficult to know who to reach out to when encountering a specific problem. Employees — especially interns and new hires — waste time reaching out to multiple people across the organization before finding the right one.

### Solution

A public-facing REST API for an employee directory at Gexa Energy, a residential electricity supplier. Users can look up employees by role, team, and office to quickly identify the right person for a given inquiry — whether they're inside the company or in the broader energy sector.

### Goals

The primary purpose of this project is to understand how enterprises build production APIs: layered architecture, rate limiting as a cross-cutting concern, and designing for extensibility toward authentication, authorization, and role-based access control.

---

## 1a. Target Users

- **Interns and new employees at Gexa Energy** — identify who to reach out to when blocked on a problem
- **Energy sector professionals** — find the right Gexa contact for a specific domain or inquiry without cold-emailing multiple people

---

## 1b. Milestones

| Milestone | Scope |
|---|---|
| M1 | Functional employee directory REST API (CRUD endpoints, PostgreSQL, pagination) |
| M2 | Rate limiting (token bucket, two tiers, swappable interface) |
| M3 | Authentication, authorization, and RBAC (stretch goal) |

---

## 2. Tech Stack

| Concern | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Build tool | Gradle |
| Database | PostgreSQL |
| ORM | Spring Data JPA |
| Rate limiting | Bucket4j (core) |
| Validation | spring-boot-starter-validation |
| Observability | spring-boot-starter-actuator |
| Testing | JUnit 5, Testcontainers, MockMvc |

---

## 3. Architecture

```
HTTP Request
     │
     ▼
RateLimitInterceptor          ← Spring HandlerInterceptor
     │                           Bucket4j token bucket, keyed by API key or IP
     ▼
EmployeeController            ← @RestController, input validation
     │
     ▼
EmployeeService               ← Business logic, DTO mapping
     │
     ▼
EmployeeRepository            ← Spring Data JPA interface
     │
     ▼
PostgreSQL
```

**Rate limiter is decoupled behind an interface:**

```
RateLimiterService (interface)
  └── InMemoryRateLimiterService   ← MVP: ConcurrentHashMap<String, Bucket>
  └── RedisRateLimiterService      ← Stretch goal: horizontally scalable
```

The `RateLimitInterceptor` depends only on `RateLimiterService`. Swapping the implementation requires no changes to the interceptor.

---

## 4. Package Structure

```
com.example.apimanager
├── controller
│   └── EmployeeController.java
├── service
│   ├── EmployeeService.java
│   └── ratelimit
│       ├── RateLimiterService.java          ← interface
│       └── InMemoryRateLimiterService.java
├── interceptor
│   └── RateLimitInterceptor.java
├── repository
│   └── EmployeeRepository.java
├── model
│   └── Employee.java                        ← JPA entity
├── dto
│   ├── EmployeeDTO.java
│   ├── CreateEmployeeRequest.java
│   └── UpdateEmployeeRequest.java
├── exception
│   ├── EmployeeNotFoundException.java
│   └── GlobalExceptionHandler.java
└── config
    └── WebMvcConfig.java                    ← registers interceptor
```

---

## 5. Data Model

**Table: `employees`**

| Column | Type | Constraints |
|---|---|---|
| `id` | `UUID` | Primary key, auto-generated |
| `name` | `VARCHAR(255)` | NOT NULL |
| `email` | `VARCHAR(255)` | NULLABLE, UNIQUE |
| `department` | `VARCHAR(100)` | NOT NULL |
| `job_title` | `VARCHAR(100)` | NOT NULL |
| `office_location` | `VARCHAR(100)` | NOT NULL |
| `created_at` | `TIMESTAMP` | NOT NULL, auto-set via `@CreatedDate` |
| `updated_at` | `TIMESTAMP` | NOT NULL, auto-updated via `@LastModifiedDate` |

**Design decisions:**
- UUID primary key — does not leak record count or insertion order to public callers
- `email` nullable but unique when present — prevents duplicate records
- Audit columns (`created_at`, `updated_at`) — standard enterprise practice, free with JPA auditing

**Java layer:**
- `Employee` — JPA entity, internal representation
- `EmployeeDTO` — API response shape; entity and DTO are kept separate so schema and contract can evolve independently
- `CreateEmployeeRequest` — validated input for POST; all fields required
- `UpdateEmployeeRequest` — validated input for PUT (all fields required) and PATCH (all fields optional); a single class with all fields nullable — the service enforces which fields are required based on the HTTP method

---

## 6. API Endpoints

**Base path:** `/api/v1/employees`

Path versioning (`v1`) allows breaking changes to ship as `v2` without disrupting existing clients.

| Method | Path | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/v1/employees` | List all employees (paginated) | No |
| `GET` | `/api/v1/employees/{id}` | Get single employee by UUID | No |
| `POST` | `/api/v1/employees` | Create new employee | Yes |
| `PUT` | `/api/v1/employees/{id}` | Replace full employee record | Yes |
| `PATCH` | `/api/v1/employees/{id}` | Update specific fields | Yes |
| `DELETE` | `/api/v1/employees/{id}` | Remove employee | Yes |

**Pagination** (`GET /employees`): Spring Data Pageable — query params `page` (0-indexed) and `size`. Response includes `content`, `totalElements`, `totalPages`, `page`, `size`.

**Auth stub (MVP):** Write endpoints (`POST/PUT/PATCH/DELETE`) require an `X-API-Key` header. The interceptor validates it against a value in `application.properties`. Returns `401` if missing or invalid. This stub is the seam for full RBAC in a future iteration.

**Standard error envelope** (all error responses):
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Employee with id '...' not found",
  "timestamp": "2026-05-24T12:00:00Z"
}
```

---

## 7. Rate Limiting Design

### Why Rate Limiting Matters

As usage grows, a public API needs safeguards to prevent abuse, protect backend resources from excessive load, and defend against DDoS-style traffic patterns. Rate limiting is the first line of defense before authentication is introduced.

### Algorithm: Token Bucket

Each client has a bucket with a fixed token capacity. Each request consumes one token. Tokens refill at a steady rate. If the bucket is empty, the request is rejected with `429 Too Many Requests`. If the bucket is full, refilled tokens are discarded — capacity is never exceeded. Token bucket handles short bursts gracefully — a client can use saved tokens — unlike a fixed window that hard-resets every minute.

### Tiers

| Tier | Identified By | Limit |
|---|---|---|
| Anonymous | IP address | 30 requests/minute |
| Authenticated | API key | 100 requests/minute |

### Request Identity Resolution

The interceptor resolves the bucket key in this order:
1. `X-API-Key` header present → use the key value
2. No key → use `X-Forwarded-For` header (proxy-aware IP)
3. No proxy header → use `request.getRemoteAddr()`

### Interceptor Flow

On every request:
1. Resolve bucket key and tier
2. Call `rateLimiterService.tryConsume(key, tier)`
3. If `true` → set rate limit headers and proceed
4. If `false` → return `429` with `Retry-After` header

### Response Headers (every request)

| Header | Description |
|---|---|
| `X-RateLimit-Limit` | Client's total bucket capacity |
| `X-RateLimit-Remaining` | Tokens remaining |
| `X-RateLimit-Retry-After` | Seconds until refill (429 only) |

### RateLimiterService Interface

```java
public interface RateLimiterService {
    boolean tryConsume(String key, RateLimitTier tier);
}
```

`InMemoryRateLimiterService`: `ConcurrentHashMap<String, Bucket>` — buckets are created on first request and reused. Safe for concurrent access.

**Scaling note:** In-memory buckets are per-instance. A future `RedisRateLimiterService` using Bucket4j's distributed proxy shares state across instances — the interface swap requires no changes to the interceptor.

---

## 8. Error Handling

A `@RestControllerAdvice` (`GlobalExceptionHandler`) centralizes all error responses:

| Exception | HTTP Status |
|---|---|
| `EmployeeNotFoundException` | `404 Not Found` |
| `MethodArgumentNotValidException` | `400 Bad Request` |
| `HttpMessageNotReadableException` | `400 Bad Request` |
| Rate limit exceeded | `429 Too Many Requests` |
| Unhandled exception | `500 Internal Server Error` |

All responses use the standard error envelope defined in Section 6.

---

## 9. Testing Strategy

### Unit Tests
- `EmployeeService` — mock repository, verify business logic (not found, create, update, delete)
- `InMemoryRateLimiterService` — verify bucket creation, token consumption, exhaustion, and refill
- `RateLimitInterceptor` — mock `RateLimiterService`, verify `429` on rejected consume, pass-through on success

### Integration Tests
- `@SpringBootTest` + Testcontainers (real PostgreSQL container)
- Full request lifecycle: HTTP → interceptor → controller → service → DB → response
- Rate limit end-to-end: fire 31 anonymous requests, assert 31st returns `429`

### Contract Tests
- `MockMvc` assertions on response JSON structure
- Ensures DTO layer never leaks internal entity fields
- Verifies pagination envelope shape

---

## 10. Stretch Goals (out of MVP scope)

These are documented here for architectural awareness — the MVP is designed so none of these require structural changes.

| Goal | What changes |
|---|---|
| Real API key issuance (`POST /keys`) | New controller + `api_keys` table |
| Role-based rate limit tiers | `RateLimiterService` reads tier from key record |
| RBAC on write endpoints | Interceptor swaps config check for key→role lookup |
| Redis-backed rate limiting | Add `RedisRateLimiterService`, swap Spring bean |
| JWT / OAuth2 authentication | Add Spring Security, replace API key stub |
| Basic filtering (`?department=X`) | Add JPA `Specification` or `@Query` to repository |
