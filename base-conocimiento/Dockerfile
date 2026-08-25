# ---------- etapa 1: dependencias (capa cacheada) ----------
FROM eclipse-temurin:25-jdk-noble AS deps
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# Cache mount de BuildKit para el repositorio local de Maven: a diferencia de la
# cache de capas de Docker (que invalida TODA esta etapa con cualquier cambio en
# pom.xml), esta persiste entre builds -- solo se bajan los artefactos nuevos o
# cambiados. No queda en la imagen final (mismo id en las dos etapas para que
# compartan el contenido ya bajado). Necesita que el build cache de BuildKit
# tenga espacio real: si esta lleno de cache vieja de otros proyectos, su GC
# puede desalojar este mount entre builds -- medido en vivo (quedo vacio pese
# a "Usage count: 2" reportado por `docker buildx du`), liberado con
# `docker builder prune`.
#
# aether.*Threads sube de 5 (default de maven-resolver) a 10 la concurrencia de
# descargas/metadata/POMs: medido en vivo que la latencia por request a Maven
# Central (~200-500ms con curl) domina el tiempo total mas que el ancho de
# banda, con cientos de artefactos transitivos en el arbol de Spring Boot/AI/
# Docling -- mas conexiones en paralelo ataca esa causa directamente.
RUN --mount=type=cache,target=/root/.m2,id=maven-repo \
    ./mvnw -B -q \
      -Daether.connector.basic.downstreamThreads=10 \
      -Daether.metadataResolver.threads=10 \
      -Daether.dependencyCollector.bf.threads=10 \
      dependency:go-offline

# ---------- etapa 2: compilacion ----------
FROM deps AS build
COPY src/ src/
# -o (offline): esta etapa parte siempre de la etapa `deps`, que ya garantizo
# (via dependency:go-offline) todo lo que declara pom.xml -- no deberia hacer
# falta red aca. Elimina el resto de las idas y vueltas a Maven Central que
# `go-offline` sola no evitaba.
RUN --mount=type=cache,target=/root/.m2,id=maven-repo \
    ./mvnw -o -B -q clean package -DskipTests

# ---------- etapa 3: extraccion de capas ----------
FROM build AS layers
# El jar queda FUERA del destino: `extract` exige que el directorio destino este
# vacio, y copiarlo dentro lo hace fallar con "already exists and is not empty".
RUN cp /build/target/*.jar /tmp/app.jar \
 && mkdir -p /layers \
 && java -Djarmode=tools -jar /tmp/app.jar extract --layers --destination /layers

# ---------- etapa 4: runtime ----------
# glibc, NO Alpine: las librerias nativas de ONNX Runtime no corren sobre musl.
FROM eclipse-temurin:25-jre-noble AS runtime

# ripgrep alimenta la herramienta search_code; curl es el healthcheck del compose.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ripgrep curl \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 kb
USER kb
WORKDIR /app

# Orden deliberado: de lo que menos cambia a lo que mas.
# Al reconstruir solo se repone la ultima capa.
#
# OJO: Boot 4 (`-Djarmode=tools extract`) deja las capas DIRECTAMENTE bajo el
# destino. El nivel `/layers/app/...` que usaba `layertools` en Boot 3 ya no existe.
COPY --from=layers --chown=kb:kb /layers/dependencies/ ./
COPY --from=layers --chown=kb:kb /layers/snapshot-dependencies/ ./
COPY --from=layers --chown=kb:kb /layers/application/ ./

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
