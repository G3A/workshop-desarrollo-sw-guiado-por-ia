#!/bin/sh
# Diagnostica, en orden, por que el sistema responde "No encontre informacion
# suficientemente relevante en la base de conocimiento" a todo.
#
# Ese mensaje (Orquestador.MENSAJE_SIN_INFORMACION) sale de DOS puntos que no
# significan lo mismo, y ninguno deja rastro de error en ningun log:
#
#   Orquestador.java:369  la recuperacion puntuo INSUFICIENTE -- el LLM nunca
#                         vio el contenido. Problema de BUSQUEDA.
#   Orquestador.java:388  puntuo AMBIGUO y VerificadorGrounding dictamino que el
#                         contexto no responde -- el LLM SI lo vio y lo rechazo.
#                         Problema de JUICIO.
#
# A eso se suman dos causas mas prosaicas que se ven identicas desde la interfaz:
# que no haya nada ingerido, y que si lo haya pero sin embeber todavia. Los
# cuatro pasos de abajo las separan, del mas barato al mas caro, y cada uno
# imprime que significa su resultado.
#
# Uso (Git Bash en Windows, o Linux/macOS), con el stack arriba:
#   sh scripts/verificar-respuesta-vacia.sh
#   sh scripts/verificar-respuesta-vacia.sh "otra pregunta"
#
# En PowerShell, invocarlo con el sh de Git:
#   & 'C:\Program Files\Git\bin\sh.exe' scripts/verificar-respuesta-vacia.sh
set -u

PREGUNTA="${1:-cuales son los tipos primitivos en Java}"
PUERTO="${KB_PORT:-8080}"
# MSYS reescribe cualquier argumento con pinta de ruta absoluta (/tmp/x, /api/y)
# a una ruta de Windows ANTES de que el binario la vea. Sin esto, `docker exec
# ... -f /tmp/x.sql` falla con un "No such file or directory" que apunta a
# C:/Users/.../Temp, y las URLs de curl salen mutiladas.
export MSYS_NO_PATHCONV=1

titulo() {
  echo ""
  echo "== $1"
  echo "-------------------------------------------------------------------"
}

titulo "0. Los contenedores estan arriba?"
if ! docker ps --format '{{.Names}}' | grep -q '^kb-db$'; then
  echo "kb-db no esta corriendo. Levanta el stack con 'make up' y repite."
  exit 1
fi
docker ps --filter 'name=kb-' --format '  {{.Names}}\t{{.Status}}'

titulo "1. El arreglo de recuperacion esta EN EL CONTENEDOR?"
echo "Esperado: TOPE=20 y EXPANDIR_VECINOS=false. Si sale 3, o no sale nada, el"
echo "contenedor es viejo aunque el repo este al dia: 'make down && make up'."
# El resultado se captura en una variable en vez de encadenar `| sed || echo`:
# en una tuberia el estado de salida es el del ULTIMO comando (sed, siempre 0),
# asi que el `||` nunca disparaba y un kb-api caido se veia como una seccion en
# blanco -- justo el silencio que este script existe para evitar.
ENV_API=$(docker exec kb-api env 2>/dev/null \
  | grep -E 'KB_RECUPERACION_TOPE_POR_DOCUMENTO|KB_EXPANDIR_VECINOS|KB_UMBRAL_RELEVANCIA_TECHO_CONFIANZA')
if [ -z "$ENV_API" ]; then
  echo "  SIN DATO: kb-api no responde o no tiene esas variables."
  echo "  Si el contenedor esta arriba, es un contenedor viejo: make down && make up"
  echo "  Si no lo esta, revisa: docker logs kb-api"
else
  echo "$ENV_API" | sed 's/^/  /'
fi

titulo "2. Hay contenido, y esta embebido?"
echo "chunks sin embeber => no es recuperacion, es el worker (TrabajadorEmbebido)."
echo "chunks = 0 => no hay corpus: 'make seed' (ver vault-init en el Makefile)."
docker exec kb-db psql -U kb -d baseconocimiento -q -c "
SELECT d.id,
       left(d.title, 34)                AS documento,
       count(c.id)                      AS chunks,
       count(c.embedding)               AS embebidos,
       count(c.id) - count(c.embedding) AS pendientes
FROM documents d LEFT JOIN chunks c ON c.document_id = d.id
GROUP BY d.id, d.title ORDER BY chunks DESC;" 2>&1 | sed 's/^/  /'

titulo "3. RECUPERACION aislada del LLM  --  /api/search"
echo "Este es el paso que decide. /api/search devuelve los candidatos crudos:"
echo "sin planificador, sin verificador de grounding y sin sintesis."
echo ""
echo "  Vuelven fragmentos del documento que responde  -> la recuperacion esta"
echo "  bien y el problema es de JUICIO (paso 4, y mira techo-confianza)."
echo "  Vuelve vacio -> el problema es de BUSQUEDA: sigue en la recuperacion."
echo ""
echo "  Pregunta: $PREGUNTA"
# 90s, no 30: /api/search embebe la consulta y corre el cross-encoder, ambos en
# CPU en el perfil por defecto. Con los modelos frios la primera llamada tarda
# bastante mas que las siguientes, y un timeout corto se confunde con un fallo.
# Se separa cuerpo de codigo HTTP: un 000 (no hubo respuesta) y un 500 significan
# cosas distintas, y "sin respuesta" a secas mandaba a revisar la api cuando el
# problema real era, por ejemplo, un modelo de embeddings ausente.
CUERPO=$(curl -s -m 90 -X POST "http://localhost:$PUERTO/api/search" \
  -H 'Content-Type: application/json' \
  -d "{\"q\":\"$PREGUNTA\"}" -w '\n%{http_code}' 2>/dev/null)
CODIGO=$(echo "$CUERPO" | tail -1)
CUERPO=$(echo "$CUERPO" | sed '$d')

case "$CODIGO" in
  200)
    FRAGMENTOS=$(echo "$CUERPO" | grep -o '"rangoFinal"' | grep -c .)
    echo "  fragmentos devueltos: $FRAGMENTOS"
    if [ "$FRAGMENTOS" -eq 0 ]; then
      echo "  -> RECUPERACION VACIA. El problema es de BUSQUEDA, no de juicio."
    else
      echo "$CUERPO" | tr ',' '\n' | grep -oE '"(uri|titulo|title)":"[^"]*"' \
        | sort | uniq -c | sed 's/^/    /'
      echo "  -> Llego material. Si aun asi la interfaz dice que no encontro nada,"
      echo "     el problema es de JUICIO: mira el paso 4 y techo-confianza."
    fi
    ;;
  000)
    echo "  SIN RESPUESTA (timeout o conexion rechazada)."
    echo "  Ojo: que /actuator/health responda NO descarta esto -- /api/search"
    echo "  embebe la consulta, y si el modelo de embeddings no esta, se cuelga."
    echo "  Comprueba cuales faltan:"
    curl -s -m 15 "http://localhost:$PUERTO/actuator/health" 2>/dev/null \
      | tr ',' '\n' | grep -iE 'modelosFaltantes|accion' | sed 's/^/    /'
    echo "    (si aparece algun modelo faltante: make pull-models)"
    ;;
  *)
    echo "  HTTP $CODIGO"
    echo "$CUERPO" | head -5 | sed 's/^/    /'
    ;;
esac

titulo "4. Que decidio el sistema en tus consultas reales"
echo "candidatos > 0 con citas = 0 => el material llego y algo lo rechazo:"
echo "es Orquestador:388, VerificadorGrounding. El perfil base usa"
echo "techo-confianza 8.0; Bonsai lo bajo a 6.0 justo por esto (un match de"
echo "rerank 7.9 caia en AMBIGUO y se rechazaba). Prueba:"
echo "  KB_UMBRAL_RELEVANCIA_TECHO_CONFIANZA=6.0 make up"
docker exec kb-db psql -U kb -d baseconocimiento -q -c "
SELECT id,
       left(question, 38)             AS pregunta,
       jsonb_array_length(candidates) AS candidatos,
       jsonb_array_length(citations)  AS citas,
       left(answer, 34)               AS respuesta,
       latency_ms
FROM query_log ORDER BY created_at DESC LIMIT 5;" 2>&1 | sed 's/^/  /'

echo ""
echo "==================================================================="
echo "Si el paso 3 devuelve el documento correcto y el 4 muestra candidatos"
echo "con 0 citas, NO toques la recuperacion: el problema es el umbral."
echo "==================================================================="
