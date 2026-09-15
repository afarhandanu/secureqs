package io.github.qssecurity;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * QS Security for LineageOS 23 / Android 16.
 *
 * Important Android 16 detail:
 * - Legacy tiles: QSTileImpl#click/secondaryClick/longClick
 * - New QS architecture: QSTileViewModelAdapter -> QSTileViewModelImpl#onActionPerformed
 *
 * We intercept BOTH paths. The old project only intercepting QSTileImpl can therefore miss
 * migrated Android 16 tiles completely.
 */
public final class MainHook extends XposedModule {

    private static final String TAG = "QSSecurity";
    private static final long UNLOCK_WATCH_TIMEOUT_MS = 15_000L;
    private static final long UNLOCK_WATCH_INTERVAL_MS = 80L;

    private static final AtomicBoolean earlyCaptureInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean hooksInstalled = new AtomicBoolean(false);
    private static volatile Context systemUiContext;
    private static volatile Object capturedActivityStarter;

    private static volatile int cachedMode = ModuleConfig.MODE_REQUIRE_UNLOCK;
    private static volatile SharedPreferences remotePreferences;
    private static volatile long lastUnlockRequestAt = 0L;
    private static volatile long blockedShadeGestureDownTime = -1L;

    /** A replay must not immediately get intercepted by another synchronous hook layer. */
    private static final ThreadLocal<Boolean> authenticatedReplay = new ThreadLocal<>();

    private final SharedPreferences.OnSharedPreferenceChangeListener remotePreferenceListener =
            (preferences, key) -> {
                if (ModuleConfig.PREF_MODE.equals(key)) {
                    int mode = normalizeMode(preferences.getInt(
                            ModuleConfig.PREF_MODE, ModuleConfig.MODE_REQUIRE_UNLOCK));
                    cachedMode = mode;
                    log(Log.INFO, TAG, "Remote mode changed -> " + modeName(mode));
                }
            };

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG,
                "Loaded. framework=" + getFrameworkName() + " api=" + getApiVersion());

        try {
            remotePreferences = getRemotePreferences(ModuleConfig.REMOTE_PREF_GROUP);
            cachedMode = normalizeMode(remotePreferences.getInt(
                    ModuleConfig.PREF_MODE, ModuleConfig.MODE_REQUIRE_UNLOCK));
            remotePreferences.registerOnSharedPreferenceChangeListener(remotePreferenceListener);
            log(Log.INFO, TAG, "RemotePreferences ready; mode=" + modeName(cachedMode));
        } catch (Throwable t) {
            remotePreferences = null;
            cachedMode = ModuleConfig.MODE_REQUIRE_UNLOCK;
            log(Log.ERROR, TAG,
                    "RemotePreferences unavailable; temporary safe fallback=REQUIRE_UNLOCK", t);
        }
    }

    /** Install only the ActivityStarter capture early; final action hooks use PackageReady's CL. */
    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!ModuleConfig.SYSTEM_UI_PACKAGE.equals(param.getPackageName())) return;
        ClassLoader cl = param.getDefaultClassLoader();
        if (cl != null && earlyCaptureInstalled.compareAndSet(false, true)) {
            try {
                installActivityStarterCapture(cl);
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "Early ActivityStarter capture failed", t);
            }
        }
    }

    /** Install actual QS hooks with the final AppComponentFactory class loader. */
    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!ModuleConfig.SYSTEM_UI_PACKAGE.equals(param.getPackageName())) return;
        ClassLoader cl = param.getClassLoader();
        if (cl == null || !hooksInstalled.compareAndSet(false, true)) return;

        log(Log.INFO, TAG, "Installing final SystemUI hooks from onPackageReady");
        try {
            captureSystemUiContext(cl);
            if (!earlyCaptureInstalled.get()) installActivityStarterCapture(cl);
            installNewArchitectureHooks(cl);
            installLegacyTileHooks(cl);
            installShadeHooks(cl);
            log(Log.INFO, TAG, "Hook installation complete");
        } catch (Throwable t) {
            hooksInstalled.set(false);
            log(Log.ERROR, TAG, "Final hook installation failed", t);
        }
    }

    /**
     * Capture the native SystemUI ActivityStarter where possible. It provides the exact behavior we
     * want: show the keyguard bouncer and execute our queued tile action only after successful
     * dismissal/authentication.
     */
    /** Capture the SystemUI Application context before any shade gesture occurs. */
    private void captureSystemUiContext(ClassLoader cl) {
        if (systemUiContext != null) return;
        try {
            Class<?> activityThread = cl.loadClass("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            makeAccessible(currentApplication);
            Object app = currentApplication.invoke(null);
            if (app instanceof Context) {
                rememberContext((Context) app);
                log(Log.INFO, TAG, "Captured SystemUI application context");
                return;
            }
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "ActivityThread.currentApplication unavailable", t);
        }

        try {
            Class<?> appGlobals = cl.loadClass("android.app.AppGlobals");
            Method getInitialApplication = appGlobals.getDeclaredMethod("getInitialApplication");
            makeAccessible(getInitialApplication);
            Object app = getInitialApplication.invoke(null);
            if (app instanceof Context) {
                rememberContext((Context) app);
                log(Log.INFO, TAG, "Captured SystemUI context from AppGlobals");
            }
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "AppGlobals context fallback unavailable", t);
        }
    }

    private void installActivityStarterCapture(ClassLoader cl) {
        String[] names = {
                "com.android.systemui.statusbar.phone.ActivityStarterImpl"
        };

        for (String name : names) {
            try {
                Class<?> clazz = cl.loadClass(name);

                // Capture existing calls as well. These methods are normally used all over SystemUI.
                for (Method m : clazz.getDeclaredMethods()) {
                    if (!m.getName().equals("postQSRunnableDismissingKeyguard")) continue;
                    makeAccessible(m);
                    hook(m).intercept(chain -> {
                        if (chain.getThisObject() != null) {
                            capturedActivityStarter = chain.getThisObject();
                        }
                        return chain.proceed();
                    });
                }

                // Capture future constructor instances. Constructor hooks are supported by API 102.
                for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                    makeAccessible(ctor);
                    hook(ctor).intercept(chain -> {
                        Object result = chain.proceed();
                        if (chain.getThisObject() != null) {
                            capturedActivityStarter = chain.getThisObject();
                            log(Log.INFO, TAG, "Captured ActivityStarterImpl");
                        }
                        return result;
                    });
                }

                log(Log.INFO, TAG, "ActivityStarter capture hooks installed");
                return;
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "ActivityStarter capture unavailable: " + name, t);
            }
        }
    }

    /** Android 16 new QS architecture. This is the critical fix for migrated tiles. */
    private void installNewArchitectureHooks(ClassLoader cl) {
        int total = 0;

        // Strongest/stablest new-architecture gate. Every user action is emitted here before
        // QSTileUserActionInteractor#handleInput mutates system state.
        String[] vmNames = {
                "com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelImpl"
        };
        for (String name : vmNames) {
            try {
                Class<?> clazz = cl.loadClass(name);
                for (Method method : clazz.getDeclaredMethods()) {
                    if (!method.getName().equals("onActionPerformed")
                            || method.getReturnType() != void.class
                            || method.getParameterCount() != 1) {
                        continue;
                    }
                    hookActionMethod(method, cl, "NEW_VM");
                    total++;
                }
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "New VM class unavailable: " + name, t);
            }
        }

        // Adapter remains in Android 16 while SystemUI transitions from QSTileImpl to ViewModels.
        String[] adapterNames = {
                "com.android.systemui.qs.tiles.viewmodel.QSTileViewModelAdapter",
                "com.android.systemui.qs.tiles.base.ui.viewmodel.QSTileViewModelAdapter"
        };
        for (String name : adapterNames) {
            try {
                Class<?> clazz = cl.loadClass(name);
                total += hookNamedTileActions(clazz, cl, "NEW_ADAPTER");
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "Adapter class unavailable: " + name, t);
            }
        }

        log(Log.INFO, TAG, "New-architecture QS hooks=" + total);
    }

    /** Android 16 still has legacy tiles; keep them protected too. */
    private void installLegacyTileHooks(ClassLoader cl) {
        try {
            Class<?> clazz = cl.loadClass("com.android.systemui.qs.tileimpl.QSTileImpl");
            int count = hookNamedTileActions(clazz, cl, "LEGACY");
            log(Log.INFO, TAG, "Legacy QSTileImpl hooks=" + count);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "QSTileImpl unavailable", t);
        }
    }

    private int hookNamedTileActions(Class<?> clazz, ClassLoader cl, String path) {
        int count = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            String n = method.getName();
            if (!(n.equals("click") || n.equals("secondaryClick") || n.equals("longClick"))) {
                continue;
            }
            if (Modifier.isAbstract(method.getModifiers()) || method.getReturnType() != void.class) {
                continue;
            }
            hookActionMethod(method, cl, path);
            count++;
        }
        return count;
    }

    private void hookActionMethod(Method method, ClassLoader cl, String path) {
        makeAccessible(method);
        hook(method).intercept(chain -> {
            if (Boolean.TRUE.equals(authenticatedReplay.get())) {
                return chain.proceed();
            }

            Object target = chain.getThisObject();
            Context context = extractContext(target);
            if (context == null) context = systemUiContext;
            rememberContext(context);

            if (!shouldRequireUnlock(context)) {
                return chain.proceed();
            }

            // We are locked: absolutely do not allow the original action into SystemUI's backend.
            // This is what the previous QSTileImpl-only implementation failed to guarantee.
            log(Log.INFO, TAG,
                    "BLOCK " + path + " " + method.getDeclaringClass().getSimpleName()
                            + "#" + method.getName());

            long now = SystemClock.elapsedRealtime();
            if (now - lastUnlockRequestAt >= 700L) {
                lastUnlockRequestAt = now;
                Object[] args = chain.getArgs().toArray();
                requestUnlockAndReplay(cl, context, target, method, args);
            }
            return null;
        });
    }

    private void requestUnlockAndReplay(
            ClassLoader cl,
            Context context,
            Object target,
            Method method,
            Object[] args) {

        final AtomicBoolean replayConsumed = new AtomicBoolean(false);

        Runnable replayOnce = () -> {
            if (!replayConsumed.compareAndSet(false, true)) return;

            authenticatedReplay.set(Boolean.TRUE);
            try {
                Context currentContext = context != null ? context : systemUiContext;
                if (currentContext != null && isKeyguardLocked(currentContext)) {
                    // A native callback can occasionally arrive slightly before the keyguard state
                    // settles. Give the watcher the right to replay instead.
                    replayConsumed.set(false);
                    log(Log.DEBUG, TAG, "Native replay arrived while keyguard still locked; watcher retained");
                    return;
                }

                makeAccessible(method);
                method.invoke(target, args);
                log(Log.INFO, TAG, "Authenticated QS action replayed: "
                        + method.getDeclaringClass().getSimpleName() + "#" + method.getName());
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "Authenticated QS replay failed", t);
            } finally {
                authenticatedReplay.remove();
            }
        };

        // Independent safety net. This is intentionally installed BEFORE requesting the bouncer.
        // Some Android 16 / Lineage SystemUI builds dismiss the keyguard correctly but do not run
        // postQSRunnableDismissingKeyguard() for third-party module callbacks. Once KeyguardManager
        // reports the device unlocked, replay exactly once.
        watchForUnlock(context, replayOnce, replayConsumed);

        try {
            Object starter = findActivityStarter(cl, target);
            if (starter != null) {
                Method post = findRunnableMethod(starter.getClass(),
                        "postQSRunnableDismissingKeyguard");
                if (post != null) {
                    makeAccessible(post);
                    post.invoke(starter, replayOnce);
                    log(Log.INFO, TAG, "Native SystemUI keyguard bouncer requested + replay armed");
                    return;
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Native ActivityStarter path failed", t);
        }

        // Fallback still gets replay semantics through watchForUnlock().
        requestCredentialFallback(context);
    }

    private void watchForUnlock(
            Context initialContext,
            Runnable replayOnce,
            AtomicBoolean replayConsumed) {

        Handler handler = new Handler(Looper.getMainLooper());
        long deadline = SystemClock.elapsedRealtime() + UNLOCK_WATCH_TIMEOUT_MS;

        Runnable watcher = new Runnable() {
            @Override
            public void run() {
                if (replayConsumed.get()) return;

                Context context = initialContext != null ? initialContext : systemUiContext;
                if (context != null && !isKeyguardLocked(context)) {
                    log(Log.INFO, TAG, "UNLOCK detected by watcher; replaying pending QS action");
                    replayOnce.run();
                    return;
                }

                if (SystemClock.elapsedRealtime() >= deadline) {
                    log(Log.INFO, TAG, "Pending QS replay expired (unlock cancelled/timed out)");
                    return;
                }

                handler.postDelayed(this, UNLOCK_WATCH_INTERVAL_MS);
            }
        };

        handler.post(watcher);
    }

    private Object findActivityStarter(ClassLoader cl, Object target) {
        Object captured = capturedActivityStarter;
        if (captured != null) return captured;

        // Legacy QSTileImpl stores ActivityStarter directly; some ROM adapters do too.
        Object fromTarget = findFieldByTypeName(target, "ActivityStarter");
        if (fromTarget != null) {
            capturedActivityStarter = fromTarget;
            return fromTarget;
        }

        // Older Lineage/SystemUI versions exposed it through Dependency.
        try {
            Class<?> dependency = cl.loadClass("com.android.systemui.Dependency");
            Class<?> starterClass = cl.loadClass("com.android.systemui.plugins.ActivityStarter");
            Method get = dependency.getDeclaredMethod("get", Class.class);
            makeAccessible(get);
            Object value = get.invoke(null, starterClass);
            if (value != null) capturedActivityStarter = value;
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object findFieldByTypeName(Object object, String typeFragment) {
        if (object == null) return null;
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!field.getType().getName().contains(typeFragment)
                        && !field.getName().toLowerCase().contains("activitystarter")) {
                    continue;
                }
                try {
                    makeAccessible(field);
                    Object value = field.get(object);
                    if (value != null) return value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private Method findRunnableMethod(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == 1
                        && Runnable.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    return method;
                }
            }
            for (Class<?> iface : c.getInterfaces()) {
                try {
                    return iface.getMethod(name, Runnable.class);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private void requestCredentialFallback(Context context) {
        if (context == null) return;
        try {
            KeyguardManager km = (KeyguardManager)
                    context.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null) return;

            Intent intent = km.createConfirmDeviceCredentialIntent(
                    "Unlock Quick Settings",
                    "Authenticate before using Quick Settings controls");
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(intent);
                log(Log.INFO, TAG, "Credential fallback launched; QS action remains blocked");
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Credential fallback failed", t);
        }
    }

    private void installShadeHooks(ClassLoader cl) {
        int total = 0;

        // Generic policy gate. Useful for programmatic expansion paths, but NOT sufficient by
        // itself on Android 16 because the migrated lockscreen shade can dispatch touch directly.
        try {
            Class<?> clazz = cl.loadClass("com.android.systemui.statusbar.CommandQueue");
            Method m = clazz.getDeclaredMethod("panelsEnabled");
            makeAccessible(m);
            hook(m).intercept(chain -> {
                Context context = extractContext(chain.getThisObject());
                rememberContext(context);
                if (shouldBlockShade(context != null ? context : systemUiContext)) {
                    log(Log.DEBUG, TAG, "BLOCK shade CommandQueue.panelsEnabled");
                    return false;
                }
                return chain.proceed();
            });
            total++;
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "CommandQueue.panelsEnabled unavailable", t);
        }

        // Android 16 QPR2 uses CentralSurfaces#getCommandQueuePanelsEnabled() from the
        // PhoneStatusBarView touch handler. Hook this public policy accessor directly too; some
        // Lineage builds do not call CommandQueue#panelsEnabled() on the same path we hooked above.
        try {
            Class<?> central = cl.loadClass(
                    "com.android.systemui.statusbar.phone.CentralSurfacesImpl");
            for (Method m : central.getDeclaredMethods()) {
                if (!m.getName().equals("getCommandQueuePanelsEnabled")
                        || m.getParameterCount() != 0
                        || m.getReturnType() != boolean.class) {
                    continue;
                }
                makeAccessible(m);
                hook(m).intercept(chain -> {
                    Context context = extractContext(chain.getThisObject());
                    if (context == null) context = systemUiContext;
                    rememberContext(context);
                    if (shouldBlockShade(context)) {
                        log(Log.DEBUG, TAG, "BLOCK CentralSurfaces#getCommandQueuePanelsEnabled");
                        return false;
                    }
                    return chain.proceed();
                });
                total++;
            }
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "CentralSurfaces panel gate unavailable", t);
        }

        // On Android 16 the actual finger stream from the status bar is owned by the private
        // PhoneStatusBarViewController.PhoneStatusBarViewTouchHandler (Gefingerpoken), not by
        // PhoneStatusBarView itself.  Hook every nested class exposing the two touch callbacks so
        // this also survives minor Lineage/Kotlin naming changes.  Returning true eats the status
        // bar gesture before SceneContainer/NotificationPanel ever receives it.
        try {
            Class<?> controller = cl.loadClass(
                    "com.android.systemui.statusbar.phone.PhoneStatusBarViewController");
            for (Class<?> nested : controller.getDeclaredClasses()) {
                for (Method m : nested.getDeclaredMethods()) {
                    String n = m.getName();
                    if (!(n.equals("onInterceptTouchEvent") || n.equals("onTouchEvent"))
                            || m.getReturnType() != boolean.class
                            || m.getParameterCount() != 1
                            || !MotionEvent.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        continue;
                    }
                    makeAccessible(m);
                    hook(m).intercept(chain -> {
                        Context context = systemUiContext;
                        if (context == null) context = extractContext(chain.getThisObject());
                        rememberContext(context);
                        if (shouldBlockShade(context)) {
                            MotionEvent ev = (MotionEvent) chain.getArgs().get(0);
                            log(Log.INFO, TAG, "BLOCK status-bar handler "
                                    + nested.getSimpleName() + "#" + n
                                    + " action=" + ev.getActionMasked());
                            return true;
                        }
                        return chain.proceed();
                    });
                    total++;
                }
            }

            // External status-bar touch forwarding uses this method on some configurations.
            for (Method m : controller.getDeclaredMethods()) {
                if (!m.getName().equals("sendTouchToView")
                        || m.getReturnType() != boolean.class
                        || m.getParameterCount() != 1
                        || !MotionEvent.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    continue;
                }
                makeAccessible(m);
                hook(m).intercept(chain -> {
                    Context context = systemUiContext;
                    if (context == null) context = extractContext(chain.getThisObject());
                    rememberContext(context);
                    if (shouldBlockShade(context)) {
                        MotionEvent ev = (MotionEvent) chain.getArgs().get(0);
                        log(Log.INFO, TAG, "BLOCK PhoneStatusBarViewController#sendTouchToView action="
                                + ev.getActionMasked());
                        return true;
                    }
                    return chain.proceed();
                });
                total++;
            }
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "PhoneStatusBarViewController hard gate unavailable", t);
        }

        // Status-bar hard gate. A top-edge pull can be routed through PhoneStatusBarView before
        // focus is transferred to the shade. Eating its touch stream while locked prevents that
        // transfer on builds where the shade root does not receive the initial ACTION_DOWN.
        try {
            Class<?> statusBarView = cl.loadClass(
                    "com.android.systemui.statusbar.phone.PhoneStatusBarView");
            for (Method m : statusBarView.getDeclaredMethods()) {
                if (!(m.getName().equals("onTouchEvent")
                        || m.getName().equals("onInterceptTouchEvent")
                        || m.getName().equals("dispatchTouchEvent"))
                        || m.getReturnType() != boolean.class
                        || m.getParameterCount() != 1
                        || !MotionEvent.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    continue;
                }
                makeAccessible(m);
                hook(m).intercept(chain -> {
                    MotionEvent ev = (MotionEvent) chain.getArgs().get(0);
                    Context context = extractContext(chain.getThisObject());
                    rememberContext(context);
                    if (shouldBlockShade(context != null ? context : systemUiContext)) {
                        log(Log.DEBUG, TAG, "BLOCK status-bar touch " + m.getName()
                                + " action=" + ev.getActionMasked());
                        return true;
                    }
                    return chain.proceed();
                });
                total++;
            }
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "PhoneStatusBarView touch hook unavailable", t);
        }

        // Android 16 migrated shade hard gate. NotificationShadeWindowView is the parent window
        // through which lockscreen shade touch is dispatched on the new hierarchy. Consuming a
        // gesture that STARTS in the top edge prevents QS pull-down while preserving notification
        // taps and normal swipe-up-to-unlock gestures lower on the lockscreen.
        try {
            Class<?> rootView = cl.loadClass("com.android.systemui.shade.NotificationShadeWindowView");
            for (Method m : rootView.getDeclaredMethods()) {
                if (!m.getName().equals("dispatchTouchEvent")
                        || m.getReturnType() != boolean.class
                        || m.getParameterCount() != 1
                        || !MotionEvent.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    continue;
                }
                makeAccessible(m);
                hook(m).intercept(chain -> {
                    MotionEvent ev = (MotionEvent) chain.getArgs().get(0);
                    Context context = extractContext(chain.getThisObject());
                    rememberContext(context);
                    if (shouldConsumeBlockedShadeGesture(context, ev)) {
                        log(Log.DEBUG, TAG, "BLOCK shade root dispatch action=" + ev.getActionMasked());
                        return true;
                    }
                    return chain.proceed();
                });
                total++;
            }
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "NotificationShadeWindowView dispatch hook unavailable", t);
        }

        // NotificationPanelViewController owns both legacy onTouchEvent() and the Android 16
        // migrated handleExternalTouch()/handleExternalInterceptTouch() path. Hook all known touch
        // entry points as a second line of defense for Lineage-specific hierarchy differences.
        String[] panelNames = {
                "com.android.systemui.shade.NotificationPanelViewController",
                "com.android.systemui.statusbar.phone.NotificationPanelViewController"
        };
        String[] touchNames = {
                "onTouch",
                "onTouchEvent",
                "onInterceptTouchEvent",
                "handleTouch",
                "handleExternalTouch",
                "handleExternalInterceptTouch"
        };
        for (String name : panelNames) {
            try {
                Class<?> clazz = cl.loadClass(name);
                for (Method m : clazz.getDeclaredMethods()) {
                    boolean nameMatch = false;
                    for (String touchName : touchNames) {
                        if (m.getName().equals(touchName)) {
                            nameMatch = true;
                            break;
                        }
                    }
                    if (!nameMatch) continue;

                    int motionIndex = findMotionEventParameter(m);
                    if (motionIndex < 0) continue;

                    Class<?> returnType = m.getReturnType();
                    if (!(returnType == boolean.class || returnType == Boolean.class
                            || returnType == void.class)) {
                        continue;
                    }

                    makeAccessible(m);
                    final int eventIndex = motionIndex;
                    hook(m).intercept(chain -> {
                        MotionEvent ev = (MotionEvent) chain.getArgs().get(eventIndex);
                        Context context = extractContext(chain.getThisObject());
                        rememberContext(context);
                        if (shouldConsumeBlockedShadeGesture(context, ev)) {
                            log(Log.DEBUG, TAG, "BLOCK panel touch " + m.getName()
                                    + " action=" + ev.getActionMasked());
                            if (returnType == boolean.class || returnType == Boolean.class) {
                                return true;
                            }
                            return null;
                        }
                        return chain.proceed();
                    });
                    total++;
                }
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "Panel touch class unavailable: " + name, t);
            }
        }

        // QS-specific interception gate remains useful on ROMs that haven't enabled the full
        // notification shade migration.
        String[] quickControllerNames = {
                "com.android.systemui.shade.QuickSettingsControllerImpl",
                "com.android.systemui.shade.QuickSettingsController"
        };
        for (String name : quickControllerNames) {
            try {
                Class<?> clazz = cl.loadClass(name);
                for (Method m : clazz.getDeclaredMethods()) {
                    if (!m.getName().equals("shouldQuickSettingsIntercept")
                            || m.getReturnType() != boolean.class) continue;
                    makeAccessible(m);
                    hook(m).intercept(chain -> {
                        Context context = extractContext(chain.getThisObject());
                        rememberContext(context);
                        if (shouldBlockShade(context != null ? context : systemUiContext)) {
                            log(Log.DEBUG, TAG, "BLOCK shouldQuickSettingsIntercept");
                            return false;
                        }
                        return chain.proceed();
                    });
                    total++;
                }
            } catch (Throwable ignored) {
            }
        }

        // Programmatic/direct expansion fallback.
        for (String name : panelNames) {
            try {
                Class<?> clazz = cl.loadClass(name);
                for (Method m : clazz.getDeclaredMethods()) {
                    String n = m.getName();
                    if (!(n.equals("expandToQs") || n.equals("expandQs")
                            || n.equals("expandWithQs"))) continue;
                    if (m.getReturnType() != void.class) continue;
                    makeAccessible(m);
                    hook(m).intercept(chain -> {
                        Context context = extractContext(chain.getThisObject());
                        rememberContext(context);
                        if (shouldBlockShade(context != null ? context : systemUiContext)) {
                            log(Log.DEBUG, TAG, "BLOCK direct QS expansion " + n);
                            return null;
                        }
                        return chain.proceed();
                    });
                    total++;
                }
            } catch (Throwable ignored) {
            }
        }

        log(Log.INFO, TAG, "Shade/QS expansion hooks=" + total);
    }

    private int findMotionEventParameter(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (MotionEvent.class.isAssignableFrom(types[i])) return i;
        }
        return -1;
    }

    private boolean shouldConsumeBlockedShadeGesture(Context context, MotionEvent event) {
        if (context == null || event == null || !shouldBlockShade(context)) {
            if (event != null && (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
                blockedShadeGestureDownTime = -1L;
            }
            return false;
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (isTopEdgeGesture(context, event)) {
                blockedShadeGestureDownTime = event.getDownTime();
                log(Log.INFO, TAG, "BLOCK_SHADE gesture armed y=" + event.getY());
                return true;
            }
            blockedShadeGestureDownTime = -1L;
            return false;
        }

        boolean blocked = blockedShadeGestureDownTime == event.getDownTime();
        if (blocked && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            blockedShadeGestureDownTime = -1L;
        }
        return blocked;
    }

    private boolean isTopEdgeGesture(Context context, MotionEvent event) {
        float density = context.getResources().getDisplayMetrics().density;
        int statusBarHeight = 0;
        try {
            int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id != 0) statusBarHeight = context.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }

        // Intentionally generous enough for cutouts / large status bars, but small enough to keep
        // lockscreen notifications and swipe-up-to-unlock outside the consumed region.
        float thresholdPx = Math.max(statusBarHeight * 2.5f, 96f * density);
        float y = event.getRawY();
        if (y < 0f) y = event.getY();
        return y <= thresholdPx;
    }

    private boolean shouldRequireUnlock(Context context) {
        return context != null
                && getMode() == ModuleConfig.MODE_REQUIRE_UNLOCK
                && isKeyguardLocked(context);
    }

    private boolean shouldBlockShade(Context context) {
        return context != null
                && getMode() == ModuleConfig.MODE_BLOCK_SHADE
                && isKeyguardLocked(context);
    }

    private boolean isKeyguardLocked(Context context) {
        try {
            KeyguardManager km = (KeyguardManager)
                    context.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            // Fail secure inside an active protection mode: if querying lock state itself breaks,
            // do not accidentally allow a protected action.
            log(Log.WARN, TAG, "Unable to query keyguard state", t);
            return true;
        }
    }

    /**
     * Read configuration from libxposed RemotePreferences. This is the important v1.4 change:
     * no cross-package ContentProvider call from SystemUI, and therefore no silent mode-2 ->
     * mode-1 fallback when that provider bridge is unavailable on a ROM/user/SELinux setup.
     */
    private int getMode() {
        SharedPreferences preferences = remotePreferences;
        if (preferences == null) return cachedMode;
        try {
            int mode = normalizeMode(preferences.getInt(
                    ModuleConfig.PREF_MODE, cachedMode));
            cachedMode = mode;
            return mode;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Unable to read RemotePreferences; keeping " + modeName(cachedMode), t);
            return cachedMode;
        }
    }

    private static int normalizeMode(int mode) {
        return mode == ModuleConfig.MODE_BLOCK_SHADE
                ? ModuleConfig.MODE_BLOCK_SHADE
                : ModuleConfig.MODE_REQUIRE_UNLOCK;
    }

    private static String modeName(int mode) {
        return mode == ModuleConfig.MODE_BLOCK_SHADE
                ? "BLOCK_SHADE"
                : "REQUIRE_UNLOCK";
    }

    private Context extractContext(Object object) {
        if (object instanceof Context) return (Context) object;
        if (object instanceof View) return ((View) object).getContext();
        if (object == null) return null;

        try {
            Method getContext = object.getClass().getMethod("getContext");
            Object value = getContext.invoke(object);
            if (value instanceof Context) return (Context) value;
        } catch (Throwable ignored) {
        }

        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(field.getType())) continue;
                try {
                    makeAccessible(field);
                    Object value = field.get(object);
                    if (value instanceof Context) return (Context) value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private void rememberContext(Context context) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext();
            systemUiContext = app != null ? app : context;
        } catch (Throwable ignored) {
            systemUiContext = context;
        }
    }

    private static void makeAccessible(Executable executable) {
        try { executable.setAccessible(true); } catch (Throwable ignored) {}
    }

    private static void makeAccessible(Field field) {
        try { field.setAccessible(true); } catch (Throwable ignored) {}
    }
}
