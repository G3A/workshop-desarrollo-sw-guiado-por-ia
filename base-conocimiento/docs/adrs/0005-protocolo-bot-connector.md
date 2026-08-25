# ADR-0005: Protocolo Bot Connector implementado directo, sobre un SDK retirado

## Estado

Aceptado (F5). Verificación en vivo contra el Bot Framework Emulator pendiente — ver
[Hallazgos de F5](../plans/plan-base-conocimiento.md#hallazgos-de-la-implementación-de-f5) en el
plan.

## Contexto

Conectar un bot de Teams en Java pasa históricamente por el Bot Framework SDK. Ese SDK está
efectivamente retirado: la variante de Java murió en noviembre de 2023, y el resto del Bot
Framework SDK (Node, .NET, Python) tuvo soporte final en diciembre de 2025, con el repositorio
archivado en enero de 2026. Su sucesor anunciado, Microsoft 365 Agents SDK, no soporta Java.

La alternativa a "usar el SDK" no es "no soportar Teams": es hablar directo el protocolo HTTP que
el SDK envolvía (Bot Connector API), porque Azure AI Bot Service sigue ejecutando bots V4 sin fin
de vida anunciado sobre ese protocolo, más allá del estado de los SDKs de cliente.

## Decisión

Implementar el protocolo Bot Connector directo, sin ningún SDK de Bot Framework:

- Validación del JWT entrante contra el documento OpenID de `login.botframework.com`
  (`NimbusJwtDecoder.withJwkSetUri`), distinguiendo `ChannelValidation` de `EmulatorValidation`
  (formas de extraer el `appId` distintas según el emisor — ver Hallazgos de F5 en el plan).
- Token saliente por `client_credentials` contra `login.microsoftonline.com/botframework.com`.
- Respuesta al canal vía `POST {serviceUrl}/v3/conversations/{id}/activities/{id}`.
- Modelo `Activity` propio, no el del SDK retirado.

## Consecuencias

- **A favor**: es la opción con vida útil más larga disponible hoy en Java para este canal — el
  protocolo sigue vivo aunque los SDKs de cliente no. Evita depender de una librería archivada que
  no recibirá parches de seguridad.
- **En contra**: hay que portar a mano detalles de validación que el SDK resolvía (los issuers
  válidos del Emulator, la distinción `aud`/`appid`/`azp` según versión de token), verificados
  contra el código fuente público del SDK archivado en vez de contra su documentación viva, que ya
  no se actualiza.
- El webhook `/api/messages` no puede esperar la síntesis (2-3 minutos en CPU): responde 200 de
  inmediato y delega en un hilo virtual — una consecuencia práctica de implementar el protocolo
  crudo en vez de que un SDK maneje ese detalle por su cuenta.
