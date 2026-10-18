package dev.itemparams.config;

import com.mojang.brigadier.StringReader;
import net.minecraft.command.argument.IdentifierArgumentType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentifierCommandArgumentTest {
    @Test void namespacedFieldsAndRecipesConsumeTheWholeArgument() throws Exception {
        for (String text : new String[]{"example:mass", "my.mod:mechanisms/drill"}) {
            StringReader reader = new StringReader(text + " 1000.29");
            assertEquals(text, IdentifierArgumentType.identifier().parse(reader).toString());
            assertEquals(text.length(), reader.getCursor());
        }
    }
}
