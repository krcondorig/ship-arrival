# Diseño: tracking histórico asistido de Hapag-Lloyd

## Intención
Permitir que un operador autorizado solicite el histórico de un B/L, atienda manualmente cualquier verificación que presente Hapag-Lloyd en un navegador remoto, y guarde en Supabase eventos históricos reales por contenedor. La aplicación no automatiza, resuelve ni evade CAPTCHA/Cloudflare.

## Estado actual
- Java 21, Spring Boot 3.4.2, Maven Wrapper.
- Persistencia de producción por JPA/JDBC a PostgreSQL de Supabase con credenciales de entorno. No hay Flyway/Liquibase; existe `ddl-auto=update` y migraciones puntuales `ApplicationRunner` para constraints.
- `Shipment` y `ShipmentEventLog` cubren datos actuales e historial de ETA; no existe entidad de jobs históricos ni de eventos por contenedor.
- `ShipmentWebController` sirve dashboard y acciones; `AuthFilter` adjunta `CurrentUser`; `ADMIN` y `OPERADOR` operan, `USER` consulta.
- `.github/workflows/daily-monitoring.yml` monitorea navieras desatendidas. No debe operar navegadores interactivos.

## Objetivos y no objetivos
### Objetivos
- Crear trabajo persistido de consulta histórica por guía/B/L.
- Crear una sesión remota y ofrecer al operador autorizado un Live View temporal.
- Mantener una conexión de proceso de confianza a la misma sesión para extraer el DOM después de la intervención humana.
- Validar e insertar eventos de cada contenedor de forma idempotente.
- Marcar `COMPLETED` únicamente cuando se hayan guardado eventos reales validados.
- Gestionar expiración, cancelación, errores, cierre de sesión, reintentos, auditoría y costos.

### No objetivos
- Resolver o automatizar desafíos Cloudflare/CAPTCHA.
- Usar `solveCaptchas`, stealth, proxies u otras funciones de evasión.
- Cambiar `Shipment.estimatedArrival`, estado operativo o alertas automáticamente en la primera versión.
- Mover el monitoreo existente de ONE/MSC/MAERSK a otro workflow.

## Hyperbrowser: capacidades verificadas y límites
Documentación consultada el 2026-10-29:
- `POST https://api.hyperbrowser.ai/api/session` crea una sesión autenticada con `x-api-key`; la respuesta incluye `id`, `liveUrl`, `wsEndpoint`, `computerActionEndpoint` y estado.
- Live View permite interacción por defecto. Cualquiera con la URL puede ver y posiblemente interactuar; su token expira a las 12 horas. Debe tratarse como credencial bearer.
- `viewOnlyLiveView` deshabilita interacción, así que no se establecerá para el flujo del operador.
- Playwright puede conectar a `wsEndpoint` por CDP y controlar el mismo navegador. La app/worker conserva la conexión mientras el operador interactúa.
- `timeoutMinutes` acepta 1–720; hay cierre automático y endpoint explícito para parar sesión. Se requiere cierre explícito en todos los caminos terminales.
- Las grabaciones rrweb pueden capturar cambios DOM, pero su API documentada entrega URL de grabación tras parar/procesar la sesión y no es un callback de eventos de negocio. No se usarán como ruta primaria de ingestión.
- Documentación incluye CAPTCHA solver/stealth; están expresamente fuera de alcance y deben omitirse/deshabilitarse.

La documentación no garantiza que el DOM de Hapag cargue ni que sus selectores/datos sean extraíbles. Antes de integrar la ingestión se requiere prueba manual con el portal y comprobar que el worker conectado por CDP obtiene el DOM de la sesión intervenida. La prueba SeleniumBase local es una referencia del formato observado, no una validación del proveedor remoto.

## Arquitectura propuesta
1. Operador (`ADMIN`/`OPERADOR`) solicita un job para un `Shipment` HAPAG_LLOYD existente.
2. Spring persiste `PENDING` e idempotency key.
3. Un worker confiable crea la sesión Hyperbrowser sin parámetros de solver/stealth, abre la página pública del tracking por B/L y persiste el ID de sesión/expiración.
4. El operador accede al Live View mediante endpoint autenticado y autorización del job. El backend obtiene una Live URL fresca desde la API del proveedor y la entrega solo al operador autorizado; no se almacena ni registra la URL ni el `wsEndpoint`.
5. Worker Playwright/CDP, ejecutado fuera del navegador del usuario y conectado a la misma sesión, espera a que la vista de resultados esté lista. No hace clic ni interactúa con retos; el operador realiza la acción humana. Extrae filas del DOM para cada contenedor.
6. Worker transmite un DTO de eventos al backend por canal interno autenticado, o invoca el servicio de aplicación directamente si vive en el mismo proceso. La decisión final de empaquetado (worker embebido versus servicio separado) se toma tras spike de CDP y revisión de límites del despliegue Render.
7. Backend valida guía, contenedor, fechas, campos mínimos y eventos; escribe eventos idempotentemente en una transacción. Solo entonces marca `COMPLETED`.
8. Job cancelado/terminado/expirado para la sesión remota. Procesador de recuperación reconcilia sesiones activas huérfanas y trabajos expirados.

### Decisión crítica: extracción y retorno
Live View únicamente da visibilidad/interacción; no entrega eventos estructurados. La primera opción a probar es worker de confianza Playwright/CDP con `wsEndpoint` mantenido en memoria y referenciado por `provider_session_id`. El operador no recibe API key, CDP endpoint, credenciales de Supabase ni token backend. Si CDP no ve el mismo estado DOM después de intervención, no se implementa el resto hasta elegir otro mecanismo documentado y aprobado.

## Estados
`PENDING`, `SESSION_CREATED`, `WAITING_FOR_OPERATOR`, `EXTRACTING`, `COMPLETED`, `FAILED`, `EXPIRED`, `CANCELLED`.
- `COMPLETED`: uno o más eventos persistidos y validados.
- Resultados vacíos no son `COMPLETED`; el diseño inicial los representa como `FAILED` con código `NO_VALID_EVENTS` (si se prefiere estado `NO_EVENTS`, es una decisión de producto pendiente).
- `SESSION_CREATED` puede ser transitorio; estado visible principal `WAITING_FOR_OPERATOR`.

## Persistencia propuesta
### `hapag_tracking_jobs`
`id`, `shipment_id`, `requested_by_user_id`, `status`, `provider`, `provider_session_id`, `idempotency_key`, `created_at`, `updated_at`, `expires_at`, `completed_at`, `event_count`, `error_code`, `error_message` sanitizado.

No persistir `liveUrl`, `wsEndpoint`, API key, cookies, headers de sesión ni secretos. Índices/constraints: idempotencia por ámbito acordado, índices shipment/status/expiration, FK según política de borrado.

### `hapag_tracking_events`
`id`, `job_id`, `shipment_id`, `container_number`, `status`, `place`, `event_date`, `event_time`, `transport`, `voyage_no`, `event_key`, `created_at`.

`event_key` unique para inserción idempotente. `event_date` y `event_time` conservan semántica observada; zona/normalización deben acordarse después de fixture. En primera fase solo histórico; no altera Shipment ETA/estado/alertas.

Migración SQL versionada en `supabase/migrations/`; no sustituir el patrón JPA existente sin explicar: SQL explícito para constraints/índices/FKs y rollout controlado, mientras JPA mapea entidades.

## Endpoints candidatos
- `POST /api/hapag/tracking-jobs` — operador crea job para `shipmentId`, con `Idempotency-Key`.
- `GET /api/hapag/tracking-jobs/{id}` — estado, expiración, lista de contenedores/eventos disponibles.
- `POST /api/hapag/tracking-jobs/{id}/live-view` — valida sesión, rol, autorización del job y expiración; devuelve URL temporal para renderizar en vista protegida.
- `POST /internal/hapag/tracking-jobs/{id}/events` — solo autenticación de servicio/worker; nunca invocado desde JS del operador.
- `POST /api/hapag/tracking-jobs/{id}/cancel` — cancela job y detiene sesión.

Evaluar CSRF en acciones browser session-authenticated; API interna usa token de servicio distinto, rotado por entorno y comparado en tiempo constante. Limitar tamaño/cantidad de eventos y payload.

## Seguridad y privacidad
- Secrets Hyperbrowser/Supabase únicamente en secrets/variables de Render/worker.
- Live URL es secreto bearer; recuperarla bajo autenticación en cada solicitud, no exponerla en logs, historial o links públicos. Respuesta `Cache-Control: no-store`, sin analítica/referrer externo; iframe solo si CSP/headers y el sitio lo permiten.
- Endpoint de recepción interno con autenticación de servicio, límites, validación estricta, asociación job/B-L/contenedor y prevención replay/idempotencia.
- Sesión única por job, cuota global y por usuario, timeout inicial conservador (10–15 min hasta medir), expiración del job antes del timeout del proveedor y cierre en cancelación/finalización/error.
- Eliminar/purgar trabajos y eventos conforme a retención acordada; auditar actor, transición de estado, duración y conteos, no URLs/tokens ni DOM completo.

## Reintentos, idempotencia, lifecycle
- `Idempotency-Key` evita jobs duplicados por doble click/retry HTTP.
- Reintentar creación de sesión solo ante error transitorio con backoff acotado; cada nueva sesión debe tener cleanup de la previa.
- Ingestión usa unique key (`job_id`, `event_key`) y transacción; reenvíos no duplican filas.
- Scheduler de housekeeping expira jobs en `WAITING_FOR_OPERATOR`/`EXTRACTING`, consulta/cierra sesiones abandonadas si la API está disponible y limpia error/timeout de forma idempotente.
- No permitir `COMPLETED` si evento count cero o validación falla.

## Costos y riesgos
- Sesiones facturan/consumen recursos durante su vida; la documentación admite hasta 12 h, demasiado largo como default. Medir minutos reales/costo en spike y establecer cuotas. El plan y precio exactos de la cuenta, concurrency y retención deben confirmarse con Hyperbrowser antes de habilitar.
- Riesgo alto de cambios de DOM/selectores/idioma y formatos de fecha.
- Reinicios Render pueden cortar worker embebido. Background worker aparte es más fiable pero agrega costo/operación y debe contrastarse con el plan disponible.
- La Live URL puede filtrarse por logs, referrer o historial si no se minimiza.
- Un operador puede navegar a otro B/L en la sesión; el backend debe validar que filas extraídas correspondan al B/L/job y contenedores asociados.
- Resultados históricos podrían no actualizar ETA/alertas; así se mantiene explícitamente fuera del alcance inicial.

## Plan de pruebas
1. Contrato del parser con HTML guardado anonimizado: varios contenedores, múltiples filas, campos status/place/date/time/transport/voyageNo, faltantes, fecha inválida, DOM cambiado, tabla vacía.
2. Servicio/persistencia PostgreSQL: transiciones legales, B/L asociado, evento válido, cero eventos no completa, evento inválido, idempotencia, cancelación, expiración, FK/borrado.
3. Seguridad: roles, URLs Live no-cache, no revelar keys/ws endpoint, endpoint interno autenticado, CSRF, rate/size limit.
4. Contrato Hyperbrowser simulado: create/get/stop, timeout y fallos/redactar logs; no activar solver/stealth.
5. Prueba manual proveedor: abrir Live View autorizado, resolver cualquier reto manualmente, comprobar CDP al mismo DOM y extraer un histórico real; comparar contenedores y eventos con navegador; conservar fixture anonimizado; medir duración/costo; confirmar cierre.
6. Integración PostgreSQL/Supabase de prueba; no aceptar H2 como única evidencia de constraints y locking.

## Puerta de decisión
No fusionar ni habilitar producción hasta que el spike demuestre que CDP/worker ve la misma página interactuada y entrega filas de eventos reales validadas al backend. Si no, volver a diseño antes de sustituirlo por recording replay u otro mecanismo.

## Decisiones pendientes para revisión del plan
- ¿Render tiene background worker disponible y autorizado en su plan actual, o se inicia con worker embebido supervisado?
- Confirmar costos, cuota/concurrencia y retención de sesiones con Hyperbrowser antes de habilitar producción.
