package io.github.dobdaniel1.mbt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/** The published page must show every transition and must not build from an unworded model. */
class ModelPageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void pageShowsEveryTransition() throws IOException {
        String html = Files.readString(ModelPage.write(Path.of("target/site/index.html")));
        JsonNode edges = JSON.readTree(ModelPage.MODEL.toFile()).path("models").get(0).path("edges");
        assertFalse(html.contains("__MODEL_DATA__"));
        assertAll(StreamSupport.stream(edges.spliterator(), false).map(e -> () ->
                assertTrue(html.contains("\"id\":\"" + e.path("id").asText() + "\""), e.path("id").asText() + " missing")));
    }

    @Test
    void refusesAConditionWithoutWording() throws IOException {
        JsonNode model = JSON.readTree(ModelPage.MODEL.toFile());
        ((ObjectNode) model.path("models").get(0).path("edges").get(1)).put("guard", "hostA == 'maybe'");
        var e = assertThrows(IllegalStateException.class, () -> render(model, text()));
        assertTrue(e.getMessage().contains("hostA == 'maybe'"), e.getMessage());
    }

    @Test
    void refusesAStateWithoutWording() throws IOException {
        JsonNode text = text();
        ((ObjectNode) text.path("states")).remove("v_Settled");
        var e = assertThrows(IllegalStateException.class, () -> render(JSON.readTree(ModelPage.MODEL.toFile()), text));
        assertTrue(e.getMessage().contains("v_Settled"), e.getMessage());
    }

    private static JsonNode text() throws IOException {
        return JSON.readTree(ModelPage.TEXT.toFile());
    }

    private static String render(JsonNode model, JsonNode text) throws IOException {
        return ModelPage.render(model, text, Files.readString(ModelPage.TEMPLATE));
    }
}
