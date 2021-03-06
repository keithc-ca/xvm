package org.xvm.runtime.template.collections;


import org.xvm.asm.ClassStructure;
import org.xvm.asm.MethodStructure;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.runtime.ClassTemplate;
import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.TemplateRegistry;

import org.xvm.runtime.template.xEnum;
import org.xvm.runtime.template.xException;

import org.xvm.runtime.template.numbers.xUInt8;

import org.xvm.runtime.template._native.collections.arrays.BitView;
import org.xvm.runtime.template._native.collections.arrays.xRTDelegate.DelegateHandle;
import org.xvm.runtime.template._native.collections.arrays.xRTSlicingDelegate.SliceHandle;
import org.xvm.runtime.template._native.collections.arrays.xRTViewFromBitToByte;


/**
 * Native BitArray<Bit> implementation.
 */
public class xBitArray
        extends BitBasedArray
    {
    public static xBitArray INSTANCE;

    public xBitArray(TemplateRegistry templates, ClassStructure structure, boolean fInstance)
        {
        super(templates, structure);

        if (fInstance)
            {
            INSTANCE = this;
            }
        }

    @Override
    public void initNative()
        {
        ClassTemplate mixin = f_templates.getTemplate("collections.arrays.BitArray");

        mixin.markNativeMethod("asByteArray", VOID, null);
        mixin.markNativeMethod("toUInt8", VOID, null);
        mixin.markNativeMethod("toByteArray", null, null);

        getCanonicalType().invalidateTypeInfo();
        }

    @Override
    public TypeConstant getCanonicalType()
        {
        return pool().typeBitArray();
        }

    @Override
    public int invokeNative1(Frame frame, MethodStructure method, ObjectHandle hTarget,
                             ObjectHandle hArg, int iReturn)
        {
        switch (method.getName())
            {
            case "toByteArray":
                {
                ArrayHandle hArray = (ArrayHandle) hTarget;
                long        cBits  = hArray.m_hDelegate.m_cSize;

                if (cBits % 8 != 0)
                    {
                    return frame.raiseException(
                        xException.illegalArgument(frame, "Invalid array size: " + cBits));
                    }

                Mutability mutability = hArg == ObjectHandle.DEFAULT
                        ? Mutability.Constant
                        : Mutability.values()[((xEnum.EnumHandle) hArg).getOrdinal()];

                byte[] aBits = getBits(hArray);

                return frame.assignValue(iReturn, xArray.makeByteArrayHandle(aBits, mutability));
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
            case "asByteArray":
                {
                ArrayHandle hArray = (ArrayHandle) hTarget;
                long        cBits  = hArray.m_hDelegate.m_cSize;
                if (cBits % 8 != 0)
                    {
                    return frame.raiseException(xException.outOfBounds(
                            frame, "Invalid array size: " + cBits));
                    }

                Mutability     mutability = hArray.m_mutability;
                DelegateHandle hDelegate  = xRTViewFromBitToByte.INSTANCE.createBitViewDelegate(
                        hArray.m_hDelegate, frame.poolContext().typeByte(), mutability);

                return frame.assignValue(iReturn, new ArrayHandle(
                        xByteArray.INSTANCE.getCanonicalClass(), hDelegate, mutability));
                }

            case "toUInt8":
                {
                ArrayHandle hArray = (ArrayHandle) hTarget;
                byte[]      abBits = getBits(hArray);
                long        cBits  = hArray.m_hDelegate.m_cSize;

                switch ((int) cBits)
                    {
                    case 0:
                        return frame.assignValue(iReturn, xUInt8.INSTANCE.makeJavaLong(0));

                    case 1: case 2: case 3: case 4: case 5: case 6: case 7:
                        return frame.assignValue(iReturn, xUInt8.INSTANCE.makeJavaLong(
                            (abBits[0] & 0xFF) >>> (8 - cBits)));

                    case 8:
                        return frame.assignValue(iReturn, xUInt8.INSTANCE.makeJavaLong(abBits[0]));

                    default:
                        return frame.raiseException(xException.outOfBounds(
                                frame, "Array is too big: " + cBits));
                    }
                }
            }

        return super.invokeNativeN(frame, method, hTarget, ahArg, iReturn);
        }

    /**
     * Extract a array of bits from the ArrayDelegate<Bit> handle.
     */
    public static byte[] getBits(ArrayHandle hArray)
        {
        DelegateHandle hDelegate = hArray.m_hDelegate;

        long    cSize    = hDelegate.m_cSize;
        long    ofStart  = 0;
        boolean fReverse = false;

        if (hDelegate instanceof SliceHandle)
            {
            SliceHandle hSlice = (SliceHandle) hDelegate;
            hDelegate = hSlice.f_hSource;
            ofStart   = hSlice.f_ofStart;
            fReverse  = hSlice.f_fReverse;
            }

        ClassTemplate tDelegate = hDelegate.getTemplate();
        if (tDelegate instanceof BitView)
            {
            return ((BitView) tDelegate).getBits(hDelegate, ofStart, cSize, fReverse);
            }
        throw new UnsupportedOperationException();
        }

    /**
     * Copy bytes from the specified array.
     */
    public static void setBits(ArrayHandle hArray, byte[] abVal)
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
            BitView tView = (BitView) tDelegate;

            for (int i = 0, c = abVal.length; i < c; i++)
                {
                tView.assignByte(hDelegate, (ofStart/8) + i, abVal[i]);
                }
            }
        throw new UnsupportedOperationException();
        }
    }
