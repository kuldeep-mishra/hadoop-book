package crunch;

import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.apache.crunch.DoFn;
import org.apache.crunch.Emitter;
import org.apache.crunch.FilterFn;
import org.apache.crunch.MapFn;
import org.apache.crunch.PCollection;
import org.apache.crunch.PObject;
import org.apache.crunch.PTable;
import org.apache.crunch.Pair;
import org.apache.crunch.Pipeline;
import org.apache.crunch.PipelineExecution;
import org.apache.crunch.PipelineResult;
import org.apache.crunch.Target;
import org.apache.crunch.fn.IdentityFn;
import org.apache.crunch.impl.mr.MRPipeline;
import org.apache.crunch.io.To;
import org.apache.crunch.test.TemporaryPath;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import static org.apache.crunch.types.writable.Writables.ints;
import static org.apache.crunch.types.writable.Writables.longs;
import static org.apache.crunch.types.writable.Writables.strings;
import static org.apache.crunch.types.writable.Writables.tableOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class RunSemanticsTest implements Serializable {

  @Rule
  public transient TemporaryPath tmpDir = new TemporaryPath();

  @Rule
  public transient TestName name = new TestName();

  @Test
  public void testCallingRunTwiceOnlyRunsOneJob() throws IOException {
    String inputPath = tmpDir.copyResourceFileName("set1.txt");

    Pipeline pipeline = new MRPipeline(RunSemanticsTest.class);
    PCollection<String> lines = pipeline.readTextFile(inputPath);
    PTable<String,String> table = lines.by(new MapFn<String, String>() {
      @Override
      public String map(String s) {
        return s;
      }
    }, strings());
    pipeline.writeTextFile(table, tmpDir.getFileName("out"));

    System.out.println("About to call run() for first time");
    pipeline.run();
    System.out.println("About to call run() for second time");
    // the MR job is not run for a second time since there are no new outputs
    PipelineResult result = pipeline.run();
    assertEquals(0, result.getStageResults().size());
    System.out.println("About to call done()");
    pipeline.done();
  }

  @Test
  public void testMaterializeCallsRunImplicitly() throws IOException {
    List<String> expectedContent = Lists.newArrayList("b", "c", "a", "e");
    String inputPath = tmpDir.copyResourceFileName("set1.txt");

    Pipeline pipeline = new MRPipeline(RunSemanticsTest.class);
    PCollection<String> lines = pipeline.readTextFile(inputPath);
    PCollection<String> lower = lines.parallelDo(new ToLowerFn(), strings());

    System.out.println("About to materialize...");
    // the MR job is not run until we get an iterator
    Iterable<String> materialized = lower.materialize();
    System.out.println("About to iterate...");
    for (String s : materialized) { // pipeline is run
      System.out.println(s);
    }
    assertEquals(expectedContent, Lists.newArrayList(materialized));

    System.out.println("About to call done()");
    PipelineResult result = pipeline.done();
    assertEquals(0, result.getStageResults().size());
  }

  @Test
  public void testPObjectCallsRunImplicitly() throws IOException {
    List<String> expectedContent = Lists.newArrayList("b", "c", "a", "e");
    String inputPath = tmpDir.copyResourceFileName("set1.txt");

    Pipeline pipeline = new MRPipeline(RunSemanticsTest.class);
    PCollection<String> lines = pipeline.readTextFile(inputPath);
    PCollection<String> lower = lines.parallelDo(new ToLowerFn(), strings());

    System.out.println("About to get as a PObject of Collection...");
    // the MR job is not run until we call getValue on the PObject
    PObject<Collection<String>> po = lower.asCollection();
    System.out.println("About to call getValue...");
    for (String s : po.getValue()) { // pipeline is run
      System.out.println(s);
    }
    // the MR job is run now (even though we haven't explicitly called run()
    assertEquals(expectedContent, po.getValue());

    System.out.println("About to call done()");
    PipelineResult result = pipeline.done();
    assertEquals(0, result.getStageResults().size());
  }

  @Test
  @Ignore
  public void testIterativeAlgorithm() throws IOException {
    String inputPath = tmpDir.copyResourceFileName("set1.txt");
    Pipeline pipeline = new MRPipeline(RunSemanticsTest.class);
    PCollection<String> lines = pipeline.readTextFile(inputPath);
    int targetLength = 1;
    PObject<Long> length = lines.length();
    while (length.getValue() > targetLength) {
      System.out.println("Length: " + length.getValue());
      System.out.println("filter");
      lines = lines.filter(new FilterFn<String>() {
        @Override
        public boolean accept(String input) {
          return Math.random() < 0.5;
        }
      });
      length = lines.length();
    }
    System.out.println("Final length: " + length.getValue());
    pipeline.done();
  }

  @Test
  public void testInterruptPipeline() throws Exception {
    String inputPath = tmpDir.copyResourceFileName("set1.txt");
    String outputPath = tmpDir.getFileName("out");
    final Pipeline pipeline = new MRPipeline(RunSemanticsTest.class);
    PCollection<String> lines = pipeline.readTextFile(inputPath);
    PTable<String, Long> counts = lines.count();
    PTable<Long, String> inverseCounts = counts.parallelDo(
        new InversePairFn<String, Long>(), tableOf(longs(), strings()));
    PTable<Long, Integer> hist = inverseCounts
        .groupByKey()
        .mapValues(new CountValuesFn<String>(), ints());
    hist.write(To.textFile(outputPath), Target.WriteMode.OVERWRITE);

    Thread thread = new Thread(new Runnable() {
      @Override
      public void run() {
        PipelineResult result = pipeline.run();
        assertFalse(result.succeeded());
      }
    });
    thread.start();
    Thread.sleep(100);
    thread.interrupt();
    thread.join();
    // note that MR jobs are *not* cancelled
  }

  @Test
  public void testKillPipeline() throws Exception {
    String inputPath = tmpDir.copyResourceFileName("set1.txt");
    String outputPath = tmpDir.getFileName("out");
    Pipeline pipeline = new MRPipeline(RunSemanticsTest.class);
    PCollection<String> lines = pipeline.readTextFile(inputPath);
    PTable<String, Long> counts = lines.count();
    PTable<Long, String> inverseCounts = counts.parallelDo(
        new InversePairFn<String, Long>(), tableOf(longs(), strings()));
    PTable<Long, Integer> hist = inverseCounts
        .groupByKey()
        .mapValues(new CountValuesFn<String>(), ints());
    hist.write(To.textFile(outputPath), Target.WriteMode.OVERWRITE);

    PipelineExecution execution = pipeline.runAsync();
    execution.kill();
    execution.waitUntilDone();
    assertEquals(PipelineExecution.Status.KILLED, execution.getStatus());
  }

  @Test
  public void testCancelPipeline() throws Exception {
    String inputPath = tmpDir.copyResourceFileName("set1.txt");
    String outputPath = tmpDir.getFileName("out");
    Pipeline pipeline = new MRPipeline(RunSemanticsTest.class);
    PCollection<String> lines = pipeline.readTextFile(inputPath);
    PTable<String, Long> counts = lines.count();
    PTable<Long, String> inverseCounts = counts.parallelDo(
        new InversePairFn<String, Long>(), tableOf(longs(), strings()));
    PTable<Long, Integer> hist = inverseCounts
        .groupByKey()
        .mapValues(new CountValuesFn<String>(), ints());
    hist.write(To.textFile(outputPath), Target.WriteMode.OVERWRITE);

    PipelineExecution execution = pipeline.runAsync();
    execution.cancel(true);
    execution.waitUntilDone();
    assertEquals(PipelineExecution.Status.KILLED, execution.getStatus());
  }

  @Test
  public void testNewTarget() throws Exception {
    String inputPath = tmpDir.copyResourceFileName("set1.txt");

    Pipeline pipeline = new MRPipeline(RunSemanticsTest.class);
    PCollection<String> lines = pipeline.readTextFile(inputPath);
    PTable<String, Long> counts = lines.count();
    PTable<Long, String> inverseCounts = counts.parallelDo(
        new InversePairFn<String, Long>(), tableOf(longs(), strings()));
    PTable<Long, Integer> hist = inverseCounts
        .groupByKey()
        .mapValues(new CountValuesFn<String>(), ints());
    pipeline.writeTextFile(hist, tmpDir.getFileName("hist1"));

    PipelineResult run1 = pipeline.run();
    assertEquals(2, run1.getStageResults().size());

    pipeline.writeTextFile(hist, tmpDir.getFileName("hist2"));

    PipelineResult run2 = pipeline.run();
    assertEquals(1, run2.getStageResults().size());

    pipeline.done();
  }

  @Test
  public void testComplexPipeline() throws Exception {
    String inputPath = tmpDir.copyResourceFileName("set1.txt");

    Pipeline pipeline = new MRPipeline(RunSemanticsTest.class);
    PCollection<String> lines = pipeline.readTextFile(inputPath);
    PTable<String, String> table = lines.by(new MapFn<String, String>() {
      @Override
      public String map(String s) {
        return s;
      }
    }, strings());
    PCollection<String> iso = table.groupByKey().parallelDo("iso",
        new DoFn<Pair<String, Iterable<String>>, String>() {
      @Override
      public void process(Pair<String, Iterable<String>> input,
          Emitter<String> emitter) {
        for (String s : input.second()) {
          emitter.emit(s);
        }
      }
    }, strings());
    PTable<String, String> table2 = iso.by(new MapFn<String, String>() {
      @Override
      public String map(String s) {
        return s;
      }
    }, strings());
    PCollection<String> iso2 = table2.groupByKey().parallelDo("iso",
        new DoFn<Pair<String, Iterable<String>>, String>() {
          @Override
          public void process(Pair<String, Iterable<String>> input,
              Emitter<String> emitter) {
            for (String s : input.second()) {
              emitter.emit(s);
            }
          }
        }, strings());
    pipeline.writeTextFile(iso2, tmpDir.getFileName("out"));

    PipelineExecution execution = pipeline.runAsync();
    execution.waitUntilDone();
    FileUtils.write(new File(name.getMethodName() + ".dot"), execution.getPlanDotFile(),
        "UTF-8");
    assertEquals(2, execution.get().getStageResults().size());

    pipeline.done();
  }

  @Test
  public void testParallelDoFusion() throws Exception {
    String inputPath = tmpDir.copyResourceFileName("set1.txt");

    Pipeline pipeline = new MRPipeline(RunSemanticsTest.class);
    PCollection<String> lines = pipeline.readTextFile(inputPath);
    PCollection<String> t1 = lines.parallelDo("t1", IdentityFn.<String>getInstance(),
        strings());
    PCollection<String> t2 = t1.parallelDo("t2", IdentityFn.<String>getInstance(),
        strings());
    pipeline.writeTextFile(t2, tmpDir.getFileName("out"));

    PipelineExecution execution = pipeline.runAsync();
    execution.waitUntilDone();
    FileUtils.write(new File(name.getMethodName() + ".dot"), execution.getPlanDotFile(),
        "UTF-8");
    assertEquals(1, execution.get().getStageResults().size());

    pipeline.done();
  }

  @Test
  public void testSiblingFusion() throws Exception {
    String inputPath = tmpDir.copyResourceFileName("set1.txt");

    Pipeline pipeline = new MRPipeline(RunSemanticsTest.class);
    PCollection<String> lines = pipeline.readTextFile(inputPath);
    PCollection<String> t1 = lines.parallelDo("t1", IdentityFn.<String>getInstance(),
        strings());
    PCollection<String> t2 = lines.parallelDo("t2", IdentityFn.<String>getInstance(),
        strings());
    pipeline.writeTextFile(t1, tmpDir.getFileName("out1"));
    pipeline.writeTextFile(t2, tmpDir.getFileName("out2"));

    PipelineExecution execution = pipeline.runAsync();
    execution.waitUntilDone();
    FileUtils.write(new File(name.getMethodName() + ".dot"), execution.getPlanDotFile(),
        "UTF-8");
    assertEquals(1, execution.get().getStageResults().size());

    pipeline.done();
  }

}
