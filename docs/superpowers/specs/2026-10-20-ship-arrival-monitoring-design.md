# Especificación de Diseño: Sistema de Monitoreo de Arribo de Buques (ShipArrival)

**Fecha:** 2026-10-20  
**Estado:** Borrador para revisión de constructor  

---

## 1. Intención y Justificación (El "Por Qué")

En el sector logístico y de comercio exterior en México, coordinar la recepción de mercancías que arriban por vía marítima (por ejemplo, a través del puerto de Manzanillo, Colima) exige conocer con exactitud la fecha estimada de llegada (ETA - *Estimated Time of Arrival*) de los buques portacontenedores.

Actualmente, el monitoreo se realiza de manera 100% manual: un operador debe consultar cada mañana (alrededor de las 08:00 AM) entre 20 y 100 guías de embarque (*Bills of Lading* - B/L) ingresando una a una en los portales web de las líneas navieras (como Ocean Network Express - ONE), buscando los contenedores y revisando las fechas estimadas de llegada. Esta tarea consume tiempo valioso, es repetitiva y está expuesta a errores humanos o descuidos en cambios repentinos de itinerario.

El objetivo de este sistema es **automatizar por completo la extracción, seguimiento y emisión de alertas de los arribos marítimos**, proporcionando una interfaz web intuitiva, importación masiva desde archivos Excel, ejecución diaria desatendida y alertas inmediatas y gratuitas vía **Telegram** cuando un buque esté a **3 días o menos** de tocar puerto.

---

## 2. Metas, No-Metas y Anti-Metas

### Metas (Goals)
1. **Gestión de Embarques (CRUD e Importación Masiva):**
   - Permitir registrar guías manualmente mediante formulario web.
   - Permitir la carga de 20 a 100+ guías mediante archivo Excel (`.xlsx` / `.xls`) con validación y reporte de guías agregadas/duplicadas.
   - Listar todos los embarques con su información crítica: No. de Guía (B/L), Naviera, Buque, Viaje, Puerto de Destino, Estado Actual, ETA/ATA, Días Restantes para Arribo y Última Actualización.
2. **Motor de Rastreo Automatizado y Extensible (Patrón Adapter/Strategy):**
   - Implementar un conector funcional para la naviera **ONE (Ocean Network Express)** capaz de obtener el estado, buque, viaje, puerto de descarga y fecha estimada de arribo (ETA).
   - Diseñar la interfaz `CarrierTrackingAdapter` de modo que agregar otras navieras (como MSC, Maersk, Hapag-Lloyd) o consultas en portales del gobierno mexicano (ej. ASIPONA Manzanillo / SEMAR) sea tan sencillo como implementar una nueva clase sin modificar el núcleo de la aplicación.
3. **Monitoreo Programado (Scheduler):**
   - Tarea programada diaria (cron `0 0 8 * * *` para las 08:00 AM) que verifique automáticamente todas las guías activas (no arribadas / no finalizadas).
   - Botón en la interfaz web para forzar un "Monitorear Ahora" bajo demanda.
4. **Regla de Alertas y Notificaciones Gratuitas:**
   - Detectar cuando un embarque se encuentre a **3 días o menos** de la fecha de arribo a puerto (ETA).
   - Enviar alerta automática a través de un canal gratuito: **Bot de Telegram** (mensaje formateado con Guía, Buque, ETA, Puerto y Estatus).
   - Evitar spam: no enviar alertas duplicadas el mismo día para el mismo estado, pero sí alertar si el ETA sufre una reprogramación o si cambia de estado (ej. "En travesía" -> "Próximo a arribar" -> "Arribado").
5. **Historial de Cambios de ETA:**
   - Registrar una bitácora de cada consulta para visualizar si la naviera adelantó o retrasó el arribo.
6. **Coste Cero (100% Free Stack):**
   - La aplicación debe funcionar sin costos de suscripción, licencias comerciales ni dependencias de servicios en la nube de pago.

### No-Metas (Non-Goals)
- No se implementará en la fase inicial la integración de múltiples navieras más allá de ONE (la arquitectura quedará lista, pero los adaptadores de MSC u otros se construirán en iteraciones posteriores).
- No se requiere autenticación de usuarios multi-inquilino (*multi-tenant*) compleja en esta primera versión; el software operará en red local / ambiente de trabajo de operador.
- No se implementará pasarela de pagos ni facturación.

### Anti-Metas (Anti-Goals)
- **Cero APIs de pago:** No depender de agregadores de rastreo de pago por consulta (como Searates, Vizion API, Terminal49, etc.) que requieran tarjetas de crédito o suscripciones mensuales.
- **Sin bloqueos de interfaz:** La consulta web masiva no debe congelar el servidor ni la interfaz de usuario; las consultas deben ejecutarse de forma asíncrona en segundo plano con control de concurrencia y pausas para evitar ser bloqueados por rate-limiting de las navieras.
- **Sin pérdida de datos en reinicio:** No usar almacenamiento puramente volátil en memoria RAM (`In-Memory`); debe persistirse en disco.

---

## 3. Restricciones Técnicas

- **Entorno de ejecución:** Windows 10/11 / Multiplataforma, Java 21 LTS instalado en el equipo.
- **Herramienta de construcción:** Maven con Wrapper (`mvnw` / `pom.xml`) para que compile y corra de forma autónoma sin exigir instalar Maven en el PATH global del sistema operativo.
- **Persistencia embebida:** H2 Database en modo archivo (`jdbc:h2:file:./data/shiparrival_db;DB_CLOSE_ON_EXIT=FALSE;AUTO_RECONNECT=TRUE`) para que los datos persistan en disco sin exigir la instalación ni configuración de un motor como PostgreSQL o MySQL.
- **Rastreo Web:** Debe ser resiliente. Si la naviera cambia dinámicamente o bloquea cabeceras simples, se empleará un cliente HTTP robusto (Spring `RestClient` / `WebClient` / JSoup / headless scraper o llamadas a los endpoints públicos de e-commerce de ONE) con manejo de timeouts, User-Agent de navegador y reintentos.

---

## 4. Decisiones de Arquitectura Tomadas

1. **Stack Tecnológico:**
   - **Framework:** Spring Boot 3.4+ con Spring Web, Spring Data JPA, Spring Scheduling.
   - **Base de Datos:** H2 Database persistente en archivo local.
   - **Procesamiento de Archivos:** Apache POI (`poi-ooxml`) para lectura transparente de hojas Excel `.xlsx`.
   - **UI / Frontend:** Spring Boot con Thymeleaf + Tailwind CSS (o Bootstrap 5 moderno) para una interfaz limpia, responsiva y utilizable desde cualquier navegador en `http://localhost:8080`.
   - **Notificaciones:** Telegram Bot API (mediante peticiones HTTP estándar a `https://api.telegram.org/bot<TOKEN>/sendMessage`). Los parámetros `botToken` y `chatId` se configuran en `application.properties` o desde una pantalla de ajustes en la interfaz.

2. **Modelo de Dominio:**
   - `Shipment` (Embarque):
     - `id` (Long, PK)
     - `trackingNumber` (String, ej. "TESTBL900001", único por naviera)
     - `carrier` (Enum: `ONE`, `MSC`, etc.)
     - `containerNumber` (String, opcional o contenedor principal representativo)
     - `vesselName` (String, ej. "ONE FANTASTIC")
     - `voyageNumber` (String, ej. "006E")
     - `portOfLoading` (String)
     - `portOfDischarge` (String, ej. "MANZANILLO, MEXICO")
     - `departureDate` (LocalDateTime, ATD)
     - `estimatedArrival` (LocalDateTime, ETA)
     - `actualArrival` (LocalDateTime, ATA, nullable)
     - `status` (Enum: `PENDING_FIRST_CHECK`, `IN_TRANSIT`, `NEAR_ARRIVAL`, `ARRIVED`, `CANCELLED`, `ERROR_CHECKING`)
     - `lastCheckedAt` (LocalDateTime)
     - `active` (boolean, true mientras no haya arribado)
     - `alertSent3Days` (boolean, bandera para evitar alertas repetidas)
     - `alertSentArrived` (boolean)
     - `createdAt`, `updatedAt`
   - `ShipmentEventLog` (Historial de Eventos y Cambios de ETA):
     - `id` (Long, PK)
     - `shipment` (FK ManyToOne a `Shipment`)
     - `eventType` (String, ej. "ETA_UPDATED", "STATUS_CHANGE", "SCRAPE_SUCCESS", "SCRAPE_FAILED")
     - `previousEta` (LocalDateTime)
     - `newEta` (LocalDateTime)
     - `description` (String)
     - `recordedAt` (LocalDateTime)

3. **Arquitectura de Software (Capas y Patrones):**
   - **Capa Web / Controlador:**
     - `ShipmentWebController`: Vista principal con dashboard, formulario de registro manual, subida de Excel y botón "Monitorear Ahora".
     - `ShipmentApiController`: Endpoints REST para operaciones asíncronas si se requieren.
   - **Capa de Servicios:**
     - `ShipmentService`: Lógica de negocio (creación, actualización, deduplicación, cálculo de proximidad).
     - `ExcelImportService`: Parseo de Excel, extracción de columnas de números de guía y reporte de importación.
     - `TrackingSchedulerService`: Orquestador cron (`@Scheduled(cron = "0 0 8 * * *")`) que consulta los embarques activos.
     - `TelegramNotificationService`: Construcción de mensajes y llamada a la API de Telegram.
   - **Capa de Adaptadores de Rastreo (`CarrierTrackingAdapter`):**
     - Interfaz: `TrackingResult track(String trackingNumber)`
     - Implementación inicial: `OneLineTrackingAdapter`
     - Factoría: `CarrierTrackingAdapterFactory` que selecciona el adaptador según la naviera del embarque.

4. **Flujo de Notificación de Alerta (Regla de los 3 Días):**
   - Al actualizar el `estimatedArrival` de un embarque activo:
     - Si `ChronoUnit.DAYS.between(LocalDate.now(), estimatedArrival.toLocalDate()) <= 3` y `alertSent3Days == false`:
       - Marcar `status = NEAR_ARRIVAL`
       - Disparar mensaje a Telegram:
         ```text
         🚢 ALERTA DE ARRIBO PRÓXIMO (≤ 3 DÍAS)
         📦 Guía: TESTBL900001
         🏢 Naviera: ONE
         ⚓ Buque: ONE FANTASTIC (Viaje 006E)
         📍 Destino: MANZANILLO, MEXICO
         📅 ETA: 2026-10-20 09:00 hrs
         ⏳ Días restantes: 1 día(s)
         ℹ️ Estado: En navegación hacia puerto
         ```
       - Marcar `alertSent3Days = true`.
     - Si el buque ya arribó: disparar alerta de "Buque Arribado a Puerto" y marcar `active = false`.

---

## 5. Lo que se Deja a Criterio del Constructor (Builder)

- La estructura exacta de los DTOs y mapeos de datos internos del adaptador de ONE (ya sea consumiendo la API JSON interna de `ecomm.one-line.com` o parseando la estructura devuelta).
- Estilo y diseño visual de la interfaz web en Thymeleaf (asegurando badges de colores para los estados: verde para arribado, amarillo/naranja para próximo a arribar, azul para en tránsito, rojo para errores).
- Intervalo de delay / pausa entre peticiones en lote (por defecto 2 a 3 segundos entre consulta para respetar los servidores de la naviera y no ser baneado).
- Formato de plantilla de Excel aceptada (flexible: detectar automáticamente si la primera columna o la columna titulada "Guía" / "BL" / "Tracking" contiene los números de guía).
