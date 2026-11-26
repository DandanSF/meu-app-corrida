package de.dennisguse.opentracks.ui.records;

import android.graphics.Typeface;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.TrackPointIterator;
import de.dennisguse.opentracks.data.models.ActivityType;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.data.models.TrackPoint;

/**
 * DanRun: recordes pessoais.
 * Procura, em todas as corridas gravadas, o trecho mais rápido de cada distância
 * (1 km, 5 km, 10 km, 21,1 km...) — igual aos "melhores esforços" do Strava.
 */
public class DanRunRecordsActivity extends AppCompatActivity {

    private static final double[] DISTANCES_M = {400, 1000, 1609.34, 5000, 10000, 15000, 21097.5, 42195};
    private static final String[] LABELS = {"400 m", "1 km", "1 milha", "5 km", "10 km", "15 km", "Meia maratona (21,1 km)", "Maratona (42,2 km)"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout list;

    private static class Best {
        double seconds = Double.MAX_VALUE;
        String trackName;
        Instant date;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);
        setTitle(R.string.danrun_records_title);

        addTitle(getString(R.string.danrun_records_title));
        addText("Calculando a partir das suas corridas…", 15, false);

        executor.execute(() -> {
            Best[] bests = compute();
            new Handler(Looper.getMainLooper()).post(() -> show(bests));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private static boolean isRun(Track track) {
        ActivityType type = track.getActivityType();
        return type == null || type == ActivityType.UNKNOWN || type.name().contains("RUNNING") || type.name().contains("JOGGING");
    }

    private Best[] compute() {
        Best[] bests = new Best[DISTANCES_M.length];
        for (int i = 0; i < bests.length; i++) bests[i] = new Best();

        ContentProviderUtils cpu = new ContentProviderUtils(this);
        for (Track track : cpu.getTracks()) {
            if (Thread.currentThread().isInterrupted()) break;
            if (!isRun(track) || track.getId() == null) continue;

            // Série (tempo ativo, distância acumulada), sem contar pausas.
            List<double[]> series = new ArrayList<>();
            double activeS = 0;
            double distM = 0;
            Location prevLoc = null;
            Instant prevTime = null;

            try (TrackPointIterator it = new TrackPointIterator(cpu, track.getId(), null)) {
                while (it.hasNext()) {
                    TrackPoint p = it.next();
                    boolean newSegment = p.getType() == TrackPoint.Type.SEGMENT_START_MANUAL
                            || p.getType() == TrackPoint.Type.SEGMENT_START_AUTOMATIC
                            || p.getType() == TrackPoint.Type.SEGMENT_END_MANUAL;
                    if (newSegment) {
                        prevLoc = null;
                        prevTime = null;
                    }
                    if (!p.hasLocation() || p.getLocation() == null) continue;
                    Location loc = p.getLocation();
                    Instant time = p.getTime();
                    if (prevLoc != null && prevTime != null) {
                        double dt = (time.toEpochMilli() - prevTime.toEpochMilli()) / 1000.0;
                        if (dt > 0 && dt < 120) {
                            activeS += dt;
                            distM += prevLoc.distanceTo(loc);
                        }
                    }
                    prevLoc = loc;
                    prevTime = time;
                    series.add(new double[]{activeS, distM});
                }
            } catch (Exception e) {
                continue;
            }

            if (series.size() < 2) continue;

            for (int d = 0; d < DISTANCES_M.length; d++) {
                double target = DISTANCES_M[d];
                if (distM < target) continue;
                // Janela deslizante: para cada ponto final, o início mais tardio que ainda cobre a distância.
                int start = 0;
                double best = Double.MAX_VALUE;
                for (int end = 1; end < series.size(); end++) {
                    while (start + 1 < end && series.get(end)[1] - series.get(start + 1)[1] >= target) {
                        start++;
                    }
                    double covered = series.get(end)[1] - series.get(start)[1];
                    if (covered >= target) {
                        double t = series.get(end)[0] - series.get(start)[0];
                        // ajusta proporcionalmente para a distância exata
                        t = t * target / covered;
                        if (t < best) best = t;
                    }
                }
                if (best < bests[d].seconds) {
                    bests[d].seconds = best;
                    bests[d].trackName = track.getName();
                    bests[d].date = track.getStartTime() != null ? track.getStartTime().toInstant() : null;
                }
            }
        }
        return bests;
    }

    private void show(Best[] bests) {
        if (isFinishing() || isDestroyed()) return;
        list.removeAllViews();
        addTitle(getString(R.string.danrun_records_title));

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale("pt", "BR")).withZone(ZoneId.systemDefault());
        boolean any = false;
        for (int i = 0; i < bests.length; i++) {
            Best b = bests[i];
            if (b.seconds == Double.MAX_VALUE) continue;
            any = true;
            double paceS = b.seconds / (DISTANCES_M[i] / 1000.0);
            addText(LABELS[i], 18, true);
            addText("Tempo: " + formatTime(b.seconds) + "   •   Pace: " + formatPace(paceS) + " /km", 16, false);
            String when = (b.trackName != null ? b.trackName : "") + (b.date != null ? " — " + fmt.format(b.date) : "");
            addText(when, 13, false);
            addSpacer();
        }
        if (!any) {
            addText("Ainda não há corridas suficientes. Grave uma corrida e volte aqui!", 16, false);
        }
    }

    private static String formatTime(double seconds) {
        long s = Math.round(seconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private static String formatPace(double secondsPerKm) {
        long s = Math.round(secondsPerKm);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private void addTitle(String text) {
        TextView tv = addText(text, 24, true);
        tv.setGravity(Gravity.START);
        addSpacer();
    }

    private TextView addText(String text, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        list.addView(tv);
        return tv;
    }

    private void addSpacer() {
        TextView spacer = new TextView(this);
        spacer.setHeight(dp(14));
        list.addView(spacer);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
