// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.os.*;
import android.os.Process;
import java.io.File;

/** A private process owns Vulkan/JIT resources. A stalled driver cannot trap the editor process. */
public class RendererService extends Service {
  static final String DESCRIPTOR = "app.spektrafilm.Renderer";
  static final int HELLO = 1,
      CREATE = 2,
      CAPABILITIES = 3,
      OPEN = 4,
      OPEN_RAW = 5,
      RAW_PREVIEW = 6,
      RENDER = 7,
      EXPORT = 8,
      CLOSE_IMAGE = 9,
      OPEN_FILE = 10,
      EDIT_BITMAP = 11;
  private final Object engineLock = new Object();
  private long engine;
  // Gallery decoding is independent of the film stock. Decode a bounded raster preview once and
  // reuse it for each reopen; NativeEngine copies the bitmap into its own session.
  private Bitmap rasterPreview;
  private String rasterPreviewKey;
  private final Binder binder =
      new Binder() {
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
          if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
          }
          data.enforceInterface(DESCRIPTOR);
          if (Binder.getCallingUid() != Process.myUid())
            throw new SecurityException("Private renderer");
          Bundle args = data.readBundle(Bitmap.class.getClassLoader());
          Bundle result = new Bundle();
          try {
            if (code == HELLO) result.putInt("pid", Process.myPid());
            else
              synchronized (engineLock) {
                switch (code) {
                  case CREATE:
                    if (engine != 0) throw new IllegalStateException("stale_renderer_session");
                    String mode = args.getString("mode", "gpu");
                    if (!mode.equals("cpu") && !mode.equals("gpu"))
                      throw new IllegalArgumentException("Unknown renderer mode");
                    if (mode.equals("cpu")) System.loadLibrary("vk_swiftshader");
                    NativeEngine.configureBackend(
                        mode,
                        getApplicationInfo().nativeLibraryDir,
                        new File(getFilesDir(), "engine-" + mode + ".json").getAbsolutePath());
                    if (mode.equals("gpu")
                        && args.getLong("test_storage_limit") > 0
                        && (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                      NativeEngine.gpuTestStorageLimit(args.getLong("test_storage_limit"));
                    long delay = args.getLong("test_startup_delay_ms");
                    if (delay > 0
                        && (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                      Thread.sleep(delay);
                    engine = NativeEngine.create(args.getString("resources"));
                    result.putString("text", NativeEngine.capabilities(engine));
                    break;
                  case CAPABILITIES:
                    result.putString("text", NativeEngine.capabilities(engine));
                    break;
                  case OPEN:
                    NativeEngine.open(
                        engine, args.getParcelable("bitmap"), args.getString("params"));
                    break;
                  case OPEN_RAW:
                    NativeEngine.openRaw(
                        engine,
                        args.getString("path"),
                        args.getString("params"),
                        args.getInt("edge"));
                    break;
                  case OPEN_FILE:
                    openRasterFile(
                        args.getString("path"),
                        args.getString("params"),
                        args.getInt("edge", 0));
                    break;
                  case RAW_PREVIEW:
                    result.putParcelable(
                        "bitmap",
                        NativeEngine.rawPreview(
                            engine, args.getString("path"), args.getInt("edge")));
                    break;
                  case RENDER:
                    result.putParcelable(
                        "bitmap",
                        resizeForTransfer(
                            NativeEngine.renderEdited(
                                engine,
                                args.getString("params"),
                                args.getString("edits"),
                                args.getBoolean("full")),
                            args.getInt("post_edge", 0)));
                    break;
                  case EXPORT:
                    result.putString(
                        "text",
                        NativeEngine.exportImage(
                            engine,
                            args.getString("params"),
                            args.getString("edits"),
                            args.getString("path"),
                            args.getString("format")));
                    break;
                  case CLOSE_IMAGE:
                    NativeEngine.closeImage(engine);
                    break;
                  case EDIT_BITMAP:
                    result.putParcelable(
                        "bitmap",
                        NativeEngine.editBitmap(
                            args.getParcelable("bitmap"), args.getString("edits")));
                    break;
                  default:
                    throw new IllegalArgumentException("Unknown renderer command");
                }
              }
            reply.writeNoException();
            reply.writeBundle(result);
          } catch (Throwable ex) {
            reply.writeException(
                new IllegalStateException(
                    ex.getMessage() == null ? ex.toString() : ex.getMessage()));
          }
          return true;
        }
      };

  public IBinder onBind(Intent intent) {
    return binder;
  }

  private void openRasterFile(String path, String params, int targetLongEdge)
      throws java.io.IOException {
    File source = new File(path);
    String cacheKey =
        targetLongEdge > 0
            ? source.getAbsolutePath()
                + ":"
                + source.length()
                + ":"
                + source.lastModified()
                + ":"
                + targetLongEdge
            : null;
    Bitmap bitmap = null;
    if (cacheKey != null
        && cacheKey.equals(rasterPreviewKey)
        && rasterPreview != null
        && !rasterPreview.isRecycled()) bitmap = rasterPreview;
    if (bitmap == null) {
      bitmap = decodeRasterPreview(source, targetLongEdge);
      if (cacheKey != null) {
        Bitmaps.release(rasterPreview);
        rasterPreview = bitmap;
        rasterPreviewKey = cacheKey;
      }
    }
    try {
      NativeEngine.open(engine, bitmap, params);
    } finally {
      if (cacheKey == null) Bitmaps.release(bitmap);
    }
  }

  private Bitmap decodeRasterPreview(File source, int targetLongEdge) throws java.io.IOException {
    Bitmap bitmap =
        ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(source),
            (decoder, info, value) -> {
              decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
              decoder.setMutableRequired(true);
              decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
              if (targetLongEdge > 0) {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                float scale = Math.min(1, targetLongEdge / (float) Math.max(width, height));
                decoder.setTargetSize(
                    Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)));
              }
            });
    if (bitmap.getConfig() == Bitmap.Config.ARGB_8888) return bitmap;
    Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, true);
    Bitmaps.release(bitmap);
    if (converted == null) throw new java.io.IOException("无法转换原照片");
    return converted;
  }

  private Bitmap resizeForTransfer(Bitmap bitmap, int targetLongEdge) {
    if (bitmap == null || targetLongEdge <= 0) return bitmap;
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();
    int longEdge = Math.max(width, height);
    if (longEdge <= targetLongEdge) return bitmap;
    float scale = targetLongEdge / (float) longEdge;
    Bitmap scaled =
        Bitmap.createScaledBitmap(
            bitmap,
            Math.max(1, Math.round(width * scale)),
            Math.max(1, Math.round(height * scale)),
            true);
    if (scaled != bitmap) Bitmaps.release(bitmap);
    return scaled;
  }

  // No blocking native cleanup on the service's UI thread. The process owns all these resources.
  public void onDestroy() {
    super.onDestroy();
    Process.killProcess(Process.myPid());
  }
}
