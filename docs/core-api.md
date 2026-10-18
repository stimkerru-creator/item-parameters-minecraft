# Числовое ядро без Minecraft

Зависимость после публикации в выбранный Maven-репозиторий:

```groovy
implementation 'dev.itemparams:item-parameters-core:1.6.2-SNAPSHOT'
```

Для разработки в этом репозитории: `implementation project(':core')`. Публикация в локальный Maven: `./gradlew -Pstandalone=true :core:publishToMavenLocal`. Публичный удалённый Maven-репозиторий пока не настроен.

## Схема и значения

```java
var schema = ParameterSchema.builder()
    .addRaw("factory:mass", 100000) // 1000.00: raw хранит сотые
    .addRaw("factory:purity", 8000) // 80.00
    .build();
var ore = schema.defaults();
ore.set("factory:mass", new BigDecimal("1000.25"));
int raw = ore.getRaw("factory:mass"); // 100025
BigDecimal mass = ore.get("factory:mass"); // 1000.25
```

Импорты: `dev.itemparams.core.*`, `java.math.BigDecimal`. Индекс и `FieldHandle` позволяют один раз разрешить ID и дальше обращаться к числу без повторного поиска. `copyRaw()` возвращает отдельный массив. Изменения через `setRaw` и `replaceRaw` проверяют диапазон.

Схема неизменяема. Чтобы добавить поля, создайте новую схему и восстановите значения **по ID**, например `ParameterValues.fromRawMap(newSchema, oldValues.toRawMap())`. Старые поля сохраняются; новые получают defaults. Не интерпретируйте сохранённый массив по позициям другой схемы.

## Несколько независимых входов

```java
var oreSchema = ParameterSchema.builder()
    .addRaw("factory:mass", 100000).addRaw("factory:purity", 8000).build();
var additiveSchema = ParameterSchema.builder().addRaw("factory:mass", 20000).build();
var outputSchema = ParameterSchema.builder().addRaw("factory:mass", 0).build();
var inputs = InputLayout.of(Map.of("ore", oreSchema, "additive", additiveSchema));
var recipe = ParameterRecipe.builder("factory:mix", inputs, outputSchema)
    .assign("factory:mass", "${ore::factory:mass} * ${ore::factory:purity} / 100 + ${additive::factory:mass}")
    .build();
var values = inputs.bind(Map.of("ore", oreSchema.defaults(), "additive", additiveSchema.defaults()));
var output = recipe.execute(values);
// output.get("factory:mass") == 1000.00
```

Дополнительные импорты: `dev.itemparams.formula.InputLayout`, `dev.itemparams.production.ParameterRecipe`, `java.util.Map`. Одинаковый ID `factory:mass` у двух входов не объединяет значения. Порядок записей Map значения не меняет; роли сопоставляются по имени. Передавайте ту же схему, которой объявлена роль.

Для повторных вызовов одного владельца: `var workspace = recipe.newWorkspace(); recipe.execute(values, workspace)`. Он запоминает одну успешную комбинацию и возвращает независимый результат. Это кэш числового API, а не фильтр игрового слота: новый корректный набор параметров может быть вычислен. Закрепление игровой линии делает механизм, см. [интеграцию](integration.md).

Порядок подготовки выхода: defaults/`base` → перенос общих полей → явные transfers → assignments. Все transfers и формулы читают исходные входы; результат одной строки не становится входом следующей. Возвращаемые значения не ссылаются на массивы входов.

## Потоки и ошибки

`ParameterSchema` и скомпилированный рецепт неизменяемы. `ParameterValues`, рабочая сессия и Workspace принадлежат одному владельцу/потоку. Для параллельных расчётов создавайте отдельные значения и workspace.

Синтаксис и неизвестные поля проверяются при компиляции. Область определения, итоговый int32 и настроенные диапазоны проверяются до публикации результата. Ошибка не меняет исходные значения и предыдущий успешный результат workspace. Точные математические ограничения — в [math.md](math.md).
