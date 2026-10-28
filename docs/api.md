# Usar la API y aprobar una incidencia

Arranca el proyecto siguiendo el [README](../README.md). Los comandos usan Bash y curl. Salud es pública; las demás rutas requieren el `OPERATOR_TOKEN` configurado en el contenedor. Hay un operador y un proyecto configurado, no un sistema de cuentas de usuario.

Desde `incident-investigator`, introduce el token local para esta terminal:

```bash
read -rsp 'OPERATOR_TOKEN del .env: ' OPERATOR_TOKEN
printf '\n'
export OPERATOR_TOKEN
mkdir -p reports
```

## Crear y consultar una investigación

Prepara `reports/request.json`. Esta petición usa la ventana por defecto de las últimas 24 horas:

```bash
cat > reports/request.json <<'JSON'
{
  "projectKey": "orders-demo",
  "question": "Investiga si hay errores al guardar pedidos. Contrasta eventos, versión, código y documentación y separa las evidencias de las hipótesis."
}
JSON
```

Para limitarla al lote generado, añade `since` y `until` con las fechas UTC/ISO 8601 de `orders-incident-demo/reports/traffic.json`. No copies una fecha ilustrativa antigua. La ventana debe ser positiva y de hasta noventa días.

Crea una clave y conserva su valor para reintentos de esta misma petición:

```bash
investigation_key=$(python3 -c 'import uuid; print(uuid.uuid4())')
curl --fail-with-body -sS -X POST http://127.0.0.1:18092/investigations \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $investigation_key" \
  --data-binary @reports/request.json \
  -o reports/investigation.json
python3 -m json.tool reports/investigation.json
```

La respuesta es **202**, con el objeto y una cabecera `Location: /investigations/{id}`. El trabajo continúa en segundo plano. Repetir la misma clave y petición devuelve la investigación existente; reutilizarla con otra entrada devuelve 409.

Recupera su identificador y consulta los avances:

```bash
investigation_id=$(python3 -c 'import json; print(json.load(open("reports/investigation.json"))["id"])')
curl --fail-with-body -sS \
  "http://127.0.0.1:18092/investigations/$investigation_id" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -o reports/investigation.json
python3 -m json.tool reports/investigation.json
```

Repite el GET mientras esté en `QUEUED` o `RUNNING`; no repitas el POST con claves nuevas para consultar. `diagnosis` separa hechos, hipótesis, incertidumbres y comprobaciones; `evidence` contiene las fuentes, `steps` las llamadas y resultados, y `failure` el fallo de ejecución cuando lo hay. `modelCalls`, `toolCalls`, `inputTokens` y `outputTokens` conservan el consumo disponible. `draft` contiene versión, título, cuerpo y huella.

| Estado | Cómo continuar |
| --- | --- |
| `COMPLETED` | Revisar diagnóstico y borrador; también puede concluir que no hay evidencia suficiente |
| `WAITING_CLARIFICATION` | Leer `clarification` y responder con la versión actual |
| `TEMPORARILY_UNAVAILABLE` | Leer `failure` y los pasos; corregir servicio o cuota y respetar la espera antes de encargar otra investigación |
| `BUDGET_EXHAUSTED` | Revisar avances; acotar la siguiente pregunta o ajustar límites antes de crear otra investigación |
| `INTERRUPTED` | El trabajo se interrumpió; los avances siguen disponibles, sin reanudación automática |

Los tres últimos estados conservan sus hallazgos; la API actual no proporciona una ruta genérica para reanudarlos. Una nueva investigación utiliza una nueva clave. Las concesiones de trabajo duran ocho minutos y se renuevan al guardar avances; un reinicio puede requerir que venza la concesión antes de reflejar la interrupción.

## Responder una aclaración

Solo se admite en `WAITING_CLARIFICATION`. Consulta primero el GET y crea `reports/clarification.json` con el campo `version` actual como `expectedVersion` y tu respuesta como `message`:

```json
{"expectedVersion": 7, "message": "El cliente afectado es legacy-1; investiga la ventana del último lote."}
```

El 7 es ilustrativo: sustitúyelo por la versión devuelta en tu investigación.

```bash
curl --fail-with-body -sS -X POST \
  "http://127.0.0.1:18092/investigations/$investigation_id/messages" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @reports/clarification.json \
  -o reports/investigation.json
```

Devuelve 202 y permite continuar el trabajo. Si la versión cambió, devuelve 409: vuelve a consultar antes de responder.

## Editar el borrador

Consulta el estado y revisa `repository`, `draft.title` y `draft.body`, incluidas las fuentes y el marcador de investigación. Para modificarlo, prepara `reports/draft.json` con la versión actual de `draft.version` como `expectedDraftVersion`:

```json
{
  "expectedDraftVersion": 1,
  "title": "Título revisado por el operador",
  "body": "Cuerpo completo revisado, con hechos, fuentes y comprobaciones."
}
```

```bash
curl --fail-with-body -sS -X PUT \
  "http://127.0.0.1:18092/investigations/$investigation_id/draft" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @reports/draft.json \
  -o reports/investigation.json
python3 -m json.tool reports/investigation.json
```

La aplicación devuelve una versión y huella nuevas y conserva el marcador de reconciliación. Revisa el contenido devuelto antes de aprobarlo. No se puede editar mientras se publica, después de publicar ni cuando el resultado de publicación sea incierto.

## Aprobar y crear la incidencia

Este paso **escribe en GitHub**. Requiere `COMPLETED`, el token de escritura configurado según [la guía de servicios](services.md#crear-incidencias-opcional) y la revisión del repositorio, título y cuerpo exactos. El script `investigate.py` nunca ejecuta este paso.

Después del último GET y de revisar `reports/investigation.json`, genera la solicitud desde esa versión exacta:

```bash
python3 - <<'PY'
import json
from pathlib import Path

item = json.loads(Path("reports/investigation.json").read_text())
assert item["status"] == "COMPLETED", "Review the investigation status"
draft = item["draft"]
approval = {"draftVersion": draft["version"], "hash": draft["hash"]}
Path("reports/approval.json").write_text(json.dumps(approval) + "\n")
PY
approval_key=$(python3 -c 'import uuid; print(uuid.uuid4())')
```

El siguiente POST aprueba y solicita la creación. Ejecútalo únicamente si has decidido publicar ese contenido:

```bash
curl --fail-with-body -sS -X POST \
  "http://127.0.0.1:18092/investigations/$investigation_id/issue" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $approval_key" \
  --data-binary @reports/approval.json \
  -o reports/publication.json
python3 -m json.tool reports/publication.json
```

Devuelve 202. Consulta el GET hasta `PUBLISHED`, con `issueUrl`, o `PUBLISH_UNKNOWN`. Una versión/hash antiguos o una clave distinta para una reserva existente producen 409. Si necesitas reintentar, conserva **la misma clave y el mismo archivo de aprobación**; no generes otra clave para esa publicación.

En `PUBLISH_UNKNOWN`, repetir exactamente esa solicitud busca el marcador en las incidencias del repositorio y no vuelve a ejecutar la creación. Si no encuentra un recibo, mantiene la incertidumbre y requiere revisión humana. La búsqueda se limita a veinte páginas de cien incidencias. Conserva `approval_key` si vas a cerrar la terminal.

## Errores de contrato

400 indica entrada inválida; 401, autenticación; 404, identificador ausente; 409, conflicto de versión o estado; 413, cuerpo superior a 64 KiB. Los fallos de modelo o herramientas después del 202 se consultan dentro de la investigación. La salud del contenedor no garantiza credenciales externas válidas.
