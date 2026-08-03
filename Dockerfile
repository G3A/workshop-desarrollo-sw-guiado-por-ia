# ---------- etapa 1: dependencias (capa cacheada) ----------
FROM eclipse-temurin:25-jdk-noble AS deps
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# ---------- etapa 2: compilacion ----------
FROM deps AS build
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

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
