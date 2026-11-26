package de.dennisguse.opentracks.services.announcement;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.Duration;
import java.util.Locale;

import de.dennisguse.opentracks.data.models.Speed;
import de.dennisguse.opentracks.stats.TrackStatistics;
import de.dennisguse.opentracks.ui.intervals.IntervalStatistics;

/**
 * DanRun: aviso de voz curto e em português a cada km.
 * Exemplo: "2 quilômetros. Pace do km: 5 e 42. Pace médio: 5 e 50. Tempo: 11 minutos e 32 segundos."
 */
public final class DanRunKmAnnouncement {

    private DanRunKmAnnouncement() {
    }

    @NonNull
    public static String build(@NonNull TrackStatistics statistics, @Nullable IntervalStatistics.Interval lastInterval) {
        StringBuilder sb = new StringBuilder();

        double km = statistics.getTotalDistance().toKM();
        long roundedKm = Math.round(km);
        if (Math.abs(km - roundedKm) < 0.05 && roundedKm > 0) {
            sb.append(roundedKm).append(roundedKm == 1 ? " quilômetro. " : " quilômetros. ");
        } else {
            sb.append(String.format(new Locale("pt", "BR"), "%.1f", km)).append(" quilômetros. ");
        }

        if (lastInterval != null) {
            Integer lapPace = secondsPerKm(lastInterval.getSpeed());
            if (lapPace != null) {
                sb.append("Pace do quilômetro: ").append(pace(lapPace)).append(". ");
            }
        }

        Integer avgPace = secondsPerKm(statistics.getAverageMovingSpeed());
        if (avgPace != null) {
            sb.append("Pace médio: ").append(pace(avgPace)).append(". ");
        }

        Duration moving = statistics.getMovingTime();
        if (moving != null && !moving.isZero()) {
            sb.append("Tempo: ").append(duration(moving)).append(".");
        }
        return sb.toString().trim();
    }

    @Nullable
    static Integer secondsPerKm(@Nullable Speed speed) {
        if (speed == null || speed.isZero() || speed.isInvalid()) {
            return null;
        }
        double mps = speed.toMPS();
        if (mps <= 0.3) { // mais lento que ~55 min/km: provavelmente parado
            return null;
        }
        return (int) Math.round(1000.0 / mps);
    }

    static String pace(int secondsPerKm) {
        int min = secondsPerKm / 60;
        int sec = secondsPerKm % 60;
        if (sec == 0) {
            return min + " minutos";
        }
        return min + " e " + sec;
    }

    static String duration(Duration d) {
        long total = d.getSeconds();
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(h).append(h == 1 ? " hora" : " horas");
            if (m > 0) sb.append(" e ");
        }
        if (m > 0 || h == 0) {
            sb.append(m).append(m == 1 ? " minuto" : " minutos");
        }
        if (h == 0 && s > 0) {
            sb.append(" e ").append(s).append(s == 1 ? " segundo" : " segundos");
        }
        return sb.toString();
    }
}
