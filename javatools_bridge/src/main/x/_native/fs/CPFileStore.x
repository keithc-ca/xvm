import ecstasy.fs.AccessDenied;

/**
 * Constant Pool FileStore implementation.
 */
const CPFileStore(String path, Object constRoot)
        implements FileStore {

    @Override
    @Lazy Directory root.calc() {
        (Boolean isDir, String name, Time created, Time modified, Int size) = loadNode(constRoot);
        assert isDir;
        return new CPDirectory(constRoot, this, Path.ROOT, created, modified, size);
    }

    @Override
    conditional Directory|File find(Path path) {
        path = path.normalize();
        if (path.depth < 0) {
            // can't climb up past the root
            return False;
        }

        Directory dir = root;
        Segments: for (Path segment : path.toArray()) {
            switch (segment.form) {
            case Root:
                // this should only occur at the first segment
                assert Segments.first && dir == root;
                break;

            case Parent:
                // since this was not cleaned out by the call to normalize(), it implies that
                // the caller is trying to climb up past the root
                return False;

            case Current:
                // this should have been cleaned out by the call to normalize()
                assert;

            case Name:
                if (Directory|File node := dir.find(segment.name)) {
                    if (Segments.last) {
                        return True, node;
                    }

                    if (node.is(File)) {
                        // this isn't the last segment, and the node isn't a directory, so we
                        // can't keep searching down the tree
                        return False;
                    }

                    dir = node;
                } else {
                    return False;
                }
                break;
            }
        }
        assert;
    }

    @Override
    Directory dirFor(Path path) {
        if (Directory|File node := find(path)) {
            if (node.is(Directory)) {
                return node;
            }
        }

        return new CPDirectory(Null, this, path, Time.EPOCH, Time.EPOCH, 0);
    }

    @Override
    File fileFor(Path path) {
        if (Directory|File node := find(path)) {
            if (node.is(File)) {
                return node;
            }
        }

        return new CPFile(Null, this, path, Time.EPOCH, Time.EPOCH, 0);
    }

    @Override
    Directory|File copy(Path source, Path dest) = throw new AccessDenied();

    @Override
    Directory|File move(Path source, Path dest) = throw new AccessDenied();

    @Override
    @RO Boolean readOnly.get() = True;

    @Override
    FileStore ensureReadOnly() = this;

    @Override
    @RO Int capacity.get() = root.size;

    @Override
    @RO Int bytesUsed.get() = root.size;

    @Override
    @RO Int bytesFree.get() = 0;

    @Override
    Appender<Char> emitListing(Appender<Char> buf, Boolean recursive = True) {
        buf.addAll("FileStore:")
           .addAll(path)
           .add('\n');
        return root.emitListing(buf, recursive, "");
    }

    // ----- Stringable methods --------------------------------------------------------------------

    @Override
    Int estimateStringLength() = 10 + path.estimateStringLength();

    @Override
    Appender<Char> appendTo(Appender<Char> buf) {
        "FileStore:".appendTo(buf);
        return path.appendTo(buf);
    }


    // ----- native support ------------------------------------------------------------------------

    /**
     * The handle for the root directory constant.
     */
    protected Object constRoot;

    /**
     * Load meta-data for a node.
     */
    static (Boolean isDir, String name, Time created, Time modified, Int size)
        loadNode(Object constNode) = TODO("native");

    /**
     * Load contents for a directory.
     */
    static (String[] names, Object[] cookies) loadDirectory(Object constNode) = TODO("native");

    /**
     * Load contents for a file.
     */
    static immutable Byte[] loadFile(Object constNode) = TODO("native");
}