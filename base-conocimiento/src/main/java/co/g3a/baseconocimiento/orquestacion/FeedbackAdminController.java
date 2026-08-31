package co.g3a.baseconocimiento.orquestacion;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/admin/feedback}, expuesto dentro de este módulo y no de {@code ingesta} —igual
 * que {@link OrquestacionController} para {@code /api/ask}: es un endpoint operativo dentro de su
 * propio módulo, la regla de ArchUnit que aísla a los adaptadores no le aplica a código
 * intra-módulo. No es el mismo precedente que {@code AdminController} (en {@code ingesta}, que sí
 * concentra el resto de {@code /api/admin/*}): el feedback vive junto a {@link
 * QueryFeedbackRepositorio}, en el módulo dueño del dato, en vez de hacer que {@code ingesta}
 * dependa de algo interno de {@code orquestacion}.
 *
 * <p>Queda cubierto por {@code ApiTokenFilter} igual que el resto de {@code /api/admin/*} — el
 * filtro empareja por prefijo de ruta, no por el módulo que la expone.
 */
@RestController
class FeedbackAdminController {

  /** Sin paginación en este corte: el tope es deliberado, no un olvido — ver el issue #3. */
  private static final int LIMITE_FEEDBACK_RECIENTE = 500;

  private final QueryFeedbackRepositorio feedbackRepo;

  FeedbackAdminController(QueryFeedbackRepositorio feedbackRepo) {
    this.feedbackRepo = feedbackRepo;
  }

  @GetMapping("/api/admin/feedback")
  List<QueryFeedbackRepositorio.FeedbackRegistrado> listar() {
    return feedbackRepo.listarRecientes(LIMITE_FEEDBACK_RECIENTE);
  }
}
