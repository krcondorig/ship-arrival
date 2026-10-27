# AGENTS.md - Sistema de Monitoreo de Arribo de Buques (ShipArrival)

Este archivo sirve como referencia central para cualquier agente o desarrollador que trabaje en este repositorio. Contiene el estado actual del proyecto, la arquitectura acordada y las tareas pendientes.

---

## 1. Estado Actual del Proyecto

- **Fase actual:** Implementación completa con soporte para ONE, MSC, MAERSK y HAPAG-LLOYD; suite de pruebas pasando (81 tests, 3 omitidos por requerir navegador real). Monitoreo desatendido en GitHub Actions y dashboard desplegable en Render.
- **Repositorio Git:** Rama `master`, con Maven Wrapper autónomo y suite de tests verde.
- **Entorno de ejecución:** 
  - Sistema Operativo: Windows (win32)
  - Java: Java 21 LTS (`21.0.11`)
  - Herramienta de compilación: Maven Wrapper (`mvnw` / `mvnw.cmd`)
- **Estatus del código:** Base de código completamente operativa y probada.

---

## 2. Arquitectura de Software Acordada

### 2.1 Visión General
Aplicación web monolítica ligera en **Java 21** con **Spring Boot 3**, que permite:
1. Registrar guías de conocimiento (*Bills of Lading* - B/L) tanto manualmente como mediante carga masiva de archivos Excel (`.xlsx`).
2. Consultar y extraer automáticamente el estado, buque, viaje, puerto y fecha estimada de arribo (ETA) desde las navieras.
3. Monitorear los embarques de manera desatendida mediante una tarea programada diaria a las **08:00 AM** y con opción de disparo manual bajo demanda.
4. Enviar alertas gratuitas a través de un **Bot de Telegram** y/o un **Webhook de Discord** cuando un buque esté a **≤ 3 días** de arribar a puerto (ej. Manzanillo, México) o cuando confirme su arribo final.
5. Gestionar todo desde un dashboard web limpio en `http://localhost:8080`.

### 2.2 Patrones y Principios Clave
- **Patrón Adapter / Strategy (`CarrierTrackingAdapter`):** 
  - Desacopla la lógica de rastreo del proveedor naviero.
  - Implementaciones actuales: **ONE (*Ocean Network Express*)** mediante su API v2, **MSC** mediante la API interna de su portal, **MAERSK** mediante la API interna de su portal y **HAPAG-LLOYD** mediante las tablas públicas de su portal. MSC y MAERSK se consultan desde Chrome headless para superar la protección de Akamai; en MAERSK se abre un navegador nuevo por guía porque Akamai solo permite una consulta por sesión.
  - **HAPAG-LLOYD** no requiere credenciales de API: usa Chrome normal fuera de pantalla con un perfil persistente y control por CDP (Chrome DevTools Protocol), sin ChromeDriver. Cloudflare bloquea peticiones directas, Chrome headless y la adhesión de Selenium, por lo que el navegador se lanza primero con la URL `?blno=<B/L>`, se espera el título final y solo después se evalúa JavaScript por CDP. El perfil se conserva en `data/hapag-chrome-profile` para reutilizar la cookie de Cloudflare entre ciclos.
  - Permite agregar a futuro portales gubernamentales mexicanos (ej. ASIPONA) creando nuevos adaptadores sin tocar el resto de la aplicación.
- **Persistencia Embebida y Local:** H2 Database en modo archivo (`./data/shiparrival_db`), garantizando persistencia entre reinicios a coste cero y sin requerir la instalación de motores de base de datos externos. El dialecto `ShipmentH2Dialect` desactiva las restricciones CHECK automáticas de los enums para que agregar navieras no requiera migrar la base de datos.
- **Control de Rate-Limiting:** Consultas en bloque con pausas de cortesía y reintentos para evitar bloqueos por parte de los portales web navieros.

### 2.3 Stack Tecnológico (100% Gratuito)
| Componente | Tecnología |
|---|---|
| **Lenguaje** | Java 21 LTS |
| **Framework** | Spring Boot 3.4+ (Web, Data JPA, Scheduling, Validation) |
| **Persistencia** | H2 Database (File mode) + Hibernate |
| **Procesamiento Excel** | Apache POI (`poi-ooxml`) |
| **Frontend / UI** | Thymeleaf + Tailwind CSS / Bootstrap 5 |
| **Notificaciones** | Telegram Bot API + Discord Webhook (HTTP REST gratuito) |
| **Rastreo MSC / MAERSK** | Selenium + Chrome headless (portales protegidos por Akamai) |
| **Rastreo HAPAG-LLOYD** | Chrome normal fuera de pantalla + CDP (portal protegido por Cloudflare, sin credenciales) |
| **Build Tool** | Maven Wrapper (`mvnw`) |

---

## 3. Modelo de Datos Principal

- **`Shipment`:**
  - `id` (Long, PK)
  - `trackingNumber` (String, ej. "TESTBL900001")
  - `carrier` (Enum: `ONE`, `MSC`, `MAERSK`, `HAPAG_LLOYD`, `UNKNOWN`), persistido como `VARCHAR(32)` para permitir nuevas navieras sin migrar el tipo de columna.
  - `containerNumber` (String)
  - `vesselName` (String)
  - `voyageNumber` (String)
  - `portOfLoading` (String)
  - `portOfDischarge` (String)
  - `departureDate` (LocalDateTime, ATD)
  - `estimatedArrival` (LocalDateTime, ETA)
  - `actualArrival` (LocalDateTime, ATA)
  - `status` (Enum: `PENDING_FIRST_CHECK`, `IN_TRANSIT`, `NEAR_ARRIVAL`, `ARRIVED`, `CANCELLED`, `ERROR_CHECKING`)
  - `lastCheckedAt` (LocalDateTime)
  - `active` (boolean)
  - `alertSent3Days` (boolean)
  - `alertSentArrived` (boolean)
- **`ShipmentEventLog`:**
  - `id` (Long, PK)
  - `shipment` (FK a `Shipment`)
  - `eventType` (String)
  - `previousEta` (LocalDateTime)
  - `newEta` (LocalDateTime)
  - `description` (String)
  - `recordedAt` (LocalDateTime)

---

## 4. Tareas del Roadmap

### Fase 1: Creación del Plan de Implementación
- [x] Ejecutar la habilidad `writing-plans` para desglosar la construcción en tareas atómicas con pruebas automatizadas (TDD).

### Fase 2: Scaffolding y Configuración Base
- [x] Generar la estructura del proyecto Spring Boot 3 con Maven Wrapper.
- [x] Configurar `pom.xml` con dependencias: Spring Web, Thymeleaf, Spring Data JPA, H2, Apache POI, Lombok, Testcontainers/JUnit.
- [x] Configurar `application.properties` (H2 en archivo, H2 console, variables de Telegram).

### Fase 3: Entidades y Repositorios
- [x] Implementar entidades `Shipment` y `ShipmentEventLog`.
- [x] Implementar repositorios `ShipmentRepository` y `ShipmentEventLogRepository`.
- [x] Pruebas unitarias y de integración para la persistencia.

### Fase 4: Adaptadores de Rastreo (ONE, MSC, MAERSK y HAPAG-LLOYD)
- [x] Diseñar interfaz `CarrierTrackingAdapter` y modelo `TrackingResult`.
- [x] Implementar `OneLineTrackingAdapter` (cliente HTTP resiliente para consultar guías y extraer ETA, buque, viaje, puerto y estatus).
- [x] Implementar `MscTrackingAdapter` y `SeleniumMscTrackingClient` (Chrome headless para obtener las cookies de Akamai y consultar la API interna de MSC).
- [x] Implementar `MaerskTrackingAdapter` y `SeleniumMaerskTrackingClient` (Chrome headless con interceptor de `fetch`, un navegador nuevo por guía para evitar el bloqueo de Akamai a la segunda consulta).
- [x] Implementar `HapagLloydTrackingAdapter` y `ChromeHapagLloydTrackingClient` (Chrome normal fuera de pantalla con perfil persistente y CDP; extrae el primer contenedor, el último movimiento real del buque y el arribo/descarga).
- [x] Factoría `CarrierTrackingAdapterFactory` con liberación de recursos al finalizar cada ciclo.
- [x] Pruebas unitarias con respuestas simuladas (JSON/HTML) y pruebas de integración reales opcionales con `MSC_BROWSER_TEST=true`, `MAERSK_BROWSER_TEST=true` y `HAPAG_LLOYD_BROWSER_TEST=true`.

### Fase 5: Servicios de Negocio e Importación Excel
- [x] Implementar `ExcelImportService` para procesar archivos `.xlsx` y extraer guías.
- [x] Implementar `ShipmentService` (creación individual, deduplicación, actualización de estado e historial).
- [x] Implementar regla de detección de proximidad (ETA ≤ 3 días).

### Fase 6: Servicios de Notificaciones (Telegram y Discord)
- [x] Implementar `TelegramNotificationService` para enviar mensajes formateados vía Telegram Bot API.
- [x] Implementar `DiscordNotificationService` mediante Webhook, configurado con variables de entorno privadas (`.env`).
- [x] Control de envío único por estado para evitar spam repetitivo.

### Fase 7: Programador de Tareas (Scheduler)
- [x] Implementar `TrackingSchedulerService` (`@Scheduled` a las 08:00 AM) para iterar sobre embarques activos con pausas entre peticiones.
- [x] Soporte para ejecución manual asíncrona ("Monitorear Ahora").
- [x] Suprimir la alerta de arribo cuando el ATA es anterior a la fecha de registro del embarque (arribo histórico), evitando notificaciones tardías.

### Fase 8: Interfaz Web (Thymeleaf)
- [x] Vista principal (dashboard) con tabla de embarques, filtros e insignias de estado (colores según proximidad).
- [x] Modal/Formulario de registro manual individual.
- [x] Componente para subida de archivo Excel con reporte de resultados.
- [x] Botón de "Monitorear Ahora" con retroalimentación visual.
- [x] Modal/Página para ver el historial de cambios de ETA de un embarque.

### Fase 9: Pruebas E2E y Verificación Final
- [x] Probar flujo completo: Carga manual/Excel -> Rastreo -> Actualización de datos -> Disparo de alerta -> Visualización web.
- [x] Verificar HAPAG-LLOYD con las guías reales `HLCUNG12607EKZE0` y `HLCUNG12608BMJY0`: datos de buque, viaje, puerto y arribo, historial de ETA y supresión de alertas históricas.
- [x] Suite final: 81 tests, 0 fallos, 3 omitidos (pruebas reales de navegador MSC, MAERSK y HAPAG-LLOYD).

### Fase 10: Ejecución Desatendida en la Nube
- [x] `TrackingRunOnceRunner`: ejecuta un único ciclo y termina el proceso; se activa con `--tracking.run-once=true`.
- [x] Workflow `.github/workflows/daily-monitoring.yml`: monitoreo diario a las 13:00 UTC (08:00 AM Perú) y disparo manual, compilando el JAR y ejecutándolo sin interfaz web.
- [x] Salida de las navieras mediante Tailscale: el runner enruta todo su tráfico por el `exit node` del teléfono Android, evitando los bloqueos de Akamai y Cloudflare sobre IPs de datacenter.
- [x] Chrome + `xvfb` en el runner para HAPAG-LLOYD, con caché del perfil `data/hapag-chrome-profile` entre ejecuciones.
- [x] Credenciales en GitHub Secrets (`SUPABASE_DB_*`, `TELEGRAM_*`, `DISCORD_*`, `TAILSCALE_AUTHKEY`); nunca en el repositorio.
- [x] Dashboard desplegable en Render (`render.yaml` + `Dockerfile`) consumiendo la misma base PostgreSQL de Supabase.
- [x] Acceso con cuentas por persona: filtro propio (`AuthFilter`) + BCrypt, sin Spring Security completo. Roles `ADMIN` (administra usuarios y dispara el monitoreo) y `USER` (solo consulta). Sesión de 12 h, cookie `HttpOnly` + `SameSite=Lax`, rotación del identificador tras el login y bloqueo de 15 minutos tras 5 intentos fallidos.
- [x] El primer administrador nace de `ADMIN_USERNAME` / `ADMIN_PASSWORD`; no hay registro público ni recuperación por correo (un administrador restablece la clave).
- [x] Botón "Monitorear Ahora" que dispara el workflow real de GitHub Actions (`workflow_dispatch`) con un token fine-grained de permiso `Actions: Read and write` (`GITHUB_TOKEN`, `GITHUB_DISPATCH_ENABLED=true`). Sin token configurado, el botón conserva el ciclo local en proceso.
- [x] El runner publica su avance en `tracking_run_progress` (identificado con `github.run_id` vía `TRACKING_RUN_ID`); el dashboard lo lee en `GET /shipments/live-status` y dibuja la barra. Si el exit node falla, el mensaje lo explica en lugar de dejar la barra detenida.
- [x] Horas siempre en `America/Lima` (`app.timezone`), independientemente de la zona del visitante o del anfitrión.
