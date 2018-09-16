package com.marklogic.mule.extension.connector.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marklogic.client.DatabaseClient;
import com.marklogic.client.datamovement.DataMovementManager;
import com.marklogic.client.datamovement.JobReport;
import com.marklogic.client.datamovement.JobTicket;
import com.marklogic.client.datamovement.WriteBatcher;
import com.marklogic.client.document.ServerTransform;
import com.marklogic.client.io.DocumentMetadataHandle;
import com.marklogic.client.io.InputStreamHandle;

import java.io.InputStream;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by jkrebs on 9/12/2018.
 * Singleton class that manages inserting documents into MarkLogic
 */

public class MarkLogicInsertionBatcher {

    // The single instance of this class
    private static MarkLogicInsertionBatcher instance;

    // If support for multiple connection configs within a flow is required, remove the above and uncomment the below.
    // private static Map<String,MarkLogicInsertionBatcher> instances = new HashMap<>();

    // Object that describes the metadata for documents being inserted
    private final DocumentMetadataHandle metadataHandle;

    // TODO: How will we know when the resources are ready to be freed up and provide the results report?
    private final JobTicket jobTicket;

    // The object that actually write record to ML
    private WriteBatcher batcher;

    // Handle for DMSDK Data Movement Manager
    private DataMovementManager dmm;

    // The timestamp of the last write to ML-- used to determine when the pipe to ML should be flushed
    private long lastWriteTime;

    private ObjectMapper jsonFactory = new ObjectMapper();

    /**
     * Private constructor-- enforces singleton pattern
     * @param configuration -- information describing how the insertion process should work
     * @param connection -- information describing how to connect to MarkLogic
     */
    private MarkLogicInsertionBatcher(MarkLogicConfiguration configuration, MarkLogicConnection connection) {

        // get the object handles needed to talk to MarkLogic
        DatabaseClient myClient = connection.getClient();
        dmm = myClient.newDataMovementManager();
        batcher = dmm.newWriteBatcher();
        // Configure the batcher's behavior
        batcher.withBatchSize(configuration.getBatchSize())
                .withThreadCount(configuration.getThreadCount())
                .onBatchSuccess(batch-> {
                    String statusMessage = batch.getTimestamp().getTime() + " documents written: " + batch.getJobWritesSoFar();
                    System.out.println(statusMessage);

                })
                .onBatchFailure((batch,throwable) -> {
                    throwable.printStackTrace();
                });
        // Configure the transform to be used, if any
        // ASSUMPTION: The same transform (or lack thereof) will be used for every document to be inserted during the
        // lifetime of this object
        String configTransform = configuration.getServerTransform();
        if ((configTransform == null) || (configTransform.equals("null"))) {
            System.out.println("Ingesting doc payload without a transform");
        } else {
            ServerTransform thistransform = new ServerTransform(configTransform);
            String[] configTransformParams = configuration.getServerTransformParams();
            if (!configTransformParams[0].equals("null") && configTransformParams.length % 2 == 0) {
                for (int i = 0; i < configTransformParams.length - 1; i++) {
                    String paramName = configTransformParams[i];
                    String paramValue = configTransformParams[i + 1];
                    thistransform.addParameter(paramName, paramValue);
                }
            }
            batcher.withTransform(thistransform);
            System.out.println("Transforming input doc payload with transform: " + thistransform.getName());
        }

        // Set up the timer to flush the pipe to MarkLogic if it's waiting to long
        int secondsBeforeFlush = Integer.parseInt(configuration.getSecondsBeforeWriteFlush());

        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                // Check to see if the pipe has been inactive longer than the wait time
                if ((System.currentTimeMillis() - lastWriteTime) >= secondsBeforeFlush * 1000) {
                    // if it has, flush the pipe
                    batcher.flushAndWait();
                    // Set the last write time to be something well into the future, so that we don't needlessly,
                    // repeatedly flush the queue
                    lastWriteTime = System.currentTimeMillis() + 900000;
                }
            }
        }, (secondsBeforeFlush * 1000), secondsBeforeFlush * 1000);

        // Set up the metadata to be used for the documents that will be inserted
        // ASSUMPTION: The same metadata will be used for every document to be inserted during the lifetime of this
        // object
        this.metadataHandle = new DocumentMetadataHandle();
        String[] configCollections = configuration.getOutputCollections();

        // Set up list of collections that new docs should be put into
        if (!configCollections[0].equals("null")) {
            metadataHandle.withCollections(configCollections);
        }
        // Set up quality new docs should have
        metadataHandle.setQuality(configuration.getOutputQuality());

        // Set up list of permissions that new docs should be granted
        String[] permissions = configuration.getOutputPermissions();
        for (int i = 0; i < permissions.length - 1; i++) {
            String role = permissions[i];
            String capability = permissions[i + 1];
            switch(capability.toLowerCase()) {
                case "read" :
                    metadataHandle.getPermissions().add(role, DocumentMetadataHandle.Capability.READ);
                    break;
                case "insert" :
                    metadataHandle.getPermissions().add(role, DocumentMetadataHandle.Capability.INSERT);
                    break;
                case "update" :
                    metadataHandle.getPermissions().add(role, DocumentMetadataHandle.Capability.UPDATE);
                    break;
                case "execute" :
                    metadataHandle.getPermissions().add(role, DocumentMetadataHandle.Capability.EXECUTE);
                    break;
                case "node_update" :
                    metadataHandle.getPermissions().add(role, DocumentMetadataHandle.Capability.NODE_UPDATE);
                    break;
                default :
                    System.out.println("No additive permissions assigned");
            }
        }

        // start the batcher job
        this.jobTicket = dmm.startJob(batcher);
    }

    boolean jobIDMatches(String jobID) {
        return jobTicket.getJobId().equals(jobID);
    }

    /**
     * Creates a JSON object containing details about the batcher job
     * @return Job results report
     */
    String createJsonJobReport(String jobID) {
        if (!jobIDMatches(jobID)) {
            throw new java.lang.IllegalArgumentException("Job '" + jobID + "' not found.");
        }
        JobReport jr = dmm.getJobReport(jobTicket);
        ObjectNode obj = jsonFactory.createObjectNode();
        long successBatches = jr.getSuccessBatchesCount();
        long successEvents = jr.getSuccessEventsCount();
        long failBatches = jr.getFailureBatchesCount();
        long failEvents = jr.getFailureEventsCount();
        if (failEvents > 0) {
            obj.put("jobOutcome", "failed");
        } else {
            obj.put("jobOutcome", "successful");
        }
        obj.put("successfulBatches", successBatches);
        obj.put("successfulEvents", successEvents);
        obj.put("failedBatches", failBatches);
        obj.put("failedEvents", failEvents);
        obj.put("jobID", jobID);
        System.out.println(obj.toString());
        return obj.toString();
    }

    /**
     * getInstance-- used in lieu of a public constructor... enforces singleton pattern
     * @param config -- information describing how the insertion process should work
     * @param connection -- information describing how to connect to MarkLogic
     * @return instance of the batcher
     */
    static MarkLogicInsertionBatcher getInstance(MarkLogicConfiguration config, MarkLogicConnection connection) {
        // String configId = config.getConfigId();
        // MarkLogicInsertionBatcher instance = instances.get(configId);
        // Uncomment above to support multiple connection config scenario
        if (instance == null) {
            instance = new MarkLogicInsertionBatcher(config,connection);
            // instances.put(configId,instance);
            // Uncomment above to support multiple connection config scenario
        }
        return instance;
    }

    /**
     * getInstance method to be used when configuration objects aren't available
     * @return instance of the batcher
     */
    static MarkLogicInsertionBatcher getInstance() {
        if (instance == null) {
            throw new java.lang.IllegalStateException ("getInstance with parameters must be used to instantiate this object");
        }
        return instance;
    }

    /**
     * Actually does the work of passing the document on to DMSDK to do its thing
     * @param outURI -- the URI to be used for the document being inserted
     * @param documentStream -- the InputStream containing the document to be inserted... comes from mule
     * @return jobTicketID
     */
    String doInsert(String outURI, InputStream documentStream){
        // Add the InputStream to the DMSDK WriteBatcher object
        batcher.addAs(outURI, metadataHandle, new InputStreamHandle(documentStream));
        // Update the most recent insert's timestamp
        lastWriteTime = System.currentTimeMillis();
        // Have the DMSDK WriteBatcher object sleep until it is needed again
        batcher.awaitCompletion();
        // Return the job ticket ID so it can be used to retrieve the document in the future
        return jobTicket.getJobId();
    }
}
