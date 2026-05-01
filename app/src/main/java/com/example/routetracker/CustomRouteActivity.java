package com.example.routetracker;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CustomRouteActivity extends AppCompatActivity {
    private static final String PREFS = "custom_routes";
    private static final String KEY_ROUTES = "routes";

    private MapView mapView;
    private Marker startMarker;
    private Marker endMarker;
    private Polyline pathLine;
    private TextView tvInfo;
    private final List<CustomRoute> routes = new ArrayList<>();
    private CustomAdapter adapter;
    private long activeStartMs = -1;
    private CustomRoute activeRoute;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_route);
        Configuration.getInstance().setUserAgentValue(getPackageName());

        mapView = findViewById(R.id.customMapView);
        tvInfo = findViewById(R.id.tvCustomInfo);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);
        mapView.getController().setCenter(new GeoPoint(19.4326, -99.1332));

        findViewById(R.id.btnCloseCustom).setOnClickListener(v -> finish());

        setupTapOverlay();
        setupButtons();
        setupRecycler();
        load();
    }

    private void setupTapOverlay() {
        mapView.getOverlays().add(new Overlay() {
            @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e, MapView map) {
                GeoPoint point = (GeoPoint) map.getProjection().fromPixels((int) e.getX(), (int) e.getY());
                if (startMarker == null) startMarker = createDraggable(point, "Inicio");
                else if (endMarker == null) endMarker = createDraggable(point, "Fin");
                else { startMarker.setPosition(point); }
                redrawLine();
                return true;
            }
        });
    }

    private Marker createDraggable(GeoPoint p, String title) {
        Marker m = new Marker(mapView);
        m.setPosition(p);
        m.setTitle(title);
        m.setDraggable(true);
        m.setOnMarkerDragListener(new Marker.OnMarkerDragListener() {
            @Override public void onMarkerDrag(Marker marker) { redrawLine(); }
            @Override public void onMarkerDragEnd(Marker marker) { redrawLine(); }
            @Override public void onMarkerDragStart(Marker marker) { }
        });
        mapView.getOverlays().add(m);
        return m;
    }

    private void redrawLine() {
        if (pathLine != null) mapView.getOverlays().remove(pathLine);
        if (startMarker != null && endMarker != null) {
            pathLine = new Polyline();
            List<GeoPoint> points = new ArrayList<>();
            points.add(startMarker.getPosition());
            points.add(endMarker.getPosition());
            pathLine.setPoints(points);
            pathLine.setWidth(10f);
            pathLine.setColor(0xFF3B82F6);
            mapView.getOverlays().add(pathLine);
            tvInfo.setText("Camino listo: puedes mover puntos y guardar");
        }
        mapView.invalidate();
    }

    private void setupButtons() {
        Button save = findViewById(R.id.btnSaveCustom);
        Button start = findViewById(R.id.btnStartCustom);
        Button finish = findViewById(R.id.btnFinishCustom);

        save.setOnClickListener(v -> {
            if (startMarker == null || endMarker == null) {
                Toast.makeText(this, "Selecciona inicio y fin", Toast.LENGTH_SHORT).show(); return;
            }
            CustomRoute r = new CustomRoute(startMarker.getPosition().getLatitude(), startMarker.getPosition().getLongitude(), endMarker.getPosition().getLatitude(), endMarker.getPosition().getLongitude());
            routes.add(0, r);
            adapter.notifyItemInserted(0);
            save();
        });

        start.setOnClickListener(v -> {
            if (activeRoute == null) {
                Toast.makeText(this, "Selecciona un camino del historial", Toast.LENGTH_SHORT).show(); return;
            }
            activeStartMs = System.currentTimeMillis();
            Toast.makeText(this, "Seguimiento de tiempo iniciado", Toast.LENGTH_SHORT).show();
        });

        finish.setOnClickListener(v -> {
            if (activeRoute == null || activeStartMs <= 0) return;
            long elapsed = System.currentTimeMillis() - activeStartMs;
            activeStartMs = -1;
            activeRoute.attempts++;
            if (activeRoute.bestMs == 0 || elapsed < activeRoute.bestMs) activeRoute.bestMs = elapsed;
            save();
            adapter.notifyDataSetChanged();
            Toast.makeText(this, "Tiempo guardado: " + formatMs(elapsed), Toast.LENGTH_SHORT).show();
        });
    }

    private void setupRecycler() {
        RecyclerView rv = findViewById(R.id.rvCustomHistory);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CustomAdapter(routes);
        rv.setAdapter(adapter);
    }

    private String formatMs(long ms) {
        long s=ms/1000, m=s/60; s%=60;
        return String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }

    private void load() {
        routes.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ROUTES, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i=0;i<arr.length();i++) routes.add(CustomRoute.from(arr.getJSONObject(i)));
        } catch (JSONException ignored) {}
        adapter.notifyDataSetChanged();
    }

    private void save() {
        JSONArray arr = new JSONArray();
        for (CustomRoute r: routes) arr.put(r.toJson());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ROUTES, arr.toString()).apply();
    }

    @Override protected void onResume() { super.onResume(); mapView.onResume(); }
    @Override protected void onPause() { super.onPause(); mapView.onPause(); }
    @Override protected void onDestroy() { super.onDestroy(); mapView.onDetach(); }

    static class CustomRoute {
        double slat, slon, elat, elon; int attempts; long bestMs;
        CustomRoute(double a,double b,double c,double d){slat=a;slon=b;elat=c;elon=d;}
        JSONObject toJson(){ JSONObject o=new JSONObject(); try{o.put("slat",slat);o.put("slon",slon);o.put("elat",elat);o.put("elon",elon);o.put("attempts",attempts);o.put("bestMs",bestMs);}catch(JSONException ignored){} return o; }
        static CustomRoute from(JSONObject o)throws JSONException{ CustomRoute r=new CustomRoute(o.getDouble("slat"),o.getDouble("slon"),o.getDouble("elat"),o.getDouble("elon")); r.attempts=o.optInt("attempts",0); r.bestMs=o.optLong("bestMs",0); return r; }
    }

    private class CustomAdapter extends RecyclerView.Adapter<CustomAdapter.VH> {
        private final List<CustomRoute> items;
        CustomAdapter(List<CustomRoute> items){this.items=items;}
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p,int v){return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_custom_route,p,false));}
        @Override public void onBindViewHolder(@NonNull VH h,int i){
            CustomRoute r=items.get(i);
            h.title.setText(String.format(Locale.getDefault(),"Ruta %.5f,%.5f -> %.5f,%.5f",r.slat,r.slon,r.elat,r.elon));
            h.sub.setText("Intentos: "+r.attempts+" · Mejor: "+(r.bestMs==0?"--":formatMs(r.bestMs)));
            h.itemView.setOnClickListener(v->{
                activeRoute = r;
                if (startMarker==null) startMarker=createDraggable(new GeoPoint(r.slat,r.slon),"Inicio"); else startMarker.setPosition(new GeoPoint(r.slat,r.slon));
                if (endMarker==null) endMarker=createDraggable(new GeoPoint(r.elat,r.elon),"Fin"); else endMarker.setPosition(new GeoPoint(r.elat,r.elon));
                redrawLine();
                mapView.getController().setCenter(startMarker.getPosition());
            });
        }
        @Override public int getItemCount(){return items.size();}
        class VH extends RecyclerView.ViewHolder{ TextView title,sub; VH(@NonNull View item){super(item);title=item.findViewById(R.id.tvCustomTitle);sub=item.findViewById(R.id.tvCustomSubtitle);} }
    }
}
