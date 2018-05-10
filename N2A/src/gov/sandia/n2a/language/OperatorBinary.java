/*
Copyright 2013-2018 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.language;

import gov.sandia.n2a.eqset.Equality;
import gov.sandia.n2a.eqset.Variable;
import gov.sandia.n2a.language.parse.SimpleNode;

public class OperatorBinary extends Operator
{
    public Operator operand0;
    public Operator operand1;

    public void getOperandsFrom (SimpleNode node) throws ParseException
    {
        if (node.jjtGetNumChildren () != 2) throw new Error ("AST for operator has unexpected form");
        operand0 = Operator.getFrom ((SimpleNode) node.jjtGetChild (0));
        operand1 = Operator.getFrom ((SimpleNode) node.jjtGetChild (1));
    }

    public Operator deepCopy ()
    {
        OperatorBinary result = null;
        try
        {
            result = (OperatorBinary) this.clone ();
            result.operand0 = operand0.deepCopy ();
            result.operand1 = operand1.deepCopy ();
        }
        catch (CloneNotSupportedException e)
        {
        }
        return result;
    }

    public boolean isOutput ()
    {
        return operand0.isOutput ()  ||  operand1.isOutput ();
    }

    public void visit (Visitor visitor)
    {
        if (! visitor.visit (this)) return;
        operand0.visit (visitor);
        operand1.visit (visitor);
    }

    public Operator transform (Transformer transformer)
    {
        Operator result = transformer.transform (this);
        if (result != null) return result;
        operand0 = operand0.transform (transformer);
        operand1 = operand1.transform (transformer);
        return this;
    }

    public Operator simplify (Variable from)
    {
        operand0 = operand0.simplify (from);
        operand1 = operand1.simplify (from);
        if (operand0 instanceof Constant  &&  operand1 instanceof Constant)
        {
            from.changed = true;
            return new Constant (eval (null));
        }
        return this;
    }

    public void dumpExponents (String pad)
    {
        //System.out.println (pad + this + " " + exponentNext + " " + exponent);
        System.out.println (pad + this + " " + exponent);
        operand0.dumpExponents (pad + "  ");
        operand1.dumpExponents (pad + "  ");
    }

    public void render (Renderer renderer)
    {
        if (renderer.render (this)) return;

        // Left-hand child
        boolean useParens = false;
        if (operand0 instanceof OperatorBinary  ||  operand0 instanceof OperatorUnary)
        {
            useParens =    precedence () < operand0.precedence ()   // read "<" as "comes before" rather than "less"
                        ||    precedence () == operand0.precedence ()
                           && associativity () == Associativity.RIGHT_TO_LEFT;
        }
        if (useParens) renderer.result.append ("(");
        operand0.render (renderer);
        if (useParens) renderer.result.append (")");

        renderer.result.append (" " + toString () + " ");

        // Right-hand child
        useParens = false;
        if (operand1 instanceof OperatorBinary  ||  operand1 instanceof OperatorUnary)
        {
            useParens =    precedence () < operand1.precedence ()
                        ||    precedence () == operand1.precedence ()
                           && associativity () == Associativity.LEFT_TO_RIGHT;
        }
        if (useParens) renderer.result.append ("(");
        operand1.render (renderer);
        if (useParens) renderer.result.append (")");
    }

    public void solve (Equality statement) throws EvaluationException
    {
        if (operand0.contains (statement.target))  // need left-inverse
        {
            statement.rhs = inverse (operand1, statement.rhs, false);
            statement.lhs = operand0;
        }
        else  // need right-inverse
        {
            statement.rhs = inverse (operand0, statement.rhs, true);
            statement.lhs = operand1;
        }
    }

    public Operator inverse (Operator lhs, Operator rhs, boolean right) throws EvaluationException
    {
        throw new EvaluationException ("Can't invert this operator.");
    }

    public boolean equals (Object that)
    {
        if (! (that instanceof OperatorBinary)) return false;
        OperatorBinary o = (OperatorBinary) that;

        // TODO: For commutative operators, test all possible orderings.
        // Commutative operators may become a proper subclass of OperatorBinary.
        return operand0.equals (o.operand0)  &&  operand1.equals (o.operand1);
    }
}
