# Acceso con usuarios y monitoreo remoto — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Proteger el dashboard con cuentas por persona y hacer que el botón "Monitorear Ahora" dispare el monitoreo en GitHub Actions, mostrando su avance real.

**Architecture:** Dos fases secuenciales. La fase 1 añade entidades de usuario, hash BCrypt, un filtro de sesión y pantallas de acceso y administración. La fase 2 añade un publicador de progreso que escribe en Supabase, un cliente de la API de GitHub que dispara el workflow, y una vista de estado en vivo que combina ambos.

**Tech Stack:** Java 21, Spring Boot 3.4.2, Thymeleaf, Spring Data JPA, H2 (pruebas), PostgreSQL/Supabase (producción), `spring-security-crypto` (solo BCrypt), `RestClient` para la API de GitHub.

**Spec:** `docs/superpowers/specs/2026-10-27-remote-monitoring-trigger-design.md`

## Global Constraints

- Java 21 y Spring Boot 3.4.2. No cambiar la versión del padre.
- **No** agregar `spring-boot-starter-security`. Solo `org.springframework.security:spring-security-crypto`, cuya versión gestiona el BOM del padre.
- Las contraseñas se guardan únicamente como hash BCrypt. Nunca en claro, nunca en logs, nunca en el repositorio.
- La suite debe seguir en verde: hoy son 94 pruebas, 0 fallos, 3 omitidas.
- En Windows los comandos usan `.\mvnw.cmd`; en CI Linux, `./mvnw`.
- Zona horaria de la aplicación: `America/Lima` vía `app.timezone`.
- Pool de conexiones a Supabase: máximo 3 por proceso. No abrir conexiones nuevas por request.
- `spring.jpa.hibernate.ddl-auto=update` en producción; `create-drop` solo con H2.
- Ningún servicio de pago ni tarjeta bancaria.

## Review Focus

Los cinco modos de fallo que el spec implica y que más daño harían:

1. **Redirección abierta** en `POST /login`: un `redirect=https://sitio-malicioso` debe ser rechazado, no seguido.
2. **Bloqueo del último administrador**: nadie debe poder desactivar, eliminar o degradar al último `ADMIN` activo, ni a sí mismo.
3. **Fijación de sesión**: tras un login exitoso el identificador de sesión debe cambiar, para que una cookie robada antes del login no sirva después.
4. **Nombres de usuario ambiguos**: `Admin`, ` admin ` y `admin` deben ser el mismo usuario; de lo contrario se crean cuentas duplicadas que confunden la auditoría.
5. **Arranque simultáneo**: dos instancias creando el primer administrador a la vez no deben producir dos administradores ni tumbar el arranque.

---

## Fase 1 — Acceso y usuarios

### Task 1: Entidad de usuario y repositorio

**Files:**
- Create: `src/main/java/com/shiparrival/domain/UserRole.java`
- Create: `src/main/java/com/shiparrival/domain/AppUser.java`
- Create: `src/main/java/com/shiparrival/repository/AppUserRepository.java`
- Test: `src/test/java/com/shiparrival/repository/AppUserRepositoryTest.java`

**Interfaces:**
- Produces: `enum UserRole { ADMIN, USER }`
- Produces: `AppUser` con campos `Long id`, `String username`, `String passwordHash`, `UserRole role`, `boolean enabled`, `LocalDateTime createdAt`, `LocalDateTime lastLoginAt`
- Produces: `AppUserRepository extends JpaRepository<AppUser, Long>` con `Optional<AppUser> findByUsernameIgnoreCase(String username)`, `boolean existsByUsernameIgnoreCase(String username)`, `long countByRoleAndEnabledTrue(UserRole role)`

- [ ] **Step 1: Write the failing test**

```java
@DataJpaTest
class AppUserRepositoryTest {

    @Autowired private AppUserRepository repository;

    @Test
    @DisplayName("Guarda un usuario y lo recupera sin distinguir mayúsculas")
    void shouldFindByUsernameIgnoringCase() {
        repository.save(AppUser.builder()
                .username("admin").passwordHash("hash").role(UserRole.ADMIN).enabled(true).build());

        assertThat(repository.findByUsernameIgnoreCase("ADMIN")).isPresent();
        assertThat(repository.findByUsernameIgnoreCase("Admin")).isPresent();
    }

    @Test
    @DisplayName("El nombre de usuario es único")
    void shouldRejectDuplicateUsername() {
        repository.saveAndFlush(AppUser.builder()
                .username("admin").passwordHash("hash").role(UserRole.ADMIN).enabled(true).build());

        assertThatThrownBy(() -> repository.saveAndFlush(AppUser.builder()
                .username("admin").passwordHash("otro").role(UserRole.USER).enabled(true).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Cuenta solo administradores activos")
    void shouldCountEnabledAdmins() {
        repository.save(AppUser.builder()
                .username("a1").passwordHash("h").role(UserRole.ADMIN).enabled(true).build());
        repository.save(AppUser.builder()
                .username("a2").passwordHash("h").role(UserRole.ADMIN).enabled(false).build());
        repository.save(AppUser.builder()
                .username("u1").passwordHash("h").role(UserRole.USER).enabled(true).build());

        assertThat(repository.countByRoleAndEnabledTrue(UserRole.ADMIN)).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=AppUserRepositoryTest`
Expected: FAIL — no compila, `AppUser` no existe.

- [ ] **Step 3: Implementar**

`AppUser`: `@Entity @Table(name = "app_users")`, `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`, `id` con `@GeneratedValue(strategy = GenerationType.IDENTITY)`, `username` con `@Column(nullable = false, unique = true, length = 32)`, `passwordHash` con `@Column(nullable = false, length = 100)`, `role` con `@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)`, `enabled` con `@Builder.Default true`, `createdAt` con `@PrePersist`.

`AppUserRepository`: extiende `JpaRepository` y declara los tres métodos de arriba.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=AppUserRepositoryTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/shiparrival/domain/UserRole.java src/main/java/com/shiparrival/domain/AppUser.java src/main/java/com/shiparrival/repository/AppUserRepository.java src/test/java/com/shiparrival/repository/AppUserRepositoryTest.java
git commit -m "feat: add AppUser entity and repository"
```

---

### Task 2: Hash de contraseñas y servicio de usuarios

**Files:**
- Modify: `pom.xml` (añadir `spring-security-crypto`)
- Create: `src/main/java/com/shiparrival/config/PasswordEncoderConfig.java`
- Create: `src/main/java/com/shiparrival/service/UserService.java`
- Test: `src/test/java/com/shiparrival/service/UserServiceTest.java`

**Interfaces:**
- Consumes: `AppUser`, `UserRole`, `AppUserRepository` (Task 1)
- Produces: bean `PasswordEncoder passwordEncoder()` que devuelve `BCryptPasswordEncoder`
- Produces: `UserService` con
  - `AppUser createUser(String username, String rawPassword, UserRole role)`
  - `Optional<AppUser> authenticate(String username, String rawPassword)`
  - `void resetPassword(Long actingUserId, Long targetId, String rawPassword)`
  - `void setEnabled(Long actingUserId, Long targetId, boolean enabled)`
  - `void changeRole(Long actingUserId, Long targetId, UserRole role)`
  - `void deleteUser(Long actingUserId, Long targetId)`
  - `List<AppUser> findAll()`
  - `Optional<AppUser> findById(Long id)`
  - `void touchLastLogin(Long id)`

Reglas: el nombre se normaliza con `trim().toLowerCase(Locale.ROOT)`; debe medir entre 3 y 32 caracteres y coincidir con `[a-z0-9._-]+`. La contraseña debe tener al menos 8 caracteres. Las violaciones lanzan `IllegalArgumentException`; los bloqueos de seguridad lanzan `IllegalStateException`.

- [ ] **Step 1: Write the failing test**

```java
class UserServiceTest {

    private AppUserRepository repository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        repository = mock(AppUserRepository.class);
        when(repository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        userService = new UserService(repository, new BCryptPasswordEncoder(10));
    }

    @Test
    @DisplayName("La contraseña se guarda como hash BCrypt, nunca en claro")
    void shouldStoreHashedPassword() {
        AppUser created = userService.createUser("admin", "claveSegura1", UserRole.ADMIN);

        assertThat(created.getPasswordHash()).isNotEqualTo("claveSegura1");
        assertThat(new BCryptPasswordEncoder().matches("claveSegura1", created.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("El nombre de usuario se normaliza a minúsculas y sin espacios")
    void shouldNormalizeUsername() {
        assertThat(userService.createUser("  Admin ", "claveSegura1", UserRole.ADMIN).getUsername())
                .isEqualTo("admin");
    }

    @Test
    @DisplayName("Rechaza contraseñas de menos de 8 caracteres")
    void shouldRejectShortPassword() {
        assertThatThrownBy(() -> userService.createUser("admin", "corta", UserRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8");
    }

    @Test
    @DisplayName("Rechaza nombres de usuario duplicados")
    void shouldRejectDuplicateUsername() {
        when(repository.existsByUsernameIgnoreCase("admin")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser("admin", "claveSegura1", UserRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Autentica con la contraseña correcta y rechaza la incorrecta")
    void shouldAuthenticate() {
        AppUser stored = userService.createUser("admin", "claveSegura1", UserRole.ADMIN);
        when(repository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(stored));

        assertThat(userService.authenticate("admin", "claveSegura1")).isPresent();
        assertThat(userService.authenticate("admin", "incorrecta")).isEmpty();
    }

    @Test
    @DisplayName("Un usuario desactivado no puede autenticarse")
    void shouldRejectDisabledUser() {
        AppUser stored = userService.createUser("admin", "claveSegura1", UserRole.ADMIN);
        stored.setEnabled(false);
        when(repository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(stored));

        assertThat(userService.authenticate("admin", "claveSegura1")).isEmpty();
    }

    @Test
    @DisplayName("Nadie puede desactivarse ni eliminarse a sí mismo")
    void shouldRejectSelfLockout() {
        assertThatThrownBy(() -> userService.setEnabled(7L, 7L, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> userService.deleteUser(7L, 7L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("No se puede degradar al último administrador activo")
    void shouldRejectDemotingLastAdmin() {
        AppUser lastAdmin = AppUser.builder()
                .id(1L).username("admin").passwordHash("h").role(UserRole.ADMIN).enabled(true).build();
        when(repository.findById(1L)).thenReturn(Optional.of(lastAdmin));
        when(repository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> userService.changeRole(2L, 1L, UserRole.USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("administrador");
    }

    @Test
    @DisplayName("Restablecer la contraseña cambia el hash")
    void shouldResetPassword() {
        AppUser stored = AppUser.builder()
                .id(1L).username("admin").passwordHash("viejo").role(UserRole.ADMIN).enabled(true).build();
        when(repository.findById(1L)).thenReturn(Optional.of(stored));

        userService.resetPassword(2L, 1L, "nuevaClave1");

        assertThat(new BCryptPasswordEncoder().matches("nuevaClave1", stored.getPasswordHash())).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=UserServiceTest`
Expected: FAIL — `UserService` no existe.

- [ ] **Step 3: Implementar**

Añadir al `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

`PasswordEncoderConfig`: `@Configuration` con `@Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(10); }`.

`UserService`: `@Service` con inyección por constructor de `AppUserRepository` y `PasswordEncoder`. La comprobación del último administrador cuenta `countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1` y el usuario objetivo es `ADMIN`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=UserServiceTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/com/shiparrival/config/PasswordEncoderConfig.java src/main/java/com/shiparrival/service/UserService.java src/test/java/com/shiparrival/service/UserServiceTest.java
git commit -m "feat: add BCrypt password hashing and user service"
```

---

### Task 3: Freno a la fuerza bruta

**Files:**
- Create: `src/main/java/com/shiparrival/service/LoginAttemptService.java`
- Test: `src/test/java/com/shiparrival/service/LoginAttemptServiceTest.java`

**Interfaces:**
- Produces: `LoginAttemptService` con
  - `boolean isBlocked(String username)`
  - `long remainingLockSeconds(String username)`
  - `void recordFailure(String username)`
  - `void recordSuccess(String username)`
  - constructor `LoginAttemptService(Clock clock)` y constructor sin argumentos que use `Clock.systemDefaultZone()`

Constantes públicas: `MAX_ATTEMPTS = 5`, `LOCK_MINUTES = 15`.

- [ ] **Step 1: Write the failing test**

```java
class LoginAttemptServiceTest {

    private MutableClock clock;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-27T12:00:00Z"), ZoneId.of("America/Lima"));
        service = new LoginAttemptService(clock);
    }

    @Test
    @DisplayName("Cinco fallos bloquean la cuenta durante 15 minutos")
    void shouldBlockAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("admin");
        }

        assertThat(service.isBlocked("admin")).isTrue();
        assertThat(service.remainingLockSeconds("admin")).isEqualTo(900);
    }

    @Test
    @DisplayName("El bloqueo expira pasados los 15 minutos")
    void shouldExpireLock() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("admin");
        }

        clock.advance(Duration.ofMinutes(16));

        assertThat(service.isBlocked("admin")).isFalse();
    }

    @Test
    @DisplayName("Un login exitoso reinicia el contador")
    void shouldResetOnSuccess() {
        service.recordFailure("admin");
        service.recordFailure("admin");
        service.recordSuccess("admin");

        for (int i = 0; i < 4; i++) {
            service.recordFailure("admin");
        }

        assertThat(service.isBlocked("admin")).isFalse();
    }

    @Test
    @DisplayName("El bloqueo es por usuario, no global")
    void shouldIsolateUsers() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("admin");
        }

        assertThat(service.isBlocked("otro")).isFalse();
    }
}
```

`MutableClock` es una clase auxiliar de prueba que extiende `java.time.Clock`, guarda un `Instant` mutable y ofrece `advance(Duration)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=LoginAttemptServiceTest`
Expected: FAIL — `LoginAttemptService` no existe.

- [ ] **Step 3: Implementar**

Estado en `ConcurrentHashMap<String, Attempt>` con un `record Attempt(int failures, Instant lockedUntil)`. `recordFailure` incrementa; al llegar a `MAX_ATTEMPTS` fija `lockedUntil = now + 15 min` y reinicia el contador. `isBlocked` compara con `clock.instant()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=LoginAttemptServiceTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/shiparrival/service/LoginAttemptService.java src/test/java/com/shiparrival/service/LoginAttemptServiceTest.java
git commit -m "feat: add login attempt throttling"
```

---

### Task 4: Filtro de sesión

**Files:**
- Create: `src/main/java/com/shiparrival/web/CurrentUser.java`
- Create: `src/main/java/com/shiparrival/web/AuthFilter.java`
- Create: `src/main/java/com/shiparrival/config/AuthWebConfig.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`
- Test: `src/test/java/com/shiparrival/web/AuthFilterIntegrationTest.java`

**Interfaces:**
- Consumes: `UserService`, `AppUser`, `UserRole` (Tasks 1–2)
- Produces: `record CurrentUser(Long id, String username, UserRole role)` con `boolean isAdmin()`
- Produces: `AuthFilter.SESSION_USER_ID = "auth.userId"` y `AuthFilter.CURRENT_USER_ATTRIBUTE = "currentUser"`
- Produces: `CurrentUserModelAdvice` que expone `${currentUser}` a Thymeleaf

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:authfilter;DB_CLOSE_DELAY=-1"
})
class AuthFilterIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository repository;

    @Test
    @DisplayName("Sin sesión, la raíz redirige al login")
    void shouldRedirectAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login*"));
    }

    @Test
    @DisplayName("La pantalla de login y los recursos estáticos son públicos")
    void shouldAllowPublicPaths() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk());
        mockMvc.perform(get("/css/dashboard.css")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Con sesión válida se accede al dashboard")
    void shouldAllowAuthenticatedUser() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("ana").passwordHash("h").role(UserRole.USER).enabled(true).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isOk())
                .andExpect(request().attribute(AuthFilter.CURRENT_USER_ATTRIBUTE,
                        new CurrentUser(user.getId(), "ana", UserRole.USER)));
    }

    @Test
    @DisplayName("Un usuario desactivado pierde el acceso aunque tenga sesión")
    void shouldRejectDisabledUserWithSession() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("beto").passwordHash("h").role(UserRole.USER).enabled(false).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login*"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=AuthFilterIntegrationTest`
Expected: FAIL — sin filtro, `GET /` responde 200 y el primer test falla.

- [ ] **Step 3: Implementar**

`AuthFilter extends OncePerRequestFilter`: si `app.auth.enabled` es `false` continúa sin filtrar. Rutas públicas: `/login`, `/css/`, `/js/`, `/favicon.ico`, `/error`. Si hay `SESSION_USER_ID` y el usuario existe y está `enabled`, publica el atributo `currentUser` y continúa. En cualquier otro caso invalida la sesión y responde `sendRedirect(contextPath + "/login")`.

`AuthWebConfig` registra el filtro con `FilterRegistrationBean<AuthFilter>` y orden `Ordered.HIGHEST_PRECEDENCE + 10`.

`CurrentUserModelAdvice`: `@ControllerAdvice` con `@ModelAttribute("currentUser") CurrentUser currentUser(@RequestAttribute(value = AuthFilter.CURRENT_USER_ATTRIBUTE, required = false) CurrentUser user)`. Sin esto, `${currentUser}` no resuelve en Thymeleaf aunque el atributo esté en la petición.

`application.properties`:

```properties
# Autenticación del dashboard. Se desactiva solo en pruebas.
app.auth.enabled=${APP_AUTH_ENABLED:true}

# Sesión de 12 horas. Es un panel de operaciones, no una red social.
server.servlet.session.timeout=12h
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=lax
```

`src/test/resources/application.properties`:

```properties
app.auth.enabled=false
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=AuthFilterIntegrationTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Verify existing suite still passes**

Run: `.\mvnw.cmd test`
Expected: la suite completa sigue en verde; el filtro está desactivado por defecto en pruebas.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/shiparrival/web/CurrentUser.java src/main/java/com/shiparrival/web/AuthFilter.java src/main/java/com/shiparrival/config/AuthWebConfig.java src/main/resources/application.properties src/test/resources/application.properties src/test/java/com/shiparrival/web/AuthFilterIntegrationTest.java
git commit -m "feat: add session authentication filter"
```

---

### Task 5: Pantalla de login y logout

**Files:**
- Create: `src/main/java/com/shiparrival/web/AuthController.java`
- Create: `src/main/resources/templates/login.html`
- Test: `src/test/java/com/shiparrival/web/AuthControllerTest.java`

**Interfaces:**
- Consumes: `UserService` (Task 2), `LoginAttemptService` (Task 3), `AuthFilter` (Task 4)
- Produces: rutas `GET /login`, `POST /login` (parámetros `username`, `password`, `redirect`), `POST /logout`

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:authcontroller;DB_CLOSE_DELAY=-1"
})
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository repository;
    @Autowired private PasswordEncoder passwordEncoder;

    private void givenUser(String username, String rawPassword, UserRole role) {
        repository.save(AppUser.builder()
                .username(username).passwordHash(passwordEncoder.encode(rawPassword))
                .role(role).enabled(true).build());
    }

    @Test
    @DisplayName("Credenciales correctas inician sesión y redirigen a la raíz")
    void shouldLoginSuccessfully() throws Exception {
        givenUser("ana", "claveSegura1", UserRole.USER);

        mockMvc.perform(post("/login")
                        .param("username", "ana")
                        .param("password", "claveSegura1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(request().sessionAttribute(AuthFilter.SESSION_USER_ID, notNullValue()));
    }

    @Test
    @DisplayName("Credenciales incorrectas vuelven al login con mensaje genérico")
    void shouldRejectWrongPassword() throws Exception {
        givenUser("ana", "claveSegura1", UserRole.USER);

        mockMvc.perform(post("/login")
                        .param("username", "ana")
                        .param("password", "incorrecta"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("errorMessage",
                        "Usuario o contraseña incorrectos."));
    }

    @Test
    @DisplayName("Tras 5 fallos, el login queda bloqueado")
    void shouldBlockAfterFiveFailures() throws Exception {
        givenUser("ana", "claveSegura1", UserRole.USER);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/login")
                    .param("username", "ana")
                    .param("password", "incorrecta"));
        }

        mockMvc.perform(post("/login")
                        .param("username", "ana")
                        .param("password", "claveSegura1"))
                .andExpect(view().name("login"))
                .andExpect(model().attribute("errorMessage",
                        "Demasiados intentos fallidos. Espera 15 minutos."));
    }

    @Test
    @DisplayName("Rechaza redirecciones a sitios externos")
    void shouldRejectOpenRedirect() throws Exception {
        givenUser("ana", "claveSegura1", UserRole.USER);

        mockMvc.perform(post("/login")
                        .param("username", "ana")
                        .param("password", "claveSegura1")
                        .param("redirect", "https://sitio-malicioso.example"))
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(post("/login")
                        .param("username", "ana")
                        .param("password", "claveSegura1")
                        .param("redirect", "//sitio-malicioso.example"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("El identificador de sesión cambia tras iniciar sesión")
    void shouldChangeSessionIdOnLogin() throws Exception {
        givenUser("ana", "claveSegura1", UserRole.USER);

        MockHttpSession session = new MockHttpSession();
        String before = session.getId();

        mockMvc.perform(post("/login").session(session)
                        .param("username", "ana")
                        .param("password", "claveSegura1"))
                .andExpect(status().is3xxRedirection());

        assertThat(session.getId()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("Cerrar sesión invalida la sesión y vuelve al login")
    void shouldLogout() throws Exception {
        givenUser("ana", "claveSegura1", UserRole.USER);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthFilter.SESSION_USER_ID, 1L);

        mockMvc.perform(post("/logout").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        assertThat(session.isInvalid()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=AuthControllerTest`
Expected: FAIL — `/login` no existe.

- [ ] **Step 3: Implementar**

`AuthController`: `GET /login` devuelve `login`. `POST /login` consulta `loginAttemptService.isBlocked`; si está bloqueado devuelve `login` con el mensaje de bloqueo. Si no, `userService.authenticate`; en éxito llama `userService.touchLastLogin`, guarda `SESSION_USER_ID`, ejecuta `request.changeSessionId()`, `loginAttemptService.recordSuccess` y redirige al destino seguro. En fallo, `recordFailure` y devuelve `login` con "Usuario o contraseña incorrectos.".

Destino seguro: solo se acepta si `redirect` no es nulo, empieza por `/`, no empieza por `//` y no contiene `:` antes del primer `/`.

`login.html`: formulario Bootstrap con campos `username`, `password`, campo oculto `redirect` y bloque de error.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=AuthControllerTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/shiparrival/web/AuthController.java src/main/resources/templates/login.html src/test/java/com/shiparrival/web/AuthControllerTest.java
git commit -m "feat: add login and logout screens"
```

---

### Task 6: Administración de usuarios

**Files:**
- Create: `src/main/java/com/shiparrival/web/UserAdminController.java`
- Create: `src/main/resources/templates/users.html`
- Test: `src/test/java/com/shiparrival/web/UserAdminControllerTest.java`

**Interfaces:**
- Consumes: `CurrentUser` (Task 4), `UserService` (Task 2)
- Produces: rutas `GET /usuarios`, `POST /usuarios`, `POST /usuarios/{id}/role`, `POST /usuarios/{id}/enabled`, `POST /usuarios/{id}/password`, `POST /usuarios/{id}/delete`

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:useradmin;DB_CLOSE_DELAY=-1"
})
class UserAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository repository;
    @Autowired private PasswordEncoder passwordEncoder;

    private AppUser givenUser(String username, UserRole role) {
        return repository.save(AppUser.builder()
                .username(username).passwordHash(passwordEncoder.encode("claveSegura1"))
                .role(role).enabled(true).build());
    }

    @Test
    @DisplayName("Un administrador ve la lista de usuarios")
    void shouldAllowAdmin() throws Exception {
        AppUser admin = givenUser("admin", UserRole.ADMIN);

        mockMvc.perform(get("/usuarios").sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("users"))
                .andExpect(model().attributeExists("users"));
    }

    @Test
    @DisplayName("Un usuario normal recibe 403")
    void shouldForbidRegularUser() throws Exception {
        AppUser user = givenUser("ana", UserRole.USER);

        mockMvc.perform(get("/usuarios").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Un administrador crea otro usuario")
    void shouldCreateUser() throws Exception {
        AppUser admin = givenUser("admin", UserRole.ADMIN);

        mockMvc.perform(post("/usuarios")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId())
                        .param("username", "nuevo")
                        .param("password", "claveSegura1")
                        .param("role", "USER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/usuarios"));

        assertThat(repository.findByUsernameIgnoreCase("nuevo")).isPresent();
    }

    @Test
    @DisplayName("Un administrador no puede desactivarse a sí mismo")
    void shouldRejectSelfDisable() throws Exception {
        AppUser admin = givenUser("admin", UserRole.ADMIN);

        mockMvc.perform(post("/usuarios/" + admin.getId() + "/enabled")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId())
                        .param("enabled", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(repository.findById(admin.getId()).orElseThrow().isEnabled()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=UserAdminControllerTest`
Expected: FAIL — `/usuarios` no existe.

- [ ] **Step 3: Implementar**

Cada handler recibe `@RequestAttribute(AuthFilter.CURRENT_USER_ATTRIBUTE) CurrentUser currentUser`; si `!currentUser.isAdmin()` lanza `ResponseStatusException(HttpStatus.FORBIDDEN)`. Los errores de `UserService` se convierten en `flash().addFlashAttribute("errorMessage", ex.getMessage())` y se redirige a `/usuarios`.

`users.html`: tabla con usuario, rol, estado, último acceso y acciones; formulario de alta; botones de cambiar rol, activar/desactivar, restablecer contraseña y eliminar.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=UserAdminControllerTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/shiparrival/web/UserAdminController.java src/main/resources/templates/users.html src/test/java/com/shiparrival/web/UserAdminControllerTest.java
git commit -m "feat: add user administration screen"
```

---

### Task 7: Primer administrador desde variables de entorno

**Files:**
- Create: `src/main/java/com/shiparrival/config/AdminBootstrap.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/shiparrival/config/AdminBootstrapTest.java`

**Interfaces:**
- Consumes: `UserService` (Task 2), `AppUserRepository` (Task 1)
- Produces: `AdminBootstrap` que implementa `ApplicationRunner` y lee `app.admin.username` / `app.admin.password`

- [ ] **Step 1: Write the failing test**

```java
class AdminBootstrapTest {

    private AppUserRepository repository;
    private UserService userService;
    private AdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        repository = mock(AppUserRepository.class);
        userService = mock(UserService.class);
    }

    @Test
    @DisplayName("Crea el administrador cuando no hay usuarios")
    void shouldCreateFirstAdmin() {
        when(repository.count()).thenReturn(0L);
        bootstrap = new AdminBootstrap(repository, userService, "admin", "claveSegura1");

        bootstrap.run(null);

        verify(userService).createUser("admin", "claveSegura1", UserRole.ADMIN);
    }

    @Test
    @DisplayName("No crea nada si ya existen usuarios")
    void shouldSkipWhenUsersExist() {
        when(repository.count()).thenReturn(3L);
        bootstrap = new AdminBootstrap(repository, userService, "admin", "claveSegura1");

        bootstrap.run(null);

        verify(userService, never()).createUser(any(), any(), any());
    }

    @Test
    @DisplayName("No falla si faltan las credenciales iniciales")
    void shouldSkipWhenCredentialsMissing() {
        when(repository.count()).thenReturn(0L);
        bootstrap = new AdminBootstrap(repository, userService, "", "");

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
        verify(userService, never()).createUser(any(), any(), any());
    }

    @Test
    @DisplayName("Dos arranques simultáneos no tumban la aplicación")
    void shouldSurviveConcurrentCreation() {
        when(repository.count()).thenReturn(0L);
        when(userService.createUser(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        bootstrap = new AdminBootstrap(repository, userService, "admin", "claveSegura1");

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=AdminBootstrapTest`
Expected: FAIL — `AdminBootstrap` no existe.

- [ ] **Step 3: Implementar**

`AdminBootstrap` con `@Component`; constructor con `@Value("${app.admin.username:}")` y `@Value("${app.admin.password:}")`. En `run`, si `repository.count() > 0` retorna. Si username o password están en blanco, registra `log.warn` y retorna. Si no, `userService.createUser(...)` dentro de `try/catch (DataIntegrityViolationException | IllegalArgumentException)` registrando `log.warn`. Nunca registrar la contraseña.

`application.properties`:

```properties
# Credenciales del primer administrador. Solo se usan cuando la tabla está vacía.
app.admin.username=${ADMIN_USERNAME:}
app.admin.password=${ADMIN_PASSWORD:}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=AdminBootstrapTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/shiparrival/config/AdminBootstrap.java src/main/resources/application.properties src/test/java/com/shiparrival/config/AdminBootstrapTest.java
git commit -m "feat: bootstrap first administrator from environment"
```

---

### Task 8: Integrar el usuario en la interfaz

**Files:**
- Modify: `src/main/resources/templates/dashboard.html`
- Modify: `src/main/resources/templates/history.html`
- Test: `src/test/java/com/shiparrival/web/DashboardUserRenderingTest.java`

**Interfaces:**
- Consumes: `CurrentUser` (Task 4), `AuthFilter` (Task 4)

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:dashboarduser;DB_CLOSE_DELAY=-1"
})
class DashboardUserRenderingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository repository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("El dashboard muestra el usuario y el botón de salir")
    void shouldShowUserAndLogout() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("ana").passwordHash(passwordEncoder.encode("claveSegura1"))
                .role(UserRole.USER).enabled(true).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ana")))
                .andExpect(content().string(containsString("Salir")));
    }

    @Test
    @DisplayName("Solo el administrador ve el enlace de usuarios y el botón de monitoreo")
    void shouldHideAdminActionsFromRegularUser() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("ana").passwordHash(passwordEncoder.encode("claveSegura1"))
                .role(UserRole.USER).enabled(true).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(content().string(not(containsString("href=\"/usuarios\""))))
                .andExpect(content().string(not(containsString("track-now"))));
    }

    @Test
    @DisplayName("El administrador ve el enlace de usuarios")
    void shouldShowAdminLink() throws Exception {
        AppUser admin = repository.save(AppUser.builder()
                .username("root").passwordHash(passwordEncoder.encode("claveSegura1"))
                .role(UserRole.ADMIN).enabled(true).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(content().string(containsString("href=\"/usuarios\"")))
                .andExpect(content().string(containsString("track-now")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=DashboardUserRenderingTest`
Expected: FAIL — la plantilla no muestra usuario ni condiciona el botón.

- [ ] **Step 3: Implementar**

En `dashboard.html` y `history.html`, dentro de la barra de navegación:

- Añadir `xmlns:sec` no es necesario; usar `th:if="${currentUser != null}"`.
- Mostrar `th:text="${currentUser.username}"`.
- Enlace `th:if="${currentUser != null and currentUser.isAdmin()}" th:href="@{/usuarios}"` con texto "Usuarios".
- Formulario `th:action="@{/logout}" method="post"` con botón "Salir".
- Envolver el formulario `trackNowForm` en `th:if="${currentUser != null and currentUser.isAdmin()}"`.

`CurrentUser` debe exponer `boolean isAdmin()`. En la plantilla se invoca como `currentUser.isAdmin()`; no usar `currentUser.admin`, porque el acceso a propiedades de un `record` no es fiable en SpringEL.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=DashboardUserRenderingTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the full suite**

Run: `.\mvnw.cmd test`
Expected: 0 fallos, 3 omitidas.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/dashboard.html src/main/resources/templates/history.html src/test/java/com/shiparrival/web/DashboardUserRenderingTest.java
git commit -m "feat: show current user and restrict admin actions in the UI"
```

---

## Fase 2 — Monitoreo remoto y progreso en vivo

### Task 9: Entidad de progreso por ejecución

**Files:**
- Create: `src/main/java/com/shiparrival/domain/TrackingRunProgress.java`
- Create: `src/main/java/com/shiparrival/repository/TrackingRunProgressRepository.java`
- Test: `src/test/java/com/shiparrival/repository/TrackingRunProgressRepositoryTest.java`

**Interfaces:**
- Produces: `TrackingRunProgress` con `Long id`, `String runId`, `String origin`, `String triggeredBy`, `String status`, `int total`, `int processed`, `int updated`, `int failed`, `int alertsSent`, `String currentTrackingNumber`, `String message`, `String detailsText`, `LocalDateTime startedAt`, `LocalDateTime finishedAt`, `LocalDateTime updatedAt`
- Produces: `TrackingRunProgressRepository` con `Optional<TrackingRunProgress> findFirstByOrderByStartedAtDesc()` y `Optional<TrackingRunProgress> findByRunId(String runId)`

- [ ] **Step 1: Write the failing test**

```java
@DataJpaTest
class TrackingRunProgressRepositoryTest {

    @Autowired private TrackingRunProgressRepository repository;

    private TrackingRunProgress row(String runId, LocalDateTime startedAt) {
        return TrackingRunProgress.builder()
                .runId(runId).origin("ci").triggeredBy("admin").status("RUNNING")
                .total(4).processed(1).startedAt(startedAt).build();
    }

    @Test
    @DisplayName("Recupera la fila por identificador de ejecución")
    void shouldFindByRunId() {
        repository.save(row("12345", LocalDateTime.now()));

        assertThat(repository.findByRunId("12345")).isPresent();
    }

    @Test
    @DisplayName("Devuelve la ejecución más reciente")
    void shouldFindLatest() {
        repository.save(row("1", LocalDateTime.now().minusHours(2)));
        repository.save(row("2", LocalDateTime.now()));

        assertThat(repository.findFirstByOrderByStartedAtDesc().orElseThrow().getRunId()).isEqualTo("2");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=TrackingRunProgressRepositoryTest`
Expected: FAIL — la entidad no existe.

- [ ] **Step 3: Implementar**

Entidad `@Table(name = "tracking_run_progress", indexes = @Index(name = "idx_run_progress_run_id", columnList = "runId"))`. `runId` con `@Column(nullable = false, length = 64, unique = true)`. `detailsText` con `@Lob @Column(columnDefinition = "text")`. `@PrePersist`/`@PreUpdate` fijan `updatedAt`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=TrackingRunProgressRepositoryTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/shiparrival/domain/TrackingRunProgress.java src/main/java/com/shiparrival/repository/TrackingRunProgressRepository.java src/test/java/com/shiparrival/repository/TrackingRunProgressRepositoryTest.java
git commit -m "feat: add per-run tracking progress entity"
```

---

### Task 10: Publicador de progreso

**Files:**
- Create: `src/main/java/com/shiparrival/service/TrackingProgressPublisher.java`
- Modify: `src/main/java/com/shiparrival/service/TrackingSchedulerService.java`
- Test: `src/test/java/com/shiparrival/service/TrackingProgressPublisherTest.java`

**Interfaces:**
- Consumes: `TrackingRunProgress`, `TrackingRunProgressRepository` (Task 9), `TrackingStatus` (existente)
- Produces: `TrackingProgressPublisher` con
  - `void start(String runId, String origin, String triggeredBy, int total)`
  - `void update(String runId, TrackingStatus status)`
  - `void finish(String runId, TrackingStatus status)`
  - `void fail(String runId, String message)`
- Produces en `TrackingSchedulerService`: `void setRunContext(String runId, String origin, String triggeredBy)`
- Produce `TrackingRunOnceRunner`: lee `TRACKING_RUN_ID` y `TRACKING_ORIGIN` del entorno y establece el contexto antes del ciclo

- [ ] **Step 1: Write the failing test**

```java
class TrackingProgressPublisherTest {

    private TrackingRunProgressRepository repository;
    private TrackingProgressPublisher publisher;

    @BeforeEach
    void setUp() {
        repository = mock(TrackingRunProgressRepository.class);
        when(repository.findByRunId(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(TrackingRunProgress.class))).thenAnswer(inv -> inv.getArgument(0));
        publisher = new TrackingProgressPublisher(repository);
    }

    @Test
    @DisplayName("Al iniciar crea la fila con estado RUNNING y el total de guías")
    void shouldCreateRowOnStart() {
        publisher.start("12345", "ci", "admin", 4);

        ArgumentCaptor<TrackingRunProgress> captor = ArgumentCaptor.forClass(TrackingRunProgress.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("RUNNING");
        assertThat(captor.getValue().getTotal()).isEqualTo(4);
        assertThat(captor.getValue().getOrigin()).isEqualTo("ci");
    }

    @Test
    @DisplayName("Al terminar guarda el resumen y el detalle guía por guía")
    void shouldStoreDetailsOnFinish() {
        TrackingStatus status = TrackingStatus.builder()
                .running(false).total(2).processed(2).updated(2).failed(0)
                .finishedAt(LocalDateTime.now())
                .details(List.of("Guía A: Actualizado - IN_TRANSIT", "Guía B: Actualizado - ARRIVED"))
                .build();

        publisher.finish("12345", status);

        ArgumentCaptor<TrackingRunProgress> captor = ArgumentCaptor.forClass(TrackingRunProgress.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(captor.getValue().getDetailsText()).contains("Guía A").contains("Guía B");
        assertThat(captor.getValue().getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Un fallo deja la fila en FAILED con el mensaje")
    void shouldMarkFailed() {
        publisher.fail("12345", "El teléfono no responde");

        ArgumentCaptor<TrackingRunProgress> captor = ArgumentCaptor.forClass(TrackingRunProgress.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getMessage()).isEqualTo("El teléfono no responde");
    }

    @Test
    @DisplayName("Un error de base de datos no interrumpe el monitoreo")
    void shouldNotPropagateDatabaseErrors() {
        when(repository.save(any(TrackingRunProgress.class)))
                .thenThrow(new DataAccessResourceFailureException("sin conexión"));

        assertThatCode(() -> publisher.start("12345", "ci", "admin", 4))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=TrackingProgressPublisherTest`
Expected: FAIL — `TrackingProgressPublisher` no existe.

- [ ] **Step 3: Implementar**

`TrackingProgressPublisher` con `@Service` y `@Transactional(propagation = Propagation.REQUIRES_NEW)` en cada método público, envolviendo todo en `try/catch (RuntimeException)` con `log.warn`: el progreso es observabilidad, nunca debe tumbar el monitoreo.

En `TrackingSchedulerService`, añadir un constructor de 8 argumentos anotado con `@Autowired` que reciba el publicador, y conservar el constructor actual de 7 argumentos delegando con un publicador nulo. En `doRunTrackingCycle`: llamar `start` tras `startStatus(total)`, `update` dentro de `updateProgress` solo cada 5 guías o cada 5 segundos, y `finish` dentro de `finishStatus`. En `finishWithError`, llamar `fail`.

En `TrackingRunOnceRunner`, antes de `runTrackingCycle()`:

```java
String runId = System.getenv().getOrDefault("TRACKING_RUN_ID", "local-" + System.currentTimeMillis());
String origin = System.getenv().getOrDefault("TRACKING_ORIGIN", "local");
trackingSchedulerService.setRunContext(runId, origin, origin.equals("ci") ? "github-actions" : "local");
```

Añadir a `TrackingRunOnceRunnerTest` una prueba `shouldResolveRunIdFromEnvironment` sobre un método estático `static String resolveRunId(Map<String, String> env)` extraído para poder probarlo: con `TRACKING_RUN_ID=12345` devuelve `12345`; con el mapa vacío devuelve un valor que empieza por `local-`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=TrackingProgressPublisherTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run the full suite**

Run: `.\mvnw.cmd test`
Expected: 0 fallos. `TrackingSchedulerServiceTest` sigue compilando gracias al constructor de 7 argumentos.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/shiparrival/service/TrackingProgressPublisher.java src/main/java/com/shiparrival/service/TrackingSchedulerService.java src/test/java/com/shiparrival/service/TrackingProgressPublisherTest.java
git commit -m "feat: publish tracking progress per run"
```

---

### Task 11: Cliente de la API de GitHub

**Files:**
- Create: `src/main/java/com/shiparrival/service/github/GitHubActionsProperties.java`
- Create: `src/main/java/com/shiparrival/service/github/GitHubRunState.java`
- Create: `src/main/java/com/shiparrival/service/github/GitHubActionsClient.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/shiparrival/service/github/GitHubActionsClientTest.java`

**Interfaces:**
- Produces: `record GitHubRunState(long id, String status, String conclusion, String htmlUrl, String failedStepName)` con `boolean isFinished()` e `boolean isFailed()`
- Produces: `GitHubActionsClient` con
  - `boolean isEnabled()`
  - `Optional<Long> dispatch()`
  - `Optional<GitHubRunState> findRun(long runId)`
  - `Optional<GitHubRunState> findLatestRun()`

- [ ] **Step 1: Write the failing test**

```java
class GitHubActionsClientTest {

    private MockRestServiceServer server;
    private GitHubActionsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();
        GitHubActionsProperties properties = new GitHubActionsProperties(
                true, "token-de-prueba", "krcondorig", "ship-arrival", "daily-monitoring.yml", "master");
        client = new GitHubActionsClient(builder.build(), properties);
    }

    @Test
    @DisplayName("Dispara el workflow y devuelve el identificador de la ejecución creada")
    void shouldDispatchAndReturnRunId() {
        server.expect(requestTo("https://api.github.com/repos/krcondorig/ship-arrival/actions/workflows/daily-monitoring.yml/dispatches"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-de-prueba"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(startsWith("https://api.github.com/repos/krcondorig/ship-arrival/actions/runs?branch=master")))
                .andRespond(withSuccess("""
                        {"workflow_runs":[{"id":987654,"status":"queued","conclusion":null,"html_url":"https://github.com/x/y/actions/runs/987654"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.dispatch()).contains(987654L);
        server.verify();
    }

    @Test
    @DisplayName("Traduce el estado de la ejecución")
    void shouldReadRunState() {
        server.expect(requestTo("https://api.github.com/repos/krcondorig/ship-arrival/actions/runs/987654"))
                .andRespond(withSuccess("""
                        {"id":987654,"status":"completed","conclusion":"failure","html_url":"https://github.com/x/y/actions/runs/987654"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/krcondorig/ship-arrival/actions/runs/987654/jobs"))
                .andRespond(withSuccess("""
                        {"jobs":[{"name":"monitor","steps":[
                          {"name":"Connect to Tailscale (Phone Exit Node)","conclusion":"success"},
                          {"name":"Activate Phone Exit Node","conclusion":"failure"}
                        ]}]}
                        """, MediaType.APPLICATION_JSON));

        GitHubRunState state = client.findRun(987654L).orElseThrow();

        assertThat(state.isFinished()).isTrue();
        assertThat(state.isFailed()).isTrue();
        assertThat(state.failedStepName()).isEqualTo("Activate Phone Exit Node");
    }

    @Test
    @DisplayName("Sin token configurado, el disparo remoto está deshabilitado")
    void shouldBeDisabledWithoutToken() {
        GitHubActionsProperties properties = new GitHubActionsProperties(
                true, "", "krcondorig", "ship-arrival", "daily-monitoring.yml", "master");
        client = new GitHubActionsClient(RestClient.builder().build(), properties);

        assertThat(client.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Un error de red no propaga excepción")
    void shouldSwallowNetworkErrors() {
        server.expect(requestTo(startsWith("https://api.github.com")))
                .andRespond(withException(new java.net.SocketTimeoutException("timeout")));

        assertThat(client.findRun(1L)).isEmpty();
    }

    @Test
    @DisplayName("Consulta la última ejecución para saber si ya hay una en curso")
    void shouldFindLatestRun() {
        server.expect(requestTo(startsWith("https://api.github.com/repos/krcondorig/ship-arrival/actions/runs?branch=master")))
                .andRespond(withSuccess("""
                        {"workflow_runs":[{"id":555,"status":"in_progress","conclusion":null,"html_url":"https://github.com/x/y/actions/runs/555"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.findLatestRun().orElseThrow().status()).isEqualTo("in_progress");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=GitHubActionsClientTest`
Expected: FAIL — las clases no existen.

- [ ] **Step 3: Implementar**

`GitHubActionsProperties` como `@ConfigurationProperties(prefix = "github.dispatch")` con `enabled`, `token`, `owner`, `repo`, `workflow`, `ref`, más un record de constructor completo para las pruebas.

`GitHubActionsClient`: `isEnabled()` es `enabled && token` no vacío. `dispatch()` hace `POST .../dispatches` con cuerpo `{"ref": "<ref>"}`, luego `GET .../runs?branch=<ref>&per_page=1` y devuelve el primer `id`. Cabeceras: `Authorization: Bearer <token>`, `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`. Todos los métodos capturan `RuntimeException` y devuelven `Optional.empty()`.

`application.properties`:

```properties
# Disparo remoto del monitoreo desde el dashboard.
# Requiere un token fine-grained con permiso Actions: write sobre el repositorio.
github.dispatch.enabled=${GITHUB_DISPATCH_ENABLED:false}
github.dispatch.token=${GITHUB_TOKEN:}
github.dispatch.owner=${GITHUB_OWNER:krcondorig}
github.dispatch.repo=${GITHUB_REPO:ship-arrival}
github.dispatch.workflow=${GITHUB_WORKFLOW:daily-monitoring.yml}
github.dispatch.ref=${GITHUB_REF:master}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=GitHubActionsClientTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/shiparrival/service/github src/main/resources/application.properties src/test/java/com/shiparrival/service/github
git commit -m "feat: add GitHub Actions dispatch client"
```

---

### Task 12: Disparo remoto y estado en vivo

**Files:**
- Create: `src/main/java/com/shiparrival/service/dto/LiveTrackingStatus.java`
- Create: `src/main/java/com/shiparrival/service/LiveTrackingStatusService.java`
- Modify: `src/main/java/com/shiparrival/web/ShipmentWebController.java`
- Test: `src/test/java/com/shiparrival/web/RemoteTrackingControllerTest.java`

**Interfaces:**
- Consumes: `GitHubActionsClient` (Task 11), `TrackingRunProgressRepository` (Task 9), `TrackingSchedulerService` (Task 10), `CurrentUser` (Task 4)
- Produces: `LiveTrackingStatus` con `phase`, `running`, `total`, `processed`, `updated`, `failed`, `alertsSent`, `currentTrackingNumber`, `message`, `runUrl`, `details`
- Produces: `LiveTrackingStatusService.resolve(Long runId) -> LiveTrackingStatus`
- Produce rutas: `POST /shipments/track-now` (modificada) y `GET /shipments/live-status?runId=`

Fases posibles: `QUEUED`, `PREPARING`, `RUNNING`, `COMPLETED`, `FAILED`, `UNKNOWN`.

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "github.dispatch.enabled=true",
        "github.dispatch.token=token-de-prueba",
        "spring.datasource.url=jdbc:h2:mem:remote;DB_CLOSE_DELAY=-1"
})
class RemoteTrackingControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository repository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private GitHubActionsClient gitHubActionsClient;

    private AppUser givenAdmin() {
        return repository.save(AppUser.builder()
                .username("root").passwordHash(passwordEncoder.encode("claveSegura1"))
                .role(UserRole.ADMIN).enabled(true).build());
    }

    @Test
    @DisplayName("El botón dispara el workflow y redirige con el identificador de la ejecución")
    void shouldDispatchWorkflow() throws Exception {
        AppUser admin = givenAdmin();
        when(gitHubActionsClient.isEnabled()).thenReturn(true);
        when(gitHubActionsClient.dispatch()).thenReturn(Optional.of(987654L));

        mockMvc.perform(post("/shipments/track-now")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?runId=987654"));
    }

    @Test
    @DisplayName("Si el teléfono no responde, el mensaje lo dice")
    void shouldReportPhoneOffline() throws Exception {
        AppUser admin = givenAdmin();
        when(gitHubActionsClient.isEnabled()).thenReturn(true);
        when(gitHubActionsClient.findRun(987654L)).thenReturn(Optional.of(
                new GitHubRunState(987654L, "completed", "failure",
                        "https://github.com/x/y/actions/runs/987654", "Activate Phone Exit Node")));

        mockMvc.perform(get("/shipments/live-status").param("runId", "987654")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("FAILED"))
                .andExpect(jsonPath("$.message").value(
                        "Tu teléfono no responde como nodo de salida. Verifica que esté encendido, con datos móviles y Tailscale activo."));
    }

    @Test
    @DisplayName("Mientras GitHub prepara el run, la fase es PREPARING sin porcentaje")
    void shouldReportPreparing() throws Exception {
        AppUser admin = givenAdmin();
        when(gitHubActionsClient.isEnabled()).thenReturn(true);
        when(gitHubActionsClient.findRun(987654L)).thenReturn(Optional.of(
                new GitHubRunState(987654L, "in_progress", null,
                        "https://github.com/x/y/actions/runs/987654", null)));

        mockMvc.perform(get("/shipments/live-status").param("runId", "987654")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(jsonPath("$.phase").value("PREPARING"))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("No dispara si ya hay una ejecución en curso")
    void shouldRefuseWhenRunAlreadyInProgress() throws Exception {
        AppUser admin = givenAdmin();
        when(gitHubActionsClient.isEnabled()).thenReturn(true);
        when(gitHubActionsClient.findLatestRun()).thenReturn(Optional.of(
                new GitHubRunState(111L, "in_progress", null,
                        "https://github.com/x/y/actions/runs/111", null)));

        mockMvc.perform(post("/shipments/track-now")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("warningMessage",
                        "Ya hay un monitoreo en curso. Espera a que termine."));

        verify(gitHubActionsClient, never()).dispatch();
    }

    @Test
    @DisplayName("Un usuario normal no puede disparar el monitoreo")
    void shouldForbidRegularUser() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("ana").passwordHash(passwordEncoder.encode("claveSegura1"))
                .role(UserRole.USER).enabled(true).build());

        mockMvc.perform(post("/shipments/track-now")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=RemoteTrackingControllerTest`
Expected: FAIL — las rutas nuevas no existen.

- [ ] **Step 3: Implementar**

`POST /shipments/track-now`: recibe `@RequestAttribute(value = AuthFilter.CURRENT_USER_ATTRIBUTE, required = false) CurrentUser currentUser`. Si `currentUser != null && !currentUser.isAdmin()` lanza `ResponseStatusException(HttpStatus.FORBIDDEN)`. Se usa `required = false` a propósito: en `@WebMvcTest` y con la autenticación desactivada el atributo no existe, y las pruebas existentes del controlador deben seguir pasando.

Orden de decisión:
1. Si el disparo remoto está deshabilitado, se conserva el comportamiento local actual (`startTrackingCycleAsync`).
2. Si `gitHubActionsClient.findLatestRun()` devuelve un estado `queued`, `waiting` o `in_progress`, se añade `warningMessage` "Ya hay un monitoreo en curso. Espera a que termine." y no se dispara nada.
3. Si `dispatch()` devuelve un identificador, se redirige a `/?runId=<id>`.
4. Si `dispatch()` devuelve vacío, se añade `errorMessage` "No se pudo enviar el monitoreo: el token de GitHub no tiene permiso para ejecutar workflows.".

`LiveTrackingStatusService.resolve`: primero busca la fila por `runId`; luego consulta `gitHubActionsClient.findRun`. Reglas:
- Sin fila y GitHub `queued` → `QUEUED`.
- Sin fila y GitHub `in_progress` → `PREPARING`, `total = 0`.
- Con fila `RUNNING` → `RUNNING` con sus contadores.
- GitHub `completed` + `success` → `COMPLETED`.
- GitHub `completed` + `failure` y paso fallido `Activate Phone Exit Node` o `Connect to Tailscale (Phone Exit Node)` → `FAILED` con el mensaje de teléfono.
- GitHub `completed` + `failure` genérico → `FAILED` con "El monitoreo falló en la nube. Revisa el detalle en GitHub Actions." y `runUrl`.
- GitHub no disponible y fila presente → usar la fila.

`GET /shipments/live-status` devuelve `LiveTrackingStatusService.resolve(runId)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=RemoteTrackingControllerTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/shiparrival/service/dto/LiveTrackingStatus.java src/main/java/com/shiparrival/service/LiveTrackingStatusService.java src/main/java/com/shiparrival/web/ShipmentWebController.java src/test/java/com/shiparrival/web/RemoteTrackingControllerTest.java
git commit -m "feat: dispatch monitoring to GitHub Actions and expose live status"
```

---

### Task 13: Panel de progreso en vivo

**Files:**
- Modify: `src/main/resources/templates/dashboard.html`
- Test: `src/test/java/com/shiparrival/web/DashboardLivePanelRenderingTest.java`

**Interfaces:**
- Consumes: `LiveTrackingStatus` (Task 12), `GET /shipments/live-status`

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:livepanel;DB_CLOSE_DELAY=-1"
})
class DashboardLivePanelRenderingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository repository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("El dashboard expone la URL del estado en vivo y el identificador del run")
    void shouldExposeLiveStatusUrl() throws Exception {
        AppUser admin = repository.save(AppUser.builder()
                .username("root").passwordHash(passwordEncoder.encode("claveSegura1"))
                .role(UserRole.ADMIN).enabled(true).build());

        mockMvc.perform(get("/").param("runId", "987654")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-live-status-url")))
                .andExpect(content().string(containsString("987654")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=DashboardLivePanelRenderingTest`
Expected: FAIL — el panel no expone esos atributos.

- [ ] **Step 3: Implementar**

En `dashboard.html`, añadir al panel `data-live-status-url="@{/shipments/live-status}"` y `th:attr="data-run-id=${param.runId != null ? param.runId[0] : ''}"`. No usar `${param.runId}` directamente: es un arreglo y Thymeleaf lo renderizaría como `[987654]`. En el script, cuando `runId` no esté vacío, consultar `live-status` cada 3 s y renderizar:
- `QUEUED` → barra indeterminada, texto "En cola en GitHub Actions...".
- `PREPARING` → barra indeterminada, texto "Preparando: compilando y conectando".
- `RUNNING` → porcentaje `processed / total`.
- `COMPLETED` → barra verde al 100 % y recarga de la página a los 3 s.
- `FAILED` → barra roja y el `message` del backend en el resumen.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=DashboardLivePanelRenderingTest`
Expected: PASS, 1 test.

- [ ] **Step 5: Run the full suite**

Run: `.\mvnw.cmd test`
Expected: 0 fallos, 3 omitidas.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/dashboard.html src/test/java/com/shiparrival/web/DashboardLivePanelRenderingTest.java
git commit -m "feat: render live remote monitoring progress"
```

---

### Task 14: Workflow, Render y documentación

**Files:**
- Modify: `.github/workflows/daily-monitoring.yml`
- Modify: `render.yaml`
- Modify: `AGENTS.md`
- Test: `src/test/java/com/shiparrival/config/DeploymentConfigurationTest.java`

**Interfaces:**
- Consumes: `TrackingRunProgress` (Task 9), `TrackingSchedulerService.setRunContext` (Task 10)

- [ ] **Step 1: Write the failing test**

```java
class DeploymentConfigurationTest {

    @Test
    @DisplayName("El workflow pasa el identificador del run al ciclo de monitoreo")
    void workflowShouldPassRunId() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/daily-monitoring.yml"));

        assertThat(workflow).contains("TRACKING_RUN_ID: ${{ github.run_id }}");
        assertThat(workflow).contains("TRACKING_ORIGIN: ci");
    }

    @Test
    @DisplayName("Render declara las variables de autenticación y del disparo remoto")
    void renderShouldDeclareRequiredEnvVars() throws IOException {
        String render = Files.readString(Path.of("render.yaml"));

        assertThat(render).contains("ADMIN_USERNAME");
        assertThat(render).contains("ADMIN_PASSWORD");
        assertThat(render).contains("GITHUB_DISPATCH_ENABLED");
        assertThat(render).contains("GITHUB_TOKEN");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=DeploymentConfigurationTest`
Expected: FAIL — las variables no están declaradas.

- [ ] **Step 3: Implementar**

En el paso "Run tracking cycle once", añadir al bloque `env`:

```yaml
          TRACKING_RUN_ID: ${{ github.run_id }}
          TRACKING_ORIGIN: ci
```

En `render.yaml`, añadir:

```yaml
      - key: ADMIN_USERNAME
        sync: false
      - key: ADMIN_PASSWORD
        sync: false
      - key: GITHUB_DISPATCH_ENABLED
        value: "true"
      - key: GITHUB_TOKEN
        sync: false
```

En `AGENTS.md`, documentar en la Fase 10: cuentas por persona, rol `ADMIN`, variables `ADMIN_USERNAME`/`ADMIN_PASSWORD` y `GITHUB_TOKEN` (fine-grained, permiso `Actions: write`), y que el botón dispara el workflow.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=DeploymentConfigurationTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Run the full suite**

Run: `.\mvnw.cmd test`
Expected: 0 fallos, 3 omitidas.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/daily-monitoring.yml render.yaml AGENTS.md src/test/java/com/shiparrival/config/DeploymentConfigurationTest.java
git commit -m "chore: wire run id, auth and dispatch configuration for deployment"
```
