package dev.itemparams.formula;

import java.util.List;

/** Maps EvalEx's generated variable names back to the author's original field references. */
final class SourcePositions {
  record Reference(int generatedStart, int generatedEnd, int originalStart, int originalEnd) {}

  private final List<Reference> references;

  SourcePositions(List<Reference> references) {
    this.references = List.copyOf(references);
  }

  int original(int generated) {
    int delta = 0;
    for (Reference reference : references) {
      if (generated < reference.generatedStart()) break;
      if (generated < reference.generatedEnd()) return reference.originalStart();
      delta = reference.originalEnd() - reference.generatedEnd();
    }
    return Math.max(0, generated + delta);
  }
}
