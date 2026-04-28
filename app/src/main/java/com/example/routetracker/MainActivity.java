package com.example.routetracker;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends FragmentActivity implements OnMapReadyCallback, SensorEventListener {

    private static final int LOCATION_PERMISSION_REQUEST = 101;
    private static final String PREFS = "walk_sessions";
    private static final String KEY_HISTORY = "history";

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private final List<LatLng> routePoints = new ArrayList<>();
    private Polyline routePolyline;

    private Button btnStartStop;
    private TextView tvStatus;
    private TextView tvTime;
    private TextView tvDistance;
    private TextView tvPace;
    private ImageView ivCompass;
    private TextView tvCompassHeading;

    private boolean isTracking = false;
    private long walkStartTime = 0L;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private double distanceMeters = 0.0;
    private Location lastTrackedLocation;

    private final List<RouteRecord> history = new ArrayList<>();
    private HistoryAdapter historyAdapter;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private float currentRotation = 0f;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isTracking) {
                return;
            }
            long elapsedMs = System.currentTimeMillis() - walkStartTime;
            updateStatsUI(elapsedMs, distanceMeters);
            animateStatsPulse();
            timerHandler.postDelayed(this, 1000);
        }
    };

    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult locationResult) {
            if (locationResult.getLastLocation() == null || mMap == null) {
                return;
            }

            Location location = locationResult.getLastLocation();
            LatLng current = new LatLng(location.getLatitude(), location.getLongitude());

            if (isTracking) {
                routePoints.add(current);
                if (lastTrackedLocation != null) {
                    distanceMeters += lastTrackedLocation.distanceTo(location);
                }
                lastTrackedLocation = location;
                drawRoute();
            }

            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(current, 17f));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        bindViews();
        setupHistory();
        setupCompass();
        loadHistory();

        btnStartStop.setOnClickListener(v -> toggleTracking());

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);

        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    private void bindViews() {
        btnStartStop = findViewById(R.id.btnStartStop);
        tvStatus = findViewById(R.id.tvStatus);
        tvTime = findViewById(R.id.tvTime);
        tvDistance = findViewById(R.id.tvDistance);
        tvPace = findViewById(R.id.tvPace);
        ivCompass = findViewById(R.id.ivCompass);
        tvCompassHeading = findViewById(R.id.tvCompassHeading);
    }

    private void setupHistory() {
        RecyclerView rvHistory = findViewById(R.id.rvHistory);
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        historyAdapter = new HistoryAdapter(history);
        rvHistory.setAdapter(historyAdapter);
    }

    private void setupCompass() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
    }

    private void toggleTracking() {
        animateButtonPress(btnStartStop);

        if (!isTracking) {
            startWalk();
        } else {
            stopWalk();
        }
    }

    private void startWalk() {
        if (!hasLocationPermission()) {
            requestLocationPermission();
            return;
        }

        isTracking = true;
        walkStartTime = System.currentTimeMillis();
        distanceMeters = 0;
        lastTrackedLocation = null;
        routePoints.clear();

        if (routePolyline != null) {
            routePolyline.remove();
            routePolyline = null;
        }

        btnStartStop.setText("Finalizar caminata");
        btnStartStop.setBackgroundResource(R.drawable.bg_stop_button);
        tvStatus.setText("Caminata en curso…");

        timerHandler.removeCallbacks(timerRunnable);
        timerHandler.post(timerRunnable);

        startLocationUpdates();
    }

    private void stopWalk() {
        isTracking = false;
        timerHandler.removeCallbacks(timerRunnable);

        long elapsedMs = System.currentTimeMillis() - walkStartTime;
        RouteRecord record = new RouteRecord(System.currentTimeMillis(), elapsedMs, distanceMeters);
        history.add(0, record);
        historyAdapter.notifyItemInserted(0);
        saveHistory();

        btnStartStop.setText("Iniciar caminata");
        btnStartStop.setBackgroundResource(R.drawable.bg_start_button);
        tvStatus.setText("Última ruta guardada");

        Toast.makeText(this, "Ruta guardada en historial", Toast.LENGTH_SHORT).show();
    }

    private void startLocationUpdates() {
        if (!hasLocationPermission()) {
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(2000)
                .setMinUpdateIntervalMillis(1000)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .build();

        fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
        );
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;

        if (!hasLocationPermission()) {
            requestLocationPermission();
            return;
        }

        mMap.setMyLocationEnabled(true);
        mMap.getUiSettings().setMyLocationButtonEnabled(true);
        mMap.getUiSettings().setCompassEnabled(true);
        startLocationUpdates();
    }

    private void drawRoute() {
        if (mMap == null || routePoints.isEmpty()) {
            return;
        }

        if (routePolyline != null) {
            routePolyline.remove();
        }

        routePolyline = mMap.addPolyline(new PolylineOptions()
                .addAll(routePoints)
                .width(10f)
                .color(0xFF38BDF8));
    }

    private void updateStatsUI(long elapsedMs, double distanceMeters) {
        tvTime.setText(formatDuration(elapsedMs));
        tvDistance.setText(String.format(Locale.getDefault(), "%.2f km", distanceMeters / 1000.0));
        tvPace.setText(formatPace(elapsedMs, distanceMeters));
    }

    private String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatPace(long elapsedMs, double distanceMeters) {
        if (distanceMeters < 10) {
            return "--:-- /km";
        }
        double secondsPerKm = (elapsedMs / 1000.0) / (distanceMeters / 1000.0);
        int minutes = (int) (secondsPerKm / 60);
        int seconds = (int) (secondsPerKm % 60);
        return String.format(Locale.getDefault(), "%02d:%02d /km", minutes, seconds);
    }

    private boolean hasLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                LOCATION_PERMISSION_REQUEST
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && mMap != null) {
            onMapReady(mMap);
        }
    }

    private void animateButtonPress(View v) {
        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction(
                () -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
        ).start();
    }

    private void animateStatsPulse() {
        ObjectAnimator animator = ObjectAnimator.ofFloat(tvDistance, "alpha", 0.7f, 1f);
        animator.setDuration(400);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.start();
    }

    private void loadHistory() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String json = preferences.getString(KEY_HISTORY, "[]");

        history.clear();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                history.add(RouteRecord.fromJson(item));
            }
        } catch (JSONException e) {
            Toast.makeText(this, "No se pudo leer el historial", Toast.LENGTH_SHORT).show();
        }

        historyAdapter.notifyDataSetChanged();
    }

    private void saveHistory() {
        JSONArray array = new JSONArray();
        for (RouteRecord record : history) {
            array.put(record.toJson());
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY, array.toString())
                .apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rotationSensor != null && sensorManager != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        fusedLocationClient.removeLocationUpdates(locationCallback);
        timerHandler.removeCallbacks(timerRunnable);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }

        float[] rotationMatrix = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientation);
        float azimuth = (float) Math.toDegrees(orientation[0]);
        azimuth = (azimuth + 360) % 360;

        ObjectAnimator rotate = ObjectAnimator.ofFloat(ivCompass, "rotation", currentRotation, -azimuth);
        rotate.setDuration(250);
        rotate.start();
        currentRotation = -azimuth;

        tvCompassHeading.setText(directionFromDegrees(azimuth));
    }

    private String directionFromDegrees(float degrees) {
        String[] dirs = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
        int index = Math.round(degrees / 45f) % 8;
        return dirs[index] + " " + (int) degrees + "°";
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No-op
    }

    private static class RouteRecord {
        long dateMs;
        long durationMs;
        double distanceMeters;

        RouteRecord(long dateMs, long durationMs, double distanceMeters) {
            this.dateMs = dateMs;
            this.durationMs = durationMs;
            this.distanceMeters = distanceMeters;
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("dateMs", dateMs);
                obj.put("durationMs", durationMs);
                obj.put("distanceMeters", distanceMeters);
            } catch (JSONException ignored) {
            }
            return obj;
        }

        static RouteRecord fromJson(JSONObject obj) throws JSONException {
            return new RouteRecord(
                    obj.getLong("dateMs"),
                    obj.getLong("durationMs"),
                    obj.getDouble("distanceMeters")
            );
        }
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryVH> {
        private final List<RouteRecord> items;

        HistoryAdapter(List<RouteRecord> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public HistoryVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_route_history, parent, false);
            return new HistoryVH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull HistoryVH holder, int position) {
            RouteRecord record = items.get(position);
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());
            holder.tvRouteDate.setText(sdf.format(new Date(record.dateMs)));

            String summary = String.format(
                    Locale.getDefault(),
                    "%.2f km · %s · %s",
                    record.distanceMeters / 1000.0,
                    formatDuration(record.durationMs),
                    formatPace(record.durationMs, record.distanceMeters)
            );
            holder.tvRouteSummary.setText(summary);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class HistoryVH extends RecyclerView.ViewHolder {
            TextView tvRouteDate;
            TextView tvRouteSummary;

            HistoryVH(@NonNull View itemView) {
                super(itemView);
                tvRouteDate = itemView.findViewById(R.id.tvRouteDate);
                tvRouteSummary = itemView.findViewById(R.id.tvRouteSummary);
            }
        }
    }
}
