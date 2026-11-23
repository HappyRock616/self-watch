/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.link.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import app.selfwatch.link.ILinkCallback;
import app.selfwatch.link.ILinkService;

/**
 * Lets a phone app exchange messages with its own Connect IQ watch app through Self Watch.
 *
 * <p>The steps match a typical watch-companion toolkit: {@link #connect connect}, list
 * {@link #devices devices}, check the watch app with {@link #appInfo appInfo}, {@link #send send} with
 * a delivery result, receive with {@link #onMessage onMessage}, and {@link #openApp openApp}.
 * Messages are {@code null}, {@link Boolean}, numbers, strings, {@link List} and {@link Map} values
 * (see {@link LinkCodec}).
 *
 * <p>Threading: every method may be called from any thread and never blocks on Self Watch. Every
 * callback runs on the main thread. Self Watch asks the user to approve each phone app for each watch
 * app the first time; until then calls report {@link SendStatus#PENDING_APPROVAL}.
 *
 * <p>The link reconnects by itself when Self Watch restarts: {@link Listener#onUnavailable} reports
 * {@link Reason#DISCONNECTED}, then {@link Listener#onReady} follows when it is back. Message handlers
 * stay registered across reconnects. Call {@link #close()} when done (for example in
 * {@code onDestroy}).
 */
public final class SelfWatchLink {
    /** Self Watch's package name. */
    public static final String SELF_WATCH_PACKAGE = "app.selfwatch";
    /** The bind action of Self Watch's link service. */
    public static final String ACTION_BIND = "app.selfwatch.link.BIND";
    /** The {@code ILinkService} version this client was written for. */
    public static final int CLIENT_API_VERSION = 1;

    private static final String TAG = "SelfWatchLink";
    static final long FIRST_RETRY_MS = 1_000L;
    static final long MAX_RETRY_MS = 60_000L;

    /** Why the link is not usable. */
    public enum Reason {
        /** Self Watch is not installed, or too old to have the link service. */
        NOT_INSTALLED,
        /** Self Watch refused the connection (link turned off, or this app is blocked). */
        DENIED,
        /** Self Watch stopped or restarted; the link reconnects by itself. */
        DISCONNECTED
    }

    /** Connection state callbacks. Both run on the main thread. */
    public interface Listener {
        /** The link is connected; calls can be made. Called again after each reconnect. */
        void onReady();

        /**
         * The link is not usable. After {@link Reason#DISCONNECTED} it reconnects by itself; after
         * {@link Reason#NOT_INSTALLED} or {@link Reason#DENIED} call {@link #close()} and
         * {@link #connect} again later (for example when the user returns to the app).
         */
        void onUnavailable(Reason reason);
    }

    /** Result of a query. {@code value} is non-null only when {@code status} is {@link SendStatus#SUCCESS}. */
    public interface ResultCallback<T> {
        /** Called once on the main thread. */
        void onResult(SendStatus status, T value);
    }

    /** Result of {@link #send} or {@link #openApp}. */
    public interface StatusCallback {
        /** Called once on the main thread. */
        void onResult(SendStatus status);
    }

    /** Receives messages from one watch app. */
    public interface MessageHandler {
        /**
         * Called on the main thread for each message.
         *
         * @param device the watch that sent it.
         * @param message the decoded message (see {@link LinkCodec} for the Java types).
         */
        void onMessage(LinkDevice device, Object message);
    }

    /** Watches connecting and disconnecting. */
    public interface DeviceStatusListener {
        /** Called on the main thread. */
        void onDeviceStatus(LinkDevice device, boolean connected);
    }

    private enum State { BINDING, READY, DISCONNECTED, RETRY_WAIT, FAILED, CLOSED }

    private final Context context;
    private final Listener listener;
    private final Handler main;
    private final Executor io;
    private final ExecutorService ownedIo;
    private final ServiceConnection connection = new Connection();
    private final CallbackStub callback = new CallbackStub();
    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private final Runnable rebind = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                if (state != State.RETRY_WAIT) {
                    return;
                }
                state = State.BINDING;
            }
            bind();
        }
    };

    private final Object lock = new Object();
    // Guarded by lock.
    private State state = State.BINDING;
    private ILinkService service;
    private boolean bound;
    private long retryDelayMs = FIRST_RETRY_MS;
    private final Map<String, MessageHandler> handlers = new LinkedHashMap<>();
    private final Map<Integer, StatusCallback> pendingSends = new HashMap<>();
    private final Map<Long, LinkDevice> knownDevices = new HashMap<>();
    private DeviceStatusListener deviceStatusListener;

    private SelfWatchLink(Context context, Listener listener, Handler main, Executor io, ExecutorService ownedIo) {
        this.context = context;
        this.listener = listener;
        this.main = main;
        this.io = io;
        this.ownedIo = ownedIo;
    }

    /**
     * Starts connecting to Self Watch. The result arrives through {@code listener}. Keep the returned object
     * and call {@link #close()} when done.
     *
     * @param context any context; the application context is kept.
     * @param listener told when the link is ready or unavailable.
     * @return the link, connecting.
     */
    public static SelfWatchLink connect(Context context, Listener listener) {
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, TAG);
            t.setDaemon(true);
            return t;
        });
        return connect(context, listener, ex, ex);
    }

    /** For tests: runs binder calls on {@code io}. */
    static SelfWatchLink connect(Context context, Listener listener, Executor io, ExecutorService ownedIo) {
        if (context == null || listener == null) {
            throw new NullPointerException("context and listener are required");
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        SelfWatchLink link = new SelfWatchLink(app, listener, new Handler(Looper.getMainLooper()), io, ownedIo);
        link.bind();
        return link;
    }

    /** Whether Self Watch is installed (needs the library's {@code <queries>} entry on API 30+). */
    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SELF_WATCH_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Whether the link is connected now. */
    public boolean isReady() {
        synchronized (lock) {
            return state == State.READY;
        }
    }

    /** Lists the watches Self Watch knows. On failure the list is empty. */
    public void devices(final ResultCallback<List<LinkDevice>> callback) {
        final ILinkService s = readyService();
        if (s == null) {
            post(() -> callback.onResult(SendStatus.UNAVAILABLE, Collections.<LinkDevice>emptyList()));
            return;
        }
        execute(() -> {
            SendStatus status;
            List<LinkDevice> out = new ArrayList<>();
            try {
                List<String> json = s.devices();
                if (json != null) {
                    for (String j : json) {
                        LinkDevice d = LinkDevice.fromJson(j);
                        if (d != null) {
                            out.add(d);
                        } else {
                            Log.w(TAG, "Ignoring malformed device entry");
                        }
                    }
                }
                synchronized (lock) {
                    for (LinkDevice d : out) {
                        knownDevices.put(d.getId(), d);
                    }
                }
                status = SendStatus.SUCCESS;
            } catch (RemoteException | RuntimeException e) {
                status = failure("devices", e);
            }
            final SendStatus st = status;
            final List<LinkDevice> result = st == SendStatus.SUCCESS
                    ? Collections.unmodifiableList(out) : Collections.<LinkDevice>emptyList();
            post(() -> callback.onResult(st, result));
        });
    }

    /** Asks whether {@code appUuid} is installed on {@code device}, and its version. */
    public void appInfo(final LinkDevice device, final UUID appUuid, final ResultCallback<AppInfo> callback) {
        final ILinkService s = readyService();
        if (s == null) {
            post(() -> callback.onResult(SendStatus.UNAVAILABLE, null));
            return;
        }
        execute(() -> {
            SendStatus status;
            AppInfo[] out = new AppInfo[1];
            try {
                status = AppInfo.parse(s.appInfo(device.getId(), key(appUuid)), out);
            } catch (RemoteException | RuntimeException e) {
                status = failure("appInfo", e);
            }
            final SendStatus st = status;
            final AppInfo info = st == SendStatus.SUCCESS ? out[0] : null;
            post(() -> callback.onResult(st, info));
        });
    }

    /**
     * Sends {@code message} to watch app {@code appUuid} on {@code device}. {@code onResult} gets {@link SendStatus#SUCCESS}
     * once the watch confirms delivery, or the reason it failed. Self Watch sends one message per
     * watch app at a time, in order.
     *
     * @param message {@code null}, Boolean, a number, a string, a List or a Map of those.
     */
    public void send(final LinkDevice device, final UUID appUuid, Object message, final StatusCallback onResult) {
        final byte[] bytes;
        try {
            bytes = LinkCodec.encode(message);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Message can't be encoded: " + e.getMessage());
            post(() -> onResult.onResult(SendStatus.INVALID_MESSAGE));
            return;
        }
        final ILinkService s = readyService();
        if (s == null) {
            post(() -> onResult.onResult(SendStatus.UNAVAILABLE));
            return;
        }
        final int requestId = nextRequestId.getAndIncrement();
        synchronized (lock) {
            pendingSends.put(requestId, onResult);
        }
        execute(() -> {
            SendStatus status;
            try {
                status = SendStatus.fromCode(s.send(device.getId(), key(appUuid), bytes, requestId));
            } catch (RemoteException | RuntimeException e) {
                status = failure("send", e);
            }
            if (status != SendStatus.SUCCESS) {
                completeSend(requestId, status);
            }
        });
    }

    /** Asks {@code device} to open watch app {@code appUuid}. SUCCESS means the request reached Self Watch's queue. */
    public void openApp(final LinkDevice device, final UUID appUuid, final StatusCallback onResult) {
        final ILinkService s = readyService();
        if (s == null) {
            post(() -> onResult.onResult(SendStatus.UNAVAILABLE));
            return;
        }
        execute(() -> {
            SendStatus status;
            try {
                status = SendStatus.fromCode(s.openApp(device.getId(), key(appUuid)));
            } catch (RemoteException | RuntimeException e) {
                status = failure("openApp", e);
            }
            final SendStatus st = status;
            post(() -> onResult.onResult(st));
        });
    }

    /**
     * Receives messages from watch app {@code appUuid}. One handler per watch app; a new one replaces the
     * old, and {@code null} removes it. Handlers stay registered across reconnects.
     */
    public void onMessage(final UUID appUuid, MessageHandler handler) {
        final String k = key(appUuid);
        final ILinkService s;
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            boolean had = handlers.containsKey(k);
            if (handler == null) {
                handlers.remove(k);
            } else {
                handlers.put(k, handler);
            }
            s = state == State.READY ? service : null;
            if (s == null || (had && handler != null)) {
                return; // registered on (re)connect, or already registered with the service
            }
        }
        execute(() -> {
            try {
                if (handler == null) {
                    s.unregister(k);
                } else {
                    s.register(k, callback);
                }
            } catch (RemoteException | RuntimeException e) {
                failure(handler == null ? "unregister" : "register", e);
            }
        });
    }

    /** Sets (or clears, with {@code null}) the listener for watches connecting and disconnecting. */
    public void setDeviceStatusListener(DeviceStatusListener l) {
        synchronized (lock) {
            deviceStatusListener = l;
        }
    }

    /**
     * Unregisters all handlers and disconnects. Pending sends report {@link SendStatus#UNAVAILABLE}.
     * The object can't be reused; call {@link #connect} for a new link.
     */
    public void close() {
        final ILinkService s;
        final List<String> uuids;
        final boolean wasBound;
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            s = service;
            service = null;
            uuids = new ArrayList<>(handlers.keySet());
            handlers.clear();
            wasBound = bound;
            bound = false;
            deviceStatusListener = null;
        }
        main.removeCallbacks(rebind);
        failPendingSends();
        execute(() -> {
            if (s != null) {
                for (String k : uuids) {
                    try {
                        s.unregister(k);
                    } catch (RemoteException | RuntimeException e) {
                        failure("unregister", e);
                    }
                }
            }
            if (wasBound) {
                unbindQuietly();
            }
        });
        if (ownedIo != null) {
            ownedIo.shutdown();
        }
    }

    // ---- connection state machine ----

    private void bind() {
        Intent intent = new Intent(ACTION_BIND).setPackage(SELF_WATCH_PACKAGE);
        boolean ok;
        try {
            ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            Log.w(TAG, "Self Watch refused the bind: " + e.getMessage());
            unbindQuietly();
            fail(Reason.DENIED);
            return;
        }
        if (!ok) {
            unbindQuietly(); // the platform asks for unbind even when bind fails
            fail(Reason.NOT_INSTALLED);
            return;
        }
        boolean closedMeanwhile;
        synchronized (lock) {
            closedMeanwhile = state == State.CLOSED;
            if (!closedMeanwhile) {
                bound = true;
            }
        }
        if (closedMeanwhile) {
            unbindQuietly();
        }
    }

    private void fail(Reason reason) {
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.FAILED;
            service = null;
            bound = false;
        }
        failPendingSends();
        post(() -> {
            if (!isClosed()) {
                listener.onUnavailable(reason);
            }
        });
    }

    private void onConnected(IBinder binder) {
        final ILinkService s = ILinkService.Stub.asInterface(binder);
        synchronized (lock) {
            if (state == State.CLOSED || state == State.FAILED || s == null) {
                return;
            }
            service = s;
            state = State.BINDING;
        }
        execute(() -> setUp(s));
    }

    /** Checks the version and re-registers handlers, then reports ready. Runs on {@code io}. */
    private void setUp(ILinkService s) {
        try {
            int v = s.apiVersion();
            if (v < 1) {
                Log.w(TAG, "Self Watch link API " + v + " is not supported");
                fail(Reason.NOT_INSTALLED);
                return;
            }
        } catch (RemoteException | RuntimeException e) {
            failure("apiVersion", e);
            return; // onServiceDisconnected follows if the service died
        }
        List<String> uuids;
        synchronized (lock) {
            if (service != s) {
                return;
            }
            uuids = new ArrayList<>(handlers.keySet());
        }
        for (String k : uuids) {
            try {
                s.register(k, callback);
            } catch (RemoteException | RuntimeException e) {
                failure("register", e);
            }
        }
        synchronized (lock) {
            if (service != s || state != State.BINDING) {
                return;
            }
            state = State.READY;
            retryDelayMs = FIRST_RETRY_MS;
        }
        post(() -> {
            if (isReady()) {
                listener.onReady();
            }
        });
    }

    private void onDisconnected() {
        boolean report;
        synchronized (lock) {
            if (state == State.CLOSED || state == State.FAILED || state == State.RETRY_WAIT) {
                return;
            }
            report = state == State.READY;
            service = null;
            state = State.DISCONNECTED;
        }
        failPendingSends();
        if (report) {
            post(() -> {
                if (!isClosed()) {
                    listener.onUnavailable(Reason.DISCONNECTED);
                }
            });
        }
    }

    /** The binding is gone for good (API 26+): unbind and bind again after a growing delay. */
    private void onBindingDied() {
        onDisconnected();
        long delay;
        synchronized (lock) {
            if (state == State.CLOSED || state == State.FAILED) {
                return;
            }
            state = State.RETRY_WAIT;
            bound = false;
            delay = retryDelayMs;
            retryDelayMs = Math.min(retryDelayMs * 2, MAX_RETRY_MS);
        }
        unbindQuietly();
        main.postDelayed(rebind, delay);
    }

    private final class Connection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            onConnected(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            onDisconnected();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            SelfWatchLink.this.onBindingDied();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG, "Self Watch returned no link binder");
            unbindQuietly();
            fail(Reason.DENIED);
        }
    }

    // ---- calls from Self Watch (binder threads) ----

    private final class CallbackStub extends ILinkCallback.Stub {
        @Override
        public void onMessage(long deviceId, String appUuid, byte[] message) {
            final Object value;
            try {
                value = LinkCodec.decode(message);
            } catch (LinkCodecException e) {
                Log.w(TAG, "Dropping malformed message: " + e.getMessage());
                return;
            }
            final String k = appUuid == null ? "" : appUuid.toLowerCase(Locale.ROOT);
            final LinkDevice device = deviceFor(deviceId);
            post(() -> {
                MessageHandler h;
                synchronized (lock) {
                    h = state == State.CLOSED ? null : handlers.get(k);
                }
                if (h != null) {
                    h.onMessage(device, value);
                }
            });
        }

        @Override
        public void onSendResult(int requestId, int status) {
            completeSend(requestId, SendStatus.fromCode(status));
        }

        @Override
        public void onDeviceStatus(long deviceId, boolean connected) {
            final LinkDevice d;
            synchronized (lock) {
                LinkDevice old = knownDevices.get(deviceId);
                d = new LinkDevice(deviceId, old == null ? "" : old.getName(), connected);
                knownDevices.put(deviceId, d);
            }
            post(() -> {
                DeviceStatusListener l;
                synchronized (lock) {
                    l = deviceStatusListener;
                }
                if (l != null) {
                    l.onDeviceStatus(d, connected);
                }
            });
        }
    }

    // ---- helpers ----

    private ILinkService readyService() {
        synchronized (lock) {
            return state == State.READY ? service : null;
        }
    }

    private boolean isClosed() {
        synchronized (lock) {
            return state == State.CLOSED;
        }
    }

    private LinkDevice deviceFor(long id) {
        synchronized (lock) {
            LinkDevice d = knownDevices.get(id);
            return d != null ? d : new LinkDevice(id, "", true);
        }
    }

    private void completeSend(int requestId, final SendStatus status) {
        final StatusCallback cb;
        synchronized (lock) {
            cb = pendingSends.remove(requestId);
        }
        if (cb != null) {
            post(() -> cb.onResult(status));
        }
    }

    private void failPendingSends() {
        final List<StatusCallback> cbs;
        synchronized (lock) {
            cbs = new ArrayList<>(pendingSends.values());
            pendingSends.clear();
        }
        for (StatusCallback cb : cbs) {
            post(() -> cb.onResult(SendStatus.UNAVAILABLE));
        }
    }

    private void unbindQuietly() {
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException e) {
            // Not bound (bind failed or already unbound): nothing to undo.
            Log.d(TAG, "unbind: not bound");
        }
    }

    private static SendStatus failure(String call, Exception e) {
        if (e instanceof SecurityException) {
            Log.w(TAG, call + " denied: " + e.getMessage());
            return SendStatus.DENIED;
        }
        Log.w(TAG, call + " failed: " + e);
        return SendStatus.UNAVAILABLE;
    }

    private void post(Runnable r) {
        main.post(r);
    }

    private static String key(UUID uuid) {
        if (uuid == null) {
            throw new NullPointerException("appUuid is required");
        }
        return uuid.toString(); // lower case, canonical form
    }

    /** Runs a binder call off the caller's thread. After {@link #close()} the work is dropped. */
    private void execute(Runnable r) {
        try {
            io.execute(r);
        } catch (RejectedExecutionException e) {
            Log.d(TAG, "Link closed; call dropped");
        }
    }
}
