package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChunkerEncabezadosTest {

  @Test
  void unaSolaSeccionCuandoNoHayEncabezados() {
    var secciones = ChunkerEncabezados.trocear("solo texto plano, sin encabezados");

    assertThat(secciones).hasSize(1);
    assertThat(secciones.get(0).rutaEncabezados()).isEmpty();
  }

  @Test
  void construyeLaRutaDeEncabezadosAnidados() {
    var texto =
        """
                # Guia de despliegue

                Introduccion general.

                ## Prerequisitos

                Necesitas Docker.

                ### Version minima

                Docker 24 o superior.

                ## Pasos

                Ejecuta el compose.
                """;

    var secciones = ChunkerEncabezados.trocear(texto);

    assertThat(secciones)
        .extracting(ChunkerEncabezados.Seccion::rutaEncabezados)
        .containsExactly(
            java.util.List.of("Guia de despliegue"),
            java.util.List.of("Guia de despliegue", "Prerequisitos"),
            java.util.List.of("Guia de despliegue", "Prerequisitos", "Version minima"),
            java.util.List.of("Guia de despliegue", "Pasos"));
  }

  @Test
  void unEncabezadoDeIgualNivelCierraElAnterior() {
    // "Pasos" es h2, igual que "Prerequisitos": debe reemplazarlo en la pila,
    // no anidarse dentro de el.
    var texto =
        """
                # Raiz
                ## Prerequisitos
                cuerpo uno
                ## Pasos
                cuerpo dos
                """;

    var secciones = ChunkerEncabezados.trocear(texto);

    assertThat(secciones)
        .extracting(ChunkerEncabezados.Seccion::rutaEncabezados)
        .containsExactly(
            java.util.List.of("Raiz", "Prerequisitos"), java.util.List.of("Raiz", "Pasos"));
  }

  @Test
  void hermanosSiguenSiendoHermanosAunqueElDocumentoSalteNiveles() {
    // Así sale la especificación de Java (jls25.pdf) por OpenDataLoader: el
    // capítulo es h1 y sus secciones h4, sin h2 ni h3 de por medio. "4.2" es
    // hermano de "4.1", no hijo.
    var texto =
        """
                # Types, Values, and Variables
                #### 4.1 The Kinds of Types
                cuerpo uno
                #### 4.2 Primitive Types
                cuerpo dos
                ##### 4.2.3 Floating-Point Types
                cuerpo tres
                #### 4.3 Reference Types
                cuerpo cuatro
                """;

    var secciones = ChunkerEncabezados.trocear(texto);

    assertThat(secciones)
        .extracting(ChunkerEncabezados.Seccion::rutaEncabezados)
        .containsExactly(
            java.util.List.of("Types, Values, and Variables", "4.1 The Kinds of Types"),
            java.util.List.of("Types, Values, and Variables", "4.2 Primitive Types"),
            java.util.List.of(
                "Types, Values, and Variables",
                "4.2 Primitive Types",
                "4.2.3 Floating-Point Types"),
            java.util.List.of("Types, Values, and Variables", "4.3 Reference Types"));
  }

  @Test
  void unEncabezadoEnumeradoSeAnidaBajoElDeSuMismoNivel() {
    // Así sale la NIST SP 800-53 por OpenDataLoader: el control y sus mejoras al mismo nivel.
    // "(13) ..." es parte de AC-2, no un control nuevo; "AC-3" sí cierra a AC-2 y sus mejoras.
    var texto =
        """
                #### 3.1 ACCESS CONTROL
                ###### AC-2 ACCOUNT MANAGEMENT
                cuerpo del control
                ###### (12) ACCOUNT MANAGEMENT | ACCOUNT MONITORING
                cuerpo de la mejora 12
                ###### (13) ACCOUNT MANAGEMENT | DISABLE ACCOUNTS
                cuerpo de la mejora 13
                ###### AC-3 ACCESS ENFORCEMENT
                cuerpo de AC-3
                """;

    var secciones = ChunkerEncabezados.trocear(texto);

    assertThat(secciones)
        .extracting(s -> String.join(" > ", s.rutaEncabezados()))
        .containsExactly(
            "3.1 ACCESS CONTROL > AC-2 ACCOUNT MANAGEMENT",
            "3.1 ACCESS CONTROL > AC-2 ACCOUNT MANAGEMENT > (12) ACCOUNT MANAGEMENT | ACCOUNT"
                + " MONITORING",
            "3.1 ACCESS CONTROL > AC-2 ACCOUNT MANAGEMENT > (13) ACCOUNT MANAGEMENT | DISABLE"
                + " ACCOUNTS",
            "3.1 ACCESS CONTROL > AC-3 ACCESS ENFORCEMENT");
  }

  @Test
  void unaSeccionDemasiadoGrandeSeSubdivide() {
    String parrafo = "x".repeat(100);
    String cuerpoGrande = (parrafo + "\n\n").repeat(60); // > 4000 caracteres
    var texto = "# Titulo\n\n" + cuerpoGrande;

    var secciones = ChunkerEncabezados.trocear(texto);

    assertThat(secciones).hasSizeGreaterThan(1);
    assertThat(secciones)
        .allSatisfy(s -> assertThat(s.rutaEncabezados()).containsExactly("Titulo"));
  }

  @Test
  void ignoraLineasQueParecenEncabezadosSinEspacio() {
    // "#sin-espacio" no es un encabezado valido de Markdown.
    var secciones = ChunkerEncabezados.trocear("#sin-espacio\ncontenido normal");

    assertThat(secciones).hasSize(1);
    assertThat(secciones.get(0).rutaEncabezados()).isEmpty();
    assertThat(secciones.get(0).cuerpo()).contains("#sin-espacio");
  }
}
