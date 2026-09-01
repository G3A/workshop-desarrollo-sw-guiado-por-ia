# Registro del bot en Azure (documentado, no ejecutado)

Esta guía describe los pasos para conectar el adaptador de Teams (F5) a un bot real de
Azure Bot Service. Ninguno de estos pasos se ejecutó como parte de F5: el producto se
demuestra completo sin ellos (ver Supuestos del plan), y requieren una suscripción de
Azure y un admin del tenant que apruebe permisos de Graph si además se habilita el
conector de Teams por Graph (F6).

## 1. Registro de la aplicación en Microsoft Entra ID

1. Entra ID → Registros de aplicaciones → Nuevo registro.
2. Tipo de cuenta: **Solo cuentas en este directorio organizacional** (single-tenant) es
   suficiente para un bot interno.
3. Certificados y secretos → nuevo secreto de cliente. Guarda el valor: es
   `KB_TEAMS_APP_PASSWORD` y no se puede volver a leer después de creado.
4. Copia el **Application (client) ID**: es `KB_TEAMS_APP_ID`.

## 2. Recurso Azure Bot

1. Azure Portal → crear recurso **Azure Bot**.
2. Pricing tier: **F0** (gratis) alcanza para desarrollo y demos — no hay costo mientras
   no se supere su cuota de mensajes.
3. Tipo: **Multi Tenant** o **Single Tenant**, usando el App ID del paso 1 (opción
   "Use existing app registration").
4. **Messaging endpoint**: `https://<host-publico>/api/messages` — tiene que ser
   accesible desde internet (Azure Bot Service llama a este endpoint, no al revés).
   Para probar en local sin exponer nada, usa el Bot Framework Emulator apuntando a
   `http://localhost:8080/api/messages` en vez de este paso.

## 3. Canal de Teams

1. En el recurso Azure Bot → Canales → agregar el canal **Microsoft Teams**.
2. Acepta los términos y guarda.

## 4. Variables de entorno del proyecto

En `.env`:

```
KB_TEAMS_HABILITADO=true
KB_TEAMS_EMULATOR=false
KB_TEAMS_APP_ID=<Application (client) ID del paso 1>
KB_TEAMS_APP_PASSWORD=<secreto del paso 1>
```

`KB_TEAMS_EMULATOR=false` es lo que hace que `ValidadorTokenBotFramework` valide contra
el emisor de canal (`https://api.botframework.com`) en vez del emisor del Emulator.

## 5. Paquete de la app de Teams

`manifest.json` en este mismo directorio ya trae la forma completa (schema 1.27). Antes
de subirlo:

1. Reemplaza el `id` y el `botId` (deben ser el mismo GUID: el Application ID del paso 1).
2. Agrega `outline.png` (32×32, transparente) y `color.png` (192×192) junto al manifiesto.
3. Comprime los tres archivos en un `.zip` sin subcarpetas.
4. Sube el `.zip` en Teams: Apps → Administrar tus apps → Subir una app personalizada.

## Verificación sin ninguno de estos pasos

El criterio de salida de F5 no depende de este registro: contra el **Bot Framework
Emulator**, con `KB_TEAMS_EMULATOR=true` y sin `KB_TEAMS_APP_ID` configurado, el
validador deja pasar la Activity sin credenciales (ver
`ValidadorTokenBotFramework.credencialesConfiguradas()`), y la conversación completa
funciona apuntando el Emulator a `http://localhost:8080/api/messages`.
