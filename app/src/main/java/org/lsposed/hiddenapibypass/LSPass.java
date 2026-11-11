package org.lsposed.hiddenapibypass;

import android.util.Log;
import android.util.Property;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Compileable reconstruction of the LSPass subset used by CreatorProxy.
 * VMRuntime is resolved by name because it is absent from the public Android SDK stubs.
 */
public final class LSPass {
    private static final String TAG = "LSPass";
    private static final Property<Class, Method[]> methods =
            Property.of(Class.class, Method[].class, "DeclaredMethods");
    private static final Property<Class, Constructor[]> constructors =
            Property.of(Class.class, Constructor[].class, "DeclaredConstructors");
    private static final Property<Class, Field[]> fields =
            Property.of(Class.class, Field[].class, "DeclaredFields");

    public LSPass() {
    }

    public static List<Method> getDeclaredMethods(Class<?> type) {
        return Arrays.asList(methods.get(type));
    }

    public static List<Constructor<?>> getDeclaredConstructors(Class<?> type) {
        return Arrays.asList(constructors.get(type));
    }

    public static List<Field> getDeclaredFields(Class<?> type) {
        return Arrays.asList(fields.get(type));
    }

    public static List<Field> getInstanceFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Field field : getDeclaredFields(type)) {
            if (!Modifier.isStatic(field.getModifiers())) {
                result.add(field);
            }
        }
        return result;
    }

    public static List<Field> getStaticFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Field field : getDeclaredFields(type)) {
            if (Modifier.isStatic(field.getModifiers())) {
                result.add(field);
            }
        }
        return result;
    }

    public static Method getDeclaredMethod(
            Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        for (Method method : getDeclaredMethods(type)) {
            if (method.getName().equals(name)
                    && Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    public static Constructor<?> getDeclaredConstructor(
            Class<?> type, Class<?>... parameterTypes) throws NoSuchMethodException {
        for (Constructor<?> constructor : getDeclaredConstructors(type)) {
            if (Arrays.equals(constructor.getParameterTypes(), parameterTypes)) {
                return constructor;
            }
        }
        throw new NoSuchMethodException(type.getName());
    }

    public static Object newInstance(Class<?> type, Object... arguments)
            throws IllegalAccessException, NoSuchMethodException,
            InstantiationException, InvocationTargetException {
        for (Constructor<?> constructor : getDeclaredConstructors(type)) {
            if (Helper.checkArgsForInvokeMethod(constructor.getParameterTypes(), arguments)) {
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            }
        }
        throw new NoSuchMethodException("Cannot find matching constructor");
    }

    public static Object invoke(Class<?> type, Object receiver, String name, Object... arguments)
            throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        for (Method method : getDeclaredMethods(type)) {
            if (!method.getName().equals(name)
                    || !Helper.checkArgsForInvokeMethod(method.getParameterTypes(), arguments)) {
                continue;
            }
            method.setAccessible(true);
            return method.invoke(receiver, arguments);
        }
        throw new NoSuchMethodException(name);
    }

    public static boolean setHiddenApiExemptions(String... prefixes) {
        try {
            Class<?> runtimeClass = Class.forName("dalvik.system.VMRuntime");
            Object runtime = invoke(runtimeClass, null, "getRuntime");
            invoke(runtimeClass, runtime, "setHiddenApiExemptions", (Object) prefixes);
            return true;
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "setHiddenApiExemptions", error);
            return false;
        }
    }

    public static boolean addHiddenApiExemptions(String... prefixes) {
        Helper.signaturePrefixes.addAll(Arrays.asList(prefixes));
        return setHiddenApiExemptions(Helper.signaturePrefixes.toArray(new String[0]));
    }

    public static boolean clearHiddenApiExemptions() {
        Helper.signaturePrefixes.clear();
        return setHiddenApiExemptions();
    }

}
