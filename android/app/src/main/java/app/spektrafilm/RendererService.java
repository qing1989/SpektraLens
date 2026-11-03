// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
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
      OPEN_FILE = 10;
  private final Object engineLock = new Object();
  private long engine;
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
                    openRasterFile(args.getString("path"), args.getString("params"));
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
                        NativeEngine.renderEdited(
                            engine,
                            args.getString("params"),
                            args.getString("edits"),
                            args.getBoolean("full")));
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

  private void openRasterFile(String path, String params) throws java.io.IOException {
    Bitmap bitmap =
        ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(new File(path)),
            (decoder, info, source) -> {
              decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
              decoder.setMutableRequired(true);
              decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            });
    try {
      if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
        Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        if (converted == null) throw new java.io.IOException("无法转换原照片");
        bitmap.recycle();
        bitmap = converted;
      }
      NativeEngine.open(engine, bitmap, params);
    } finally {
      bitmap.recycle();
    }
  }

  // No blocking native cleanup on the service's UI thread. The process owns all these resources.
  public void onDestroy() {
    super.onDestroy();
    Process.killProcess(Process.myPid());
  }
}
