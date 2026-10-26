package org.motoai.baseline;

import android.app.Instrumentation;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Test-only driver for the unmodified upstream APK. Never part of the product. */
public final class BaselineInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : args;
        start();
    }

    private Object invoke(Object engine, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = engine.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(engine, args);
    }

    private void check(Object result, String stage) {
        if (!(result instanceof Integer) || ((Integer) result) != 0) {
            throw new IllegalStateException(stage + " failed: " + result);
        }
    }

    private void progress(Context context, JSONObject report, String runId, String stage) throws Exception {
        report.put("last_stage", stage);
        report.put("pid", android.os.Process.myPid());
        report.put("pss_kib", Debug.getPss());
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        report.put("process_importance", info.importance);
        File file = new File(context.getFilesDir(), "motoai-b0-progress-" + runId + ".json");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override public void onStart() {
        JSONObject report = new JSONObject();
        Object engine = null;
        boolean prepared = false;
        boolean passed = false;
        Context context = getTargetContext();
        String runId = arguments.getString("runId", "manual");
        if (!runId.matches("[a-zA-Z0-9_-]{1,80}")) {
            Bundle invalid = new Bundle();
            invalid.putString("error", "Invalid run ID");
            finish(0, invalid);
            return;
        }
        long start = SystemClock.elapsedRealtimeNanos();
        try {
            report.put("run_id", runId);
            String modelName = arguments.getString("model", "smoke.gguf");
            if (!modelName.matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("Invalid model filename");
            File model = new File(context.getFilesDir(), "motoai-bench/" + modelName);
            if (!model.isFile()) throw new IllegalArgumentException("Model not staged: " + modelName);
            String prompt = arguments.getString("prompt", "Write a Python function that adds two integers.");
            int limit = Integer.parseInt(arguments.getString("tokens", "32"));
            if (prompt.length() > 2048 || limit < 1 || limit > 256) throw new IllegalArgumentException("Short baseline fixture required");
            String variant = arguments.getString("variant", "B0");
            if (!variant.equals("B0") && !variant.equals("B1-jinja") && !variant.equals("B1-jinja-no-mmap")) throw new IllegalArgumentException("Invalid variant");
            report.put("variant", variant);
            report.put("scope", variant + " native JNI instrumentation; no UI/Flow");
            report.put("context", 8192);
            report.put("model", modelName);
            report.put("prompt", prompt);
            report.put("output_token_limit", limit);
            report.put("sampling", "upstream default temperature 0.3, default random seed");
            boolean foreground = arguments.getString("foreground", "false").equals("true");
            report.put("foreground_activity", foreground);
            if (foreground) {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                if (launch == null) throw new IllegalStateException("No launcher Activity");
                startActivitySync(launch);
                Class<?> implementation = context.getClassLoader().loadClass("com.arm.aichat.internal.InferenceEngineImpl");
                Method existing = implementation.getDeclaredMethod("access$getInstance$cp");
                existing.setAccessible(true);
                for (int i = 0; i < 300 && engine == null; ++i) {
                    engine = existing.invoke(null);
                    if (engine == null) SystemClock.sleep(100);
                }
                if (engine == null) throw new IllegalStateException("Activity did not initialize engine");
            } else {
                Class<?> facade = context.getClassLoader().loadClass("com.arm.aichat.AiChat");
                Object instance = facade.getField("INSTANCE").get(null);
                engine = facade.getMethod("getInferenceEngine", Context.class).invoke(instance, context);
            }
            Method stateMethod = engine.getClass().getMethod("getState");
            stateMethod.setAccessible(true);
            boolean initialized = false;
            for (int i = 0; i < 300; ++i) {
                Object stateFlow = stateMethod.invoke(engine);
                Method valueMethod = stateFlow.getClass().getMethod("getValue");
                valueMethod.setAccessible(true);
                Object state = valueMethod.invoke(stateFlow);
                if (state.getClass().getName().endsWith("$Initialized")) { initialized = true; break; }
                SystemClock.sleep(100);
            }
            if (!initialized) throw new IllegalStateException("Upstream engine did not initialize within 30s");
            report.put("system_info", invoke(engine, "systemInfo", new Class<?>[]{}));
            progress(context, report, runId, "before_load");
            long loadStart = SystemClock.elapsedRealtimeNanos();
            check(invoke(engine, "load", new Class<?>[]{String.class}, model.getAbsolutePath()), "load");
            check(invoke(engine, "prepare", new Class<?>[]{}), "prepare");
            prepared = true;
            long loaded = SystemClock.elapsedRealtimeNanos();
            report.put("model_and_context_load_ms", (loaded - loadStart) / 1e6);
            progress(context, report, runId, "prepared");
            long ready = SystemClock.elapsedRealtimeNanos();
            check(invoke(engine, "processSystemPrompt", new Class<?>[]{String.class}, "You are a helpful assistant."), "system prompt");
            check(invoke(engine, "processUserPrompt", new Class<?>[]{String.class, int.class}, prompt, limit), "user prompt");
            long prefilled = SystemClock.elapsedRealtimeNanos();
            long firstVisible = 0;
            int emitted = 0;
            StringBuilder output = new StringBuilder();
            boolean eos = false;
            for (int i = 0; i < limit; ++i) {
                String piece = (String) invoke(engine, "generateNextToken", new Class<?>[]{});
                if (piece == null) { eos = true; break; }
                emitted++;
                if (!piece.isEmpty() && firstVisible == 0) firstVisible = SystemClock.elapsedRealtimeNanos();
                output.append(piece);
            }
            long done = SystemClock.elapsedRealtimeNanos();
            report.put("system_and_user_prefill_ms", (prefilled - ready) / 1e6);
            report.put("decode_ms", (done - prefilled) / 1e6);
            report.put("ready_ttft_ms", firstVisible == 0 ? JSONObject.NULL : (firstVisible - ready) / 1e6);
            report.put("non_eog_tokens", emitted);
            report.put("native_returned_null", eos);
            report.put("output", output.toString());
            progress(context, report, runId, "decoded");
            passed = emitted > 0 && output.length() > 0;
        } catch (Throwable error) {
            try { report.put("error", error.toString()); } catch (Exception ignored) { }
        } finally {
            if (prepared && engine != null) {
                try { invoke(engine, "unload", new Class<?>[]{}); }
                catch (Throwable error) { passed = false; try { report.put("cleanup_error", error.toString()); } catch (Exception ignored) { } }
            }
        }
        Bundle result = new Bundle();
        try {
            report.put("passed", passed);
            report.put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1e6);
            File file = new File(context.getFilesDir(), "motoai-b0-result-" + runId + ".json");
            try (FileOutputStream stream = new FileOutputStream(file)) {
                stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            result.putString("report", report.toString());
        } catch (Exception error) {
            passed = false;
            result.putString("error", error.toString());
        }
        finish(passed ? -1 : 0, result);
    }
}
