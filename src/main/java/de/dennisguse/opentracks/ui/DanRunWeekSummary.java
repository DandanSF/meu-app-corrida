package de.dennisguse.opentracks.ui;

import android.app.Activity;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.stats.TrackStatistics;

/**
 * DanRun: painel "Esta semana" da tela inicial (km, corridas, tempo e pace médio).
 */
public final class DanRunWeekSummary {

    private DanRunWeekSummary() {
    }

    public static void update(Activity activity, TextView distance, TextView details, TextView month) {
        new Thread(() -> {
            ZoneId zone = ZoneId.systemDefault();
            LocalDate today = LocalDate.now(zone);
            LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate monthStart = today.withDayOfMonth(1);

            double weekM = 0, monthM = 0;
            long weekS = 0;
            int weekRuns = 0, monthRuns = 0;

            try {
                for (Track track : new ContentProviderUtils(activity).getTracks()) {
                    OffsetDateTime start = track.getStartTime();
                    TrackStatistics stats = track.getTrackStatistics();
                    if (start == null || stats == null) continue;
                    LocalDate day = start.atZoneSameInstant(zone).toLocalDate();
                    double meters = stats.getTotalDistance().toM();
                    if (Double.isNaN(meters)) continue;
                    if (!day.isBefore(monthStart)) {
                        monthM += meters;
                        monthRuns++;
                    }
                    if (!day.isBefore(weekStart)) {
                        weekM += meters;
                        weekS += stats.getMovingTime().getSeconds();
                        weekRuns++;
                    }
                }
            } catch (Exception e) {
                return;
            }

            Locale br = new Locale("pt", "BR");
            String distanceText = String.format(br, "%.1f km", weekM / 1000.0);
            String detailsText;
            if (weekRuns == 0) {
                detailsText = "Nenhuma corrida ainda. Toque no botão laranja para começar!";
            } else {
                String runs = weekRuns + (weekRuns == 1 ? " corrida" : " corridas");
                long h = weekS / 3600, m = (weekS % 3600) / 60;
                String time = h > 0 ? h + "h" + String.format(br, "%02d", m) : m + " min";
                String pace = "";
                if (weekM > 50) {
                    long p = Math.round(weekS / (weekM / 1000.0));
                    pace = String.format(br, "  •  %d'%02d\" /km", p / 60, p % 60);
                }
                detailsText = runs + "  •  " + time + pace;
            }
            String monthText = String.format(br, "No mês: %.1f km em %d %s", monthM / 1000.0, monthRuns, monthRuns == 1 ? "atividade" : "atividades");

            final String d = distanceText, dt = detailsText, mt = monthText;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                distance.setText(d);
                details.setText(dt);
                month.setText(mt);
            });
        }).start();
    }
}
