/*
Copyright 2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.language.function;

import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.TreeMap;

import gov.sandia.n2a.backend.internal.InstanceTemporaries;
import gov.sandia.n2a.backend.internal.Simulator;
import gov.sandia.n2a.eqset.EquationSet.ExponentContext;
import gov.sandia.n2a.language.Function;
import gov.sandia.n2a.language.Operator;
import gov.sandia.n2a.language.Type;
import gov.sandia.n2a.language.type.Instance;
import gov.sandia.n2a.language.type.Scalar;

public class Delay extends Function
{
    public int index;  // For internal backend, the position in valuesObject of the buffer object. For C backend, the suffix of the buffer object name in the current class.

    public static Factory factory ()
    {
        return new Factory ()
        {
            public String name ()
            {
                return "delay";
            }

            public Operator createInstance ()
            {
                return new Delay ();
            }
        };
    }

    public boolean canBeConstant ()
    {
        return false;
    }

    public void determineExponent (ExponentContext context)
    {
        for (int i = 0; i < operands.length; i++) operands[i].determineExponent (context);

        Operator value = operands[0];  // value to delay
        updateExponent (context, value.exponent, value.center);

        // Need to record the exponent for time, since we avoid passing context to determineExponentNext().
        if (operands.length > 1) operands[1].exponentNext = context.exponentTime;

        // Assume that defaultValue is representable in the same range as value,
        // so don't bother incorporating it into the estimate of our output exponent.
    }

    public void determineExponentNext ()
    {
        exponent = exponentNext;  // Assumes that we simply output the same exponent as our inputs.

        Operator value = operands[0];
        value.exponentNext = exponentNext;  // Passes the required exponent down to operands.
        value.determineExponentNext ();

        if (operands.length > 1)
        {
            Operator delta = operands[1];
            // delta.exponentNext already set in determineExponent()
            delta.determineExponentNext ();
        }

        if (operands.length > 2)
        {
            Operator defaultValue = operands[2];
            defaultValue.exponentNext = exponentNext;
            defaultValue.determineExponentNext ();
        }
    }

    public void determineUnit (boolean fatal) throws Exception
    {
        operands[0].determineUnit (fatal);
        unit = operands[0].unit;
    }

    public static class DelayBuffer
    {
        double value;  // Return value is not strictly immutable, but generally treated that way, so we will use this repeatedly.
        NavigableMap<Double,Double> buffer = new TreeMap<Double,Double> ();

        public void step (double now, double delay, double value)
        {
            buffer.put (now + delay, value);
            while (buffer.firstKey () <= now)
            {
                Entry<Double,Double> e = buffer.pollFirstEntry ();
                this.value = e.getValue ();
            }
        }
    }

    public Type eval (Instance context)
    {
        Type tempValue = operands[0].eval (context);
        Simulator simulator = Simulator.instance.get ();
        if (simulator == null) return tempValue;
        if (operands.length < 2) return tempValue;  // Zero delay, which generally introduces one cycle of delay in an expression like: A = delay(B)

        double value = ((Scalar) tempValue).value;
        double delay = ((Scalar) operands[1].eval (context)).value;

        Instance wrapped = ((InstanceTemporaries) context).wrapped;  // Unpack the main instance data, to access buffer.
        DelayBuffer buffer = (DelayBuffer) wrapped.valuesObject[index];
        if (buffer == null)
        {
            wrapped.valuesObject[index] = buffer = new DelayBuffer ();
            if (operands.length > 2) buffer.value = ((Scalar) operands[2].eval (context)).value;
            else                     buffer.value = 0;
        }
        buffer.step (simulator.currentEvent.t, delay, value);
        return new Scalar (buffer.value);
    }

    public String toString ()
    {
        return "delay";
    }
}
