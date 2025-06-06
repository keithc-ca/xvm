/**
 * PrefetchBinaryInput is a [BinaryInput] implementation that asynchronously pre-fetches data from
 * the underlying data source while serving data from a pre-read buffer. This allows for improved
 * performance by overlapping I/O operations with data processing.
 *
 * This `BinaryInput` will start fetching data from the source as soon as it is created, and will
 * continue to do so in the background until it is closed or the end of the source is reached.
 */
 class PrefetchBinaryInput
        implements BinaryInput {

    /**
     * Construct the `PrefetchBinaryInput` based on the function that fetches data from the
     * underlying source.
     */
    construct(function Byte[]() fetch) {
        // prime the buffer
        @Future Byte[] buffer = fetch^();

        this.fetch        = fetch;
        this.bufferRef    = &buffer;
        this.bufferOffset = 0;
    }

    /**
     * The function that fetches the next buffer from the underlying source.
     */
    protected function Byte[]() fetch;

    /**
     * Asynchronously filled buffer.
     */
    protected FutureVar<Byte[]> bufferRef;
    protected Byte[] buffer.get() = bufferRef.get();

    /**
     * The current offset into the buffer.
     */
    protected Int bufferOffset;

    /**
     * Fire an async request to fetch a next buffer.
     */
    protected void requestNextBuffer() {
        @Future Byte[] buffer = fetch^();
        bufferRef = &buffer;
    }

    // ----- BinaryInput interface -----------------------------------------------------------------

    @Override
    @RO Boolean eof.get() = buffer.empty;

    @Override
    @RO Int available.get() = bufferRef.assigned ? buffer.size - bufferOffset : 0;

    @Override
    Byte readByte() {
        Int size = buffer.size;
        if (size == 0) {
            throw new EndOfFile();
        }

        Byte byte = buffer[bufferOffset++];
        if (bufferOffset == size) {
            // request a new buffer
            requestNextBuffer();
            bufferOffset = 0;
        }
        return byte;
    }

    @Override
    immutable Byte[] readBytes(Int count) {
        if (bufferOffset == 0 && count == buffer.size) {
            // request a new buffer; freeze and return our current buffer
            Byte[] bufferCurr = buffer;
            requestNextBuffer();
            return bufferCurr.freeze(inPlace=True);
        } else {
            return super(count);
        }
    }

    @Override
    Int pipeTo(BinaryOutput out, Int count = MaxValue) {
        if (count == 0) {
            return 0;
        }
        assert count > 0;

        Int piped = 0;
        if (bufferOffset > 0) {
            // finish piping from the current buffer in the "byte-by-byte" mode
            piped = super(out, (buffer.size - bufferOffset).notGreaterThan(count));
            count -= piped;
            if (piped == 0 || count == 0) {
                // end-of-file or requested amount has been piped
                return piped;
            }
            assert bufferOffset == 0;
        }

        Byte[] bufferCurr = buffer;
        Int    bufferSize = bufferCurr.size;
        while (count >= bufferSize > 0) {
            // this is the bulk "entire buffer at a time" mode
            requestNextBuffer();
            out.writeBytes(bufferCurr);
            piped += bufferSize;
            count -= bufferSize;

            bufferCurr = buffer;
            bufferSize = bufferCurr.size;
        }

        if (count > 0 && bufferSize > 0) {
            // we only need to take part of the current buffer; use the "byte-by-byte" mode
            piped += super(out, count);
        }
        return piped;
    }

    @Override
    void close(Exception? cause = Null) {
        fetch = () -> [];
        requestNextBuffer();
    }
}