package io.github.dobdaniel1.mbt;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import org.graphwalker.core.condition.EdgeCoverage;
import org.graphwalker.core.condition.Length;
import org.graphwalker.core.generator.PathGenerator;
import org.graphwalker.core.generator.RandomPath;
import org.graphwalker.core.generator.SingletonRandomGenerator;
import org.graphwalker.java.test.Result;
import org.graphwalker.java.test.TestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/** Walks the transaction lifecycle model against a running switch. */
class TransactionLifecycleTest {

    private static final Path MODEL = Paths.get("io/github/dobdaniel1/mbt/TransactionLifecycle.json");

    private final SwitchClient client = new SwitchClient(System.getProperty("baseUrl", "http://localhost:8000"));

    @Test
    void everyTransitionAtLeastOnce(TestInfo info) throws IOException {
        walk(new RandomPath(new EdgeCoverage(100)), info);
    }

    @Test
    void longRandomWalk(TestInfo info) throws IOException {
        walk(new RandomPath(new Length(Long.getLong("walkLength", 2000))), info);
    }

    private void walk(PathGenerator<?> generator, TestInfo info) throws IOException {
        String configured = System.getProperty("seed", "");
        long seed = configured.isBlank() ? System.nanoTime() : Long.parseLong(configured);
        SingletonRandomGenerator.setSeed(seed);

        TransactionLifecycleModel model = new TransactionLifecycleModel(client, new Random(seed));
        model.setPathGenerator(generator);
        Result result = new TestBuilder().addContext(model, MODEL).execute(true);

        String name = info.getTestMethod().orElseThrow().getName();
        Path report = Paths.get("target", "graphwalker", name + ".json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, result.getResults().toString(2));

        var stats = result.getResults();
        System.out.printf("%s: seed=%d actions=%d transactions=%d edges %d/%d (%d%%) vertices %d/%d%n",
                name, seed, model.stepCount(), model.transactionCount(),
                stats.optInt("totalNumberOfVisitedEdges"), stats.optInt("totalNumberOfEdges"), stats.optInt("edgeCoverage"),
                stats.optInt("totalNumberOfVisitedVertices"), stats.optInt("totalNumberOfVertices"));

        if (result.hasErrors()) {
            fail("""
                    %s
                    Replay with -Dmbt.seed=%d
                    Last steps:
                      %s""".formatted(String.join("\n", result.getErrors()), seed,
                    String.join("\n  ", model.recentSteps())));
        }
    }
}
