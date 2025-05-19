package com.drateor.diabeticscan;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class InferenceActivity extends AppCompatActivity {

    private ImageView imageView;
    private TextView resultText;
    private Interpreter tflite;
    private List<String> classLabels = Arrays.asList("ClaseA", "ClaseB", "ClaseC", "ClaseD");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inference);

        imageView = findViewById(R.id.image_input);
        resultText = findViewById(R.id.text_result);

        String uriString = getIntent().getStringExtra("imageUri");

        if (uriString != null) {
            try {
                Uri imageUri = Uri.parse(uriString);
                //Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(imageUri));
                //imageView.setImageBitmap(bitmap);
                Bitmap originalBitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(imageUri));
                Bitmap segmentedBitmap = segmentImage(originalBitmap);
                imageView.setImageBitmap(segmentedBitmap);
                tflite = new Interpreter(loadModelFile());
                //String result = runInference(bitmap);
                String result = runInference(segmentedBitmap);
                resultText.setText(result);

            } catch (Exception e) {
                resultText.setText("Error: " + e.getMessage());
            }
        } else {
            resultText.setText("Imagen no encontrada");
        }
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = getAssets().openFd("modelo.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY,
                fileDescriptor.getStartOffset(),
                fileDescriptor.getDeclaredLength());
    }

    private String runInference(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true);
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(resized);

        float[][] output = new float[1][classLabels.size()];
        tflite.run(inputBuffer, output);

        int maxIdx = 0;
        float maxProb = output[0][0];
        for (int i = 1; i < output[0].length; i++) {
            if (output[0][i] > maxProb) {
                maxProb = output[0][i];
                maxIdx = i;
            }
        }

        return "Clase: " + classLabels.get(maxIdx) + "\nConfianza: " + String.format(Locale.US, "%.2f%%", maxProb * 100);
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 224 * 224 * 3);
        buffer.order(ByteOrder.nativeOrder());
        int[] pixels = new int[224 * 224];
        bitmap.getPixels(pixels, 0, 224, 0, 0, 224, 224);

        for (int pixel : pixels) {
            buffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f); // R
            buffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);  // G
            buffer.putFloat((pixel & 0xFF) / 255.0f);         // B
        }

        return buffer;
    }

    private Bitmap segmentImage(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;

            float[] hsv = new float[3];
            android.graphics.Color.RGBToHSV(r, g, b, hsv);

            // Detección de azul: tono entre 180° y 250° (0.5 a 0.7 en escala 0–1)
            //boolean isBlue = hsv[0] >= 180 && hsv[0] <= 250 && hsv[1] > 0.3;
            boolean isBlue = hsv[0] >= 180 && hsv[0] <= 250 && hsv[1] > 0.3;
            //boolean isBlue = hsv[0] >= 160 && hsv[0] <= 260 && hsv[1] >= 0.2 && hsv[2] >= 0.2;

            if (isBlue) {
                pixels[i] = android.graphics.Color.BLACK;  // Fondo azul → negro
            } else {
                pixels[i] = android.graphics.Color.rgb(r, g, b);  // Conservar objeto
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }
}
