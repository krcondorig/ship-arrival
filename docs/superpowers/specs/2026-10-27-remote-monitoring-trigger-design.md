# Monitoreo remoto disparado desde el dashboard

Fecha: 2026-10-27
Estado: propuesta para aprobación

## 1. Intención y por qué

Hoy el botón **"Monitorear Ahora"** ejecuta el rastreo **dentro del servidor de
Render**. Ese servidor está en un datacenter y su plan gratuito tiene 512 MB de
RAM, así que:

- **MSC**, **Maersk** y **Hapag-Lloyd** responden con bloqueo (Akamai y
  Cloudflare detectan la IP de datacenter).
- Levantar Chrome en 512 MB suele fallar por memoria.
- Solo **ONE** funciona desde ahí.

El monitoreo confiable ocurre únicamente en **GitHub Actions**, que sale a
internet por la IP del teléfono Android mediante Tailscale.

La persona que usa el sistema quiere presionar el botón desde cualquier
dispositivo (celular o PC), sin entrar a GitHub, y que el trabajo real ocurra en
la nube. Quiere, además, saber que está corriendo y cuánto falta.

## 2. Objetivos

- El botón del dashboard dispara el workflow de GitHub Actions en lugar de
  rastrear localmente.
- El dashboard muestra el estado real del proceso: en cola, ejecutando,
  completado o falló.
- Barra de progreso con avance real (guías procesadas sobre total).
- Si el teléfono no responde como nodo de salida, el dashboard lo dice con un
  mensaje claro y accionable.
- Se puede lanzar y olvidar: las alertas siguen llegando a Telegram y Discord.
- Funciona desde cualquier navegador, sin abrir GitHub.

## 3. No objetivos

- No se muestra el detalle guía por guía durante la ejecución.
- No se transmite el log del runner en vivo.
- No se modifica la lógica de scraping ni los adaptadores.
- No se agregan servicios de pago ni se pide tarjeta.
- No se toca el monitoreo programado de las 08:00 AM: sigue igual.

## 4. Anti-objetivos

- Que el botón deje de funcionar cuando se corre la aplicación localmente sin
  token de GitHub.
- Que el dashboard invente un progreso estimado por tiempo transcurrido.
- Que se guarden credenciales de GitHub en el repositorio.
- Que el sondeo del dashboard agote el pool de conexiones de Supabase (hoy
  limitado a 3 por proceso).

## 5. Restricciones

- Token de GitHub: *fine-grained personal access token*, permiso **Actions:
  write** únicamente sobre `krcondorig/ship-arrival`. Se guarda como variable
  secreta en Render. Gratuito.
- El workflow es `.github/workflows/daily-monitoring.yml` y ya tiene
  `workflow_dispatch` y un grupo de concurrencia `daily-monitoring`.
- El pool de conexiones a Supabase es pequeño a propósito (3 en el dashboard,
  2 en CI) porque el pooler en modo sesión admite 15 clientes.
- El dashboard ya sondea `/shipments/track-status` cada pocos segundos; el
  patrón de interfaz existe y se reutiliza.

## 6. Decisiones tomadas por la persona usuaria

- El botón debe disparar el trabajo en la nube, no rastrear en Render.
- Debe poder presionarse desde el celular o desde cualquier PC, sin entrar a
  GitHub.
- Basta con ver "ejecutando" mientras corre, más una barra de avance.
- Si el teléfono está apagado o sin datos, debe mostrarse un mensaje claro.
- El envío de alertas a Telegram y Discord se mantiene igual.

## 7. Decisiones del constructor (mías)

Estas las propongo yo; quedan abiertas a revisión.

- **Progreso real, no estimado.** El runner publica su avance en Supabase y el
  dashboard lo lee. Es la única forma honesta de dibujar una barra: GitHub solo
  informa "en cola / en ejecución / terminado", sin porcentaje.
- **Reutilizar la tabla de progreso para el ciclo programado.** El monitoreo de
  las 08:00 AM también publica avance, así el dashboard puede mostrar el estado
  aunque nadie haya presionado el botón.
- **Escrituras de progreso espaciadas.** Se escribe al iniciar, cada 5 guías o
  cada 5 segundos (lo que ocurra primero) y al terminar. Evita castigar el pool.
- **Fallback local.** Si no hay token configurado, el botón conserva el
  comportamiento actual (ciclo asíncrono en el propio proceso). Así el
  desarrollo local y las pruebas siguen funcionando sin GitHub.
- **Detección de teléfono caído por paso fallido.** Al terminar con fallo, se
  consultan los pasos del job; si el que falló es `Connect to Tailscale` o
  `Activate Phone Exit Node`, el mensaje es el de teléfono no disponible.
- **Un solo ciclo a la vez.** Antes de disparar se verifica que no haya una
  ejecución en curso, para no duplicar trabajo ni consumir el presupuesto de
  conexiones.

## 8. Cómo se arma

### 8.1 Disparo

- `GitHubActionsDispatchService` (nuevo): cliente HTTP que usa `RestClient`.
  - `POST /repos/{owner}/{repo}/actions/workflows/{workflow}/dispatches` con
    `{"ref": "<rama>"}`.
  - `GET /repos/{owner}/{repo}/actions/runs` para localizar el run recién
    creado y consultar su estado.
  - `GET /repos/{owner}/{repo}/actions/runs/{id}/jobs` para identificar el paso
    que falló.
- Configuración nueva: `github.dispatch.enabled`, `github.token`,
  `github.owner`, `github.repo`, `github.workflow`, `github.ref`.
- El controlador `POST /shipments/track-now` elige: disparo remoto si está
  habilitado, ciclo local si no.

### 8.2 Progreso

- Entidad `TrackingRunProgress` (tabla `tracking_run_progress`): una fila por
  ciclo, con `status`, `total`, `processed`, `updated`, `failed`, `alerts`,
  `currentTrackingNumber`, `message`, `startedAt`, `finishedAt`, `updatedAt`.
- `TrackingProgressPublisher` (nuevo): traduce los cambios de estado que ya
  emite `TrackingSchedulerService` a escrituras en esa tabla.
- El dashboard lee el progreso más reciente por un endpoint
  `GET /shipments/live-status`, que devuelve tanto el estado local como el
  remoto.

### 8.3 Interfaz

- Panel de estado existente, ampliado: etiqueta de estado, barra de avance con
  porcentaje, guía en curso, tiempo transcurrido y, si falló, el motivo.
- El sondeo continúa mientras haya una ejecución activa y se detiene al
  terminar.
- Al completarse, la tabla de embarques se refresca.

### 8.4 Mensajes de error

| Situación | Mensaje |
|---|---|
| Paso de Tailscale falló | "Tu teléfono no responde como nodo de salida. Verifica que esté encendido, con datos móviles y Tailscale activo." |
| Fallo genérico del run | "El monitoreo falló en la nube. Revisa el detalle en GitHub Actions." + enlace al run |
| Token inválido o sin permiso | "No se pudo enviar el monitoreo: el token de GitHub no tiene permiso para ejecutar workflows." |
| Ya hay un monitoreo activo | "Ya hay un monitoreo en curso. Espera a que termine." |

## 9. Lo que queda a criterio de quien construya

- Nombres exactos de clases, rutas y columnas.
- Estrategia de sondeo (intervalo, corte por inactividad).
- Cómo se identifica el run recién creado (por `created_at` más reciente o por
  `head_branch`), siempre que no se confunda con otro.
- Formato visual de la barra, respetando los estilos existentes del dashboard.
- Manejo de reintentos y tiempos de espera contra la API de GitHub.

## 10. Criterios de aceptación

1. Con token configurado, presionar el botón crea una ejecución en GitHub
   Actions y el dashboard muestra "en cola" y luego "ejecutando".
2. La barra avanza según las guías realmente procesadas.
3. Al terminar, el estado pasa a "completado" y la tabla se actualiza.
4. Si el teléfono está caído, el mensaje lo indica explícitamente.
5. Sin token, el botón mantiene el comportamiento local actual.
6. La suite de pruebas sigue en verde y se agregan pruebas para el cliente de
   GitHub, el publicador de progreso y la selección de mensajes de error.
7. El monitoreo programado de las 08:00 AM no cambia su funcionamiento.
