/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.client.unified;

import com.mongodb.ClientEncryptionSettings;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoNamespace;
import com.mongodb.ReadPreference;
import com.mongodb.logging.TestLoggingInterceptor;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.model.Filters;
import com.mongodb.client.test.CollectionHelper;
import com.mongodb.client.vault.ClientEncryption;
import com.mongodb.connection.ClusterDescription;
import com.mongodb.connection.ClusterType;
import com.mongodb.connection.ServerDescription;
import com.mongodb.event.CommandEvent;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.internal.connection.TestCommandListener;
import com.mongodb.internal.connection.TestConnectionPoolListener;
import com.mongodb.lang.NonNull;
import com.mongodb.lang.Nullable;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.codecs.BsonDocumentCodec;
import org.junit.After;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.mongodb.ClusterFixture.getServerVersion;
import static com.mongodb.client.Fixture.getMongoClient;
import static com.mongodb.client.Fixture.getMongoClientSettings;
import static com.mongodb.client.unified.RunOnRequirementsMatcher.runOnRequirementsMet;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static util.JsonPoweredTestHelper.getTestDocument;
import static util.JsonPoweredTestHelper.getTestFiles;

@RunWith(Parameterized.class)
public abstract class UnifiedTest {
    private static final Set<String> PRESTART_POOL_ASYNC_WORK_MANAGER_FILE_DESCRIPTIONS = Collections.singleton(
            "wait queue timeout errors include details about checked out connections");

    @Nullable
    private final String fileDescription;
    private final String schemaVersion;
    private final BsonArray runOnRequirements;
    private final BsonArray entitiesArray;
    private final BsonArray initialData;
    private final BsonDocument definition;
    private final Entities entities = new Entities();
    private final UnifiedCrudHelper crudHelper;
    private final UnifiedGridFSHelper gridFSHelper = new UnifiedGridFSHelper(entities);
    private final UnifiedClientEncryptionHelper clientEncryptionHelper = new UnifiedClientEncryptionHelper(entities);
    private final List<FailPoint> failPoints = new ArrayList<>();
    private final UnifiedTestContext rootContext = new UnifiedTestContext();

    private class UnifiedTestContext {
        private final AssertionContext context = new AssertionContext();
        private final ValueMatcher valueMatcher = new ValueMatcher(entities, context);
        private final ErrorMatcher errorMatcher = new ErrorMatcher(context, valueMatcher);
        private final EventMatcher eventMatcher = new EventMatcher(valueMatcher, context);
        private final LogMatcher logMatcher = new LogMatcher(valueMatcher, context);

        AssertionContext getAssertionContext() {
            return context;
        }

        ValueMatcher getValueMatcher() {
            return valueMatcher;
        }

        ErrorMatcher getErrorMatcher() {
            return errorMatcher;
        }

        EventMatcher getEventMatcher() {
            return eventMatcher;
        }

        LogMatcher getLogMatcher() {
            return logMatcher;
        }
    }

    public UnifiedTest(@Nullable final String fileDescription, final String schemaVersion, @Nullable final BsonArray runOnRequirements,
            final BsonArray entitiesArray, final BsonArray initialData, final BsonDocument definition) {
        this.fileDescription = fileDescription;
        this.schemaVersion = schemaVersion;
        this.runOnRequirements = runOnRequirements;
        this.entitiesArray = entitiesArray;
        this.initialData = initialData;
        this.definition = definition;
        this.rootContext.getAssertionContext().push(ContextElement.ofTest(definition));
        crudHelper = new UnifiedCrudHelper(entities, definition.getString("description").getValue());
    }

    public Entities getEntities() {
        return entities;
    }

    @NonNull
    protected static Collection<Object[]> getTestData(final String directory) throws URISyntaxException, IOException {
        List<Object[]> data = new ArrayList<>();
        for (File file : getTestFiles("/" + directory + "/")) {
            BsonDocument fileDocument = getTestDocument(file);

            for (BsonValue cur : fileDocument.getArray("tests")) {
                data.add(UnifiedTest.createTestData(fileDocument, cur.asDocument()));
            }
        }
        return data;
    }

    @NonNull
    private static Object[] createTestData(final BsonDocument fileDocument, final BsonDocument testDocument) {
        return new Object[]{
                fileDocument.getString("description").getValue(),
                testDocument.getString("description").getValue(),
                fileDocument.getString("schemaVersion").getValue(),
                fileDocument.getArray("runOnRequirements", null),
                fileDocument.getArray("createEntities", new BsonArray()),
                fileDocument.getArray("initialData", new BsonArray()),
                testDocument};
    }

    protected abstract MongoClient createMongoClient(MongoClientSettings settings);

    protected abstract GridFSBucket createGridFSBucket(MongoDatabase database);

    protected abstract ClientEncryption createClientEncryption(MongoClient keyVaultClient, ClientEncryptionSettings clientEncryptionSettings);

    @Before
    public void setUp() {
        assertTrue(String.format("Unsupported schema version %s", schemaVersion),
                schemaVersion.equals("1.0")
                        || schemaVersion.equals("1.1")
                        || schemaVersion.equals("1.2")
                        || schemaVersion.equals("1.3")
                        || schemaVersion.equals("1.4")
                        || schemaVersion.equals("1.5")
                        || schemaVersion.equals("1.6")
                        || schemaVersion.equals("1.7")
                        || schemaVersion.equals("1.8")
                        || schemaVersion.equals("1.9")
                        || schemaVersion.equals("1.10")
                        || schemaVersion.equals("1.11")
                        || schemaVersion.equals("1.12")
                        || schemaVersion.equals("1.13")
                        || schemaVersion.equals("1.14")
                        || schemaVersion.equals("1.15"));
        if (runOnRequirements != null) {
            assumeTrue("Run-on requirements not met",
                    runOnRequirementsMet(runOnRequirements, getMongoClientSettings(), getServerVersion()));
        }
        if (definition.containsKey("runOnRequirements")) {
            assumeTrue("Run-on requirements not met",
                    runOnRequirementsMet(definition.getArray("runOnRequirements", new BsonArray()), getMongoClientSettings(),
                            getServerVersion()));
        }
        if (definition.containsKey("skipReason")) {
            throw new AssumptionViolatedException(definition.getString("skipReason").getValue());
        }
        entities.init(entitiesArray,
                fileDescription != null && PRESTART_POOL_ASYNC_WORK_MANAGER_FILE_DESCRIPTIONS.contains(fileDescription),
                this::createMongoClient,
                this::createGridFSBucket,
                this::createClientEncryption);
        addInitialData();
    }

    @After
    public void cleanUp() {
        for (FailPoint failPoint : failPoints) {
            failPoint.disableFailPoint();
        }
        entities.close();
    }

    @Test
    public void shouldPassAllOutcomes() {
        BsonArray operations = definition.getArray("operations");
        for (int i = 0; i < operations.size(); i++) {
            BsonValue cur = operations.get(i);
            assertOperation(rootContext, cur.asDocument(), i);
        }

        if (definition.containsKey("outcome")) {
            assertOutcome(rootContext);
        }

        if (definition.containsKey("expectEvents")) {
            compareEvents(rootContext, definition);
        }

        if (definition.containsKey("expectLogMessages")) {
            compareLogMessages(rootContext, definition);
        }
    }

    private void compareEvents(final UnifiedTestContext context, final BsonDocument definition) {
        for (BsonValue cur : definition.getArray("expectEvents")) {
            BsonDocument curClientEvents = cur.asDocument();
            String client = curClientEvents.getString("client").getValue();
            boolean ignoreExtraEvents = curClientEvents.getBoolean("ignoreExtraEvents", BsonBoolean.FALSE).getValue();
            String eventType = curClientEvents.getString("eventType", new BsonString("command")).getValue();
            if (eventType.equals("command")) {
                TestCommandListener listener = entities.getClientCommandListener(client);
                context.getEventMatcher().assertCommandEventsEquality(client, ignoreExtraEvents, curClientEvents.getArray("events"),
                        listener.getEvents());
            } else if (eventType.equals("cmap")) {
                TestConnectionPoolListener listener = entities.getConnectionPoolListener(client);
                context.getEventMatcher().assertConnectionPoolEventsEquality(client, ignoreExtraEvents, curClientEvents.getArray("events"),
                        listener.getEvents());
            } else {
                throw new UnsupportedOperationException("Unexpected event type: " + eventType);
            }
        }
    }

    private void compareLogMessages(final UnifiedTestContext rootContext, final BsonDocument definition) {
        for (BsonValue cur : definition.getArray("expectLogMessages")) {
            BsonDocument curLogMessagesForClient = cur.asDocument();
            String clientId = curLogMessagesForClient.getString("client").getValue();
            TestLoggingInterceptor loggingInterceptor =
                    entities.getClientLoggingInterceptor(clientId);
            rootContext.getLogMatcher().assertLogMessageEquality(clientId, curLogMessagesForClient.getArray("messages"),
                    loggingInterceptor.getMessages());
        }
    }

    private void assertOutcome(final UnifiedTestContext context) {
        for (BsonValue cur : definition.getArray("outcome")) {
            BsonDocument curDocument = cur.asDocument();
            MongoNamespace namespace = new MongoNamespace(curDocument.getString("databaseName").getValue(),
                    curDocument.getString("collectionName").getValue());
            List<BsonDocument> expectedOutcome = curDocument.getArray("documents").stream().map(BsonValue::asDocument).collect(toList());
            List<BsonDocument> actualOutcome = new CollectionHelper<>(new BsonDocumentCodec(), namespace).find();
            context.getAssertionContext().push(ContextElement.ofOutcome(namespace, expectedOutcome, actualOutcome));
            assertEquals(context.getAssertionContext().getMessage("Outcomes are not equal"), expectedOutcome, actualOutcome);
            context.getAssertionContext().pop();
        }
    }

    private void assertOperation(final UnifiedTestContext context, final BsonDocument operation, final int operationIndex) {
        OperationResult result = executeOperation(context, operation, operationIndex);
        context.getAssertionContext().push(ContextElement.ofCompletedOperation(operation, result, operationIndex));
        if (!operation.getBoolean("ignoreResultAndError", BsonBoolean.FALSE).getValue()) {
            if (operation.containsKey("expectResult")) {
                assertNull(context.getAssertionContext().getMessage("The operation expects a result but an exception occurred"),
                        result.getException());
                context.getValueMatcher().assertValuesMatch(operation.get("expectResult"), result.getResult());
            } else if (operation.containsKey("expectError")) {
                assertNotNull(context.getAssertionContext().getMessage("The operation expects an error but no exception was thrown"),
                        result.getException());
                context.getErrorMatcher().assertErrorsMatch(operation.getDocument("expectError"), result.getException());
            } else {
                assertNull(context.getAssertionContext().getMessage("The operation expects no error but an exception occurred"),
                        result.getException());
            }
        }
        context.getAssertionContext().pop();
    }

    private OperationResult executeOperation(final UnifiedTestContext context, final BsonDocument operation, final int operationNum) {
        context.getAssertionContext().push(ContextElement.ofStartedOperation(operation, operationNum));
        String name = operation.getString("name").getValue();
        try {
            switch (name) {
                case "createEntities":
                    return executeCreateEntities(operation);
                case "wait":
                    return executeWait(operation);
                case "waitForEvent":
                    return executeWaitForEvent(context, operation);
                case "waitForPrimaryChange":
                    return executeWaitPrimaryChange(context, operation);
                case "waitForThread":
                    return executeWaitForThread(context, operation);
                case "recordTopologyDescription":
                    return executeRecordTopologyDescription(operation);
                case "assertTopologyType":
                    return executeAssertTopologyType(context, operation);
                case "runOnThread":
                    return executeRunOnThread(context, operation);
                case "assertEventCount":
                    return executeAssertEventCount(context, operation);
                case "failPoint":
                    return executeFailPoint(operation);
                case "targetedFailPoint":
                    return executeTargetedFailPoint(operation);
                case "endSession":
                    return executeEndSession(operation);
                case "assertSessionDirty":
                    return executeAssertSessionDirty(operation);
                case "assertSessionNotDirty":
                    return executeAssertSessionNotDirty(operation);
                case "assertSessionPinned":
                    return executeAssertSessionPinned(operation);
                case "assertSessionUnpinned":
                    return executeAssertSessionUnpinned(operation);
                case "assertSameLsidOnLastTwoCommands":
                    return executeAssertSameLsidOnLastTwoCommands(operation);
                case "assertDifferentLsidOnLastTwoCommands":
                    return executeAssertDifferentLsidOnLastTwoCommands(operation);
                case "assertNumberConnectionsCheckedOut":
                    return executeAssertNumberConnectionsCheckedOut(context, operation);
                case "assertSessionTransactionState":
                    return executeAssertSessionTransactionState(operation);
                case "assertCollectionExists":
                    return executeAssertCollectionExists(operation);
                case "assertCollectionNotExists":
                    return executeAssertCollectionNotExists(operation);
                case "assertIndexExists":
                    return executeAssertIndexExists(operation);
                case "assertIndexNotExists":
                    return executeAssertIndexNotExists(operation);
                case "bulkWrite":
                    return crudHelper.executeBulkWrite(operation);
                case "insertOne":
                    return crudHelper.executeInsertOne(operation);
                case "insertMany":
                    return crudHelper.executeInsertMany(operation);
                case "updateOne":
                    return crudHelper.executeUpdateOne(operation);
                case "updateMany":
                    return crudHelper.executeUpdateMany(operation);
                case "replaceOne":
                    return crudHelper.executeReplaceOne(operation);
                case "deleteOne":
                    return crudHelper.executeDeleteOne(operation);
                case "deleteMany":
                    return crudHelper.executeDeleteMany(operation);
                case "aggregate":
                    return crudHelper.executeAggregate(operation);
                case "find":
                    return crudHelper.executeFind(operation);
                case "findOne":
                    return crudHelper.executeFindOne(operation);
                case "distinct":
                    return crudHelper.executeDistinct(operation);
                case "countDocuments":
                    return crudHelper.executeCountDocuments(operation);
                case "estimatedDocumentCount":
                    return crudHelper.executeEstimatedDocumentCount(operation);
                case "findOneAndUpdate":
                    return crudHelper.executeFindOneAndUpdate(operation);
                case "findOneAndReplace":
                    return crudHelper.executeFindOneAndReplace(operation);
                case "findOneAndDelete":
                    return crudHelper.executeFindOneAndDelete(operation);
                case "listDatabases":
                    return crudHelper.executeListDatabases(operation);
                case "listDatabaseNames":
                    return crudHelper.executeListDatabaseNames(operation);
                case "listCollections":
                    return crudHelper.executeListCollections(operation);
                case "listCollectionNames":
                    return crudHelper.executeListCollectionNames(operation);
                case "listIndexes":
                    return crudHelper.executeListIndexes(operation);
                case "listIndexNames":
                    return crudHelper.executeListIndexNames(operation);
                case "dropCollection":
                    return crudHelper.executeDropCollection(operation);
                case "createCollection":
                    return crudHelper.executeCreateCollection(operation);
                case "modifyCollection":
                    return crudHelper.executeModifyCollection(operation);
                case "rename":
                    return crudHelper.executeRenameCollection(operation);
                case "createSearchIndex":
                    return crudHelper.executeCreateSearchIndex(operation);
                case "createSearchIndexes":
                    return crudHelper.executeCreateSearchIndexes(operation);
                case "updateSearchIndex":
                    return crudHelper.executeUpdateSearchIndex(operation);
                case "dropSearchIndex":
                    return crudHelper.executeDropSearchIndex(operation);
                case "listSearchIndexes":
                    return crudHelper.executeListSearchIndexes(operation);
                case "createIndex":
                    return crudHelper.executeCreateIndex(operation);
                case "dropIndex":
                    return crudHelper.executeDropIndex(operation);
                case "startTransaction":
                    return crudHelper.executeStartTransaction(operation);
                case "commitTransaction":
                    return crudHelper.executeCommitTransaction(operation);
                case "abortTransaction":
                    return crudHelper.executeAbortTransaction(operation);
                case "withTransaction":
                    return crudHelper.executeWithTransaction(operation, (op, idx) -> assertOperation(context, op, idx));
                case "createFindCursor":
                    return crudHelper.createFindCursor(operation);
                case "createChangeStream":
                    return crudHelper.createChangeStreamCursor(operation);
                case "close":
                    return crudHelper.close(operation);
                case "iterateUntilDocumentOrError":
                    return crudHelper.executeIterateUntilDocumentOrError(operation);
                case "delete":
                    return gridFSHelper.executeDelete(operation);
                case "download":
                    return gridFSHelper.executeDownload(operation);
                case "downloadByName":
                    return gridFSHelper.executeDownloadByName(operation);
                case "upload":
                    return gridFSHelper.executeUpload(operation);
                case "runCommand":
                    return crudHelper.executeRunCommand(operation);
                case "loop":
                    return loop(context, operation);
                case "createDataKey":
                    return clientEncryptionHelper.executeCreateDataKey(operation);
                case "addKeyAltName":
                    return clientEncryptionHelper.executeAddKeyAltName(operation);
                case "deleteKey":
                    return clientEncryptionHelper.executeDeleteKey(operation);
                case "removeKeyAltName":
                    return clientEncryptionHelper.executeRemoveKeyAltName(operation);
                case "getKey":
                    return clientEncryptionHelper.executeGetKey(operation);
                case "getKeys":
                    return clientEncryptionHelper.executeGetKeys(operation);
                case "getKeyByAltName":
                    return clientEncryptionHelper.executeGetKeyByAltName(operation);
                case "rewrapManyDataKey":
                    return clientEncryptionHelper.executeRewrapManyDataKey(operation);
                default:
                    throw new UnsupportedOperationException("Unsupported test operation: " + name);
            }
        } finally {
            context.getAssertionContext().pop();
        }
    }

    private OperationResult loop(final UnifiedTestContext context, final BsonDocument operation) {
        BsonDocument arguments = operation.getDocument("arguments");

        int numIterations = 0;
        int numSuccessfulOperations = 0;
        boolean storeFailures = arguments.containsKey("storeFailuresAsEntity");
        boolean storeErrors = arguments.containsKey("storeErrorsAsEntity");
        BsonArray failureDescriptionDocuments = new BsonArray();
        BsonArray errorDescriptionDocuments = new BsonArray();

        while (!terminateLoop()) {
            BsonArray array = arguments.getArray("operations");
            for (int i = 0; i < array.size(); i++) {
                BsonValue cur = array.get(i);
                try {
                    assertOperation(context, cur.asDocument().clone(), i);
                    numSuccessfulOperations++;
                } catch (AssertionError e) {
                    if (storeFailures) {
                        failureDescriptionDocuments.add(createDocumentFromException(e));
                    } else if (storeErrors) {
                        errorDescriptionDocuments.add(createDocumentFromException(e));
                    } else {
                        throw e;
                    }
                    break;
                } catch (Exception e) {
                    if (storeErrors) {
                        errorDescriptionDocuments.add(createDocumentFromException(e));
                    } else if (storeFailures) {
                        failureDescriptionDocuments.add(createDocumentFromException(e));
                    } else {
                        throw e;
                    }
                    break;
                }
            }
            numIterations++;
        }

        if (arguments.containsKey("storeSuccessesAsEntity")) {
            entities.addSuccessCount(arguments.getString("storeSuccessesAsEntity").getValue(), numSuccessfulOperations);
        }
        if (arguments.containsKey("storeIterationsAsEntity")) {
            entities.addIterationCount(arguments.getString("storeIterationsAsEntity").getValue(), numIterations);
        }
        if (storeFailures) {
            entities.addFailureDocuments(arguments.getString("storeFailuresAsEntity").getValue(), failureDescriptionDocuments);
        }
        if (storeErrors) {
            entities.addErrorDocuments(arguments.getString("storeErrorsAsEntity").getValue(), errorDescriptionDocuments);
        }

        return OperationResult.NONE;
    }

    private BsonDocument createDocumentFromException(final Throwable throwable) {
        return new BsonDocument("error", new BsonString(throwable.toString()))
                .append("time", new BsonDouble(System.currentTimeMillis() / 1000.0));
    }

    protected boolean terminateLoop() {
        return true;
    }

    private OperationResult executeCreateEntities(final BsonDocument operation) {
        entities.init(operation.getDocument("arguments").getArray("entities"),
                false,
                this::createMongoClient,
                this::createGridFSBucket,
                this::createClientEncryption);
        return OperationResult.NONE;
    }

    private OperationResult executeWait(final BsonDocument operation) {
        try {
            Thread.sleep(operation.getDocument("arguments").getNumber("ms").longValue());
            return OperationResult.NONE;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private OperationResult executeWaitForEvent(final UnifiedTestContext context, final BsonDocument operation) {
        BsonDocument arguments = operation.getDocument("arguments");
        String clientId = arguments.getString("client").getValue();
        BsonDocument event = arguments.getDocument("event");
        String eventName = event.getFirstKey();
        int count = arguments.getNumber("count").intValue();

        switch (eventName) {
            case "serverDescriptionChangedEvent":
                context.getEventMatcher().waitForServerDescriptionChangedEvents(clientId, event, count,
                        entities.getServerListener(clientId));
                break;
            case "poolClearedEvent":
            case "poolReadyEvent":
            case "connectionCreatedEvent":
            case "connectionReadyEvent":
                context.getEventMatcher().waitForConnectionPoolEvents(clientId, event, count, entities.getConnectionPoolListener(clientId));
                break;
            default:
                throw new UnsupportedOperationException("Unsupported event: " + eventName);
        }

        return OperationResult.NONE;
    }

    private OperationResult executeAssertEventCount(final UnifiedTestContext context, final BsonDocument operation) {
        BsonDocument arguments = operation.getDocument("arguments");
        String clientId = arguments.getString("client").getValue();
        BsonDocument event = arguments.getDocument("event");
        String eventName = event.getFirstKey();
        int count = arguments.getNumber("count").intValue();

        switch (eventName) {
            case "serverDescriptionChangedEvent":
                context.getEventMatcher().assertServerDescriptionChangeEventCount(clientId, event, count,
                        entities.getServerListener(clientId).getServerDescriptionChangedEvents());
                break;
            case "poolClearedEvent":
            case "poolReadyEvent":
                context.getEventMatcher().assertConnectionPoolEventCount(clientId, event, count,
                        entities.getConnectionPoolListener(clientId).getEvents());
                break;
            default:
                throw new UnsupportedOperationException("Unsupported event: " + eventName);
        }

        return OperationResult.NONE;
    }

    private OperationResult executeWaitPrimaryChange(final UnifiedTestContext context, final BsonDocument operation) {
        context.getAssertionContext().push(ContextElement.ofWaitForPrimaryChange());
        BsonDocument arguments = operation.getDocument("arguments");
        MongoClient client = entities.getClient(arguments.getString("client").getValue());
        ClusterDescription priorClusterDescription =
                entities.getTopologyDescription(arguments.getString("priorTopologyDescription").getValue());
        ClusterDescription currentClusterDescription = client.getClusterDescription();
        long timeoutNanos =
                TimeUnit.NANOSECONDS.convert(arguments.getNumber("timeoutMS", new BsonInt32(10000)).longValue(), TimeUnit.MILLISECONDS);
        long startTime = System.nanoTime();
        while (primaryIsSame(priorClusterDescription, currentClusterDescription) || noPrimary(currentClusterDescription)) {
            if (System.nanoTime() - startTime > timeoutNanos) {
                fail(context.getAssertionContext().getMessage("Timed out waiting for primary change"));
            }
            try {
                //noinspection BusyWait
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            currentClusterDescription = client.getClusterDescription();
        }
        context.getAssertionContext().pop();
        return OperationResult.NONE;
    }

    private boolean noPrimary(final ClusterDescription clusterDescription) {
        return ReadPreference.primary().choose(clusterDescription).isEmpty();
    }

    private boolean primaryIsSame(final ClusterDescription priorClusterDescription, final ClusterDescription currentClusterDescription) {
        List<ServerDescription> priorPrimaries = ReadPreference.primary().choose(priorClusterDescription);
        List<ServerDescription> currentPrimaries = ReadPreference.primary().choose(currentClusterDescription);
        if (priorPrimaries.isEmpty() && currentPrimaries.isEmpty()) {
            return true;
        }
        if (priorPrimaries.size() == 1 && currentPrimaries.size() == 1) {
            return priorPrimaries.get(0).getAddress().equals(currentPrimaries.get(0).getAddress());
        } else {
            return false;
        }
    }

    private OperationResult executeWaitForThread(final UnifiedTestContext context, final BsonDocument operation) {
        BsonDocument arguments = operation.getDocument("arguments");
        String threadId = arguments.getString("thread").getValue();
        context.getAssertionContext().push(ContextElement.ofWaitForThread(threadId));
        List<Future<?>> tasks = entities.getThreadTasks(threadId);
        for (Future<?> task : tasks) {
            try {
                task.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException executionException) {
                try {
                    throw executionException.getCause();
                } catch (Throwable e) {
                    fail(context.getAssertionContext().getMessage(e.getMessage()));
                }
            } catch (TimeoutException e) {
                fail(context.getAssertionContext().getMessage(e.getMessage()));
            }
        }
        entities.clearThreadTasks(threadId);
        context.getAssertionContext().pop();
        return OperationResult.NONE;
    }

    private OperationResult executeRecordTopologyDescription(final BsonDocument operation) {
        BsonDocument arguments = operation.getDocument("arguments");
        ClusterDescription clusterDescription = entities.getClient(arguments.getString("client").getValue()).getClusterDescription();
        String topologyDescriptionId = arguments.getString("id").getValue();
        entities.addTopologyDescription(topologyDescriptionId, clusterDescription);
        return OperationResult.NONE;
    }

    private OperationResult executeAssertTopologyType(final UnifiedTestContext context, final BsonDocument operation) {
        BsonDocument arguments = operation.getDocument("arguments");
        ClusterDescription clusterDescription = entities.getTopologyDescription(arguments.getString("topologyDescription").getValue());
        String expectedTopologyType = arguments.getString("topologyType").getValue();

        context.getAssertionContext().push(ContextElement.ofTopologyType(expectedTopologyType));

        assertEquals(context.getAssertionContext().getMessage("Unexpected topology type"), getClusterType(expectedTopologyType),
                clusterDescription.getType());

        context.getAssertionContext().pop();
        return OperationResult.NONE;
    }

    private ClusterType getClusterType(final String topologyType) {
        if (topologyType.equalsIgnoreCase("Sharded")) {
            return ClusterType.SHARDED;
        } else if (topologyType.equalsIgnoreCase("LoadBalanced")) {
            return ClusterType.LOAD_BALANCED;
        } else if (topologyType.startsWith("ReplicaSet")) {
            return ClusterType.REPLICA_SET;
        } else if (topologyType.equalsIgnoreCase("Unknown")) {
            return ClusterType.UNKNOWN;
        } else {
            throw new IllegalArgumentException("Unsupported topology type: " + topologyType);
        }
    }

    private OperationResult executeRunOnThread(final UnifiedTestContext context, final BsonDocument operation) {
        UnifiedTestContext newContext = new UnifiedTestContext();
        BsonDocument arguments = operation.getDocument("arguments");
        String threadId = arguments.getString("thread").getValue();
        ExecutorService thread = entities.getThread(threadId);
        Future<?> future = thread.submit(() -> assertOperation(newContext, arguments.getDocument("operation"), 0));
        entities.addThreadTask(threadId, future);
        return OperationResult.NONE;
    }

    private OperationResult executeFailPoint(final BsonDocument operation) {
        FailPoint failPoint = FailPoint.untargeted(operation, entities);
        failPoint.executeFailPoint();
        failPoints.add(failPoint);
        return OperationResult.NONE;
    }

    private OperationResult executeTargetedFailPoint(final BsonDocument operation) {
        FailPoint failPoint = FailPoint.targeted(operation, entities);
        failPoint.executeFailPoint();
        failPoints.add(failPoint);
        return OperationResult.NONE;
    }

    private OperationResult executeEndSession(final BsonDocument operation) {
        ClientSession session = entities.getSession(operation.getString("object").getValue());
        session.close();
        return OperationResult.NONE;
    }

    private OperationResult executeAssertSessionDirty(final BsonDocument operation) {
        return executeAssertSessionDirtiness(operation, true);
    }

    private OperationResult executeAssertSessionNotDirty(final BsonDocument operation) {
        return executeAssertSessionDirtiness(operation, false);
    }

    private OperationResult executeAssertSessionDirtiness(final BsonDocument operation, final boolean expected) {
        ClientSession session = entities.getSession(operation.getDocument("arguments").getString("session").getValue());
        assertNotNull(session.getServerSession());
        assertEquals(expected, session.getServerSession().isMarkedDirty());
        return OperationResult.NONE;
    }

    private OperationResult executeAssertSessionPinned(final BsonDocument operation) {
        return executeAssertSessionPinniness(operation, true);
    }

    private OperationResult executeAssertSessionUnpinned(final BsonDocument operation) {
        return executeAssertSessionPinniness(operation, false);
    }

    private OperationResult executeAssertSessionPinniness(final BsonDocument operation, final boolean expected) {
        ClientSession session = entities.getSession(operation.getDocument("arguments").getString("session").getValue());
        assertNotNull(session.getServerSession());
        assertEquals(expected, session.getPinnedServerAddress() != null);
        return OperationResult.NONE;
    }

    private OperationResult executeAssertNumberConnectionsCheckedOut(final UnifiedTestContext context, final BsonDocument operation) {
        TestConnectionPoolListener listener = entities.getConnectionPoolListener(
                operation.getDocument("arguments").getString("client").getValue());
        assertEquals(context.getAssertionContext().getMessage("Number of checked out connections must match expected"),
                operation.getDocument("arguments").getNumber("connections").intValue(), listener.getNumConnectionsCheckedOut());
        return OperationResult.NONE;
    }

    private OperationResult executeAssertSameLsidOnLastTwoCommands(final BsonDocument operation) {
        return executeAssertLsidOnLastTwoCommands(operation, true);
    }

    private OperationResult executeAssertDifferentLsidOnLastTwoCommands(final BsonDocument operation) {
        return executeAssertLsidOnLastTwoCommands(operation, false);
    }

    private OperationResult executeAssertLsidOnLastTwoCommands(final BsonDocument operation, final boolean same) {
        TestCommandListener listener = entities.getClientCommandListener(
                operation.getDocument("arguments").getString("client").getValue());
        List<CommandEvent> events = lastTwoCommandEvents(listener);
        String eventsJson = listener.getCommandStartedEvents().stream()
                .map(e -> ((CommandStartedEvent) e).getCommand().toJson())
                .collect(Collectors.joining(", "));
        BsonDocument expected = ((CommandStartedEvent) events.get(0)).getCommand().getDocument("lsid");
        BsonDocument actual = ((CommandStartedEvent) events.get(1)).getCommand().getDocument("lsid");
        if (same) {
            assertEquals(eventsJson, expected, actual);
        } else {
            assertNotEquals(eventsJson, expected, actual);
        }
        return OperationResult.NONE;
    }

    private OperationResult executeAssertSessionTransactionState(final BsonDocument operation) {
        BsonDocument arguments = operation.getDocument("arguments");
        ClientSession session = entities.getSession(arguments.getString("session").getValue());
        String state = arguments.getString("state").getValue();
        //noinspection SwitchStatementWithTooFewBranches
        switch (state) {
            case "starting":
                assertTrue(session.hasActiveTransaction());
                break;
            default:
                throw new UnsupportedOperationException("Unsupported transaction state: " + state);
        }
        return OperationResult.NONE;
    }

    private OperationResult executeAssertCollectionExists(final BsonDocument operation) {
        assertTrue(collectionExists(operation));
        return OperationResult.NONE;
    }

    private OperationResult executeAssertCollectionNotExists(final BsonDocument operation) {
        assertFalse(collectionExists(operation));
        return OperationResult.NONE;
    }

    private boolean collectionExists(final BsonDocument operation) {
        BsonDocument arguments = operation.getDocument("arguments");
        String databaseName = arguments.getString("databaseName").getValue();
        String collectionName = arguments.getString("collectionName").getValue();
        return getMongoClient().getDatabase(databaseName)
                .listCollections().filter(Filters.eq("name", collectionName)).first() != null;
    }

    private OperationResult executeAssertIndexExists(final BsonDocument operation) {
        assertTrue(indexExists(operation));
        return OperationResult.NONE;
    }

    private OperationResult executeAssertIndexNotExists(final BsonDocument operation) {
        assertFalse(indexExists(operation));
        return OperationResult.NONE;
    }

    private boolean indexExists(final BsonDocument operation) {
        BsonDocument arguments = operation.getDocument("arguments");
        String databaseName = arguments.getString("databaseName").getValue();
        String collectionName = arguments.getString("collectionName").getValue();
        String indexName = arguments.getString("indexName").getValue();
        return getMongoClient().getDatabase(databaseName).getCollection(collectionName)
                .listIndexes(BsonDocument.class).into(new ArrayList<>()).stream()
                .anyMatch(document -> document.getString("name").getValue().equals(indexName));
    }

    private List<CommandEvent> lastTwoCommandEvents(final TestCommandListener listener) {
        List<CommandEvent> events = listener.getCommandStartedEvents();
        assertTrue(events.size() >= 2);
        return events.subList(events.size() - 2, events.size());
    }

    private void addInitialData() {
        for (BsonValue cur : initialData.getValues()) {
            BsonDocument curDataSet = cur.asDocument();
            CollectionHelper<BsonDocument> helper = new CollectionHelper<>(new BsonDocumentCodec(),
                    new MongoNamespace(curDataSet.getString("databaseName").getValue(),
                            curDataSet.getString("collectionName").getValue()));

            helper.create(WriteConcern.MAJORITY);

            BsonArray documentsArray = curDataSet.getArray("documents", new BsonArray());
            if (!documentsArray.isEmpty()) {
                helper.insertDocuments(documentsArray.stream().map(BsonValue::asDocument).collect(toList()),
                        WriteConcern.MAJORITY);
            }
        }
    }
}
