package dev.itemparams.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorCatalogInstallerTest {
  @TempDir Path folder;

  @Test
  void installsTheWholeProjectAndKeepsThePreviousCatalogAndOtherConfig() throws Exception {
    Files.createDirectories(folder.resolve("mods"));
    Files.createDirectories(folder.resolve("config/itemparams"));
    Files.writeString(folder.resolve("config/itemparams/old.txt"), "old catalog");
    Files.writeString(folder.resolve("config/other-mod.txt"), "untouched");
    var project = EditorProject.tutorialSample();
    var result = EditorCatalogInstaller.install(project, folder);
    assertEquals("old catalog", Files.readString(result.backup().resolve("old.txt")));
    assertFalse(Files.exists(result.catalog().resolve("old.txt")));
    assertEquals("untouched", Files.readString(folder.resolve("config/other-mod.txt")));
    var imported = EditorProject.importCatalog(result.catalog());
    assertEquals(
        project.preview(project.recipes.get(0)).values(),
        imported.preview(imported.recipes.get(0)).values());
  }

  @Test
  void badProjectDoesNotTouchExistingGameCatalog() throws Exception {
    Files.createDirectories(folder.resolve("mods"));
    Files.createDirectories(folder.resolve("config/itemparams"));
    Files.writeString(folder.resolve("config/itemparams/old.txt"), "old catalog");
    var project = EditorProject.tutorialSample();
    project.items.get(0).fields.get(0).value = "not a number";
    assertThrows(
        IllegalArgumentException.class, () -> EditorCatalogInstaller.install(project, folder));
    assertEquals("old catalog", Files.readString(folder.resolve("config/itemparams/old.txt")));
    try (var paths = Files.list(folder.resolve("config"))) {
      assertEquals(1, paths.count());
    }
  }

  @Test
  void wrongFolderIsRejectedAndFirstInstallNeedsNoBackup() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> EditorCatalogInstaller.install(EditorProject.tutorialSample(), folder));
    assertFalse(Files.exists(folder.resolve("config")));
    Files.createDirectory(folder.resolve("mods"));
    assertNull(EditorCatalogInstaller.install(EditorProject.tutorialSample(), folder).backup());
  }
}
