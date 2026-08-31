package co.g3a.baseconocimiento.ingesta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * El mismo candado contra path traversal que necesitan {@code AdminController} (subir y borrar
 * archivos del vault) y {@code ContenidoVaultController} (el visor de citas) — antes vivía
 * triplicado, cada copia con su propio resolve+normalize+startsWith.
 *
 * <p>Ese chequeo léxico no alcanza solo: es vulnerable a un symlink FÍSICO dentro de {@code raiz}
 * que apunte afuera ({@code Files.isRegularFile} lo sigue por defecto). Por eso, si el candidato ya
 * existe en disco, se exige además que su real path (symlinks resueltos) siga adentro de la real
 * path de la raíz — un candidato que todavía no existe (p. ej. destino de una carga nueva) no tiene
 * symlink que resolver, así que ese paso se salta.
 */
final class RutasVault {

  private RutasVault() {}

  static Optional<Path> resolverDentroDe(Path raiz, String relativo) {
    Path raizNormalizada = raiz.normalize();
    Path candidato = raizNormalizada.resolve(relativo).normalize();
    if (!candidato.startsWith(raizNormalizada)) {
      return Optional.empty();
    }
    if (Files.exists(candidato)) {
      try {
        if (!candidato.toRealPath().startsWith(raizNormalizada.toRealPath())) {
          return Optional.empty();
        }
      } catch (IOException e) {
        return Optional.empty();
      }
    }
    return Optional.of(candidato);
  }
}
