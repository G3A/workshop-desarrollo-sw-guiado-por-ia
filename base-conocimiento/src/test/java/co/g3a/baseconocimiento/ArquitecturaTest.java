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
 * <p>Existen desde el primer commit a proposito: una frontera que solo vive en un
 * documento se erosiona en la tercera semana. Estas pruebas la ponen en el build.
 */
class ArquitecturaTest {

    private static final String RAIZ = "co.g3a.baseconocimiento";

    private static JavaClasses clases;

    @BeforeAll
    static void importar() {
        clases = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(RAIZ);
    }

    @Test
    @DisplayName("Los adaptadores son piel: no conocen el retrieval, solo la fachada")
    void losAdaptadoresNoConocenElNucleo() {
        // Este es EL contrato del proyecto. La UI web y el bot de Teams deben poder
        // reemplazarse sin tocar una linea de recuperacion, y para eso no pueden
        // saber que existen cuatro senales, un RRF, un cross-encoder ni un LLM.
        //
        // allowEmptyShould queda en true porque `web` y `teams` llegan en F4 y F5:
        // la regla nace verde y empieza a morder cuando esos paquetes aparecen.
        noClasses()
                .that().resideInAnyPackage(RAIZ + ".web..", RAIZ + ".teams..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        RAIZ + ".recuperacion..",
                        RAIZ + ".ingesta..",
                        RAIZ + ".modelos..",
                        RAIZ + ".llm..")
                .because("los adaptadores solo pueden cruzar la fachada Consultar")
                .allowEmptyShould(true)
                .check(clases);
    }

    @Test
    @DisplayName("El nucleo no depende de sus adaptadores")
    void elNucleoNoConoceALosAdaptadores() {
        // La direccion contraria importa igual: si `orquestacion` importara algo de
        // `web`, el nucleo quedaria atado al transporte y el bot de Teams heredaria
        // conceptos HTTP que no le corresponden.
        noClasses()
                .that().resideInAnyPackage(
                        RAIZ + ".orquestacion..",
                        RAIZ + ".recuperacion..",
                        RAIZ + ".ingesta..",
                        RAIZ + ".modelos..",
                        RAIZ + ".llm..")
                .should().dependOnClassesThat().resideInAnyPackage(RAIZ + ".web..", RAIZ + ".teams..")
                .because("el nucleo no sabe por que puerta entro la pregunta")
                .allowEmptyShould(true)
                .check(clases);
    }

    @Test
    @DisplayName("Compartido no depende de nadie")
    void compartidoEsHoja() {
        noClasses()
                .that().resideInAPackage(RAIZ + ".compartido..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        RAIZ + ".orquestacion..",
                        RAIZ + ".recuperacion..",
                        RAIZ + ".ingesta..",
                        RAIZ + ".modelos..",
                        RAIZ + ".llm..",
                        RAIZ + ".web..",
                        RAIZ + ".teams..")
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
