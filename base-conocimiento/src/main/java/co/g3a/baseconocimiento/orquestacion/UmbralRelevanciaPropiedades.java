package co.g3a.baseconocimiento.orquestacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code kb.orquestacion.umbral-relevancia.*}: la puerta de ADR-0008 antes de sintetizar.
 *
 * <p>{@code piso} y {@code techo} acotan una interpolacion lineal por tamaño de corpus (ver {@link
 * UmbralRelevancia}) — no hay literatura ni dataset propio detras de estos numeros, son la
 * calibracion empirica de ADR-0008 sobre el corpus semilla del taller. Recalibrar cuando haya
 * corpus reales de tamaño intermedio para validarlos.
 *
 * <p>{@code techoConfianza} (default {@code 8.0}) es un umbral aparte, sin escalar por corpus: por
 * encima de el, el mejor fragmento se acepta sin gastar una llamada extra al verificador de
 * grounding (ver {@code VerificadorGrounding}) porque es practicamente una copia literal. Por
 * debajo (y por encima de {@code piso}), el score del cross-encoder no alcanza a decidir solo --
 * ADR-0008 documenta DOS contraejemplos reales donde un score alto no significaba relevancia real
 * ("como usar java 25" puntuando mas que una pregunta relevante, y "como se configura docker"
 * puntuando 3.499 -- muy por debajo de una copia literal (~9.8) pero muy por encima del rango de
 * una pregunta relevante parafraseada normal) -- asi que se verifica con una llamada corta al LLM
 * antes de aceptar, salvo que el score sea abrumador.
 */
@ConfigurationProperties(prefix = "kb.orquestacion.umbral-relevancia")
record UmbralRelevanciaPropiedades(
    boolean habilitado, double piso, double techo, long chunksReferencia, double techoConfianza) {}
