# ADR: núcleo independiente y publicación aprobada

Estado: aceptada.

## Contexto

Una investigación combina decisiones del modelo, resultados externos, evidencia persistida y una escritura posterior en GitHub. Hay que poder cambiar proveedor o almacenamiento y mantener las reglas de estados, citas y aprobación aunque falle una integración.

## Decisión

Un único servicio Spring Boot, con Clean Architecture y DDD sobre las reglas del caso:

```text
adapter/in/web, adapter/in/jobs → application → domain
adapter/out/model, adapter/out/mcp, adapter/out/persistence → application + domain
bootstrap → composición
```

`Investigation` encapsula estados, evidencias, diagnóstico, borrador versionado y aprobación del repositorio, título y cuerpo exactos. Los valores `ProjectScope`, `InvestigationWindow` y `Diagnosis` protegen sus restricciones. No se convierten respuestas de Sentry o GitHub en entidades de dominio.

`ManageInvestigations` resuelve las peticiones del operador. `Investigator` coordina el ciclo del modelo mediante puertos de modelo, herramientas, recuperación y persistencia. `InvestigationWork` conserva conversación y consumo fuera del agregado. Los adaptadores contienen HTTP, SDK MCP, JDBC y traducciones; dominio y aplicación no dependen de Spring.

PostgreSQL conserva instantáneas JSONB versionadas y el historial. Las actualizaciones comprueban la versión anterior; los trabajadores reclaman trabajos con concesiones temporales y transacciones breves que terminan antes de llamar a servicios externos. La restauración valida el estado del agregado.

El MCP utiliza procesos independientes de los servidores oficiales por `stdio`. El modelo recibe únicamente lecturas autorizadas. `Publisher` utiliza un token y un caso de uso separados después de la aprobación. Una respuesta incierta reserva el resultado como desconocido; la reconciliación busca un marcador y no repite automáticamente la escritura.

La recuperación empieza con índices léxicos de PostgreSQL y un puerto intercambiable. Los fragmentos conservan ruta, sección, líneas, revisión y huella. No se añade una base vectorial sin una necesidad medida.

## Alternativas y consecuencias

Concentrar HTTP, reglas y proveedores en un controlador reduciría archivos, pero impediría probar las garantías sin infraestructura y mezclaría aprobación con ejecución. Separar cada integración en un microservicio añadiría despliegues y coordinación distribuida sin una necesidad actual. Los puertos y adaptadores localizan cambios, a cambio de contratos y traducciones.

JSONB simplifica guardar avances atómicos, pero reescribe el historial y dificulta consultas agregadas: si crece el volumen se podrán separar pasos y evidencias en tablas. La búsqueda léxica es sencilla y conserva identificadores técnicos; una evaluación de relevancia decidirá si conviene combinarla con búsqueda semántica.

Evitar reenvíos inciertos reduce duplicados a cambio de requerir intervención humana en algunos resultados desconocidos. ArchUnit comprueba las dependencias y encapsulación; las pruebas de dominio, contratos y PostgreSQL comprueban las reglas. Esas garantías no demuestran por sí solas la calidad de investigación de un modelo real.
