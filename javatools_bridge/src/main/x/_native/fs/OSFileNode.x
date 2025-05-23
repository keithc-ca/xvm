import ecstasy.fs.DirectoryFileStore.FileNodeWrapper;
import ecstasy.fs.FileNode;
import ecstasy.fs.FileWatcher;

import ecstasy.io.IOException;

/**
 * Native OS FileNode implementation.
 */
const OSFileNode
        implements FileNode
        delegates  Stringable(pathString) {

    @Override
    OSFileStore store;

    @Override
    @Lazy Path path.calc() = new Path(pathString);

    @Override
    Boolean exists.get() = TODO("Native");

    @Override
    Int size.get() = TODO("Native");

    @Override
    conditional File linkAsFile() = store.linkAsFile(this:protected);

    // TODO: should it be the "local" timezone?
    @Override
    @Lazy Time created.calc() = new Time(createdMillis*TimeOfDay.PicosPerMilli);

    @Override
    Time modified.get() = new Time(modifiedMillis*TimeOfDay.PicosPerMilli);

    @Override
    @RO Time accessed.get() = new Time(accessedMillis*TimeOfDay.PicosPerMilli);

    @Override
    Boolean readable.get() = TODO("Native");

    @Override
    Boolean writable.get() = TODO("Native");

    @Override
    Boolean create() = !exists && store.create(this:protected);

    @Override
    FileNode ensure() {
        if (!exists) {
            create();
        }
        return this;
    }

    @Override
    Boolean delete() = exists && store.delete(this:protected);

    @Override
    conditional FileNode renameTo(String name) {
        Path src = path;
        Path dst = new Path(src.parent?, name) : new Path(name);
        try {
            return True, store.copyOrMove(src, src.toString(), dst, dst.toString(), move=True);
        } catch (IOException e) {
            return False;
        }
    }

    /**
     * @return `True` if the specified `FileNode` represents a native `OSFileNode`
     * @return (conditionally) the corresponding `OSFileNode`
     */
    static conditional OSFileNode unwrap(FileNode node) {
        if (OSFileNode osNode := &node.revealAs(OSFileNode)) {
            return True, osNode;
        }
        if (FileNodeWrapper wrapper := &node.revealAs(FileNodeWrapper)) {
            return unwrap(wrapper.origNode);
        }
        return False;
    }

    // ----- equality support ----------------------------------------------------------------------

    @Override
    static <CompileType extends OSFileNode> Int64 hashCode(CompileType value) {
        return String.hashCode(value.pathString);
    }

    @Override
    static <CompileType extends OSFileNode> Boolean equals(CompileType node1, CompileType node2) {
        return node1.pathString == node2.pathString &&
               node1.is(OSFile) == node2.is(OSFile);
    }

    // ----- native --------------------------------------------------------------------------------

    String pathString.get() = TODO("Native");

    private Int createdMillis.get()  = TODO("Native");
    private Int accessedMillis.get() = TODO("Native");
    private Int modifiedMillis.get() = TODO("Native");
}