/**
 * An implementation of the Authenticator interface that rejects all authentication attempts.
 */
service NeverAuthenticator(WebApp app)
        implements Duplicable, Authenticator {

    // ----- constructors --------------------------------------------------------------------------

    @Override
    construct(NeverAuthenticator that) {
        this.app = that.app;
    }

    // ----- Authenticator API ---------------------------------------------------------------------

    @Override
    Realm realm = new FixedRealm("Neverland", []);

    @Override
    Attempt[] findAndRevokeSecrets(RequestIn request) = [];

    @Override
    Attempt[] authenticate(RequestIn request) {
        private Boolean logged = False;
        if (!logged) {
            // log a message the first time this Authenticator has to reject a user, so the
            // developer (or deployer) can see that an Authenticator needs to be provided
            // TODO define a Log API and make an injectable Log
            @Inject Console console;
            console.print($|Authentication was requested, but the WebApp "{app}" \
                           |did not provide an Authenticator
                         );
            logged = True;
        }

        static Attempt[] Never = [new Attempt(Null, NoData, Forbidden)];
        return Never;
    }
}
