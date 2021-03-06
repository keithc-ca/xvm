package org.xvm.runtime.template.collections;


import org.xvm.asm.ClassStructure;
import org.xvm.asm.Constant;
import org.xvm.asm.MethodStructure;

import org.xvm.asm.constants.TypeConstant;
import org.xvm.asm.constants.UInt8ArrayConstant;

import org.xvm.runtime.ClassTemplate;
import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.TemplateRegistry;
import org.xvm.runtime.TypeComposition;

import org.xvm.runtime.template.xEnum;
import org.xvm.runtime.template.xException;

import org.xvm.runtime.template.numbers.xInt64;

import org.xvm.runtime.template._native.collections.arrays.BitView;
import org.xvm.runtime.template._native.collections.arrays.ByteView;
import org.xvm.runtime.template._native.collections.arrays.xRTDelegate.DelegateHandle;
import org.xvm.runtime.template._native.collections.arrays.xRTSlicingDelegate.SliceHandle;
import org.xvm.runtime.template._native.collections.arrays.xRTViewFromByteToInt8;
import org.xvm.runtime.template._native.collections.arrays.xRTViewFromByteToInt16;
import org.xvm.runtime.template._native.collections.arrays.xRTViewFromByteToInt64;

import org.xvm.util.Handy;


/**
 * Native ByteArray implementation.
 */
public class xByteArray
        extends xArray
    {
    public static xByteArray INSTANCE;

    public xByteArray(TemplateRegistry templates, ClassStructure structure, boolean fInstance)
        {
        super(templates, structure, false);

        if (fInstance)
            {
            INSTANCE = this;
            }
        }

    @Override
    public void initNative()
        {
        ClassTemplate mixin = f_templates.getTemplate("collections.arrays.ByteArray");

        mixin.markNativeMethod("asInt8Array", VOID, null);
        mixin.markNativeMethod("asInt16Array", VOID, null);
        mixin.markNativeMethod("asInt64Array", VOID, null);
        mixin.markNativeMethod("toBitArray", null, null);
        mixin.markNativeMethod("toInt64", VOID, null);

        getCanonicalType().invalidateTypeInfo();
        }

    @Override
    public TypeConstant getCanonicalType()
        {
        return pool().typeByteArray();
        }

    @Override
    public int createConstHandle(Frame frame, Constant constant)
        {
        if (constant instanceof UInt8ArrayConstant)
            {
            UInt8ArrayConstant constBytes = (UInt8ArrayConstant) constant;

            return frame.pushStack(makeByteArrayHandle(constBytes.getValue(), Mutability.Constant));
            }

        return super.createConstHandle(frame, constant);
        }

    @Override
    public int invokeNative1(Frame frame, MethodStructure method, ObjectHandle hTarget,
                             ObjectHandle hArg, int iReturn)
        {
        switch (method.getName())
            {
            case "toBitArray":
                {
                ArrayHandle hArray = (ArrayHandle) hTarget;
                byte[]      aBytes = xByteArray.getBytes(hArray);

                Mutability mutability = hArg == ObjectHandle.DEFAULT
                        ? Mutability.Constant
                        : Mutability.values()[((xEnum.EnumHandle) hArg).getOrdinal()];

                if (hArray.m_mutability == Mutability.Constant && mutability != Mutability.Constant)
                    {
                    // if the array is not constant, getBytes() returns a copy
                    aBytes = aBytes.clone();
                    }

                return frame.assignValue(iReturn,
                        xArray.makeBitArrayHandle(aBytes, aBytes.length >>> 3, mutability));
                }
            }

        return super.invokeNative1(frame, method, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeNativeN(Frame frame, MethodStructure method, ObjectHandle hTarget,
                             ObjectHandle[] ahArg, int iReturn)
        {
        switch (method.getName())
            {
            case "asInt8Array":
                {
                ArrayHandle hArray = (ArrayHandle) hTarget;

                Mutability      mutability = hArray.m_mutability;
                DelegateHandle  hView      = xRTViewFromByteToInt8.INSTANCE.createByteView(
                                                    hArray.m_hDelegate, mutability, 1);
                return frame.assignValue(iReturn,
                        new ArrayHandle(getInt8ArrayComposition(), hView, mutability));
                }

            case "asInt16Array":
                {
                ArrayHandle hArray = (ArrayHandle) hTarget;

                if (hArray.m_hDelegate.m_cSize % 2 != 0)
                    {
                    return frame.raiseException(xException.illegalArgument(frame,
                                "Invalid array size: " + hArray.m_hDelegate.m_cSize));
                    }

                Mutability     mutability = hArray.m_mutability;
                DelegateHandle hView      = xRTViewFromByteToInt16.INSTANCE.createByteView(
                                                    hArray.m_hDelegate, mutability, 2);
                return frame.assignValue(iReturn,
                        new ArrayHandle(getInt16ArrayComposition(), hView, mutability));
                }

            case "asInt64Array":
                {
                ArrayHandle hArray = (ArrayHandle) hTarget;

                if (hArray.m_hDelegate.m_cSize % 8 != 0)
                    {
                    return frame.raiseException(xException.illegalArgument(frame,
                                "Invalid array size: " + hArray.m_hDelegate.m_cSize));
                    }

                Mutability     mutability = hArray.m_mutability;
                DelegateHandle hView      = xRTViewFromByteToInt64.INSTANCE.createByteView(
                                                    hArray.m_hDelegate, mutability, 8);
                return frame.assignValue(iReturn,
                        new ArrayHandle(getInt64ArrayComposition(), hView, mutability));
                }

            case "toInt64":
                {
                byte[] ab = getBytes((ArrayHandle) hTarget);

                if (ab.length != 8)
                    {
                    return frame.raiseException(
                            xException.illegalArgument(frame, "Invalid array size: " + ab.length));
                    }

                return frame.assignValue(iReturn,
                        xInt64.makeHandle(Handy.byteArrayToLong(ab, 0)));
                }
            }

        return super.invokeNativeN(frame, method, hTarget, ahArg, iReturn);
        }

    /**
     * Extract a array of bytes from the Array<Byte> handle.
     */
    public static byte[] getBytes(ArrayHandle hArray)
        {
        DelegateHandle hDelegate = hArray.m_hDelegate;
        long           cSize     = hDelegate.m_cSize;
        long           ofStart   = 0;
        boolean        fReverse  = false;

        if (hDelegate instanceof SliceHandle)
            {
            SliceHandle hSlice = (SliceHandle) hDelegate;
            hDelegate = hSlice.f_hSource;
            ofStart   = hSlice.f_ofStart;
            fReverse  = hSlice.f_fReverse;
            }

        ClassTemplate tDelegate = hDelegate.getTemplate();
        if (tDelegate instanceof ByteView)
            {
            return ((ByteView) tDelegate).getBytes(hDelegate, ofStart, cSize, fReverse);
            }
        throw new UnsupportedOperationException();
        }

    /**
     * Copy bytes from the specified array.
     */
    public static void setBytes(ArrayHandle hArray, byte[] abVal)
        {
        DelegateHandle hDelegate = hArray.m_hDelegate;

        long ofStart = 0;

        if (hDelegate instanceof SliceHandle)
            {
            SliceHandle hSlice = (SliceHandle) hDelegate;
            hDelegate = hSlice.f_hSource;
            ofStart   = hSlice.f_ofStart;
            }

        ClassTemplate tDelegate = hDelegate.getTemplate();
        if (tDelegate instanceof BitView)
            {
            ByteView tView = (ByteView) tDelegate;

            for (int i = 0, c = abVal.length; i < c; i++)
                {
                tView.assignByte(hDelegate, ofStart + i, abVal[i]);
                }
            }
        throw new UnsupportedOperationException();
        }

    private TypeComposition getInt8ArrayComposition()
        {
        TypeComposition clz = INT8_ARRAY_CLZ;
        if (clz == null)
            {
            TypeConstant typeInt8 = pool().ensureEcstasyTypeConstant("numbers.Int8");

            INT8_ARRAY_CLZ = clz = f_templates.resolveClass(pool().ensureArrayType(typeInt8));
            }
        return clz;
        }

    private TypeComposition getInt16ArrayComposition()
        {
        TypeComposition clz = INT16_ARRAY_CLZ;
        if (clz == null)
            {
            TypeConstant typeInt16 = pool().ensureEcstasyTypeConstant("numbers.Int16");
            INT16_ARRAY_CLZ = clz = f_templates.resolveClass(pool().ensureArrayType(typeInt16));
            }
        return clz;
        }

    private TypeComposition getInt64ArrayComposition()
        {
        TypeComposition clz = INT64_ARRAY_CLZ;
        if (clz == null)
            {
            INT64_ARRAY_CLZ = clz = f_templates.resolveClass(pool().ensureArrayType(pool().typeInt()));
            }
        return clz;
        }

    private static TypeComposition INT8_ARRAY_CLZ;
    private static TypeComposition INT16_ARRAY_CLZ;
    private static TypeComposition INT64_ARRAY_CLZ;
    }
