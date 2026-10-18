# Сборка, тесты и выпуск

Требуются JDK 17 и Gradle Wrapper из репозитория. Первый полный запуск получает Gradle, Minecraft и зависимости из официальных Maven/Mojang-источников. Машинные пути разработчика и локальные кэши не входят в репозиторий.

```sh
./gradlew build
python3 tools/validate-demo-catalog.py
python3 tools/verify-artifacts.py
```

Windows: замените `./gradlew` на `.\gradlew.bat`. Сумма SHA-256 Gradle 8.10.2 закреплена в wrapper properties. Максимальное число Gradle workers — 2, параллельная сборка модулей отключена. Для кэша на другом диске задайте `GRADLE_USER_HOME`; временную папку задают стандартные `TEMP`/`TMP`.

## Отдельные проверки

```sh
./gradlew -Pstandalone=true :core:test :core:verifyCoreBoundary
./gradlew -Pstandalone=true :editor:test :editor:editorJar
./gradlew :fabric:test :fabric:coreRuntimeTest :fabric:verifyCoreBoundary
```

`standalone=true` вообще не подключает Fabric-проект: числовые тесты работают без классов Minecraft/Fabric. Core boundary также проверяет скомпилированный JAR на ссылки на платформу и GUI. Fabric runtime-проверки запускаются штатной JUnit-интеграцией Fabric Loader, чтобы применялись преобразования Minecraft. `coreRuntimeTest` исключает demo и редактор.

Регрессионные тесты сохраняют существующие проверки чисел, сложных корней, нескольких входов, совпадающих ID, ошибок, переносов, JSON/NBT, старого формата редактора и игрового допуска. Разделение модулей не является новой математической реализацией.

Тяжёлые `catalogBenchmark`, `coreBenchmark` и `multiInputBenchmark` запускаются явно. `:fabric:catalogBenchmark` может использовать до 2 ГиБ heap и не входит в `build`. Результаты лабораторного теста создания объектов нельзя объявлять гарантией TPS или вместимости произвольного мира.

## Артефакты

Корневой `build/libs/` содержит:

- `item-parameters-core-<version>.jar` и sources — обычная Java-библиотека.
- `item-parameters-fabric-<version>.jar` и sources — устанавливаемая основа с вложенным core и математическими библиотеками.
- `item-parameters-demo-<version>.jar` и sources — необязательный игровой пример.
- `item-parameters-editor-<version>.jar` и sources — самостоятельный редактор.

Публикации Maven настроены для core и Fabric. `publishToMavenLocal` не публикует ничего в интернет. Для удалённого Maven/GitHub Packages сначала требуется настроить назначение и учётные данные; в репозитории их нет.

## GitHub

Workflow `.github/workflows/build.yml` проверяет независимую Java-сборку и полную Fabric-сборку на JDK 17. Это конфигурация будущего CI: успешный локальный запуск не означает, что GitHub Actions уже выполнялся.

При выпуске обновите версию, проверьте `build` и установку согласованных Fabric/demo JAR в Minecraft 1.20.1. Основу без demo и editor также нужно проверять. Создание GitHub-репозитория, push и выпуск релиза выполняются отдельно от локального коммита.

Пример workflow основан на [GitHub: Java with Gradle](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-gradle) и [Gradle setup-gradle](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md).
