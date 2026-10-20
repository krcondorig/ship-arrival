# ShipArrival Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir una aplicación web completa y 100% gratuita en Java 21 con Spring Boot 3 para monitorear automáticamente la llegada de buques a puerto (ej. Manzanillo) mediante guías de embarque (B/L), con importación Excel, adaptador para ONE (*Ocean Network Express*), scheduler diario a las 08:00 AM y alertas automáticas a Telegram a ≤ 3 días del arribo.

**Architecture:** Monolito modular en Spring Boot 3 con patrón Strategy/Adapter para el motor de rastreo de navieras (`CarrierTrackingAdapter`), base de datos embebida H2 persistente en archivo local (`./data/shiparrival_db`), motor de lectura Excel con Apache POI, llamadas HTTP resilientes a la API/web de navieras y a Telegram Bot API, interfaz web en Thymeleaf con visualización de estados por color.

**Tech Stack:** Java 21 LTS, Spring Boot 3.4.x, Spring Data JPA, H2 Database (modo archivo), Apache POI (`poi-ooxml`), Thymeleaf, Telegram Bot REST API, JUnit 5, Mockito, AssertJ, Maven Wrapper (`mvnw.cmd`).

**Spec:** `docs/superpowers/specs/2026-10-20-ship-arrival-monitoring-design.md`

## Global Constraints

- **Java Version:** Java 21 LTS (`21.0.11` o superior).
- **Herramienta de construcción:** Maven Wrapper autónomo (`mvnw` / `mvnw.cmd`) sin requerir Maven preinstalado en el PATH.
- **Persistencia:** H2 Database persistente en archivo (`jdbc:h2:file:./data/shiparrival_db;DB_CLOSE_ON_EXIT=FALSE;AUTO_RECONNECT=TRUE`).
- **Coste cero:** Dependencias exclusivamente de código abierto y servicios gratuitos (Telegram Bot API).
- **Umbral de Alerta:** ≤ 3 días antes de la fecha estimada de arribo (`estimatedArrival`).
- **Hora programada:** Cron a las 08:00 AM (`0 0 8 * * *`).

## Review Focus

1. **Filas vacías, encabezados extra o celdas con espacios en blanco en el Excel:** El importador debe recortar espacios, ignorar filas vacías o no válidas y no fallar todo el lote por una celda corrupta.
2. **Falla de conexión, timeout o respuesta no esperada del portal naviero:** El adaptador debe capturar excepciones de red, marcar el embarque como `ERROR_CHECKING` sin interrumpir el rastreo de las demás guías y registrar el fallo en `ShipmentEventLog`.
3. **Múltiples contenedores en una misma guía (B/L):** El adaptador de ONE debe tomar la fecha de arribo a puerto de descarga del contenedor representativo del embarque sin duplicar registros.
4. **Reprogramación de fecha de arribo (ETA cambiante):** Si una guía ya alertada sufre un cambio de fecha, el sistema debe registrar el evento `ETA_UPDATED` con el `previousEta` y `newEta` y enviar un aviso de reprogramación si la nueva fecha está dentro del umbral.
5. **Token de Telegram no configurado o sin conexión a internet:** El servicio de notificaciones debe registrar una advertencia (*warning*) y continuar normalmente sin provocar rollback ni abortar el proceso de actualización en la base de datos.

---

### Task 1: Scaffolding del Proyecto y Configuración de Maven Wrapper

**Files:**
- Create: `pom.xml`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `mvnw.cmd`
- Create: `mvnw`
- Create: `src/main/resources/application.properties`
- Create: `src/main/java/com/shiparrival/ShipArrivalApplication.java`
- Test: `src/test/java/com/shiparrival/ShipArrivalApplicationTests.java`

**Interfaces:**
- Produces: Proyecto Spring Boot 3 ejecutable y compilable con `./mvnw.cmd test`.

- [ ] **Step 1: Crear `pom.xml` con dependencias estándar de Spring Boot 3.4**
  - Dependencias: `spring-boot-starter-web`, `spring-boot-starter-thymeleaf`, `spring-boot-starter-data-jpa`, `com.h2database:h2`, `org.apache.poi:poi-ooxml:5.3.0`, `org.projectlombok:lombok`, `spring-boot-starter-test`.
  - Plugins: `spring-boot-maven-plugin`.

- [ ] **Step 2: Configurar Maven Wrapper y `application.properties`**
  - Configurar `.mvn/wrapper/maven-wrapper.properties` con Maven 3.9.9.
  - En `application.properties`: H2 en archivo `./data/shiparrival_db`, `spring.h2.console.enabled=true`, `spring.jpa.hibernate.ddl-auto=update`, puerto `8080`, propiedades para Telegram (`telegram.bot.token`, `telegram.bot.chat-id`, `telegram.bot.enabled=false`).

- [ ] **Step 3: Crear clase principal `ShipArrivalApplication`**
  - `@SpringBootApplication` y `@EnableScheduling`.

- [ ] **Step 4: Crear test de carga de contexto `ShipArrivalApplicationTests`**
  - Test `@SpringBootTest` que valida que el contexto levanta limpiamente.

- [ ] **Step 5: Ejecutar test con Maven Wrapper para verificar compilación y arranque**
  - Run: `.\mvnw.cmd test`
  - Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**
  - `git add pom.xml .mvn mvnw mvnw.cmd src/`
  - `git commit -m "chore: scaffold Spring Boot 3 project with Maven Wrapper and H2 config"`

---

### Task 2: Modelo de Dominio y Repositorios JPA

**Files:**
- Create: `src/main/java/com/shiparrival/domain/Carrier.java`
- Create: `src/main/java/com/shiparrival/domain/ShipmentStatus.java`
- Create: `src/main/java/com/shiparrival/domain/Shipment.java`
- Create: `src/main/java/com/shiparrival/domain/ShipmentEventLog.java`
- Create: `src/main/java/com/shiparrival/repository/ShipmentRepository.java`
- Create: `src/main/java/com/shiparrival/repository/ShipmentEventLogRepository.java`
- Test: `src/test/java/com/shiparrival/repository/ShipmentRepositoryTest.java`

**Interfaces:**
- Produces: 
  - `Carrier`: Enum (`ONE`, `MSC`, `UNKNOWN`)
  - `ShipmentStatus`: Enum (`PENDING_FIRST_CHECK`, `IN_TRANSIT`, `NEAR_ARRIVAL`, `ARRIVED`, `CANCELLED`, `ERROR_CHECKING`)
  - `ShipmentRepository.findByTrackingNumber(String trackingNumber) -> Optional<Shipment>`
  - `ShipmentRepository.findByActiveTrue() -> List<Shipment>`
  - `ShipmentEventLogRepository.findByShipmentIdOrderByRecordedAtDesc(Long shipmentId) -> List<ShipmentEventLog>`

- [ ] **Step 1: Escribir test fallido en `ShipmentRepositoryTest`**
  - Test de persistencia de `Shipment` y búsqueda por `trackingNumber` y por `active == true`.
  - Test de persistencia de `ShipmentEventLog` asociado.

- [ ] **Step 2: Ejecutar test para verificar que falla (tipos no existen)**
  - Run: `.\mvnw.cmd test -Dtest=ShipmentRepositoryTest`
  - Expected: Compilation FAIL

- [ ] **Step 3: Implementar Enums, Entidades y Repositorios**
  - `Carrier`: `ONE`, `MSC`, `UNKNOWN`.
  - `ShipmentStatus`: `PENDING_FIRST_CHECK`, `IN_TRANSIT`, `NEAR_ARRIVAL`, `ARRIVED`, `CANCELLED`, `ERROR_CHECKING`.
  - `Shipment`: Campos id, trackingNumber, carrier, containerNumber, vesselName, voyageNumber, portOfLoading, portOfDischarge, departureDate, estimatedArrival, actualArrival, status, lastCheckedAt, active, alertSent3Days, alertSentArrived, createdAt, updatedAt.
  - `ShipmentEventLog`: Campos id, shipment, eventType, previousEta, newEta, description, recordedAt.
  - `ShipmentRepository` y `ShipmentEventLogRepository` con Spring Data JPA.

- [ ] **Step 4: Ejecutar test para verificar que pasa**
  - Run: `.\mvnw.cmd test -Dtest=ShipmentRepositoryTest`
  - Expected: `BUILD SUCCESS` (1 test passed)

- [ ] **Step 5: Commit**
  - `git add src/main/java/com/shiparrival/domain src/main/java/com/shiparrival/repository src/test/java/com/shiparrival/repository`
  - `git commit -m "feat: implement Shipment and ShipmentEventLog JPA domain models and repositories"`

---

### Task 3: Arquitectura de Adaptadores de Rastreo y Conector ONE

**Files:**
- Create: `src/main/java/com/shiparrival/adapter/TrackingResult.java`
- Create: `src/main/java/com/shiparrival/adapter/CarrierTrackingAdapter.java`
- Create: `src/main/java/com/shiparrival/adapter/CarrierTrackingAdapterFactory.java`
- Create: `src/main/java/com/shiparrival/adapter/one/OneLineTrackingAdapter.java`
- Create: `src/main/java/com/shiparrival/adapter/one/OneTrackingResponseDto.java`
- Test: `src/test/java/com/shiparrival/adapter/one/OneLineTrackingAdapterTest.java`

**Interfaces:**
- Produces:
  - `CarrierTrackingAdapter.supports(Carrier carrier) -> boolean`
  - `CarrierTrackingAdapter.track(String trackingNumber) -> TrackingResult`
  - `TrackingResult`: `boolean success, String trackingNumber, String containerNumber, String vesselName, String voyageNumber, String portOfLoading, String portOfDischarge, LocalDateTime departureDate, LocalDateTime estimatedArrival, LocalDateTime actualArrival, ShipmentStatus detectedStatus, String rawStatusMessage, String errorMessage`
  - `CarrierTrackingAdapterFactory.getAdapter(Carrier carrier) -> CarrierTrackingAdapter`

- [ ] **Step 1: Escribir test fallido en `OneLineTrackingAdapterTest`**
  - Test con respuesta simulada JSON/HTML típica de ONE con guía `TAOGR4856400`: verifica extracción correcta de buque, viaje, puerto Manzanillo, ETA `2026-10-20 09:00`, partida y estatus.
  - Test de manejo de error (guía no encontrada o error 500 del servidor naviero).

- [ ] **Step 2: Ejecutar test para verificar que falla**
  - Run: `.\mvnw.cmd test -Dtest=OneLineTrackingAdapterTest`
  - Expected: FAIL

- [ ] **Step 3: Implementar `TrackingResult`, `CarrierTrackingAdapter`, `OneLineTrackingAdapter` y `CarrierTrackingAdapterFactory`**
  - `OneLineTrackingAdapter` utiliza `RestClient` de Spring con User-Agent estándar de navegador, timeouts configurables y parser de la estructura de datos devuelta por `ecomm.one-line.com`.
  - Mapear estados a `ShipmentStatus` (`IN_TRANSIT`, `ARRIVED`, `ERROR_CHECKING`).

- [ ] **Step 4: Ejecutar test para verificar que pasa**
  - Run: `.\mvnw.cmd test -Dtest=OneLineTrackingAdapterTest`
  - Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**
  - `git add src/main/java/com/shiparrival/adapter src/test/java/com/shiparrival/adapter`
  - `git commit -m "feat: implement CarrierTrackingAdapter pattern and OneLineTrackingAdapter"`

---

### Task 4: Servicio de Importación de Archivos Excel con Apache POI

**Files:**
- Create: `src/main/java/com/shiparrival/service/dto/ExcelImportResult.java`
- Create: `src/main/java/com/shiparrival/service/ExcelImportService.java`
- Test: `src/test/java/com/shiparrival/service/ExcelImportServiceTest.java`

**Interfaces:**
- Produces:
  - `ExcelImportService.extractTrackingNumbers(InputStream inputStream) -> ExcelImportResult`
  - `ExcelImportResult`: `List<String> validTrackingNumbers, List<String> invalidRows, int totalRowsProcessed`

- [ ] **Step 1: Escribir test fallido en `ExcelImportServiceTest`**
  - Test con archivo Excel generado en memoria (usando Apache POI en el test):
    - Hoja con columna de guías (`TAOGR4856400`, `SZPG70884900`), celdas vacías intermedias, filas con espacios.
    - Validar que extrae las guías limpias y descarta filas vacías o no válidas sin lanzar excepción.

- [ ] **Step 2: Ejecutar test para verificar que falla**
  - Run: `.\mvnw.cmd test -Dtest=ExcelImportServiceTest`
  - Expected: FAIL

- [ ] **Step 3: Implementar `ExcelImportService`**
  - Leer la primera hoja con `WorkbookFactory.create(inputStream)`.
  - Detectar columna de guías (buscar encabezado "GUIA", "B/L", "BL", "TRACKING" o tomar columna 0 si no hay encabezados explícitos).
  - Sanitizar cadenas (`trim()`, ignorar celdas en blanco o nulas).
  - Retornar `ExcelImportResult`.

- [ ] **Step 4: Ejecutar test para verificar que pasa**
  - Run: `.\mvnw.cmd test -Dtest=ExcelImportServiceTest`
  - Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**
  - `git add src/main/java/com/shiparrival/service/dto/ExcelImportResult.java src/main/java/com/shiparrival/service/ExcelImportService.java src/test/java/com/shiparrival/service/ExcelImportServiceTest.java`
  - `git commit -m "feat: implement ExcelImportService for .xlsx batch parsing"`

---

### Task 5: Servicio de Negocio de Embarques y Lógica de Proximidad (≤ 3 Días)

**Files:**
- Create: `src/main/java/com/shiparrival/service/dto/ShipmentDto.java`
- Create: `src/main/java/com/shiparrival/service/dto/BatchImportSummary.java`
- Create: `src/main/java/com/shiparrival/service/ShipmentService.java`
- Test: `src/test/java/com/shiparrival/service/ShipmentServiceTest.java`

**Interfaces:**
- Consumes: `ShipmentRepository`, `ShipmentEventLogRepository`, `ExcelImportService`
- Produces:
  - `ShipmentService.registerShipment(String trackingNumber, Carrier carrier) -> Shipment`
  - `ShipmentService.importFromExcel(InputStream inputStream, Carrier defaultCarrier) -> BatchImportSummary`
  - `ShipmentService.updateFromTrackingResult(Shipment shipment, TrackingResult result) -> Shipment`
  - `ShipmentService.checkProximity(Shipment shipment, LocalDate referenceDate) -> boolean` (devuelve true si días hasta ETA están entre 0 y 3)
  - `ShipmentService.getAllShipments() -> List<Shipment>`
  - `ShipmentService.getActiveShipments() -> List<Shipment>`
  - `ShipmentService.getEventLogs(Long shipmentId) -> List<ShipmentEventLog>`

- [ ] **Step 1: Escribir test fallido en `ShipmentServiceTest`**
  - Test de registro manual con deduplicación: si la guía ya existe, no crea un duplicado.
  - Test de importación por Excel: resumen de guías nuevas vs existentes.
  - Test de regla de proximidad:
    - ETA a 2 días -> `isNearArrival == true`, estado pasa a `NEAR_ARRIVAL`.
    - ETA a 10 días -> `isNearArrival == false`, estado es `IN_TRANSIT`.
  - Test de detección de cambio de ETA: registra `ShipmentEventLog` con `ETA_UPDATED`.

- [ ] **Step 2: Ejecutar test para verificar que falla**
  - Run: `.\mvnw.cmd test -Dtest=ShipmentServiceTest`
  - Expected: FAIL

- [ ] **Step 3: Implementar `ShipmentService`**
  - Métodos CRUD, deduplicación, cálculo de días restantes con `ChronoUnit.DAYS.between(now, eta.toLocalDate())`.
  - Actualización de flags `alertSent3Days` y `alertSentArrived`.

- [ ] **Step 4: Ejecutar test para verificar que pasa**
  - Run: `.\mvnw.cmd test -Dtest=ShipmentServiceTest`
  - Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**
  - `git add src/main/java/com/shiparrival/service src/test/java/com/shiparrival/service`
  - `git commit -m "feat: implement ShipmentService business logic and 3-day proximity detection"`

---

### Task 6: Servicio de Notificaciones Gratuitas por Telegram Bot API

**Files:**
- Create: `src/main/java/com/shiparrival/service/TelegramNotificationService.java`
- Test: `src/test/java/com/shiparrival/service/TelegramNotificationServiceTest.java`

**Interfaces:**
- Produces:
  - `TelegramNotificationService.sendNearArrivalAlert(Shipment shipment, long daysRemaining) -> boolean`
  - `TelegramNotificationService.sendArrivedAlert(Shipment shipment) -> boolean`
  - `TelegramNotificationService.sendCustomMessage(String message) -> boolean`

- [ ] **Step 1: Escribir test fallido en `TelegramNotificationServiceTest`**
  - Test cuando `telegram.bot.enabled=false`: no lanza excepción, retorna false, no hace petición HTTP.
  - Test cuando `telegram.bot.enabled=true`: verifica formato del mensaje Markdown (incluye guía, buque, puerto, ETA y días restantes) y llamada a la API simulada.
  - Test de tolerancia a fallos: si Telegram devuelve error 400 o timeout, captura el error y no propaga excepción.

- [ ] **Step 2: Ejecutar test para verificar que falla**
  - Run: `.\mvnw.cmd test -Dtest=TelegramNotificationServiceTest`
  - Expected: FAIL

- [ ] **Step 3: Implementar `TelegramNotificationService`**
  - Inyectar valores de `@Value("${telegram.bot.token:}")`, `@Value("${telegram.bot.chat-id:}")`, `@Value("${telegram.bot.enabled:false}")`.
  - Construir mensaje con emojis informativos (🚢, 📦, 📍, 📅, ⏳) y enviar vía `RestClient` POST a `https://api.telegram.org/bot{token}/sendMessage`.
  - Capturar excepciones y registrar logs con SLF4J.

- [ ] **Step 4: Ejecutar test para verificar que pasa**
  - Run: `.\mvnw.cmd test -Dtest=TelegramNotificationServiceTest`
  - Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**
  - `git add src/main/java/com/shiparrival/service/TelegramNotificationService.java src/test/java/com/shiparrival/service/TelegramNotificationServiceTest.java`
  - `git commit -m "feat: implement TelegramNotificationService for free arrival alerts"`

---

### Task 7: Programador Diario (Scheduler) y Orquestador de Monitoreo

**Files:**
- Create: `src/main/java/com/shiparrival/service/TrackingSchedulerService.java`
- Test: `src/test/java/com/shiparrival/service/TrackingSchedulerServiceTest.java`

**Interfaces:**
- Consumes: `ShipmentService`, `CarrierTrackingAdapterFactory`, `TelegramNotificationService`
- Produces:
  - `TrackingSchedulerService.runDailyTrackingJob() -> int` (anotado con `@Scheduled(cron = "${tracking.schedule.cron:0 0 8 * * *}")`)
  - `TrackingSchedulerService.triggerManualTracking() -> CompletableFuture<Integer>` (ejecución asíncrona manual)

- [ ] **Step 1: Escribir test fallido en `TrackingSchedulerServiceTest`**
  - Test del ciclo de rastreo:
    - 2 embarques activos.
    - El adaptador devuelve resultados de rastreo simulados (uno a 2 días de llegar y otro arribado).
    - Verificar que se actualizan los embarques y se invocan las alertas de Telegram correspondientes.
  - Test de pausas/delays entre consultas para evitar rate-limiting.

- [ ] **Step 2: Ejecutar test para verificar que falla**
  - Run: `.\mvnw.cmd test -Dtest=TrackingSchedulerServiceTest`
  - Expected: FAIL

- [ ] **Step 3: Implementar `TrackingSchedulerService`**
  - Obtener lista de embarques activos (`shipmentService.getActiveShipments()`).
  - Iterar con pausa de cortesía configurable (`tracking.request.delay-ms: 2000`).
  - Obtener adaptador correspondiente, ejecutar `track()`, actualizar embarque y disparar alerta si corresponde.
  - Implementar método para disparo manual asíncrono.

- [ ] **Step 4: Ejecutar test para verificar que pasa**
  - Run: `.\mvnw.cmd test -Dtest=TrackingSchedulerServiceTest`
  - Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**
  - `git add src/main/java/com/shiparrival/service/TrackingSchedulerService.java src/test/java/com/shiparrival/service/TrackingSchedulerServiceTest.java`
  - `git commit -m "feat: implement TrackingSchedulerService with daily 08:00 AM cron and manual trigger"`

---

### Task 8: Interfaz Web Dashboard con Thymeleaf y Controladores MVC

**Files:**
- Create: `src/main/java/com/shiparrival/controller/ShipmentWebController.java`
- Create: `src/main/resources/templates/dashboard.html`
- Create: `src/main/resources/templates/fragments/navbar.html`
- Create: `src/main/resources/templates/history.html`
- Create: `src/main/resources/static/css/styles.css`
- Test: `src/test/java/com/shiparrival/controller/ShipmentWebControllerTest.java`

**Interfaces:**
- Produces:
  - `GET /` -> Renderiza `dashboard.html` con lista de embarques, métricas (total, activos, próximos a arribar, arribados).
  - `POST /shipments/register` -> Formulario manual (guía y naviera), redirecciona con flash message.
  - `POST /shipments/upload-excel` -> Multipart file upload, procesa con `ShipmentService.importFromExcel`, flash message de resumen.
  - `POST /shipments/track-all` -> Dispara monitoreo en segundo plano.
  - `GET /shipments/{id}/history` -> Vista con bitácora de cambios de ETA y eventos.

- [ ] **Step 1: Escribir test fallido en `ShipmentWebControllerTest`**
  - `@WebMvcTest(ShipmentWebController.class)`
  - Test de renderizado del dashboard (status 200 y modelo con atributos `shipments`, `stats`).
  - Test de registro manual con redirección.
  - Test de subida de archivo Excel con multipart request.

- [ ] **Step 2: Ejecutar test para verificar que falla**
  - Run: `.\mvnw.cmd test -Dtest=ShipmentWebControllerTest`
  - Expected: FAIL

- [ ] **Step 3: Implementar `ShipmentWebController` y plantillas Thymeleaf**
  - Controlador Spring MVC con `@Controller` y `@PostMapping` / `@GetMapping`.
  - Plantilla `dashboard.html` con diseño moderno (Bootstrap 5 o Tailwind via CDN):
    - Tarjetas de resumen (Total, En Tránsito, Próximos a Arribar [≤3 días], Arribados).
    - Botón "Monitorear Ahora" con estado de carga.
    - Botón y modal "Subir Excel (.xlsx)".
    - Formulario rápido "Nueva Guía".
    - Tabla con insignias de estado (Verde = Arribado, Amarillo/Naranja = Próximo a arribar, Azul = En tránsito, Rojo = Error).
    - Columna de acciones para ver historial de cambios de ETA.

- [ ] **Step 4: Ejecutar test para verificar que pasa**
  - Run: `.\mvnw.cmd test -Dtest=ShipmentWebControllerTest`
  - Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**
  - `git add src/main/java/com/shiparrival/controller src/main/resources/templates src/main/resources/static src/test/java/com/shiparrival/controller`
  - `git commit -m "feat: implement Thymeleaf web UI dashboard and MVC controllers"`

---

### Task 9: Verificación de Integración End-to-End

**Files:**
- Create: `src/test/java/com/shiparrival/integration/ShipArrivalEndToEndTest.java`

**Interfaces:**
- Consumes: Todo el stack integrado (Base de datos H2, Servicios, Importación Excel, Scheduler, Adaptador simulado).

- [ ] **Step 1: Escribir test de integración E2E**
  - Crear archivo Excel de prueba con 3 guías de ONE en memoria.
  - Subir el Excel al servicio `importFromExcel`.
  - Ejecutar `runDailyTrackingJob()`.
  - Verificar:
    1. Las 3 guías están en la base de datos H2.
    2. Las fechas de ETA y buques fueron actualizadas desde el adaptador.
    3. La guía con ETA ≤ 3 días fue detectada y tiene `alertSent3Days = true`.
    4. El log de auditoría `ShipmentEventLog` registró la creación y la actualización de ETA.

- [ ] **Step 2: Ejecutar suite de pruebas completa del proyecto**
  - Run: `.\mvnw.cmd verify`
  - Expected: `BUILD SUCCESS` con todos los tests unitarios y de integración pasando al 100%.

- [ ] **Step 3: Commit**
  - `git add src/test/java/com/shiparrival/integration`
  - `git commit -m "test: add comprehensive end-to-end integration test"`
