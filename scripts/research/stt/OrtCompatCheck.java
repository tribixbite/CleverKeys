// ORT 1.21.1 compatibility probe for the STT research spike (2026-10-28).
//
// Loads each candidate STT graph with the SAME onnxruntime version and the SAME bionic arm64
// native library the CleverKeys APK ships (onnxruntime-android:1.21.1; natives unpacked by the
// `extractOrtNative` Gradle task into build/ort-native/arm64-v8a) and runs one inference with
// dummy inputs, on the CPU EP and, where it loads, the XNNPACK EP. This answers "does the model
// run on the app's ORT" without building an APK. Nothing here is app code.
//
//   JAR=~/.gradle/caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime/1.21.1/*/onnxruntime-1.21.1.jar
//   javac -cp "$JAR" -d $STT/ortcheck scripts/research/stt/OrtCompatCheck.java
//   java -Donnxruntime.native.path=build/ort-native/arm64-v8a -cp "$JAR:$STT/ortcheck" OrtCompatCheck $STT/models
//
// Output: one line per graph: provider, load ms, run ms, output shape or the exception message.
import ai.onnxruntime.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OrtCompatCheck {
  private static OrtEnvironment env;

  public static void main(String[] args) throws Exception {
    Path models = Paths.get(args[0]);
    env = OrtEnvironment.getEnvironment();
    System.out.println("onnxruntime " + env.getVersion() + " providers=" + OrtEnvironment.getAvailableProviders());
    for (boolean xnnpack : new boolean[] {false, true}) {
      String tag = xnnpack ? "XNNPACK+CPU" : "CPU";
      Path w = models.resolve("whistle-onnx/onnx");
      Path w8 = models.resolve("whistle-onnx/onnx-int8");
      Path moon = models.resolve("moonshine-tiny-streaming-en");
      Path zip = firstDir(models.resolve("sherpa"));
      whistle(tag, xnnpack, w, "fp32");
      whistle(tag, xnnpack, w8, "int8");
      moonshine(tag, xnnpack, moon, "", ".ort");
      moonshine(tag, xnnpack, moon, "masterx_", "_int8.onnx");
      if (zip != null) zipformer(tag, xnnpack, zip);
    }
  }

  private static Path firstDir(Path p) throws Exception {
    if (!Files.isDirectory(p)) return null;
    try (var s = Files.list(p)) {
      return s.filter(Files::isDirectory).findFirst().orElse(null);
    }
  }

  private static OrtSession.SessionOptions opts(boolean xnnpack) throws OrtException {
    OrtSession.SessionOptions so = new OrtSession.SessionOptions();
    so.setIntraOpNumThreads(4);
    so.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
    if (xnnpack) so.addXnnpack(Collections.singletonMap("intra_op_num_threads", "4"));
    return so;
  }

  private static OnnxTensor f32(long[] shape) throws OrtException {
    long n = 1;
    for (long d : shape) n *= d;
    FloatBuffer b = FloatBuffer.allocate((int) n);
    for (int i = 0; i < n; i++) b.put(i, (float) (Math.sin(i * 0.37) * 0.5));
    return OnnxTensor.createTensor(env, b, shape);
  }

  private static OnnxTensor i64(long[] shape, long value) throws OrtException {
    long n = 1;
    for (long d : shape) n *= d;
    LongBuffer b = LongBuffer.allocate((int) n);
    for (int i = 0; i < n; i++) b.put(i, value);
    return OnnxTensor.createTensor(env, b, shape);
  }

  private static String shape(OnnxValue v) throws OrtException {
    return java.util.Arrays.toString(((OnnxTensor) v).getInfo().getShape());
  }

  private static void report(String tag, String name, long loadMs, long runMs, String detail) {
    System.out.printf("%-12s %-34s load %6d ms  run %6d ms  %s%n", tag, name, loadMs, runMs, detail);
  }

  private static void whistle(String tag, boolean xnnpack, Path dir, String label) {
    if (!Files.exists(dir.resolve("encoder.onnx"))) return;
    long t0 = System.nanoTime();
    try (OrtSession enc = env.createSession(dir.resolve("encoder.onnx").toString(), opts(xnnpack));
         OrtSession dec = env.createSession(dir.resolve("decoder.onnx").toString(), opts(xnnpack))) {
      long t1 = System.nanoTime();
      Map<String, OnnxTensor> in = new LinkedHashMap<>();
      in.put("mel", f32(new long[] {500, 80}));
      try (OrtSession.Result r = enc.run(in)) {
        OnnxTensor ck = (OnnxTensor) r.get(0), cv = (OnnxTensor) r.get(1);
        long s = ck.getInfo().getShape()[2];
        Map<String, OnnxTensor> d = new LinkedHashMap<>();
        d.put("token", i64(new long[] {1}, 2));
        d.put("pos", i64(new long[] {1}, 0));
        d.put("efeat", f32(new long[] {2, 4, 512}));
        d.put("prev", f32(new long[] {8, 2, 608}));
        d.put("past_k", f32(new long[] {8, 2, 0, 48}));
        d.put("past_v", f32(new long[] {8, 2, 0, 64}));
        d.put("cross_k", ck);
        d.put("cross_v", cv);
        try (OrtSession.Result r2 = dec.run(d)) {
          long t2 = System.nanoTime();
          report(tag, "whistle " + label + " enc+dec step", (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000,
              "cross_k " + shape(ck) + " logits " + shape(r2.get(0)) + " present_k " + shape(r2.get(2)));
        }
      }
    } catch (Exception e) {
      report(tag, "whistle " + label, (System.nanoTime() - t0) / 1_000_000, 0, "FAILED: " + e.getMessage());
    }
  }

  private static void moonshine(String tag, boolean xnnpack, Path dir, String prefix, String ext) {
    String front = prefix.isEmpty() ? "frontend.ort" : prefix + "frontend.onnx";
    if (!Files.exists(dir.resolve(front))) front = "masterx_frontend.onnx"; // official 26_08_21 split frontend
    String label = "moonshine " + (prefix.isEmpty() ? "official .ort" : "onnx int8");
    long t0 = System.nanoTime();
    try (OrtSession fr = env.createSession(dir.resolve(front).toString(), opts(xnnpack));
         OrtSession en = env.createSession(dir.resolve(prefix + "encoder" + ext).toString(), opts(xnnpack));
         OrtSession ad = env.createSession(dir.resolve(prefix + "adapter" + ext).toString(), opts(xnnpack));
         OrtSession ck = env.createSession(dir.resolve(prefix + "cross_kv" + ext).toString(), opts(xnnpack));
         OrtSession de = env.createSession(dir.resolve(prefix + "decoder_kv" + ext).toString(), opts(xnnpack))) {
      long t1 = System.nanoTime();
      Map<String, OnnxTensor> f = new LinkedHashMap<>();
      f.put("audio_chunk", f32(new long[] {1, 16000}));
      f.put("sample_buffer", f32(new long[] {1, 79}));
      f.put("sample_len", i64(new long[] {1}, 0));
      f.put("conv1_buffer", f32(new long[] {1, 320, 4}));
      f.put("conv2_buffer", f32(new long[] {1, 640, 4}));
      f.put("frame_count", i64(new long[] {1}, 0));
      try (OrtSession.Result rf = fr.run(f)) {
        OnnxTensor feats = (OnnxTensor) rf.get(0);
        try (OrtSession.Result re = en.run(Collections.singletonMap("features", feats))) {
          Map<String, OnnxTensor> a = new LinkedHashMap<>();
          a.put("encoded", (OnnxTensor) re.get(0));
          a.put("pos_offset", i64(new long[] {1}, 0));
          try (OrtSession.Result ra = ad.run(a);
               OrtSession.Result rc = ck.run(Collections.singletonMap("memory", (OnnxTensor) ra.get(0)))) {
            Map<String, OnnxTensor> d = new LinkedHashMap<>();
            d.put("token", i64(new long[] {1, 1}, 1));
            d.put("k_self", f32(new long[] {6, 1, 8, 0, 40}));
            d.put("v_self", f32(new long[] {6, 1, 8, 0, 40}));
            d.put("out_k_cross", (OnnxTensor) rc.get(0));
            d.put("out_v_cross", (OnnxTensor) rc.get(1));
            try (OrtSession.Result rd = de.run(d)) {
              long t2 = System.nanoTime();
              report(tag, label + " 5 graphs", (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000,
                  "features " + shape(feats) + " memory " + shape(ra.get(0)) + " logits " + shape(rd.get(0)));
            }
          }
        }
      }
    } catch (Exception e) {
      report(tag, label, (System.nanoTime() - t0) / 1_000_000, 0, "FAILED: " + e.getMessage());
    }
  }

  private static void zipformer(String tag, boolean xnnpack, Path dir) {
    long t0 = System.nanoTime();
    try (OrtSession en = env.createSession(dir.resolve("encoder-epoch-99-avg-1.int8.onnx").toString(), opts(xnnpack))) {
      long t1 = System.nanoTime();
      Map<String, OnnxTensor> in = new LinkedHashMap<>();
      for (Map.Entry<String, NodeInfo> e : en.getInputInfo().entrySet()) {
        TensorInfo ti = (TensorInfo) e.getValue().getInfo();
        long[] shp = ti.getShape().clone();
        for (int i = 0; i < shp.length; i++) if (shp[i] < 0) shp[i] = 1;
        in.put(e.getKey(), ti.type == OnnxJavaType.INT64 ? i64(shp, 0) : f32(shp));
      }
      try (OrtSession.Result r = en.run(in)) {
        long t2 = System.nanoTime();
        report(tag, "zipformer 20M int8 encoder step", (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000,
            "encoder_out " + shape(r.get(0)) + " (" + in.size() + " inputs incl. 35 states)");
      }
    } catch (Exception e) {
      report(tag, "zipformer", (System.nanoTime() - t0) / 1_000_000, 0, "FAILED: " + e.getMessage());
    }
  }
}
