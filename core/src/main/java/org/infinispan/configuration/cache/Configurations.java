package org.infinispan.configuration.cache;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.infinispan.commons.configuration.ConfigurationBuilderInfo;
import org.infinispan.commons.util.ServiceFinder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.internal.PrivateGlobalConfiguration;
import org.infinispan.configuration.parsing.ConfigurationParser;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.distribution.TriangleOrderManager;
import org.infinispan.transaction.LockingMode;
import org.infinispan.util.concurrent.IsolationLevel;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * Helper configuration methods.
 *
 * @author Galder Zamarreño
 * @author Pedro Ruivo
 * @since 5.2
 */
public class Configurations {
   private static final Log logger = LogFactory.getLog(TriangleOrderManager.class);
   // Suppresses default constructor, ensuring non-instantiability.
   private Configurations() {
   }

   public static boolean isExceptionBasedEviction(Configuration cfg) {
      return cfg.memory().size() > 0 && cfg.memory().evictionStrategy().isExceptionBased();
   }

   public static boolean isOnePhaseCommit(Configuration cfg) {
      // Otherwise pessimistic transactions will be one phase commit
      if (isExceptionBasedEviction(cfg)) {
         return false;
      }
      return !cfg.clustering().cacheMode().isSynchronous() ||
            cfg.transaction().lockingMode() == LockingMode.PESSIMISTIC;
   }

   public static boolean isOnePhaseTotalOrderCommit(Configuration cfg) {
      // Even total order needs 2 phase commit with this
      if (isExceptionBasedEviction(cfg)) {
         return false;
      }
      return cfg.transaction().transactionMode().isTransactional() &&
            cfg.transaction().transactionProtocol().isTotalOrder() &&
            !isTxVersioned(cfg);
   }

   public static boolean isTxVersioned(Configuration cfg) {
      return cfg.transaction().transactionMode().isTransactional() &&
            cfg.transaction().lockingMode() == LockingMode.OPTIMISTIC &&
            cfg.locking().isolationLevel() == IsolationLevel.REPEATABLE_READ &&
            !cfg.clustering().cacheMode().isInvalidation(); //invalidation can't use versions
   }

   public static boolean noDataLossOnJoiner(Configuration configuration) {
      //local caches does not have joiners
      if (!configuration.clustering().cacheMode().isClustered()) {
         return true;
      }
      //shared cache store has all the data
      if (hasSharedCacheLoaderOrWriter(configuration)) {
         return true;
      }
      final boolean usingStores = configuration.persistence().usingStores();
      final boolean passivation = configuration.persistence().passivation();
      final boolean fetchInMemoryState = configuration.clustering().stateTransfer().fetchInMemoryState();
      final boolean fetchPersistenceState = configuration.persistence().fetchPersistentState();
      //local cache store without passivation, with fetchPersistentState, regardless of fetchInMemoryState
      return (usingStores && !passivation && (fetchInMemoryState || fetchPersistenceState)) ||
            //local cache store with passivation, with fetchPersistentState && fetchInMemoryState
            (usingStores && passivation && fetchInMemoryState && fetchPersistenceState) ||
            //no cache stores, fetch in memory state
            (!usingStores && fetchInMemoryState);
   }

   public static boolean hasSharedCacheLoaderOrWriter(Configuration configuration) {
      for (StoreConfiguration storeConfiguration : configuration.persistence().stores()) {
         if (storeConfiguration.shared()) {
            return true;
         }
      }
      return false;
   }

   public static boolean isEmbeddedMode(GlobalConfiguration globalConfiguration) {
      PrivateGlobalConfiguration config = globalConfiguration.module(PrivateGlobalConfiguration.class);
      //logger.warn(String.format("isEmbeddedMode:%s",config ),new RuntimeException("isEmbeddedMode calltrace"));
      boolean flag=( config == null || !config.isServerMode());
      logger.info(String.format("isEmbeddedMode:%b,%s",flag,config ));
      return flag;
   }

   public static boolean isClustered(GlobalConfiguration globalConfiguration) {
      return globalConfiguration.transport().transport() != null;
   }

   public static boolean isStateTransferStore(StoreConfiguration storeConfiguration) {
      return !storeConfiguration.shared() && storeConfiguration.fetchPersistentState();
   }

   public static boolean needSegments(Configuration configuration) {
      CacheMode cacheMode = configuration.clustering().cacheMode();
      boolean transactional = configuration.transaction().transactionMode().isTransactional();
      boolean usingSegmentedStore = configuration.persistence().usingSegmentedStore();
      return (cacheMode.isReplicated() ||
              cacheMode.isDistributed() ||
              cacheMode.isScattered() ||
              (cacheMode.isInvalidation() && transactional) ||
              usingSegmentedStore);
   }

   static Set<Class<? extends StoreConfigurationBuilder<?, ?>>> lookupPersistenceBuilders() {
      Collection<ConfigurationParser> parsers = ServiceFinder.load(ConfigurationParser.class,
                                                                   Configurations.class.getClassLoader(),
                                                                   ParserRegistry.class.getClassLoader());
      Set<Class<? extends StoreConfigurationBuilder<?, ?>>> builders = new HashSet<>();
      for (ConfigurationParser parser : parsers) {
         Class<? extends ConfigurationBuilderInfo> builderClass = parser.getConfigurationBuilderInfo();
         if (builderClass != null) {
            if (AbstractStoreConfigurationBuilder.class.isAssignableFrom(builderClass)) {
               builders.add((Class<? extends StoreConfigurationBuilder<?, ?>>) builderClass);
            }
         }
      }
      return builders;
   }
}
