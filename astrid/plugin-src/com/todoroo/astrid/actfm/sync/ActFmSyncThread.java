package com.todoroo.astrid.actfm.sync;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.Intent;
import android.util.Log;

import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.AndroidUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.actfm.sync.messages.BriefMe;
import com.todoroo.astrid.actfm.sync.messages.ChangesHappened;
import com.todoroo.astrid.actfm.sync.messages.ClientToServerMessage;
import com.todoroo.astrid.actfm.sync.messages.NameMaps;
import com.todoroo.astrid.actfm.sync.messages.ReplayOutstandingEntries;
import com.todoroo.astrid.actfm.sync.messages.ReplayTaskListMetadataOutstanding;
import com.todoroo.astrid.actfm.sync.messages.ServerToClientMessage;
import com.todoroo.astrid.actfm.sync.messages.TaskListMetadataChangesHappened;
import com.todoroo.astrid.api.AstridApiConstants;
import com.todoroo.astrid.core.PluginServices;
import com.todoroo.astrid.dao.OutstandingEntryDao;
import com.todoroo.astrid.dao.RemoteModelDao;
import com.todoroo.astrid.dao.TagDataDao;
import com.todoroo.astrid.dao.TagOutstandingDao;
import com.todoroo.astrid.dao.TaskAttachmentDao;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.dao.TaskListMetadataDao;
import com.todoroo.astrid.dao.TaskListMetadataOutstandingDao;
import com.todoroo.astrid.dao.TaskOutstandingDao;
import com.todoroo.astrid.dao.UserActivityDao;
import com.todoroo.astrid.dao.UserActivityOutstandingDao;
import com.todoroo.astrid.data.OutstandingEntry;
import com.todoroo.astrid.data.RemoteModel;
import com.todoroo.astrid.data.TagData;
import com.todoroo.astrid.data.TagOutstanding;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.data.TaskAttachment;
import com.todoroo.astrid.data.TaskListMetadata;
import com.todoroo.astrid.data.TaskListMetadataOutstanding;
import com.todoroo.astrid.data.TaskOutstanding;
import com.todoroo.astrid.data.User;
import com.todoroo.astrid.data.UserActivity;

public class ActFmSyncThread {

    private static final String ERROR_TAG = "actfm-sync-thread"; //$NON-NLS-1$

    private final List<ClientToServerMessage<?>> pendingMessages;
    private final Map<ClientToServerMessage<?>, Runnable> pendingCallbacks;
    private final Object monitor;
    private Thread thread;

    @Autowired
    private ActFmInvoker actFmInvoker;

    @Autowired
    private ActFmPreferenceService actFmPreferenceService;

    @Autowired
    private TaskDao taskDao;

    @Autowired
    private TaskOutstandingDao taskOutstandingDao;

    @Autowired
    private TagDataDao tagDataDao;

    @Autowired
    private TagOutstandingDao tagOutstandingDao;

    @Autowired
    private UserActivityDao userActivityDao;

    @Autowired
    private UserActivityOutstandingDao userActivityOutstandingDao;

    @Autowired
    private TaskListMetadataDao taskListMetadataDao;

    @Autowired
    private TaskListMetadataOutstandingDao taskListMetadataOutstandingDao;

    private String token;

    private boolean syncMigration = false;

    private boolean isTimeForBackgroundSync = false;

    public static enum ModelType {
        TYPE_TASK,
        TYPE_TAG,
        TYPE_ACTIVITY,
        TYPE_ATTACHMENT,
        TYPE_TASK_LIST_METADATA
    }

    private static volatile ActFmSyncThread instance;

    public static ActFmSyncThread getInstance() {
        if (instance == null) {
            synchronized(ActFmSyncThread.class) {
                if (instance == null) {
                    initializeSyncComponents(PluginServices.getTaskDao(), PluginServices.getTagDataDao(), PluginServices.getUserActivityDao(),
                            PluginServices.getTaskAttachmentDao(), PluginServices.getTaskListMetadataDao());
                }
            }
        }
        return instance;
    }

    public static ActFmSyncThread initializeSyncComponents(TaskDao taskDao, TagDataDao tagDataDao, UserActivityDao userActivityDao, TaskAttachmentDao taskAttachmentDao, TaskListMetadataDao taskListMetadataDao) {
        if (instance == null) {
            synchronized(ActFmSyncThread.class) {
                if (instance == null) {
                    List<ClientToServerMessage<?>> syncQueue = Collections.synchronizedList(new LinkedList<ClientToServerMessage<?>>());
                    ActFmSyncMonitor monitor = ActFmSyncMonitor.getInstance();
                    ActFmSyncWaitingPool waitingPool = ActFmSyncWaitingPool.getInstance();

                    instance = new ActFmSyncThread(syncQueue, monitor);

                    taskDao.addListener(new SyncDatabaseListener<Task>(instance, ModelType.TYPE_TASK));
                    tagDataDao.addListener(new SyncDatabaseListener<TagData>(instance, ModelType.TYPE_TAG));
                    userActivityDao.addListener(new SyncDatabaseListener<UserActivity>(instance, ModelType.TYPE_ACTIVITY));
                    taskAttachmentDao.addListener(new SyncDatabaseListener<TaskAttachment>(instance, ModelType.TYPE_ATTACHMENT));
                    taskListMetadataDao.addListener(new TaskListMetadataSyncDatabaseListener(instance, waitingPool, ModelType.TYPE_TASK_LIST_METADATA));

                    instance.startSyncThread();
                }
            }
        }
        return instance;
    }

    private ActFmSyncThread(List<ClientToServerMessage<?>> messageQueue, Object syncMonitor) {
        DependencyInjectionService.getInstance().inject(this);
        this.pendingMessages = messageQueue;
        this.pendingCallbacks = Collections.synchronizedMap(new HashMap<ClientToServerMessage<?>, Runnable>());
        this.monitor = syncMonitor;
        this.syncMigration = Preferences.getBoolean(AstridNewSyncMigrator.PREF_SYNC_MIGRATION, false);
    }

    public synchronized void startSyncThread() {
        if (thread == null || !thread.isAlive()) {
            repopulateQueueFromOutstandingTables();
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    sync();
                }
            });
            thread.start();
        }
    }

    public synchronized void enqueueMessage(ClientToServerMessage<?> message, Runnable callback) {
        if (!pendingMessages.contains(message)) {
            pendingMessages.add(message);
            if (callback != null)
                pendingCallbacks.put(message, callback);
            synchronized(monitor) {
                monitor.notifyAll();
            }
        }
    }

    public synchronized void setTimeForBackgroundSync(boolean isTimeForBackgroundSync) {
        this.isTimeForBackgroundSync = isTimeForBackgroundSync;
        if (isTimeForBackgroundSync)
            synchronized (monitor) {
                monitor.notifyAll();
            }
    }

    private final Runnable defaultRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            Intent refresh = new Intent(AstridApiConstants.BROADCAST_EVENT_REFRESH);
            ContextManager.getContext().sendBroadcast(refresh);
        }
    };

    @SuppressWarnings("nls")
    private void sync() {
        try {
            int batchSize = 4;
            List<ClientToServerMessage<?>> messageBatch = new LinkedList<ClientToServerMessage<?>>();
            while(true) {
                synchronized(monitor) {
                    while ((pendingMessages.isEmpty() && !timeForBackgroundSync()) || !actFmPreferenceService.isLoggedIn() || !syncMigration) {
                        try {
                            monitor.wait();
                            AndroidUtilities.sleepDeep(500L); // Wait briefly for large database operations to finish (e.g. adding a task with several tags may trigger a message before all saves are done--fix this?)

                            if (!syncMigration)
                                syncMigration = Preferences.getBoolean(AstridNewSyncMigrator.PREF_SYNC_MIGRATION, false);
                        } catch (InterruptedException e) {
                            // Ignored
                        }
                    }
                }

                if (timeForBackgroundSync()) {
                    repopulateQueueFromOutstandingTables();
                    enqueueMessage(BriefMe.instantiateBriefMeForClass(TaskListMetadata.class, NameMaps.PUSHED_AT_TASK_LIST_METADATA), defaultRefreshRunnable);
                    enqueueMessage(BriefMe.instantiateBriefMeForClass(Task.class, NameMaps.PUSHED_AT_TASKS), defaultRefreshRunnable);
                    enqueueMessage(BriefMe.instantiateBriefMeForClass(TagData.class, NameMaps.PUSHED_AT_TAGS), defaultRefreshRunnable);
                    enqueueMessage(BriefMe.instantiateBriefMeForClass(User.class, NameMaps.PUSHED_AT_USERS), defaultRefreshRunnable);
                    setTimeForBackgroundSync(false);
                }

                while (messageBatch.size() < batchSize && !pendingMessages.isEmpty()) {
                    ClientToServerMessage<?> message = pendingMessages.remove(0);
                    if (message != null)
                        messageBatch.add(message);
                }

                if (!messageBatch.isEmpty() && checkForToken()) {
                    JSONArray payload = new JSONArray();
                    for (ClientToServerMessage<?> message : messageBatch) {
                        JSONObject serialized = message.serializeToJSON();
                        if (serialized != null) {
                            payload.put(serialized);
                            syncLog("Sending: " + serialized);
                        }
                    }

                    if (payload.length() == 0)
                        continue;

                    try {
                        JSONObject response = actFmInvoker.postSync(payload, token);
                        // process responses
                        JSONArray serverMessagesJson = response.optJSONArray("messages");
                        if (serverMessagesJson != null) {
                            for (int i = 0; i < serverMessagesJson.length(); i++) {
                                JSONObject serverMessageJson = serverMessagesJson.optJSONObject(i);
                                if (serverMessageJson != null) {
                                    ServerToClientMessage serverMessage = ServerToClientMessage.instantiateMessage(serverMessageJson);
                                    if (serverMessage != null) {
                                        syncLog("Processing server message of type " + serverMessage.getClass().getSimpleName());
                                        serverMessage.processMessage();
                                    } else {
                                        syncLog("Unable to instantiate message " + serverMessageJson.toString());
                                    }
                                }
                            }
                            JSONArray errors = response.optJSONArray("errors");
                            boolean errorsExist = (errors != null && errors.length() > 0);
                            replayOutstandingChanges(errorsExist);
                        }

                        batchSize = Math.min(batchSize, messageBatch.size()) * 2;
                    } catch (IOException e) {
                        Log.e(ERROR_TAG, "IOException", e);
                        batchSize = Math.max(batchSize / 2, 1);
                    }

                    boolean didDefaultRefreshThisLoop = false;
                    for (ClientToServerMessage<?> message : messageBatch) {
                        try {
                            Runnable r = pendingCallbacks.remove(message);
                            if (r != null) {
                                if (r == defaultRefreshRunnable) {
                                    if (didDefaultRefreshThisLoop)
                                        continue;
                                    didDefaultRefreshThisLoop = true;
                                }
                                r.run();
                            }
                        } catch (Exception e) {
                            Log.e(ERROR_TAG, "Unexpected exception executing sync callback", e);
                        }
                    }

                    messageBatch = new LinkedList<ClientToServerMessage<?>>();
                }
            }
        } catch (Exception e) {
            // In the worst case, restart thread if something goes wrong
            Log.e(ERROR_TAG, "Unexpected sync thread exception", e);
            thread = null;
            startSyncThread();
        }

    }

    // Reapplies changes still in the outstanding tables to the local database
    // Called after a batch has finished processing
    private void replayOutstandingChanges(boolean afterErrors) {
        syncLog("Replaying outstanding changes"); //$NON-NLS-1$
        new ReplayOutstandingEntries<Task, TaskOutstanding>(Task.class, NameMaps.TABLE_ID_TASKS, taskDao, taskOutstandingDao, afterErrors).execute();
        new ReplayOutstandingEntries<TagData, TagOutstanding>(TagData.class, NameMaps.TABLE_ID_TAGS, tagDataDao, tagOutstandingDao, afterErrors).execute();
        new ReplayTaskListMetadataOutstanding(taskListMetadataDao, taskListMetadataOutstandingDao, afterErrors).execute();
    }

    private boolean timeForBackgroundSync() {
        return isTimeForBackgroundSync;
    }

    public void repopulateQueueFromOutstandingTables() {
        syncLog("Constructing queue from outstanding tables"); //$NON-NLS-1$
        constructChangesHappenedFromOutstandingTable(Task.class, taskDao, taskOutstandingDao);
        constructChangesHappenedFromOutstandingTable(TagData.class, tagDataDao, tagOutstandingDao);
        constructChangesHappenedFromOutstandingTable(UserActivity.class, userActivityDao, userActivityOutstandingDao);
        constructChangesHappenedForTaskListMetadata(taskListMetadataDao, taskListMetadataOutstandingDao);
    }

    private <T extends RemoteModel, OE extends OutstandingEntry<T>> void constructChangesHappenedFromOutstandingTable(Class<T> modelClass, RemoteModelDao<T> modelDao, OutstandingEntryDao<OE> oustandingDao) {
        TodorooCursor<OE> outstanding = oustandingDao.query(Query.select(OutstandingEntry.ENTITY_ID_PROPERTY).groupBy(OutstandingEntry.ENTITY_ID_PROPERTY));
        try {
            for (outstanding.moveToFirst(); !outstanding.isAfterLast(); outstanding.moveToNext()) {
                Long id = outstanding.get(OutstandingEntry.ENTITY_ID_PROPERTY);
                enqueueMessage(new ChangesHappened<T, OE>(id, modelClass, modelDao, oustandingDao), null);
            }
        } finally {
            outstanding.close();
        }
    }

    private void constructChangesHappenedForTaskListMetadata(TaskListMetadataDao dao, TaskListMetadataOutstandingDao outstandingDao) {
        TodorooCursor<TaskListMetadataOutstanding> outstanding = outstandingDao.query(Query.select(OutstandingEntry.ENTITY_ID_PROPERTY).groupBy(OutstandingEntry.ENTITY_ID_PROPERTY));
        try {
            for (outstanding.moveToFirst(); !outstanding.isAfterLast(); outstanding.moveToNext()) {
                Long id = outstanding.get(OutstandingEntry.ENTITY_ID_PROPERTY);
                ActFmSyncWaitingPool.getInstance().enqueueMessage(new TaskListMetadataChangesHappened(id, TaskListMetadata.class, dao, outstandingDao));
            }
        } finally {
            outstanding.close();
        }
    }

    private boolean checkForToken() {
        if(!actFmPreferenceService.isLoggedIn())
            return false;
        token = actFmPreferenceService.getToken();
        return true;
    }

    private void syncLog(String message) {
        if (ActFmInvoker.SYNC_DEBUG)
            Log.e(ERROR_TAG, message);
    }
}
