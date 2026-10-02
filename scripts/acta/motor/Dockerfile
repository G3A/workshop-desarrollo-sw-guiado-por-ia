# Imagen del motor de re-ejecucion del acta (#222, fase 2; ADR-0005 punto 4).
#
# La construye reejecutar-acta.mjs, que es el unico paso con red: el contenedor corre con
# --network none. La imagen de partida va fijada por digest y PowerShell por version y sha256;
# los paquetes de Debian salen del espejo del dia, asi que el reporte guarda el id de la imagen
# construida, no solo este archivo.
FROM node@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6

ARG PWSH_VERSION=7.6.6
ARG PWSH_SHA256=ddbc4a2d113bbd46d283cfedcbcd117a70caefd7673f41f2b4e0000badf103bc

RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates curl libicu72 make \
 && curl -fsSL -o /tmp/pwsh.tar.gz \
    "https://github.com/PowerShell/PowerShell/releases/download/v${PWSH_VERSION}/powershell-${PWSH_VERSION}-linux-x64.tar.gz" \
 && echo "${PWSH_SHA256}  /tmp/pwsh.tar.gz" | sha256sum -c - \
 && mkdir -p /opt/pwsh && tar -xzf /tmp/pwsh.tar.gz -C /opt/pwsh \
 && chmod +x /opt/pwsh/pwsh && ln -s /opt/pwsh/pwsh /usr/local/bin/pwsh \
 && rm /tmp/pwsh.tar.gz \
 && apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

# Sin telemetria ni actualizaciones: el contenedor no tiene red, y pwsh esperaria por ella.
ENV POWERSHELL_TELEMETRY_OPTOUT=1 \
    POWERSHELL_UPDATECHECK=Off \
    DOTNET_CLI_TELEMETRY_OPTOUT=1

USER node
WORKDIR /trabajo
