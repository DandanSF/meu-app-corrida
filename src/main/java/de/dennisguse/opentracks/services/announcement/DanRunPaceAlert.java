package de.dennisguse.opentracks.services.announcement;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.stats.TrackStatistics;

/**
 * DanRun: alerta de ritmo-alvo.
 * Calcula o pace dos últimos ~30 segundos e avisa por voz quando
 * você está mais lento ou mais rápido que o ritmo-alvo configurado.
 */
public class DanRunPaceAlert {

    private static final long WINDOW_MS = 30_000;
    private static final long MIN_WINDOW_MS = 15_000;
    private static final double MIN_WINDOW_M = 25;
    private static final double WARMUP_M = 200;
    private static final long COOLDOWN_MS = 45_000;

    /** pares (tempo em movimento em ms, distância total em m) */
    private final ArrayDeque<double[]> window = new ArrayDeque<>();
    private long lastAlertMovingMs = -COOLDOWN_MS;

    public void reset() {
        window.clear();
        lastAlertMovingMs = -COOLDOWN_MS;
    }

    /**
     * Converte "6:30" (ou "6.30" / "630") em segundos por km. Retorna 0 se vazio ou inválido.
     */
    static int parsePace(@Nullable String text) {
        if (text == null) return 0;
        String t = text.trim().replace('.', ':').replace(',', ':');
        if (t.isEmpty()) return 0;
        try {
            if (t.contains(":")) {
                String[] p = t.split(":");
                int min = Integer.parseInt(p[0].trim());
                int sec = p.length > 1 && !p[1].trim().isEmpty() ? Integer.parseInt(p[1].trim()) : 0;
                return min * 60 + sec;
            }
            int v = Integer.parseInt(t);
            if (v >= 100) {
                return (v / 100) * 60 + (v % 100);
            }
            return v * 60;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String speakPace(int secondsPerKm) {
        int min = secondsPerKm / 60;
        int sec = secondsPerKm % 60;
        if (sec == 0) {
            return min + " minutos por quilômetro";
        }
        return min + " e " + sec;
    }

    /**
     * @return a frase a ser falada, ou null se não precisa avisar.
     */
    @Nullable
    public String check(@NonNull TrackStatistics statistics) {
        int target = parsePace(PreferencesUtils.getString(R.string.danrun_target_pace_key, ""));
        if (target <= 0) {
            return null;
        }
        int tolerance;
        try {
            tolerance = Integer.parseInt(PreferencesUtils.getString(R.string.danrun_pace_tolerance_key, "15"));
        } catch (NumberFormatException e) {
            tolerance = 15;
        }

        double movingMs = statistics.getMovingTime().toMillis();
        double distanceM = statistics.getTotalDistance().toM();
        if (Double.isNaN(distanceM)) {
            return null;
        }

        window.addLast(new double[]{movingMs, distanceM});
        while (window.size() > 2 && window.peekFirst()[0] < movingMs - WINDOW_MS) {
            window.removeFirst();
        }

        if (distanceM < WARMUP_M) {
            return null;
        }

        double[] first = window.peekFirst();
        double dt = movingMs - first[0];
        double dd = distanceM - first[1];
        if (dt < MIN_WINDOW_MS || dd < MIN_WINDOW_M) {
            return null;
        }

        if (movingMs - lastAlertMovingMs < COOLDOWN_MS) {
            return null;
        }

        int pace = (int) Math.round((dt / 1000.0) / (dd / 1000.0));
        if (pace > 30 * 60) {
            // praticamente parado: não incomoda
            return null;
        }

        if (pace > target + tolerance) {
            lastAlertMovingMs = (long) movingMs;
            return "Acelere. Ritmo " + speakPace(pace) + ". Alvo " + speakPace(target) + ".";
        }
        if (pace < target - tolerance) {
            lastAlertMovingMs = (long) movingMs;
            return "Segura o ritmo. Você está em " + speakPace(pace) + ". Alvo " + speakPace(target) + ".";
        }
        return null;
    }
}
