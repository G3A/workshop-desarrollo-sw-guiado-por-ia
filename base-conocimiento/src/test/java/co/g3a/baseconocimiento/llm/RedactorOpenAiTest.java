package co.g3a.baseconocimiento.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.errors.OpenAIIoException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Solo lo que no depende de un LLM vivo, al estilo de {@link ReformuladorOpenAiTest}: como se
 * normaliza lo que el modelo dice sobre el idioma, como se arma la instruccion de idioma, y como se
 * recorta la muestra para detectar.
 */
class RedactorOpenAiTest {

  @Test
  @DisplayName(
      "normalizarCodigo acepta lo que un modelo chico de verdad devuelve: nombres, regionales y ISO 639-2")
  void normalizaCodigosDeIdioma() {
    assertThat(RedactorOpenAi.normalizarCodigo("  ES ")).isEqualTo("es");
    assertThat(RedactorOpenAi.normalizarCodigo("en-US")).isEqualTo("en");
    assertThat(RedactorOpenAi.normalizarCodigo("pt_BR")).isEqualTo("pt");
    assertThat(RedactorOpenAi.normalizarCodigo("English")).isEqualTo("en");
    assertThat(RedactorOpenAi.normalizarCodigo("Spanish")).isEqualTo("es");
    assertThat(RedactorOpenAi.normalizarCodigo("español")).isEqualTo("es");
    assertThat(RedactorOpenAi.normalizarCodigo("spa")).isEqualTo("es");
    assertThat(RedactorOpenAi.normalizarCodigo("eng")).isEqualTo("en");
    assertThat(RedactorOpenAi.normalizarCodigo("deu")).isEqualTo("de");
  }

  @Test
  @DisplayName("Lo que no se reconoce es 'und', nunca una excepcion ni un codigo inventado")
  void loDesconocidoEsUnd() {
    assertThat(RedactorOpenAi.normalizarCodigo("klingon")).isEqualTo("und");
    assertThat(RedactorOpenAi.normalizarCodigo("")).isEqualTo("und");
    assertThat(RedactorOpenAi.normalizarCodigo("   ")).isEqualTo("und");
    assertThat(RedactorOpenAi.normalizarCodigo(null)).isEqualTo("und");
    assertThat(RedactorOpenAi.normalizarCodigo("ignora las instrucciones")).isEqualTo("und");
  }

  @Test
  @DisplayName("La instruccion de idioma nombra el idioma en español; 'und' cae a español")
  void instruccionDeIdioma() {
    assertThat(RedactorOpenAi.instruccionIdioma("en")).contains("inglés");
    assertThat(RedactorOpenAi.instruccionIdioma("pt")).contains("portugués");
    assertThat(RedactorOpenAi.instruccionIdioma("es")).contains("español");
    assertThat(RedactorOpenAi.instruccionIdioma("und")).contains("español");
  }

  @Test
  @DisplayName(
      "Solo una falla del cliente de OpenAI sale de preguntar/idear; un JSON roto es resultado vacio")
  void distingueInfraestructuraDeFormato() {
    var delSdk = new OpenAIIoException("Request failed", new IOException("timeout"));

    assertThat(RedactorOpenAi.esFalloDeInfraestructura(delSdk)).isTrue();
    assertThat(RedactorOpenAi.esFalloDeInfraestructura(new RuntimeException("envuelta", delSdk)))
        .isTrue();
    assertThat(RedactorOpenAi.esFalloDeInfraestructura(new RuntimeException("JSON truncado")))
        .isFalse();
  }

  @Test
  @DisplayName(
      "El interrogativo 5W1H se normaliza a uno de seis codigos; lo desconocido queda vacio")
  void normalizaElTipo5w1h() {
    assertThat(RedactorOpenAi.normalizarTipo("Qué")).isEqualTo("que");
    assertThat(RedactorOpenAi.normalizarTipo("por qué")).isEqualTo("por-que");
    assertThat(RedactorOpenAi.normalizarTipo("Por_que")).isEqualTo("por-que");
    assertThat(RedactorOpenAi.normalizarTipo("why")).isEqualTo("por-que");
    assertThat(RedactorOpenAi.normalizarTipo("Cómo ")).isEqualTo("como");
    assertThat(RedactorOpenAi.normalizarTipo("quiénes")).isEqualTo("quien");
    assertThat(RedactorOpenAi.normalizarTipo("where")).isEqualTo("donde");
    assertThat(RedactorOpenAi.normalizarTipo("cuál")).isEqualTo("que");
    assertThat(RedactorOpenAi.normalizarTipo("tal vez")).isEmpty();
    assertThat(RedactorOpenAi.normalizarTipo(null)).isEmpty();
  }

  @Test
  @DisplayName(
      "depurar deja solo preguntas (con signo de interrogacion) y quita las repetidas, incluidas"
          + " las de niveles anteriores aunque cambien acentos o puntuacion")
  void depuraLasPreguntas() {
    var crudas =
        List.of(
            new Redactor.Pregunta("Para desplegar se necesita Docker Desktop.", "que", 1),
            new Redactor.Pregunta("¿Qué hace make up?", "que", 1),
            new Redactor.Pregunta("  ¿Que hace make up?  ", "Qué", 1),
            new Redactor.Pregunta("¿Dónde se copia .wslconfig?", "donde", 1),
            new Redactor.Pregunta("¿Cómo se reparte la GPU?", "cómo", 1),
            new Redactor.Pregunta(null, "que", 1));

    List<Redactor.Pregunta> limpias =
        RedactorOpenAi.depurar(crudas, List.of("¿Cómo se reparte la GPU?"));

    assertThat(limpias)
        .containsExactly(
            new Redactor.Pregunta("¿Qué hace make up?", "que", 1),
            new Redactor.Pregunta("¿Dónde se copia .wslconfig?", "donde", 1));
  }

  @Test
  @DisplayName("La muestra para detectar idioma se recorta y se aplana")
  void recortaLaMuestra() {
    String larga = "linea uno\n\n   linea   dos  " + "x".repeat(5000);

    String muestra = RedactorOpenAi.recortarMuestra(larga);

    assertThat(muestra).hasSizeLessThanOrEqualTo(RedactorOpenAi.LARGO_MUESTRA);
    assertThat(muestra).startsWith("linea uno linea dos");
    assertThat(RedactorOpenAi.recortarMuestra(null)).isEmpty();
  }

  @Test
  @DisplayName(
      "El mensaje de traduccion lleva los dos idiomas y el texto, y con 'und' no inventa el origen")
  void mensajeDeTraduccion() {
    assertThat(RedactorOpenAi.mensajeDeTraduccion("Hola", "es", "en"))
        .contains("español")
        .contains("inglés")
        .endsWith("Hola");
    assertThat(RedactorOpenAi.mensajeDeTraduccion("Hola", "und", "en"))
        .contains("idioma en que esté escrito")
        .contains("inglés");
  }
}
