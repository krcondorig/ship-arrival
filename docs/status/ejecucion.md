# Estado de ejecución

**Actualizado:** 2026-11-23
**Fase:** Borrado condicionado de guías: botón "Eliminar" habilitado en guías no monitoreadas. Integrado a `master` por el PR #35 y desplegado.
**Último commit:** ba3fdfd — "feat(dashboard): eliminar habilitado en guías no monitoreadas" (`master`)

## Migración UI (ArriVix)

| Vista | Template | Estado |
|---|---|---|
| Dashboard | src/main/resources/templates/dashboard.html | Migrado — tokens portados de dashboard-single-action.html |
| Historial | src/main/resources/templates/history.html | Migrado |
| Login | src/main/resources/templates/login.html | Migrado |
| Usuarios | src/main/resources/templates/users.html | Migrado en el PR #34; refinamientos locales sin commitear (ver deuda técnica) |

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

## Bloqueos

Ninguno.

## Gobernanza documental

- `AGENTS.md` lo mantiene el `project-manager` al cerrar cada ciclo, y solo con datos verificados de los gates (salida de Maven, resultado de QA, evidencia de review). Sin evidencia no se toca el roadmap.
- `reclutador` conserva la propiedad del roster y de las fichas en `.opencode/agents/`.
- Ningún otro agente tiene `edit` sobre la raíz del repo. El resto de los roles edita únicamente sus rutas asignadas: `backend-dev` en `src/main/java`, `src/test/java`, `src/main/resources` y `pom.xml`; `frontend-dev` en `templates/` y `static/`; `qa-engineer` en tests. Delegar una edición fuera de esas rutas falla con `Permission denied` antes de evaluar el contenido, así que conviene revisar el bloque `permissions` de la ficha del agente antes de despachar la tarea.

## Próximo paso

Ciclo cerrado. La funcionalidad está en `master` (`ba3fdfd`) y desplegada; no requiere acción adicional. Cuando se retome trabajo, lo pendiente es `users.html` (migración ArriVix sin commitear, 217 inserciones / 62 borrados) en su propia rama.

## Deuda técnica registrada

- **El borrado pierde los filtros del dashboard**: el formulario solo envía `page`, `size` y `search`, así que tras borrar se vuelve a la lista sin filtro de naviera, estado, puerto ni ETA. Preexistente, pero ahora visible porque el botón aplica a todas las guías pendientes.
- **`ShipmentService.deleteShipment` sigue público y sin guardas**: cualquier caller futuro que lo use se salta la regla. El nombre seguro por defecto sería el restringido.
- **Sin CSRF en los POST**: mitigado por la cookie `SameSite=Lax`, no por el servidor. Afecta a borrar, registrar y "Monitorear Ahora" por igual.
- **`*.perm-probe.tmp` en los árboles de código**: `src/main/java/com/shiparrival/.perm-probe.tmp` y `src/test/java/com/shiparrival/.perm-probe.tmp`. Sin rastrear, no entran al JAR; son residuo de una prueba de permisos. No bloquean nada.
- **`users.html` sin commitear**: la migración ArriVix de esa plantilla (279 líneas) sigue en el árbol de trabajo sobre `master` en `ba3fdfd`. Vive fuera de este ciclo y necesita su propia rama.

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
