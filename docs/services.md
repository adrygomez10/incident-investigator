# Configurar los servicios externos

Esta guía completa los pasos del [README](../README.md). Trabaja con pedidos y usuarios ficticios y un proyecto Sentry de pruebas. Cada servicio tiene su propia cuenta; el token del operador y la contraseña de PostgreSQL se generan localmente.

## Sentry: recibir eventos desde pedidos

1. Crea una cuenta en [Sentry](https://sentry.io/) y una organización. Para este recorrido utiliza su modalidad gratuita; revisa las cuotas de tu cuenta al terminar cualquier periodo inicial de prueba.
2. En el asistente elige **Set up manually**, **Continue without a repo** y plataforma **Java**, **Nope, Vanilla**. Nuestro adaptador utiliza el SDK Java directo aunque la aplicación arranque con Spring Boot.
3. Selecciona **Error monitoring** y **Tracing**. Puedes omitir avisos por Slack, Discord o Teams. No necesitas Seer, instalación mediante agente, OpenTelemetry ni plugins que suban código fuente.
4. Nombra el proyecto `orders-incident-demo`. Si el asistente lo crea como `java`, puedes mantener ese slug y configurarlo igual en el investigador, o renombrarlo en los ajustes del proyecto.
5. Copia el **DSN** del asistente o de **Settings → Projects → tu proyecto → Client Keys (DSN)**. En `orders-incident-demo/.env`, localiza `SENTRY_DSN=` y pega el valor después del `=`.
6. Si pedidos ya estaba arrancado, desde su carpeta ejecuta:

```bash
docker compose up -d --force-recreate app
curl -i -X POST http://127.0.0.1:18091/orders \
  -H 'Content-Type: application/json' \
  -H 'X-Demo-User: demo-user-1' \
  -H 'X-Client-Version: web-2' \
  -d '{"quantity":1,"customerReference":"ORDER-SENTRY-CHECK"}'
```

Debe devolver 201. En la vista **Tracing / Traces** de Sentry busca `POST /orders`, proyecto correcto, entorno `workshop` e intervalo reciente. La recepción puede tardar unos segundos. Verifica la versión en los atributos. Los rechazos de validación devuelven 400; las excepciones de aplicación devuelven 500 y se capturan como errores. Una petición correcta puede aparecer como traza sin crear una incidencia en **Issues**.

## Sentry: permitir consultas desde el investigador

El DSN permite **enviar** eventos. Para **leerlos**, crea un token distinto:

1. Abre **User settings → Personal Tokens**; en algunas versiones aparece como **User Auth Tokens**.
2. Crea un token llamado `incident-investigator-read` con `org:read`, `project:read`, `team:read` y `event:read`.
3. Pega su valor en la línea `SENTRY_ACCESS_TOKEN=` de `incident-investigator/.env`.
4. Completa `SENTRY_ORGANIZATION` con el slug de la organización y `SENTRY_PROJECT` con el slug del proyecto. Son los identificadores de sus ajustes o URL, no el nombre visible ni un ID numérico.
5. Configura `SENTRY_REGION` con la URL de la región de tu organización:

| Región | `SENTRY_REGION` |
| --- | --- |
| Alemania / DE | `https://de.sentry.io` |
| Estados Unidos / US | `https://us.sentry.io` |
| Ruta global admitida por tu organización | `https://sentry.io` |

Comprueba la región en Sentry. Un DSN con `ingest.de.sentry.io` corresponde a DE; uno con `ingest.us.sentry.io`, a US. No pegues el DSN en `SENTRY_REGION`: se necesita la URL de la tabla. El MCP se limita al skill `inspect`; el proyecto no requiere permisos de escritura ni un proveedor interno de IA de Sentry.

Referencias: [autenticación de Sentry](https://docs.sentry.io/api/auth/), [DSN](https://docs.sentry.io/concepts/key-terms/dsn-explainer/), [MCP oficial y permisos](https://github.com/getsentry/toolkit/tree/main/packages/mcp-core), [planes](https://sentry.io/pricing/).

## Groq: ejecutar el modelo

El proveedor configurado es **Groq** (con q), que aloja `openai/gpt-oss-20b`. No es Grok ni requiere una clave de OpenAI.

1. Crea tu cuenta en [Groq Console](https://console.groq.com/) y conserva el plan gratuito para el recorrido básico.
2. En [API Keys](https://console.groq.com/keys), crea una clave llamada `incident-investigator-local`.
3. En `incident-investigator/.env`, pega la clave después de `MODEL_API_KEY=` y conserva:

```dotenv
MODEL_BASE_URL=https://api.groq.com/openai/v1
MODEL_NAME=openai/gpt-oss-20b
MODEL_MIN_INTERVAL_SECONDS=65
```

El modelo admite herramientas y figura en la [tabla de cuotas gratuitas](https://console.groq.com/docs/rate-limits); consulta los límites efectivos de tu cuenta. La aplicación solicita una sola herramienta por paso y espera entre llamadas. Si el proveedor devuelve 429, conserva `Retry-After` y termina como indisponibilidad temporal. No contrata capacidad ni cambia automáticamente de proveedor. Una investigación envía al proveedor fragmentos del contexto, código y eventos; utiliza datos ficticios.

Referencias: [crear una clave](https://console.groq.com/docs/quickstart), [herramientas](https://console.groq.com/docs/tool-use/overview).

### Endpoint alternativo, opcional

Puedes cambiar `MODEL_BASE_URL`, `MODEL_NAME` y `MODEL_API_KEY` por un servidor compatible con **Chat Completions**, incluidos modelos descargables de Hugging Face como [Qwen3-4B](https://huggingface.co/Qwen/Qwen3-4B). `MODEL_BASE_URL` es la base de API, normalmente terminada en `/v1`; el adaptador añade `/chat/completions`. Configura también el intervalo apropiado en `MODEL_MIN_INTERVAL_SECONDS`.

El endpoint debe devolver `tool_calls` y aceptar resultados con rol `tool` y su `tool_call_id`; responder a un chat simple no basta. Si sirves pesos con vLLM, configura su [soporte de herramientas](https://docs.vllm.ai/en/latest/features/tool_calling/). Pesos, infraestructura, memoria y tiempo de inferencia tienen costes y requisitos separados; no son necesarios para la ruta con Groq.

Usa HTTPS para un servidor remoto. Dentro de Docker, `localhost` es el propio contenedor: para un servidor del equipo anfitrión puedes usar `host.docker.internal` si tu instalación lo resuelve. En Docker Engine Linux tendrás que añadir a `app` un `extra_hosts` con `host.docker.internal:host-gateway`. Esa resolución de red es independiente de la compatibilidad de herramientas del modelo.

## GitHub: consultar tu copia de pedidos

La copia de `orders-incident-demo` debe estar accesible en GitHub, con el historial y el SHA completo que figura en `APP_RELEASE`. Un directorio local o un ZIP no proporciona esa información al MCP de GitHub. Configura `GITHUB_REPOSITORY=propietario/repositorio` con tu copia; no es una URL ni incluye `.git`.

1. Abre **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token** en GitHub.
2. Elige el propietario correcto en **Resource owner**, una caducidad y el nombre `incident-investigator-read`.
3. En **Repository access**, selecciona **Only select repositories** y únicamente la copia de pedidos que vas a investigar.
4. En **Repository permissions**, asigna **Read-only** a **Contents**, **Pull requests** e **Issues**. **Metadata: Read-only** se incluye automáticamente.
5. Genera el token y pégalo en `GITHUB_READ_TOKEN=` del `.env` del investigador. Si requiere aprobación de una organización, espera a que se conceda antes de probar.

El servidor MCP se ejecuta en modo de lectura y Java añade restricciones de repositorio y herramientas. El token no necesita administración, Actions ni escritura de código.

### Crear incidencias, opcional

Puedes investigar sin `GITHUB_WRITE_TOKEN`. Para completar la creación de un borrador aprobado, habilita **Issues** en tu repositorio y crea **otro** token fine-grained, limitado al mismo repositorio, con **Issues: Read and write** y **Metadata: Read-only**. Guárdalo en `GITHUB_WRITE_TOKEN=` y recrea el contenedor del investigador.

El caso de uso de aprobación utiliza ese token por separado; el LLM no recibe herramientas de escritura. Sigue [la revisión y creación por API](api.md#aprobar-y-crear-la-incidencia) para aprobar la versión exacta. El token de escritura por sí solo no crea nada.

Referencias: [tokens y repositorios seleccionados](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens), [servidor MCP oficial](https://github.com/github/github-mcp-server).
