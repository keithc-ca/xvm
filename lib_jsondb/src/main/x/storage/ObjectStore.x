import Catalog.Status;
import model.DBObjectInfo;
import oodb.DBObject.DBCategory as Category;
import json.Doc;


/**
 * This is an abstract base class for storage services that manage information as JSON formatted
 * data on disk. The abstraction is also designed to allow support for caching optimizations to be
 * layered on top of a persistent storage implementation -- probably more to avoid expensive
 * serialization and deserialization, than to avoid raw I/O operations (which are expected to be
 * low-cost in NVMe-flash and SAN environments).
 *
 * ObjectStore represents the mapping between binary storage on disk using files and directories,
 * and the fundamental set of operations that a particular DBObject interface (such as [DBMap] or
 * [DBList]) requires and uses to provide its high-level functionality.
 *
 * Each DBObject in the database has a corresponding ObjectStore that manages its persistent data on
 * disk.
 *
 * When an ObjectStore is constructed, it is in an inert mode; it does not interact at all with the
 * disk storage until it is explicitly instructed to do so. Normally, the ObjectStore assumes that
 * the storage is intact, and it takes a fast-path to initialize its information about the disk
 * contents; this is called [quickScan]. If recovery of the storage is indicated or instructed, then
 * the ObjectStore uses a [deepScan], which is assumed to be far more intensive both in terms of IO
 * activity and computational time, and may even be "destructive" in the sense that it must return
 * the persistent image to a working state, and that may force it to discard information that cannot
 * be recovered.
 *
 * TODO background maintenance
 */
service ObjectStore(Catalog catalog, DBObjectInfo info, Appender<String> errs)
        implements Hashable
        implements Closeable
    {
    // ----- properties ----------------------------------------------------------------------------

    /**
     * The Catalog that this ObjectStore belongs to.
     */
    public/protected Catalog catalog;

    /**
     * The Catalog that this ObjectStore belongs to.
     */
    protected @Lazy TxManager txManager.calc()
        {
        return catalog.txManager;
        }

    /**
     * The DBObjectInfo that identifies the configuration of this ObjectStore.
     */
    public/protected DBObjectInfo info;

    /**
     * The id of the database object for which this storage exists.
     */
    Int id.get()
        {
        return info.id;
        }

    /**
     * The `DBCategory` of the `DBObject`.
     */
    Category category.get()
        {
        return info.category;
        }

    /**
     * An error log that was provided to this storage when it was created, for the purpose of
     * logging detailed error information encountered in the course of operation.
     */
    public/protected Appender<String> errs;

    /**
     * The current status of this ObjectStore.
     */
    public/protected Status status = Closed;

    /**
     * True iff the ObjectStore is permitted to write to persistent storage.
     */
    public/protected Boolean writeable = False;

    /**
     * The path that specifies this `DBObject`, and that indicates the storage location for the
     * data contained within it.
     */
    Path path.get()
        {
        return info.path;
        }

    /**
     * The directory within which the named ObjectStore file or directory exists. That file or
     * directory is used to load data from and store data into by this ObjectStore. Generally, the
     * ObjectStore does not use the `containingDir` directly.
     */
    @Lazy public/private Directory containingDir.calc()
        {
        // TODO this should be a lot easier ... e.g.: return catalog.dir.apply(path);
        Directory dir = catalog.dir;
        for (Path part : path[1..path.size-1))
            {
            dir = dir.dirFor(part.name);
            }
        return dir;
        }

    /**
     * The directory owned by this ObjectStore for purpose of its data storage. The ObjectStore may
     * create and remove this directory, and may create, modify, and remove any files within this
     * directory, except for any `FileNode`s in the directory whose names match any of the names
     * of child `DBObject`s of the `DBObject` that this ObjectStore corresponds to. (In other words,
     * if the corresponding `DBObject` has children named `A`, `B`, and `C`, then any file or
     * directory with any name can be created, modified, and removed by this ObjectStore except for
     * the file nodes named `A`, `B`, or `C`.)
     */
    @Lazy public/private Directory dataDir.calc()
        {
        return containingDir.dirFor(info.name);
        }

    /**
     * Statistics: The estimated number of bytes on disk in use by this storage object.
     */
    public/protected Int bytesUsed = 0;

    /**
     * Statistics: The estimated number of file on disk in use by this storage object.
     */
    public/protected Int filesUsed = 0;

    /**
     * * Empty - Either no storage has been allocated, or the contents are absent, so the storage
     *   is currently optimized for an absence of data.
     * * Small - The amount of data can be easily loaded into, manipulated in, and even held (if
     *   necessary and/or desirable) in memory.
     * * Medium - The amount of data is such that it can be managed in memory when necessary.
     * * Large - The amount of data is large enough that it should paged into memory only in
     *           portions at any given time.
     */
    enum StorageModel {Empty, Small, Medium, Large}

    /**
     * A special value, used only inside the ObjectStore that indicates missing data.
     */
    protected enum Indicator {Missing}

    /**
     * Statistics: The current storage model for this ObjectStore.
     */
    public/protected StorageModel model = Empty;

    /**
     * Statistics: The last time that the storage was accessed. Null indicates no record of access.
     *
     * This property will only have a dependable value when the storage is not closed and/or has
     * completed either a quick scan or a deep scan.
     */
    public/protected DateTime? lastAccessed = Null;

    /**
     * Statistics: The last time that the storage was modified. Null indicates no record of
     * modification.
     *
     * This property will only have a dependable value when the storage is not closed and/or has
     * completed either a quick scan or a deep scan.
     */
    public/protected DateTime? lastModified = Null;

    /**
     * The most recent transaction identifier for which this ObjectStore processed data changes.
     */
    public/protected Int? newestTx = Null;

    /**
     * Count of the number of operations since the ObjectStore was created.
     */
    public/protected Int opCount = 0;

    /**
     * Determine if this ObjectStore for a DBObject is allowed to write to disk. True iff the
     * catalog is not read only and the DBObject has not been deprecated or removed.
     */
    protected Boolean defaultWriteable.get()
        {
        return !catalog.readOnly && info.id > 0 && info.lifeCycle == Current;
        }

    /**
     * An internal, mutable record of Changes for a specific transaction.
     */
    protected class Changes(Int writeId, Int readId)
        {
        /**
         * This txId, the "write" txId.
         */
        Int writeId;

        /**
         * The read txId that this transaction is based from. (Note that the "read" id may itself be
         * a "write" id.)
         */
        Int readId;

        /**
         * The worker to dump CPU intensive serialization and deserialization work onto.
         */
        @Lazy Client.Worker worker.calc()
            {
            return txManager.workerFor(writeId);
            }

        /**
         * Set to True when the transaction contains possible changes related to this ObjectStore.
         */
        Boolean modified = False;
        }

    /**
     * In flight transactions involving this ObjectStore, keyed by "write" transaction id.
     */
    @Unassigned protected SkiplistMap<Int, Changes> inFlight;


    // ----- life cycle ----------------------------------------------------------------------------

    /**
     * For a closed ObjectStore, examine the contents of the persistent storage and recover from
     * that to a running state if at all possible.
     *
     * @throws IllegalState  if the ObjectStore is not `Closed`
     */
    Boolean recover()
        {
        assert status == Closed;
        status = Recovering;
        if (deepScan(True))
            {
            status    = Running;
            writeable = defaultWriteable;
            return True;
            }

        status    = Closed;
        writeable = False;
        return False;
        }

    /**
     * For a closed ObjectStore, quickly open the contents of the persistent storage in order to
     * achieve a running state.
     *
     * @throws IllegalState  if the ObjectStore is not `Closed`
     */
    Boolean open()
        {
        assert status == Closed;
        if (quickScan())
            {
            status    = Running;
            writeable = defaultWriteable;
            return True;
            }

        status    = Closed;
        writeable = False;
        return False;
        }

    /**
     * Close this `ObjectStore`.
     */
    @Override
    void close(Exception? cause = Null)
        {
        switch (status)
            {
            case Recovering:
                // there is nothing that the generic ObjectStore can do
                break;

            case Running:
                // there is nothing for the generic ObjectStore to do
                break;

            case Closed:
                break;

            case Configuring:
                assert as $"Illegal attempt to close {info.name.quoted()} storage while Configuring";

            default:
                assert as $"Illegal status: {status}";
            }

        status    = Closed;
        writeable = False;
        }

    /**
     * Delete the contents of the ObjectStore's persistent data.
     */
    void delete()
        {
        switch (status)
            {
            case Recovering:
            case Configuring:
            case Closed:
                model        = Empty;
                filesUsed    = 0;
                bytesUsed    = 0;
                lastAccessed = Null;
                lastModified = Null;

                Directory dir = dataDir;
                if (dir.exists)
                    {
                    dir.deleteRecursively();
                    }
                break;

            case Running:
                // there is nothing for the generic ObjectStore to do
                assert as $"Illegal attempt to delete {info.name.quoted()} storage while running";

            default:
                assert as $"Illegal status: {status}";
            }

        }


    // ----- transaction handling ------------------------------------------------------------------

    /**
     * Determine if the specified txId indicates a read ID, versus a write ID.
     *
     * @param txId  a transaction identity
     *
     * @return True iff the txId is in the range reserved for read transaction IDs
     */
    Boolean isReadTx(Int txId)
        {
        return txId > 0;
        }

    /**
     * Determine if the specified txId indicates a write ID, versus a read ID.
     *
     * @param txId  a transaction identity
     *
     * @return True iff the txId is in the range reserved for write transaction IDs
     */
    Boolean isWriteTx(Int txId)
        {
        return txId < 0;
        }

    /**
     * Possible outcomes from a [prepare] call:
     *
     * * FailedRolledBack indicates that the prepare failed, and this ObjectStore has rolled back
     *   all of its data associated with the specified transaction
     * * CommittedNoChanges indicates that the prepare succeeded, but that there were no changes,
     *   so an implicit commit has already occurred for this transaction on this ObjectStore
     * * Prepared indicates that there were changes, and they were successfully prepared for a
     *   subsequent commit request
     */
    enum PrepareResult {FailedRolledBack, CommittedNoChanges, Prepared}

    /**
     * Prepare the specified transaction.
     *
     * @param writeId  the "write" transaction id that was used to collect the transactional changes
     *
     * @return a [PrepareResult]
     */
    PrepareResult prepare(Int writeId)
        {
        TODO
        }

    /**
     * Move changes from one transaction id into another, merging the changes into the destination
     * transaction and leaving the source transaction empty.
     *
     * @param fromTxId  the source (uncommitted) write transaction id to move the changes from
     * @param toTxId    the destination (uncommitted) write transaction id to merge the changes into
     * @param release   (optional) pass True to release (discard) the source transaction
     *
     * @return containsMutation  true iff there were any changes that were moved by this ObjectStore
     *                           into the destination transaction
     */
    Boolean mergeTx(Int fromTxId, Int toTxId, Boolean release = False)
        {
        TODO
        }

    /**
     * Commit a group of previously prepared transactions. When this method returns, the
     * transactional changes related to this ObjectStore will have been successfully committed to
     * disk.
     *
     * @param byCommitId  a Map, keyed by "commit" transaction id, with the corresponding values
     *                    being the "prepared" transactions ids
     *
     * @return the ObjectStore's commit records, as JSON documents, keyed by commit id
     *
     * @throws Exception on any failure
     */
    OrderedMap<Int, Doc> commit(OrderedMap<Int, Int> byCommitId)
        {
        SkiplistMap<Int, Doc> records = new SkiplistMap();

        // in theory, these can all be committed together, which should be more efficient, but the
        // default implementation simply delegates to the single-commit method
        for ((Int commitId, Int prepareId) : byCommitId)
            {
            if (inFlight.contains(prepareId))
                {
                Doc record = commit(prepareId, commitId);
                records.put(commitId, record);
                }
            }

        return records;
        }

    /**
     * Commit a previously prepared transaction. When this method returns, the transactional changes
     * related to this ObjectStore will have been successfully committed to disk.
     *
     * @param prepareId  the previously prepared transaction, which is a "write" transaction id
     * @param commitId   the new identity of the transaction when it has been committed, which is a
     *                   "read" transaction id
     *
     * @return the ObjectStore's commit record, as a JSON document
     *
     * @throws Exception on any failure
     */
    Doc commit(Int prepareId, Int commitId)
        {
        TODO
        }

    /**
     * Roll back any transactional data associated with the specified transaction id.
     *
     * @param uncommittedId  a "write" transaction id
     *
     * @throws Exception on hard failure
     */
    void rollback(Int uncommittedId)
        {
        TODO
        }

    /**
     * Inform the ObjectStore of all of the read-transaction-ids that are still being relied upon
     * by in-flight transactions; any other historical transaction information can be discarded by
     * the ObjectStore, both in-memory and in the persistent storage. For asynchronous purposes, any
     * transaction newer than the most recent transaction in the passed set must be retained.
     *
     * @param inUseTxIds  an ordered set of transaction whose information needs to be retained
     * @param force       (optional) specify True to force the ObjectStore to immediately clean out
     *                    all older transactions in order to synchronously compress the storage
     */
    void retainTx(Set<Int> inUseTxIds, Boolean force = False)
        {
        TODO
        }


    // ----- IO handling ---------------------------------------------------------------------------

    /**
     * Verify that the storage is open and can read.
     *
     * @return True if the check passes
     *
     * @throws Exception if the check fails
     */
    Boolean checkRead()
        {
        return status == Recovering || status == Running
            || throw new IllegalState($"Read is not permitted for {info.name.quoted()} storage when status is {status}");
        }

    /**
     * Verify that the storage is open and can write.
     *
     * @return True if the check passes
     *
     * @throws Exception if the check fails
     */
    Boolean checkWrite()
        {
        return (writeable
                || throw new IllegalState($"Write is not enabled for the {info.name.quoted()} storage"))
            && (status == Recovering || status == Running
                || throw new IllegalState($"Write is not permitted for {info.name.quoted()} storage when status is {status}"));
        }

    /**
     * Determine the files owned by this storage.
     *
     * @return an iterator over all of the files that are presumed to be owned by this storage
     */
    Iterator<File> findFiles()
        {
        return dataDir.files().filter(f -> f.name.endsWith(".json")).toArray().iterator();
        }

    /**
     * Initialize the state of the ObjectStore by scanning the persistent image of the ObjectStore's
     * data.
     *
     * @return True if no issues were detected (which does not guarantee that no issues exist);
     *         False indicates that fixes are required
     */
    Boolean quickScan()
        {
        model        = Empty;
        filesUsed    = 0;
        bytesUsed    = 0;
        lastAccessed = Null;
        lastModified = Null;

        Directory dir = dataDir;
        if (dir.exists)
            {
            lastAccessed = dir.accessed;
            lastModified = dir.modified;

            for (File file : findFiles())
                {
                ++filesUsed;
                bytesUsed   += file.size;
                lastAccessed = lastAccessed?.maxOf(file.accessed) : file.accessed;
                lastModified = lastModified?.maxOf(file.modified) : file.modified;
                }

            // knowledge of model categorization is owned by the ObjectStore sub-classes; this is
            // just an initial guess at this level; sub-classes should override this if there is a
            // more correct calculation
            model = switch (filesUsed)
                {
                case 0: Empty;
                case 1: Small;
                default: Medium;
                };
            }

        return True;
        }

    /**
     * Initialize the state of the ObjectStore by deep-scanning (and optionally fixing as necessary)
     * the persistent image of the ObjectStore's data.
     *
     * @param fix  (optional) specify True to fix the persistent data if necessary, and False to
     *             deep scan without modifying the persistent data even if an error is detected
     *
     * @return True if the scan is clean (which, if `fix` is True, indicates that the scan corrected
     *         any errors that it encountered)
     */
    Boolean deepScan(Boolean fix = True)
        {
        // knowledge of how to perform a deep scan is handled by specific ObjectStore sub-classes
        return quickScan();
        }

    /**
     * Log an error description.
     *
     * @param err  an error message to add to the log
     */
    void log(String err)
        {
        try
            {
            errs.add(err);
            }
        catch (Exception e) {}
        }


    // ----- IO handling ---------------------------------------------------------------------------

    @Override static <CompileType extends ObjectStore> Int hashCode(CompileType value)
        {
        // use the hash code of the path; we are not expecting ObjectStore instances from multiple
        // different databases to end up in the same hashed data structure, so this should be more
        // than sufficient
        return value.info.path.hashCode();
        }

    @Override static <CompileType extends ObjectStore> Boolean equals(CompileType value1, CompileType value2)
        {
        // equality of ObjectStore references is very strict
        return &value1 == &value2;
        }
    }
