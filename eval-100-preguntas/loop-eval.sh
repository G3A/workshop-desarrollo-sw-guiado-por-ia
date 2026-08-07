#!/bin/bash
cd "$(dirname "$0")"
while true; do
  pend=$(node -e "
    const total = require('./preguntas.json').length;
    const done = require('fs').existsSync('./resultados-brutos.json')
      ? JSON.parse(require('fs').readFileSync('./resultados-brutos.json','utf8')).length
      : 0;
    console.log(total - done);
  ")
  echo "$(date '+%Y-%m-%d %H:%M:%S') pendientes: $pend" >> loop-eval.log
  if [ "$pend" -le 0 ]; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') LISTO, 0 pendientes" >> loop-eval.log
    break
  fi
  # Si el ciclo anterior murio de un kill externo (no un exit limpio de node),
  # Playwright no alcanza a cerrar sus hijos y quedan chrome-headless-shell.exe
  # huerfanos consumiendo RAM -- medido en vivo, 8 procesos huerfanos bajaron
  # la memoria libre del sistema de 6.4GB a 5.0GB en un solo ciclo. Limpiarlos
  # antes de cada relanzamiento evita que la presion de memoria empeore con
  # cada ciclo de crash-relanzamiento.
  taskkill //F //IM chrome-headless-shell.exe > /dev/null 2>&1
  npm run eval >> eval-run.ministral-verificacion-sesion16.log 2>&1
  sleep 5
done
