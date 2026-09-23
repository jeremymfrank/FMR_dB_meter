package com.fmr.dbmeter;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 1001;
    private static final int SAMPLE_RATE = 48000;
    private static final int LIME = Color.rgb(114, 255, 114);
    private static final int DIM = Color.rgb(138, 166, 143);
    private static final int LINE = Color.rgb(23, 53, 30);
    private static final int BG = Color.rgb(5, 8, 6);
    private static final int PANEL = Color.rgb(9, 16, 11);
    private static final int HOT = Color.rgb(255, 107, 107);

    private TextView mainValue, status, leqValue, peakValue, rawValue;
    private EditText calibration;
    private Button startButton, stopButton;
    private ProgressBar meter;
    private volatile boolean running = false;
    private AudioRecord recorder;
    private Thread audioThread;
    private double peakDb = Double.NEGATIVE_INFINITY;
    private final ArrayDeque<LevelSample> history = new ArrayDeque<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AWeightFilter aFilter = new AWeightFilter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private LinearLayout panel() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(14), 0, 0);
        l.setLayoutParams(lp);
        return l;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(LIME);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setBackgroundColor(Color.rgb(16, 32, 21));
        b.setAllCaps(false);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);

        TextView title = text("FMR dB METER", 30, LIME);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(title);

        TextView badge = text("OFFLINE • MIC ONLY", 12, DIM);
        badge.setPadding(0, dp(8), 0, dp(6));
        root.addView(badge);

        LinearLayout reading = panel();
        reading.setGravity(Gravity.CENTER_HORIZONTAL);
        mainValue = text("--.-", 78, LIME);
        mainValue.setGravity(Gravity.CENTER);
        mainValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        reading.addView(mainValue, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView unit = text("estimated dBA", 18, DIM);
        unit.setGravity(Gravity.CENTER);
        reading.addView(unit);

        status = text("Tap START to request microphone permission.", 13, DIM);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(10), 0, dp(10));
        reading.addView(status);

        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(1000);
        meter.setProgress(0);
        reading.addView(meter, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setPadding(0, dp(14), 0, 0);
        leqValue = addStat(stats, "--.-", "10 s Leq");
        peakValue = addStat(stats, "--.-", "Peak");
        rawValue = addStat(stats, "--.-", "A-weighted dBFS");
        reading.addView(stats);
        root.addView(reading);

        LinearLayout controls = panel();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("START");
        stopButton = button("STOP");
        Button reset = button("RESET PEAK");
        stopButton.setEnabled(false);
        row.addView(startButton, weighted());
        row.addView(stopButton, weighted());
        row.addView(reset, weighted());
        controls.addView(row);

        TextView calLabel = text("Calibration offset (dB)", 14, DIM);
        calLabel.setPadding(0, dp(18), 0, dp(4));
        controls.addView(calLabel);
        calibration = new EditText(this);
        calibration.setText("100.0");
        calibration.setSingleLine(true);
        calibration.setTextColor(Color.WHITE);
        calibration.setTextSize(18);
        calibration.setTypeface(Typeface.MONOSPACE);
        calibration.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL |
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        calibration.setBackgroundColor(Color.rgb(3, 5, 3));
        calibration.setPadding(dp(12), dp(10), dp(12), dp(10));
        controls.addView(calibration);

        TextView note = text("100 dB is only a starting estimate. Phone microphone gain differs by model. Calibrate against a trusted sound meter for absolute readings.", 12, DIM);
        note.setPadding(0, dp(10), 0, 0);
        controls.addView(note);
        root.addView(controls);

        LinearLayout privacy = panel();
        TextView pTitle = text("PRIVACY / BEHAVIOR", 15, LIME);
        pTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        privacy.addView(pTitle);
        TextView p = text("No Internet permission. No ads, analytics, accounts, uploads, or audio files. Samples are processed in memory only. The app requests microphone access when START is tapped.", 12, DIM);
        p.setPadding(0, dp(8), 0, 0);
        privacy.addView(p);
        root.addView(privacy);

        TextView footer = text("FMR dB Meter v0.3 • native Android", 11, Color.rgb(93, 120, 98));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(16), 0, 0);
        root.addView(footer);

        startButton.setOnClickListener(v -> requestOrStart());
        stopButton.setOnClickListener(v -> stopMeter());
        reset.setOnClickListener(v -> {
            peakDb = Double.NEGATIVE_INFINITY;
            synchronized (history) { history.clear(); }
            peakValue.setText("--.-");
            leqValue.setText("--.-");
        });

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private TextView addStat(LinearLayout row, String value, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(4), dp(10), dp(4), dp(10));
        TextView v = text(value, 20, LIME);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        TextView l = text(label, 10, DIM);
        l.setGravity(Gravity.CENTER);
        box.addView(v);
        box.addView(l);
        row.addView(box, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    private void requestOrStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else {
            startMeter();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMeter();
            } else {
                status.setTextColor(HOT);
                status.setText("Microphone permission denied. START will ask again if Android allows it.");
            }
        }
    }

    private double calibrationOffset() {
        try { return Double.parseDouble(calibration.getText().toString().trim()); }
        catch (Exception e) { return 100.0; }
    }

    private void startMeter() {
        if (running) return;
        status.setTextColor(DIM);
        status.setText("Starting microphone…");
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(min * 2, 8192);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord initialization failed");
            aFilter.reset();
            recorder.startRecording();
            running = true;
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            status.setText("LIVE • A-weighted estimate");
            audioThread = new Thread(this::audioLoop, "FMR-dB-Audio");
            audioThread.start();
        } catch (Exception e) {
            status.setTextColor(HOT);
            status.setText("Microphone unavailable: " + e.getMessage());
            safeRelease();
        }
    }

    private void audioLoop() {
        short[] buffer = new short[2048];
        while (running && recorder != null) {
            int n = recorder.read(buffer, 0, buffer.length);
            if (n <= 0) continue;
            double sum = 0.0;
            for (int i = 0; i < n; i++) {
                double x = buffer[i] / 32768.0;
                double y = aFilter.process(x);
                sum += y * y;
            }
            double rms = Math.sqrt(sum / n);
            double awDbfs = 20.0 * Math.log10(Math.max(rms, 1e-9));
            double spl = awDbfs + calibrationOffset();
            long now = System.currentTimeMillis();
            peakDb = Math.max(peakDb, spl);
            double leq;
            synchronized (history) {
                history.addLast(new LevelSample(now, spl));
                while (!history.isEmpty() && now - history.peekFirst().time > 10000) history.removeFirst();
                double energy = 0;
                for (LevelSample s : history) energy += Math.pow(10.0, s.db / 10.0);
                leq = history.isEmpty() ? spl : 10.0 * Math.log10(energy / history.size());
            }
            final double fSpl = spl, fLeq = leq, fPeak = peakDb, fRaw = awDbfs;
            ui.post(() -> updateReadings(fSpl, fLeq, fPeak, fRaw));
        }
    }

    private void updateReadings(double spl, double leq, double peak, double raw) {
        mainValue.setText(String.format(Locale.US, "%.1f", spl));
        leqValue.setText(String.format(Locale.US, "%.1f", leq));
        peakValue.setText(String.format(Locale.US, "%.1f", peak));
        rawValue.setText(String.format(Locale.US, "%.1f", raw));
        int p = (int)Math.round(Math.max(0, Math.min(1000, ((spl - 25.0) / 65.0) * 1000.0)));
        meter.setProgress(p);
    }

    private void stopMeter() {
        running = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
        }
        safeRelease();
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        status.setTextColor(DIM);
        status.setText("Stopped.");
    }

    private void safeRelease() {
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopMeter();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class LevelSample {
        final long time;
        final double db;
        LevelSample(long time, double db) { this.time = time; this.db = db; }
    }

    // IEC-style A-weighting filter, bilinear-transformed for 48 kHz and
    // implemented as three stable biquad sections (Direct Form II Transposed).
    private static class AWeightFilter {
        private static final double[][] SOS = {
                {0.23430179,  0.46860358, 0.23430179, 1.0, -0.22455846, 0.01260663},
                {1.0,        -1.99999996, 0.99999999, 1.0, -1.89387049, 0.89515977},
                {1.0,        -2.00000004, 1.00000001, 1.0, -1.99461446, 0.99462171}
        };
        private final double[][] z = new double[3][2];

        double process(double x) {
            double y = x;
            for (int s = 0; s < 3; s++) {
                double[] c = SOS[s];
                double out = c[0] * y + z[s][0];
                z[s][0] = c[1] * y - c[4] * out + z[s][1];
                z[s][1] = c[2] * y - c[5] * out;
                y = out;
            }
            return y;
        }
        void reset() {
            for (int s = 0; s < z.length; s++) { z[s][0] = 0; z[s][1] = 0; }
        }
    }
}
