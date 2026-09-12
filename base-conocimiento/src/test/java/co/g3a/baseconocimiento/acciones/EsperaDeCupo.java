package co.g3a.baseconocimiento.acciones;

import static org.awaitility.Awaitility.await;

import java.time.Duration;

/**
 * Espera acotada a que el cupo vuelva despues de que un Flux termina.
 *
 * <p><b>Por que hace falta.</b> El cupo se devuelve en un {@code doFinally}, y Reactor ejecuta ese
 * callback <b>despues</b> de propagar la senal terminal. Su propio javadoc lo dice nombrando el
 * caso: <i>"the signal is propagated downstream before the callback is executed"</i> y <i>"the Mono
 * will complete before the doFinally callback is executed, so its effect might not be visible
 * immediately after a block()"</i>.
 *
 * <p>Por eso una asercion inmediata sobre el cupo justo despues de un {@code block()} o un {@code
 * blockLast()} es una <b>carrera</b>, no una verificacion. Las que igual pasaban lo hacian porque
 * entre el {@code block()} y la asercion corrian otras lineas que le daban tiempo al callback: eso
 * es suerte con forma de test, y se notaba cada tanto en CI.
 *
 * <p><b>No cambiar esto por un {@code Thread.sleep}.</b> Un sleep fijo es lento cuando acierta y
 * sigue siendo una carrera cuando no. Esta espera falla rapido y con mensaje propio si el cupo no
 * vuelve.
 *
 * <p><b>Tampoco se arregla del lado de produccion</b> cambiando {@code doFinally} por {@code
 * doOnTerminate}: ese no cubre la cancelacion, y devolver el permiso <i>pase lo que pase</i> es
 * justamente el contrato de la ruta con cupo. El codigo de produccion esta bien; lo que estaba mal
 * era como se lo miraba.
 *
 * <p><b>Cuando NO usar esto.</b> Solo para el post-estado de una suscripcion que termino. Una
 * asercion que verifica que <i>nadie se suscribio</i>, o que prepara el cupo antes de suscribirse,
 * tiene que seguir siendo inmediata: convertirla en espera taparia justo la regresion que busca.
 */
final class EsperaDeCupo {

  /** Corto a proposito: si el callback no corrio en este plazo, hay algo roto, no lento. */
  private static final Duration LIMITE = Duration.ofSeconds(2);

  private EsperaDeCupo() {}

  /**
   * Espera a que el cupo este libre y lo toma, igual que haria {@code intentarTomar()} directo.
   *
   * @param porQue que se esta verificando, para que el fallo diga cual de las esperas se vencio
   */
  static void vuelveYSeToma(CupoDeAcciones cupo, String porQue) {
    await().alias(porQue).atMost(LIMITE).until(cupo::intentarTomar);
  }
}
