package io.github.jacek4yang.stegsolver.bench;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Runs the benchmarks, which live in the test sources so that the normal build stays untouched.
 *
 * <pre>
 *   mvn -Pbench test-compile exec:java
 *   mvn -Pbench test-compile exec:java -Dbench.include=TransformBenchmark -Dbench.size=1024
 * </pre>
 */
public final class BenchmarkMain {

    private BenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        String include = System.getProperty("bench.include", ".*Benchmark.*");
        String size = System.getProperty("bench.size", "");
        String resultFile = System.getProperty("bench.resultFile", "target/jmh-result.json");

        var builder = new OptionsBuilder()
                .include(include)
                .forks(Integer.getInteger("bench.forks", 1))
                .warmupIterations(Integer.getInteger("bench.warmup", 2))
                .measurementIterations(Integer.getInteger("bench.iterations", 5))
                .warmupTime(TimeValue.seconds(Integer.getInteger("bench.warmupSeconds", 1)))
                .measurementTime(TimeValue.seconds(Integer.getInteger("bench.measurementSeconds", 1)))
                .resultFormat(ResultFormatType.JSON)
                .result(resultFile)
                .shouldFailOnError(true)
                .jvmArgsAppend("-Xmx2g");
        if (!size.isBlank()) {
            builder.param("size", size);
        }
        Options options = builder.build();
        System.out.println("Running JMH benchmarks matching '" + include + "' (results: " + resultFile + ")");
        new Runner(options).run();
    }
}
