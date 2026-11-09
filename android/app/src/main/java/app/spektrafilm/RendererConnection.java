// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import java.util.concurrent.*;

/** Binder calls have real deadlines: a timeout ends only the private renderer process. */
final class RendererConnection implements AutoCloseable {
  static final class StaleSessionException extends IllegalStateException {}

  private static final ConcurrentHashMap<String, IBinder> retiring = new ConcurrentHashMap<>();
  private final Context context;
  private final ExecutorService rpc = Executors.newSingleThreadExecutor();
  private final CountDownLatch connected = new CountDownLatch(1);
  private volatile IBinder binder;
  private volatile boolean closed, bound;
  private volatile int pid;
  final String mode;
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder value) {
          if (!closed) {
            binder = value;
            connected.countDown();
          }
        }

        public void onServiceDisconnected(ComponentName name) {
          binder = null;
          close();
        }

        public void onBindingDied(ComponentName name) {
          binder = null;
          close();
          connected.countDown();
        }

        public void onNullBinding(ComponentName name) {
          close();
          connected.countDown();
        }
      };

  RendererConnection(Context c, String mode) {
    context = c.getApplicationContext();
    this.mode = mode;
  }

  String create(String resources, long timeoutMs, long testDelay) {
    return create(resources, timeoutMs, testDelay, 0);
  }

  String create(String resources, long timeoutMs, long testDelay, long storageLimit) {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IllegalStateException("Renderer initialization belongs on worker thread");
    awaitRetiringProcess();
    synchronized (this) {
      if (closed) throw new IllegalStateException("显影进程已关闭");
      bound =
          context.bindService(
              new Intent(
                  context,
                  mode.equals("cpu") ? CompatibilityRendererService.class : RendererService.class),
              connection,
              Context.BIND_AUTO_CREATE);
    }
    try {
      if (!bound || !connected.await(8, TimeUnit.SECONDS) || binder == null)
        throw new IllegalStateException("无法连接显影进程");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("连接显影进程被中断", ex);
    }
    pid = request(RendererService.HELLO, new Bundle(), 3000).getInt("pid");
    if (pid <= 0 || pid == android.os.Process.myPid()) throw new IllegalStateException("显影进程未隔离");
    Bundle args = new Bundle();
    args.putString("resources", resources);
    args.putString("mode", mode);
    args.putLong("test_startup_delay_ms", testDelay);
    args.putLong("test_storage_limit", storageLimit);
    try {
      return request(RendererService.CREATE, args, timeoutMs).getString("text");
    } catch (IllegalStateException ex) {
      if ("stale_renderer_session".equals(ex.getMessage())) throw new StaleSessionException();
      throw ex;
    }
  }

  private void awaitRetiringProcess() {
    IBinder previous = retiring.get(mode);
    if (previous == null) return;
    CountDownLatch died = new CountDownLatch(1);
    IBinder.DeathRecipient recipient = died::countDown;
    try {
      previous.linkToDeath(recipient, 0);
      if (previous.isBinderAlive() && !died.await(3, TimeUnit.SECONDS))
        throw new IllegalStateException("旧显影进程尚未退出，可稍后重试");
    } catch (RemoteException ignored) {
      // Already dead; the next bind will create a fresh process.
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待旧显影进程退出被中断", ex);
    } finally {
      try {
        previous.unlinkToDeath(recipient, 0);
      } catch (java.util.NoSuchElementException ignored) {
      }
    }
    retiring.remove(mode, previous);
  }

  int processId() {
    return pid;
  }

  boolean isAlive() {
    IBinder value = binder;
    return !closed && value != null && value.isBinderAlive();
  }

  Bundle request(int code, Bundle args, long timeoutMs) {
    if (closed) throw new IllegalStateException("显影进程已关闭");
    IBinder target = binder;
    if (target == null) throw new IllegalStateException("显影进程已退出");
    Future<Bundle> future =
        rpc.submit(
            () -> {
              Parcel data = Parcel.obtain(), reply = Parcel.obtain();
              try {
                data.writeInterfaceToken(RendererService.DESCRIPTOR);
                data.writeBundle(args);
                if (!target.transact(code, data, reply, 0))
                  throw new IllegalStateException("显影命令未响应");
                reply.readException();
                Bundle result = reply.readBundle(Bitmap.class.getClassLoader());
                return result == null ? new Bundle() : result;
              } finally {
                data.recycle();
                reply.recycle();
              }
            });
    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      close();
      throw new IllegalStateException(
          (mode.equals("cpu") ? "兼容" : "GPU") + "显影进程响应超时（" + timeoutMs / 1000.0 + " 秒）", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      close();
      throw new IllegalStateException("显影等待被中断", ex);
    } catch (ExecutionException ex) {
      Throwable cause = ex.getCause();
      throw new IllegalStateException(cause == null ? ex.toString() : cause.getMessage(), cause);
    }
  }

  public synchronized void close() {
    if (closed) return;
    closed = true;
    int child = pid;
    IBinder live = binder;
    if (bound)
      try {
        context.unbindService(connection);
      } catch (IllegalArgumentException ignored) {
      }
    bound = false;
    if (child > 0 && child != android.os.Process.myPid() && live != null && live.isBinderAlive()) {
      retiring.put(mode, live);
      android.os.Process.killProcess(child);
    }
    binder = null;
    rpc.shutdownNow();
  }
}
