package dev.itemparams.config;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class StrictJsonTest {
    @Test void duplicateKeysCannotSilentlyOverwriteSettings() {
        assertThrows(IOException.class, () -> StrictJson.parseObject("{\"recipes\": [], \"recipes\": []}"));
        assertThrows(IOException.class, () -> StrictJson.parseObject("{\"a\": {\"b\": 1, \"b\": 2}}"));
    }
    @Test void decimalLiteralRetainsExactValue() throws Exception {
        assertEquals("0.29", StrictJson.parseObject("{\"value\":0.29}").get("value").getAsBigDecimal().toPlainString());
    }
    @Test void rejectsTrailingCommentsAndOversizedExponents() {
        assertThrows(IOException.class, () -> StrictJson.parseObject("{\"value\":1} trailing"));
        assertThrows(IOException.class, () -> StrictJson.parseObject("{/*comment*/\"value\":1}"));
        assertThrows(IOException.class, () -> StrictJson.parseObject("{\"value\":1e999999999}"));
    }
    @Test void supportsCatalogWithTenThousandRecipes() throws Exception {
        String input = "{\"recipes\":[" + "{},".repeat(9999) + "{}]}";
        assertEquals(10000, StrictJson.parseObject(input).getAsJsonArray("recipes").size());
    }
}
