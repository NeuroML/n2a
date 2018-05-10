/*
Copyright 2013-2018 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.language;

import gov.sandia.n2a.eqset.EquationEntry;
import gov.sandia.n2a.eqset.Variable;
import gov.sandia.n2a.eqset.VariableReference;
import gov.sandia.n2a.language.parse.SimpleNode;
import gov.sandia.n2a.language.type.Instance;
import gov.sandia.n2a.language.type.Scalar;

public class AccessVariable extends Operator
{
    public String name; // only needed to resolve the variable (since we will abandon the AST node)
    public VariableReference reference;  // non-null when this node has been resolved in the context of an EquationSet

    public AccessVariable ()
    {
    }

    public AccessVariable (String name)
    {
        this.name = name;
    }

    public AccessVariable (VariableReference reference)
    {
        this.reference = reference;
        name = reference.variable.nameString ();
    }

    public int getOrder ()
    {
        String temp = name;
        int order = 0;
        while (temp.endsWith ("'"))
        {
            order++;
            temp = temp.substring (0, temp.length () - 1);
        }
        return order;
    }

    public String getName ()
    {
        String[] pieces = name.split ("'", 2);
        return pieces[0];
    }

    public void getOperandsFrom (SimpleNode node)
    {
        name = node.jjtGetValue ().toString ();
    }

    public Operator simplify (Variable from)
    {
        if (reference == null  ||  reference.variable == null) return this;  // unresolved!
        Variable v = reference.variable;
        if (v.name.equals ("$init")  ||  v.name.equals ("$live")) return this;  // specifically prevent $init and $live from being replaced by a Constant
        if (v.hasAttribute ("externalWrite")) return this;  // A variable may locally evaluate to a constant, yet be subject to change from outside equations.
        if (v.equations.size () != 1) return this;
        EquationEntry e = v.equations.first ();
        if (e.expression == null) return this;
        if (e.condition != null)
        {
            if (! (e.condition instanceof Constant)) return this;
            // Check for nonzero constant
            Type value = ((Constant) e.condition).value;
            if (! (value instanceof Scalar)  ||  ((Scalar) value).value == 0) return this;  // This second condition should be eliminated by Variable.simplify(), but until it is, don't do anything here.
        }
        if (e.expression instanceof Constant)
        {
            from.removeDependencyOn (v);
            from.changed = true;
            return e.expression;
        }

        // Attempt to simplify expression, and maybe get a Constant
        Variable p = from;
        while (p != null)
        {
            if (p == v) return this;  // can't simplify, because we've already visited this variable
            p = p.visited;
        }
        v.visited = from;
        e.expression = e.expression.simplify (v);
        if (e.expression instanceof Constant)
        {
            from.removeDependencyOn (v);
            from.changed = true;
            return e.expression;
        }
        if (e.expression instanceof AccessVariable)  // Our variable is simply an alias for another variable, so grab the other variable instead.
        {
            AccessVariable av = (AccessVariable) e.expression;
            Variable v2 = av.reference.variable;
            if (v2 == v) return this;
            if (v2.hasAttribute ("temporary")  &&  v2.container != from.container) return this;  // Can't reference a temporary outside the current equation set.
            // Note: Folding an aliased variable will very likely remove one or more steps of delay in the movement of values through an equation set.
            // This might go against the user's intention. The folding can be blocked by adding a condition
            reference = av.reference;
            name      = av.reference.variable.nameString ();
            from.removeDependencyOn (v);
            from.addDependencyOn (v2);
            from.changed = true;
        }
        return this;
    }

    public void determineExponent (Variable from)
    {
        Variable v = reference.variable;
        if (v.exponentLast != Integer.MIN_VALUE) updateExponent (from, v.exponentLast);
        else                                     updateExponent (from, v.exponent);
    }

    public Type eval (Instance instance)
    {
        return instance.get (reference);
    }

    public String toString ()
    {
        return name;
    }

    public boolean equals (Object that)
    {
        if (! (that instanceof AccessVariable)) return false;
        AccessVariable a = (AccessVariable) that;

        if (reference != null  &&  a.reference != null) return reference.variable == a.reference.variable;
        return name.equals (a.name);
    }
}
