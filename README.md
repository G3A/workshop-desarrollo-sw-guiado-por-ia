# Base de Conocimiento interna

Un RAG interno que responde preguntas sobre tus documentos, tu código, tus canales de Teams y tus
work items, con **citas verificables**. Reproduce la arquitectura que Cerebras describió en
*How we built our knowledge base*, con dos diferencias:

- En vez de Slack, **dos adaptadores**: una UI HTML/JS y un bot de Teams.
- **Costo cero**: modelos abiertos en contenedores locales. Sin APIs de pago, sin nube.

## Qué lo hace distinto de un RAG genérico

| Decisión | Por qué |
|---|---|
| **El texto crudo nunca se embebe** | Un LLM destila cada hilo en pregunta, resumen, resolución y sistemas involucrados. El embedding ancla ahí; el crudo solo alimenta la búsqueda de texto completo. |
| **Una sola tabla de embeddings** | Documentos, código, hilos y work items comparten tabla, índices e interfaz de consulta. Sin migración de datos, sin silos. |
| **Cuatro señales, no una** | Texto completo, vectorial, supresión por IDF y decaimiento por antigüedad. Cada una cubre el punto ciego de las otras. |
| **Fusión por rango, no por puntaje** | RRF con k=60: gana el consenso entre recuperadores, no un primer puesto afortunado. |
| **Cross-encoder antes de sintetizar** | La defensa contra falsos positivos que ninguna búsqueda por similitud da sola. |
| **Los adaptadores son piel** | Una prueba de ArchUnit falla el build si la UI o el bot tocan el retrieval. |

## Requisitos

- Docker Desktop con WSL2
- ~15 GB libres en disco (5 GB de modelos + base + imágenes)
- 16 GB de RAM (recomendado 32)
- **Opcional**: GPU NVIDIA con `nvidia-container-toolkit`

Java y Maven **no** hacen falta para ejecutarlo: el build ocurre dentro de Docker. Solo los
necesitas para desarrollar (Java 21 y el wrapper `./mvnw` incluido).

## Arranque

```bash
cp .env.example .env
make up                 # levanta db, ollama y api
make pull-models        # ~5 GB, una sola vez
make health             # confirma que no falta ningún modelo
```

Abre <http://localhost:8080>. Para poblarlo con el corpus de ejemplo: `make seed`.

Si el host tiene una GPU NVIDIA (con `nvidia-container-toolkit`), `make up` la detecta sola y
levanta Ollama con la VRAM reservada — no hace falta ningún paso extra. Para dejarle toda la VRAM
a la síntesis (recomendado con 4 GB o menos):

```bash
make pin-embeddings-cpu   # deja toda la VRAM para la síntesis
# y pon en .env:  KB_EMBEDDINGS_MODELO=bge-m3-cpu
```

`make gpu-up` sigue disponible para forzar el perfil GPU si por algún motivo la detección
automática no encuentra `nvidia-smi`. `make help` lista todo lo demás.

## Dónde vive cada cosa

Todo lo pesado y persistente cae bajo `KB_DATA_DIR` (por defecto `./.data`, junto al repo):
modelos de Ollama, datos de PostgreSQL y el ONNX del reranker. Esto mantiene libre la unidad del
sistema sin depender de configuración del host.

Si tu unidad `C:` va justa, además conviene mover el *data-root* de Docker Desktop a otra unidad
desde **Settings → Resources → Advanced → Disk image location**. Es configuración del host, fuera
de este repo, y por eso queda como recomendación y no como requisito.

### El único hueco del "infraestructura como código"

`docker-compose` no puede fijar la memoria de la VM de WSL2. Eso vive en
`%USERPROFILE%\.wslconfig`. Versionamos la plantilla en [`wslconfig.example`](wslconfig.example),
pero aplicarla es un paso manual:

```powershell
Copy-Item wslconfig.example $env:USERPROFILE\.wslconfig
wsl --shutdown
```

## Stack

| Pieza | Elección |
|---|---|
| Núcleo | Java 21 · Spring Boot 4.1 · Spring Modulith 2.1 |
| Datos | PostgreSQL 18 · pgvector 0.8.6 · Flyway |
| Generación | Ollama con `gemma3:4b` — planner, destilación y síntesis |
| Embeddings | `bge-m3` por Ollama, 1024 dimensiones, multilingüe |
| Reranking | `bge-reranker-v2-m3` sobre ONNX Runtime, en proceso |
| Adaptadores | HTML/JS sin build · protocolo Bot Connector implementado directo |

Spring AI se usa **solo como cliente de Ollama**. Sus abstracciones de RAG y su `VectorStore`
quedan fuera a propósito: un `VectorStore` no sabe expresar cuatro señales independientes
fusionadas por RRF, que es justamente el aporte del diseño.

El bot de Teams implementa el protocolo Bot Connector directo porque **todos los SDK de Bot
Framework están retirados** — el de Java desde noviembre de 2023, el resto con soporte final en
diciembre de 2025. Azure AI Bot Service sigue ejecutando bots V4 sin fin de vida anunciado, así
que hablarle al protocolo es hoy la opción más duradera. Deshabilitado por defecto
(`KB_TEAMS_HABILITADO=false`): el producto se demuestra completo sin él. Para conectarlo a un bot
real, ver [`docs/teams/registro-azure-bot.md`](docs/teams/registro-azure-bot.md); contra el Bot
Framework Emulator alcanza con `KB_TEAMS_HABILITADO=true` y apuntarlo a
`http://localhost:8080/api/messages`, sin credenciales.

## Autenticación

`KB_API_TOKEN` vacío (el default) deja el API sin autenticación. Con un valor, `/api/ask`,
`/api/search` y `/api/ingest/*` exigen `Authorization: Bearer <token>`. Quedan afuera a propósito
`/api/chat` y `/api/preview` (la UI web no tiene login de persona en este MVP) y `/api/messages`
(el Bot Connector valida su propio JWT). Detalle y decisiones pendientes en
[`docs/architecture.md`](docs/architecture.md).

## Documentación

- [`docs/architecture.md`](docs/architecture.md): arquitectura completa, esquema de datos, límites
  entre módulos y equivalencias con el artículo de Cerebras.
- [`docs/adrs/`](docs/adrs): decisiones de diseño que no son obvias leyendo el código.
- [`docs/plans/plan-base-conocimiento.md`](docs/plans/plan-base-conocimiento.md): el plan de
  ejecución completo, fase por fase, con los hallazgos reales de cada una.

## Desarrollo

```bash
./mvnw test      # incluye los gates de arquitectura
./mvnw verify    # build completo
```

Los gates viven en `ArquitecturaTest` desde el primer commit: una frontera que solo existe en un
documento se erosiona en la tercera semana.

## Licencia

MIT — ver [LICENSE](LICENSE).
