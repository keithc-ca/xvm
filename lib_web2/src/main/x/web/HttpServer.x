// TODO remove or refactor
interface HttpServer
    {
    /**
     * Attach a handler.
     */
    void attachHandler(Handler handler);

    /**
     * Send a response.
     */
    void send(Object context, Int status, String[] headerNames, String[][] headerValues, Byte[] body);

    /**
     * Request handler.
     */
    static interface Handler
        {
        void handle(Object context, String uri, String method,
                    String[] headerNames, String[][] headerValues, Byte[] body);
        }
    }