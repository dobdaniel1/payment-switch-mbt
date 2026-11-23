package io.github.dobdaniel1.mbt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the GitHub Pages view of the model: the model file plus the plain-English wording in
 * site/model-text.json. Refuses to build when any state, action, condition or update has no wording.
 */
final class ModelPage {

    static final Path MODEL = Path.of("src/test/resources/io/github/dobdaniel1/mbt/TransactionLifecycle.json");
    static final Path TEXT = Path.of("site/model-text.json");
    static final Path TEMPLATE = Path.of("site/index.template.html");
    static final String MODEL_URL = "https://github.com/dobdaniel1/payment-switch-mbt/blob/main/" + MODEL;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ModelPage() {}

    public static void main(String[] args) throws IOException {
        write(Path.of(args.length > 0 ? args[0] : "target/site/index.html"));
    }

    static Path write(Path out) throws IOException {
        String html = render(JSON.readTree(MODEL.toFile()), JSON.readTree(TEXT.toFile()), Files.readString(TEMPLATE));
        Files.createDirectories(out.getParent());
        return Files.writeString(out, html);
    }

    static String render(JsonNode modelFile, JsonNode text, String template) throws IOException {
        JsonNode model = modelFile.path("models").get(0);
        List<String> missing = new ArrayList<>();

        Map<String, String> vertexNames = new HashMap<>();
        model.path("vertices").forEach(v -> vertexNames.put(v.path("id").asText(), v.path("name").asText()));
        Set<String> inModel = new TreeSet<>(vertexNames.values());
        Set<String> worded = new TreeSet<>();
        text.path("states").fieldNames().forEachRemaining(worded::add);
        if (!inModel.equals(worded)) {
            missing.add("states (model has " + inModel + ", wording has " + worded + ")");
        }

        ArrayNode edges = JSON.createArrayNode();
        for (JsonNode e : model.path("edges")) {
            String name = e.path("name").asText();
            String guard = e.path("guard").asText("");
            String from = vertexNames.get(e.path("sourceVertexId").asText());
            String to = vertexNames.get(e.path("targetVertexId").asText());
            if (!text.path("actions").has(name)) {
                missing.add("action " + name);
            }
            if (!text.path("guards").has(guard)) {
                missing.add("condition \"" + guard + "\"");
            }
            if (name.equals("e_Reverse") && to.equals(from) && !text.path("reverseInPlace").has(from)) {
                missing.add("repeated reversal in " + from);
            }
            List<String> effects = new ArrayList<>();
            for (JsonNode a : e.path("actions")) {
                JsonNode wording = text.path("effects").get(a.asText());
                if (wording == null) {
                    missing.add("model update \"" + a.asText() + "\"");
                } else {
                    effects.add(wording.asText());
                }
            }
            ObjectNode edge = edges.addObject();
            edge.put("id", e.path("id").asText());
            edge.put("name", name);
            edge.put("from", from);
            edge.put("to", to);
            edge.put("guard", text.path("guards").path(guard).asText());
            edge.put("effect", String.join("; ", effects));
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No wording in " + TEXT + " for: " + String.join(", ", missing));
        }

        ObjectNode data = JSON.createObjectNode();
        data.set("states", text.path("states"));
        data.set("actions", text.path("actions"));
        data.set("reverseInPlace", text.path("reverseInPlace"));
        data.put("modelUrl", MODEL_URL);
        data.set("edges", edges);
        // Keeps any "</" in the wording from closing the script tag early.
        String json = JSON.writeValueAsString(data).replace("</", "<\\/");
        return template.replace("__MODEL_DATA__", json);
    }
}
