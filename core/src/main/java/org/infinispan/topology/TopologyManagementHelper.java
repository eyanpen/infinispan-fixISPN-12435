package org.infinispan.topology;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.infinispan.util.logging.Log.CLUSTER;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.infinispan.commands.ReplicableCommand;
import org.infinispan.commons.util.Util;
import org.infinispan.factories.impl.BasicComponentRegistry;
import org.infinispan.remoting.inboundhandler.DeliverOrder;
import org.infinispan.remoting.responses.ExceptionResponse;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.ResponseCollector;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.remoting.transport.impl.SingleResponseCollector;
import org.infinispan.util.concurrent.CompletableFutures;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

public class TopologyManagementHelper {
   private static final Log log = LogFactory.getLog(TopologyManagementHelper.class);
   private static final boolean trace = log.isTraceEnabled();

   private BasicComponentRegistry gcr;

   public TopologyManagementHelper(BasicComponentRegistry gcr) {
      this.gcr = gcr;
   }

   public <T> CompletionStage<T> executeOnClusterSync(Transport transport, ReplicableCommand command,
                                                      int timeout, boolean totalOrder,
                                                      ResponseCollector<T> responseCollector) {
      // Total order invokes the command on the local node automatically
      if (totalOrder) {
         return transport.invokeCommandOnAll(command, responseCollector, DeliverOrder.TOTAL, timeout, MILLISECONDS);
      }

      // First invoke the command remotely, but make sure we don't call finish() on the collector
      ResponseCollector<Void> delegatingCollector = new DelegatingResponseCollector<>(responseCollector);
      CompletionStage<Void> remoteFuture =
            transport.invokeCommandOnAll(command, delegatingCollector, DeliverOrder.NONE, timeout, MILLISECONDS);

      // Then invoke the command on the local node
      CompletableFuture<Object> localFuture;
      try {
         if (trace)
            log.tracef("Attempting to execute command on self: %s", command);
         gcr.wireDependencies(command, true);
         localFuture = command.invokeAsync();
      } catch (Throwable throwable) {
         localFuture = CompletableFutures.completedExceptionFuture(throwable);
      }

      return addLocalResult(responseCollector, remoteFuture, localFuture, transport.getAddress());
   }

   public void executeOnClusterAsync(Transport transport, ReplicableCommand command, boolean totalOrder) {
      // invoke remotely
      try {
         DeliverOrder deliverOrder = totalOrder ? DeliverOrder.TOTAL : DeliverOrder.NONE;
         transport.sendToAll(command, deliverOrder);
      } catch (Exception e) {
         throw Util.rewrapAsCacheException(e);
      }

      if (!totalOrder) {
         // invoke the command on the local node
         try {
            if (trace)
               log.tracef("Attempting to execute command on self: %s", command);
            gcr.wireDependencies(command, true);
            command.invokeAsync();
         } catch (Throwable throwable) {
            // The command already logs any exception in invoke()
         }
      }
   }

   public CompletionStage<Object> executeOnCoordinator(Transport transport, ReplicableCommand command,
                                                       long timeoutMillis) {
      CompletionStage<? extends Response> responseStage;
      Address coordinator = transport.getCoordinator();
      if (transport.getAddress().equals(coordinator)) {
         try {
            if (trace)
               log.tracef("Attempting to execute command on self: %s", command);
            gcr.wireDependencies(command, true);
            responseStage = command.invokeAsync().thenApply(v -> makeResponse(v, null, transport.getAddress()));
         } catch (Throwable t) {
            throw CompletableFutures.asCompletionException(t);
         }
      } else {
         // this node is not the coordinator
         responseStage = transport.invokeCommand(coordinator, command, SingleResponseCollector.validOnly(),
                                                 DeliverOrder.NONE, timeoutMillis, TimeUnit.MILLISECONDS);
      }
      return responseStage.thenApply(response -> {
         if (!(response instanceof SuccessfulResponse)) {
            throw CLUSTER.unexpectedResponse(coordinator, response);
         }
         return ((SuccessfulResponse) response).getResponseValue();
      });
   }

   public void executeOnCoordinatorAsync(Transport transport, ReplicableCommand command) throws Exception {
      if (transport.isCoordinator()) {
         if (trace)
            log.tracef("Attempting to execute command on self: %s", command);
         try {
            gcr.wireDependencies(command, true);
            // ignore the result
            command.invokeAsync();
         } catch (Throwable t) {
            log.errorf(t, "Failed to execute ReplicableCommand %s on coordinator async: %s", command, t.getMessage());
         }
      } else {
         Address coordinator = transport.getCoordinator();
         // ignore the response
         transport.sendTo(coordinator, command, DeliverOrder.NONE);
      }
   }

   private <T> CompletionStage<T> addLocalResult(ResponseCollector<T> responseCollector,
                                                 CompletionStage<Void> remoteFuture,
                                                 CompletableFuture<Object> localFuture, Address localAddress) {
      return remoteFuture.thenCompose(ignore -> localFuture.handle((v, t) -> {
         Response localResponse = makeResponse(v, t, localAddress);

         // No more responses are coming, so we don't need to synchronize
         responseCollector.addResponse(localAddress, localResponse);
         return responseCollector.finish();
      }));
   }

   private Response makeResponse(Object v, Throwable t, Address localAddress) {
      Response localResponse;
      if (t != null) {
         localResponse = new ExceptionResponse(
               CLUSTER.remoteException(localAddress, CompletableFutures.extractException(t)));
      } else {
         if (v instanceof Response) {
            localResponse = ((Response) v);
         } else {
            localResponse = SuccessfulResponse.create(v);
         }
      }
      return localResponse;
   }

   private static class DelegatingResponseCollector<T> implements ResponseCollector<Void> {
      private final ResponseCollector<T> responseCollector;

      public DelegatingResponseCollector(ResponseCollector<T> responseCollector) {
         this.responseCollector = responseCollector;
      }

      @Override
      public Void addResponse(Address sender, Response response) {
         responseCollector.addResponse(sender, response);
         return null;
      }

      @Override
      public Void finish() {
         return null;
      }
   }
}
