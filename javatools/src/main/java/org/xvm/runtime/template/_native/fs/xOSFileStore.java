package org.xvm.runtime.template._native.fs;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.Op;

import org.xvm.runtime.ClassTemplate;
import org.xvm.runtime.Container;
import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;

import org.xvm.runtime.template.xBoolean;
import org.xvm.runtime.template.xException;

import org.xvm.runtime.template.numbers.xInt64;

import org.xvm.runtime.template.text.xString.StringHandle;


/**
 * Native OSFileStore implementation.
 */
public class xOSFileStore
        extends ClassTemplate
    {
    public xOSFileStore(Container container, ClassStructure structure, boolean fInstance)
        {
        super(container, structure);
        }

    @Override
    public void initNative()
        {
        markNativeProperty("capacity");
        markNativeProperty("bytesFree");
        markNativeProperty("bytesUsed");

        markNativeMethod("dirFor", STRING, null);
        markNativeMethod("fileFor", STRING, null);
        markNativeMethod("linkAsFile", STRING, null);
        markNativeMethod("copyOrMove", null, null);

        invalidateTypeInfo();
        }

    @Override
    protected int postValidate(Frame frame, ObjectHandle hStruct)
        {
        // we need to make the OSFileStore handle immutable, so it can go across the service
        // boundary, but it holds a reference to a OSStorage service handle, so a call to
        //   makeImmutable(frame, hStruct);
        // would result in a natural exception
        // TODO: consider an option for ClassTemplate.makeImmutable() to exclude service handles

        hStruct.makeImmutable();
        return Op.R_NEXT;
        }

    @Override
    public int invokeNativeGet(Frame frame, String sPropName, ObjectHandle hTarget, int iReturn)
        {
        switch (sPropName)
            {
            case "capacity":
                return frame.assignValue(iReturn, xInt64.makeHandle(ROOT.getTotalSpace()));

            case "bytesFree":
                return frame.assignValue(iReturn, xInt64.makeHandle(ROOT.getFreeSpace()));

            case "bytesUsed":
                return frame.assignValue(iReturn, xInt64.makeHandle(ROOT.getTotalSpace() - ROOT.getFreeSpace()));
            }

        return super.invokeNativeGet(frame, sPropName, hTarget, iReturn);
        }

    @Override
    public int invokeNative1(Frame frame, MethodStructure method, ObjectHandle hTarget,
                             ObjectHandle hArg, int iReturn)
        {
        switch (method.getName())
            {
            case "dirFor":  // (pathString)
                {
                StringHandle hPathString = (StringHandle) hArg;
                try
                    {
                    Path path = Paths.get(hPathString.getStringValue());
                    return xOSFileNode.createHandle(frame, hTarget, path, true, iReturn);
                    }
                catch (InvalidPathException e)
                    {
                    return frame.raiseException(xException.ioException(frame, e.getMessage()));
                    }
                }
            case "fileFor":  // (pathString)
                {
                StringHandle hPathString = (StringHandle) hArg;
                try
                    {
                    Path path = Paths.get(hPathString.getStringValue());
                    return xOSFileNode.createHandle(frame, hTarget, path, false, iReturn);
                    }
                catch (InvalidPathException e)
                    {
                    return frame.raiseException(xException.ioException(frame, e.getMessage()));
                    }
                }
            }

        return super.invokeNative1(frame, method, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeNativeN(Frame frame, MethodStructure method, ObjectHandle hTarget, ObjectHandle[] ahArg, int iReturn)
        {
        switch (method.getName())
            {
            case "copyOrMove":
                {
                ObjectHandle hSrc  = ahArg[0];
                String       sSrc  = ((StringHandle) ahArg[1]).getStringValue();
                ObjectHandle hDest = ahArg[2];
                String       sDest = ((StringHandle) ahArg[3]).getStringValue();
                boolean      fMove = ((xBoolean.BooleanHandle) ahArg[4]).get();

                Path pathResult;
                try
                    {
                    Path    pathSrc = Paths.get(sSrc);
                    boolean fDir    = Files.isDirectory(pathSrc);
                    if (Files.notExists(pathSrc))
                        {
                        return frame.raiseException(xException.fileNotFoundException(
                                frame, "Could not find file or directory: " + sSrc, hSrc));
                        }

                    Path pathDest = Paths.get(sDest);
                    if (Files.exists(pathDest) && !Files.isDirectory(pathDest))
                        {
                        return frame.raiseException(xException.fileAlreadyExistsException(
                                frame, "Could not overwrite file or directory: " + sDest, hDest));
                        }

                    pathResult = fMove
                            ? Files.move(pathSrc, pathDest)
                            : Files.copy(pathSrc, pathDest);
                    return xOSFileNode.createHandle(frame, hTarget, pathResult, fDir, iReturn);
                    }
                catch (NoSuchFileException | FileNotFoundException e)
                    {
                    return frame.raiseException(xException.fileNotFoundException(frame, e.getMessage(), hSrc));
                    }
                catch (FileAlreadyExistsException e)
                    {
                    return frame.raiseException(xException.fileAlreadyExistsException(frame, e.getMessage(), hDest));
                    }
                catch (SecurityException | AccessDeniedException e)
                    {
                    return frame.raiseException(xException.accessDeniedException(frame, e.getMessage(), hDest));
                    }
                catch (IOException|InvalidPathException e)
                    {
                    return frame.raiseException(xException.ioException(frame, e.getMessage()));
                    }
                }
            }

        return super.invokeNativeN(frame, method, hTarget, ahArg, iReturn);
        }

    @Override
    public int invokeNativeNN(Frame frame, MethodStructure method, ObjectHandle hTarget,
                              ObjectHandle[] ahArg, int[] aiReturn)
        {
        switch (method.getName())
            {
            case "linkAsFile": // pathString
                {
                StringHandle hPathString = (StringHandle) ahArg[0];
                try
                    {
                    Path path  = Paths.get(hPathString.getStringValue());

                    if (Files.isSymbolicLink(path))
                        {
                        // TODO: implement native support for link files
                        System.err.println("*** File is a link: " + path);
                        }
                    return frame.assignValue(aiReturn[0], xBoolean.FALSE);
                    }
                catch (InvalidPathException e)
                    {
                    return frame.raiseException(xException.ioException(frame, e.getMessage()));
                    }
                }
            }
        return super.invokeNativeNN(frame, method, hTarget, ahArg, aiReturn);
        }


    // ----- constants -----------------------------------------------------------------------------

    private static final File ROOT = new File("/");
    }