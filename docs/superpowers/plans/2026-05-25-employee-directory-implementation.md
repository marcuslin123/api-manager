# Employee Directory API — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a public-facing employee directory REST API with token-bucket rate limiting, backed by PostgreSQL, using Spring Boot 4 / Java 21.

**Architecture:** Requests pass through `RateLimitInterceptor` (Spring `HandlerInterceptor`) before reaching `EmployeeController`. The interceptor delegates to `RateLimiterService` (interface), whose `InMemoryRateLimiterService` implementation manages per-client Bucket4j token buckets in a `ConcurrentHashMap`. Below the controller, `EmployeeService` handles business logic and entity↔DTO mapping, and `EmployeeRepository` (Spring Data JPA) talks to PostgreSQL.

**Tech Stack:** Java 21, Spring Boot 4.0.6, Gradle (Kotlin DSL), PostgreSQL, Bucket4j 8.10.1, JUnit 5, Mockito, MockMvc, Testcontainers

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `build.gradle.kts` | Modify | Add Bucket4j + Testcontainers dependencies |
| `src/main/resources/application.properties` | Modify | DB config, JPA settings, admin key |
| `EmployeeDirectoryApplication.java` | Modify | Add `@EnableJpaAuditing` |
| `model/Employee.java` | Create | JPA entity mapping `employees` table |
| `repository/EmployeeRepository.java` | Create | Spring Data JPA interface |
| `dto/EmployeeDTO.java` | Create | API response shape |
| `dto/CreateEmployeeRequest.java` | Create | Validated POST body |
| `dto/UpdateEmployeeRequest.java` | Create | PUT/PATCH body (all fields nullable) |
| `exception/ErrorResponse.java` | Create | Standard error envelope record |
| `exception/EmployeeNotFoundException.java` | Create | 404 exception |
| `exception/GlobalExceptionHandler.java` | Create | `@RestControllerAdvice` centralizing all errors |
| `service/EmployeeService.java` | Create | Business logic + entity↔DTO mapping |
| `service/ratelimit/RateLimitTier.java` | Create | Enum with per-tier token limits |
| `service/ratelimit/RateLimitResult.java` | Create | Result record returned by rate limiter |
| `service/ratelimit/RateLimiterService.java` | Create | Interface: `tryConsume(key, tier) → RateLimitResult` |
| `service/ratelimit/InMemoryRateLimiterService.java` | Create | Bucket4j + ConcurrentHashMap implementation |
| `interceptor/RateLimitInterceptor.java` | Create | Resolves key/tier, calls service, sets headers |
| `config/WebMvcConfig.java` | Create | Creates `RateLimitInterceptor` bean, registers it |
| `controller/EmployeeController.java` | Create | REST endpoints |
| `test/.../EmployeeServiceTest.java` | Create | Unit tests for service logic |
| `test/.../InMemoryRateLimiterServiceTest.java` | Create | Unit tests for token bucket behavior |
| `test/.../RateLimitInterceptorTest.java` | Create | Unit tests for interceptor flow |
| `test/.../EmployeeControllerTest.java` | Create | MockMvc contract tests |
| `test/.../EmployeeIntegrationTest.java` | Create | Testcontainers end-to-end tests |

**Note:** `RateLimitResult` is a new file not listed in the spec's package structure — it is required for the interceptor to set `X-RateLimit-Remaining` and `X-RateLimit-Retry-After` headers without leaking Bucket4j types through the interface.

---

## Task 1: Project Configuration

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/com/gexaenergy/employeedirectory/EmployeeDirectoryApplication.java`

- [ ] **Step 1: Add Bucket4j and Testcontainers to build.gradle.kts**

Replace the `dependencies` block in `build.gradle.kts` with:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 2: Verify the build resolves**

```bash
./gradlew dependencies --configuration compileClasspath | grep bucket4j
```

Expected output includes: `com.bucket4j:bucket4j-core:8.10.1`

- [ ] **Step 3: Configure application.properties**

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=employee-directory

# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/employee_directory
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.datasource.driver-class-name=org.postgresql.Driver

# JPA
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# Admin API key (change in production)
api.admin.key=dev-admin-key-change-in-production

# Actuator
management.endpoints.web.exposure.include=health,info
```

- [ ] **Step 4: Enable JPA auditing on the main application class**

Replace `src/main/java/com/gexaenergy/employeedirectory/EmployeeDirectoryApplication.java` with:

```java
package com.gexaenergy.employeedirectory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class EmployeeDirectoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmployeeDirectoryApplication.class, args);
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts src/main/resources/application.properties src/main/java/com/gexaenergy/employeedirectory/EmployeeDirectoryApplication.java
git commit -m "feat: configure dependencies, application properties, JPA auditing"
```

---

## Task 2: Employee Entity

**Files:**
- Create: `src/main/java/com/gexaenergy/employeedirectory/model/Employee.java`

- [ ] **Step 1: Implement the Employee JPA entity**

```java
package com.gexaenergy.employeedirectory.model;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "employees")
@EntityListeners(AuditingEntityListener.class)
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true)
    private String email;

    @Column(nullable = false)
    private String department;

    @Column(name = "job_title", nullable = false)
    private String jobTitle;

    @Column(name = "office_location", nullable = false)
    private String officeLocation;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }

    public String getOfficeLocation() { return officeLocation; }
    public void setOfficeLocation(String officeLocation) { this.officeLocation = officeLocation; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/gexaenergy/employeedirectory/model/Employee.java
git commit -m "feat: add Employee JPA entity"
```

---

## Task 3: Repository

**Files:**
- Create: `src/main/java/com/gexaenergy/employeedirectory/repository/EmployeeRepository.java`

- [ ] **Step 1: Implement the repository interface**

```java
package com.gexaenergy.employeedirectory.repository;

import com.gexaenergy.employeedirectory.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/gexaenergy/employeedirectory/repository/EmployeeRepository.java
git commit -m "feat: add EmployeeRepository"
```

---

## Task 4: DTOs and Request Classes

**Files:**
- Create: `src/main/java/com/gexaenergy/employeedirectory/dto/EmployeeDTO.java`
- Create: `src/main/java/com/gexaenergy/employeedirectory/dto/CreateEmployeeRequest.java`
- Create: `src/main/java/com/gexaenergy/employeedirectory/dto/UpdateEmployeeRequest.java`

- [ ] **Step 1: Implement EmployeeDTO**

```java
package com.gexaenergy.employeedirectory.dto;

import java.util.UUID;

public class EmployeeDTO {

    private UUID id;
    private String name;
    private String email;
    private String department;
    private String jobTitle;
    private String officeLocation;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }

    public String getOfficeLocation() { return officeLocation; }
    public void setOfficeLocation(String officeLocation) { this.officeLocation = officeLocation; }
}
```

- [ ] **Step 2: Implement CreateEmployeeRequest**

```java
package com.gexaenergy.employeedirectory.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class CreateEmployeeRequest {

    @NotBlank
    private String name;

    @Email
    private String email;

    @NotBlank
    private String department;

    @NotBlank
    private String jobTitle;

    @NotBlank
    private String officeLocation;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }

    public String getOfficeLocation() { return officeLocation; }
    public void setOfficeLocation(String officeLocation) { this.officeLocation = officeLocation; }
}
```

- [ ] **Step 3: Implement UpdateEmployeeRequest**

All fields nullable — the service decides what's required based on PUT vs PATCH.

```java
package com.gexaenergy.employeedirectory.dto;

public class UpdateEmployeeRequest {

    private String name;
    private String email;
    private String department;
    private String jobTitle;
    private String officeLocation;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }

    public String getOfficeLocation() { return officeLocation; }
    public void setOfficeLocation(String officeLocation) { this.officeLocation = officeLocation; }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/gexaenergy/employeedirectory/dto/
git commit -m "feat: add EmployeeDTO, CreateEmployeeRequest, UpdateEmployeeRequest"
```

---

## Task 5: Exception Handling

**Files:**
- Create: `src/main/java/com/gexaenergy/employeedirectory/exception/ErrorResponse.java`
- Create: `src/main/java/com/gexaenergy/employeedirectory/exception/EmployeeNotFoundException.java`
- Create: `src/main/java/com/gexaenergy/employeedirectory/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Implement ErrorResponse record**

```java
package com.gexaenergy.employeedirectory.exception;

public record ErrorResponse(int status, String error, String message, String timestamp) {
}
```

- [ ] **Step 2: Implement EmployeeNotFoundException**

```java
package com.gexaenergy.employeedirectory.exception;

import java.util.UUID;

public class EmployeeNotFoundException extends RuntimeException {

    public EmployeeNotFoundException(UUID id) {
        super("Employee with id '" + id + "' not found");
    }
}
```

- [ ] **Step 3: Implement GlobalExceptionHandler**

```java
package com.gexaenergy.employeedirectory.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmployeeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EmployeeNotFoundException ex) {
        return ResponseEntity.status(404).body(
            new ErrorResponse(404, "Not Found", ex.getMessage(), Instant.now().toString())
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity.status(400).body(
            new ErrorResponse(400, "Bad Request", message, Instant.now().toString())
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        return ResponseEntity.status(500).body(
            new ErrorResponse(500, "Internal Server Error", "An unexpected error occurred", Instant.now().toString())
        );
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/gexaenergy/employeedirectory/exception/
git commit -m "feat: add ErrorResponse, EmployeeNotFoundException, GlobalExceptionHandler"
```

---

## Task 6: EmployeeService (TDD)

**Files:**
- Create: `src/test/java/com/gexaenergy/employeedirectory/service/EmployeeServiceTest.java`
- Create: `src/main/java/com/gexaenergy/employeedirectory/service/EmployeeService.java`

- [ ] **Step 1: Write the failing unit tests**

```java
package com.gexaenergy.employeedirectory.service;

import com.gexaenergy.employeedirectory.dto.CreateEmployeeRequest;
import com.gexaenergy.employeedirectory.dto.EmployeeDTO;
import com.gexaenergy.employeedirectory.dto.UpdateEmployeeRequest;
import com.gexaenergy.employeedirectory.exception.EmployeeNotFoundException;
import com.gexaenergy.employeedirectory.model.Employee;
import com.gexaenergy.employeedirectory.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {

    @Mock
    private EmployeeRepository repository;

    @InjectMocks
    private EmployeeService service;

    private Employee sampleEmployee() {
        Employee e = new Employee();
        e.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        e.setName("Alice Smith");
        e.setEmail("alice@gexa.com");
        e.setDepartment("Engineering");
        e.setJobTitle("Software Engineer");
        e.setOfficeLocation("Houston");
        return e;
    }

    @Test
    void getEmployeeById_returnsDTO_whenFound() {
        Employee emp = sampleEmployee();
        when(repository.findById(emp.getId())).thenReturn(Optional.of(emp));

        EmployeeDTO result = service.getEmployeeById(emp.getId());

        assertThat(result.getName()).isEqualTo("Alice Smith");
        assertThat(result.getDepartment()).isEqualTo("Engineering");
        assertThat(result.getJobTitle()).isEqualTo("Software Engineer");
        assertThat(result.getOfficeLocation()).isEqualTo("Houston");
    }

    @Test
    void getEmployeeById_throwsNotFoundException_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEmployeeById(id))
            .isInstanceOf(EmployeeNotFoundException.class)
            .hasMessageContaining(id.toString());
    }

    @Test
    void createEmployee_savesAndReturnsDTO() {
        CreateEmployeeRequest request = new CreateEmployeeRequest();
        request.setName("Bob Jones");
        request.setDepartment("Product");
        request.setJobTitle("Product Manager");
        request.setOfficeLocation("Austin");

        Employee saved = new Employee();
        saved.setId(UUID.randomUUID());
        saved.setName("Bob Jones");
        saved.setDepartment("Product");
        saved.setJobTitle("Product Manager");
        saved.setOfficeLocation("Austin");

        when(repository.save(any(Employee.class))).thenReturn(saved);

        EmployeeDTO result = service.createEmployee(request);

        assertThat(result.getName()).isEqualTo("Bob Jones");
        assertThat(result.getId()).isNotNull();
        verify(repository).save(any(Employee.class));
    }

    @Test
    void deleteEmployee_throwsNotFoundException_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteEmployee(id))
            .isInstanceOf(EmployeeNotFoundException.class);
    }

    @Test
    void patchEmployee_updatesOnlyProvidedFields() {
        Employee emp = sampleEmployee();
        when(repository.findById(emp.getId())).thenReturn(Optional.of(emp));
        when(repository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));

        UpdateEmployeeRequest request = new UpdateEmployeeRequest();
        request.setDepartment("Product");

        EmployeeDTO result = service.patchEmployee(emp.getId(), request);

        assertThat(result.getDepartment()).isEqualTo("Product");
        assertThat(result.getName()).isEqualTo("Alice Smith");
        assertThat(result.getJobTitle()).isEqualTo("Software Engineer");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.gexaenergy.employeedirectory.service.EmployeeServiceTest"
```

Expected: FAIL — `EmployeeService` has no implementation yet.

- [ ] **Step 3: Implement EmployeeService**

```java
package com.gexaenergy.employeedirectory.service;

import com.gexaenergy.employeedirectory.dto.CreateEmployeeRequest;
import com.gexaenergy.employeedirectory.dto.EmployeeDTO;
import com.gexaenergy.employeedirectory.dto.UpdateEmployeeRequest;
import com.gexaenergy.employeedirectory.exception.EmployeeNotFoundException;
import com.gexaenergy.employeedirectory.model.Employee;
import com.gexaenergy.employeedirectory.repository.EmployeeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class EmployeeService {

    private final EmployeeRepository repository;

    public EmployeeService(EmployeeRepository repository) {
        this.repository = repository;
    }

    public Page<EmployeeDTO> getAllEmployees(Pageable pageable) {
        return repository.findAll(pageable).map(this::toDTO);
    }

    public EmployeeDTO getEmployeeById(UUID id) {
        return repository.findById(id)
            .map(this::toDTO)
            .orElseThrow(() -> new EmployeeNotFoundException(id));
    }

    public EmployeeDTO createEmployee(CreateEmployeeRequest request) {
        Employee employee = new Employee();
        employee.setName(request.getName());
        employee.setEmail(request.getEmail());
        employee.setDepartment(request.getDepartment());
        employee.setJobTitle(request.getJobTitle());
        employee.setOfficeLocation(request.getOfficeLocation());
        return toDTO(repository.save(employee));
    }

    public EmployeeDTO updateEmployee(UUID id, UpdateEmployeeRequest request) {
        Employee employee = repository.findById(id)
            .orElseThrow(() -> new EmployeeNotFoundException(id));
        employee.setName(request.getName());
        employee.setEmail(request.getEmail());
        employee.setDepartment(request.getDepartment());
        employee.setJobTitle(request.getJobTitle());
        employee.setOfficeLocation(request.getOfficeLocation());
        return toDTO(repository.save(employee));
    }

    public EmployeeDTO patchEmployee(UUID id, UpdateEmployeeRequest request) {
        Employee employee = repository.findById(id)
            .orElseThrow(() -> new EmployeeNotFoundException(id));
        if (request.getName() != null) employee.setName(request.getName());
        if (request.getEmail() != null) employee.setEmail(request.getEmail());
        if (request.getDepartment() != null) employee.setDepartment(request.getDepartment());
        if (request.getJobTitle() != null) employee.setJobTitle(request.getJobTitle());
        if (request.getOfficeLocation() != null) employee.setOfficeLocation(request.getOfficeLocation());
        return toDTO(repository.save(employee));
    }

    public void deleteEmployee(UUID id) {
        if (!repository.existsById(id)) {
            throw new EmployeeNotFoundException(id);
        }
        repository.deleteById(id);
    }

    private EmployeeDTO toDTO(Employee employee) {
        EmployeeDTO dto = new EmployeeDTO();
        dto.setId(employee.getId());
        dto.setName(employee.getName());
        dto.setEmail(employee.getEmail());
        dto.setDepartment(employee.getDepartment());
        dto.setJobTitle(employee.getJobTitle());
        dto.setOfficeLocation(employee.getOfficeLocation());
        return dto;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.gexaenergy.employeedirectory.service.EmployeeServiceTest"
```

Expected: PASS — 5 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gexaenergy/employeedirectory/service/EmployeeService.java \
        src/test/java/com/gexaenergy/employeedirectory/service/EmployeeServiceTest.java
git commit -m "feat: implement EmployeeService with unit tests"
```

---

## Task 7: Rate Limit Infrastructure

**Files:**
- Create: `src/main/java/com/gexaenergy/employeedirectory/service/ratelimit/RateLimitTier.java`
- Create: `src/main/java/com/gexaenergy/employeedirectory/service/ratelimit/RateLimitResult.java`
- Create: `src/main/java/com/gexaenergy/employeedirectory/service/ratelimit/RateLimiterService.java`

- [ ] **Step 1: Implement RateLimitTier enum**

```java
package com.gexaenergy.employeedirectory.service.ratelimit;

public enum RateLimitTier {
    ANONYMOUS(30),
    AUTHENTICATED(100);

    private final int requestsPerMinute;

    RateLimitTier(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }
}
```

- [ ] **Step 2: Implement RateLimitResult record**

```java
package com.gexaenergy.employeedirectory.service.ratelimit;

public record RateLimitResult(boolean consumed, long remainingTokens, long retryAfterSeconds) {
}
```

- [ ] **Step 3: Implement RateLimiterService interface**

```java
package com.gexaenergy.employeedirectory.service.ratelimit;

public interface RateLimiterService {
    RateLimitResult tryConsume(String key, RateLimitTier tier);
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/gexaenergy/employeedirectory/service/ratelimit/
git commit -m "feat: add RateLimitTier, RateLimitResult, RateLimiterService interface"
```

---

## Task 8: InMemoryRateLimiterService (TDD)

**Files:**
- Create: `src/test/java/com/gexaenergy/employeedirectory/service/ratelimit/InMemoryRateLimiterServiceTest.java`
- Create: `src/main/java/com/gexaenergy/employeedirectory/service/ratelimit/InMemoryRateLimiterService.java`

- [ ] **Step 1: Write the failing unit tests**

```java
package com.gexaenergy.employeedirectory.service.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRateLimiterServiceTest {

    private InMemoryRateLimiterService service;

    @BeforeEach
    void setUp() {
        service = new InMemoryRateLimiterService();
    }

    @Test
    void tryConsume_returnsConsumedTrue_whenTokensAvailable() {
        RateLimitResult result = service.tryConsume("test-key", RateLimitTier.ANONYMOUS);

        assertTrue(result.consumed());
        assertEquals(29, result.remainingTokens());
    }

    @Test
    void tryConsume_returnsConsumedFalse_whenBucketExhausted() {
        for (int i = 0; i < 30; i++) {
            service.tryConsume("exhausted-key", RateLimitTier.ANONYMOUS);
        }

        RateLimitResult result = service.tryConsume("exhausted-key", RateLimitTier.ANONYMOUS);

        assertFalse(result.consumed());
        assertEquals(0, result.remainingTokens());
        assertTrue(result.retryAfterSeconds() > 0);
    }

    @Test
    void tryConsume_differentKeys_haveIndependentBuckets() {
        for (int i = 0; i < 30; i++) {
            service.tryConsume("key-a", RateLimitTier.ANONYMOUS);
        }

        RateLimitResult result = service.tryConsume("key-b", RateLimitTier.ANONYMOUS);

        assertTrue(result.consumed());
    }

    @Test
    void tryConsume_authenticated_hasHigherLimit() {
        for (int i = 0; i < 100; i++) {
            RateLimitResult r = service.tryConsume("auth-key", RateLimitTier.AUTHENTICATED);
            assertTrue(r.consumed(), "Should succeed on request " + (i + 1));
        }

        RateLimitResult result = service.tryConsume("auth-key", RateLimitTier.AUTHENTICATED);
        assertFalse(result.consumed());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.gexaenergy.employeedirectory.service.ratelimit.InMemoryRateLimiterServiceTest"
```

Expected: FAIL — class does not exist yet.

- [ ] **Step 3: Implement InMemoryRateLimiterService**

```java
package com.gexaenergy.employeedirectory.service.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class InMemoryRateLimiterService implements RateLimiterService {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitTier tier) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> createBucket(tier));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        long retryAfter = probe.isConsumed()
            ? 0
            : TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
        return new RateLimitResult(probe.isConsumed(), probe.getRemainingTokens(), retryAfter);
    }

    private Bucket createBucket(RateLimitTier tier) {
        Bandwidth limit = Bandwidth.builder()
            .capacity(tier.getRequestsPerMinute())
            .refillGreedy(tier.getRequestsPerMinute(), Duration.ofMinutes(1))
            .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.gexaenergy.employeedirectory.service.ratelimit.InMemoryRateLimiterServiceTest"
```

Expected: PASS — 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gexaenergy/employeedirectory/service/ratelimit/InMemoryRateLimiterService.java \
        src/test/java/com/gexaenergy/employeedirectory/service/ratelimit/InMemoryRateLimiterServiceTest.java
git commit -m "feat: implement InMemoryRateLimiterService with unit tests"
```

---

## Task 9: RateLimitInterceptor (TDD)

**Files:**
- Create: `src/test/java/com/gexaenergy/employeedirectory/interceptor/RateLimitInterceptorTest.java`
- Create: `src/main/java/com/gexaenergy/employeedirectory/interceptor/RateLimitInterceptor.java`

- [ ] **Step 1: Write the failing unit tests**

```java
package com.gexaenergy.employeedirectory.interceptor;

import com.gexaenergy.employeedirectory.service.ratelimit.RateLimitResult;
import com.gexaenergy.employeedirectory.service.ratelimit.RateLimitTier;
import com.gexaenergy.employeedirectory.service.ratelimit.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private RateLimiterService rateLimiterService;

    private RateLimitInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(rateLimiterService, "test-admin-key");
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.setRemoteAddr("127.0.0.1");
    }

    @Test
    void preHandle_allowsRequest_andSetsHeaders_whenTokenAvailable() throws Exception {
        when(rateLimiterService.tryConsume("127.0.0.1", RateLimitTier.ANONYMOUS))
            .thenReturn(new RateLimitResult(true, 29, 0));

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        assertEquals("30", response.getHeader("X-RateLimit-Limit"));
        assertEquals("29", response.getHeader("X-RateLimit-Remaining"));
    }

    @Test
    void preHandle_returns429_whenBucketExhausted() throws Exception {
        when(rateLimiterService.tryConsume("127.0.0.1", RateLimitTier.ANONYMOUS))
            .thenReturn(new RateLimitResult(false, 0, 45));

        boolean result = interceptor.preHandle(request, response, new Object());

        assertFalse(result);
        assertEquals(429, response.getStatus());
        assertEquals("45", response.getHeader("X-RateLimit-Retry-After"));
    }

    @Test
    void preHandle_usesApiKeyAsBucketKey_whenHeaderPresent() throws Exception {
        request.addHeader("X-API-Key", "test-admin-key");
        when(rateLimiterService.tryConsume("test-admin-key", RateLimitTier.AUTHENTICATED))
            .thenReturn(new RateLimitResult(true, 99, 0));

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        assertEquals("100", response.getHeader("X-RateLimit-Limit"));
    }

    @Test
    void preHandle_returns401_whenWriteRequestMissingApiKey() throws Exception {
        request.setMethod("POST");
        when(rateLimiterService.tryConsume("127.0.0.1", RateLimitTier.ANONYMOUS))
            .thenReturn(new RateLimitResult(true, 29, 0));

        boolean result = interceptor.preHandle(request, response, new Object());

        assertFalse(result);
        assertEquals(401, response.getStatus());
    }

    @Test
    void preHandle_returns401_whenWriteRequestHasInvalidApiKey() throws Exception {
        request.setMethod("DELETE");
        request.addHeader("X-API-Key", "wrong-key");
        when(rateLimiterService.tryConsume("wrong-key", RateLimitTier.AUTHENTICATED))
            .thenReturn(new RateLimitResult(true, 99, 0));

        boolean result = interceptor.preHandle(request, response, new Object());

        assertFalse(result);
        assertEquals(401, response.getStatus());
    }

    @Test
    void preHandle_usesForwardedIp_whenXForwardedForPresent() throws Exception {
        request.addHeader("X-Forwarded-For", "203.0.113.42");
        when(rateLimiterService.tryConsume("203.0.113.42", RateLimitTier.ANONYMOUS))
            .thenReturn(new RateLimitResult(true, 29, 0));

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.gexaenergy.employeedirectory.interceptor.RateLimitInterceptorTest"
```

Expected: FAIL — class does not exist yet.

- [ ] **Step 3: Implement RateLimitInterceptor**

```java
package com.gexaenergy.employeedirectory.interceptor;

import com.gexaenergy.employeedirectory.service.ratelimit.RateLimitResult;
import com.gexaenergy.employeedirectory.service.ratelimit.RateLimitTier;
import com.gexaenergy.employeedirectory.service.ratelimit.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

public class RateLimitInterceptor implements HandlerInterceptor {

    private static final List<String> WRITE_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");

    private final RateLimiterService rateLimiterService;
    private final String adminKey;

    public RateLimitInterceptor(RateLimiterService rateLimiterService, String adminKey) {
        this.rateLimiterService = rateLimiterService;
        this.adminKey = adminKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String apiKey = request.getHeader("X-API-Key");
        String bucketKey = apiKey != null ? apiKey : resolveIp(request);
        RateLimitTier tier = apiKey != null ? RateLimitTier.AUTHENTICATED : RateLimitTier.ANONYMOUS;

        RateLimitResult result = rateLimiterService.tryConsume(bucketKey, tier);

        response.setHeader("X-RateLimit-Limit", String.valueOf(tier.getRequestsPerMinute()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remainingTokens()));

        if (!result.consumed()) {
            response.setHeader("X-RateLimit-Retry-After", String.valueOf(result.retryAfterSeconds()));
            writeError(response, 429, "Too Many Requests", "Rate limit exceeded");
            return false;
        }

        if (isWriteMethod(request.getMethod()) && !adminKey.equals(apiKey)) {
            writeError(response, 401, "Unauthorized", "X-API-Key header is required for write operations");
            return false;
        }

        return true;
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }

    private boolean isWriteMethod(String method) {
        return WRITE_METHODS.contains(method.toUpperCase());
    }

    private void writeError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"status\":" + status +
            ",\"error\":\"" + error + "\"" +
            ",\"message\":\"" + message + "\"" +
            ",\"timestamp\":\"" + Instant.now() + "\"}"
        );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.gexaenergy.employeedirectory.interceptor.RateLimitInterceptorTest"
```

Expected: PASS — 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gexaenergy/employeedirectory/interceptor/RateLimitInterceptor.java \
        src/test/java/com/gexaenergy/employeedirectory/interceptor/RateLimitInterceptorTest.java
git commit -m "feat: implement RateLimitInterceptor with unit tests"
```

---

## Task 10: WebMvcConfig

**Files:**
- Modify: `src/main/java/com/gexaenergy/employeedirectory/config/WebMvcConfig.java`

- [ ] **Step 1: Implement WebMvcConfig**

```java
package com.gexaenergy.employeedirectory.config;

import com.gexaenergy.employeedirectory.interceptor.RateLimitInterceptor;
import com.gexaenergy.employeedirectory.service.ratelimit.RateLimiterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimiterService rateLimiterService;

    @Value("${api.admin.key}")
    private String adminKey;

    public WebMvcConfig(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Bean
    public RateLimitInterceptor rateLimitInterceptor() {
        return new RateLimitInterceptor(rateLimiterService, adminKey);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/gexaenergy/employeedirectory/config/WebMvcConfig.java
git commit -m "feat: register RateLimitInterceptor via WebMvcConfig"
```

---

## Task 11: EmployeeController (TDD with MockMvc)

**Files:**
- Create: `src/test/java/com/gexaenergy/employeedirectory/controller/EmployeeControllerTest.java`
- Modify: `src/main/java/com/gexaenergy/employeedirectory/controller/EmployeeController.java`

- [ ] **Step 1: Write the failing MockMvc contract tests**

```java
package com.gexaenergy.employeedirectory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gexaenergy.employeedirectory.dto.CreateEmployeeRequest;
import com.gexaenergy.employeedirectory.dto.EmployeeDTO;
import com.gexaenergy.employeedirectory.exception.EmployeeNotFoundException;
import com.gexaenergy.employeedirectory.service.EmployeeService;
import com.gexaenergy.employeedirectory.service.ratelimit.RateLimitResult;
import com.gexaenergy.employeedirectory.service.ratelimit.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EmployeeController.class)
@TestPropertySource(properties = "api.admin.key=test-key")
class EmployeeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EmployeeService employeeService;

    @MockitoBean
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        lenient().when(rateLimiterService.tryConsume(any(), any()))
            .thenReturn(new RateLimitResult(true, 99, 0));
    }

    private EmployeeDTO sampleDTO() {
        EmployeeDTO dto = new EmployeeDTO();
        dto.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        dto.setName("Alice Smith");
        dto.setDepartment("Engineering");
        dto.setJobTitle("Software Engineer");
        dto.setOfficeLocation("Houston");
        return dto;
    }

    @Test
    void getAllEmployees_returns200_withPagedContent() throws Exception {
        when(employeeService.getAllEmployees(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sampleDTO())));

        mockMvc.perform(get("/api/v1/employees"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].name").value("Alice Smith"))
            .andExpect(jsonPath("$.content[0].department").value("Engineering"))
            .andExpect(jsonPath("$.content[0].createdAt").doesNotExist())
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getEmployee_returns200_whenFound() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(employeeService.getEmployeeById(id)).thenReturn(sampleDTO());

        mockMvc.perform(get("/api/v1/employees/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Alice Smith"))
            .andExpect(jsonPath("$.createdAt").doesNotExist());
    }

    @Test
    void getEmployee_returns404_withErrorEnvelope_whenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(employeeService.getEmployeeById(id)).thenThrow(new EmployeeNotFoundException(id));

        mockMvc.perform(get("/api/v1/employees/{id}", id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void createEmployee_returns201_withValidRequest() throws Exception {
        CreateEmployeeRequest request = new CreateEmployeeRequest();
        request.setName("Bob Jones");
        request.setDepartment("Product");
        request.setJobTitle("Product Manager");
        request.setOfficeLocation("Austin");

        EmployeeDTO dto = new EmployeeDTO();
        dto.setId(UUID.randomUUID());
        dto.setName("Bob Jones");
        dto.setDepartment("Product");
        dto.setJobTitle("Product Manager");
        dto.setOfficeLocation("Austin");

        when(employeeService.createEmployee(any(CreateEmployeeRequest.class))).thenReturn(dto);

        mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", "test-key")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Bob Jones"))
            .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void createEmployee_returns400_whenNameMissing() throws Exception {
        CreateEmployeeRequest request = new CreateEmployeeRequest();
        request.setDepartment("Product");
        request.setJobTitle("Product Manager");
        request.setOfficeLocation("Austin");

        mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", "test-key")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void deleteEmployee_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(employeeService).deleteEmployee(id);

        mockMvc.perform(delete("/api/v1/employees/{id}", id)
                .header("X-API-Key", "test-key"))
            .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.gexaenergy.employeedirectory.controller.EmployeeControllerTest"
```

Expected: FAIL — `EmployeeController` has no implementation yet.

- [ ] **Step 3: Implement EmployeeController**

```java
package com.gexaenergy.employeedirectory.controller;

import com.gexaenergy.employeedirectory.dto.CreateEmployeeRequest;
import com.gexaenergy.employeedirectory.dto.EmployeeDTO;
import com.gexaenergy.employeedirectory.dto.UpdateEmployeeRequest;
import com.gexaenergy.employeedirectory.service.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Page<EmployeeDTO>> getAllEmployees(Pageable pageable) {
        return ResponseEntity.ok(service.getAllEmployees(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeDTO> getEmployee(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getEmployeeById(id));
    }

    @PostMapping
    public ResponseEntity<EmployeeDTO> createEmployee(@RequestBody @Valid CreateEmployeeRequest request) {
        return ResponseEntity.status(201).body(service.createEmployee(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmployeeDTO> updateEmployee(
            @PathVariable UUID id,
            @RequestBody UpdateEmployeeRequest request) {
        return ResponseEntity.ok(service.updateEmployee(id, request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<EmployeeDTO> patchEmployee(
            @PathVariable UUID id,
            @RequestBody UpdateEmployeeRequest request) {
        return ResponseEntity.ok(service.patchEmployee(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEmployee(@PathVariable UUID id) {
        service.deleteEmployee(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.gexaenergy.employeedirectory.controller.EmployeeControllerTest"
```

Expected: PASS — 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gexaenergy/employeedirectory/controller/EmployeeController.java \
        src/test/java/com/gexaenergy/employeedirectory/controller/EmployeeControllerTest.java
git commit -m "feat: implement EmployeeController with MockMvc contract tests"
```

---

## Task 12: Integration Tests (Testcontainers)

**Files:**
- Create: `src/test/java/com/gexaenergy/employeedirectory/EmployeeIntegrationTest.java`

**Prerequisite:** Docker must be running on your machine for Testcontainers to start a PostgreSQL container.

- [ ] **Step 1: Write the integration tests**

```java
package com.gexaenergy.employeedirectory;

import com.gexaenergy.employeedirectory.dto.CreateEmployeeRequest;
import com.gexaenergy.employeedirectory.dto.EmployeeDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EmployeeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("employee_directory_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("api.admin.key", () -> "test-admin-key");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "test-admin-key");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void createEmployee_thenRetrieveById() {
        CreateEmployeeRequest request = new CreateEmployeeRequest();
        request.setName("Jane Smith");
        request.setDepartment("Product");
        request.setJobTitle("Product Manager");
        request.setOfficeLocation("Austin");

        ResponseEntity<EmployeeDTO> createResponse = restTemplate.postForEntity(
            "/api/v1/employees",
            new HttpEntity<>(request, adminHeaders()),
            EmployeeDTO.class);

        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
        assertNotNull(createResponse.getBody());
        UUID id = createResponse.getBody().getId();
        assertNotNull(id);

        ResponseEntity<EmployeeDTO> getResponse = restTemplate.getForEntity(
            "/api/v1/employees/" + id, EmployeeDTO.class);

        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
        assertEquals("Jane Smith", getResponse.getBody().getName());
        assertEquals("Product", getResponse.getBody().getDepartment());
        assertNotNull(getResponse.getBody().getId());
    }

    @Test
    void getEmployee_returns404_whenNotFound() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/api/v1/employees/00000000-0000-0000-0000-000000000099", String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void createEmployee_returns401_whenNoApiKey() {
        CreateEmployeeRequest request = new CreateEmployeeRequest();
        request.setName("Jane Smith");
        request.setDepartment("Product");
        request.setJobTitle("Product Manager");
        request.setOfficeLocation("Austin");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/v1/employees",
            new HttpEntity<>(request, headers),
            String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @DirtiesContext
    void rateLimitBlocks_afterAnonymousLimitExceeded() {
        for (int i = 0; i < 30; i++) {
            restTemplate.getForEntity("/api/v1/employees", String.class);
        }

        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/employees", String.class);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
    }
}
```

- [ ] **Step 2: Run integration tests (Docker must be running)**

```bash
./gradlew test --tests "com.gexaenergy.employeedirectory.EmployeeIntegrationTest"
```

Expected: PASS — 4 tests passing. Testcontainers will pull `postgres:16` on first run (takes ~30 seconds). Subsequent runs use the cached image.

- [ ] **Step 3: Run the full test suite to confirm nothing is broken**

```bash
./gradlew test
```

Expected: All tests pass. Output shows test counts for each class.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/gexaenergy/employeedirectory/EmployeeIntegrationTest.java
git commit -m "feat: add Testcontainers integration tests"
```

---

## Task 13: Final Verification

- [ ] **Step 1: Run the full build**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL` with all tests passing.

- [ ] **Step 2: Start a local PostgreSQL database and run the app**

```bash
# Create the database if it doesn't exist
createdb employee_directory

# Start the application
./gradlew bootRun
```

Expected: Application starts on port 8080. No errors in console. `spring.jpa.hibernate.ddl-auto=create-drop` creates the `employees` table automatically.

- [ ] **Step 3: Smoke test the API**

```bash
# List employees (empty at start)
curl http://localhost:8080/api/v1/employees

# Create an employee
curl -X POST http://localhost:8080/api/v1/employees \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dev-admin-key-change-in-production" \
  -d '{"name":"Marcus Lin","department":"Engineering","jobTitle":"Software Engineer","officeLocation":"Houston"}'

# List again — should show the created employee
curl http://localhost:8080/api/v1/employees

# Verify rate limiting — fire 31 requests and confirm the last returns 429
for i in $(seq 1 31); do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/employees; done
```

Expected: First 30 requests return `200`. Request 31 returns `429`.

- [ ] **Step 4: Push to GitHub**

```bash
git push origin main
```
