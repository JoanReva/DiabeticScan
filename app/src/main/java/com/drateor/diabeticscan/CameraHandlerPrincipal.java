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

public class CameraHandlerPrincipal {

    private StreamDataListener streamDataListener;
    private Context context;

    // Constructor para pasar el contexto (actividad)
    public CameraHandlerPrincipal(Context context) {
        this.context = context;
    }

    public interface StreamDataListener {
        void images(FrameDataHolder dataHolder);

        void images(Bitmap msxBitmap, Bitmap dcBitmap, String informacion);
    }

    // Cámaras FLIR descubiertas
    LinkedList<Identity> foundCameraIdentities = new LinkedList<>();

    // Cámara FLIR conectada
    private Camera camera;
    private Stream connectedStream;
    private ThermalStreamer streamer;

    public interface DiscoveryStatus {
        void started();

        void stopped();
    }

    /**
     * Inicia la búsqueda de dispositivos USB y emuladores
     */
    public void startDiscovery(DiscoveryEventListener cameraDiscoveryListener, DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().scan(cameraDiscoveryListener, CommunicationInterface.USB);
        discoveryStatus.started();
    }

    /**
     * Detiene la búsqueda de dispositivos USB y emuladores
     */
    public void stopDiscovery(DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().stop(CommunicationInterface.USB);
        discoveryStatus.stopped();
    }

    /**
     * Conecta a una cámara usando su identidad
     */
    public synchronized void connect(Identity identity, ConnectionStatusListener connectionStatusListener) throws IOException {
        Log.d(TAG, "Conectando a la cámara con identidad: " + identity);
        camera = new Camera();
        camera.connect(identity, connectionStatusListener, new ConnectParameters());
    }

    /**
     * Desconecta la cámara actual
     */
    public synchronized void disconnect() {
        Log.d(TAG, "Desconectando cámara...");
        if (camera == null) {
            return;
        }
        if (connectedStream == null) {
            return;
        }

        if (connectedStream.isStreaming()) {
            connectedStream.stop();
        }
        camera.disconnect();
        camera = null;
    }

    /**
     * Ejecuta una calibración NUC (corrección de no uniformidad)
     */
    public synchronized void performNuc() {
        Log.d(TAG, "Ejecutando NUC...");
        if (camera == null) {
            return;
        }
        RemoteControl rc = camera.getRemoteControl();
        if (rc == null) {
            return;
        }
        Calibration calib = rc.getCalibration();
        if (calib == null) {
            return;
        }
        calib.nuc().executeSync();
    }

    public void actualizarTextoVista() {
        // Asegúrate de que el contexto sea una actividad
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            TextView textView = activity.findViewById(R.id.informacion);
            textView.setText("Paletas Cargadas");
        }
    }

    /**
     * Inicia el streaming de imágenes térmicas desde una FLIR ONE o un emulador
     */
    public synchronized void startStream(StreamDataListener listener) {
        this.streamDataListener = listener;
        if (camera == null || !camera.isConnected()) {
            Log.e(TAG, "Error al iniciar el stream, la cámara es nula o no está conectada");
            return;
        }
        connectedStream = camera.getStreams().get(0);
        if (connectedStream.isThermal()) {
            streamer = new ThermalStreamer(connectedStream);
        } else {
            Log.e(TAG, "Error: no hay flujo térmico disponible para esta cámara");
            return;
        }

        connectedStream.start(
                unused -> {
                    streamer.update();
                    final Bitmap[] dcBitmap = new Bitmap[1];
                    //0 iron ---Flir
                    //1 artic --- Flir
                    //2 blackhot
                    //3 bw -- Flir gris
                    //4 coldest -- Flir
                    //5 wheel_redhot
                    //6 colorwheel6
                    //7 colorwheel12
                    //8 doublerainbow2
                    //9 lava --- Flir
                    //10 rainbow ---Flir
                    //11 rainicHC
                    //12 whitehot
                    //13 Hottset -- FLir
                    final String[] info = new String[1];
                    final Bitmap[] scaleBitmap = new Bitmap[1];
                    Palette palette = PaletteManager.getDefaultPalettes().get(10);
                    streamer.withThermalImage(thermalImage -> {
                        Objects.requireNonNull(thermalImage.getFusion()).setFusionMode(FusionMode.THERMAL_ONLY);
                        //Palette inverPalette=palette.getInverted();

                        thermalImage.setPalette(palette);


                        ImageColorizer colorize = new ImageColorizer(thermalImage);
                        colorize.setAutoScale(true);
                        colorize.setRenderScale(true);
                        colorize.update();
                        scaleBitmap[0] = BitmapAndroid.createBitmap(Objects.requireNonNull(colorize.getScaleImage())).getBitMap();

                        info[0] = palette.toString();
                        //info[0]="Max:"+thermalImage.getScale().getRangeMax()+" Min:"+thermalImage.getScale().getRangeMin();
                        dcBitmap[0] = BitmapAndroid.createBitmap(
                                Objects.requireNonNull(thermalImage.getFusion().getPhoto())).getBitMap();
                    });


                    final Bitmap thermalPixels = BitmapAndroid.createBitmap(streamer.getImage()).getBitMap();
                    // Enviar las imágenes al listener
                    streamDataListener.images(thermalPixels, scaleBitmap[0], info[0]);
                },
                error -> Log.e(TAG, "Error durante el streaming: " + error)
        );

    }

    /**
     * Agrega una cámara descubierta a la lista de cámaras encontradas
     */
    public void add(Identity identity) {
        foundCameraIdentities.add(identity);
    }

    /**
     * Devuelve la primera cámara FLIR ONE conectada por USB
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
        if (camera == null) {
            return "No disponible";
        }
        RemoteControl rc = camera.getRemoteControl();
        if (rc == null) {
            return "No disponible";
        }
        CameraInformation ci = rc.cameraInformation().getSync();
        if (ci == null) {
            return "No disponible";
        }
        return ci.displayName + ", SN: " + ci.serialNumber;
    }
}
