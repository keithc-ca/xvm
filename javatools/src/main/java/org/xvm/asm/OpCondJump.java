package org.xvm.asm;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import java.util.List;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.asm.op.Label;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.ExceptionHandle;
import org.xvm.runtime.Utils;

import static org.xvm.util.Handy.readPackedInt;
import static org.xvm.util.Handy.writePackedLong;


/**
 * Base class for conditional jump (JMP_*) op-codes.
 */
public abstract class OpCondJump
        extends Op {
    /**
     * Construct a unary conditional JMP_ op.
     *
     * @param arg  a value Argument
     * @param op   the op to jump to
     */
    protected OpCondJump(Argument arg, Op op) {
        assert !hasSecondArgument() && !isBinaryOp();
        m_argVal = arg;
        m_opDest = op;
    }

    /**
     * Construct a binary conditional JMP_ op.
     *
     * @param arg   a value Argument
     * @param arg2  a second value Argument
     * @param op    the op to jump to
     */
    protected OpCondJump(Argument arg, Argument arg2, Op op) {
        assert hasSecondArgument() && !isBinaryOp();
        m_argVal  = arg;
        m_argVal2 = arg2;
        m_opDest  = op;
    }

    /**
     * Construct a binary conditional JMP_ op.
     *
     * @param type  the compile-time type
     * @param arg   a value Argument
     * @param arg2  a second value Argument
     * @param op    the op to jump to
     */
    protected OpCondJump(TypeConstant type, Argument arg, Argument arg2, Op op) {
        assert isBinaryOp();
        m_typeCommon = type;
        m_argVal     = arg;
        m_argVal2    = arg2;
        m_opDest     = op;
    }

    /**
     * Deserialization constructor.
     *
     * @param in      the DataInput to read from
     * @param aconst  an array of constants used within the method
     */
    protected OpCondJump(DataInput in, Constant[] aconst)
            throws IOException {
        if (isBinaryOp()) {
            m_nType = readPackedInt(in);
        }
        m_nArg = readPackedInt(in);
        if (hasSecondArgument()) {
            m_nArg2 = readPackedInt(in);
        }
        m_ofJmp = readPackedInt(in);
    }

    @Override
    public void write(DataOutput out, ConstantRegistry registry)
            throws IOException {
        super.write(out, registry);

        if (m_typeCommon != null) {
            m_nType = encodeArgument(m_typeCommon, registry);
            m_typeCommon = null;
        }
        if (m_argVal != null) {
            m_nArg = encodeArgument(m_argVal, registry);
        }
        if (m_argVal2 != null) {
            m_nArg2 = encodeArgument(m_argVal2, registry);
        }

        if (isBinaryOp()) {
            writePackedLong(out, m_nType);
        }
        writePackedLong(out, m_nArg);
        if (hasSecondArgument()) {
            writePackedLong(out, m_nArg2);
        }
        writePackedLong(out, m_ofJmp);
    }

    @Override
    public void resolveAddresses(Op[] aop) {
        if (m_opDest == null) {
            m_ofJmp  = adjustRelativeAddress(aop, m_ofJmp);
            m_opDest = aop[getAddress() + m_ofJmp];
        } else {
            m_ofJmp = calcRelativeAddress(m_opDest);
        }
        m_cExits = calcExits(m_opDest);
    }

    /**
     * @return true iff the op is a binary operator
     */
    protected boolean isBinaryOp() {
        return false;
    }

    /**
     * @return true iff the op has two arguments
     */
    protected boolean hasSecondArgument() {
        return isBinaryOp();
    }

    @Override
    public int process(Frame frame, int iPC) {
        return isBinaryOp() ? processBinaryOp(frame, iPC) : processUnaryOp(frame, iPC);
    }

    protected int processUnaryOp(Frame frame, int iPC) {
        try {
            ObjectHandle hValue = frame.getArgument(m_nArg);

            return isDeferred(hValue)
                    ? hValue.proceed(frame, frameCaller ->
                        completeUnaryOp(frameCaller, iPC, frameCaller.popStack()))
                    : completeUnaryOp(frame, iPC, hValue);
        } catch (ExceptionHandle.WrapperException e) {
            return frame.raiseException(e);
        }
    }

    protected int processBinaryOp(Frame frame, int iPC) {
        try {
            ObjectHandle[] ahArg      = frame.getArguments(new int[]{m_nArg, m_nArg2}, 2);
            TypeConstant   typeCommon = calculateCommonType(frame);

            if (anyDeferred(ahArg)) {
                Frame.Continuation stepNext = frameCaller ->
                    completeBinaryOp(frame, iPC, typeCommon, ahArg[0], ahArg[1]);

                return new Utils.GetArguments(ahArg, stepNext).doNext(frame);
            }

            return completeBinaryOp(frame, iPC, typeCommon, ahArg[0], ahArg[1]);
        } catch (ExceptionHandle.WrapperException e) {
            return frame.raiseException(e);
        }
    }

    protected TypeConstant calculateCommonType(Frame frame) {
        TypeConstant typeCommon = m_typeCommon;
        if (typeCommon == null) {
            m_typeCommon = typeCommon = (TypeConstant) frame.getConstant(m_nType);
        }
        return frame.resolveType(typeCommon);
    }

    /**
     * A completion of a unary op; must be overridden by all binary ops.
     */
    protected int completeUnaryOp(Frame frame, int iPC, ObjectHandle hValue) {
        throw new UnsupportedOperationException();
    }

    /**
     * A completion of a binary op; must be overridden by all binary ops.
     */
    protected int completeBinaryOp(Frame frame, int iPC, TypeConstant type,
                                   ObjectHandle hValue1, ObjectHandle hValue2) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void markReachable(Op[] aop) {
        super.markReachable(aop);

        m_opDest = findDestinationOp(aop, m_ofJmp);
        m_ofJmp  = calcRelativeAddress(m_opDest);
    }

    @Override
    public boolean checkRedundant(Op[] aop) {
        if (m_ofJmp == 1) {
            markRedundant();
            return true;
        }

        return false;
    }

    @Override
    public boolean branches(Op[] aop, List<Integer> list) {
        list.add(getRelativeAddress());
        return true;
    }

    /**
     * @return the number of instructions to jump (may be negative)
     */
    public int getRelativeAddress() {
        return m_ofJmp;
    }

    @Override
    public void registerConstants(ConstantRegistry registry) {
        if (isBinaryOp()) {
            m_typeCommon = (TypeConstant) registerArgument(m_typeCommon, registry);
        }
        m_argVal = registerArgument(m_argVal, registry);
        if (hasSecondArgument()) {
            m_argVal2 = registerArgument(m_argVal2, registry);
        }
    }

    /**
     * @return a String that identifies the argument, for debugging purposes
     */
    protected String getArgDesc() {
        return Argument.toIdString(m_argVal, m_nArg);
    }

    /**
     * @return a String that identifies the second argument, for debugging purposes
     */
    protected String getArg2Desc() {
        assert hasSecondArgument();
        return Argument.toIdString(m_argVal2, m_nArg2);
    }

    /**
     * @return a String to use for debugging to denote the destination of the jump
     */
    protected String getLabelDesc() {
        if (m_opDest instanceof Label) {
            return ((Label) m_opDest).getName();
        }
        else if (m_ofJmp != 0) {
            return (m_ofJmp > 0 ? "+" : "") + m_ofJmp;
        }
        else if (m_opDest != null) {
            return "-> " + m_opDest;
        } else {
            return "???";
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(toName(getOpCode()))
          .append(' ');

        if (isBinaryOp()) {
           sb.append(Argument.toIdString(m_typeCommon, m_nType))
             .append(", ");
        }

        sb.append(getArgDesc());

        if (hasSecondArgument()) {
            sb.append(", ")
              .append(getArg2Desc());
        }

        sb.append(", ")
          .append(getLabelDesc());

        return sb.toString();
    }

    protected int m_nType;
    protected int m_nArg;
    protected int m_nArg2;
    protected int m_ofJmp;

    protected TypeConstant m_typeCommon;    // the type to use for the comparison
    private   Argument     m_argVal;
    private   Argument     m_argVal2;
    private   Op           m_opDest;

    // number of exits to simulate on the jump
    protected transient int m_cExits;
}
