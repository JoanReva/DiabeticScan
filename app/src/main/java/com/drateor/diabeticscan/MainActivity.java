package com.drateor.diabeticscan;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.flir.thermalsdk.ErrorCode;
import com.flir.thermalsdk.androidsdk.ThermalSdkAndroid;
import com.flir.thermalsdk.androidsdk.live.connectivity.UsbPermissionHandler;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.discovery.DiscoveredCamera;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.log.ThermalLog;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private Identity connectedIdentity = null;

    private CameraHandlerPrincipal cameraHandler;
    private final LinkedBlockingQueue<FrameDataHolder> framesBuffer = new LinkedBlockingQueue<>(21);
    private final UsbPermissionHandler usbPermissionHandler = new UsbPermissionHandler();
    private AlertDialog discoveryDialog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean cameraFound = false;
    private Bitmap ultimoMsxBitmap;

    private TextView informacion;
    private ImageView msxImage, thermal_scale, imagenCapturada;
    private Button connectButton, disconnectButton, nucButton, capturaButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ThermalSdkAndroid.init(getApplicationContext(), ThermalLog.LogLevel.DEBUG);


        cameraHandler = new CameraHandlerPrincipal(this);

        msxImage = findViewById(R.id.msx_image);
        thermal_scale = findViewById(R.id.thermal_scale);
        imagenCapturada = findViewById(R.id.imagen_capturada);
        informacion = findViewById(R.id.informacion);
        connectButton = findViewById(R.id.connect_flir_one);
        disconnectButton = findViewById(R.id.disconnect_flir_one);
        nucButton = findViewById(R.id.nuc);
        capturaButton = findViewById(R.id.captura);

        capturaButton.setOnClickListener(v -> snapShotImage());

        connectButton.setEnabled(false);
        disconnectButton.setEnabled(false);
        nucButton.setEnabled(false);
        capturaButton.setEnabled(false);

        showInitialDialog();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Toast.makeText(this, "La aplicación está en segundo plano. Cerrando conexión.", Toast.LENGTH_SHORT).show();
        disconnect();
    }

    private void showInitialDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Conectar cámara")
                .setMessage("Por favor, conecte su cámara FLIR al dispositivo y presione Aceptar para continuar.")
                .setCancelable(false)
                .setPositiveButton("Aceptar", (dialog, which) -> {
                    dialog.dismiss();
                    startDiscovery();
                    showDiscoveryDialog();
                })
                .show();
    }

    private void showDiscoveryDialog() {
        discoveryDialog = new AlertDialog.Builder(this)
                .setTitle("Buscando cámaras...")
                .setMessage("Por favor, espere mientras buscamos dispositivos FLIR conectados.")
                .setCancelable(false)
                .create();
        discoveryDialog.show();

        mainHandler.postDelayed(() -> {
            if (!cameraFound) {
                if (discoveryDialog.isShowing()) discoveryDialog.dismiss();
                showNoCameraFoundDialog();
            } else {
                stopDiscovery();
                if (discoveryDialog.isShowing()) discoveryDialog.dismiss();
            }
        }, 10_000);
    }

    private void showNoCameraFoundDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Cámara no encontrada")
                .setMessage("No se detectó ninguna cámara conectada.\n¿Desea reintentar la búsqueda?")
                .setCancelable(false)
                .setPositiveButton("Reintentar", (dialog, which) -> {
                    cameraFound = false;
                    dialog.dismiss();
                    startDiscovery();
                    showDiscoveryDialog();
                }).show();
    }

    private void startDiscovery() {
        cameraHandler.startDiscovery(cameraDiscoveryListener, discoveryStatusListener);
    }

    private void stopDiscovery() {
        cameraHandler.stopDiscovery(discoveryStatusListener);
    }

    public void connectFlirOne(View view) {
        connect(cameraHandler.getFlirOne());
    }

    public void disconnect(View view) {
        disconnect();
    }

    public void performNuc(View view) {
        cameraHandler.performNuc();
    }

    private void connect(Identity identity) {
        stopDiscovery();
        if (connectedIdentity != null || identity == null) {
            Toast.makeText(this, "No se puede conectar: cámara ocupada o no disponible", Toast.LENGTH_SHORT).show();
            return;
        }

        connectedIdentity = identity;

        if (UsbPermissionHandler.isFlirOne(identity)) {
            usbPermissionHandler.requestFlirOnePermisson(identity, this, permissionListener);
        } else {
            doConnect(identity);
        }
    }

    private final UsbPermissionHandler.UsbPermissionListener permissionListener = new UsbPermissionHandler.UsbPermissionListener() {
        @Override
        public void permissionGranted(@NonNull Identity identity) {
            doConnect(identity);
        }

        @Override
        public void permissionDenied(@NonNull Identity identity) {
            Toast.makeText(MainActivity.this, "Permiso denegado", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void error(UsbPermissionHandler.UsbPermissionListener.ErrorType errorType, @NonNull Identity identity) {
            Toast.makeText(MainActivity.this, "Error al pedir permiso", Toast.LENGTH_SHORT).show();
        }
    };

    private void doConnect(Identity identity) {
        new Thread(() -> {
            try {
                cameraHandler.connect(identity, errorCode -> runOnUiThread(() ->
                        informacion.setText("Desconectado (" + errorCode + ")")));
                runOnUiThread(() -> informacion.setText("Conectado: " + identity.deviceId));
                cameraHandler.startStream(streamDataListener);
            } catch (IOException e) {
                runOnUiThread(() -> informacion.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private void disconnect() {
        connectedIdentity = null;
        new Thread(() -> {
            cameraHandler.disconnect();
            runOnUiThread(() -> informacion.setText("Desconectado"));
        }).start();
    }

    private final CameraHandlerPrincipal.DiscoveryStatus discoveryStatusListener = new CameraHandlerPrincipal.DiscoveryStatus() {
        @Override
        public void started() {
            runOnUiThread(() -> informacion.setText("Buscando cámaras..."));
        }

        @Override
        public void stopped() {
            runOnUiThread(() -> informacion.setText("Búsqueda finalizada"));
        }
    };

    private final DiscoveryEventListener cameraDiscoveryListener = new DiscoveryEventListener() {
        @Override
        public void onCameraFound(DiscoveredCamera discoveredCamera) {
            cameraHandler.add(discoveredCamera.getIdentity());
            cameraFound = true;
            runOnUiThread(() -> {
                if (discoveryDialog.isShowing()) discoveryDialog.dismiss();
                connectButton.setEnabled(true);
                disconnectButton.setEnabled(true);
                nucButton.setEnabled(true);
                capturaButton.setEnabled(true);
                informacion.setText("Cámara detectada");
            });
        }

        @Override
        public void onDiscoveryError(CommunicationInterface comm, ErrorCode errorCode) {
            stopDiscovery();
            if (discoveryDialog.isShowing()) discoveryDialog.dismiss();
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Error de descubrimiento: " + errorCode, Toast.LENGTH_LONG).show());
        }
    };

    private final CameraHandlerPrincipal.StreamDataListener streamDataListener = new CameraHandlerPrincipal.StreamDataListener() {
        @Override
        public void images(Bitmap msxBitmap, Bitmap dcBitmap, String info) {
            try {
                framesBuffer.put(new FrameDataHolder(msxBitmap, dcBitmap, info));
            } catch (InterruptedException e) {
                Log.e(TAG, "Buffer lleno", e);
            }

            runOnUiThread(() -> {
                FrameDataHolder poll = framesBuffer.poll();
                if (poll != null) {
                    ultimoMsxBitmap = poll.msxBitmap;
                    msxImage.setImageBitmap(poll.msxBitmap);
                    thermal_scale.setImageBitmap(poll.dcBitmap);
                    informacion.setText(poll.inforacion);
                }
            });
        }

        @Override
        public void images(FrameDataHolder dataHolder) {
            runOnUiThread(() -> {
                ultimoMsxBitmap = dataHolder.msxBitmap;
                msxImage.setImageBitmap(dataHolder.msxBitmap);
                thermal_scale.setImageBitmap(dataHolder.dcBitmap);
                informacion.setText(dataHolder.inforacion);
            });
        }
    };

    private void snapShotImage() {
        if (ultimoMsxBitmap != null) {
            guardarBitmapEnGaleria(ultimoMsxBitmap);
            imagenCapturada.setVisibility(View.VISIBLE);
            imagenCapturada.setImageBitmap(ultimoMsxBitmap);
        } else {
            Toast.makeText(this, "No hay imagen para guardar", Toast.LENGTH_SHORT).show();
        }
    }

    private void guardarBitmapEnGaleria(Bitmap bitmap) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "flir_msx_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FLIR_Imagenes");

        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        try (OutputStream outputStream = resolver.openOutputStream(uri)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
            if (outputStream != null) outputStream.flush();
            Toast.makeText(this, "Imagen guardada en Galería", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Error al guardar imagen", Toast.LENGTH_SHORT).show();
        }
    }
}
