package org.jbpm.elasticsearch.persistence.listener;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.drools.persistence.TransactionManager;
import org.drools.persistence.TransactionSynchronization;
import org.jbpm.elasticsearch.client.ElasticSearchClient;
import org.jbpm.elasticsearch.client.RestEasyElasticSearchClient;
import org.jbpm.elasticsearch.persistence.context.ProcessEventContext;
import org.jbpm.elasticsearch.persistence.context.ProcessEventContext.ProcessState;
import org.jbpm.elasticsearch.persistence.model.JbpmProcessDocument;
import org.kie.api.event.process.DefaultProcessEventListener;
import org.kie.api.event.process.ProcessCompletedEvent;
import org.kie.api.event.process.ProcessEvent;
import org.kie.api.event.process.ProcessEventListener;
import org.kie.api.event.process.ProcessStartedEvent;
import org.kie.api.event.process.ProcessVariableChangedEvent;
import org.kie.api.runtime.EnvironmentName;
import org.kie.api.runtime.process.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <code>KIE</code> {@link ProcessEventListener}, which indexes process data in ElasticSearch using the {@link ElasticSearchClient}.
 * 
 * @author <a href="mailto:duncan.doyle@redhat.com">Duncan Doyle</a>
 */
public class ElasticSearchProcessEventListener extends DefaultProcessEventListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(ElasticSearchProcessEventListener.class);

	private final ElasticSearchClient esClient;

	/*
	 * TODO: Shouldn't we use a Map of ProcessContexts?
	 * 
	 * A potential problem with using only one ProcessContext per thread is when we're using sub-process. We can have, for example, a
	 * process start event for the parent process and a process-start-event for the child process in the same transaction. When we use only
	 * a single process-context, one process-event could overwrite the other. This is probably not an issue if we use a KIE-Session PPI ....
	 *
	 * We need to test this properly. Might be a bit hard to do in a unit-test though (althought we could just create 2 process-start-events
	 * on one listener.
	 */
	// We use a Map of IndexTaskContexts as multiple tasks can be created in a single transaction.
	private ThreadLocal<Map<String, ProcessEventContext>> processContexts = new ThreadLocal<>();

	// private ThreadLocal<ProcessEventContext> processContext = new ThreadLocal<>();

	public ElasticSearchProcessEventListener(final String elasticSearchEndpointUrl) {
		esClient = new RestEasyElasticSearchClient(elasticSearchEndpointUrl);
	}

	@Override
	public void afterProcessStarted(ProcessStartedEvent event) {
		ProcessEventContext context = getProcessContext(event);
		context.setIndexingProcessState(ProcessState.STARTING);
		context.setProcessState(getProcessState(event));
	}

	@Override
	public void afterProcessCompleted(ProcessCompletedEvent event) {
		ProcessEventContext context = getProcessContext(event);
		context.setIndexingProcessState(ProcessState.COMPLETING);
		context.setProcessState(getProcessState(event));
	}

	@Override
	public void afterVariableChanged(ProcessVariableChangedEvent event) {
		ProcessEventContext context = getProcessContext(event);
		context.changeVariable(event.getVariableId(), event.getNewValue());
		context.setProcessState(getProcessState(event));
	}

	private String getProcessState(ProcessEvent event) {
		String stateName = "";
		int state = event.getProcessInstance().getState();
		switch (state) {
		case ProcessInstance.STATE_ABORTED:
			stateName = "ABORTED";
			break;
		case ProcessInstance.STATE_ACTIVE:
			stateName = "ACTIVE";
			break;
		case ProcessInstance.STATE_COMPLETED:
			stateName = "COMPLETED";
			break;
		case ProcessInstance.STATE_PENDING:
			stateName = "PENDING";
			break;
		case ProcessInstance.STATE_SUSPENDED:
			stateName = "SUSPENDED";
			break;
		}
		return stateName;
	}

	// We don't need to synchronize as we're working on a threadlocal.
	private ProcessEventContext getProcessContext(ProcessEvent processEvent) {
		Map<String, ProcessEventContext> contexts = processContexts.get();
		if (contexts == null) {
			// Register a transaction synchronization to index the variables on a TX commit.
			registerTransactionSynchronization(processEvent);

			contexts = new HashMap<>();
			processContexts.set(contexts);
		}

		ProcessEventContext context = contexts.get(getProcessEventContextKey(processEvent));
		if (context == null) {

			// Create a new context, and register for TX synchronization.
			long processInstanceId = processEvent.getProcessInstance().getId();
			LOGGER.debug("Building new IndexingContext for process-id: " + processInstanceId);
			String deploymentUnit = (String) processEvent.getKieRuntime().getEnvironment().get("deploymentId");
			String processId = processEvent.getProcessInstance().getProcessId();
			context = new ProcessEventContext(deploymentUnit, processId, processInstanceId);
			contexts.put(getProcessEventContextKey(processEvent), context);
		}
		return context;
	}

	/**
	 * Computes the key with which we can store the processEventContext in our Map.
	 * 
	 * @param processEvent
	 *            the {@link ProcessEvent}
	 * @return the computed key
	 */
	private String getProcessEventContextKey(ProcessEvent processEvent) {
		ProcessInstance processInstance = processEvent.getProcessInstance();
		return new StringBuilder().append(processInstance.getProcessId()).append("-").append(processInstance.getId()).toString();
	}

	private void registerTransactionSynchronization(ProcessEvent processEvent) {
		TransactionManager tmManager = (TransactionManager) processEvent.getKieRuntime().getEnvironment()
				.get(EnvironmentName.TRANSACTION_MANAGER);
		if (tmManager == null) {
			String message = "This process event listener requires access to a TransactionManager.";
			LOGGER.error(message);
			throw new IllegalStateException(message);
		}
		// Check that there is a transaction on the thread.
		if (tmManager.getStatus() == TransactionManager.STATUS_NO_TRANSACTION) {
			String message = "No transaction!";
			LOGGER.error(message);
			throw new IllegalStateException(message);
		}

		TransactionSynchronizationAdapter tsAdapter = new TransactionSynchronizationAdapter();
		tmManager.registerTransactionSynchronization(tsAdapter);
	}

	private class TransactionSynchronizationAdapter implements TransactionSynchronization {

		@Override
		public void beforeCompletion() {
			// TODO: Should we do something here?
		}

		@Override
		public void afterCompletion(int status) {
			LOGGER.debug("Indexing process after TX completion.");
			try {
				switch (status) {
				case TransactionManager.STATUS_COMMITTED:
					// Loop through all contexts and write to ElasticSearch for each task.
					Map<String, ProcessEventContext> contexts = ElasticSearchProcessEventListener.this.processContexts.get();
					if (contexts != null) {
						Collection<ProcessEventContext> contextValues = contexts.values();

						for (ProcessEventContext nextContext : contextValues) {

							if (nextContext != null) {
								JbpmProcessDocument processDocument = new JbpmProcessDocument(nextContext);
								String jsonProcessDocument = processDocument.toJsonString();
								String processDocumentId = processDocument.getDeploymentUnit() + "_" + processDocument.getProcessId() + "_"
										+ processDocument.getProcessInstanceId();
								LOGGER.debug("Indexing Document: " + jsonProcessDocument);

								switch (nextContext.getIndexingProcessState()) {
								case STARTING:
									esClient.indexProcessData(processDocumentId, jsonProcessDocument);
									break;
								case ACTIVE:
									esClient.updateProcessData(processDocumentId, jsonProcessDocument);
									break;
								case COMPLETING:
									// esProducer.delete
									// TODO: Should we update or remove here? I.e. do we want to maintain old processes in Elastic?
									esClient.updateProcessData(processDocumentId, jsonProcessDocument);
									break;
								default:
									String message = "Unexpected process state.";
									LOGGER.error(message);
									throw new IllegalStateException(message);
								}
							}
						}
					}
					break;
				case TransactionManager.STATUS_ROLLEDBACK:
					LOGGER.warn("Transaction rolled back. Discarding process indexing for this transaction.");
					break;
				default:
					String message = "Unexpected transaction outcome.";
					LOGGER.error(message);
					throw new IllegalStateException(message);
				}
			} finally {
				// Reset the ThreadLocal.
				processContexts.set(null);

			}
		}
	}

}
