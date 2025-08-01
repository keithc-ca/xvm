import oodb.Connection;
import oodb.DBMap;
import oodb.DBObject;
import oodb.DBObjectInfo;
import oodb.DBSchema;
import oodb.RootSchema;

import sec.Credential;
import sec.Entitlement;
import sec.Group;
import sec.PlainTextCredential;
import sec.Principal;
import sec.Realm;
import sec.Subject;

import web.security.DigestCredential;

/**
 * A DBRealm is a realm implementation on top of an [AuthSchema].
 */
const DBRealm
        implements Realm {
    /**
     * Construct a `DBRealm`.
     *
     * @param name            the human readable name of the realm
     * @param rootSchema      (optional) the database to look for the AuthSchema at
     * @param initConfig      (optional) the initial configuration
     * @param connectionName  (optional) the name of the injected database (in case there are more
     *                        than one) to look for the AuthSchema
     */
    construct(String         name,
              RootSchema?    rootSchema     = Null,
              Configuration? initConfig     = Null,
              String?        connectionName = Null,
             ) {
        // if no schema is passed in, then request it by injection
        if (rootSchema == Null) {
            @Inject(resourceName=connectionName) Connection dbc;
            rootSchema = dbc;
        }

        assert AuthSchema db := findAuthSchema(rootSchema)
                as "The database does not contain an \"AuthSchema\"";

        Configuration cfg = db.config.get();
        if (!cfg.configured) {
            // the database has not yet been configured, so we need an initial configuration to be
            // provided or injected, and we'll configure the database in the finally block
            // (because we need the realm to exist in order to configure the database)
            if (initConfig == Null) {
                @Inject Configuration startingCfg;
                cfg = startingCfg;
            } else {
                cfg = initConfig;
            }
            this.createCfg = cfg;
        }

        this.name = name;
        this.db   = db;
    } finally {
        void applyConfig(Configuration cfg, String realmName) {
            // create the user records
            function Credential(String, String) createCredential;

            if (cfg.credScheme == DigestCredential.Scheme) {
                createCredential = (userName, pwd) -> new DigestCredential(realmName, userName, pwd);
            } else if (cfg.credScheme == PlainTextCredential.Scheme) {
                createCredential = (userName, pwd) -> new PlainTextCredential(userName, pwd);
            } else {
                throw new IllegalState("Unsupported credential scheme: {cfg.credScheme.quoted}");
            }

            Map<String, String> initUserNoPass = new ListMap();
            for ((String userName, String password) : cfg.initUserPass) {
                Credential credential = createCredential(userName, password);
                Principal  principal  = createPrincipal(
                        new Principal(0, userName, permissions=[AllowAll], credentials=[credential]));
                initUserNoPass.put(userName, "");
            }

            // store the configuration (but remove the passwords), and specify that the database
            // has now been configured (so we don't repeat the db configuration the next time)
            db.config.set(cfg.with(initUserPass = initUserNoPass,
                                   configured   = True));
        }

        if (Configuration cfg ?= this.createCfg) {
            using (db.connection.createTransaction()) {
                applyConfig(cfg, name);
            }
        }
    }

    @Override
    public/private String name;

    /**
     * The configuration to write to the database the first time the database and realm are created.
     */
    protected Configuration? createCfg;

    /**
     * The part of the database where the authentication information is stored.
     */
    AuthSchema db;

    // ----- operations: Principals ----------------------------------------------------------------

    @Override
    immutable Principal[] findPrincipals(function Boolean(Principal) match) {
        return db.principals.filter(e -> match(e.value)).values.toArray(Constant).freeze(True);
    }

    @Override
    conditional Principal findPrincipal(String scheme, String locator) {
        if (Int id := db.principalLocators.get(munge(scheme, locator))) {
            return readPrincipal(id);
        }
        return False;
    }

    @Override
    Principal createPrincipal(Principal principal) {
        Int principalId = db.principalGen.next();
        principal = principal.with(principalId=principalId);

        using (db.connection.createTransaction()) {
            // verify groups
            DBMap<Int, Group> groups = db.groups;
            for (Int groupId : principal.groupIds) {
                if (!groups.contains(groupId)) {
                    throw new MissingGroup(groupId);
                }
            }

            // add new locators
            DBMap<String, Int> index    = db.principalLocators;
            HashSet<String>    locators = locatorsFor(principal);
            for (String locator : locators) {
                if (!index.putIfAbsent(locator, principalId)) {
                    throw new DuplicateCredential(schemeFrom(locator), locatorFrom(locator));
                }
            }

            // store the principal
            if (!db.principals.putIfAbsent(principalId, principal)) {
                throw new RealmException($"Principal id={principalId} already existed");
            }
            return principal;
        }
    }

    @Override
    conditional Principal readPrincipal(Int id) = db.principals.get(id);

    @Override
    Principal updatePrincipal(Principal principal) {
        Int principalId = principal.principalId;
        using (db.connection.createTransaction()) {
            Principal old;
            if (!(old := readPrincipal(principalId))) {
                throw new MissingPrincipal(principalId);
            }

            // verify groups
            DBMap<Int, Group> groups = db.groups;
            for (Int groupId : principal.groupIds) {
                if (!groups.contains(groupId)) {
                    throw new MissingGroup(groupId);
                }
            }

            // add new locators
            DBMap<String, Int> index       = db.principalLocators;
            HashSet<String>    oldLocators = locatorsFor(old);
            HashSet<String>    newLocators = locatorsFor(principal);
            for (String locator : newLocators) {
                if (!oldLocators.contains(locator)) {
                    if (!index.putIfAbsent(locator, principalId) && index[locator] != principalId) {
                        throw new DuplicateCredential(schemeFrom(locator), locatorFrom(locator));
                    }
                }
            }

            // remove unused locators
            for (String locator : oldLocators) {
                if (!newLocators.contains(locator)) {
                    index.remove(locator, principalId);
                }
            }

            // store the principal
            db.principals.put(principalId, principal);
        }
        return principal;
    }

    @Override
    Boolean deletePrincipal(Int|Principal principal) {
        Int     principalId = principal.is(Int) ?: principal.principalId;
        using (db.connection.createTransaction()) {
            if (!(principal := readPrincipal(principalId))) {
                return False;
            }

            // delete all locators for the principal
            db.principalLocators.removeAll(e -> e.value == principalId);

            // delete all entitlements for the principal
            db.entitlementLocators.removeAll(e -> e.value == principalId);

            // delete the principal
            db.principals.remove(principalId);
        }
        return True;
    }

    // ----- operations: Groups ----------------------------------------------------------------

    @Override
    immutable Group[] findGroups(function Boolean(Group) match) {
        return db.groups.filter(e -> match(e.value)).values.toArray(Constant).freeze(True);
    }

    @Override
    Group createGroup(Group group) {
        Int groupId = db.groupGen.next();
        group = group.with(groupId = groupId);
        using (db.connection.createTransaction()) {
            // verify groups
            DBMap<Int, Group> groups = db.groups;
            for (Int parentId : group.groupIds) {
                if (parentId == groupId) {
                    throw new GroupLoop(groupId);
                } else if (!groups.contains(parentId)) {
                    throw new MissingGroup(parentId);
                }
            }

            // store the group
            if (!groups.putIfAbsent(groupId, group)) {
                throw new RealmException($"Group id={groupId} already existed");
            }
        }
        return group;
    }

    @Override
    conditional Group readGroup(Int id) = db.groups.get(id);

    @Override
    Group updateGroup(Group group) {
        Int groupId = group.groupId;
        using (db.connection.createTransaction()) {
            Group old;
            if (!(old := readGroup(groupId))) {
                throw new MissingGroup(groupId);
            }

            // verify groups
            DBMap<Int, Group> groups = db.groups;
            for (Int parentId : group.groupIds) {
                if (!groups.contains(parentId)) {
                    throw new MissingGroup(parentId);
                }
            }

            // store the group
            db.groups.put(groupId, group);

            // check for infinite loop of group dependencies
            if (Int loopId := group.circularDependency(this)) {
                throw new GroupLoop(loopId);
            }
        }
        return group;
    }

    @Override
    Boolean deleteGroup(Int|Group group) {
        Int groupId = group.is(Int) ?: group.groupId;

        // check if there are any entities that belong to that group
        if (findPrincipals(p -> !p.groupIds.contains(groupId)).empty ||
            findGroups    (g -> !g.groupIds.contains(groupId)).empty) {

            throw new RealmException("Group is not empty");
        }
        return db.groups.keys.removeIfPresent(groupId);
    }

    // ----- operations: Entitlements --------------------------------------------------------------

    @Override
    immutable Entitlement[] findEntitlements(function Boolean(Entitlement) match) {
        return db.entitlements.filter(e -> match(e.value)).values.toArray(Constant).freeze(True);
    }


    @Override
    conditional Entitlement findEntitlement(String scheme, String locator) {
        if (Int id := db.entitlementLocators.get(munge(scheme, locator))) {
            return readEntitlement(id);
        }
        return False;
    }

    @Override
    Entitlement createEntitlement(Entitlement entitlement) {
        Int entitlementId = db.entitlementGen.next();
        entitlement = entitlement.with(entitlementId = entitlementId);
        using (db.connection.createTransaction()) {
            // verify principal
            if (!db.principals.contains(entitlement.principalId)) {
                throw new MissingPrincipal(entitlement.principalId);
            }

            // add new locators
            DBMap<String, Int> index    = db.entitlementLocators;
            HashSet<String>    locators = locatorsFor(entitlement);
            for (String locator : locators) {
                if (!index.putIfAbsent(locator, entitlementId)) {
                    throw new DuplicateCredential(schemeFrom(locator), locatorFrom(locator));
                }
            }

            // store the entitlement
            if (!db.entitlements.putIfAbsent(entitlementId, entitlement)) {
                throw new RealmException($"Entitlement id={entitlementId} already existed");
            }
        }
        return entitlement;
    }

    @Override
    conditional Entitlement readEntitlement(Int id) = db.entitlements.get(id);

    @Override
    Entitlement updateEntitlement(Entitlement entitlement) {
        Int entitlementId = entitlement.entitlementId;
        using (db.connection.createTransaction()) {
            Entitlement old;
            if (!(old := readEntitlement(entitlementId))) {
                throw new MissingEntitlement(entitlementId);
            }

            // add new locators
            DBMap<String, Int> index       = db.entitlementLocators;
            HashSet<String>    oldLocators = locatorsFor(old);
            HashSet<String>    newLocators = locatorsFor(entitlement);
            for (String locator : newLocators) {
                if (!oldLocators.contains(locator)) {
                    if (!index.putIfAbsent(locator, entitlementId) && index[locator] != entitlementId) {
                        throw new DuplicateCredential(schemeFrom(locator), locatorFrom(locator));
                    }
                }
            }

            // remove unused locators
            for (String locator : oldLocators) {
                if (!newLocators.contains(locator)) {
                    index.remove(locator, entitlementId);
                }
            }

            // store the entitlement
            db.entitlements.put(entitlementId, entitlement);
        }
        return entitlement;
    }

    @Override
    Boolean deleteEntitlement(Int|Entitlement entitlement) {
        Int entitlementId = entitlement.is(Int) ?: entitlement.entitlementId;
        using (db.connection.createTransaction()) {
            if (!(entitlement := readEntitlement(entitlementId))) {
                return False;
            }

            // delete all locators for the entitlement
            db.entitlementLocators.removeAll(e -> e.value == entitlementId);

            // delete the entitlement
            db.entitlements.remove(entitlementId);
        }
        return True;
    }

    // ----- helpers -------------------------------------------------------------------------------

    /**
     * Find an [AuthSchema] inside the database.
     */
    static conditional AuthSchema findAuthSchema(RootSchema rootSchema) {
        String?     path = Null;
        AuthSchema? db   = Null;
        for ((String pathStr, DBObjectInfo info) : rootSchema.sys.schemas) {
            // find the AuthSchema; it must occur exactly-once
            assert DBObject schema ?= info.lookupUsing(rootSchema);
            if (schema.is(AuthSchema)) {
                assert path == Null as $|Ambiguous "AuthSchema" instances found at multiple\
                                        | locations within the database:\
                                        | {pathStr.quoted()} and {path.quoted()}
                                       ;
                path = pathStr;
                db   = schema;
            }
        }
        return db == Null ? False : (True, db);
    }

    /**
     * Munge a credential "scheme" and "locator" together into a string.
     *
     * @param scheme   a credential scheme
     * @param locator  a credential locator
     *
     * @return a string that combines the credential "scheme" and "locator"
     */
    static protected String munge(String scheme, String locator) = $"{scheme}:{locator}";

    /**
     * Given a previously munged string, extract the "scheme" from it.
     *
     * @param munged  a previous result from [munge]
     *
     * @return the credential "scheme"
     */
    static String schemeFrom(String munged) {
        assert Int colon := munged.indexOf(':');
        return munged[0..<colon];
    }

    /**
     * Given a previously munged string, extract the "locator" from it.
     *
     * @param munged  a previous result from [munge]
     *
     * @return the credential "locator"
     */
    static String locatorFrom(String munged) {
        assert Int colon := munged.indexOf(':');
        return munged.substring(colon+1);
    }

    /**
     * Build a set of all "munged" scheme/locator strings for a given Principal or Entitlement.
     *
     * @param subject  a Principal or Entitlement
     *
     * @return a set of all of the [Credential] scheme/locator strings for the specified `Subject`
     */
    static HashSet<String> locatorsFor(Subject subject) {
        HashSet<String> locators = new HashSet();
        for (Credential credential : subject.credentials) {
            String scheme = credential.scheme;
            for (String locator : credential.locators) {
                locators.add(munge(scheme, locator));
            }
        }
        return locators;
    }
}