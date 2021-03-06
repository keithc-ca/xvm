package org.xvm.compiler.ast;


import org.xvm.asm.Constant;
import org.xvm.asm.ErrorListener;
import org.xvm.asm.MethodStructure.Code;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.asm.op.Jump;
import org.xvm.asm.op.Label;

import org.xvm.compiler.Compiler;
import org.xvm.compiler.Token;

import org.xvm.util.Severity;


/**
 * The trailing "else" expression for any short-circuited expressions that precede it:
 *
 * <ul>
 * <li><tt>COLON: ":"</tt> - an "else" for nullability checks</li>
 * </ul>
 */
public class ElseExpression
        extends BiExpression
    {
    // ----- constructors --------------------------------------------------------------------------

    public ElseExpression(Expression expr1, Token operator, Expression expr2)
        {
        super(expr1, operator, expr2);
        }


    // ----- accessors -----------------------------------------------------------------------------


    // ----- compilation ---------------------------------------------------------------------------

    @Override
    protected boolean hasSingleValueImpl()
        {
        return false;
        }

    @Override
    protected boolean hasMultiValueImpl()
        {
        return true;
        }

    @Override
    public TypeConstant[] getImplicitTypes(Context ctx)
        {
        TypeConstant[] atype1 = expr1.getImplicitTypes(ctx);
        if (!expr2.isCompletable())
            {
            // a non-completable expression (e.g. "parent? : assert") doesn't contribute to the type
            return atype1;
            }

        TypeConstant[] atype2 = expr2.getImplicitTypes(ctx);
        if (atype1 == null || atype1.length == 0 || atype2 == null || atype2.length == 0)
            {
            return TypeConstant.NO_TYPES;
            }

        return selectCommonTypes(atype1, atype2);
        }

    @Override
    public TypeFit testFitMulti(Context ctx, TypeConstant[] atypeRequired, ErrorListener errs)
        {
        TypeFit fit = expr1.testFitMulti(ctx, atypeRequired, errs);
        if (fit.isFit() && expr2.isCompletable())
            {
            fit.combineWith(expr2.testFitMulti(ctx, atypeRequired, errs));
            }
        return fit;
        }

    @Override
    protected Expression validateMulti(Context ctx, TypeConstant[] atypeRequired, ErrorListener errs)
        {
        Expression expr1New = expr1.validateMulti(ctx, atypeRequired, errs);

        if (expr1New == null)
            {
            return null;
            }

        expr1 = expr1New;

        TypeConstant[] atype1 = expr1New.getTypes();
        TypeConstant[] atype2Req = atype1 == null
                ? null
                : selectCommonTypes(atype1, new TypeConstant[atype1.length]);

        if (atypeRequired != null && atypeRequired.length > 0 &&
                (atype2Req == null || !expr2.testFitMulti(ctx, atype2Req, null).isFit()))
            {
            atype2Req = atypeRequired;
            }

        // TODO CP: this is a temporary solution; simply ignore the impact of the "else"
        Context    ctx2     = ctx.enter();
        Expression expr2New = expr2.validateMulti(ctx2, atype2Req, errs);

        ctx2.discard();

        if (expr2New == null)
            {
            return null;
            }
        expr2 = expr2New;

        if (!expr1New.isShortCircuiting())
            {
            expr1New.log(errs, Severity.ERROR, Compiler.SHORT_CIRCUIT_REQUIRED);
            return replaceThisWith(expr1New);
            }

        TypeConstant[] atype2      = expr2New.getTypes();
        TypeConstant[] atypeResult = selectCommonTypes(atype1, atype2);

        Constant[] aconstVal = null;
        if (expr1New.isConstant())
            {
            aconstVal = expr1New.toConstants();
            }

        if (m_labelElse != null)
            {
            m_labelElse.restoreNarrowed(ctx);
            }

        return finishValidations(ctx, atypeRequired, atypeResult, TypeFit.Fit, aconstVal, errs);
        }

    @Override
    public boolean isShortCircuiting()
        {
        // this expression "grounds" any short circuit that happens on the left side of the ":"
        return expr2.isShortCircuiting();
        }

    @Override
    public boolean isCompletable()
        {
        // these can complete if the first expression can complete, because the result can
        // be calculated from the first expression, depending on what its answer is
        return expr1.isCompletable();
        }

    @Override
    protected boolean allowsShortCircuit(AstNode nodeChild)
        {
        return nodeChild == expr1 || super.allowsShortCircuit(nodeChild);
        }

    @Override
    protected Label ensureShortCircuitLabel(AstNode nodeOrigin, Context ctxOrigin)
        {
        AstNode nodeChild = findChild(nodeOrigin);
        if (nodeChild != expr1)
            {
            assert nodeChild == expr2;
            return super.ensureShortCircuitLabel(nodeOrigin, ctxOrigin);
            }

        // generate a "grounding" target label for the "left side child expression"
        Label label = m_labelElse;
        if (label == null)
            {
            m_nLabel    = ++m_nCounter;
            m_labelElse = label = new Label("else_:_" + m_nLabel);
            }
        return label;
        }

    @Override
    public void generateAssignments(Context ctx, Code code, Assignable[] aLVal, ErrorListener errs)
        {
        if (isConstant())
            {
            super.generateAssignments(ctx, code, aLVal, errs);
            return;
            }

        expr1.generateAssignments(ctx, code, aLVal, errs);

        if (m_labelElse != null)
            {
            Label labelEnd = new Label("end_:_" + m_nLabel);
            code.add(new Jump(labelEnd));
            code.add(m_labelElse);
            expr2.generateAssignments(ctx, code, aLVal, errs);
            code.add(labelEnd);
            }
        }


    // ----- fields --------------------------------------------------------------------------------

    private static    int   m_nCounter;
    private transient int   m_nLabel;
    private transient Label m_labelElse;
    }
