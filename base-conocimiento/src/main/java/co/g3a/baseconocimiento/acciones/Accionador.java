package co.g3a.baseconocimiento.acciones;

import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * La implementacion de {@link Acciones}: delega en los tres servicios del modulo, igual que {@code
 * Consultador} delega en {@code Orquestador}. Los adaptadores dependen de la interfaz, nunca de
 * esto ni de los servicios — asi no ven el presupuesto, el cupo ni el {@code Redactor}.
 */
@Component
class Accionador implements Acciones {

  private final AccionesSobreDocumentos accionesSobreDocumentos;
  private final TraductorDeDocumentos traductorDeDocumentos;
  private final TraductorDeTexto traductorDeTexto;
  private final AccionesPropiedades propiedades;

  Accionador(
      AccionesSobreDocumentos accionesSobreDocumentos,
      TraductorDeDocumentos traductorDeDocumentos,
      TraductorDeTexto traductorDeTexto,
      AccionesPropiedades propiedades) {
    this.accionesSobreDocumentos = accionesSobreDocumentos;
    this.traductorDeDocumentos = traductorDeDocumentos;
    this.traductorDeTexto = traductorDeTexto;
    this.propiedades = propiedades;
  }

  @Override
  public Limites limites() {
    return new Limites(propiedades.maxDocumentos(), propiedades.maxCaracteresTexto());
  }

  @Override
  public ResultadoDeAccion ejecutar(
      Tipo tipo, List<Long> documentos, ProyectoId proyecto, String idioma) {
    return accionesSobreDocumentos.ejecutar(tipo, documentos, proyecto, idioma);
  }

  @Override
  public TraduccionDeDocumentos traducirDocumentos(
      List<Long> documentos, ProyectoId proyecto, String origen, String destino) {
    return traductorDeDocumentos.traducir(documentos, proyecto, origen, destino);
  }

  @Override
  public TextoTraducido traducirTexto(String texto, String origen, String destino) {
    return traductorDeTexto.traducir(texto, origen, destino);
  }
}
