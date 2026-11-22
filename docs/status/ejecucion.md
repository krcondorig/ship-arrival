# Estado de ejecución

**Actualizado:** 2026-11-20
**Fase:** Migración de prototipos UI al sistema visual ArriVix.
**Último commit:** c3688cc — "fix(history): translate status and event type labels to Spanish"

## Migración UI (ArriVix)

| Vista | Template | Estado |
|---|---|---|
| Dashboard | src/main/resources/templates/dashboard.html | Migrado — tokens portados de dashboard-single-action.html |
| Historial | src/main/resources/templates/history.html | Migrado |
| Login | src/main/resources/templates/login.html | Migrado |
| Usuarios | src/main/resources/templates/users.html | En curso (frontend-dev) |

## Gates

- QA: sin datos aún.
- Code review: sin datos aún.
- Deploy: pendiente; se valida en local antes de desplegar.

## Bloqueos

Ninguno.

## Próximo paso

Validar users.html en local (users-preview.html o ./mvnw.cmd spring-boot:run) antes de pasar los gates y desplegar.

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
