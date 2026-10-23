# AGENTS.md - Sistema de Monitoreo de Arribo de Buques (ShipArrival)

Este archivo sirve como referencia central para cualquier agente o desarrollador que trabaje en este repositorio. Contiene el estado actual del proyecto, la arquitectura acordada y las tareas pendientes.

---

## 1. Estado Actual del Proyecto

- **Fase actual:** Implementación completa con soporte para ONE y MSC; suite de pruebas pasando (55 tests, 1 omitido por requerir navegador real).
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
4. Enviar alertas gratuitas a través de un **Bot de Telegram** cuando un buque esté a **≤ 3 días** de arribar a puerto (ej. Manzanillo, México) o cuando confirme su arribo final.
5. Gestionar todo desde un dashboard web limpio en `http://localhost:8080`.

### 2.2 Patrones y Principios Clave
- **Patrón Adapter / Strategy (`CarrierTrackingAdapter`):** 
  - Desacopla la lógica de rastreo del proveedor naviero.
  - Implementaciones actuales: **ONE (*Ocean Network Express*)** mediante su API v2 y **MSC** mediante la API interna de su portal, invocada desde Chrome headless para superar la protección de Akamai.
  - Permite agregar a futuro Maersk o portales gubernamentales mexicanos (ej. ASIPONA) creando nuevos adaptadores sin tocar el resto de la aplicación.
- **Persistencia Embebida y Local:** H2 Database en modo archivo (`./data/shiparrival_db`), garantizando persistencia entre reinicios a coste cero y sin requerir la instalación de motores de base de datos externos.
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
| **Rastreo MSC** | Selenium + Chrome headless (portal protegido por Akamai) |
| **Build Tool** | Maven Wrapper (`mvnw`) |

---

## 3. Modelo de Datos Principal

- **`Shipment`:**
  - `id` (Long, PK)
  - `trackingNumber` (String, ej. "TESTBL900001")
  - `carrier` (Enum: `ONE`, `MSC`, etc.)
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

### Fase 4: Adaptadores de Rastreo (ONE y MSC)
- [x] Diseñar interfaz `CarrierTrackingAdapter` y modelo `TrackingResult`.
- [x] Implementar `OneLineTrackingAdapter` (cliente HTTP resiliente para consultar guías y extraer ETA, buque, viaje, puerto y estatus).
- [x] Implementar `MscTrackingAdapter` y `SeleniumMscTrackingClient` (Chrome headless para obtener las cookies de Akamai y consultar la API interna de MSC).
- [x] Factoría `CarrierTrackingAdapterFactory` con liberación de recursos al finalizar cada ciclo.
- [x] Pruebas unitarias con respuestas simuladas (JSON/HTML) y prueba de integración real opcional con `MSC_BROWSER_TEST=true`.

### Fase 5: Servicios de Negocio e Importación Excel
- [x] Implementar `ExcelImportService` para procesar archivos `.xlsx` y extraer guías.
- [x] Implementar `ShipmentService` (creación individual, deduplicación, actualización de estado e historial).
- [x] Implementar regla de detección de proximidad (ETA ≤ 3 días).

### Fase 6: Servicio de Notificaciones Telegram
- [x] Implementar `TelegramNotificationService` para enviar mensajes formateados vía Telegram Bot API.
- [x] Control de envío único por estado para evitar spam repetitivo.

### Fase 7: Programador de Tareas (Scheduler)
- [x] Implementar `TrackingSchedulerService` (`@Scheduled` a las 08:00 AM) para iterar sobre embarques activos con pausas entre peticiones.
- [x] Soporte para ejecución manual asíncrona ("Monitorear Ahora").

### Fase 8: Interfaz Web (Thymeleaf)
- [x] Vista principal (dashboard) con tabla de embarques, filtros e insignias de estado (colores según proximidad).
- [x] Modal/Formulario de registro manual individual.
- [x] Componente para subida de archivo Excel con reporte de resultados.
- [x] Botón de "Monitorear Ahora" con retroalimentación visual.
- [x] Modal/Página para ver el historial de cambios de ETA de un embarque.

### Fase 9: Pruebas E2E y Verificación Final
- [x] Probar flujo completo: Carga manual/Excel -> Rastreo -> Actualización de datos -> Disparo de alerta -> Visualización web.
