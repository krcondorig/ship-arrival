# Diseño actualizado: scheduler de paralelismo acotado MSC + Maersk

**Fecha:** 2026-11-02  
**Estado:** Propuesta pendiente de aprobación del usuario. No implementar todavía.

## Intención

Rediseñar la integración de consultas MSC/Maersk en el scheduler para evitar los fallos actuales de despacho y conservar los objetivos del piloto: solapamiento acotado, efectos secuenciales y una pausa de cortesía inequívoca. El piloto permanecerá deshabilitado por defecto y este trabajo no hará consultas reales ni modificará GitHub Actions o Render.

## Criterio de cortesía acordado

El usuario aprobó que `tracking.request.delay-ms` represente un **intervalo mínimo entre autorizaciones de inicio emitidas por el coordinador**. La autorización se registra con reloj monotónico en el límite controlado por la aplicación, inmediatamente antes de invocar `adapter.track`; no se mide por índices de lista ni por el instante en que se encola una tarea. Por una limitación de planificación de Java/JVM, no se promete una separación física exacta entre entradas al método ante una desplanificación arbitraria del hilo después de la autorización; el intervalo garantizado es entre autorizaciones del coordinador.

No se exigirá además una pausa fija después de aplicar cada posición si el intervalo ya se consumió. Las guías manuales no son consultas y no se despachan. Las exclusiones de paralelismo sí se consultan, pero por la ruta serial.

Para llamadas sucesivas del mismo carrier se exige, además, que la siguiente autorización ocurra después de que la anterior haya terminado y haya transcurrido `delayMs` desde esa finalización. MSC y Maersk distintos pueden solaparse después de respetar el intervalo entre sus autorizaciones.

## Requisitos funcionales

1. Como máximo una llamada MSC y una llamada Maersk en vuelo; máximo global de dos llamadas de adaptadores.
2. MSC y Maersk pueden solaparse; los resultados pueden terminar fuera de orden.
3. ONE y los carriers no piloto no se solapan con ninguna otra llamada de adaptador.
4. Los embarques configurados para verificación manual se omiten sin invocar adaptadores ni contarlos como fallidos. No constituyen una llamada de adaptador ni consumen un intervalo de inicio. Su efecto de omisión/progreso ocurre en el orden de entrada.
5. `MSMU1804864` y `275792562` nunca se despachan al executor paralelo cuando el piloto está habilitado; conservan el rastreo normal serial. La limitación de una llamada por carrier del executor paralelo no convierte ni omite esa ruta serial.
6. Persistencia, historial, alertas, detalles y progreso se ejecutan exactamente una vez y en el orden original de embarques.
7. El progreso solo avanza después de aplicar el resultado de la posición correspondiente.
8. Fallos de lookup, rechazo de despacho y excepción del worker producen un fallo localizable a una sola guía y no descartan resultados hermanos ya aceptados.
9. Interrupción o fallo de ciclo detiene nuevos inicios, cancela cuando sea posible y drena todo trabajo aceptado antes de liberar recursos de adaptadores. Los resultados todavía no aplicados al momento de abortar se descartan tras el drenado; no se aplican side effects fuera del prefijo ya procesado, no se ejecuta retención y el resumen informa el prefijo aplicado.
10. Medir duración por carrier/llamada, éxito y fallo, y máximo observado en vuelo sin registrar B/L, payloads, cookies ni credenciales.
11. `tracking.parallel.enabled=false` sigue siendo el valor por defecto; no cambiar workflow, Render ni ejecución operativa.

## Arquitectura propuesta

### Separación de responsabilidades

El coordinador ejecuta solo llamadas a adaptadores. El hilo del scheduler conserva la propiedad de la aplicación de resultados y de todos los efectos de negocio. El resultado de cada guía se recoge una vez y se aplica secuencialmente.

### Registro por posición

Mantener un registro explícito por índice de entrada, separado del estado de temporización. Como mínimo, cada posición tendrá un estado inequívoco:

- `MANUAL_SKIP`: marcado manual; nunca se consulta.
- `SERIAL_REQUIRED`: carrier no piloto o guía excluida; se ejecuta serialmente.
- `PILOT_ACCEPTED`: future/tarea aceptada y asociada a ese índice.
- `PILOT_DISPATCH_FAILED`: lookup o rechazo ocurrió; no se representa como ausencia de future.
- `RESULT_READY` / `RESULT_FAILED`: terminal del worker asociado a la misma posición.
- `APPLIED`: side effects ya completados exactamente una vez.

La presencia/ausencia de una future no se usará para deducir si el trabajo fue aceptado. Los slots de pacing no se usarán para deducir estado de despacho.

### Despacho acotado y barreras

El scheduler puede anticipar trabajo piloto elegible dentro de un conjunto acotado de máximo dos tareas, una por carrier. No encola ilimitadamente embarques. El scheduler no despacha trabajo piloto que deba cruzar una posición serial/no piloto: antes de invocar un adaptador serial espera que terminen las llamadas piloto aceptadas y evita que despachos posteriores crucen esa barrera. Una guía manual no invoca adaptador y no espera por workers para ejecutar su marca manual; esa marca/progreso sigue en el orden de entrada, y el scheduler no permite que una llamada serial no piloto empiece hasta que los pilotos terminen.

Una llamada del segundo carrier puede arrancar mientras la primera sigue en vuelo, si ya se cumplió el intervalo mínimo entre inicios. Guías piloto sucesivas del mismo carrier se serializan y esperan finalización más intervalo desde finalización; esta condición es `start(next) >= completion(previous) + delayMs` y reemplaza, no suma, al intervalo global entre inicios para esa pareja del mismo carrier.

### Control de inicio

La reserva del siguiente inicio usa `System.nanoTime()` o un reloj monotónico inyectable para pruebas. El control debe ocurrir inmediatamente antes de la invocación a `adapter.track`, para que el intervalo mida autorizaciones de inicio y no despachos/enqueues. El diseño no retiene un lock global durante la ejecución del adaptador, pues eso impediría el solapamiento entre carriers. El segundo carrier espera el tiempo restante del intervalo, no un intervalo completo redundante. Interrupción durante esta espera cancela/despacha cleanup antes de continuar.

### Aplicación ordenada

Al recorrer el input en orden:

- `MANUAL_SKIP`: marcar para verificación manual y avanzar progreso una vez.
- `PILOT_ACCEPTED`: esperar/recoger el resultado ligado a esa posición.
- `PILOT_DISPATCH_FAILED`: construir un resultado de fallo para esa guía, sin perder tareas aceptadas anteriores.
- `SERIAL_REQUIRED`: esperar el barrier de piloto, invocar su adaptador en serie y recoger el resultado.

Después, aplicar actualización, historial, alertas, detalles y progreso de forma secuencial. No repetir efectos por callbacks de workers.

### Errores e interrupciones

Los errores de una llamada piloto se convierten en un resultado fallido de esa guía y no cancelan el sibling. Errores de setup/ciclo hacen el ciclo parcial. Si se interrumpe el scheduler, no inicia consultas que aún no habían comenzado; cancela trabajos aceptados cuando proceda, espera su terminación aunque el hilo reciba interrupciones repetidas y restaura el flag de interrupción después de cleanup. Los recursos solo se liberan tras confirmar que ya no hay workers activos. En un aborto, solo queda aplicada la secuencia de posiciones cuyo procesamiento y side effects terminaron antes del punto de interrupción; resultados de workers que terminen durante el drenado pero aún no se habían aplicado se descartan para que cleanup no emita alertas ni mutaciones tardías.

## Fuera de alcance

- Habilitar el piloto en Actions, Render o producción.
- Consultar B/L reales, modificar datos compartidos o enviar alertas reales.
- Cambiar reglas de negocio de tracking, persistencia o retención.
- Aumentar límites globales de concurrencia o paralelizar otros carriers.
- Reabrir decisión de intervalo: el mínimo entre inicios fue aprobado por el usuario.

## Verificación de aceptación

Pruebas deterministas con barreras, latches y reloj/sleeper controlable deben cubrir:

- solapamiento MSC+Maersk con intervalos de inicio respetados;
- finalización fuera de orden y aplicación/progreso en orden exactamente una vez;
- máximo en vuelo igual a dos y por-carrier igual a uno;
- no solapamiento de ONE/otros carriers con llamadas piloto;
- exclusiones seriales y manuales no despachadas;
- lookup fallido, rechazo parcial, excepción de worker y fallo de un sibling;
- delay completion-plus-delay para llamadas sucesivas del mismo carrier;
- interrupción durante espera de spacing y durante drenado, con liberación posterior al fin de workers;
- configuración desactivada por defecto y ausencia de cambios operativos.

El cambio solo estará listo para revisión final con suite completa verde, revisión independiente sin hallazgos importantes y `git diff --check` limpio. No se ejecutará un piloto real como parte de esta verificación.
