# Incident Investigator

Agente Java que consulta Sentry y GitHub mediante MCP, recupera documentación y prepara un diagnóstico con evidencias y un borrador de incidencia. Su API permite encargar una investigación, consultar sus avances y aprobar un contenido concreto antes de crearlo en GitHub.

## Qué vas a ejecutar

Necesitas dos proyectos independientes, colocados como carpetas hermanas:

```text
proyectos/
├── incident-investigator/
└── orders-incident-demo/
```

| Pieza | Función | Dirección local |
| --- | --- | --- |
| `orders-incident-demo` | Recibe pedidos y envía errores y trazas a Sentry | `http://127.0.0.1:18091` |
| `incident-investigator` | Investiga los datos y conserva el diagnóstico | `http://127.0.0.1:18092` |
| PostgreSQL de cada proyecto | Guarda sus propios datos en un volumen Docker | Sin puerto público |
| Sentry, GitHub y Groq | Errores, código y modelo, respectivamente | Servicios externos |

Las dos aplicaciones son API: abrir `/` en el navegador devuelve 404. Para comprobar el arranque se utiliza `/actuator/health`. El investigador no llama a la API de pedidos: consulta los eventos enviados a Sentry, el código accesible en GitHub y los documentos exportados de Git.

## Requisitos

- Docker con contenedores Linux y Compose v2. La imagen del investigador incluye un binario GitHub MCP para **amd64**; en ARM hace falta emulación de esa plataforma o adaptar la imagen.
- Git, curl 7.76 o posterior y Python 3.12 o posterior para los scripts locales. No requieren paquetes Python adicionales para el recorrido básico.
- Una terminal **Bash**, por ejemplo WSL en Windows. Los comandos siguientes usan `python3` y sintaxis Bash.
- Acceso a Internet durante la construcción y la investigación. Java, Maven, Node y los servidores MCP se instalan dentro de las imágenes; no necesitas instalarlos en tu equipo para arrancar con Docker.
- Cuentas de Sentry, GitHub y Groq para la investigación real. La API de pedidos se puede probar por separado sin esas cuentas.

Comprueba las herramientas:

```bash
docker version
docker compose version
git --version
curl --version
python3 --version
```

## 1. Preparar las copias y los servicios

Para seguir el recorrido completo con tus propios permisos, utiliza **Fork** en GitHub sobre el repositorio de pedidos y clona tu fork con Git. Clona también el investigador y conserva los nombres de carpeta indicados. La copia de pedidos debe estar accesible en GitHub para su token, incluido el SHA que ejecutarás. Configura su propietario real, no el del autor del ejemplo. El fork conserva el historial y permite crear la incidencia en tu propia copia; habilita **Issues** en sus ajustes si está desactivado.

Una descarga ZIP o una carpeta sin commits permite probar pedidos, pero no exportar un corpus ligado a un commit ni consultar ese código por SHA en GitHub. En ese caso utiliza el modo local del [README de pedidos](../orders-incident-demo/README.md) y prepara el historial accesible antes de continuar con la investigación completa.

Desde esta carpeta, crea la configuración solo si todavía no existe:

```bash
test -f .env || cp .env.example .env
```

Sigue [la guía de servicios](docs/services.md) para crear el proyecto Sentry, obtener las claves y completar sus slugs y región. La guía indica qué valor va en cada proyecto y cómo comprobar la recepción de trazas.

## 2. Completar el `.env` del investigador

Genera un valor aleatorio para el token del operador y otro distinto para la contraseña de esta base de datos. Ejecuta este comando dos veces y copia cada resultado en su línea correspondiente:

```bash
python3 -c 'import secrets; print(secrets.token_hex(32))'
```

| Variable | Valor que debes guardar |
| --- | --- |
| `OPERATOR_TOKEN` | Primer valor generado; autentica las peticiones a esta API, mínimo 32 caracteres |
| `DB_PASSWORD` | Segundo valor generado; contraseña de PostgreSQL del investigador |
| `MODEL_API_KEY` | Clave de Groq |
| `GITHUB_REPOSITORY` | `tu-usuario/orders-incident-demo`, o propietario/nombre de tu copia accesible |
| `GITHUB_READ_TOKEN` | Token limitado a esa copia, con lectura de Contents, Pull requests e Issues |
| `SENTRY_ORGANIZATION` | Slug de la organización de Sentry |
| `SENTRY_PROJECT` | Slug real del proyecto; puede ser `java` si no cambiaste el nombre por defecto |
| `SENTRY_REGION` | URL regional indicada en la guía de servicios |
| `SENTRY_ACCESS_TOKEN` | Token de usuario de Sentry con los cuatro permisos de lectura de la guía |

Conserva inicialmente `PROJECT_KEY=orders-demo`, `MODEL_BASE_URL=https://api.groq.com/openai/v1`, `MODEL_NAME=openai/gpt-oss-20b` y `MODEL_MIN_INTERVAL_SECONDS=65`. Deja `GITHUB_WRITE_TOKEN` vacío si solo vas a investigar y revisar borradores. Podrás configurarlo al seguir [la aprobación por API](docs/api.md#aprobar-y-crear-la-incidencia).

Los valores reales se escriben en `.env`, que está excluido de Git y de la construcción Docker. `.env.example` es únicamente la plantilla. No compartas los archivos de configuración ni informes con datos reales.

## 3. Arrancar pedidos y generar los datos

Sigue el [arranque de pedidos](../orders-incident-demo/README.md): configura su propia contraseña, el DSN de Sentry y `APP_RELEASE=orders-demo@<SHA completo>`. Ese SHA debe existir en el repositorio indicado en `GITHUB_REPOSITORY`. Comprueba la salud de pedidos y envía un lote:

```bash
cd ../orders-incident-demo
docker compose up --build -d
curl -fsS http://127.0.0.1:18091/actuator/health
python3 scripts/generate_traffic.py --requests 30 --output reports/traffic.json
cd ../incident-investigator
```

El informe de tráfico conserva la ventana UTC, el total y los estados de respuesta. Comprueba también en Sentry la recepción de `POST /orders`, entorno `workshop`, y la versión configurada. Un HTTP 201 solo acredita el guardado; ver la traza en Sentry acredita además su recepción. Si esta copia de pedidos es la base funcional, el lote debe guardar los treinta pedidos y no producir excepciones. El investigador debe reconocer esa ausencia de evidencias, sin inventar una regresión.

## 4. Preparar el corpus documental

Desde la carpeta del investigador, exporta los Markdown del mismo commit de pedidos:

```bash
demo_revision=$(git -C ../orders-incident-demo rev-parse HEAD)
python3 scripts/export_corpus.py ../orders-incident-demo "$demo_revision" \
  --output ".tools/corpus-$demo_revision"
```

El script muestra la revisión completa, la ruta y el número de documentos. En `.env`, ajusta `CORPUS_PATH` a `.tools/corpus-<ese SHA>` y `CORPUS_REVISION` a ese mismo SHA, sin el prefijo `orders-demo@`. La ruta es local, relativa a esta carpeta; Compose la monta de solo lectura en `/app/corpus`.

La exportación lee los bytes del commit, no modificaciones locales sin guardar. Exige una carpeta nueva o vacía: si ya exportaste esa revisión, puedes reutilizar la carpeta existente sin volver a exportarla. Para otro commit usa otra carpeta. El servidor verifica el manifiesto y sus huellas antes de indexar en PostgreSQL.

## 5. Arrancar el investigador

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://127.0.0.1:18092/actuator/health
```

Espera a que salud devuelva `"status":"UP"`. Si no responde, revisa `docker compose logs --tail 80 app db`. El arranque comprueba PostgreSQL y carga el corpus; no hace una investigación ni valida por sí solo las tres credenciales externas.

## 6. Encargar una investigación

El script local necesita el mismo `OPERATOR_TOKEN` que guardaste en `.env`. Compose lo carga dentro del contenedor, pero no lo exporta a esta terminal. Introduce el valor en el siguiente prompt; no se muestra mientras lo pegas:

```bash
read -rsp 'OPERATOR_TOKEN del .env: ' OPERATOR_TOKEN
printf '\n'
export OPERATOR_TOKEN
python3 scripts/investigate.py \
  'Comprueba si hay fallos al guardar pedidos. Contrasta los eventos, el código y el contrato del campo opcional; si no hay evidencias suficientes, indícalo.'
```

El script crea una investigación sobre las últimas 24 horas, consulta su estado y guarda `reports/investigation.json`. No crea incidencias. Lee `diagnosis`, `evidence`, `steps`, `failure` y `draft` en ese archivo para revisar lo que obtuvo. Con Groq puede tardar varios minutos: hay una espera de 65 segundos entre comienzos de llamadas y un presupuesto de seis minutos.

Para elegir la ventana exacta de `reports/traffic.json`, aclarar una pregunta o editar y aprobar el borrador, continúa con [los ejemplos completos de la API](docs/api.md).

## Parar, actualizar y resolver problemas

Desde cada carpeta, `docker compose down` detiene sus servicios y conserva el volumen de PostgreSQL. Para aplicar cambios de `.env`, utiliza `docker compose up -d --force-recreate app`; para reconstruir después de cambiar código, añade `--build`. Para cambiar el corpus, exporta la nueva revisión, actualiza sus dos variables y recrea el investigador. La ingesta reemplaza el índice compartido por el proyecto; las evidencias ya guardadas conservan sus fragmentos.

| Síntoma | Qué comprobar |
| --- | --- |
| 404 en `/` | No hay interfaz gráfica; utiliza salud o las rutas de API |
| Conexión rechazada o sin salud | Estado y registros de `app` y `db`; puertos 18091 y 18092 disponibles |
| `Set DB_PASSWORD` o `Set CORPUS_PATH` | Falta completar `.env` en la carpeta correcta |
| `password authentication failed` tras cambiar la contraseña | El volumen de PostgreSQL conserva su contraseña inicial; usa la anterior o cambia la contraseña en la base antes de actualizar `.env` |
| `OPERATOR_TOKEN` rechazado o HTTP 401 | Token de al menos 32 caracteres y mismo valor en cliente y contenedor; recrea `app` si lo cambiaste |
| Corpus inválido | Ruta exportada, `manifest.json`, SHA completo y huellas sin modificar |
| `HEAD` no existe o SHA no encontrado en GitHub | Hace falta un checkout con commits y esa revisión accesible al token |
| Sin eventos Sentry | DSN en pedidos, recreación de `app`, filtros de proyecto/entorno/fecha y tráfico después de configurar; la base funcional no genera errores |
| MCP devuelve 401/403/404 | Token, permisos, propietario/repositorio, slugs y región; consulta `steps` y `failure` |
| `TEMPORARILY_UNAVAILABLE` / HTTP 429 del proveedor | Cuota efectiva y espera `Retry-After`; no hay cambio automático a un plan de pago |
| `WAITING_CLARIFICATION`, `BUDGET_EXHAUSTED` o `INTERRUPTED` | Revisa los avances guardados y las acciones admitidas en la guía de API |

## Documentación del proyecto

- [Servicios y credenciales](docs/services.md): Sentry, GitHub, Groq y endpoint alternativo.
- [API y aprobación](docs/api.md): peticiones, respuestas, versiones e idempotencia.
- [Desarrollo y comprobaciones](docs/development.md): Java, Python, PostgreSQL y pruebas controladas.
- [ADR: arquitectura y persistencia](docs/architecture.md): reglas, alternativas y costes.
