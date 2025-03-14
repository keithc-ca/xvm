package org.xvm.xec.ecstasy;

import org.xvm.XEC;
import org.xvm.util.Ary;
import org.xvm.util.SB;
import org.xvm.xec.XTC;
import org.xvm.xec.ecstasy.collections.Array.Mutability;
import org.xvm.xec.ecstasy.collections.Hashable;
import org.xvm.xec.ecstasy.numbers.Int64;
import org.xvm.xec.ecstasy.text.Char;
import org.xvm.xec.ecstasy.text.Stringable;
import org.xvm.xrun.Never;
import org.xvm.xtc.*;


/** The Java Const class, implementing an XTC hidden internal class.
    The XTC Const is an *XTC interface*, not an XTC class, but it has many class-like properties.
    The XTC Const interface is thus treated like this Java Class.
 */
public class Const extends XTC
  implements org.xvm.xec.ecstasy.Orderable, // XTC comparable adds compare,equals
             Hashable,                      // XTC hashCode
             Stringable                     // has appendTo
{
  public static final Const GOLD = new Const(null);
  public Const() {}             // Explicit no-arg-no-work constructor
  public Const(Never n) {}      // Forced   no-arg-no-work constructor


  /* Generate:
     @Override public String toString() {
       SB sb = new SB().p("(");
       sb.p(fld0.toString()).p(",");
       sb.p( fld_prim      ).p(",");
       sb.p(fldN.toString()).p(");");
       return sb.toString();
     }
  */
  public static void make_toString( ClassPart clz, SB sb ) {
    sb.ip("// Default toString\n");
    sb.ip("@Override public String toString() {\n").ii();
    sb.ip("StringBuilder sb = new StringBuilder().append(\"(\");\n");
    Ary<PropPart> pps = new Ary<>(PropPart.class);
    // TODO: Need a static cutout test
    for( Part p : clz._name2kid.values() )
      if( p instanceof PropPart prop )
        pps.setX(prop._order,prop);
    for( int i=0; i<pps._len; i++ ) {
      sb.ip("sb.append(").p(pps._es[i]._name);
      if( !xeq(pps._es[i]) ) sb.p(".toString()");
      sb.p(").append(\"").p(i<pps._len-1 ? "," : ")").p("\");\n");
    }
    sb.ip("return sb.toString();\n").di();
    sb.ip("}\n");
  };

  private static boolean xeq(PropPart p) {
    return XType.xtype(p._con,false).is_jdk();
  }

  // --- Default Implementations ----------------------------------------------

  // Default mutability
  public Mutability mutability$get() { return Mutability.Constant; }
  public int mutability$getOrd() { return Mutability.Constant.ordinal(); }


  // Subclasses that use this must override
  @Override public boolean equals( XTC x0, XTC x1 ) { assert x0.getClass() != x1.getClass(); return false; }

  public static <E extends Const> boolean equals$Const(XTC gold, E c0, E c1 ) {
    // Lost all type info, need to make sure same subclass
    return c0.getClass() == c1.getClass() && c0.equals(c1);
  }

  public static boolean equals$Const(XTC gold, Const c0, long i ) {
    // Lost all type info, need to make sure same subclass
    return c0 instanceof Int64 i0 && i0._i == i;
  }
  public static boolean equals$Const(XTC gold, Const c0, char c ) {
    // Lost all type info, need to make sure same subclass
    return c0 instanceof Char i0 && i0._i == c;
  }

  public static boolean  equals$Const(XTC gold, XTC c0, XTC c1 ) { return equals$Const(gold,(Const)c0,(Const)c1); }

  public static boolean  equals$Const(XTC gold, char c0, char c1 ) { return c0==c1; }
  public static Ordered compare$Const(XTC gold, char c0, char c1 ) {
    return c0==c1 ? Ordered.Equal : (c0 < c1 ? Ordered.Lesser : Ordered.Greater);
  }
  public static boolean  equals$Const(org.xvm.xec.ecstasy.text.String gold, String c0, String c1 ) { throw XEC.TODO(); }
  public static Ordered compare$Const(org.xvm.xec.ecstasy.text.String gold, String s0, String s1 ) { return gold.compare(s0,s1); }

  public static boolean  equals$String(XTC gold, String s0, String s1 ) { return s0.equals(s1); }
  public static Ordered compare$String(XTC gold, String s0, String s1 ) { return org.xvm.xec.ecstasy.text.String.GOLD.compare(s0,s1); }

  @Override public Const freeze( boolean inPlace ) { assert mutability$get() == Mutability.Constant; return this; }
}
