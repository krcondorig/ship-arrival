# Hapag-Lloyd tracking via ZenRows

El adaptador Hapag-Lloyd consulta ZenRows Fetch API desde Java. Define `ZENROWS_API_KEY` en el entorno local o como secreto del proveedor de despliegue; no la escribas en archivos versionados ni la compartas en logs.

Cada B/L genera dos solicitudes: una para obtener la tabla de contenedores y otra para los detalles del primer contenedor. Los créditos cobrados dependen del modo que ZenRows use y comunica en las cabeceras de respuesta; revisa el uso real en la cuenta ZenRows y no asumas una cifra fija.

El timeout se configura con `ZENROWS_TIMEOUT_SECONDS` (por defecto 180 segundos). Las pruebas automatizadas usan HTML local simulado y no llaman al servicio externo.
