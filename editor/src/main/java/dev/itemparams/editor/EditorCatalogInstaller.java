package dev.itemparams.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Export first, then switch the complete catalog; keep the previous catalog for recovery. */
public final class EditorCatalogInstaller {
  private EditorCatalogInstaller() {}

  public record Result(Path catalog, Path backup) {}

  public static Result install(EditorProject project, Path game) throws IOException {
    project.validate();
    game = game.toAbsolutePath().normalize();
    if (!Files.isDirectory(game.resolve("mods")))
      throw new IllegalArgumentException("Выберите папку игры с папкой mods");
    Path config = game.resolve("config");
    Files.createDirectories(config);
    Path target = config.resolve("itemparams");
    Path stage = Files.createTempDirectory(config, "itemparams-stage-");
    project.exportCatalog(stage);
    Path backup = null;
    if (Files.exists(target)) {
      backup = config.resolve("itemparams-backup-" + UUID.randomUUID());
      Files.move(target, backup);
    }
    try {
      Files.move(stage, target);
    } catch (IOException failure) {
      if (backup != null) {
        try {
          Files.move(backup, target);
        } catch (IOException restoreFailure) {
          failure.addSuppressed(restoreFailure);
        }
      }
      throw failure;
    }
    return new Result(target, backup);
  }
}
