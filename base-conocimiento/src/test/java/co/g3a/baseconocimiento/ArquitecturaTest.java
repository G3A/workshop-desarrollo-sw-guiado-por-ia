package co.g3a.baseconocimiento;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Los gates arquitectonicos del proyecto.
 *
 * <p>Existen desde el primer commit a proposito: una frontera que solo vive en un documento se
 * erosiona en la tercera semana. Estas pruebas la ponen en el build.
 */
class ArquitecturaTest {

  private static final String RAIZ = "co.g3a.baseconocimiento";

  private static JavaClasses clases;

  @BeforeAll
  static void importar() {
    clases =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(RAIZ);
  }

  @Test
  @DisplayName("Los adaptadores son piel: no conocen el retrieval, solo la fachada")
  void losAdaptadoresNoConocenElNucleo() {
    // Este es EL contrato del proyecto. La UI web, el bot de Teams y el filtro de
    // seguridad deben poder reemplazarse sin tocar una linea de recuperacion, y para
    // eso no pueden saber que existen cuatro senales, un RRF, un cross-encoder ni un LLM.
    //
    // allowEmptyShould en false: los tres paquetes ya existen, asi que la regla debe
    // morder de verdad, no nacer verde por vacia.
    noClasses()
        .that()
        .resideInAnyPackage(RAIZ + ".web..", RAIZ + ".teams..", RAIZ + ".seguridad..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            RAIZ + ".recuperacion..", RAIZ + ".ingesta..", RAIZ + ".modelos..", RAIZ + ".llm..")
        .because("los adaptadores solo pueden cruzar la fachada Consultar")
        .allowEmptyShould(false)
        .check(clases);
  }

  @Test
  @DisplayName("El nucleo no depende de sus adaptadores")
  void elNucleoNoConoceALosAdaptadores() {
    // La direccion contraria importa igual: si `orquestacion` importara algo de
    // `web`, el nucleo quedaria atado al transporte y el bot de Teams heredaria
    // conceptos HTTP que no le corresponden.
    noClasses()
        .that()
        .resideInAnyPackage(
            RAIZ + ".orquestacion..",
            RAIZ + ".recuperacion..",
            RAIZ + ".ingesta..",
            RAIZ + ".modelos..",
            RAIZ + ".llm..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(RAIZ + ".web..", RAIZ + ".teams..", RAIZ + ".seguridad..")
        .because("el nucleo no sabe por que puerta entro la pregunta")
        .allowEmptyShould(false)
        .check(clases);
  }

  @Test
  @DisplayName("Seguridad no se mezcla con los otros adaptadores")
  void seguridadNoSeMezclaConLosOtrosAdaptadores() {
    // `seguridad` es el noveno modulo del sistema y durante un tiempo no aparecio en
    // ninguna regla. Las dos de arriba ya lo cubren frente al nucleo; falta la
    // frontera lateral: el filtro de token no sabe si la peticion venia de la UI web
    // o del bot, y ni la UI ni el bot lo instancian a mano para saltearselo.
    noClasses()
        .that()
        .resideInAnyPackage(RAIZ + ".web..", RAIZ + ".teams..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(RAIZ + ".seguridad..")
        .because("seguridad se configura sola por filtro, no se llama desde un adaptador")
        .allowEmptyShould(false)
        .check(clases);

    noClasses()
        .that()
        .resideInAPackage(RAIZ + ".seguridad..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(RAIZ + ".web..", RAIZ + ".teams..")
        .because("seguridad no sabe por que puerta entro la peticion")
        .allowEmptyShould(false)
        .check(clases);
  }

  @Test
  @DisplayName("Compartido no depende de nadie")
  void compartidoEsHoja() {
    noClasses()
        .that()
        .resideInAPackage(RAIZ + ".compartido..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            RAIZ + ".orquestacion..",
            RAIZ + ".recuperacion..",
            RAIZ + ".ingesta..",
            RAIZ + ".modelos..",
            RAIZ + ".llm..",
            RAIZ + ".web..",
            RAIZ + ".teams..",
            RAIZ + ".seguridad..")
        .because("es solo vocabulario: si depende de algo, deja de ser compartido")
        .allowEmptyShould(true)
        .check(clases);
  }

  @Test
  @DisplayName("Las fronteras entre modulos Modulith son validas")
  void modulosValidos() {
    ApplicationModules.of(BaseConocimientoApplication.class).verify();
  }
}
