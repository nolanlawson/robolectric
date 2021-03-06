package org.robolectric.bytecode;

import android.R;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.DependencyJar;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.SdkConfig;
import org.robolectric.SdkEnvironment;
import org.robolectric.TestLifecycle;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.impl.ExtendedResponseCache;
import org.robolectric.impl.FakeCharsets;
import org.robolectric.impl.ResponseSource;
import org.robolectric.annotation.internal.DoNotInstrument;
import org.robolectric.annotation.internal.Instrument;
import org.robolectric.internal.ParallelUniverseInterface;
import org.robolectric.res.ResourceLoader;
import org.robolectric.res.ResourcePath;
import org.robolectric.util.Transcript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Arrays.asList;

public class Setup {
  public static final List<String> CLASSES_TO_ALWAYS_DELEGATE = stringify(
      TestLifecycle.class,
      RealObject.class,
      ShadowWrangler.class,
      AndroidManifest.class,
      R.class,

      org.robolectric.bytecode.AsmInstrumentingClassLoader.class,
      SdkEnvironment.class,
      SdkConfig.class,
      RobolectricTestRunner.class,
      RobolectricTestRunner.HelperTestRunner.class,
      ResourcePath.class,
      ResourceLoader.class,
      ClassHandler.class,
      ClassHandler.Plan.class,
      Implements.class,
      Implementation.class,
      Instrument.class,
      DoNotInstrument.class,
      Config.class,
      Transcript.class,
      org.robolectric.bytecode.DirectObjectMarker.class,
      DependencyJar.class,
      ParallelUniverseInterface.class
  );

  private static List<String> stringify(Class... classes) {
    ArrayList<String> strings = new ArrayList<>();
    for (Class aClass : classes) {
      strings.add(aClass.getName());
    }
    return strings;
  }

  public boolean shouldInstrument(ClassInfo classInfo) {
    if (classInfo.isInterface() || classInfo.isAnnotation() || classInfo.hasAnnotation(DoNotInstrument.class)) {
      return false;
    }

    // allow explicit control with @Instrument, mostly for tests
    return classInfo.hasAnnotation(Instrument.class) || isFromAndroidSdk(classInfo);
  }

  public boolean isFromAndroidSdk(ClassInfo classInfo) {
    String className = classInfo.getName();
    return className.startsWith("android.")
        || className.startsWith("libcore.")
        || className.startsWith("dalvik.")
        || className.startsWith("com.android.internal.")
        || className.startsWith("com.google.android.maps.")
        || className.startsWith("com.google.android.gms.")
        || className.startsWith("dalvik.system.")
        || className.startsWith("org.apache.http.impl.client.DefaultRequestDirector");
  }

  public boolean shouldAcquire(String name) {
    // the org.robolectric.res and org.robolectric.manifest packages live in the base classloader, but not its tests; yuck.
    int lastDot = name.lastIndexOf('.');
    String pkgName = name.substring(0, lastDot == -1 ? 0 : lastDot);
    if (pkgName.equals("org.robolectric.res") || (pkgName.equals("org.robolectric.manifest"))) {
      return name.contains("Test");
    }

    if (name.matches("com\\.android\\.internal\\.R(\\$.*)?")) return true;

    // Android SDK code almost universally refers to com.android.internal.R, except
    // when refering to android.R.stylable, as in HorizontalScrollView. arghgh.
    // See https://github.com/robolectric/robolectric/issues/521
    if (name.equals("android.R$styleable")) return true;

    return !(
        name.matches(".*\\.R(|\\$[a-z]+)$")
            || CLASSES_TO_ALWAYS_DELEGATE.contains(name)
            || name.startsWith("java.")
            || name.startsWith("javax.")
            || name.startsWith("sun.")
            || name.startsWith("com.sun.")
            || name.startsWith("org.w3c.")
            || name.startsWith("org.xml.")
            || name.startsWith("org.junit")
            || name.startsWith("org.hamcrest")
            || name.startsWith("org.specs2")  // allows for android projects with mixed scala\java tests to be
            || name.startsWith("scala.")      //  run with Maven Surefire (see the RoboSpecs project on github)
            || name.startsWith("kotlin.")
            || name.startsWith("com.almworks.sqlite4java") // Fix #958: SQLite native library must be loaded once.
    );
  }

  public Set<MethodRef> methodsToIntercept() {
    return Collections.unmodifiableSet(new HashSet<>(asList(
        new MethodRef(LinkedHashMap.class, "eldest"),
        new MethodRef(System.class, "loadLibrary"),
        new MethodRef("android.os.StrictMode", "trackActivity"),
        new MethodRef("android.os.StrictMode", "incrementExpectedActivityCount"),
        new MethodRef("java.lang.AutoCloseable", "*"),
        new MethodRef("android.util.LocaleUtil", "getLayoutDirectionFromLocale"),
        new MethodRef("com.android.internal.policy.PolicyManager", "*"),
        new MethodRef("android.view.FallbackEventHandler", "*"),
        new MethodRef("android.view.IWindowSession", "*"),
        new MethodRef("java.lang.System", "nanoTime"),
        new MethodRef("java.lang.System", "currentTimeMillis"),
        new MethodRef("java.lang.System", "arraycopy"),
        new MethodRef("java.lang.System", "logE"),
        new MethodRef("java.util.Locale", "adjustLanguageCode")
    )));
  }

  /**
   * Map from a requested class to an alternate stand-in, or not.
   *
   * @return Mapping of class name translations.
   */
  public Map<String, String> classNameTranslations() {
    Map<String, String> map = new HashMap<>();
    map.put("java.net.ExtendedResponseCache", ExtendedResponseCache.class.getName());
    map.put("java.net.ResponseSource", ResponseSource.class.getName());
    map.put("java.nio.charset.Charsets", FakeCharsets.class.getName());
    return map;
  }

  public boolean containsStubs(ClassInfo classInfo) {
    return classInfo.getName().startsWith("com.google.android.maps.");
  }

  public static class MethodRef {
    public final String className;
    public final String methodName;

    public MethodRef(Class<?> clazz, String methodName) {
      this(clazz.getName(), methodName);
    }

    public MethodRef(String className, String methodName) {
      this.className = className;
      this.methodName = methodName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      MethodRef methodRef = (MethodRef) o;

      if (!className.equals(methodRef.className)) return false;
      if (!methodName.equals(methodRef.methodName)) return false;

      return true;
    }

    @Override
    public int hashCode() {
      int result = className.hashCode();
      result = 31 * result + methodName.hashCode();
      return result;
    }

    @Override
    public String toString() {
      return "MethodRef{" +
          "className='" + className + '\'' +
          ", methodName='" + methodName + '\'' +
          '}';
    }
  }
}
