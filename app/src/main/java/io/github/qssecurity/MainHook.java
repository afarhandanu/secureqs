package io.github.qssecurity;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

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
    private static final long MODE_CACHE_MS = 500L;

    private static final AtomicBoolean earlyCaptureInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean hooksInstalled = new AtomicBoolean(false);
    private static volatile Context systemUiContext;
    private static volatile Object capturedActivityStarter;

    private static volatile int cachedMode = ModuleConfig.MODE_REQUIRE_UNLOCK;
    private static volatile long cachedModeAt = Long.MIN_VALUE;
    private static volatile long lastUnlockRequestAt = 0L;

    /** A replay must not immediately get intercepted by another synchronous hook layer. */
    private static final ThreadLocal<Boolean> authenticatedReplay = new ThreadLocal<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG,
                "Loaded. framework=" + getFrameworkName() + " api=" + getApiVersion());
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

        Runnable replay = () -> {
            authenticatedReplay.set(Boolean.TRUE);
            try {
                // If the device is somehow still locked, do not execute a sensitive action.
                if (context != null && isKeyguardLocked(context)) {
                    log(Log.WARN, TAG, "Replay skipped: keyguard still locked");
                    return;
                }
                makeAccessible(method);
                method.invoke(target, args);
                log(Log.INFO, TAG, "Authenticated QS action replayed");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "Authenticated QS replay failed", t);
            } finally {
                authenticatedReplay.remove();
            }
        };

        try {
            Object starter = findActivityStarter(cl, target);
            if (starter != null) {
                Method post = findRunnableMethod(starter.getClass(),
                        "postQSRunnableDismissingKeyguard");
                if (post != null) {
                    makeAccessible(post);
                    post.invoke(starter, replay);
                    log(Log.INFO, TAG, "Native SystemUI keyguard bouncer requested");
                    return;
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Native ActivityStarter path failed", t);
        }

        // Safe fallback: action remains blocked. The credential confirmation is shown, but we never
        // execute the QS action while KeyguardManager still reports the device as locked.
        requestCredentialFallback(context);
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

        // Generic notification-panel gate.
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

        // QS-specific Android 16 interception gate used by the shade controller.
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
                        if (shouldBlockShade(systemUiContext)) return false;
                        return chain.proceed();
                    });
                    total++;
                }
            } catch (Throwable ignored) {
            }
        }

        // Expansion methods: if ROM bypasses the generic panel gate, neutralize direct QS expansion.
        String[] expansionClasses = {
                "com.android.systemui.shade.NotificationPanelViewController",
                "com.android.systemui.statusbar.phone.NotificationPanelViewController"
        };
        for (String name : expansionClasses) {
            try {
                Class<?> clazz = cl.loadClass(name);
                for (Method m : clazz.getDeclaredMethods()) {
                    String n = m.getName();
                    if (!(n.equals("expandToQs") || n.equals("expandQs"))) continue;
                    if (m.getReturnType() != void.class) continue;
                    makeAccessible(m);
                    hook(m).intercept(chain -> {
                        if (shouldBlockShade(systemUiContext)) {
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

    private boolean shouldRequireUnlock(Context context) {
        return context != null
                && getMode(context) == ModuleConfig.MODE_REQUIRE_UNLOCK
                && isKeyguardLocked(context);
    }

    private boolean shouldBlockShade(Context context) {
        return context != null
                && getMode(context) == ModuleConfig.MODE_BLOCK_SHADE
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

    private int getMode(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (now - cachedModeAt < MODE_CACHE_MS) return cachedMode;

        synchronized (MainHook.class) {
            now = SystemClock.elapsedRealtime();
            if (now - cachedModeAt < MODE_CACHE_MS) return cachedMode;

            try {
                Bundle result = context.getContentResolver().call(
                        ModuleConfig.PROVIDER_URI,
                        ModuleConfig.PROVIDER_METHOD_GET_MODE,
                        null,
                        null);
                if (result != null) {
                    int value = result.getInt(
                            ModuleConfig.PROVIDER_RESULT_MODE,
                            ModuleConfig.MODE_REQUIRE_UNLOCK);
                    if (value == ModuleConfig.MODE_REQUIRE_UNLOCK
                            || value == ModuleConfig.MODE_BLOCK_SHADE) {
                        cachedMode = value;
                    }
                }
            } catch (Throwable t) {
                // Safe default: tile actions require unlock.
                cachedMode = ModuleConfig.MODE_REQUIRE_UNLOCK;
                log(Log.WARN, TAG, "Unable to read module mode; using REQUIRE_UNLOCK", t);
            }

            cachedModeAt = now;
            return cachedMode;
        }
    }

    private Context extractContext(Object object) {
        if (object instanceof Context) return (Context) object;
        if (object == null) return null;

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
