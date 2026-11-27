# AGENTS.md - Sistema de Monitoreo de Arribo de Buques (ShipArrival)

Este archivo sirve como referencia central para cualquier agente o desarrollador que trabaje en este repositorio. Contiene el estado actual del proyecto, la arquitectura acordada y las tareas pendientes.

---

## 1. Estado Actual del Proyecto

- **Fase actual:** Implementación completa con soporte para ONE, MSC, MAERSK y HAPAG-LLOYD; suite de pruebas pasando (318 tests, 7 omitidos por requerir navegador real). Migración completa al sistema visual ArriVix en las cuatro plantillas, cerrada con el PR #36 (`1b2544f`) y documentación de cierre en `28a1b03`. El monitoreo desatendido ya no depende de un teléfono: Hapag-Lloyd sale por Cloudflare WARP en modo proxy y MSC, Maersk y ONE van directas, así que el ciclo corre sin intervención del operador. Si Chrome falla, un respaldo por ZenRows salva la guía y deja `::warning::` en el run. Se corrigió un falso estado "Arribado" en ONE para transbordos, con migración de auto-recuperación al arranque (`ShipmentDataReactivationMigration`) y reactivación automática de embarques. Optimización ultra-ligera en MSC y MAERSK bloqueando la carga de imágenes (`--blink-settings=imagesEnabled=false`, `managed_default_content_settings.images=2`) y aplicando carga `EAGER` en Maersk para reducir en un 98% la transferencia de red. Pre-descarga y caché de ChromeDriver en GitHub Actions con modo `SE_OFFLINE` y `SE_AVOID_STATS`, eliminando los retrasos de arranque y la telemetría de Selenium. Optimización del ciclo diario en CI activando paralelismo acotado MSC/Maersk (`TRACKING_PARALLEL_ENABLED=true`), reducción del delay de cortesía a 1.0s (`TRACKING_REQUEST_DELAY_MS=1000`), eliminación de delays innecesarios en `MANUAL_SKIP` y empaquetado directo con `-Dmaven.test.skip=true package` para acortar el tiempo total de ejecución. Monitoreo desatendido en GitHub Actions y dashboard desplegable en Render.
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
- **Persistencia Embebida y Local:** H2 Database en modo archivo (`./data/shiparrival_db`), garantizando persistencia entre reinicios a coste cero y sin requerir la instalación de motores de base de datos externos. El dialecto `ShipmentH2Dialect` desactiva las restricciones CHECK automáticas de los enums para que agregar navieras no requiera migrar la base de datos. En PostgreSQL esa misma liberación la hacen al arrancar `AppUserRoleConstraintMigration` (roles) y `ShipmentEnumConstraintMigration` (estado y naviera de `shipments`), porque Hibernate graba el CHECK con los valores del enum del momento.
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
  - `status` (Enum: `PENDING_FIRST_CHECK`, `IN_TRANSIT`, `NEAR_ARRIVAL`, `ARRIVED`, `CANCELLED`, `ERROR_CHECKING`, `MANUAL_CHECK`)
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
- [x] Retención de datos: al final de cada ciclo (`ShipmentRetentionService`) se borran definitivamente los embarques arribados hace más de 7 días junto con su historial, los cancelados, y los que quedaron en `ERROR_CHECKING` con la ETA ya vencida hace más de 7 días (una guía sin ETA se conserva: todavía puede llegar). Plazo configurable con `tracking.retention.days`; `0` desactiva la limpieza.
- [x] Navieras con rastreo automático deshabilitado: `tracking.manual-check-carriers` (por defecto `HAPAG_LLOYD`, lista separada por comas) hace que el ciclo omita esas guías sin llamar al adaptador y las marque como `MANUAL_CHECK` ("Verificar manual" en el dashboard). No cuentan como fallo ni disparan alertas; un operador consulta el ETA a mano. Dejar la lista vacía reactiva el rastreo automático. Una guía ya `ARRIVED` o `CANCELLED` no se degrada.
- [x] Paralelismo acotado MSC/Maersk: máximo 2 consultas en vuelo (1 por carrier), workers que solo invocan adaptadores, aplicación de resultados en el orden original, pausa de cortesía entre autorizaciones y drenado/cancelación segura antes de liberar recursos. Se controla con `tracking.parallel.enabled` (desactivado por defecto) y está pensado para el runner run-once de GitHub Actions.

### Fase 8: Interfaz Web (Thymeleaf)
- [x] Vista principal (dashboard) con tabla de embarques, filtros e insignias de estado (colores según proximidad).
- [x] Modal/Formulario de registro manual individual.
- [x] Componente para subida de archivo Excel con reporte de resultados.
- [x] Botón de "Monitorear Ahora" con retroalimentación visual.
- [x] Modal/Página para ver el historial de cambios de ETA de un embarque.
- [x] Borrado manual condicionado en el dashboard: el botón "Eliminar" aparece solo en guías todavía sin monitorear (`PENDING_FIRST_CHECK`) o cuya consulta a la naviera falló (`ERROR_CHECKING`), criterio centralizado en `ShipmentStatus.canBeDeletedManually()` y compartido por la vista (Thymeleaf) y el servicio. El borrado manual pasa por `ShipmentService.deleteShipmentManually()`, que valida el estado y borra dentro de la misma transacción para que el planificador no pueda mover la guía entre el chequeo y el borrado; el controlador solo traduce el rechazo a un flash de error con la etiqueta del estado. Aplica a `ADMIN` y `OPERADOR` (`currentUser.canOperate()`). La retención automática (`ShipmentRetentionService`) sigue usando `ShipmentService.deleteShipment()`, la variante sin restricción de estado, para purgar arribados, cancelados y errores vencidos.
- [x] Migración completa al sistema visual ArriVix: las cuatro plantillas (`dashboard.html`, `history.html`, `login.html`, `users.html`) comparten topbar oscura, isotipo, tokens `--bg-*` / `--text-*` / `--nav-*` / `--shadow-card` y badges con contraste AAA, con Bootstrap 5 como andamio y no como identidad. `users.html` cerró con `aria-label` en las cinco acciones por fila y en el modal de restablecer contraseña, clases semánticas (`.btn-arri-warn`, `.btn-arri-danger`, `.cell-*`) en lugar de estilos inline, `<main role="main">` y `<h1>`, y un corte responsive propio en 480px. Fusionado a `master` por el PR #36 (`1b2544f`, +217/−62) y desplegado.

### Fase 9: Pruebas E2E y Verificación Final
- [x] Probar flujo completo: Carga manual/Excel -> Rastreo -> Actualización de datos -> Disparo de alerta -> Visualización web.
- [x] Verificar HAPAG-LLOYD con las guías reales `HLCUNG12607EKZE0` y `HLCUNG12608BMJY0`: datos de buque, viaje, puerto y arribo, historial de ETA y supresión de alertas históricas.
- [x] Suite final: 279 tests, 0 fallos, 6 omitidos (pruebas reales de navegador MSC, MAERSK y HAPAG-LLOYD, más integraciones condicionadas).

### Fase 10: Ejecución Desatendida en la Nube
- [x] `TrackingRunOnceRunner`: ejecuta un único ciclo y termina el proceso; se activa con `--tracking.run-once=true`.
- [x] Workflow `.github/workflows/daily-monitoring.yml`: monitoreo diario a las 13:00 UTC (08:00 AM Perú) y disparo manual, compilando el JAR y ejecutándolo sin interfaz web.
- [x] Salida por naviera, sin cruzar fronteras de proxy: **HAPAG-LLOYD** sale por **Cloudflare WARP** en modo proxy (`socks5://127.0.0.1:40000`, solo ella); **MSC**, **MAERSK** y **ONE** salen directas. El proxy se ata a una única propiedad (`hapag-lloyd.proxy`) que solo lee `ChromeHapagLloydTrackingClient`, y el workflow no define `HTTP_PROXY` ni `ALL_PROXY`, así que una caída de WARP no arrastra a las demás navieras. Reemplaza al `exit node` de Tailscale: Hapag bloquea por reputación de IP y la IP residencial del teléfono tampoco pasaba el *Managed Challenge*.
- [x] Chrome + CDP en el runner para HAPAG-LLOYD, sin `xvfb` y sin ChromeDriver: el navegador se lanza fuera de pantalla (`--headless`) con un perfil persistente en `data/hapag-chrome-profile` y se evalúa JavaScript por CDP. `runs-on: ubuntu-24.04` es deliberado, porque `cloudflare-warp` no instala en la imagen `ubuntu-latest` actual.
- [x] Respaldo de HAPAG-LLOYD: si Chrome falla, un cliente compuesto reintenta por **ZenRows** y deja una anotación `::warning::` en el run, para que un camino primario roto no quede tapado por el respaldo. Se activa con `HAPAG_LLOYD_FALLBACK_ENABLED=true` (por defecto `false`, porque Render no tiene Chrome ni WARP y no debe gastar consultas de pago). La excepción, cuando fallan los dos caminos, menciona ambos motivos.
- [x] Credenciales en GitHub Secrets (`SUPABASE_DB_*`, `TELEGRAM_*`, `DISCORD_*`, `ZENROWS_API_KEY`); nunca en el repositorio. `TAILSCALE_AUTHKEY` y `TAILSCALE_EXIT_NODE_IP` ya no los usa el ciclo diario.
- [x] Dashboard desplegable en Render (`render.yaml` + `Dockerfile`) consumiendo la misma base PostgreSQL de Supabase.
- [x] Acceso con cuentas por persona: filtro propio (`AuthFilter`) + BCrypt, sin Spring Security completo. Roles `ADMIN` (administra cuentas además de operar), `OPERADOR` (registra, importa Excel, elimina guías y dispara el monitoreo, sin tocar cuentas) y `USER` (solo consulta). Sesión deslizante de 1 h renovada en cada petición autenticada, cookie `HttpOnly` + `SameSite=Lax`, rotación del identificador tras el login y bloqueo de 15 minutos tras 5 intentos fallidos.
- [x] El primer administrador nace de `ADMIN_USERNAME` / `ADMIN_PASSWORD`; no hay registro público ni recuperación por correo (un administrador restablece la clave).
- [x] Botón "Monitorear Ahora" que dispara el workflow real de GitHub Actions (`workflow_dispatch`) con un token fine-grained de permiso `Actions: Read and write` (`GITHUB_TOKEN`, `GITHUB_DISPATCH_ENABLED=true`). Sin token configurado, el botón conserva el ciclo local en proceso.
- [x] El runner publica su avance en `tracking_run_progress` (identificado con `github.run_id` vía `TRACKING_RUN_ID`); el dashboard lo lee en `GET /shipments/live-status` y dibuja la barra. Si WARP no conecta, el workflow emite `::warning::` y el ciclo sigue con MSC, Maersk y ONE por conexión directa.
- [x] Horas siempre en `America/Lima` (`app.timezone`), independientemente de la zona del visitante o del anfitrión.

---

## 5. Convenciones para agentes

### Evidencia antes de declarar terminado

Ningún agente da una tarea por terminada sin **pegar la salida literal** de la verificación que le corresponde. Un "está hecho", "debería pasar" o un resumen del resultado no es evidencia y no se acepta como entrega.

| Tipo de tarea | Verificación obligatoria | Qué se pega |
|---|---|---|
| Cambio en código Java o en tests | `.\mvnw.cmd test` | El bloque `[INFO] Results:` con el conteo (tests, fallos, errores, omitidos) y la línea `BUILD SUCCESS` / `BUILD FAILURE` |
| Cambio en plantillas Thymeleaf | La suite, porque `@SpringBootTest` renderiza la plantilla real | Lo mismo que arriba; un `@WebMvcTest` no prueba la vista |
| Cambio en documentación | Releer el archivo escrito | El fragmento tal como quedó en disco |
| Cambio de configuración | Releer el archivo | El bloque modificado, completo |

Si la verificación falla y no se puede resolver, se reporta el **mensaje de error literal** y el estado real. Nunca se relajan aserciones ni se omite una prueba para dejar la suite en verde.

### Alcance de un encargo

Un encargo = un entregable verificable. Si una tarea necesita leer varios archivos, editar tres y además ejecutar y depurar la suite, se parte en entregas sucesivas en lugar de pedir todo de golpe: los límites de pasos por agente (`steps` en `.opencode/agents/*.md`) no dan para encargos grandes, y el resultado es trabajo a medias sin evidencia.

### Permisos de escritura

Cada agente edita solo las rutas declaradas en su ficha. Antes de delegar una edición, se revisa el bloque `permissions` del agente destino: una ruta no listada falla con `Permission denied` sin importar el contenido. En particular, solo `project-manager` edita `AGENTS.md` y `docs/status/**`, y solo `reclutador` edita las fichas de `.opencode/agents/**`.
