package com.drateor.diabeticscan;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private Identity connectedIdentity;
    private CameraHandlerPrincipal cameraHandler;
    private final LinkedBlockingQueue<FrameDataHolder> framesBuffer = new LinkedBlockingQueue<>(21);
    private final UsbPermissionHandler usbPermissionHandler = new UsbPermissionHandler();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean cameraFound = false;
    private AlertDialog discoveryDialog;
    private Bitmap ultimoMsxBitmap;

    private TextView informacion;
    private ImageView msxImage, thermalScale, imagenCapturada;
    private Button connectButton, disconnectButton, nucButton, captureButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layout_main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ThermalSdkAndroid.init(getApplicationContext(), ThermalLog.LogLevel.DEBUG);
        cameraHandler = new CameraHandlerPrincipal(this);

        initUI();
        showInitialDialog();
    }

    private void initUI() {
        msxImage = findViewById(R.id.image_msx);
        thermalScale = findViewById(R.id.image_thermal_scale);
        imagenCapturada = findViewById(R.id.image_thumbnail);
        informacion = findViewById(R.id.text_info);

        connectButton = findViewById(R.id.button_connect);
        disconnectButton = findViewById(R.id.button_disconnect);
        nucButton = findViewById(R.id.button_nuc);
        captureButton = findViewById(R.id.button_capture);

        connectButton.setEnabled(false);
        disconnectButton.setEnabled(false);
        nucButton.setEnabled(false);
        captureButton.setEnabled(false);

        captureButton.setOnClickListener(v -> snapShotImage());
    }

    @Override
    protected void onStop() {
        super.onStop();
        Toast.makeText(this, "Cerrando conexión...", Toast.LENGTH_SHORT).show();
        disconnect();
    }

    private void showInitialDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Conectar cámara")
                .setMessage("Conecte su cámara FLIR y presione Aceptar.")
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
                .setMessage("Esperando dispositivos FLIR...")
                .setCancelable(false)
                .create();
        discoveryDialog.show();

        mainHandler.postDelayed(() -> {
            if (!cameraFound) {
                if (discoveryDialog.isShowing()) discoveryDialog.dismiss();
                showNoCameraFoundDialog();
            } else {
                stopDiscovery();
            }
        }, 10000);
    }

    private void showNoCameraFoundDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Cámara no encontrada")
                .setMessage("¿Desea reintentar la búsqueda?")
                .setCancelable(false)
                .setPositiveButton("Reintentar", (dialog, which) -> {
                    cameraFound = false;
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
            Toast.makeText(this, "No se puede conectar: cámara no disponible", Toast.LENGTH_SHORT).show();
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
        public void error(ErrorType errorType, @NonNull Identity identity) {
            Toast.makeText(MainActivity.this, "Error de permisos USB", Toast.LENGTH_SHORT).show();
        }
    };

    private void doConnect(Identity identity) {
        new Thread(() -> {
            try {
                cameraHandler.connect(identity, errorCode ->
                        runOnUiThread(() -> informacion.setText("Desconectado (" + errorCode + ")")));
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
                captureButton.setEnabled(true);
                informacion.setText("Cámara detectada");
            });
        }

        @Override
        public void onDiscoveryError(CommunicationInterface comm, ErrorCode errorCode) {
            stopDiscovery();
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Error en la búsqueda: " + errorCode, Toast.LENGTH_LONG).show());
        }
    };

    private final CameraHandlerPrincipal.StreamDataListener streamDataListener = (msxBitmap, dcBitmap, info) -> {
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
                thermalScale.setImageBitmap(poll.dcBitmap);
                informacion.setText(poll.inforacion);
            }
        });
    };

    private void snapShotImage() {
        if (ultimoMsxBitmap == null) {
            Toast.makeText(this, "No hay imagen para guardar", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // Guardar en archivo temporal
            File file = new File(getCacheDir(), "captured.jpg");
            FileOutputStream out = new FileOutputStream(file);
            ultimoMsxBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            out.flush();
            out.close();

            // Pasar la URI al InferenceActivity
            Intent intent = new Intent(this, InferenceActivity.class);
            intent.putExtra("imageUri", Uri.fromFile(file).toString());
            startActivity(intent);

        } catch (IOException e) {
            Toast.makeText(this, "Error al guardar imagen temporal", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Imagen guardada", Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, InferenceActivity.class);
            intent.putExtra("capturedImage", bitmap);
            startActivity(intent);

        } catch (IOException e) {
            Toast.makeText(this, "Error al guardar imagen", Toast.LENGTH_SHORT).show();
        }
    }

}
