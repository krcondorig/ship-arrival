# Estado de ejecución

**Actualizado:** 2026-11-23
**Fase:** Migración ArriVix cerrada. `users.html` fusionado a `master` por el PR #36 y desplegado: local y producción ya no difieren en ninguna plantilla.
**Último commit:** `1b2544f` — "feat(ui): polish users.html accessibility and semantics (#36)" (`master`).

## Migración UI (ArriVix)

La migración queda **completa en las cuatro vistas**. Con el PR #36 en `master`, local y producción ya no difieren en ninguna plantilla.

| Vista | Template | Estado |
|---|---|---|
| Dashboard | src/main/resources/templates/dashboard.html | Migrado — tokens portados de dashboard-single-action.html. Idéntico a `master` |
| Historial | src/main/resources/templates/history.html | Migrado. Idéntico a `master` |
| Login | src/main/resources/templates/login.html | Migrado. Idéntico a `master` |
| Usuarios | src/main/resources/templates/users.html | Migrado en el PR #34; refinamientos de accesibilidad fusionados en `master` por el PR #36 (`1b2544f`, 479 líneas) |

### Refinamientos de `users.html` (PR #36, +217/−62)

| Cambio | Detalle |
|---|---|
| Accesibilidad | `aria-label` en los cinco botones de acción por fila y en el modal de restablecer contraseña (`role="dialog"`, `aria-modal="true"`) |
| Estilos | Clases semánticas en lugar de estilos inline: `.btn-arri-warn`, `.btn-arri-danger`, `.cell-user`, `.cell-date`, `.cell-actions` |
| Semántica | `<main role="main">` y `<h1>` (antes `<div>` y `<h5>`) |
| Responsive | Nuevo corte `@media (max-width: 480px)` además del de 820px |
| Tokens | `--error`, `--font-mono`, `--shadow-ambient`; ratios de contraste documentados en comentarios |

Verificación de paridad local vs producción: `dashboard.html` (1298 líneas), `history.html` (677) y `login.html` (228) se compararon línea por línea contra `raw.githubusercontent.com/.../master` y coinciden. `users.html` era el único archivo divergente; con el PR #36 fusionado, ya no lo es.

## Cierre de `users.html`

| Paso | Evidencia |
|---|---|
| Punto de partida | `master` = `origin/master` = `c59e658`; `users.html` +217/−62 sin commitear |
| Rama | `feature/users-arrivix-refinements` creada desde `c59e658` |
| Commit | `b7e4cdd` — un único padre (`c59e658`), un único archivo en el índice (`git diff --cached --stat`: 1 file changed, 217 insertions(+), 62 deletions(-)) |
| Gate QA | `.\mvnw.cmd test` → `Tests run: 279, Failures: 0, Errors: 0, Skipped: 6` / `BUILD SUCCESS` en 34.652 s. Idéntico a la línea base |
| Render de la plantilla | `UserAdminControllerTest` es `@SpringBootTest` + `@AutoConfigureMockMvc`; `GET /users` exige `status().isOk()` y `view().name("users")`. `target/classes/templates/users.html` contiene el markup nuevo |
| Publicación | `git push -u origin feature/users-arrivix-refinements`; `git ls-remote` confirma `b7e4cdd4ce23e9d97162c765e04fbbef0b194cbd` |
| PR | **#36 abierto y fusionado.** `merged: true`, `merged_by: krcondorig`, `merged_at` 2026-11-23T17:16:38Z. Base `master`, head `feature/users-arrivix-refinements`, 1 archivo, +217/−62 |
| `master` | `1b2544f` — "feat(ui): polish users.html accessibility and semantics (#36)", padre directo `c59e658`. Confirmado con `GET /repos/…/commits/master` |
| Despliegue | Render reconstruye desde `master` (`render.yaml`, `autoDeploy: true`). El cambio es solo de plantilla |
| Verificación post-merge | `users.html` en `master` vía `raw.githubusercontent.com`: 479 líneas, 13 `aria-label`, `role="main"`, `<h1>Cuentas de acceso</h1>`, `.cell-actions`, `.btn-arri-warn` y `@media (max-width: 480px)` presentes |

## Borrado condicionado de guías

| Componente | Archivo | Estado |
|---|---|---|
| Dominio | src/main/java/com/shiparrival/domain/ShipmentStatus.java | Hecho — `canBeDeletedManually()` (PENDING_FIRST_CHECK, ERROR_CHECKING) |
| Servicio | src/main/java/com/shiparrival/service/ShipmentService.java | Hecho — `deleteShipmentManually()` valida y borra en una sola transacción; `deleteShipment()` sigue sin restricción para la retención |
| Controlador | src/main/java/com/shiparrival/web/ShipmentWebController.java | Hecho — traduce excepciones a flash; sin pre-validación |
| Plantilla | src/main/resources/templates/dashboard.html | Hecho — botón visible solo si `canBeDeletedManually()` |
| Tests | src/test/java/com/shiparrival/{domain,service,web}/… | Hecho — suite completa 279 tests, 0 fallos, 6 omitidos |

## Integración

| Paso | Evidencia |
|---|---|
| Commit original | `0002102` en `feature/delete-unmonitored-guides`, colgado de `fedd6a1` |
| Reconstrucción | `fedd6a1` y `origin/master` (`7c6c4f0`) comparten árbol `e41a0922`; el cherry-pick aplicó limpio |
| Commit integrado | `ba3fdfd`, padre directo `7c6c4f0` (PR #34). Sin `fedd6a1` en la historia |
| PR | #35 abierto sobre `feature/delete-guides-clean`; `mergeable_state: clean`, 10 archivos, +378/−18 |
| Merge | Fast-forward por refs (`git push origin feature/delete-guides-clean:master`). Sin commit de merge |
| `master` | `ba3fdfd` — confirmado con `git ls-remote` y `GET /repos/…/pulls/35` (`merged: true`, `merged_at` 2026-11-23T05:42:11Z) |
| Suite post-cherry-pick | 279 tests, 0 fallos, 6 omitidos, `BUILD SUCCESS` en 44.4 s sobre `ba3fdfd` |

Los 6 omitidos son los de navegador/red real: `MscTrackingBrowserIntegrationTest` (1), `MaerskTrackingBrowserIntegrationTest` (1), `HapagLloydTrackingBrowserIntegrationTest` (1) y `CarrierNetworkDiagnosticTest` (3).

## Gates

- QA: aprobado — suite completa 279 tests, 0 fallos, 0 errores, 6 omitidos (navegador real). Re-ejecutada sobre `ba3fdfd` tras el cherry-pick.
- Code review: aprobado con observaciones. Cerrados el TOCTOU (validación y borrado en la misma transacción), el mensaje de rechazo con `getLabel()` y la cobertura de rol ADMIN y de los estados restantes. Pendiente como deuda: pérdida de filtros en el redirect.
- Deploy: hecho — `master` en `ba3fdfd`; Render reconstruye desde esa rama.

## Gates — ciclo `users.html` (2026-11-23)

- QA: aprobado — `.\mvnw.cmd test` sobre `b7e4cdd`: 279 tests, 0 fallos, 0 errores, 6 omitidos, `BUILD SUCCESS` en 34.652 s. Conteo idéntico a la línea base; no se añadieron ni se perdieron tests.
- Code review: **no ejecutado.** El cambio es de plantilla y sin lógica; el gate de QA cubre el render. Queda como deuda si se quiere revisión formal antes del merge.
- Deploy: hecho — `master` en `1b2544f` (PR #36); Render reconstruye desde esa rama.
- PR: **#36 fusionado** el 2026-11-23T17:16:38Z por `krcondorig`. 1 archivo, +217/−62.

## Bloqueos

**Operativo, no técnico:** el borrado de los dos `.perm-probe.tmp` está bloqueado. `devops` y `qa-engineer` reciben `Permission denied: shell` para `rm`, y `project-manager` tampoco tiene shell. Hay que borrarlos desde una shell interactiva con permisos.

**Monitoreo diario caído desde 2026-11-12.** Independiente de este ciclo: los runs programados de `daily-monitoring.yml` fallan todos en el paso 10 `Activate Phone Exit Node` (exit node de Tailscale). Último run automático verde: #74, 2026-11-11. Ver "Monitoreo desatendido" abajo.

## Gobernanza documental

- `AGENTS.md` lo mantiene el `project-manager` al cerrar cada ciclo, y solo con datos verificados de los gates (salida de Maven, resultado de QA, evidencia de review). Sin evidencia no se toca el roadmap.
- `reclutador` conserva la propiedad del roster y de las fichas en `.opencode/agents/`.
- Ningún otro agente tiene `edit` sobre la raíz del repo. El resto de los roles edita únicamente sus rutas asignadas: `backend-dev` en `src/main/java`, `src/test/java`, `src/main/resources` y `pom.xml`; `frontend-dev` en `templates/` y `static/`; `qa-engineer` en tests. Delegar una edición fuera de esas rutas falla con `Permission denied` antes de evaluar el contenido, así que conviene revisar el bloque `permissions` de la ficha del agente antes de despachar la tarea.

## Próximo paso

1. **Arreglar el monitoreo diario.** Es lo único que queda roto en producción. Los runs programados llevan fallando desde el 12-nov en `Activate Phone Exit Node`. Encargo a `devops`: imprimir `tailscale status` y la IP configurada antes de fallar, y distinguir un fallo de red de uno de rastreo.
2. **Borrar los dos `.perm-probe.tmp`** desde una shell interactiva.
3. **Borrar la rama remota** `feature/users-arrivix-refinements` si no se quiere conservar (el PR #36 ya está fusionado).

## Deuda técnica registrada

- **`users.html` no tiene aserciones sobre su HTML**: `UserAdminControllerTest` prueba que la vista renderiza (`status().isOk()`, `view().name("users")`), no qué contiene. Un `aria-label` vacío que evalúe sin excepción pasaría desapercibido. Un test útil haría `GET /users` y comprobaría `role="main"`, `<h1`, las clases `.cell-*` y algún `aria-label` concreto. **Pendiente con más motivo ahora que el PR #36 está en `master`**: los `aria-label` que ese PR introdujo no tienen prueba.
- **El borrado pierde los filtros del dashboard**: el formulario solo envía `page`, `size` y `search`, así que tras borrar se vuelve a la lista sin filtro de naviera, estado, puerto ni ETA. Preexistente, pero ahora visible porque el botón aplica a todas las guías pendientes.
- **`ShipmentService.deleteShipment` sigue público y sin guardas**: cualquier caller futuro que lo use se salta la regla. El nombre seguro por defecto sería el restringido.
- **Sin CSRF en los POST**: mitigado por la cookie `SameSite=Lax`, no por el servidor. Afecta a borrar, registrar, "Monitorear Ahora" y a toda la administración de cuentas en `users.html`.
- **`*.perm-probe.tmp` en los árboles de código**: `src/main/java/com/shiparrival/.perm-probe.tmp` y `src/test/java/com/shiparrival/.perm-probe.tmp`. Sin rastrear, no entran al JAR, pero siguen en disco porque `rm` está bloqueado para los agentes de este roster.
- **`SPRING_PROFILES_ACTIVE=prod` apunta a un archivo que no existe**: `src/main/resources/application-prod.properties` fue revertido en la cadena `a6664675`→`b122d5b4`. El perfil es un no-op; toda la configuración sale de `application.properties` más variables de entorno. Funciona, pero el nombre engaña.
- **Local escribe sobre la base de producción**: `application.properties` apunta directo a Supabase, así que levantar la app en local modifica la misma base que ve el dashboard desplegado. No hay perfil ni base aislada para desarrollo.
- **`gh` CLI sin estado verificado**: el binario existe en `C:\Program Files\GitHub CLI\gh.exe` con la cuenta `krcondorig` configurada, pero `gh --version` y `gh auth status` devuelven `Permission denied: shell` para los agentes. Los PR se abren a mano.

## Monitoreo desatendido — incidencia abierta

Los runs programados de `daily-monitoring.yml` fallan sistemáticamente desde el 2026-11-12. Último verde automático: run #74 (2026-11-11). Los `workflow_dispatch` manuales sí pasan (#88 el 22-nov, #84 el 19-nov, #79 el 15-nov).

| Run | Fecha | Disparo | Resultado | Paso que falla |
|---|---|---|---|---|
| 89 | 2026-11-22 20:03Z | schedule | failure | 10 `Activate Phone Exit Node` |
| 88 | 2026-11-22 06:33Z | manual | success | — |
| 87 | 2026-11-21 19:52Z | schedule | failure | 10 `Activate Phone Exit Node` |
| 86 | 2026-11-20 20:51Z | schedule | failure | 10 `Activate Phone Exit Node` |
| 85 | 2026-11-19 21:05Z | schedule | failure | 10 (mismo patrón) |
| 83–75 | 18 al 12 nov | schedule | failure | 10 (mismo patrón) |
| 74 | 2026-11-11 | schedule | success | — |

El paso 11 `Run tracking cycle once` queda en `skipped`: el JAR nunca arranca. Consecuencia: sin disparo manual, las ETA y las alertas de ≤3 días llevan sin refrescarse desde el 12-nov.

Causa probable: el teléfono dejó de anunciarse como exit node (app dormida, nodo con las keys caducadas, o IP cambiada respecto a `TAILSCALE_EXIT_NODE_IP`). Descartada la authkey: el paso 9 `Connect to Tailscale` sí pasa.

Hallazgo aparte: los runs programados arrancan a las 19:52–20:03 UTC cuando el cron es `0 13 * * *`, es decir 7-8 horas tarde por saturación de la cola de runners gratuitos. Aun arreglando el exit node, el ciclo no correría a las 08:00 de Lima.

---

## Roster de agentes — cerrado 2026-11-20

Auditoría del roster completada. 11 agentes, formato V2 válido, sin campos deprecados.

Corregido en esta ronda:
- `reclutador.md`: faltaba `skill * deny`, heredaba `* * allow`. Ruta de config corregida (`opencode.jsonc` en la raíz, no en `.opencode/`).
- 9 agentes recibieron permisos de skill coherentes con su rol (antes todos tenían `skill * deny` sin `allow`, lo que dejaba skills reales inaccesibles).
- `frontend-dev.md`: descripción actualizada a sistema ArriVix; ya no describe solo "Bootstrap".

Skills permitidas por agente:

| Agente | Skills |
|---|---|
| project-manager | executing-plans, dispatching-parallel-agents, subagent-driven-development |
| backend-dev | executing-plans, test-driven-development, systematic-debugging, verification-before-completion, java-springboot |
| frontend-dev | accessibility, impeccable, executing-plans |
| carrier-scout | systematic-debugging, verification-before-completion |
| devops | verification-before-completion, executing-plans |
| qa-engineer | systematic-debugging, verification-before-completion, test-driven-development |
| code-reviewer | verification-before-completion, systematic-debugging |
| tech-lead | writing-plans, brainstorming |
| product-owner | writing-plans, brainstorming |
| marketing | ninguna (base deny) |
| reclutador | ninguna (base deny) |
