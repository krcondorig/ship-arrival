# Diseño: paralelismo acotado MSC + Maersk en el scheduler

**Fecha:** 2026-11-01  
**Estado:** Borrador para revisión y aprobación; no implementar todavía.

## Intención

Reducir la duración de ciclos de monitoreo con guías MSC y Maersk, aplicando al scheduler el enfoque medido con `MSMU1804864` y `275792562`, sin cambiar reglas de negocio ni exponer la base/notificaciones a mutaciones concurrentes.

## Evidencia disponible

- Una medición local directa, fuera del scheduler y sin persistencia/notificaciones, consultó ambas guías en paralelo en aproximadamente 7,3 s.
- Una pasada secuencial posterior tardó aproximadamente 13,6 s. Ambas respuestas fueron exitosas en ambas pasadas; MSC reportó `IN_TRANSIT` y Maersk `ARRIVED`.
- Es una muestra pequeña en Windows, no una garantía de ahorro o capacidad del runner GitHub Actions.
- El scheduler actual procesa cada guía de extremo a extremo secuencialmente: adaptador, persistencia, alertas, avance y espera de cortesía.
- `SeleniumMscTrackingClient` usa un navegador compartido y sincroniza cada consulta en un monitor; las llamadas MSC deben seguir serializadas.
- `SeleniumMaerskTrackingClient.fetchTrackingJson` está declarado `synchronized`, aunque cada intento crea y cierra su propio Chrome. Quitar esa sincronización para permitir solapamiento requiere verificar que todo el estado mutable sea local a la llamada.
- El executor existente `trackingTaskExecutor` tiene un solo hilo y está destinado a encolar el ciclo completo, no a procesar trabajos internos en paralelo.
- GitHub Actions configura su pool de conexiones a base con límite bajo y tiene timeout de job de 45 minutos.

## Objetivos

1. Limitar el piloto a **dos adaptadores invocados simultáneamente como máximo** en una instancia de aplicación.
2. Permitir que una llamada MSC coincida con una llamada Maersk, pero no aumentar concurrencia dentro de MSC ni dentro de Maersk.
3. Mantener llamadas a ONE y adaptadores no incluidos secuenciales, sin solaparlas con otras llamadas de adaptador.
4. Mantener consultas fuera de transacciones JPA; persistencia, alertas, historial y progreso quedan serializados en el coordinador.
5. Conservar contadores monotónicos, resultados/fallos por guía, limpieza de recursos y prevención local de ciclos duplicados.
6. Hacer el piloto configurable y reversible sin cambiar el comportamiento por defecto hasta su habilitación explícita.
7. Medir duración y fallos en un ciclo representativo antes de ampliar o activar permanentemente.

## No objetivos

- Paralelizar ONE, Hapag-Lloyd u otros adaptadores.
- Permitir dos sesiones Maersk simultáneas o más de una sesión MSC.
- Cambiar reglas de alerta, estados, persistencia, reintentos, timeout de navegador o retención.
- Aumentar el pool JDBC o los recursos de GitHub Actions.
- Garantizar inicio puntual del workflow `schedule` de GitHub.
- Implementar una exclusión distribuida entre instancias Render y GitHub Actions; esto requeriría una decisión y mecanismo separados.

## Comportamiento propuesto

### Selección y límites

- Añadir configuración de concurrencia con valor conservador/deshabilitado por defecto para no alterar el sistema hasta un piloto habilitado intencionalmente. El límite del piloto cuando se habilite es global por proceso = 2.
- La activación debe estar restringida a una propiedad explícita habilitada solo en el runner de GitHub Actions (que ejecuta con `tracking.run-once=true`). La variable no se configura en Render; por eso tanto el monitoreo local manual como cualquier otro ciclo de Render siguen seriales, incluso si Render dispara el workflow remoto mediante `GITHUB_DISPATCH_ENABLED`.
- Elegir como candidatos solo embarques activos de MSC y Maersk que no estén en `manualCheckCarriers` ni sean las guías piloto `MSMU1804864` y `275792562`. La exclusión se aplica únicamente cuando el piloto paralelo está habilitado: esas guías conservan el rastreo normal serial en otros contextos/ciclos.
- Aplicar semáforos/capacidad por carrier: MSC=1 y Maersk=1. No se permite Maersk+Maersk ni MSC+MSC en vuelo.
- ONE y carriers restantes se procesan secuencialmente y no se solapan con llamadas activas de otros adaptadores. Los embarques marcados para verificación manual conservan la lógica de omisión actual.
- Las llamadas pueden empezar separadas por el espaciado de cortesía configurado, aunque luego se solapen durante sus esperas de portal.

### Rate limiting

- No reemplazar accidentalmente la pausa actual por un intervalo de 2 segundos entre inicios globales: actualmente el ciclo espera 2 segundos después de completar/procesar cada guía, por lo que la separación real entre consultas incluye además el tiempo que tardó la consulta anterior.
- El comportamiento conservador propuesto para el piloto es limitar a una consulta activa por carrier y no iniciar otra consulta del mismo carrier hasta que la anterior haya terminado y se haya respetado la pausa configurada. Esto mantiene el ritmo de cada portal sin impedir que una consulta MSC y otra Maersk se solapen.
- Para carriers fuera del piloto, conservar exactamente el procesamiento secuencial y la pausa actual.
- Para un mismo carrier, la siguiente consulta no empieza hasta que la llamada previa de ese carrier haya terminado y luego transcurra el `delayMs` configurado. Así el intervalo no se mide solo de inicio a inicio y no aumenta la tasa por carrier.
- Entre carriers distintos, el coordinador respeta también la pausa global actual entre elementos del ciclo; permitir solapamiento significa que la segunda llamada puede empezar después del intervalo de cortesía aunque la primera siga esperando respuesta. Una guía omitida por chequeo manual conserva el comportamiento actual de consumir su posición/pausa del ciclo.
- Si no se puede preservar esta semántica de pausa sin bloquear indebidamente los workers, priorizar la pausa conservadora sobre el máximo aprovechamiento de concurrencia.
- No alterar los reintentos internos actuales de los clientes en este piloto. MSC puede reiniciar el navegador en su retry y Maersk mantiene su retry interno; dejar claro que la pausa se controla entre llamadas del scheduler, no necesariamente entre intentos HTTP internos.
- No reducir silenciosamente la pausa para buscar más velocidad.

### Recolección y mutaciones

- Los workers realizan únicamente `adapter.track(trackingNumber)` y entregan un resultado inmutable asociado a su embarque. No mutan entidades gestionadas ni llaman repositorios, alertas, progreso global o retención.
- El coordinador consume y aplica cada resultado exactamente una vez mediante la secuencia ya existente: `updateFromTrackingResult`, evaluación/envío de alertas, contadores, detalles y progreso.
- Para evitar cambios incompatibles en la interfaz, procesar/aplicar resultados en el orden original de la lista de embarques aunque terminen fuera de orden. El progreso representa el prefijo de guías ya aplicado, no el número de workers terminados.
- Mantener una ventana acotada: como máximo dos trabajos despachados y sin una cola de ciclo ilimitada. No omitir guías por saturación.
- Cada error de adaptador/worker se convierte en un resultado fallido para esa guía y no cancela el resto del ciclo.

### Seguridad de clientes y recursos

- Antes de eliminar `synchronized` de Maersk, revisar que campos mutables no se compartan por invocación; demostrar con prueba concurrente que dos llamadas separadas crean/cerran drivers independientes y no cruzan respuestas.
- Si la prueba de seguridad Maersk no se puede establecer, no quitar el monitor: mantener Maersk serial y posponer la mejora, sin forzar paralelismo por otro camino.
- El monitor del cliente MSC se conserva.
- `adapterFactory.releaseResources()` se ejecuta solo después de drenar/cancelar y recoger todos los trabajos del ciclo, nunca mientras una consulta activa usa el cliente.
- En interrupción/apagado, no iniciar nuevos trabajos y recoger/cancelar las tareas activas antes de liberar adaptadores. El coordinador debe marcar el ciclo como incompleto/cancelado (`processed < total`), publicar estado fallido o parcial en `tracking_run_progress`, conservar lo ya aplicado sin reintentar alertas en ese mismo ciclo y omitir retención. Una ejecución completa conserva el estado terminal y la retención actuales.
- Si el hilo coordinador se interrumpe mientras espera un resultado, debe restaurar la bandera de interrupción después de coordinar el cierre y no reportar el ciclo como finalización normal.

### Ciclos entre procesos

- `AtomicBoolean` protege solo la instancia local. La concurrencia de Actions protege ejecuciones del workflow remoto, pero no coordina por sí sola con una ejecución iniciada desde Render.
- Este diseño limita a dos consultas por proceso. En el caso excepcional de monitoreo local y remoto simultáneos, ambos podrían llegar a dos (cuatro en total). El piloto no debe habilitarse en ambos orígenes a la vez sin coordinación distribuida o una decisión operativa que impida esa coincidencia.
- La adquisición/liberación de un lock distribuido en PostgreSQL queda fuera del alcance actual y debe evaluarse por separado si se necesita garantía global.

### Observabilidad

- Registrar duración total del ciclo y tiempos por llamada/carrier, cantidad de éxitos/fallos y límite observado de trabajos en vuelo.
- No registrar payloads completos, cookies, credenciales ni datos sensibles de las guías.
- Comparar ciclos de volumen parecido: duración, tasa de fallos/bloqueos, reintentos, memoria y cantidad de procesos Chrome.
- Mantener configuración para volver inmediatamente a concurrencia 1 si suben errores, hay problemas de recursos o el beneficio no es significativo.

## Pruebas y criterios de aceptación

1. Pruebas unitarias del coordinador: nunca más de dos llamadas totales activas, MSC máximo 1 y Maersk máximo 1; demostrar además que la siguiente llamada del mismo carrier no comienza hasta finalizar la previa y transcurrir `delayMs`.
2. Prueba de solapamiento MSC+Maersk con llamadas controladas por latch/future, no mediante demoras arbitrarias.
3. Prueba de que ONE/adaptadores fuera del piloto no se solapan con otros adaptadores.
4. Prueba del espaciado mínimo configurable entre inicios de llamadas al adaptador.
5. Pruebas de resultados fuera de orden: actualización, alertas, detalles y progreso se aplican en el orden acordado, una vez por guía, con contadores monotónicos.
6. Regresión para navieras de chequeo manual, cero embarques, fallos parciales e interrupción.
7. Prueba de que los recursos no se liberan hasta que todas las consultas activas concluyan.
8. Prueba de concurrencia específica para Maersk; si no puede comprobar independencia de los drivers/respuestas, mantener la implementación serial y no activar el solapamiento para Maersk.
9. Suite completa verde.
10. Piloto real habilitado en un solo origen, con volumen representativo, sin incremento material de fallos/bloqueos ni síntomas de agotamiento de memoria y con una mejora de duración reproducible. Definir umbrales antes de evaluar el piloto.

## Decisiones menores del implementador

- Usar un executor dedicado para las consultas, distinto del executor que encola el ciclo completo.
- Mantener resultados asociados por índice de entrada y consumirlos en orden estable.
- Exponer el límite/feature flag mediante propiedades de Spring, con valor conservador y documentado.
- Si un worker falla inesperadamente, convertirlo en error de esa guía y continuar.

## Decisiones tomadas para este diseño

- La concurrencia aplica únicamente a consultas de adaptador. Las actualizaciones de embarque, eventos/historial, alertas y publicación de progreso permanecen en el coordinador y se ejecutan secuencialmente.
- Las alertas siguen siendo individuales; no se agrupan en el piloto.
- Las dos guías usadas en la medición inicial (`MSMU1804864` y `275792562`) se excluyen del piloto operativo para no disparar alertas de arribo sobre registros de prueba. Otras guías elegibles conservan el comportamiento de notificación existente.
- La primera activación del piloto será solo en GitHub Actions; Render permanece con concurrencia desactivada. Si una configuración independiente y verificable por origen no se puede garantizar, el piloto no se activa hasta resolverlo.
- Se prioriza conservar el intervalo de cortesía actual por carrier y no aumentar el ritmo de llamadas a MSC o Maersk sobre el comportamiento previo.

## Aprobaciones necesarias antes del plan de implementación

- Aprobar el alcance y los criterios descritos aquí.
- Confirmar umbral operativo de aceptación: reducción mínima deseada del tiempo y cuánto aumento de errores/reintentos sería tolerable. Si no se fija otro, se propone no aceptar aumento observable de errores de rastreo en una muestra representativa y exigir al menos 20% de reducción mediana de duración en varios ciclos comparables.
