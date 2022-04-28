package org.xvm.runtime;


import java.io.File;
import java.io.IOException;

import java.net.URL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;

import java.util.function.BiFunction;
import java.util.function.Consumer;

import java.util.jar.JarFile;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.Component;
import org.xvm.asm.Constant;
import org.xvm.asm.ConstantPool;
import org.xvm.asm.Constants;
import org.xvm.asm.FileStructure;
import org.xvm.asm.InjectionKey;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.ModuleRepository;
import org.xvm.asm.ModuleStructure;
import org.xvm.asm.Op;
import org.xvm.asm.TypedefStructure;

import org.xvm.asm.constants.IdentityConstant;
import org.xvm.asm.constants.MethodConstant;
import org.xvm.asm.constants.PropertyConstant;
import org.xvm.asm.constants.TypeConstant;

import org.xvm.runtime.ObjectHandle.DeferredCallHandle;

import org.xvm.runtime.template.xConst;
import org.xvm.runtime.template.xEnum;
import org.xvm.runtime.template.xObject;
import org.xvm.runtime.template.xService;

import org.xvm.runtime.template.collections.xArray;

import org.xvm.runtime.template.text.xString;
import org.xvm.runtime.template.text.xString.StringHandle;

import org.xvm.runtime.template._native.xTerminalConsole;

import org.xvm.runtime.template._native.lang.src.xRTCompiler;

import org.xvm.runtime.template._native.mgmt.xContainerLinker;
import org.xvm.runtime.template._native.mgmt.xCoreRepository;

import org.xvm.runtime.template._native.numbers.xRTRandom;

import org.xvm.runtime.template._native.reflect.xRTFunction;
import org.xvm.runtime.template._native.reflect.xRTType;

import org.xvm.runtime.template._native.temporal.xLocalClock;
import org.xvm.runtime.template._native.temporal.xNanosTimer;

import org.xvm.runtime.template._native.web.xRTServer;

import org.xvm.util.Handy;


/**
 * The main container (zero) associated with the main module.
 */
public class NativeContainer
        extends Container
    {
    public NativeContainer(Runtime runtime, ModuleRepository repository)
        {
        super(runtime, null, null);

        f_repository = repository;

        ConstantPool pool = loadNativeTemplates();
        try (var ignore = ConstantPool.withPool(pool))
            {
            initResources(pool);
            }
        }


    // ----- initialization ------------------------------------------------------------------------

    private ConstantPool loadNativeTemplates()
        {
        ModuleStructure moduleRoot   = f_repository.loadModule(Constants.ECSTASY_MODULE);
        ModuleStructure moduleNative = f_repository.loadModule(NATIVE_MODULE);

        // "root" is a merge of "native" module into the "system"
        FileStructure containerRoot = new FileStructure(moduleRoot);
        containerRoot.merge(moduleNative, true);

        // obtain the cloned modules that belong to the merged container
        m_moduleSystem = (ModuleStructure) containerRoot.getChild(Constants.ECSTASY_MODULE);
        m_moduleNative = (ModuleStructure) containerRoot.getChild(NATIVE_MODULE);

        ConstantPool pool = containerRoot.getConstantPool();
        ConstantPool.setCurrentPool(pool);

        if (pool.getNakedRefType() == null)
            {
            ClassStructure clzNakedRef = (ClassStructure) m_moduleNative.getChild("NakedRef");
            pool.setNakedRefType(clzNakedRef.getFormalType());
            }

        Class clzObject = xObject.class;
        URL url      = clzObject.getProtectionDomain().getCodeSource().getLocation();
        String sRoot    = url.getFile();
        Map<String, Class> mapTemplateClasses = new HashMap<>();
        if (sRoot.endsWith(".jar"))
            {
            scanNativeJarDirectory(sRoot, "org/xvm/runtime/template", mapTemplateClasses);
            }
        else
            {
            File dirTemplates = new File(sRoot, "org/xvm/runtime/template");
            scanNativeDirectory(dirTemplates, "", mapTemplateClasses);
            }

        // we need a number of INSTANCE static variables to be set up right away
        // (they are used by the ClassTemplate constructor)
        storeNativeTemplate(new xObject (this, getClassStructure("Object"),  true));
        storeNativeTemplate(new xEnum   (this, getClassStructure("Enum"),    true));
        storeNativeTemplate(new xConst  (this, getClassStructure("Const"),   true));
        storeNativeTemplate(new xService(this, getClassStructure("Service"), true));

        for (Map.Entry<String, Class> entry : mapTemplateClasses.entrySet())
            {
            ClassStructure structClass = getClassStructure(entry.getKey());
            if (structClass == null)
                {
                // this is a native class for a composite type;
                // it will be declared by the corresponding "primitive"
                // (see xArray.initNative() for an example)
                continue;
                }

            if (f_mapTemplatesByType.containsKey(
                    structClass.getIdentityConstant().getType()))
                {
                // already loaded - one of the "base" classes
                continue;
                }

            Class<ClassTemplate> clz = entry.getValue();

            try
                {
                storeNativeTemplate(clz.getConstructor(
                    Container.class, ClassStructure.class, Boolean.TYPE).
                    newInstance(this, structClass, Boolean.TRUE));
                }
            catch (Exception e)
                {
                throw new RuntimeException("Constructor failed for " + clz.getName(), e);
                }
            }

        // add run-time templates
        f_mapTemplatesByType.put(pool.typeFunction(), xRTFunction.INSTANCE);
        f_mapTemplatesByType.put(pool.typeType()    , xRTType.INSTANCE);

        // clone the map since the loop below can add to it
        Set<ClassTemplate> setTemplates = new HashSet<>(f_mapTemplatesByType.values());

        for (ClassTemplate template : setTemplates)
            {
            template.registerNativeTemplates();
            }

        Utils.initNative(this);

        for (ClassTemplate template : f_mapTemplatesByType.values())
            {
            template.initNative();
            }
        ConstantPool.setCurrentPool(null);
        return pool;
        }

    private void scanNativeJarDirectory(String sJarFile, String sPackage, Map<String, Class> mapTemplateClasses)
        {
        JarFile jf;
        try
            {
            jf = new JarFile(sJarFile);
            }
        catch (IOException e)
            {
            throw new RuntimeException(e);
            }

        jf.stream().filter(e -> isNativeClass(sPackage, e.getName()))
                   .forEach(e -> mapTemplateClasses.put(componentName(e.getName()), classForName(e.getName())));
        }

    private static boolean isNativeClass(String sPackage, String sFile)
        {
        return sFile.startsWith(sPackage)
            && sFile.endsWith(".class")
            && sFile.indexOf('$') < 0
            && sFile.charAt(sFile.lastIndexOf('/') + 1) == 'x';
        }

    private static String componentName(String sFile)
        {
        // input : org/xvm/runtime/template/numbers/xFloat64.class
        // output: numbers.Float64
        String[]      parts = Handy.parseDelimitedString(sFile, '/');
        StringBuilder sb    = new StringBuilder();
        for (int i = 4, c = parts.length - 1; i < c; ++i)
            {
            sb.append(parts[i])
              .append('.');
            }
        String sClass = parts[parts.length-1];
        assert sClass.charAt(0) == 'x';
        assert sClass.endsWith(".class");
        sb.append(sClass, 1, sClass.indexOf('.'));
        return sb.toString();
        }

    private static Class classForName(String sFile)
        {
        assert sFile.endsWith(".class");
        String sClz = sFile.substring(0, sFile.length() - ".class".length()).replace('/', '.');
        try
            {
            return Class.forName(sClz);
            }
        catch (ClassNotFoundException e)
            {
            throw new RuntimeException(e);
            }
        }

    // sPackage is either empty or ends with a dot
    private void scanNativeDirectory(File dirNative, String sPackage, Map<String, Class> mapTemplateClasses)
        {
        for (String sName : dirNative.list())
            {
            if (sName.endsWith(".class"))
                {
                if (sName.startsWith("x") && !sName.contains("$"))
                    {
                    String sSimpleName = sName.substring(1, sName.length() - 6);
                    String sQualifiedName = sPackage + sSimpleName;
                    String sClass = "org.xvm.runtime.template." + sPackage + "x" + sSimpleName;

                    try
                        {
                        mapTemplateClasses.put(sQualifiedName, Class.forName(sClass));
                        }
                    catch (ClassNotFoundException e)
                        {
                        throw new IllegalStateException("Cannot load " + sClass, e);
                        }
                    }
                }
            else
                {
                File dir = new File(dirNative, sName);
                if (dir.isDirectory())
                    {
                    scanNativeDirectory(dir, sPackage.isEmpty() ? sName + '.' : sPackage + sName + '.',
                        mapTemplateClasses);
                    }
                }
            }
        }

    private void storeNativeTemplate(ClassTemplate template)
        {
        // register just a naked underlying type
        TypeConstant typeBase = template.getClassConstant().getType();

        registerNativeTemplate(typeBase, template);
        }

    private void initResources(ConstantPool pool)
        {
        // +++ LocalClock
        xLocalClock  templateClock = xLocalClock.INSTANCE;
        TypeConstant typeClock     = templateClock.getCanonicalType();
        addResourceSupplier(new InjectionKey("clock"     , typeClock), templateClock::ensureDefaultClock);
        addResourceSupplier(new InjectionKey("localClock", typeClock), templateClock::ensureLocalClock);
        addResourceSupplier(new InjectionKey("utcClock"  , typeClock), templateClock::ensureUTCClock);

        // +++ NanosTimer
        xNanosTimer  templateTimer = xNanosTimer.INSTANCE;
        TypeConstant typeTimer     = templateTimer.getCanonicalType();
        addResourceSupplier(new InjectionKey("timer", typeTimer), templateTimer::ensureTimer);

        // +++ Console
        xTerminalConsole templateConsole = xTerminalConsole.INSTANCE;
        TypeConstant     typeConsole     = templateConsole.getCanonicalType();
        addResourceSupplier(new InjectionKey("console", typeConsole), templateConsole::ensureConsole);

        // +++ Random
        xRTRandom    templateRandom = xRTRandom.INSTANCE;
        TypeConstant typeRandom     = templateRandom.getCanonicalType();
        addResourceSupplier(new InjectionKey("rnd"   , typeRandom), templateRandom::ensureDefaultRandom);
        addResourceSupplier(new InjectionKey("random", typeRandom), templateRandom::ensureDefaultRandom);

        // +++ OSFileStore etc.
        TypeConstant typeFileStore = pool.ensureEcstasyTypeConstant("fs.FileStore");
        TypeConstant typeDirectory = pool.ensureEcstasyTypeConstant("fs.Directory");
        addResourceSupplier(new InjectionKey("storage", typeFileStore), this::ensureFileStore);
        addResourceSupplier(new InjectionKey("rootDir", typeDirectory), this::ensureRootDir);
        addResourceSupplier(new InjectionKey("homeDir", typeDirectory), this::ensureHomeDir);
        addResourceSupplier(new InjectionKey("curDir" , typeDirectory), this::ensureCurDir);
        addResourceSupplier(new InjectionKey("tmpDir" , typeDirectory), this::ensureTmpDir);

        // +++ WebServer
        xRTServer    templateServer = xRTServer.INSTANCE;
        TypeConstant typeServer     = templateServer.getCanonicalType();
        addResourceSupplier(new InjectionKey("server", typeServer), templateServer::ensureServer);

        // +++ Linker
        xContainerLinker templateLinker = xContainerLinker.INSTANCE;
        TypeConstant     typeLinker     = templateLinker.getCanonicalType();
        addResourceSupplier(new InjectionKey("linker", typeLinker), templateLinker::ensureLinker);

        // +++ ModuleRepository
        xCoreRepository templateRepo = xCoreRepository.INSTANCE;
        TypeConstant    typeRepo     = templateRepo.getCanonicalType();
        addResourceSupplier(new InjectionKey("repository", typeRepo), templateRepo::ensureModuleRepository);

        // +++ Compiler
        xRTCompiler  templateCompiler = xRTCompiler.INSTANCE;
        TypeConstant typeCompiler     = templateCompiler.getCanonicalType();
        addResourceSupplier(new InjectionKey("compiler", typeCompiler), templateCompiler::ensureCompiler);

        // ++ xvmProperties
        TypeConstant typeProps = pool.ensureParameterizedTypeConstant(pool.typeMap(), pool.typeString(), pool.typeString());
        addResourceSupplier(new InjectionKey("properties", typeProps), this::ensureProperties);
        }

    /**
     * Add a native resource supplier for an injection.
     *
     * @param key  the injection key
     * @param fn   the resource supplier bi-function
     */
    private void addResourceSupplier(InjectionKey key, BiFunction<Frame, ObjectHandle, ObjectHandle> fn)
        {
        assert !f_mapResources.containsKey(key);

        f_mapResources.put(key, fn);
        f_mapResourceNames.put(key.f_sName, key);
        }

    protected ObjectHandle ensureOSStorage(Frame frame, ObjectHandle hOpts)
        {
        ObjectHandle hStorage = m_hOSStorage;
        if (hStorage == null)
            {
            ClassTemplate    templateStorage = getTemplate("_native.fs.OSStorage");
            ClassComposition clzStorage      = templateStorage.getCanonicalClass();
            MethodStructure  constructor     = templateStorage.getStructure().findConstructor();

            switch (templateStorage.construct(frame, constructor, clzStorage,
                                              null, Utils.OBJECTS_NONE, Op.A_STACK))
                {
                case Op.R_NEXT:
                    hStorage = frame.popStack();
                    break;

                case Op.R_EXCEPTION:
                    break;

                case Op.R_CALL:
                    {
                    Frame frameNext = frame.m_frameNext;
                    frameNext.addContinuation(frameCaller ->
                        {
                        m_hOSStorage = frameCaller.peekStack();
                        return Op.R_NEXT;
                        });
                    return new DeferredCallHandle(frameNext);
                    }

                default:
                    throw new IllegalStateException();
                }
            m_hOSStorage = hStorage;
            }

        return hStorage;
        }

    private ObjectHandle ensureFileStore(Frame frame, ObjectHandle hOpts)
        {
        ObjectHandle hStore = m_hFileStore;
        if (hStore == null)
            {
            ObjectHandle hOSStorage = ensureOSStorage(frame, hOpts);
            if (hOSStorage != null)
                {
                ClassTemplate    template = getTemplate("_native.fs.OSStorage");
                PropertyConstant idProp   = template.getCanonicalType().
                        ensureTypeInfo().findProperty("fileStore").getIdentity();

                return getProperty(frame, hOSStorage, idProp, h -> m_hFileStore = h);
                }
            }

        return hStore;
        }

    private ObjectHandle ensureRootDir(Frame frame, ObjectHandle hOpts)
        {
        ObjectHandle hDir = m_hRootDir;
        if (hDir == null)
            {
            ObjectHandle hOSStorage = ensureOSStorage(frame, hOpts);
            if (hOSStorage != null)
                {
                ClassTemplate    template = getTemplate("_native.fs.OSStorage");
                PropertyConstant idProp   = template.getCanonicalType().
                        ensureTypeInfo().findProperty("rootDir").getIdentity();

                return getProperty(frame, hOSStorage, idProp, h -> m_hRootDir = h);
                }
            }

        return hDir;
        }

    private ObjectHandle ensureHomeDir(Frame frame, ObjectHandle hOpts)
        {
        ObjectHandle hDir = m_hHomeDir;
        if (hDir == null)
            {
            ObjectHandle hOSStorage = ensureOSStorage(frame, hOpts);
            if (hOSStorage != null)
                {
                ClassTemplate    template = getTemplate("_native.fs.OSStorage");
                PropertyConstant idProp   = template.getCanonicalType().
                        ensureTypeInfo().findProperty("homeDir").getIdentity();

                return getProperty(frame, hOSStorage, idProp, h -> m_hHomeDir = h);
                }
            }

        return hDir;
        }

    private ObjectHandle ensureCurDir(Frame frame, ObjectHandle hOpts)
        {
        ObjectHandle hDir = m_hCurDir;
        if (hDir == null)
            {
            ObjectHandle hOSStorage = ensureOSStorage(frame, hOpts);
            if (hOSStorage != null)
                {
                ClassTemplate    template = getTemplate("_native.fs.OSStorage");
                PropertyConstant idProp   = template.getCanonicalType().
                        ensureTypeInfo().findProperty("curDir").getIdentity();

                return getProperty(frame, hOSStorage, idProp, h -> m_hCurDir = h);
                }
            }

        return hDir;
        }

    private ObjectHandle ensureTmpDir(Frame frame, ObjectHandle hOpts)
        {
        ObjectHandle hDir = m_hTmpDir;
        if (hDir == null)
            {
            ObjectHandle hOSStorage = ensureOSStorage(frame, hOpts);
            if (hOSStorage != null)
                {
                ClassTemplate    template = getTemplate("_native.fs.OSStorage");
                PropertyConstant idProp   = template.getCanonicalType().
                        ensureTypeInfo().findProperty("tmpDir").getIdentity();

                return getProperty(frame, hOSStorage, idProp, h -> m_hTmpDir = h);
                }
            }

        return hDir;
        }

    private ObjectHandle ensureProperties(Frame frame, ObjectHandle hOpts)
        {
        ObjectHandle hProps = m_hProperties;
        if (hProps == null)
            {
            List<StringHandle> listKeys = new ArrayList<>();
            List<StringHandle> listVals = new ArrayList<>();
            for (String sKey : (Set<String>) (Set) System.getProperties().keySet())
                {
                if (sKey.startsWith("xvm."))
                    {
                    String sVal = System.getProperty(sKey);
                    if (sVal != null)
                        {
                        listKeys.add(xString.makeHandle(sKey.substring(4)));
                        listVals.add(xString.makeHandle(sVal));
                        }
                    }
                }
            ObjectHandle haKeys   = xArray.makeStringArrayHandle(listKeys.toArray(Utils.STRINGS_NONE));
            ObjectHandle haValues = xArray.makeStringArrayHandle(listVals.toArray(Utils.STRINGS_NONE));

            ConstantPool pool    = frame.poolContext();
            TypeConstant typeMap = pool.ensureParameterizedTypeConstant(
                                    pool.ensureEcstasyTypeConstant("collections.ListMap"),
                                    pool.typeString(),
                                    pool.typeString());

            switch (Utils.constructListMap(frame,
                            typeMap.ensureClass(frame), haKeys, haValues, Op.A_STACK))
                {
                case Op.R_NEXT:
                    hProps = frame.popStack();
                    break;

                case Op.R_EXCEPTION:
                    break;

                case Op.R_CALL:
                    {
                    Frame frameNext = frame.m_frameNext;
                    frameNext.addContinuation(frameCaller ->
                        {
                        m_hProperties = frameCaller.peekStack();
                        return Op.R_NEXT;
                        });
                    return new DeferredCallHandle(frameNext);
                    }

                default:
                    throw new IllegalStateException();
                }
            m_hProperties = hProps;
            }

        return hProps;
        }

    /**
     * Helper method to get a property on the specified target.
     */
    private ObjectHandle getProperty(Frame frame, ObjectHandle hTarget, PropertyConstant idProp,
                                     Consumer<ObjectHandle> consumer)
        {
        TypeConstant typeRevealed = idProp.getType();
        if (hTarget instanceof DeferredCallHandle hDeferred)
            {
            hDeferred.addContinuation(frameCaller ->
                {
                ObjectHandle hTargetReal = frameCaller.popStack();
                int          iResult     = hTargetReal.getTemplate().getPropertyValue(
                                                frameCaller, hTargetReal, idProp, Op.A_STACK);
                switch (iResult)
                    {
                    case Op.R_NEXT:
                        {
                        ObjectHandle h = frameCaller.popStack().maskAs(this, typeRevealed);
                        frameCaller.pushStack(h);
                        consumer.accept(h);
                        break;
                        }

                    case Op.R_CALL:
                        frameCaller.m_frameNext.addContinuation(frameCaller1 ->
                            {
                            ObjectHandle h = frameCaller1.popStack().maskAs(this, typeRevealed);
                            consumer.accept(h);
                            return frameCaller1.pushStack(h);
                            });
                        break;
                    }
                return iResult;
                });
            return hTarget;
            }

        ClassTemplate template = hTarget.getTemplate();
        switch (template.getPropertyValue(frame, hTarget, idProp, Op.A_STACK))
            {
            case Op.R_NEXT:
                {
                ObjectHandle h = frame.popStack().maskAs(this, typeRevealed);
                consumer.accept(h);
                return h;
                }

            case Op.R_CALL:
                Frame frameNext = frame.m_frameNext;
                frameNext.addContinuation(frameCaller ->
                    {
                    ObjectHandle h = frameCaller.popStack().maskAs(this, typeRevealed);
                    consumer.accept(h);
                    return frameCaller.pushStack(h);
                    });
                return new DeferredCallHandle(frameNext);

            case Op.R_EXCEPTION:
                return new DeferredCallHandle(frame.m_hException);

            default:
                throw new IllegalStateException();
            }
        }


    // ----- Container methods ---------------------------------------------------------------------

    @Override
    public ConstantPool getConstantPool()
        {
        return m_moduleNative.getConstantPool();
        }

    @Override
    public ObjectHandle getInjectable(Frame frame, String sName, TypeConstant type, ObjectHandle hOpts)
        {
        InjectionKey key = f_mapResourceNames.get(sName);

        return key != null && key.f_type.isA(type)
                ? f_mapResources.get(key).apply(frame, hOpts)
                : null;
        }


    @Override
    public ClassTemplate getTemplate(String sName)
        {
        return getTemplate(getIdentityConstant(sName));
        }

    @Override
    public ClassStructure getClassStructure(String sName)
        {
        Component component = sName.startsWith(PREF_NATIVE)
                ? m_moduleNative.getChildByPath(sName.substring(PREF_LENGTH))
                : m_moduleSystem.getChildByPath(sName);

        while (component instanceof TypedefStructure typedef)
            {
            component = typedef.getType().getSingleUnderlyingClass(true).getComponent();
            }

        return (ClassStructure) component;
        }

    @Override
    public ModuleRepository getModuleRepository()
        {
        return f_repository;
        }


    // ----- helpers -------------------------------------------------------------------------------

    /**
     * Register the specified native template.
     */
    public void registerNativeTemplate(TypeConstant type, ClassTemplate template)
        {
        f_mapTemplatesByType.putIfAbsent(type, template);
        }

    /**
     * Create a new FileStructure for the specified module built on top of the system modules.
     *
     * @param moduleApp  the module to build a FileStructure for
     *
     * @return a new FileStructure
     */
    public FileStructure createFileStructure(ModuleStructure moduleApp)
        {
        FileStructure structApp = new FileStructure(m_moduleSystem);
        structApp.merge(m_moduleNative, false);
        structApp.merge(moduleApp, true);

        assert structApp.validateConstants();

        return structApp;
        }

    /**
     * Obtain an object type for the specified constant.
     */
    protected TypeConstant getConstType(Constant constValue)
        {
        String sComponent;

        switch (constValue.getFormat())
            {
            case Char, String:
            case Bit,  Nibble:

            case IntLiteral, FPLiteral:

            case CInt8,    Int8,   CUInt8,   UInt8:
            case CInt16,   Int16,  CUInt16,  UInt16:
            case CInt32,   Int32,  CUInt32,  UInt32:
            case CInt64,   Int64,  CUInt64,  UInt64:
            case CInt128,  Int128, CUInt128, UInt128:
            case CIntN,    IntN,   CUIntN,   UIntN:
            case BFloat16:
            case Float16, Float32, Float64, Float128, FloatN:
            case          Dec32,   Dec64,   Dec128,   DecN:

            case Array, UInt8Array:
            case Date, Time, DateTime, Duration:
            case Range, Path, Version, RegEx:
            case Module, Package:
            case Tuple:
                return constValue.getType();

            case FileStore:
                sComponent = "_native.fs.CPFileStore";
                break;

            case FSDir:
                sComponent = "_native.fs.CPDirectory";
                break;

            case FSFile:
                sComponent = "_native.fs.CPFile";
                break;

            case Map:
                sComponent = "collections.ListMap";
                break;

            case Set:
                // see xArray.createConstHandle()
                sComponent = "collections.Array";
                break;

            case MapEntry:
                throw new UnsupportedOperationException("TODO: " + constValue);

            case Class:
            case DecoratedClass:
            case NativeClass:
                sComponent = "reflect.Class";
                break;

            case PropertyClassType:
                sComponent = "_native.reflect.RTProperty";
                break;

            case AnnotatedType, ParameterizedType:
            case ImmutableType, AccessType, TerminalType:
            case UnionType, IntersectionType, DifferenceType:
                sComponent = "_native.reflect.RTType";
                break;

            case Method:
                sComponent = ((MethodConstant) constValue).isFunction()
                        ? "_native.reflect.RTFunction" : "_native.reflect.RTMethod";
                break;

            default:
                throw new IllegalStateException(constValue.toString());
            }

        return getClassStructure(sComponent).getIdentityConstant().getType();
        }

    private IdentityConstant getIdentityConstant(String sName)
        {
        try
            {
            return f_mapIdByName.computeIfAbsent(sName, s ->
                getClassStructure(s).getIdentityConstant());
            }
        catch (NullPointerException e)
            {
            throw new IllegalArgumentException("Missing constant: " + sName);
            }
        }

    @Override
    public String toString()
        {
        return "Primordial container";
        }


    // ----- constants and data fields -------------------------------------------------------------

    private static final String NATIVE_MODULE = Constants.PROTOTYPE_MODULE;
    private static final String PREF_NATIVE   = "_native.";
    private static final int    PREF_LENGTH   = PREF_NATIVE.length();

    private ObjectHandle m_hOSStorage;
    private ObjectHandle m_hFileStore;
    private ObjectHandle m_hRootDir;
    private ObjectHandle m_hHomeDir;
    private ObjectHandle m_hCurDir;
    private ObjectHandle m_hTmpDir;
    private ObjectHandle m_hProperties;

    private final ModuleRepository f_repository;
    private       ModuleStructure  m_moduleSystem;
    private       ModuleStructure  m_moduleNative;

    /**
     * Map of IdentityConstants by name.
     */
    private final Map<String, IdentityConstant> f_mapIdByName = new ConcurrentHashMap<>();

    /**
     * Map of resource names for a name based lookup.
     */
    private final Map<String, InjectionKey> f_mapResourceNames = new HashMap<>();

    /**
     * Map of resources that are injectable from this container, keyed by their InjectionKey.
     */
    private final Map<InjectionKey, BiFunction<Frame, ObjectHandle, ObjectHandle>>
            f_mapResources = new HashMap<>();
    }