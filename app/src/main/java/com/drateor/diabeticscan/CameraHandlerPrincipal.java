package com.drateor.diabeticscan;

import static android.content.ContentValues.TAG;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.widget.TextView;

import com.flir.thermalsdk.androidsdk.image.BitmapAndroid;
import com.flir.thermalsdk.image.ImageColorizer;
import com.flir.thermalsdk.image.Palette;
import com.flir.thermalsdk.image.PaletteManager;
import com.flir.thermalsdk.image.fusion.FusionMode;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.CameraInformation;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.ConnectParameters;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.live.discovery.DiscoveryFactory;
import com.flir.thermalsdk.live.remote.Calibration;
import com.flir.thermalsdk.live.remote.RemoteControl;
import com.flir.thermalsdk.live.streaming.Stream;
import com.flir.thermalsdk.live.streaming.ThermalStreamer;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Objects;

/**
 * Clase encargada del manejo de la cámara FLIR.
 * Realiza conexión, descubrimiento, NUC, y streaming térmico.
 */
public class CameraHandlerPrincipal {

    public interface StreamDataListener {
        void images(Bitmap thermalImage, Bitmap thermalScale, String information);
    }

    public interface DiscoveryStatus {
        void started();

        void stopped();
    }

    private final Context context;
    private StreamDataListener streamDataListener;

    private final LinkedList<Identity> foundCameraIdentities = new LinkedList<>();
    private Camera camera;
    private Stream connectedStream;
    private ThermalStreamer streamer;

    public CameraHandlerPrincipal(Context context) {
        this.context = context;
    }

    /**
     * Inicia descubrimiento USB
     */
    public void startDiscovery(DiscoveryEventListener listener, DiscoveryStatus callback) {
        DiscoveryFactory.getInstance().scan(listener, CommunicationInterface.USB);
        callback.started();
    }

    /**
     * Detiene descubrimiento USB
     */
    public void stopDiscovery(DiscoveryStatus callback) {
        DiscoveryFactory.getInstance().stop(CommunicationInterface.USB);
        callback.stopped();
    }

    /**
     * Conecta a una cámara FLIR
     */
    public synchronized void connect(Identity identity, ConnectionStatusListener statusListener) throws IOException {
        Log.d(TAG, "Conectando a cámara: " + identity);
        camera = new Camera();
        camera.connect(identity, statusListener, new ConnectParameters());
    }

    /**
     * Desconecta cámara FLIR
     */
    public synchronized void disconnect() {
        Log.d(TAG, "Desconectando cámara...");
        if (camera == null) return;

        if (connectedStream != null && connectedStream.isStreaming()) {
            connectedStream.stop();
        }

        camera.disconnect();
        camera = null;
    }

    /**
     * Ejecuta calibración térmica NUC (Non-Uniformity Correction)
     */
    public synchronized void performNuc() {
        Log.d(TAG, "Ejecutando NUC...");
        if (camera == null) return;

        RemoteControl rc = camera.getRemoteControl();
        if (rc == null) return;

        Calibration calibration = rc.getCalibration();
        if (calibration != null) {
            calibration.nuc().executeSync();
        }
    }

    /**
     * Muestra texto desde el contexto si es una actividad
     */
    public void mostrarTextoPaletasCargadas() {
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            TextView textView = activity.findViewById(R.id.text_info);
            textView.setText("Paletas cargadas correctamente.");
        }
    }


    /**
     * Inicia el flujo de imágenes térmicas desde la cámara
     */
    public synchronized void startStream(StreamDataListener listener) {
        this.streamDataListener = listener;

        if (camera == null || !camera.isConnected()) {
            Log.e(TAG, "No se puede iniciar el stream: cámara nula o desconectada.");
            return;
        }

        connectedStream = camera.getStreams().get(0);
        if (!connectedStream.isThermal()) {
            Log.e(TAG, "Stream no es térmico.");
            return;
        }

        streamer = new ThermalStreamer(connectedStream);

        connectedStream.start(
                unused -> {
                    streamer.update();

                    final Bitmap[] thermalScaleBitmap = new Bitmap[1];
                    final String[] info = new String[1];

                    Palette palette = PaletteManager.getDefaultPalettes().get(10); // rainbow

                    streamer.withThermalImage(thermalImage -> {
                        Objects.requireNonNull(thermalImage.getFusion()).setFusionMode(FusionMode.THERMAL_ONLY);
                        thermalImage.setPalette(palette);

                        ImageColorizer colorizer = new ImageColorizer(thermalImage);
                        colorizer.setAutoScale(true);
                        colorizer.setRenderScale(true);
                        colorizer.update();

                        thermalScaleBitmap[0] = BitmapAndroid.createBitmap(
                                Objects.requireNonNull(colorizer.getScaleImage())).getBitMap();

                        info[0] = palette.toString();
                    });

                    Bitmap thermalBitmap = BitmapAndroid.createBitmap(streamer.getImage()).getBitMap();
                    streamDataListener.images(thermalBitmap, thermalScaleBitmap[0], info[0]);
                },
                error -> Log.e(TAG, "Error durante el streaming: " + error)
        );
    }

    /**
     * Agrega cámara descubierta
     */
    public void add(Identity identity) {
        foundCameraIdentities.add(identity);
    }

    /**
     * Retorna la primera cámara FLIR encontrada por USB
     */
    @Nullable
    public Identity getFlirOne() {
        return foundCameraIdentities.stream()
                .filter(identity -> identity.communicationInterface == CommunicationInterface.USB)
                .findFirst()
                .orElse(null);
    }

    /**
     * Obtiene información del dispositivo conectado
     */
    public String getDeviceInfo() {
        if (camera == null) return "No disponible";

        RemoteControl rc = camera.getRemoteControl();
        if (rc == null) return "No disponible";

        CameraInformation info = rc.cameraInformation().getSync();
        if (info == null) return "No disponible";

        return info.displayName + ", SN: " + info.serialNumber;
    }
}
