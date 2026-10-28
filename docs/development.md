# Desarrollo y comprobaciones

Para ejecutar la aplicación con Docker sigue el [README](../README.md). Esta guía se utiliza al modificar o verificar el código.

## Java y Python

Instala JDK 25 y Python 3.12. Maven Wrapper fija Maven; no necesitas instalar Maven por separado. Desde esta carpeta:

```bash
PYTHON=python3 ./mvnw verify
python3 -m pip install -r requirements-dev.txt
python3 -m ruff check .
python3 -m ruff format --check .
python3 -m unittest discover -s src/test/python
```

`verify` ejecuta pruebas, ArchUnit, Spotless y Checkstyle con el perfil Clean Code - Project Style, también sobre las pruebas. Sus avisos hacen fallar la comprobación. `./mvnw spotless:apply` aplica el formato; la revisión manual completa contratos, nombres y separación lógica. Python sigue PEP 8 y PEP 257 con Ruff fijado en `requirements-dev.txt`. No hacen falta estas herramientas de estilo para usar los scripts del recorrido básico.

## Persistencia real en PostgreSQL

La suite JDBC solo se ejecuta si recibe `TEST_JDBC_URL`. Sin esa variable, no estás verificando persistencia real. Utiliza una base desechable distinta de tus servicios locales:

```bash
docker run -d --rm --name investigator-tests \
  -p 127.0.0.1:15479:5432 \
  -e POSTGRES_DB=investigator_test \
  -e POSTGRES_USER=investigator_test \
  -e POSTGRES_PASSWORD=test-only \
  postgres:17.6-alpine
docker exec investigator-tests pg_isready -U investigator_test
```

Espera a que `pg_isready` indique que acepta conexiones y ejecuta:

```bash
TEST_JDBC_URL=jdbc:postgresql://localhost:15479/investigator_test \
TEST_DB_USER=investigator_test TEST_DB_PASSWORD=test-only \
PYTHON=python3 ./mvnw verify
docker stop investigator-tests
```

Las pruebas crean y eliminan un esquema aleatorio dentro de esa base. No apuntes las variables de prueba a una base con datos que quieras conservar.

## Procesos MCP y contenedor con respuestas controladas

Las pruebas Java arrancan procesos MCP de prueba mediante Python. No necesitan cuentas externas. La imagen Docker incorpora Node y los binarios oficiales fijados; puedes comprobar su inicialización sin red:

```bash
docker build -t incident-investigator:verification .
python3 scripts/probe_official_mcp.py
```

El informe `reports/official-capabilities.json` describe catálogos y compatibilidad de transporte, no acceso a datos reales ni calidad del modelo.

Para el recorrido controlado completo, libera el puerto 18092 deteniendo primero el investigador local. El helper usa exactamente `.tools/corpus`; exporta a esa carpeta nueva o vacía:

```bash
python3 scripts/export_corpus.py ../orders-incident-demo HEAD --output .tools/corpus
```

No modifica tu `.env` y monta los procesos de prueba desde `scripts/fixtures`.

Arranca su base dedicada y espera a que acepte conexiones:

```bash
docker run -d --rm --name incident-investigator-verification-db \
  -e POSTGRES_DB=investigator_test \
  -e POSTGRES_USER=investigator_test \
  -e POSTGRES_PASSWORD=test-only \
  postgres:17.6-alpine
docker exec incident-investigator-verification-db pg_isready -U investigator_test
```

Con la imagen `incident-investigator:verification` construida, `.tools/corpus/manifest.json` presente y una carpeta `.tools/fixture-state` nueva o sin recibos de una ejecución anterior:

```bash
python3 scripts/smoke_container.py
docker stop incident-investigator-verification-db
```

El helper comprueba API, SDK MCP real, recuperación PostgreSQL, aprobación, respuesta de escritura perdida y recibo tras reinicio. Retira sus contenedores y red; la base dedicada se detiene con el último comando. Antes de repetir, conserva fuera de `.tools/fixture-state` los resultados anteriores que necesites. Todas las respuestas externas de esta prueba son fixtures identificados; no ejecuta escrituras en GitHub real.

## Evaluación con cuentas reales

Después de completar el recorrido local y exportar `OPERATOR_TOKEN` a la terminal, puedes ejecutar:

```bash
python3 scripts/evaluate_live.py --confirm-live
```

Ejecuta los casos de `evaluation/cases.json`, consume cuota y guarda respuestas reales y una ficha CSV para revisión manual en `reports/`. Nunca aprueba incidencias ni calcula automáticamente una puntuación de calidad. Los resultados esperados de evaluación están fuera del corpus documental de pedidos.
