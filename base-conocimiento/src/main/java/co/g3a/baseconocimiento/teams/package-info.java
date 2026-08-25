/**
 * El adaptador de Teams: protocolo Bot Connector implementado directo, sin
 * SDK — el SDK de Java murió en noviembre de 2023 y el resto del Bot
 * Framework SDK se archivó en enero de 2026, pero Azure Bot Service sigue
 * sirviendo bots V4 sin fin de vida anunciado.
 *
 * <p>Solo conoce {@link co.g3a.baseconocimiento.orquestacion.Consultar} y el
 * vocabulario de {@link co.g3a.baseconocimiento.compartido.Dominio} — la misma
 * regla de ArchUnit que aísla a {@code web} aísla también a este paquete.
 *
 * <p>Valida el JWT entrante contra el JWKS de Bot Framework (canal real o
 * Emulator, según {@code kb.teams.emulator}), responde con indicador de
 * escritura mientras el pipeline de siete etapas corre, y entrega la síntesis
 * como una Adaptive Card con las citas como enlaces.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Teams")
package co.g3a.baseconocimiento.teams;
