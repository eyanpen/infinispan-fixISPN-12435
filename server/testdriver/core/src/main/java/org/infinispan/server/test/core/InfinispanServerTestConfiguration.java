package org.infinispan.server.test.core;

import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.jboss.shrinkwrap.api.spec.JavaArchive;

/**
 * @author Tristan Tarrant &lt;tristan@infinispan.org&gt;
 * @since 10.0
 **/
public class InfinispanServerTestConfiguration {

   private final String configurationFile;
   private final int numServers;
   private final ServerRunMode runMode;
   private final Properties properties;
   private final String[] mavenArtifacts;
   private final JavaArchive[] archives;
   private final boolean jmx;
   private final boolean parallelStartup;
   private final List<InfinispanServerListener> listeners;

   public InfinispanServerTestConfiguration(String configurationFile, int numServers, ServerRunMode runMode,
                                            Properties properties, String[] mavenArtifacts, JavaArchive[] archives,
                                            boolean jmx, boolean parallelStartup,
                                            List<InfinispanServerListener> listeners) {
      this.configurationFile = configurationFile;
      this.numServers = numServers;
      this.runMode = runMode;
      this.properties = properties;
      this.mavenArtifacts = mavenArtifacts;
      this.archives = archives;
      this.jmx = jmx;
      this.parallelStartup = parallelStartup;
      this.listeners = Collections.unmodifiableList(listeners);
   }

   public String configurationFile() {
      return configurationFile;
   }

   public int numServers() {
      return numServers;
   }

   public ServerRunMode runMode() {
      return runMode;
   }

   public Properties properties() {
      return properties;
   }

   public JavaArchive[] archives() {
      return archives;
   }

   public boolean isJMXEnabled() {
      return jmx;
   }

   public String[] mavenArtifacts() {
      return mavenArtifacts;
   }

   public boolean isParallelStartup() {
      return parallelStartup;
   }

   public List<InfinispanServerListener> listeners() {
      return listeners;
   }
}
