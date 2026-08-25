
# Cómo ejecutarla

cd eval-100-preguntas
npm install
npx playwright install chromium   # solo la primera vez

npm run eval       # corre las 100 preguntas contra http://localhost:8080 (ya está arriba)
npm run reporte    # cruza con query_log y genera reporte.html

Abre eval-100-preguntas/reporte.html en el navegador.

Importante sobre el tiempo: en mi prueba de humo, una pregunta tardó 217s (Bonsai a ~1-3.5 tok/s). Con 100 preguntas puede tomar varias horas corriendo en serie. Para probar rápido primero:

EVAL_LIMITE=5 npm run eval
npm run reporte

Si se corta a la mitad, npm run eval de nuevo retoma donde quedó (salta las que ya tienen resultado). Variables de entorno opcionales: EVAL_BASE_URL, EVAL_PROYECTO, EVAL_TIMEOUT_MS, EVAL_DB_CONTAINER/EVAL_DB_USER/EVAL_DB_NAME/EVAL_API_CONTAINER (todas con default acorde a tu .env actual).

Una observación de la prueba de humo: la segunda pregunta cayó en "se perdió la conexión con el servidor" a los 25s (probablemente el modelo aún liberando GPU de la pregunta anterior) — el script ya la detecta y la excluye de la calificación en vez de contarla como incorrecta, mostrándola como "⚠️ error" en la tabla.