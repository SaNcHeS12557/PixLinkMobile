package com.example.pixlinkmobile;

// listens ingoing clipboard data
// encoding/decoding clipboard data
// applies copied data to clipboard

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

public class ClipboardSyncManager {
    private static final String TAG = "ClipboardSyncManager";
    private Context context;

    public ClipboardSyncManager(Context context) {
        this.context = context;
    }

    public void handleIncomingData(String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);
            String type = json.optString("type");

            if("clipboard".equals(type)) {
                Log.d(TAG, "Handling clipboard upd");
                String dataType = json.optString("dataType");
                String data = json.optString("data");

                if("text".equals(dataType)) {
                    copyTextToClipboard(data);
                } else if("image_png".equals(dataType)) {
                    copyImageToClipboard(data);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing clipboard JSON", e);
        }
    }

    private void copyTextToClipboard(String data) {
        if(context == null || data == null) return;

        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard != null) {
            ClipData clip = ClipData.newPlainText("text", data);
            clipboard.setPrimaryClip(clip);
            Log.d(TAG, "Copied text to clipboard: " + data);
            Toast.makeText(context, "Text copied from PC", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyImageToClipboard(String data) {
        // data(image) is base64 encoded png string
        if(context == null || data == null) return;

        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard != null) {
            try {
                byte[] imageBytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

                File cachePath = new File(context.getCacheDir(), "images/");
                cachePath.mkdirs();

                File imageFile = new File(cachePath, "shared_image.png");
                FileOutputStream stream = new FileOutputStream(imageFile);

                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                stream.close();

                Uri contentUri = FileProvider.getUriForFile(context,
                        context.getPackageName() + ".fileprovider",
                        imageFile);

                ClipData clip = ClipData.newUri(context.getContentResolver(), "image", contentUri);
                clipboard.setPrimaryClip(clip);

                Log.d(TAG, "Copied image to clipboard");
                Toast.makeText(context, "Image copied from PC", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e("Clipboard", "Failed to copy image to clipboard", e);
            }
        }
    }
}