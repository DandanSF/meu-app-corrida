package de.dennisguse.opentracks.ui.map;

import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;

import de.dennisguse.opentracks.BuildConfig;
import de.dennisguse.opentracks.chart.TrackDataHubInterface;
import de.dennisguse.opentracks.data.TrackDataHub;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.data.models.TrackPoint;
import de.dennisguse.opentracks.stats.TrackStatistics;

/**
 * DanRun: mapa (OpenStreetMap) dentro do app.
 * Durante a corrida, desenha o trajeto ao vivo e segue a posição atual.
 * Depois da corrida, mostra o trajeto inteiro.
 */
public class DanRunMapFragment extends Fragment implements TrackDataHub.Listener {

    private static final String ARG_RECORDING = "recording";

    private static final int ROUTE_COLOR = Color.parseColor("#FFC000");
    private static final int ROUTE_BORDER_COLOR = Color.parseColor("#0B1F3A");

    private MapView mapView;
    private Polyline routeBorder;
    private Polyline route;
    private Marker startMarker;
    private Marker currentMarker;

    private TrackDataHub trackDataHub;
    private boolean recording;

    // O usuário mexeu no mapa? Então paramos de seguir a posição por um tempo.
    private long userTouchedAt = 0;

    private final Object lock = new Object();
    private final List<GeoPoint> pending = new ArrayList<>();
    private final List<GeoPoint> allPoints = new ArrayList<>();
    private boolean clearRequested = false;

    public static DanRunMapFragment newInstance(boolean recording) {
        DanRunMapFragment fragment = new DanRunMapFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_RECORDING, recording);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        recording = getArguments() != null && getArguments().getBoolean(ARG_RECORDING, false);

        Configuration.getInstance().load(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()));
        Configuration.getInstance().setUserAgentValue(BuildConfig.APPLICATION_ID);

        mapView = new MapView(requireContext());
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(16.0);
        mapView.setOnTouchListener((v, event) -> {
            // Arrastar o mapa não deve trocar de aba.
            if (event.getAction() == MotionEvent.ACTION_DOWN && v.getParent() != null) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                userTouchedAt = System.currentTimeMillis();
            }
            return false;
        });

        routeBorder = new Polyline(mapView);
        routeBorder.getOutlinePaint().setColor(ROUTE_BORDER_COLOR);
        routeBorder.getOutlinePaint().setStrokeWidth(16f);
        mapView.getOverlays().add(routeBorder);

        route = new Polyline(mapView);
        route.getOutlinePaint().setColor(ROUTE_COLOR);
        route.getOutlinePaint().setStrokeWidth(10f);
        mapView.getOverlays().add(route);

        startMarker = new Marker(mapView);
        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        startMarker.setTitle("Largada");
        startMarker.setEnabled(false);
        mapView.getOverlays().add(startMarker);

        currentMarker = new Marker(mapView);
        currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        currentMarker.setTitle(recording ? "Você está aqui" : "Chegada");
        currentMarker.setEnabled(false);
        mapView.getOverlays().add(currentMarker);

        return mapView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
        if (getActivity() instanceof TrackDataHubInterface) {
            trackDataHub = ((TrackDataHubInterface) getActivity()).getTrackDataHub();
            if (trackDataHub != null) {
                trackDataHub.registerTrackDataListener(this);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (trackDataHub != null) {
            trackDataHub.unregisterTrackDataListener(this);
            trackDataHub = null;
        }
        if (mapView != null) {
            mapView.onPause();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mapView != null) {
            mapView.onDetach();
            mapView = null;
        }
    }

    // ---- Dados da corrida (chegam numa linha de execução separada) ----

    @Override
    public void onTrackUpdated(@NonNull Track track) {
        // nada a fazer
    }

    @Override
    public void clearTrackPoints() {
        synchronized (lock) {
            pending.clear();
            clearRequested = true;
        }
    }

    @Override
    public void onSampledInTrackPoint(@NonNull TrackPoint trackPoint, @NonNull TrackStatistics trackStatistics) {
        addPoint(trackPoint);
    }

    @Override
    public void onSampledOutTrackPoint(@NonNull TrackPoint trackPoint, @NonNull TrackStatistics trackStatistics) {
        addPoint(trackPoint);
    }

    private void addPoint(@NonNull TrackPoint trackPoint) {
        if (!trackPoint.hasLocation()) {
            return;
        }
        Location location = trackPoint.getLocation();
        if (location == null) {
            return;
        }
        synchronized (lock) {
            pending.add(new GeoPoint(location.getLatitude(), location.getLongitude()));
        }
    }

    @Override
    public void onNewTrackPointsDone() {
        if (getActivity() == null) {
            return;
        }
        getActivity().runOnUiThread(this::updateMap);
    }

    private void updateMap() {
        if (mapView == null || !isAdded()) {
            return;
        }

        boolean firstDraw;
        synchronized (lock) {
            if (clearRequested) {
                allPoints.clear();
                clearRequested = false;
            }
            firstDraw = allPoints.isEmpty();
            allPoints.addAll(pending);
            pending.clear();
        }

        if (allPoints.isEmpty()) {
            return;
        }

        List<GeoPoint> copy = new ArrayList<>(allPoints);
        routeBorder.setPoints(copy);
        route.setPoints(copy);

        GeoPoint first = copy.get(0);
        GeoPoint last = copy.get(copy.size() - 1);

        startMarker.setPosition(first);
        startMarker.setEnabled(true);
        currentMarker.setPosition(last);
        currentMarker.setEnabled(true);

        if (recording) {
            boolean userIsExploring = System.currentTimeMillis() - userTouchedAt < 15_000;
            if (!userIsExploring) {
                if (firstDraw) {
                    mapView.getController().setZoom(17.0);
                }
                mapView.getController().animateTo(last);
            }
        } else if (copy.size() > 1) {
            BoundingBox box = BoundingBox.fromGeoPointsSafe(copy);
            mapView.post(() -> {
                if (mapView != null) {
                    mapView.zoomToBoundingBox(box.increaseByScale(1.2f), false);
                }
            });
        } else {
            mapView.getController().setCenter(last);
        }

        mapView.invalidate();
    }
}
