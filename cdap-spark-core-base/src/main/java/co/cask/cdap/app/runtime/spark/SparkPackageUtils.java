/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.app.runtime.spark;

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.utils.DirUtils;
import co.cask.cdap.internal.app.runtime.LocalizationUtils;
import co.cask.cdap.internal.app.runtime.distributed.LocalizeResource;
import com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.twill.filesystem.FileContextLocationFactory;
import org.apache.twill.filesystem.ForwardingLocationFactory;
import org.apache.twill.filesystem.Location;
import org.apache.twill.filesystem.LocationFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nullable;

/**
 * A utility class to help determine Spark supports and locating Spark jar.
 * This class shouldn't use any classes from Spark/Scala.
 */
public final class SparkPackageUtils {

  private static final Logger LOG = LoggerFactory.getLogger(SparkPackageUtils.class);

  // The prefix for spark environment variable names. It is being setup by the startup script.
  private static final String SPARK_ENV_PREFIX = "_SPARK_";
  // Environment variable name for the spark conf directory.
  private static final String SPARK_CONF_DIR = "SPARK_CONF_DIR";
  // Environment variable name for locating spark assembly jar file
  private static final String SPARK_ASSEMBLY_JAR = "SPARK_ASSEMBLY_JAR";
  // File name for the spark default config
  private static final String SPARK_DEFAULTS_CONF = "spark-defaults.conf";
  // Spark1 conf key for spark-assembly jar location
  private static final String SPARK_YARN_JAR = "spark.yarn.jar";

  // Environment variable name for locating spark home directory
  private static final String SPARK_HOME = Constants.SPARK_HOME;

  // File name of the Spark conf directory as defined by the Spark framework
  // This is for the Hack to workaround CDAP-5019 (SPARK-13441)
  public static final String LOCALIZED_CONF_DIR = "__spark_conf__";

  private static Map<String, String> sparkEnv;

  private static File sparkAssemblyJar;
  private static LocalizeResource sparkFramework;

  /**
   * Prepares the spark framework jar(s) and have it ready on a location.
   *
   * @param cConf the configuration
   * @param locationFactory the {@link LocationFactory} for storing the spark framework jar(s).
   * @return a {@link LocalizeResource} containing information for the spark framework for file localization;
   *         {@code null} will be returned if not able to prepare such location
   */
  @Nullable
  public static synchronized LocalizeResource prepareSparkFramework(CConfiguration cConf,
                                                                    LocationFactory locationFactory) {
    try {
      if (sparkFramework != null && locationFactory.create(sparkFramework.getURI()).exists()) {
        return sparkFramework;
      }
      sparkFramework = null;

      Properties sparkConf = getSparkDefaultConf();
      switch (SparkCompat.get(cConf)) {
        case SPARK1_2_10: {
          sparkFramework = prepareSpark1Framework(sparkConf, locationFactory);
        }
        break;

        default:
          return null;
      }
    } catch (Exception e) {
      sparkFramework = null;
    }

    return sparkFramework;
  }

  /**
   * Locates the spark-assembly jar from the local file system.
   *
   * @return the spark-assembly jar location
   * @throws IllegalStateException if cannot locate the spark assembly jar
   */
  public static synchronized File locateSparkAssemblyJar() {
    if (sparkAssemblyJar != null) {
      return sparkAssemblyJar;
    }

    // If someone explicitly set the location, use it.
    // It's useful for overriding what being set for SPARK_HOME
    String jarEnv = System.getenv(SPARK_ASSEMBLY_JAR);
    if (jarEnv != null) {
      File file = new File(jarEnv);
      if (file.isFile()) {
        LOG.info("Located Spark Assembly JAR in {}", file);
        sparkAssemblyJar = file;
        return file;
      }
      LOG.warn("Env $" + SPARK_ASSEMBLY_JAR + "=" + jarEnv + " is not a file. " +
                 "Will locate Spark Assembly JAR with $" + SPARK_HOME);
    }

    String sparkHome = System.getenv(SPARK_HOME);
    if (sparkHome == null) {
      throw new IllegalStateException("Spark library not found. " +
                                        "Please set environment variable " + SPARK_HOME + " or " + SPARK_ASSEMBLY_JAR);
    }

    // Look for spark-assembly.jar symlink
    Path assemblyJar = Paths.get(sparkHome, "lib", "spark-assembly.jar");
    if (Files.isSymbolicLink(assemblyJar)) {
      sparkAssemblyJar = assemblyJar.toFile();
      return sparkAssemblyJar;
    }

    // No symbolic link exists. Search for spark-assembly*.jar in the lib directory
    Path sparkLib = Paths.get(sparkHome, "lib");
    final PathMatcher pathMatcher = sparkLib.getFileSystem().getPathMatcher("glob:spark-assembly*.jar");
    try {
      Files.walkFileTree(sparkLib, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          // Take the first file match
          if (attrs.isRegularFile() && pathMatcher.matches(file.getFileName())) {
            sparkAssemblyJar = file.toFile();
            return FileVisitResult.TERMINATE;
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
          // Ignore error
          return FileVisitResult.CONTINUE;
        }

      });

    } catch (IOException e) {
      // Just log, don't throw.
      // If we already located the Spark Assembly jar during visiting, we can still use the jar.
      LOG.warn("Exception raised while inspecting {}", sparkLib, e);
    }

    Preconditions.checkState(sparkAssemblyJar != null, "Failed to locate Spark library from %s", sparkHome);

    LOG.info("Located Spark Assembly JAR in {}", sparkAssemblyJar);
    return sparkAssemblyJar;
  }

  /**
   * Prepares the resources that need to be localized to the Spark client container.
   *
   * @param cConf
   * @param locationFactory
   * @param tempDir a temporary directory for file creation
   * @param localizeResources A map from localized name to {@link LocalizeResource} for this method to update
   */
  public static void prepareSparkResources(CConfiguration cConf, LocationFactory locationFactory, File tempDir,
                                           Map<String, LocalizeResource> localizeResources) throws IOException {
    Properties sparkConf = getSparkDefaultConf();

    LocalizeResource sparkFramework = prepareSparkFramework(cConf, locationFactory);
    if (sparkFramework != null) {
      localizeResources.put(LocalizationUtils.getLocalizedName(sparkFramework.getURI()), sparkFramework);
      if (sparkConf.getProperty(SPARK_YARN_JAR) == null) {
        sparkConf.setProperty(SPARK_YARN_JAR, sparkFramework.getURI().toString());
      }
    } else {
      File sparkAssemblyJar = locateSparkAssemblyJar();
      localizeResources.put(sparkAssemblyJar.getName(), new LocalizeResource(sparkAssemblyJar));
    }

    // Localize the spark-defaults.conf file
    File sparkDefaultConfFile = saveSparkDefaultConf(sparkConf,
                                                     File.createTempFile(SPARK_DEFAULTS_CONF, null, tempDir));
    localizeResources.put(SPARK_DEFAULTS_CONF, new LocalizeResource(sparkDefaultConfFile));

    // Shallow copy all files under directory defined by $HADOOP_CONF_DIR
    // If $HADOOP_CONF_DIR is not defined, use the location of "yarn-site.xml" to determine the directory
    // This is part of workaround for CDAP-5019 (SPARK-13441).
    File hadoopConfDir = null;
    if (System.getenv().containsKey(ApplicationConstants.Environment.HADOOP_CONF_DIR.key())) {
      hadoopConfDir = new File(System.getenv(ApplicationConstants.Environment.HADOOP_CONF_DIR.key()));
    } else {
      URL yarnSiteLocation = SparkPackageUtils.class.getClassLoader().getResource("yarn-site.xml");
      if (yarnSiteLocation != null) {
        try {
          hadoopConfDir = new File(yarnSiteLocation.toURI()).getParentFile();
        } catch (URISyntaxException e) {
          // Shouldn't happen
          LOG.warn("Failed to derive HADOOP_CONF_DIR from yarn-site.xml");
        }
      }
    }
    if (hadoopConfDir != null && hadoopConfDir.isDirectory()) {
      try {
        final File targetFile = File.createTempFile(LOCALIZED_CONF_DIR, ".zip", tempDir);
        try (
          ZipOutputStream zipOutput = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(targetFile)))
        ) {
          for (File file : DirUtils.listFiles(hadoopConfDir)) {
            // Shallow copy of files under the hadoop conf dir. Ignore files that cannot be read
            if (file.isFile() && file.canRead()) {
              zipOutput.putNextEntry(new ZipEntry(file.getName()));
              Files.copy(file.toPath(), zipOutput);
            }
          }
        }
        localizeResources.put(LOCALIZED_CONF_DIR, new LocalizeResource(targetFile, true));
      } catch (IOException e) {
        LOG.warn("Failed to create archive from {}", hadoopConfDir, e);
      }
    }
  }

  /**
   * Returns the Spark environment setup via the start up script.
   */
  public static synchronized Map<String, String> getSparkEnv() {
    if (sparkEnv != null) {
      return sparkEnv;
    }

    Map<String, String> env = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
      if (entry.getKey().startsWith(SPARK_ENV_PREFIX)) {
        env.put(entry.getKey().substring(SPARK_ENV_PREFIX.length()), entry.getValue());
      }
    }

    // Spark using YARN and it is needed for both Workflow and Spark runner. We need to set it
    // because inside Spark code, it will set and unset the SPARK_YARN_MODE system properties, causing
    // fork in distributed mode not working. Setting it in the environment, which Spark uses for defaults,
    // so it can't be unset by Spark
    env.put("SPARK_YARN_MODE", "true");

    sparkEnv = Collections.unmodifiableMap(env);
    return sparkEnv;
  }

  /**
   * Returns the environment for the Spark client container.
   */
  public static Map<String, String> getSparkClientEnv() {
    Map<String, String> env = new LinkedHashMap<>(getSparkEnv());

    // The spark-defaults.conf will be localized to container
    // and we shouldn't have SPARK_HOME set
    env.put(SPARK_CONF_DIR, "$PWD");
    env.remove(SPARK_HOME);

    // Spark using YARN and it is needed for both Workflow and Spark runner. We need to set it
    // because inside Spark code, it will set and unset the SPARK_YARN_MODE system properties, causing
    // fork in distributed mode not working. Setting it in the environment, which Spark uses for defaults,
    // so it can't be unset by Spark
    env.put("SPARK_YARN_MODE", "true");

    return Collections.unmodifiableMap(env);
  }

  /**
   * Tries to read the spark default config file and put those configurations into the given map.
   *
   * @return
   */
  public static synchronized Properties getSparkDefaultConf() {
    Properties properties = new Properties();

    File confFile = SparkPackageUtils.locateSparkDefaultsConfFile(getSparkEnv());
    if (confFile == null) {
      return properties;
    }
    try (Reader reader = com.google.common.io.Files.newReader(confFile, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException e) {
      LOG.warn("Failed to load Spark default configurations from {}.", confFile, e);
    }
    return properties;
  }

  @Nullable
  private static File locateSparkDefaultsConfFile(Map<String, String> env) {
    File confFile = null;
    if (env.containsKey(SPARK_CONF_DIR)) {
      // If SPARK_CONF_DIR is defined, then the default conf should be under it
      confFile = new File(env.get(SPARK_CONF_DIR), SPARK_DEFAULTS_CONF);
    } else if (env.containsKey(SPARK_HOME)) {
      // Otherwise, it should be under SPARK_HOME/conf
      confFile = new File(new File(env.get(SPARK_HOME), "conf"), SPARK_DEFAULTS_CONF);
    }

    return confFile == null || !confFile.isFile() ? null : confFile;
  }

  private static File saveSparkDefaultConf(Properties sparkConf, File file) throws IOException {
    try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
      sparkConf.store(writer, null);
    }
    return file;
  }

  /**
   * Prepares the Spark 1 framework on the file system.
   *
   * @param sparkConf the spark configuration
   * @param locationFactory the {@link LocationFactory} for saving the spark framework jar
   * @return A {@link LocalizeResource} containing information about the spark framework in localization context.
   * @throws IOException If failed to prepare the framework.
   */
  private static LocalizeResource prepareSpark1Framework(Properties sparkConf,
                                                         LocationFactory locationFactory) throws IOException {
    String sparkYarnJar = sparkConf.getProperty(SPARK_YARN_JAR);

    Location frameworkLocation;
    if (sparkYarnJar != null) {
      frameworkLocation = locationFactory.create(URI.create(sparkYarnJar));
      if (!frameworkLocation.exists()) {
        LOG.warn("The location {} set by '{}' does not exist.", frameworkLocation, SPARK_YARN_JAR);
        return null;
      }
    } else {
      // If spark.yarn.jar is not defined, get the spark-assembly jar from local FS and upload it
      File sparkAssemblyJar = locateSparkAssemblyJar();
      Location frameworkDir = locationFactory.create("/framework/spark");

      frameworkLocation = frameworkDir.append(sparkAssemblyJar.getName());

      // Upload assembly jar to the framework location if not exists
      if (!frameworkLocation.exists()) {
        frameworkDir.mkdirs("755");

        try (OutputStream os = frameworkLocation.getOutputStream("644")) {
          Files.copy(sparkAssemblyJar.toPath(), os);
        }
      }
    }
    return new LocalizeResource(resolveURI(frameworkLocation), false);
  }

  private static URI resolveURI(Location location) throws IOException {
    LocationFactory locationFactory = location.getLocationFactory();

    while (locationFactory instanceof ForwardingLocationFactory) {
      locationFactory = ((ForwardingLocationFactory) locationFactory).getDelegate();
    }
    if (!(locationFactory instanceof FileContextLocationFactory)) {
      return location.toURI();
    }

    // Resolves the URI the way as Spark does
    Configuration hConf = ((FileContextLocationFactory) locationFactory).getConfiguration();
    org.apache.hadoop.fs.Path path = new org.apache.hadoop.fs.Path(location.toURI().getPath());
    path = path.getFileSystem(hConf).makeQualified(path);
    return ((FileContextLocationFactory) locationFactory).getFileContext().resolvePath(path).toUri();
  }

  private SparkPackageUtils() {
  }
}
